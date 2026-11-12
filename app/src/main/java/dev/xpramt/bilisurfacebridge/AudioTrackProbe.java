package dev.xpramt.bilisurfacebridge;

import android.media.AudioTimestamp;
import android.media.AudioTrack;
import android.media.PlaybackParams;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;


/**
 * Diagnostic only (switch: {@link DebugLog}): shows what IJK really asks the system's AudioTrack
 * for when the playback speed changes and where the hardware playback position goes, so the
 * IJK position clock ({@code getCurrentPosition}) can be compared with it.
 *
 * <ul>
 * <li>{@code setPlaybackParams}/{@code setPlaybackRate}: the requested values, the result and any
 * exception are logged as they happen.</li>
 * <li>A background thread polls {@code getTimestamp} and {@code getPlaybackParams} of the playing
 * track every 100 ms and logs once a second: the track's sample rate, the speed it reports, the
 * slope of the hardware frame position (frames per second divided by the sample rate: the content
 * speed the hardware really plays at) and the slope of the IJK position clock.</li>
 * </ul>
 */
final class AudioTrackProbe {
    private static final String TAG = "BiliExtended";
    private static final long POLL_MS = 100L;
    private static final long WINDOW_NS = 1_500_000_000L;
    private static final int RING = 64;

    private static boolean installed;
    private static final List<WeakReference<AudioTrack>> TRACKS = new ArrayList<>();
    private static Thread poller;

    // Hardware position samples of the track polled last.
    private static final long[] tsNs = new long[RING];
    private static final long[] tsFrames = new long[RING];
    private static int tsCount;
    private static WeakReference<AudioTrack> polled = new WeakReference<>(null);

    // IJK position clock samples (milliseconds).
    private static final long[] clockNs = new long[RING * 2];
    private static final long[] clockMs = new long[RING * 2];
    private static int clockCount;

    private AudioTrackProbe() {
    }

