[English](README.md) | [繁體中文](README_TW.md)

# Bili Extended

An LSPosed module for the official Bilibili Android app (`tv.danmaku.bili`). It fixes the micro-stutter of Bilibili's built-in IJK player at high frame rates and playback speeds, lets you choose the renderer of the video-detail player, lifts the speed limit, shows the actual video and audio format on screen, and can pick the best audio track automatically. The official APK is not modified; everything is hooked at runtime.

- Supports Bilibili 9.13.0, 9.17.0, 9.18.0 and 9.19.0 (the native hooks check the IJK library's signatures and stay off when they do not match; other versions get only part of the Java features).
- Needs a framework that loads modules of the modern Xposed API (libxposed **API 102**), for example LSPosed 2.2.x; Android 7.0 / API 24 or later.
- Root-cause analysis and fix guide for Bilibili's player engineers (in Chinese): [docs/bilibili-fix-guide.md](docs/bilibili-fix-guide.md); the mechanisms added beyond Media3 (phase lock, cadence lock and others) and their measured gains (in Chinese): [docs/beyond-media3.md](docs/beyond-media3.md).

## Features

Every setting is changed **inside Bilibili**, in the entry **Bili Extended**, which appears in three places: the "more settings" panel on the right in full-screen playback (top of the "playback feedback" card), the ⋮ bottom menu of the video page, and Bilibili's own **Settings** page.

| Group | Setting | What it does |
|---|---|---|
| Smoothness | **Video renderer** (`Texture` / `Surface external` / `Surface`) | Which renderer the video-detail player uses. **`Surface external` is recommended**: it keeps zoom, rotation and screenshots, and every frame can carry a presentation timestamp. |
| Smoothness | **Media3 Vsync** (on by default) | Schedules and releases video frames with timestamps the way Media3 (ExoPlayer) does, at every speed: frames are handed over a fixed time ahead at 1x, picked with a cadence lock at higher speeds, and late frames are skipped by rule. |
| Smoothness | **Threshold before the planned presentation** (default 33 ms) | A frame is not handed over while it is still more than this ahead of its planned presentation; 1–50 ms. |
| Smoothness | **How late behind the audio a frame may be dropped** (default 30 ms) | A frame may be dropped only when it is later than this behind the audio; 1–50 ms. |
| Smoothness | **Surface for HDR videos** (on by default) | HDR videos switch to the direct `Surface` so the HDR metadata reaches the display (the external renderer turns HDR into SDR). |
| Smoothness | **Native-resolution output** (off by default; shown only on screens whose panel resolution is higher than the UI's) | The video goes to the display hardware at the panel's native resolution; the rest of the UI keeps its size. |
| Speed | **High speeds (up to 10x)** | Lifts the playback core's speed limit and adds 2.5x–10x at the top of the speed menu and the slow speeds 0.33x and 0.25x at the bottom. High speeds of high-frame-rate or 4K videos can exceed the decoder. |
| Speed | **Long-press speed** | 2–10x (in 0.5x steps); Bilibili's 3x when not changed. |
| Info overlay | **fps overlay** | Shows `On \| Avg 120fps \| 1%Low 83fps \| Surface Ext`: Media3 Vsync state, average fps, 1% low and renderer. |
| Info overlay | **Video / audio format** | The video format the decoder confirmed and the selected audio format (e.g. `HEVC \| Hi-Res 96KHz`). |
| Info overlay | **Position and font size** | Horizontal / vertical position of the overlay (one pair for landscape, one for portrait) and its font size. |
| Audio | **Pick the best audio quality** | Picks the highest-bandwidth track when separate DASH audio tracks exist. |
| Page behaviour | **Open vertical Story videos in the normal video page** | Re-routes `bilibili://story` links to the normal video page. |

The module also disables, by default, the performance sampling built into Bilibili's player: it shares the thread that delivers new-frame notifications, and each sample freezes the picture for 80–144 ms (see section B.5 of the fix guide).

Settings live only in Bilibili's own folder (`files/bilisurfacebridge/settings.txt`); the module app keeps no settings. The module writes only non-sensitive state to the log and never reads account data, cookies, video URLs or playback authorization values.

## Installation

1. Download and install the module APK from [GitHub Releases](https://github.com/XPRAMT/BiliExtended/releases), enable it in LSPosed and select only Bilibili as its scope.
2. Force-stop and reopen Bilibili, play a normal video, and open **Bili Extended** from the player menu to change the settings.
3. Later module updates need no Bilibili restart: install the new version even during playback (hot reload).

Rootless NPatch / LSPatch: tested with versions before 1.4.0; this API 102 version has not been tested with either.

## Scoring

Each round records 15 s with root Perfetto in full-screen playback and looks only at the actual presentation times of the video layer in SurfaceFlinger.

- **Analysis window**: the video layer's presentation span minus 1.5 s at each end.
- **A round passes on its settled part**: from 5 s after playback starts. The start-up transition after resuming from pause does not count.
- **The recording must be complete**: the recording covers (time span of SurfaceFlinger's VSYNC counters) at least 95 % of its length and contains SurfaceFlinger's per-frame data; an incomplete round does not count and has to be fixed and recorded again.

### Terms

- **Slot**: one refresh (VSync) of the panel. R = the measured panel refresh rate (slots per second), taken from the trace's VSYNC counters, not the nominal value.
- **Interval**: how many slots separate two consecutive presented pictures (rounded to whole slots).
- **F**: content frame rate × speed, i.e. how many different pictures should be shown per second.
- **The actual playback rate is taken to equal the requested speed**: in the settled part, the slope of every presented picture's content timestamp against its presentation time deviates from the requested speed by at most 0.73 % (0.25x–2x, 78 rounds of the original player and the module), so the allowances use F directly and are not adjusted per round.
- **Allowances are computed per round**: W and R are measured in that round, so the original player and the module (whose best rounds differ) can have different allowances for the same combination.
- **W**: the length of the analysis window (seconds).

### Three cases

The ratio of F to R decides the case; each has its own pass condition.

**1. Sparse: R / F is close to a whole number n (within 1 %)**, e.g. 4K60 1x holds every picture 2 slots, 1080p24 0.25x holds every picture 20 slots.

- **On-time rate** = intervals of exactly n slots ÷ all intervals.
- **Longer** = intervals of more than n slots; **shorter** = intervals of fewer than n slots.
- R / F is never exactly n, so a picture must now and then stay one slot longer (or shorter) to keep up with the audio; this is the physical limit:
  - allowed longer = ⌈W × (R − F × n)⌉ + 1 (0 when the bracket is negative, then + 1)
  - allowed shorter = ⌈W × (F × n − R)⌉ (0 when the bracket is negative)
- **Pass** = longer ≤ allowed longer and shorter ≤ allowed shorter. The on-time rate is only a ratio and does not decide the verdict by itself; low-frame-rate combinations have few intervals (about 120 in 8 s at 15 pictures per second), so a single longer interval lowers it by almost 1 %.

**2. Dense: F / R is close to a whole number k ≥ 2**, e.g. 4K120 2x (2 pictures per slot), 1080p24 10x.

- **Parity flip** = how often the content index of consecutive presented pictures changes parity (e.g. from even-numbered to odd-numbered pictures). Every dense combination in the table shows 2 pictures per slot (F ≈ 240).
- **Pass** = 0 parity flips. Holding the same picture one more slot (the phase stays) is the physical limit and allowed.

**3. Fractional**: every other ratio, e.g. 4K120 1.5x (3 pictures per 2 slots), 1080p30 3x.

- **Pacing deviation** = the largest distance of a presented picture from the ideal evenly spaced line (after removing a constant rate difference), in pictures or slots.
- **Pass** = pacing deviation ≤ 1. A deviation of 1 is one visible hitch.

**A round passes** when its settled part (from 5 s after playback starts) meets the condition of its case and the recording is complete. The pass / fail in the table's "result (settled part)" columns is this verdict.

## Test results

Module results use the `Surface external` renderer with the fps and stream-format overlays on; original-player results have the module's playback features off (see below) and both overlays off. Bilibili 9.18.0, offline downloads. **Tested in performance mode**: on the phone Bilibili is in game mode; on the tablet the minimum frequency of the big cluster (cpu4–6) is pinned at its maximum and every round checks before recording that the system is not throttling it. Each combination ran 2–4 rounds; the table shows the best round of each side (settled part).

- **Content / panel frames**: content frames = source frame rate × speed, i.e. how many different pictures there are per second. Above the panel refresh rate the panel can show only one picture per refresh; the number in brackets is what the panel can show, e.g. `180 (120)`. No brackets means the panel can show them all.
- **Measured frame rate**: new pictures the video layer actually presented per second in the settled part, from SurfaceFlinger's present records: (pictures − 1) ÷ time from the first to the last.

- **Phone**: Nubia NX809J (Snapdragon 8 Elite Gen 5, Android 16, panel measured at 120.28 Hz). Both the original player and the module were tested (the original passes only 1 of 23 combinations, 4K 60 fps at 0.333x).
- **Tablet**: Xiaomi Pad 6 Pro (Snapdragon 8+ Gen 1, HyperOS / Android 15, 120 Hz panel). Both the original player and the module were tested.
- **Original**: every module feature that changes playback is off (Media3 Vsync, the performance-sampling block, immediate external rendering, the deeper picture queue, renderer selection and so on); only the speed setting, the speed-limit lift and the measurement marks stay, and the renderer is Bilibili's own default (external rendering). Scored the same way as the module.

| Device | Video | Speed | Content / panel frames (fps) | Original: measured frame rate (fps) | Original: result (settled part) | Module: measured frame rate (fps) | Module: result (settled part) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Phone | 1080p 24 fps AV1 | 0.25x | 6 | 5.8 | Fail: on-time 51.16 %, longer 12 / allowed 3 | 6.0 | Pass: on-time 97.50 %, longer 1 / allowed 4
| Phone | 1080p 24 fps AV1 | 0.333x | 8 | 8.0 | Fail: on-time 41.38 %, longer 18 / allowed 5 | 8.0 | Pass: on-time 93.33 %, longer 4 / allowed 5
| Phone | 1080p 24 fps AV1 | 0.5x | 12 | 12.0 | Fail: on-time 46.24 %, longer 25 / allowed 4 | 12.0 | Pass: on-time 97.85 %, longer 2 / allowed 4
| Phone | 1080p 24 fps AV1 | 1x | 24 | 23.8 | Fail: on-time 70.71 %, longer 28 / allowed 4 | 24.0 | Pass: on-time 98.45 %, longer 3 / allowed 4
| Phone | 1080p 24 fps AV1 | 2x | 48 | 47.2 | Fail: pacing deviation 4.31 | 48.0 | Pass: pacing deviation 0.50
| Phone | 1080p 24 fps AV1 | 10x | 240 (120) | 115.6 | Fail: parity flips 337 | 120.0 | Pass: parity flips 0
| Phone | 4K 30 fps AV1 | 0.25x | 7.5 | 7.1 | Fail: on-time 54.90 %, longer 8 / allowed 4 | 7.5 | Pass: on-time 94.55 %, longer 3 / allowed 4
| Phone | 4K 30 fps AV1 | 0.333x | 10 | 10.0 | Fail: on-time 85.90 %, longer 7 / allowed 5 | 10.0 | Pass: on-time 96.10 %, longer 3 / allowed 5
| Phone | 4K 30 fps AV1 | 0.5x | 15 | 15.0 | Fail: on-time 77.69 %, longer 15 / allowed 4 | 15.0 | Pass: on-time 98.33 %, longer 2 / allowed 5
| Phone | 4K 30 fps AV1 | 1x | 30 | 30.0 | Fail: on-time 94.38 %, longer 8 / allowed 4 | 30.0 | Pass: on-time 99.19 %, longer 2 / allowed 4
| Phone | 4K 30 fps AV1 | 2x | 60 | 58.4 | Fail: on-time 89.92 %, longer 32 / allowed 4 | 60.0 | Pass: on-time 99.39 %, longer 3 / allowed 5
| Phone | 4K 30 fps AV1 | 8x | 240 (120) | 116.5 | Fail: parity flips 417 | 120.1 | Pass: parity flips 0
| Phone | 4K 60 fps AV1 | 0.25x | 15 | 14.5 | Fail: on-time 47.86 %, longer 27 / allowed 4 | 15.0 | Pass: on-time 100.00 %, longer 0 / allowed 4
| Phone | 4K 60 fps AV1 | 0.333x | 20 | 20.0 | Pass: on-time 98.73 %, longer 1 / allowed 3 | 20.0 | Pass: on-time 98.15 %, longer 3 / allowed 6
| Phone | 4K 60 fps AV1 | 0.5x | 30 | 30.0 | Fail: on-time 93.38 %, longer 5 / allowed 3 | 30.0 | Pass: on-time 99.18 %, longer 2 / allowed 5
| Phone | 4K 60 fps AV1 | 1x | 60 | 59.8 | Fail: on-time 96.81 %, longer 10 / allowed 4 | 60.0 | Pass: on-time 99.40 %, longer 3 / allowed 5
| Phone | 4K 60 fps AV1 | 2x | 120 | 105.8 | Fail: on-time 85.96 %, longer 125 / allowed 4 | 120.0 | Pass: on-time 99.70 %, longer 3 / allowed 5
| Phone | 4K 60 fps AV1 | 4x | 240 (120) | 118.3 | Fail: parity flips 273 | 120.0 | Pass: parity flips 0
| Phone | 4K 120 fps AVC | 0.25x | 30 | 26.1 | Fail: on-time 61.54 %, longer 7 / allowed 2 | 30.1 | Pass: on-time 100.00 %, longer 0 / allowed 5
| Phone | 4K 120 fps AVC | 0.333x | 40 | 39.8 | Fail: on-time 84.04 %, longer 29 / allowed 6 | 40.1 | Pass: on-time 99.70 %, longer 1 / allowed 6
| Phone | 4K 120 fps AVC | 0.5x | 60 | 60.0 | Fail: on-time 85.60 %, longer 37 / allowed 4 | 60.0 | Pass: on-time 99.20 %, longer 4 / allowed 5
| Phone | 4K 120 fps AVC | 1x | 120 | 107.8 | Fail: on-time 89.17 %, longer 98 / allowed 4 | 120.0 | Pass: on-time 99.70 %, longer 3 / allowed 5
| Phone | 4K 120 fps AVC | 2x | 240 (120) | 118.3 | Fail: parity flips 184 | 119.9 | Pass: parity flips 0
| Tablet | 4K 120 fps AVC | 0.25x | 30 | 25.1 | Fail: on-time 49.02 %, longer 66 / allowed 2 | 30.0 | Pass: on-time 100.00 %, longer 0 / allowed 1
| Tablet | 4K 120 fps AVC | 0.333x | 40 | 39.8 | Fail: on-time 46.79 %, longer 86 / allowed 3 | 39.9 | Pass: on-time 99.39 %, longer 2 / allowed 3
| Tablet | 4K 120 fps AVC | 0.5x | 60 | 60.0 | Fail: on-time 42.60 %, longer 129 / allowed 2 | 60.0 | Pass: on-time 100.00 %, longer 0 / allowed 2
| Tablet | 4K 120 fps AVC | 1x | 120 | 112.6 | Fail: on-time 94.22 %, longer 54 / allowed 2 | 119.7 | Pass: on-time 99.90 %, longer 1 / allowed 1
| Tablet | 4K 120 fps AVC | 1.25x | 150 (120) | 109.7 | Fail: pacing deviation 3.27 | 120.0 | Pass: pacing deviation 0.60 |
| Tablet | 4K 120 fps AVC | 1.5x | 180 (120) | 111.4 | Fail (decoder hardware limit): pacing deviation 4.79 | 108.7 | Fail (decoder hardware limit): pacing deviation 3.56
| Tablet | 4K 60 fps AVC | 0.25x | 15 | 14.3 | Fail: on-time 44.55 %, longer 32 / allowed 2 | 15.0 | Pass: on-time 100.00 %, longer 0 / allowed 2
| Tablet | 4K 60 fps AVC | 0.333x | 20 | 19.7 | Fail: on-time 66.25 %, longer 29 / allowed 2 | 20.0 | Pass: on-time 99.38 %, longer 1 / allowed 3
| Tablet | 4K 60 fps AVC | 0.5x | 30 | 30.0 | Fail: on-time 69.67 %, longer 37 / allowed 2 | 30.0 | Pass: on-time 100.00 %, longer 0 / allowed 2
| Tablet | 4K 60 fps AVC | 1x | 60 | 59.9 | Fail: on-time 63.56 %, longer 90 / allowed 1 | 60.0 | Pass: on-time 100.00 %, longer 0 / allowed 2
| Tablet | 4K 60 fps AVC | 1.5x | 90 | 88.0 | Fail: pacing deviation 3.56 | 90.0 | Pass: pacing deviation 0.35
| Tablet | 4K 60 fps AVC | 2x | 120 | 116.2 | Fail: on-time 96.78 %, longer 31 / allowed 2 | 120.0 | Pass: on-time 100.00 %, longer 0 / allowed 2
| Tablet | 4K 60 fps AVC | 3x | 180 (120) | 112.3 | Fail (decoder hardware limit): pacing deviation 8.28 | 107.5 | Fail (decoder hardware limit): pacing deviation 3.28
| Tablet | 1080p 30 fps HEVC | 0.5x | 15 | 15.0 | Fail: on-time 77.78 %, longer 14 / allowed 2 | 15.0 | Pass: on-time 100.00 %, longer 0 / allowed 2
| Tablet | 1080p 30 fps HEVC | 1x | 30 | 30.0 | Fail: on-time 89.39 %, longer 13 / allowed 2 | 30.0 | Pass: on-time 100.00 %, longer 0 / allowed 1
| Tablet | 1080p 30 fps HEVC | 2x | 60 | 58.4 | Fail: on-time 64.93 %, longer 89 / allowed 1 | 60.0 | Pass: on-time 100.00 %, longer 0 / allowed 2
| Tablet | 1080p 30 fps HEVC | 3x | 90 | 82.4 | Fail: pacing deviation 3.98 | 90.0 | Pass: pacing deviation 0.35
| Tablet | 1080p 30 fps HEVC | 4x | 120 | 103.9 | Fail: on-time 86.01 %, longer 120 / allowed 2 | 120.0 | Pass: on-time 100.00 %, longer 0 / allowed 1

Known limits: 4K at 180 pictures per second (1.5x, 3x) exceeds the tablet decoder's hardware capability; neither the original player nor the module passes.

## License

This project is licensed under [Apache-2.0](LICENSE). Parts of the frame scheduling are adapted from [AndroidX Media3](https://github.com/androidx/media) (Apache-2.0); see [NOTICE](NOTICE). Releases 1.0.0–1.3.2 and the first 1.5.0 build (versionCode 229) were published under GPL-3.0.
