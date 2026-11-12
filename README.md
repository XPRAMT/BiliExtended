[English](README.md) | [繁體中文](README_TW.md)

# Bili Extended

An LSPosed module for the official Bilibili Android app (`tv.danmaku.bili`). It fixes the micro-stutter of Bilibili's built-in IJK player at high frame rates and playback speeds, lets you choose the renderer of the video-detail player, lifts the speed limit, shows the actual video and audio format on screen, and can pick the best audio track automatically. The official APK is not modified; everything is hooked at runtime.

- Supports Bilibili 9.13.0, 9.17.0 and 9.18.0 (the native hooks check the IJK library's signatures and stay off when they do not match; other versions get only part of the Java features).
- Needs a framework that loads modules of the modern Xposed API (libxposed **API 102**), for example LSPosed 2.2.x; Android 7.0 / API 24 or later.
- Root-cause analysis and fix guide for Bilibili's player engineers (in Chinese): [docs/bilibili-fix-guide.md](docs/bilibili-fix-guide.md).

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
- **Actual frame rate F′**: source frame rate × actual playback rate. The actual playback rate is the slope of a linear fit of every presented picture's content timestamp against its presentation time in the analysis window. When a frame is dropped the next timestamp jumps ahead, so the slope does not change with drops.
- **W**: the length of the analysis window (seconds).

### Three cases

The ratio of F to R decides the case; each has its own pass condition.

**1. Sparse: R / F is close to a whole number n (within 1 %)**, e.g. 4K60 1x holds every picture 2 slots, 1080p24 0.25x holds every picture 20 slots.

- **On-time rate** = intervals of exactly n slots ÷ all intervals.
- **Longer** = intervals of more than n slots; **shorter** = intervals of fewer than n slots.
- R / F′ is never exactly n, so a picture must now and then stay one slot longer (or shorter) to keep up with the audio; this is the physical limit:
  - allowed longer = ⌈W × (R − F′ × n)⌉ + 1 (0 when the bracket is negative, then + 1)
  - allowed shorter = ⌈W × (F′ × n − R)⌉ (0 when the bracket is negative)
- **Pass** = longer ≤ allowed longer and shorter ≤ allowed shorter. The on-time rate is only a ratio and does not decide the verdict by itself; low-frame-rate combinations have few intervals (about 120 in 8 s at 15 pictures per second), so a single longer interval lowers it by almost 1 %.

**2. Dense: F / R is close to a whole number k ≥ 2**, e.g. 4K120 2x (2 pictures per slot), 1080p24 10x.

- **Parity flip** = how often the content index of consecutive presented pictures changes parity (e.g. from even-numbered to odd-numbered pictures). Every dense combination in the tables shows 2 pictures per slot (F ≈ 240).
- **Pass** = 0 parity flips. Holding the same picture one more slot (the phase stays) is the physical limit and allowed.

**3. Fractional**: every other ratio, e.g. 4K120 1.5x (3 pictures per 2 slots), 1080p30 3x.

- **Pacing deviation** = the largest distance of a presented picture from the ideal evenly spaced line (after removing a constant rate difference), in pictures or slots.
- **Pass** = pacing deviation ≤ 1. A deviation of 1 is one visible hitch.

**A round passes** when its settled part (from 5 s after playback starts) meets the condition of its case and the recording is complete. The "Result" column of the tables is this verdict.

## Test results

Module 229, Bilibili 9.18.0, offline downloads, `Surface external` renderer. **Tested in performance mode**: on the phone Bilibili is in game mode; on the tablet the minimum frequency of the big cluster (cpu4–6) is pinned at its maximum and every round checks before recording that the system is not throttling it. Each combination ran 2–4 rounds; the table shows the best round (settled part).

**Phone**: Nubia NX809J (Snapdragon 8 Elite Gen 5, Android 16, panel measured at 120.28 Hz)

| Video | Speed | Result | Settled-part metric |
|---|---|---|---|
| 1080p 24 fps AV1 | 0.25x | Pass | on-time 97.50 % |
| 1080p 24 fps AV1 | 0.333x | Pass | on-time 93.33 % |
| 1080p 24 fps AV1 | 0.5x | Pass | on-time 97.85 % |
| 1080p 24 fps AV1 | 1x | Pass | on-time 98.45 % |
| 1080p 24 fps AV1 | 2x | Pass | pacing deviation 0.50 |
| 1080p 24 fps AV1 | 10x | Pass | parity flips 0 |
| 4K 30 fps AV1 | 0.25x | Pass | on-time 94.55 % |
| 4K 30 fps AV1 | 0.333x | Pass | on-time 96.10 % |
| 4K 30 fps AV1 | 0.5x | Pass | on-time 98.33 % |
| 4K 30 fps AV1 | 1x | Pass | on-time 99.19 % |
| 4K 30 fps AV1 | 2x | Pass | on-time 99.39 % |
| 4K 30 fps AV1 | 8x | Pass | parity flips 0 |
| 4K 60 fps AV1 | 0.25x | Pass | on-time 100.00 % |
| 4K 60 fps AV1 | 0.333x | Pass | on-time 98.15 % |
| 4K 60 fps AV1 | 0.5x | Pass | on-time 99.18 % |
| 4K 60 fps AV1 | 1x | Pass | on-time 99.40 % |
| 4K 60 fps AV1 | 2x | Pass | on-time 99.70 % |
| 4K 60 fps AV1 | 4x | Pass | parity flips 0 |
| 4K 120 fps AVC | 0.25x | Pass | on-time 100.00 % |
| 4K 120 fps AVC | 0.333x | Pass | on-time 99.70 % |
| 4K 120 fps AVC | 0.5x | Pass | on-time 99.20 % |
| 4K 120 fps AVC | 1x | Pass | on-time 99.70 % |
| 4K 120 fps AVC | 2x | Pass | parity flips 0 |

**Tablet**: Xiaomi Pad 6 Pro (Snapdragon 8+ Gen 1, HyperOS / Android 15, 120 Hz panel)

| Video | Speed | Result | Settled-part metric |
|---|---|---|---|
| 4K 120 fps AVC | 0.25x | Pass | on-time 100.00 % |
| 4K 120 fps AVC | 0.333x | Pass | on-time 99.39 % |
| 4K 120 fps AVC | 0.5x | Pass | on-time 100.00 % |
| 4K 120 fps AVC | 1x | Pass | on-time 99.90 % |
| 4K 120 fps AVC | 1.5x | Fail (decoder hardware limit: 4K at 180 fps) | pacing deviation 3.56 |
| 4K 60 fps AVC | 0.25x | Pass | on-time 96.64 % |
| 4K 60 fps AVC | 0.333x | Pass | on-time 99.38 % |
| 4K 60 fps AVC | 0.5x | Pass | on-time 100.00 % |
| 4K 60 fps AVC | 1x | Pass | on-time 100.00 % |
| 4K 60 fps AVC | 1.5x | Pass | pacing deviation 0.35 |
| 4K 60 fps AVC | 2x | Pass | on-time 100.00 % |
| 4K 60 fps AVC | 3x | Fail (decoder hardware limit: 4K at 180 fps) | pacing deviation 3.28 |
| 1080p 30 fps HEVC | 0.5x | Pass | on-time 100.00 % |
| 1080p 30 fps HEVC | 1x | Pass | on-time 100.00 % |
| 1080p 30 fps HEVC | 2x | Pass | on-time 100.00 % |
| 1080p 30 fps HEVC | 3x | Pass | pacing deviation 0.35 |
| 1080p 30 fps HEVC | 4x | Pass | on-time 100.00 % |

## License

This project is licensed under [GPL-3.0](LICENSE).