    static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        try {
            final Class<?> trackClass = AudioTrack.class;
            Xp.hookAllMethods(trackClass, "setPlaybackParams", new Xp.Hook() {
                @Override
                protected void before(Xp.Param param) {
                    if (DebugLog.enabled() && param.args.length == 1
                            && param.args[0] instanceof PlaybackParams) {
                        PlaybackParams params = (PlaybackParams) param.args[0];
                        Xp.log(TAG + ": AudioTrack.setPlaybackParams speed="
                                + safeSpeed(params) + " pitch=" + safePitch(params)
                                + " track=" + System.identityHashCode(param.thisObject));
                    }
                }

                @Override
                protected void after(Xp.Param param) {
                    if (DebugLog.enabled()) {
                        Xp.log(TAG + ": AudioTrack.setPlaybackParams -> "
                                + (param.hasThrowable() ? "threw " + param.getThrowable() : "ok"));
                    }
                }
            });
            Xp.hookAllMethods(trackClass, "setPlaybackRate", new Xp.Hook() {
                @Override
                protected void before(Xp.Param param) {
                    if (DebugLog.enabled()) {
                        Xp.log(TAG + ": AudioTrack.setPlaybackRate(" + param.args[0]
                                + ") track=" + System.identityHashCode(param.thisObject));
                    }
                }
            });
            Xp.hookAllMethods(trackClass, "play", new Xp.Hook() {
                @Override
                protected void after(Xp.Param param) {
                    register((AudioTrack) param.thisObject);
                }
            });
        } catch (Throwable throwable) {
            Xp.log(TAG + ": AudioTrackProbe hooks unavailable="
                    + throwable.getClass().getSimpleName());
        }
    }

    /** Called with every IJK position clock sample (same data as the Media3 telemetry). */
    static void notePosition(long nowNs, long positionMs) {
        synchronized (AudioTrackProbe.class) {
            if (clockCount == clockNs.length) {
                final int keep = clockNs.length / 2;
                System.arraycopy(clockNs, clockNs.length - keep, clockNs, 0, keep);
                System.arraycopy(clockMs, clockMs.length - keep, clockMs, 0, keep);
                clockCount = keep;
            }
            clockNs[clockCount] = nowNs;
            clockMs[clockCount] = positionMs;
            clockCount++;
        }
    }

    private static synchronized void register(AudioTrack track) {
        for (WeakReference<AudioTrack> reference : TRACKS) {
            if (reference.get() == track) {
                return;
            }
        }
        TRACKS.add(new WeakReference<AudioTrack>(track));
        if (DebugLog.enabled()) {
            Xp.log(TAG + ": AudioTrack.play track=" + System.identityHashCode(track)
                    + " sampleRate=" + track.getSampleRate());
        }
        if (poller == null) {
            poller = new Thread(new Runnable() {
                @Override
                public void run() {
                    pollLoop();
                }
            }, "bsb-audiotrack-probe");
            poller.setDaemon(true);
            poller.start();
        }
    }

    private static void pollLoop() {
        long lastLogNs = 0L;
        final AudioTimestamp timestamp = new AudioTimestamp();
        while (Generation.alive()) {
            try {
                Thread.sleep(POLL_MS);
                if (!DebugLog.enabled()) {
                    synchronized (AudioTrackProbe.class) {
                        tsCount = 0;
                    }
                    continue;
                }
                AudioTrack track = playingTrack();
                if (track == null) {
                    continue;
                }
                synchronized (AudioTrackProbe.class) {
                    if (polled.get() != track) {
                        polled = new WeakReference<AudioTrack>(track);
                        tsCount = 0;
                    }
                }
                if (track.getTimestamp(timestamp)) {
                    synchronized (AudioTrackProbe.class) {
                        if (tsCount == RING) {
                            System.arraycopy(tsNs, 1, tsNs, 0, RING - 1);
                            System.arraycopy(tsFrames, 1, tsFrames, 0, RING - 1);
                            tsCount = RING - 1;
                        }
                        tsNs[tsCount] = timestamp.nanoTime;
                        tsFrames[tsCount] = timestamp.framePosition;
                        tsCount++;
                    }
                }
                final long now = System.nanoTime();
                if (now - lastLogNs >= 1_000_000_000L) {
                    lastLogNs = now;
                    logSummary(track, now);
                }
            } catch (Throwable ignored) {
                // Diagnostics only.
            }
        }
    }

    private static synchronized AudioTrack playingTrack() {
        AudioTrack best = null;
        for (int i = TRACKS.size() - 1; i >= 0; i--) {
            AudioTrack track = TRACKS.get(i).get();
            if (track == null) {
                TRACKS.remove(i);
                continue;
            }
            try {
                if (best == null && track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    best = track;
                }
            } catch (Throwable ignored) {
                // Released.
            }
        }
        return best;
    }

    private static void logSummary(AudioTrack track, long now) {
        double hardwareRate = Double.NaN;
        long frames = -1L;
        synchronized (AudioTrackProbe.class) {
            int first = 0;
            while (first < tsCount - 2 && now - tsNs[first] > WINDOW_NS) {
                first++;
            }
            if (tsCount - first >= 3 && tsNs[tsCount - 1] > tsNs[first]) {
                final int sampleRate = Math.max(1, track.getSampleRate());
                hardwareRate = (tsFrames[tsCount - 1] - tsFrames[first]) * 1e9
                        / (tsNs[tsCount - 1] - tsNs[first]) / sampleRate;
                frames = tsFrames[tsCount - 1];
            }
        }
        final double clockRate = clockSlope(now);
        float speed = Float.NaN;
        try {
            speed = track.getPlaybackParams().getSpeed();
        } catch (Throwable ignored) {
            // No params set yet.
        }
        Xp.log(TAG + ": AudioTrackProbe sampleRate=" + track.getSampleRate()
                + " reportedSpeed=" + speed + " hardwareRate=" + fmt(hardwareRate)
                + "x ijkClockRate=" + fmt(clockRate) + "x hardwareFrames=" + frames);
    }

    private static synchronized double clockSlope(long now) {
        int first = 0;
        while (first < clockCount - 2 && now - clockNs[first] > WINDOW_NS) {
            first++;
        }
        if (clockCount - first < 5 || clockNs[clockCount - 1] <= clockNs[first]) {
            return Double.NaN;
        }
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        final int n = clockCount - first;
        final double baseNs = clockNs[first];
        final double baseMs = clockMs[first];
        for (int i = first; i < clockCount; i++) {
            final double x = (clockNs[i] - baseNs) / 1e6;
            final double y = clockMs[i] - baseMs;
            sx += x;
            sy += y;
            sxx += x * x;
            sxy += x * y;
        }
        final double denominator = n * sxx - sx * sx;
        return denominator <= 0.0 ? Double.NaN : (n * sxy - sx * sy) / denominator;
    }

    private static String fmt(double value) {
        return Double.isNaN(value) ? "n/a" : String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    private static float safeSpeed(PlaybackParams params) {
        try {
            return params.getSpeed();
        } catch (Throwable throwable) {
            return Float.NaN;
        }
    }

    private static float safePitch(PlaybackParams params) {
        try {
            return params.getPitch();
        } catch (Throwable throwable) {
            return Float.NaN;
        }
    }
}
