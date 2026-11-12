package dev.xpramt.bilisurfacebridge;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.media.MediaCodec;
import android.media.MediaCrypto;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.Trace;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.view.Choreographer;
import android.view.Display;
import android.view.Surface;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;


/**
 * Media3 VideoFrameReleaseHelper-style video output synchronizer for Bilibili's IJK path.
 *
 * <p>This keeps the IJK render decision unchanged, but replaces an immediate rendering release
 * with a timed release. The release timeline, VSync sampler, display-rate changes, API 33
 * FrameTimeline path, prediction reset, frame-index handling, and VSync hysteresis mirror the
 * corresponding Media3 helper behavior as closely as the IJK hook surface allows.</p>
 */
final class Media3FrameSynchronizer {
    private static final String TAG = "BiliExtended";
    private static final long MAX_ALLOWED_ADJUSTMENT_NS = 20_000_000L;
    private static final long LINE_TOLERANCE_NS = 3_000_000L;
    // NX809J's SurfaceFlinger never deferred on desiredPresentTime, so the timestamp only
    // adds an unknown; the call phase alone decides the slot.
    private static final boolean RELEASE_WITH_TIMESTAMP = false;
    // Experiment: release every frame as soon as IJK decides (through the handler) to tell a
    // thread effect from a hold effect on decoder output lateness.
    private static final boolean ZERO_HOLD_EXPERIMENT = false;
    // Preferred mode: IJK's refresh loop is steered from the native compute_target_delay
    // hook so it calls releaseOutputBuffer at the slot's call phase by itself.  The Java
    // hold-and-release path below is the fallback when that hook is unavailable.
    private static final boolean NATIVE_RELEASE_SCHEDULING = true;
    private static volatile boolean nativeSchedulingActive;

    /** When native Media3 scheduling is active, overlay interval stats are recorded from
     * non-skip releases only (see release path), not from every onVideoDisplay. */
    static boolean overlayShouldCountVideoDisplayCallback() {
        return !nativeSchedulingActive;
    }
    private static long scheduleTuningAppliedNs;
    private static volatile boolean nativeSchedulingRequested;
    private static long nativePassThroughReleases;
    private static final long MAX_CLOCK_DISCONTINUITY_US = 500_000L;
    private static final long MIN_FRAME_DURATION_NS = 1_000_000L;
    private static final long MAX_FRAME_DURATION_NS = 50_000_000L;
    private static final long MAX_VSYNC_SAMPLE_AGE_NS = 100_000_000L;
    private static final long MIN_VSYNC_DURATION_NS = 4_000_000L;
    private static final long MAX_VSYNC_DURATION_NS = 34_000_000L;
    private static final long VSYNC_OFFSET_PERCENT = 80L;
    // Lead applied to IJK's "render now" decision before snapping to a VSync.  Measured
    // Queue->PresentFence latency on NX809J is 1.3-2.2 periods, so one period is not enough
    // for the buffer to reach the target VSync's latch.
    // IJK's own release decisions jitter by only +-0.6 ms when nothing is held back, so the
    // smoothing line only needs CALL_LEAD plus a small margin: holds then range from -0.33p
    // (still inside the slot window) to +0.67p and the decoder's output pool is not starved.
    private static final long RELEASE_LEAD_PERCENT = 230L;
    // A frame can still make its slot when the call lands up to half a period after the
    // ideal call phase (the queue window is one period wide), so only targets closer than
    // CALL_LEAD - 0.5p (+1 ms) to "now" are unreachable.  Holding buffers longer than that
    // starves the decoder's output pool: measured 22-24 ms decode stalls at 1.66p holds.
    private static final long MIN_HOLD_LEAD_PERCENT = 135L;
    private static final long MIN_HOLD_LEAD_MARGIN_NS = 1_000_000L;
    // Measured on NX809J: latch->present is 1.17p and the release call reaches the queue
    // ~0.18p later, so calling 2.0p before the target tick queues ~4.9 ms before the latch
    // (window is one period wide), leaving margin on both sides.
    private static final long CALL_LEAD_BEFORE_PRESENT_PERCENT = 220L;
    private static final long GRID_ANCHOR_TOLERANCE_NS = 1_000_000L;
    // Keeps desiredPresentTime strictly before the target expected-presentation tick so
    // SurfaceFlinger's "is due" comparison selects exactly that tick.
    private static final long DESIRED_PRESENT_MARGIN_NS = 1_000_000L;
    private static final long DEFAULT_RELEASE_LEAD_PERIOD_NS = 8_333_333L;
    private static final long MAX_TELEMETRY_FILE_BYTES = 512L * 1024L * 1024L;
    private static final int CONSECUTIVE_MATCHING_FRAME_DURATIONS_FOR_SYNC = 15;
    private static final long MAX_MATCHING_FRAME_DIFFERENCE_NS = 1_000_000L;
    private static final long MAX_IJK_EARLY_US_SAMPLE_AGE_NS = 1_000_000_000L;
    private static final int IJK_EARLY_US_SAMPLE_FIELD_COUNT = 4;
    private static final long CADENCE_DURATION_CHANGE_TOLERANCE_NS = 200_000L;
    private static final long CADENCE_PHASE_CORRECTION_TOLERANCE_NS = 100_000L;
    private static final long RENDER_CALLBACK_BURST_WINDOW_NS = 1_000_000L;
    private static final int MAX_PENDING_RENDER_TIMINGS = 4096;
    private static final long TIME_UNSET = Long.MIN_VALUE;
    private static final long INDEX_UNSET = -1L;
    // The software-fence key is optional: the NX809J's AVC decoder declares the two output-fence keys below but not this one, and requiring all three made the whole switch a
    // no-op there (measured 2026-11-11, qf1: "decoder does not declare all keys", qtiFenceConfigs=0).  The Moonlight forks (Axixi2233, qiin2333) set the three keys with the
    // comments "sw-fence: Snapdragon 8 gen 2", "output-fence.enable / fence_type: 8s Gen 3 and Elite, fence_type 1 = sw is best".  So: the two output-fence keys are required,
    // the sw key is added only on a decoder that declares it.
    private static final String QTI_SW_FENCE =
            "vendor.qti-ext-output-sw-fence-enable.value";
    private static final String QTI_OUTPUT_FENCE = "vendor.qti-ext-output-fence.enable";
    private static final String QTI_OUTPUT_FENCE_TYPE = "vendor.qti-ext-output-fence.fence_type";
    private static final List<String> QTI_FENCE_KEYS;
    private static volatile int deadCodecReleases;   // codecs released by the leak guard in the stop() hook
    private static final Map<MediaCodec, CodecState> CODECS =
            Collections.synchronizedMap(new WeakHashMap<MediaCodec, CodecState>());
    private static final Map<MediaCodec, PendingRenderListener> PENDING_RENDER_LISTENERS =
            Collections.synchronizedMap(new WeakHashMap<MediaCodec, PendingRenderListener>());
    private static final Set<MediaCodec> ACTIVE_VIDEO_CODECS =
            Collections.newSetFromMap(new WeakHashMap<MediaCodec, Boolean>());

    static {
        List<String> keys = new ArrayList<String>(2);
        keys.add(QTI_OUTPUT_FENCE);
        keys.add(QTI_OUTPUT_FENCE_TYPE);
        QTI_FENCE_KEYS = Collections.unmodifiableList(keys);
    }

    private static boolean installed;
    private static HandlerThread vsyncThread;
    private static Handler vsyncHandler;
    private static HandlerThread renderCallbackThread;
    private static Handler renderCallbackHandler;
    private static volatile VSyncSampler vsyncSampler;
    private static volatile String vsyncSamplerMode = "unavailable";
    private static long timedReleases;
    private static long predictedDeadlines;
    private static long deadlineResets;
    private static long clockReanchors;
    private static long clockLateFrames;
    private static long vsyncSnaps;
    private static long ijkEarlyUsAnchors;
    private static long ijkEarlyUsMisses;
    private static long ijkEarlyUsStaleSamples;
    private static long cadenceAnchors;
    private static long cadenceExtraVsyncs;
    private static long cadencePhaseCorrections;
    private static long cadenceFallbacks;
    private static long holdLeadBumps;
    private static long slotCollisionShifts;
    private static long gridAnchorRejects;
    private static long scheduledCallReleases;
    private static long lateCallReleases;
    private static long cancelledCallReleases;
    private static HandlerThread releaseThread;
    private static Handler releaseHandler;
    private static volatile long qtiFenceConfigurations;
    private static volatile String qtiFenceLast = "";

    /** For the rate-state file: how many video decoders this process configured with the QTI output fence, and the last one (a test reads it per round). */
    static long qtiFenceConfigs() {
        return qtiFenceConfigurations;
    }

    static String qtiFenceLast() {
        return qtiFenceLast;
    }
    private static long surfaceFrameRateHints;
    private static long lastReportNs;
    private static boolean reportedOnce;
    private static long lastTimedReleaseNs;
    private static final String TELEMETRY_SESSION_ID =
            Long.toHexString(System.currentTimeMillis()) + "-" + Process.myPid();
    private static volatile long latestPlaybackPositionUs = TIME_UNSET;
    private static volatile long latestPlaybackPositionSampleNs = TIME_UNSET;
    private static volatile String latestPlayerLifecycle = "unknown";
    private static volatile boolean playbackClockHooksInstalled;
    private static volatile long telemetryDroppedRecords;
    private static final ThreadLocal<Boolean> INTERNAL_TIMED_RELEASE =
            new ThreadLocal<Boolean>() {
                @Override
                protected Boolean initialValue() {
                    return Boolean.FALSE;
                }
            };
    private static final ThreadLocal<Boolean> INTERNAL_RENDER_LISTENER_SETUP =
            new ThreadLocal<Boolean>() {
                @Override
                protected Boolean initialValue() {
                    return Boolean.FALSE;
                }
            };

    private Media3FrameSynchronizer() {
    }

    static synchronized String install() {
        if (installed) {
            return "Media3 frame-sync hooks already installed";
        }
        try {
            Xp.findAndHookMethod(MediaCodec.class, "configure", MediaFormat.class,
                    Surface.class, MediaCrypto.class, int.class, new Xp.Hook() {
                        @Override
                        protected void before(Xp.Param param) {
                            beforeConfigure((MediaCodec) param.thisObject,
                                    (MediaFormat) param.args[0]);
                        }

                        @Override
                        protected void after(Xp.Param param) {
                            if (param.hasThrowable()) {
                                logOnce("decoder-configure-failed:" + codecName(
                                                (MediaCodec) param.thisObject),
                                        "decoder configure failed; MIME is not reported as active");
                                return;
                            }
                            afterConfigure((MediaCodec) param.thisObject,
                                    (MediaFormat) param.args[0], (Surface) param.args[1]);
                        }
                    });
            try {
                Xp.findAndHookMethod(MediaCodec.class, "setOnFrameRenderedListener",
                        MediaCodec.OnFrameRenderedListener.class, Handler.class,
                        new Xp.Hook() {
                            @Override
                            protected void before(Xp.Param param) {
                                if (Boolean.TRUE.equals(INTERNAL_RENDER_LISTENER_SETUP.get())) {
                                    return;
                                }
                                beforeSetOnFrameRenderedListener(
                                        (MediaCodec) param.thisObject,
                                        (MediaCodec.OnFrameRenderedListener) param.args[0],
                                        (Handler) param.args[1], param);
                            }
                        });
            } catch (Throwable throwable) {
                logOnce("media3-render-listener-hook",
                        "MediaCodec render callback hook unavailable: "
                                + throwable.getClass().getSimpleName());
            }
            Xp.findAndHookMethod(MediaCodec.class, "dequeueOutputBuffer",
                    MediaCodec.BufferInfo.class, long.class, new Xp.Hook() {
                        @Override
                        protected void after(Xp.Param param) {
                            Object result = param.getResult();
                            if (result instanceof Integer) {
                                onOutputDequeued((MediaCodec) param.thisObject, (Integer) result,
                                        (MediaCodec.BufferInfo) param.args[0]);
                            }
                        }
                    });
            try {
                // When each input buffer is queued: with the matching dequeueOutputBuffer this is the decode latency (the way Moonlight measures "decode time"); always on
                // for video decoders (a ring write per frame), the debug csv also carries it per frame.
                Xp.findAndHookMethod(MediaCodec.class, "queueInputBuffer", int.class, int.class, int.class,
                        long.class, int.class, new Xp.Hook() {
                            @Override
                            protected void before(Xp.Param param) {
                                onInputQueued((MediaCodec) param.thisObject, (Long) param.args[3]);
                            }
                        });
            } catch (Throwable throwable) {
                logOnce("media3-input-queue-hook", "input queue telemetry unavailable: " + throwable.getClass().getSimpleName());
            }
            Xp.findAndHookMethod(MediaCodec.class, "start", new Xp.Hook() {
                @Override
                protected void after(Xp.Param param) {
                    recordCodecLifecycle((MediaCodec) param.thisObject, "start", null);
                }
            });
            Xp.findAndHookMethod(MediaCodec.class, "releaseOutputBuffer", int.class,
                    boolean.class, new Xp.Hook() {
                        @Override
                        protected void before(Xp.Param param) {
                            if (Boolean.TRUE.equals(INTERNAL_TIMED_RELEASE.get())) {
                                return;
                            }
                            int index = (Integer) param.args[0];
                            boolean render = (Boolean) param.args[1];
                            if (releaseAtAdjustedTime((MediaCodec) param.thisObject, index, render)) {
                                param.setResult(null);
                            }
                        }
                    });
            try {
                Xp.findAndHookMethod(MediaCodec.class, "releaseOutputBuffer", int.class,
                        long.class, new Xp.Hook() {
                            @Override
                            protected void before(Xp.Param param) {
                                if (!Boolean.TRUE.equals(INTERNAL_TIMED_RELEASE.get())) {
                                    recordDirectTimedRelease((MediaCodec) param.thisObject,
                                            (Integer) param.args[0], (Long) param.args[1]);
                                }
                            }
                        });
            } catch (Throwable throwable) {
                logOnce("media3-direct-timed-release-hook",
                        "direct timed release telemetry unavailable: "
                                + throwable.getClass().getSimpleName());
            }
            try {
                Xp.findAndHookMethod(MediaCodec.class, "setOutputSurface", Surface.class,
                        new Xp.Hook() {
                            @Override
                            protected void before(Xp.Param param) {
                                recordCodecLifecycle((MediaCodec) param.thisObject,
                                        "setOutputSurface", param.args[0]);
                            }
                        });
            } catch (Throwable throwable) {
                logOnce("media3-surface-change-hook",
                        "Surface change telemetry unavailable: "
                                + throwable.getClass().getSimpleName());
            }
            Xp.Hook resetHook = new Xp.Hook() {
                @Override
                protected void before(Xp.Param param) {
                    MediaCodec codec = (MediaCodec) param.thisObject;
                    String methodName = param.method.getName();
                    CodecState state = CODECS.get(codec);
                    long nowNs = System.nanoTime();
                    long sinceLastTimedReleaseMs = lastTimedReleaseNs == 0 ? 0
                            : (nowNs - lastTimedReleaseNs) / 1_000_000L;
if (DebugLog.enabled()) {
                    Xp.log(TAG + ": [DEBUG-FRAME-LOCATE] codecLifecycle="
                            + methodName
                            + " codec=" + codecName(codec)
                            + " sinceLastTimedReleaseMs=" + sinceLastTimedReleaseMs
                            + " timed=" + timedReleases
                            + " predicted=" + predictedDeadlines
                            + " resets=" + deadlineResets
                            + " clockReanchors=" + clockReanchors
                            + " vsyncSnaps=" + vsyncSnaps
                            + " vsyncMode=" + vsyncSamplerMode);
}
                    // Pending timed releases refer to buffer indices that are invalid after
                    // flush/stop/release; drop them before the codec state changes.
                    cancelScheduledReleases(codec);
                    NativeRefreshCap.invalidateFrameTimer();
                    if ("flush".equals(methodName)) {
                        if (state != null) {
                            synchronized (state) {
                                state.resetPosition();
                            }
                        }
                    } else {
                        removeCodec(codec, state);
                    }
                }
            };
            Xp.findAndHookMethod(MediaCodec.class, "flush", resetHook);
            Xp.findAndHookMethod(MediaCodec.class, "stop", resetHook);
            Xp.findAndHookMethod(MediaCodec.class, "release", resetHook);
            // A hardware decoder that died in the error state (its output surface was abandoned while IJK was still decoding, which is
            // what leaving the player and reopening a clip does) makes stop() throw IllegalStateException.  IJK's error path then returns
            // without ever calling release(), so the c2.qti.avc.decoder instance stays registered with the resource manager for the whole
            // life of the :ijkservice process.  Measured 2026-11-09: after two such reopens the process held 2 leaked decoders, the 3rd
            // instance failed to start (QC2 vppFltr VPP_ERR_INVALID_CFG -> MediaCodec NO_MEMORY) and IJK fell back to FFmpeg software
            // decoding ("audio only" rounds, and the -3.1 rate after the player restarted).  Releasing a codec whose stop() has just thrown
            // is always safe (the codec is unusable) and frees the instance at once.
            Xp.findAndHookMethod(MediaCodec.class, "stop", new Xp.Hook() {
                @Override
                protected void after(Xp.Param param) {
                    if (param.getThrowable() == null) {
                        return;
                    }
                    MediaCodec dead = (MediaCodec) param.thisObject;
                    try {
                        dead.release();
                        deadCodecReleases++;
                        Xp.log(TAG + ": released a codec whose stop() threw " + param.getThrowable().getClass().getSimpleName()
                                + " (leak guard, total " + deadCodecReleases + ")");
                    } catch (Throwable ignored) {
                        // already released
                    }
                }
            });
                installed = true;
            return "installed Media3 telemetry hooks and QTI configure hooks";
        } catch (Throwable throwable) {
            return "Media3 frame-sync hooks unavailable=" + throwable.getClass().getSimpleName();
        }
    }

    /**
     * Where IJK decodes inside the main process (no :ijkservice process, as on some phones), the
     * service-side hooks never run, so the info overlay would never learn the real decoder
     * format. This reports it from the main process, for IJK's own decoder threads only.
     */
    static String installMainProcessDecoderFormatRelay() {
        try {
            Xp.findAndHookMethod(MediaCodec.class, "configure", MediaFormat.class,
                    Surface.class, MediaCrypto.class, int.class, new Xp.Hook() {
                        @Override
                        protected void after(Xp.Param param) {
                            if (param.hasThrowable()
                                    || !Thread.currentThread().getName().startsWith("ijk_")) {
                                return;
                            }
                            MediaFormat format = (MediaFormat) param.args[0];
                            String mime = format == null || !format.containsKey(MediaFormat.KEY_MIME)
                                    ? null : format.getString(MediaFormat.KEY_MIME);
                            MediaCodec codec = (MediaCodec) param.thisObject;
                            if (isAudio(format)) {
                                DecoderFormatRelay.publishConfiguredAudio(mime,
                                        formatBitrate(format), formatSampleRate(format),
                                        codecName(codec));
                            } else if (isVideo(format)) {
                                DecoderFormatRelay.publishConfiguredVideo(mime, codecName(codec));
                            }
                        }
                    });
            return "installed main-process decoder format relay";
        } catch (Throwable throwable) {
            return "main-process decoder format relay unavailable="
                    + throwable.getClass().getSimpleName();
        }
    }

    private static void beforeConfigure(MediaCodec codec, MediaFormat format) {
        if (isVideo(format) && DebugLog.enabled()) {
            // The vendor SurfaceFlinger picks the panel mode from the frame rate its decoder reports (VTFps); this is what the app handed that decoder.
            Xp.log(TAG + ": video decoder configure " + codecName(codec) + " format=" + format);
        }
        if (!isVideo(format) || !experimentsEnabled() || !ModulePreferences.loadQtiOutputFenceForHook()
                || !isQtiDecoder(codec)) {
            return;
        }
        List<String> supported = supportedVendorParameters(codec);
        if (!supported.containsAll(QTI_FENCE_KEYS)) {
            logOnce("qti-fence-unsupported:" + codecName(codec),
                    "QTI output fence skipped; decoder does not declare all keys=" + supported);
            return;
        }
        final boolean swFence = supported.contains(QTI_SW_FENCE);
        if (swFence) {
            format.setInteger(QTI_SW_FENCE, 1);
        }
        format.setInteger(QTI_OUTPUT_FENCE, 1);
        format.setInteger(QTI_OUTPUT_FENCE_TYPE, 1);
        qtiFenceConfigurations++;
        qtiFenceLast = codecName(codec) + (swFence ? "+sw" : "");
        logOnce("qti-fence-enabled:" + codecName(codec),
                "enabled QTI output fence (enable + fence_type" + (swFence ? " + sw fence" : ", sw-fence key not declared") + ") for decoder=" + codecName(codec));
    }

    private static void afterConfigure(MediaCodec codec, MediaFormat format, Surface surface) {
        String mime = format == null || !format.containsKey(MediaFormat.KEY_MIME)
                ? null : format.getString(MediaFormat.KEY_MIME);
        if (isAudio(format)) {
            DecoderFormatRelay.publishConfiguredAudio(mime, formatBitrate(format),
                    formatSampleRate(format), codecName(codec));
            return;
        }
        if (!isVideo(format)) {
            return;
        }
        DecoderFormatRelay.publishConfiguredVideo(mime, codecName(codec));
        if (surface != null && ModulePreferences.loadMedia3FrameSyncForHook()) {
            // Direct SurfaceView: this Surface is what SurfaceFlinger composes, so its
            // compositor timing is the scheduler's latch grid.
            logOnce("compositor-window:" + codecName(codec),
                    NativeRefreshCap.setCompositorWindow(surface));
        }
        CodecState state = new CodecState(surface);
        CODECS.put(codec, state);
        synchronized (ACTIVE_VIDEO_CODECS) {
            ACTIVE_VIDEO_CODECS.add(codec);
        }
        recordCodecLifecycle(codec, "configure", surface);
        // Experiment switch (2026-11-09): `debug.biliextended.frcb` = 0 skips the module's own OnFrameRenderedListener (unset/1 = installed).  The HAL's onFramesRendered callback
        // into this process coincided with ~500 ms stalls of the whole codec pipeline (MediaCodec_loop <-> Codec2 HAL binder calls both blocked); with frame sync off (no listener) none occur.
        if (experimentsEnabled() && DebugLog.readInt("debug.biliextended.frcb", 1) != 0) {
            installRenderCallbackListener(codec, state);
        }
        if (experimentsEnabled() && ModulePreferences.loadMedia3FrameSyncForHook()) {
            ensureVsyncSampler();
            requestVsyncRegistration();
        }
    }

    /**
     * Hot reload, old generation: hand the decoders that are playing, their listeners and the playback rate to the next generation (framework and
     * Bilibili objects only), then stop this generation's threads.  The render-callback thread outlives the hand-over by 2 s: until the next
     * generation registers its own listener on a codec, callbacks still arrive here and must reach Bilibili's listener.
     */
    static void exportForHotReload(Map<String, Object> out) {
        List<Object[]> codecs = new ArrayList<Object[]>();
        synchronized (ACTIVE_VIDEO_CODECS) {
            for (MediaCodec codec : ACTIVE_VIDEO_CODECS) {
                CodecState state = CODECS.get(codec);
                if (state == null) {
                    continue;
                }
                synchronized (state) {
                    codecs.add(new Object[]{codec, state.surface, state.applicationRenderListener, state.applicationRenderHandler});
                }
            }
        }
        out.put("m3.codecs", codecs);
        out.put("m3.rate", Float.valueOf(latestPlaybackRate));
        List<Object[]> clients = new ArrayList<Object[]>();
        synchronized (CLIENT_RATES) {
            for (Map.Entry<Object, Float> entry : CLIENT_RATES.entrySet()) {
                clients.add(new Object[]{entry.getKey(), entry.getValue(), CLIENT_SEEN_NS.get(entry.getKey())});
            }
        }
        out.put("m3.clients", clients);
        nativeSchedulingActive = false;
        final Handler vsync = vsyncHandler;
        final HandlerThread vsyncLoop = vsyncThread;
        if (vsync != null) {
            vsync.post(() -> {
                VSyncSampler sampler = vsyncSampler;
                if (sampler != null) {
                    sampler.unregister();
                }
                if (vsyncLoop != null) {
                    vsyncLoop.quitSafely();
                }
            });
        }
        if (releaseThread != null) {
            releaseThread.quitSafely();
        }
        final Handler render = renderCallbackHandler;
        final HandlerThread renderLoop = renderCallbackThread;
        if (render != null && renderLoop != null) {
            render.postDelayed(renderLoop::quitSafely, 2000L);
        }
    }

    /** Hot reload, new generation: take over what the old one handed over (see exportForHotReload). */
    static String adoptAfterHotReload(Map<?, ?> in) {
        if (in == null) {
            return "nothing handed over";
        }
        Object clients = in.get("m3.clients");
        if (clients instanceof List) {
            for (Object item : (List<?>) clients) {
                Object[] client = (Object[]) item;
                if (client[0] != null && client[1] instanceof Float) {
                    CLIENT_RATES.put(client[0], (Float) client[1]);
                    CLIENT_SEEN_NS.put(client[0], client[2] instanceof Long ? (Long) client[2] : System.nanoTime());
                }
            }
        }
        Object rate = in.get("m3.rate");
        if (rate instanceof Float) {
            onPlaybackRate((Float) rate, "hot reload");
        }
        int adopted = 0;
        Object codecs = in.get("m3.codecs");
        if (codecs instanceof List) {
            for (Object item : (List<?>) codecs) {
                Object[] entry = (Object[]) item;
                MediaCodec codec = (MediaCodec) entry[0];
                Surface surface = (Surface) entry[1];
                if (codec == null) {
                    continue;
                }
                if (surface != null && ModulePreferences.loadMedia3FrameSyncForHook()) {
                    NativeRefreshCap.setCompositorWindow(surface);
                }
                CodecState state = new CodecState(surface);
                CODECS.put(codec, state);
                synchronized (ACTIVE_VIDEO_CODECS) {
                    ACTIVE_VIDEO_CODECS.add(codec);
                }
                if (entry[2] != null || entry[3] != null) {
                    PENDING_RENDER_LISTENERS.put(codec, new PendingRenderListener(
                            (MediaCodec.OnFrameRenderedListener) entry[2], (Handler) entry[3]));
                }
                if (experimentsEnabled() && DebugLog.readInt("debug.biliextended.frcb", 1) != 0) {
                    installRenderCallbackListener(codec, state);
                }
                if (experimentsEnabled() && ModulePreferences.loadMedia3FrameSyncForHook()) {
                    ensureVsyncSampler();
                    requestVsyncRegistration();
                }
                adopted++;
            }
        }
        return "adopted codecs=" + adopted + " rate=" + rate;
    }

    private static void beforeSetOnFrameRenderedListener(
            MediaCodec codec,
            MediaCodec.OnFrameRenderedListener applicationListener,
            Handler applicationHandler,
            Xp.Param param) {
        if (!experimentsEnabled()) {
            return;
        }
        CodecState state = CODECS.get(codec);
        if (state == null) {
            PENDING_RENDER_LISTENERS.put(codec,
                    new PendingRenderListener(applicationListener, applicationHandler));
            return;
        }
        final long listenerGeneration;
        final MediaCodec.OnFrameRenderedListener wrapper;
        synchronized (state) {
            if (applicationListener == state.renderListenerWrapper) {
                return;
            }
            state.applicationRenderListener = applicationListener;
            state.applicationRenderHandler = applicationHandler;
            listenerGeneration = ++state.renderListenerGeneration;
            wrapper = createRenderListener(codec, state, applicationListener, listenerGeneration);
            state.renderListenerWrapper = wrapper;
        }
        param.args[0] = wrapper;
        recordRenderListenerLifecycle(codec, "setOnFrameRenderedListener", applicationListener,
                applicationHandler, listenerGeneration);
    }

    private static void installRenderCallbackListener(MediaCodec codec, CodecState state) {
        PendingRenderListener pending = PENDING_RENDER_LISTENERS.remove(codec);
        MediaCodec.OnFrameRenderedListener applicationListener =
                pending == null ? null : pending.listener;
        Handler handler = pending == null || pending.handler == null
                ? ensureRenderCallbackHandler() : pending.handler;
        if (handler == null) {
            recordRenderListenerLifecycle(codec, "renderListenerInstallFailed", null, null,
                    state.renderListenerGeneration);
            return;
        }
        final long listenerGeneration;
        final MediaCodec.OnFrameRenderedListener wrapper;
        synchronized (state) {
            state.applicationRenderListener = applicationListener;
            state.applicationRenderHandler = pending == null ? null : pending.handler;
            listenerGeneration = ++state.renderListenerGeneration;
            wrapper = createRenderListener(codec, state, applicationListener, listenerGeneration);
            state.renderListenerWrapper = wrapper;
        }
        try {
            INTERNAL_RENDER_LISTENER_SETUP.set(Boolean.TRUE);
            codec.setOnFrameRenderedListener(wrapper, handler);
            recordRenderListenerLifecycle(codec, "renderListenerInstall", applicationListener, handler,
                    listenerGeneration);
        } catch (Throwable throwable) {
            recordRenderListenerLifecycle(codec, "renderListenerInstallFailed", applicationListener, handler,
                    listenerGeneration);
            logOnce("media3-render-listener-install:" + codecName(codec),
                    "MediaCodec render callback registration failed="
                            + throwable.getClass().getSimpleName());
        } finally {
            INTERNAL_RENDER_LISTENER_SETUP.set(Boolean.FALSE);
        }
    }

    private static MediaCodec.OnFrameRenderedListener createRenderListener(
            final MediaCodec codec,
            final CodecState state,
            final MediaCodec.OnFrameRenderedListener applicationListener,
            final long listenerGeneration) {
        return new MediaCodec.OnFrameRenderedListener() {
            @Override
            public void onFrameRendered(MediaCodec callbackCodec, long presentationTimeUs,
                                        long nanoTime) {
                if (nativeSchedulingActive) {
                    NativeRefreshCap.notePresent(nanoTime);
                }
                try {
                    recordFrameRendered(codec, state, listenerGeneration, presentationTimeUs,
                            nanoTime);
                } catch (Throwable throwable) {
                    logOnce("media3-render-callback:" + codecName(codec),
                            "render callback telemetry failed="
                                    + throwable.getClass().getSimpleName());
                }
                if (applicationListener != null) {
                    try {
                        applicationListener.onFrameRendered(callbackCodec, presentationTimeUs,
                                nanoTime);
                    } catch (Throwable throwable) {
                        logOnce("media3-render-listener-forward:" + codecName(codec),
                                "original render callback failed="
                                        + throwable.getClass().getSimpleName());
                    }
                }
            }
        };
    }

    private static synchronized Handler ensureRenderCallbackHandler() {
        if (renderCallbackHandler != null) {
            return renderCallbackHandler;
        }
        try {
            renderCallbackThread = new HandlerThread("Bili-Media3-RenderCallback",
                    Process.THREAD_PRIORITY_DISPLAY);
            renderCallbackThread.start();
            renderCallbackHandler = new Handler(renderCallbackThread.getLooper());
            return renderCallbackHandler;
        } catch (Throwable throwable) {
            renderCallbackThread = null;
            renderCallbackHandler = null;
            logOnce("media3-render-callback-handler",
                    "render callback handler unavailable="
                            + throwable.getClass().getSimpleName());
            return null;
        }
    }

    private static void onInputQueued(MediaCodec codec, long presentationTimeUs) {
        if (!DebugLog.monitor()) {
            return;     // test-only: this ring exists for the decode-latency measurement and the csv detail
        }
        CodecState state = CODECS.get(codec);
        if (state == null) {
            return;
        }
        long nowNs = System.nanoTime();
        synchronized (state.inputRing) {
            int slot = state.inputRingPos;
            state.inputRingPts[slot] = presentationTimeUs;
            state.inputRingAtNs[slot] = nowNs;
            state.inputRingPos = (slot + 1) % INPUT_RING;
        }
    }

    // Decode latency, input buffer queued -> output buffer dequeued (user 2026-11-11: "add decoder latency measurement, see Moonlight").  Moonlight does the same: its PTS is the
    // submit time and delta = now - PTS at dequeueOutputBuffer, samples outside 0..1000 ms dropped.  Here the input time is looked up by presentation timestamp in the
    // per-codec ring above.  The last DECODE_LAT_RING frames are published in the rate state file once a second (n, mean, p50, p95, max in microseconds).
    private static final int DECODE_LAT_RING = 1024;
    private static final Object DECODE_LAT_LOCK = new Object();
    private static final long[] DECODE_LAT_NS = new long[DECODE_LAT_RING];
    private static int decodeLatPos;
    private static long decodeLatTotal;

    private static void recordDecodeLatency(CodecState state, long presentationTimeUs, long dequeuedAtNs) {
        long queuedAtNs = 0L;
        synchronized (state.inputRing) {
            for (int i = 1; i <= INPUT_RING; i++) {
                int slot = (state.inputRingPos - i + INPUT_RING) % INPUT_RING;
                if (state.inputRingAtNs[slot] != 0L && state.inputRingPts[slot] == presentationTimeUs) {
                    queuedAtNs = state.inputRingAtNs[slot];
                    break;
                }
            }
        }
        if (queuedAtNs == 0L) {
            return;
        }
        long latencyNs = dequeuedAtNs - queuedAtNs;
        if (latencyNs < 0L || latencyNs >= 1_000_000_000L) {
            return;
        }
        synchronized (DECODE_LAT_LOCK) {
            DECODE_LAT_NS[decodeLatPos] = latencyNs;
            decodeLatPos = (decodeLatPos + 1) % DECODE_LAT_RING;
            decodeLatTotal++;
        }
    }

    /** JSON fields (no braces) for the rate state file: decode latency of the last frames, microseconds; zeros before the first sample. */
    static String decodeLatencyJson() {
        long[] copy;
        long total;
        synchronized (DECODE_LAT_LOCK) {
            total = decodeLatTotal;
            int n = (int) Math.min(total, (long) DECODE_LAT_RING);
            copy = new long[n];
            for (int i = 0; i < n; i++) {
                copy[i] = DECODE_LAT_NS[(decodeLatPos - n + i + DECODE_LAT_RING) % DECODE_LAT_RING];
            }
        }
        if (copy.length == 0) {
            return "\"decode_lat_n\":0,\"decode_lat_total\":0,\"decode_lat_mean_us\":0,\"decode_lat_p50_us\":0,\"decode_lat_p95_us\":0,\"decode_lat_max_us\":0";
        }
        java.util.Arrays.sort(copy);
        long sum = 0L;
        for (long v : copy) {
            sum += v;
        }
        int n = copy.length;
        return "\"decode_lat_n\":" + n + ",\"decode_lat_total\":" + total
                + ",\"decode_lat_mean_us\":" + (sum / n / 1000L)
                + ",\"decode_lat_p50_us\":" + (copy[n / 2] / 1000L)
                + ",\"decode_lat_p95_us\":" + (copy[Math.min(n - 1, (int) (n * 0.95))] / 1000L)
                + ",\"decode_lat_max_us\":" + (copy[n - 1] / 1000L);
    }

    /** csv detail fragment of the dequeued frame: when its input buffer was queued and the resulting decode latency; empty if not found. */
    private static String inputTimingDetail(CodecState state, long presentationTimeUs, long dequeuedAtNs) {
        synchronized (state.inputRing) {
            for (int i = 1; i <= INPUT_RING; i++) {
                int slot = (state.inputRingPos - i + INPUT_RING) % INPUT_RING;
                if (state.inputRingAtNs[slot] != 0L && state.inputRingPts[slot] == presentationTimeUs) {
                    return ";inputQueuedAtNs=" + state.inputRingAtNs[slot] + ";decodeLatencyNs=" + (dequeuedAtNs - state.inputRingAtNs[slot]);
                }
            }
        }
        return "";
    }

    private static final int INPUT_RING = 512;

    private static void onOutputDequeued(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
        if (index < 0 || info == null) {
            return;
        }
        CodecState state = CODECS.get(codec);
        if (state == null) {
            return;
        }
        long dequeuedAtNs = System.nanoTime();
        if (DebugLog.monitor()) {
            recordDecodeLatency(state, info.presentationTimeUs, dequeuedAtNs);
        }
        FrameTiming timing;
        synchronized (state) {
            timing = new FrameTiming(index, info.presentationTimeUs, state.nextFrameIndex++,
                    dequeuedAtNs, state.renderGeneration);
            state.presentationTimes.put(index, timing);
            state.addRenderCandidate(timing);
            state.frameRateEstimator.onNextFrame(info.presentationTimeUs * 1_000L);
            long estimatedFrameDurationNs = state.frameRateEstimator.getFrameDurationNs();
            if (estimatedFrameDurationNs != TIME_UNSET) {
                state.frameDurationNs = estimatedFrameDurationNs;
                maybeSetSurfaceFrameRate(state);
            }
        }
        recordFrameEvent(codec, index, timing, dequeuedAtNs, TIME_UNSET, TIME_UNSET,
                TIME_UNSET, TIME_UNSET, TIME_UNSET, "DEQUEUED", true,
                "dequeueOutputBuffer", telemetryEnabled() ? inputTimingDetail(state, info.presentationTimeUs, dequeuedAtNs) : "");
    }

    private static void recordFrameRendered(MediaCodec codec, CodecState state,
                                            long listenerGeneration, long presentationTimeUs,
                                            long renderedNanoTimeNs) {
        long callbackObservedNs = System.nanoTime();
        FrameTiming timing = null;
        int pendingBefore = 0;
        long callbackCount = 0L;
        long exactMatchCount = 0L;
        long unmatchedCount = 0L;
        long burstCount = 0L;
        long codecRenderGeneration = TIME_UNSET;
        synchronized (state) {
            pendingBefore = state.pendingRenderTimingCount();
            timing = state.takeRenderCandidate(presentationTimeUs, renderedNanoTimeNs);
            state.renderCallbackCount++;
            callbackCount = state.renderCallbackCount;
            if (timing == null) {
                state.renderCallbackUnmatchedCount++;
                unmatchedCount = state.renderCallbackUnmatchedCount;
            } else {
                state.renderCallbackExactMatchCount++;
                exactMatchCount = state.renderCallbackExactMatchCount;
                timing.renderedNanoTimeNs = renderedNanoTimeNs;
                timing.renderCallbackObservedNs = callbackObservedNs;
                timing.renderListenerGeneration = listenerGeneration;
                codecRenderGeneration = timing.renderGeneration;
            }
            if (state.lastRenderCallbackObservedNs != TIME_UNSET
                    && callbackObservedNs - state.lastRenderCallbackObservedNs
                    <= RENDER_CALLBACK_BURST_WINDOW_NS) {
                state.renderCallbackBurstCount++;
            }
            state.lastRenderCallbackObservedNs = callbackObservedNs;
            state.lastRenderCallbackNanoTimeNs = renderedNanoTimeNs;
            burstCount = state.renderCallbackBurstCount;
        }
        if (timing == null) {
            timing = new FrameTiming(-1, presentationTimeUs, INDEX_UNSET, TIME_UNSET,
                    codecRenderGeneration);
        }
        long callbackDispatchDelayNs = renderedNanoTimeNs <= 0L
                ? TIME_UNSET : callbackObservedNs - renderedNanoTimeNs;
        String match = timing.frameIndex == INDEX_UNSET ? "PTS_UNMATCHED" : "PTS_EXACT";
        String detail = "callbackNanoTimeNs=" + renderedNanoTimeNs
                + ";callbackObservedNs=" + callbackObservedNs
                + ";callbackDispatchDelayNs=" + callbackDispatchDelayNs
                + ";renderMatch=" + match
                + ";renderListenerGeneration=" + listenerGeneration
                + ";codecRenderGeneration=" + timing.renderGeneration
                + ";renderCallbackCount=" + callbackCount
                + ";renderCallbackExactMatchCount=" + exactMatchCount
                + ";renderCallbackUnmatchedCount=" + unmatchedCount
                + ";renderCallbackBurstCount=" + burstCount
                + ";pendingRenderFramesBefore=" + pendingBefore;
        recordFrameEvent(codec, timing.bufferIndex, timing, callbackObservedNs,
                currentPlaybackPositionUs(callbackObservedNs),
                playbackPositionAgeMs(callbackObservedNs),
                state.estimatedPositionUs(callbackObservedNs), TIME_UNSET,
                timing.scheduledReleaseNs, "FRAME_RENDERED", true,
                "MediaCodec.OnFrameRenderedListener", detail);
    }

    private static boolean releaseAtAdjustedTime(MediaCodec codec, int index, boolean render) {
        if (!render || !experimentsEnabled() || !ModulePreferences.loadMedia3FrameSyncForHook()) {
            recordReleaseRequest(codec, index, render, "PASS_THROUGH", "feature-disabled-or-not-render");
            return false;
        }
        CodecState state = CODECS.get(codec);
        if (state == null) {
            recordReleaseRequest(codec, index, render, "PASS_THROUGH", "codec-state-missing");
            return false;
        }
        ensureVsyncSampler();
        requestVsyncRegistration();
        ensureNativeScheduling();
        final FrameTiming timing;
        synchronized (state) {
            timing = state.presentationTimes.remove(index);
            if (timing == null) {
                recordReleaseRequest(codec, index, render, "PASS_THROUGH", "frame-timing-missing");
                return false;
            }
        }
        long nowNs = System.nanoTime();
        if (nativeSchedulingActive) {
            // IJK's refresh loop already woke at the slot's call phase; every microsecond
            // spent here before the real release eats the Queue->Latch margin, so release
            // first and keep the bookkeeping afterwards.
            nativePassThroughReleases++;
            boolean fired;
            // Media3 mode: the scheduler picked a release timestamp (closest VSync - 80 % of a
            // period) and woke IJK 45 ms ahead of it.  Every path to SurfaceFlinger goes through
            // one more GL pass, so the timestamp has to reach that pass:
            //  - direct Surface: IJK's own GL redraw on this thread; taking the timestamp arms
            //    the native hook on its eglSwapBuffers;
            //  - Surface external: the codec renders into the main process's SurfaceTexture, so
            //    the buffer carries it (SurfaceTexture.getTimestamp()) to ijk_ext_render, whose
            //    swap is timestamped there (ExternalRenderTimestamps).
            long releaseTs = NativeRefreshCap.takeReleaseTimestamp();
            if (releaseTs > 0L && DebugLog.enabled()) {
                // How far ahead of the real time the planned presentation timestamp is at the release call (negative = already late).
                // Paired with bsb_ext_lead_us (the same timestamp at the GL swap) this separates "planned late" from "delayed on the way".
                try {
                    Trace.setCounter("bsb_rel_lead_us", (releaseTs - nowNs) / 1000L);
                } catch (Throwable ignored) {
                    // Measurement only.
                }
            }
            // bisect bits (2026-11-09, `debug.biliextended.off`, re-read every 2 s): 512 = release with `true` instead of the timestamp, 1024 = never drop (a skip is released with `true`)
            final int bisect = DebugLog.readInt("debug.biliextended.off", 0);
            emitReleaseMarkBeforeCall(timing.presentationTimeUs, releaseTs == -2L);
            try {
                INTERNAL_TIMED_RELEASE.set(Boolean.TRUE);
                if (releaseTs > 0L) {
                    if ((bisect & 512) != 0) {
                        codec.releaseOutputBuffer(index, true);
                    } else {
                        codec.releaseOutputBuffer(index, releaseTs);
                    }
                } else if (releaseTs == -2L) {
                    // Media3 skip: content faster than the panel, this VSync is taken.
                    codec.releaseOutputBuffer(index, (bisect & 1024) != 0);
                } else {
                    codec.releaseOutputBuffer(index, true);
                }
                fired = true;
            } catch (Throwable throwable) {
                fired = false;
                logOnce("native-release-failed:" + codecName(codec),
                        "native-scheduled release failed=" + throwable.getClass().getSimpleName());
            } finally {
                INTERNAL_TIMED_RELEASE.set(Boolean.FALSE);
            }
            // Overlay interval / Avg / 1%Low: count actual handoffs only (not skip).
            // Skipped frames must not update previousCallbackTimeNanos.
            if (fired && releaseTs != -2L) {
                PlaybackDebugOverlay.recordFrameCallback();
            }
            NativeRefreshCap.setDisplayedPts(timing.presentationTimeUs);
            NativeRefreshCap.noteDisplay(nowNs);
            if (timing.dequeuedAtNs > 0L && nowNs > timing.dequeuedAtNs) {
                NativeRefreshCap.noteDecodeWait(nowNs - timing.dequeuedAtNs);   // backlog signal of the late-frame skip (skipped frames included)
            }
            long afterNs = System.nanoTime();
            NativeRefreshCap.noteReleaseCall(afterNs - nowNs);   // low-overhead histogram in the native status line (no perfetto needed)
            if (DebugLog.enabled()) {
                // How long MediaCodec.releaseOutputBuffer itself blocked the single IJK refresh thread (a binder call into the codec service).  While it blocks,
                // the next frame is not even evaluated.
                try {
                    Trace.setCounter("bsb_rel_call_us", (afterNs - nowNs) / 1000L);
                } catch (Throwable ignored) {
                    // Measurement only.
                }
            }
            synchronized (state) {
                timing.releaseCallNs = nowNs;
                timing.scheduledReleaseNs = nowNs;
            }
            timedReleases++;
            lastTimedReleaseNs = nowNs;
            recordFrameEvent(codec, index, timing, nowNs, TIME_UNSET, TIME_UNSET, TIME_UNSET,
                    nowNs, nowNs, "TIMED_RELEASE_FIRED", render, "releaseOutputBuffer(bool)",
                    "reason=native;releaseTs=" + releaseTs
                            + ";releaseCallDurationNs=" + (afterNs - nowNs)
                            + ";nativeScheduling=true;nativePassThroughReleases="
                            + nativePassThroughReleases
                            + (telemetryEnabled() ? NativeRefreshCap.frameTimingDetail() : ""));
            maybeReport(nowNs);
            return fired;
        }
        IjkEarlyUsTiming ijkEarlyUsTiming = readIjkEarlyUsTiming(
                timing.presentationTimeUs, nowNs);
        boolean fixedCadenceEnabled = FIXED_CADENCE_COMPENSATION;
        VSyncSampler sampler = vsyncSampler;
        long sampledVsyncTimeNs = sampler == null
                ? TIME_UNSET : sampler.effectiveVsyncAnchorTimeNs(nowNs);
        long vsyncDurationNs = sampler == null ? TIME_UNSET : sampler.vsyncDurationNs;
        long releaseTimeNs;
        long clockReleaseTimeNs;
        long estimatedPositionUs;
        long playbackPositionUs;
        long playbackPositionAgeMs;
        CadenceTiming cadenceTiming = null;
        long ptsClockReleaseTimeNs;
        long releaseLeadNs = releaseLeadNs(vsyncDurationNs);
        synchronized (state) {
            // The PTS-anchored clock is telemetry only.  It is pinned to the first release and
            // never re-synchronises after a stall, so after a few seconds every timestamp it
            // produces lies in the past and SurfaceFlinger presents the buffer immediately,
            // which is indistinguishable from an untimed release.
            ptsClockReleaseTimeNs = state.videoClockReleaseTimeNs(timing, nowNs);
            if (ijkEarlyUsTiming != null) {
                clockReleaseTimeNs = ijkEarlyUsTiming.releaseTimeNs;
                state.anchorNativeClock(timing, clockReleaseTimeNs);
                ijkEarlyUsAnchors++;
            } else {
                // IJK's refresh loop has already waited until this frame is due on the audio
                // clock, so "now" is the frame's intended display time.  Add a fixed lead so
                // the snapped target VSync is still reachable after SurfaceFlinger's latch
                // deadline; the adjustment below removes the loop's wake-up jitter.
                clockReleaseTimeNs = nowNs + releaseLeadNs;
            }
            if (clockReleaseTimeNs < nowNs) {
                clockLateFrames++;
            }
            if (fixedCadenceEnabled) {
                cadenceTiming = state.fixedCadenceReleaseTime(timing, clockReleaseTimeNs, nowNs,
                        sampledVsyncTimeNs, vsyncDurationNs);
            }
            if (cadenceTiming != null) {
                releaseTimeNs = cadenceTiming.releaseTimeNs;
            } else {
                if (fixedCadenceEnabled) {
                    cadenceFallbacks++;
                }
                releaseTimeNs = adjustedReleaseTime(state, timing, clockReleaseTimeNs, nowNs);
            }
            estimatedPositionUs = state.estimatedPositionUs(nowNs);
        }
        playbackPositionUs = currentPlaybackPositionUs(nowNs);
        playbackPositionAgeMs = playbackPositionAgeMs(nowNs);
        long earlyUs = playbackPositionUs == TIME_UNSET
                ? TIME_UNSET : timing.presentationTimeUs - playbackPositionUs;
        String ijkEarlyUsDetail = ijkEarlyUsTiming == null
                ? "ijkEarlyUsAnchor=unavailable"
                : "ijkEarlyUsAnchor=active"
                        + ";ijkEarlyUsPtsUs=" + ijkEarlyUsTiming.matchedPtsUs
                        + ";ijkEarlyUsNanos=" + ijkEarlyUsTiming.earlyUsNanos
                        + ";ijkEarlyUsFilterDelayNanos="
                        + ijkEarlyUsTiming.filterDelayNanos
                        + ";ijkEarlyUsSampleNs=" + ijkEarlyUsTiming.sampleTimestampNs
                        + ";ijkEarlyUsAgeMs=" + ijkEarlyUsTiming.ageNs / 1_000_000L
                        + ";ijkEarlyUsReleaseNs=" + ijkEarlyUsTiming.releaseTimeNs;
        String cadenceDetail = cadenceTiming == null
                ? "fixedCadence=unavailable"
                : "fixedCadence=active"
                        + ";cadenceExtraVsyncs=" + cadenceTiming.extraVsyncs
                        + ";cadenceCorrectionIntervalFrames="
                        + cadenceTiming.correctionIntervalFrames
                        + ";cadencePhaseCorrections=" + cadencePhaseCorrections
                        + ";cadenceFrameDurationNs=" + cadenceTiming.frameDurationNs
                        + ";cadenceVsyncDurationNs=" + cadenceTiming.vsyncDurationNs;
        long samplerAgeNs = sampledVsyncAgeNs(sampler, nowNs);
        String schedulerDetail = "decisionNowNs=" + nowNs
                + ";clockReleaseTimeNs=" + clockReleaseTimeNs
                + ";ptsClockReleaseNs=" + ptsClockReleaseTimeNs
                + ";releaseLeadNs=" + releaseLeadNs
                + ";gridAnchorNs=" + state.gridAnchorNs
                + ";gridResidualNs=" + state.lastGridResidualNs
                + ";holdBumpPeriods=" + state.lastHoldBumpPeriods
                + ";holdLeadBumps=" + holdLeadBumps
                + ";slotShift=" + state.lastSlotCollisions
                + ";slotCollisionShifts=" + slotCollisionShifts
                + ";gridAnchorRejects=" + gridAnchorRejects
                + ";nativeScheduling=" + nativeSchedulingActive
                + ";nativePassThroughReleases=" + nativePassThroughReleases
                + ";scheduledCallReleases=" + scheduledCallReleases
                + ";lateCallReleases=" + lateCallReleases
                + ";cancelledCallReleases=" + cancelledCallReleases
                + ";scheduledReleaseNs=" + releaseTimeNs
                + ";clockReleaseMinusDecisionNs="
                + differenceOrUnset(clockReleaseTimeNs, nowNs)
                + ";scheduledReleaseMinusDecisionNs="
                + differenceOrUnset(releaseTimeNs, nowNs)
                + ";scheduledReleaseMinusClockReleaseNs="
                + differenceOrUnset(releaseTimeNs, clockReleaseTimeNs)
                + ";chosenTargetVsyncNs="
                + (cadenceTiming == null ? TIME_UNSET : cadenceTiming.targetVsyncTimeNs)
                + ";sampledVsyncAgeNs=" + samplerAgeNs
                + ";playbackPositionAvailable=" + (playbackPositionUs != TIME_UNSET)
                + ";playbackPositionAgeMs=" + playbackPositionAgeMs;
        if (cadenceTiming != null) {
            schedulerDetail += ";cadenceAnchorFrameIndex=" + cadenceTiming.anchorFrameIndex
                    + ";cadenceAnchorPtsUs=" + cadenceTiming.anchorPresentationTimeUs
                    + ";cadenceAnchorVsyncNs=" + cadenceTiming.anchorVsyncTimeNs
                    + ";cadenceFrameDelta=" + cadenceTiming.frameDelta
                    + ";cadenceFrameSlot=" + cadenceTiming.frameSlot
                    + ";cadenceDriftNs=" + cadenceTiming.driftNs
                    + ";cadencePhaseErrorNs=" + cadenceTiming.phaseErrorNs
                    + ";cadenceReanchored=" + cadenceTiming.reanchored
                    + ";cadenceReanchorReason=" + cadenceTiming.reanchorReason;
        }
        synchronized (state) {
            timing.releaseCallNs = nowNs;
            timing.unadjustedReleaseNs = clockReleaseTimeNs;
            timing.scheduledReleaseNs = releaseTimeNs;
        }
        recordFrameEvent(codec, index, timing, nowNs, playbackPositionUs, playbackPositionAgeMs,
                estimatedPositionUs, clockReleaseTimeNs, releaseTimeNs,
                "SCHEDULED_TIMED_RELEASE", render, "releaseOutputBuffer(bool->time)",
                "earlyUs=" + earlyUs
                        + ";lifecycle=" + latestPlayerLifecycle
                        + ";" + ijkEarlyUsDetail
                        + ";" + cadenceDetail
                        + ";" + schedulerDetail);
        // SurfaceFlinger on NX809J never deferred a buffer on desiredPresentTime (no
        // "not current desiredPresentTime" commit in any trace), so the present slot is
        // decided purely by when the buffer is queued.  Hold the frame and issue the release
        // call itself at a fixed phase before the target tick instead of trusting the
        // timestamp.
        long targetTickNs = releaseTimeNs + DESIRED_PRESENT_MARGIN_NS;
        long periodNs = vsyncDurationNs >= MIN_VSYNC_DURATION_NS
                && vsyncDurationNs <= MAX_VSYNC_DURATION_NS
                ? vsyncDurationNs : DEFAULT_RELEASE_LEAD_PERIOD_NS;
        long callTimeNs = targetTickNs - (periodNs * CALL_LEAD_BEFORE_PRESENT_PERCENT) / 100L;
        // Every release goes through the single release handler, whose queue is ordered by
        // call time, so a late frame can never overtake an earlier frame that is still
        // waiting for its slot.  Late frames are posted for "now".
        boolean late = ZERO_HOLD_EXPERIMENT || callTimeNs <= nowNs;
        if (late) {
            lateCallReleases++;
        }
        if (scheduleTimedRelease(codec, index, timing, releaseTimeNs,
                late ? nowNs : callTimeNs, late ? "immediate" : "scheduled")) {
            scheduledCallReleases++;
            return true;
        }
        return fireTimedRelease(codec, index, timing, releaseTimeNs, nowNs, "no-scheduler");
    }

    /** Calls the real release now; returns true when the original call must be swallowed. */
    private static boolean fireTimedRelease(MediaCodec codec, int index, FrameTiming timing,
                                            long releaseTimeNs, long decisionNowNs,
                                            String reason) {
        long nowNs = System.nanoTime();
        emitReleaseMarkBeforeCall(timing.presentationTimeUs, false);
        try {
            INTERNAL_TIMED_RELEASE.set(Boolean.TRUE);
            if (RELEASE_WITH_TIMESTAMP) {
                codec.releaseOutputBuffer(index, releaseTimeNs);
            } else {
                codec.releaseOutputBuffer(index, true);
            }
            timedReleases++;
            lastTimedReleaseNs = nowNs;
            recordFrameEvent(codec, index, timing, nowNs, TIME_UNSET, TIME_UNSET, TIME_UNSET,
                    timing.unadjustedReleaseNs, releaseTimeNs, "TIMED_RELEASE_FIRED", true,
                    "releaseOutputBuffer(time)", "reason=" + reason
                            + ";callLatenessNs=" + (timing.scheduledCallNs == TIME_UNSET
                                    ? TIME_UNSET : nowNs - timing.scheduledCallNs)
                            + ";fireMinusDecisionNs=" + (nowNs - decisionNowNs)
                            + ";fireToTargetTickNs="
                            + (releaseTimeNs + DESIRED_PRESENT_MARGIN_NS - nowNs));
            maybeReport(nowNs);
            return true;
        } catch (Throwable throwable) {
            recordFrameEvent(codec, index, timing, System.nanoTime(), TIME_UNSET, TIME_UNSET,
                    TIME_UNSET, timing.unadjustedReleaseNs, releaseTimeNs,
                    "TIMED_RELEASE_FAILED", true, "releaseOutputBuffer(time)",
                    throwable.getClass().getSimpleName() + ";reason=" + reason);
            logOnce("timed-release-failed:" + codecName(codec),
                    "timed MediaCodec release failed=" + throwable.getClass().getSimpleName());
            return true;
        } finally {
            INTERNAL_TIMED_RELEASE.set(Boolean.FALSE);
        }
    }

    private static synchronized void ensureReleaseScheduler() {
        if (releaseThread != null) {
            return;
        }
        releaseThread = new HandlerThread("Bili-Media3-Release",
                Process.THREAD_PRIORITY_URGENT_DISPLAY);
        releaseThread.start();
        releaseHandler = new Handler(releaseThread.getLooper());
    }

    private static boolean scheduleTimedRelease(final MediaCodec codec, final int index,
                                                final FrameTiming timing,
                                                final long releaseTimeNs, long callTimeNs,
                                                final String reason) {
        ensureReleaseScheduler();
        Handler handler = releaseHandler;
        if (handler == null) {
            return false;
        }
        final long decisionNowNs = System.nanoTime();
        timing.scheduledCallNs = callTimeNs;
        // uptimeMillis and nanoTime are both CLOCK_MONOTONIC; Handler timing is millisecond
        // granular, so round up to avoid firing a fraction early into the previous slot.
        long delayMs = (callTimeNs - decisionNowNs + 999_999L) / 1_000_000L;
        long uptimeMs = android.os.SystemClock.uptimeMillis() + delayMs;
        return handler.postAtTime(new Runnable() {
            @Override
            public void run() {
                if (CODECS.get(codec) == null) {
                    cancelledCallReleases++;
                    return;
                }
                fireTimedRelease(codec, index, timing, releaseTimeNs, decisionNowNs, reason);
            }
        }, codec, uptimeMs);
    }

    private static void cancelScheduledReleases(MediaCodec codec) {
        Handler handler = releaseHandler;
        if (handler != null) {
            handler.removeCallbacksAndMessages(codec);
        }
    }

    private static long nativeSchedulingLastAttemptNs;

    private static long nativeGridAnchorNs = TIME_UNSET;

    /** Sampler thread: keep the native scheduler's grid fresh without touching IJK's thread. */
    private static void publishNativeGridSample(long expectedPresentationNs, long periodNs,
                                                long vsyncNs, long deadlineNs) {
        if (!nativeSchedulingActive || expectedPresentationNs == TIME_UNSET
                || periodNs < MIN_VSYNC_DURATION_NS || periodNs > MAX_VSYNC_DURATION_NS) {
            return;
        }
        applyScheduleTuningFromPreferencesThrottled(System.nanoTime());
        if (nativeGridAnchorNs == TIME_UNSET) {
            nativeGridAnchorNs = expectedPresentationNs;
        } else {
            long residualNs = Math.floorMod(expectedPresentationNs - nativeGridAnchorNs, periodNs);
            if (residualNs > periodNs / 2L) {
                residualNs -= periodNs;
            }
            if (Math.abs(residualNs) <= GRID_ANCHOR_TOLERANCE_NS) {
                nativeGridAnchorNs = expectedPresentationNs;
            }
        }
        NativeRefreshCap.setVsyncGrid(nativeGridAnchorNs, periodNs, vsyncNs, deadlineNs);
    }

    /** Media3 Vsync was switched off while playing: stop the native release scheduling. */
    static synchronized void disableNativeScheduling() {
        NativeRefreshCap.setReleaseScheduling(false, 0, NativeRefreshCap.GRID_MEDIA3);
        nativeSchedulingActive = false;
        nativeSchedulingRequested = false;
        NativeRefreshCap.invalidateFrameTimer();
    }


    /**
     * Push live schedule prefs (ready window / late-drop) into this process's
     * native atomics about every 2 s, so ijkservice follows the panel as well as the UI process.
     */
    private static void applyScheduleTuningFromPreferencesThrottled(long nowNs) {
        if (nowNs - scheduleTuningAppliedNs < 2_000_000_000L && scheduleTuningAppliedNs != 0L) {
            return;
        }
        scheduleTuningAppliedNs = nowNs;
        NativeRefreshCap.applyScheduleTuningFromPreferences();
    }

    private static synchronized void ensureNativeScheduling() {
        applyScheduleTuningFromPreferencesThrottled(System.nanoTime());
        if (!NATIVE_RELEASE_SCHEDULING || nativeSchedulingActive) {
            return;
        }
        // The IJK refresh hooks are installed when the player service starts; the first
        // release can arrive earlier, so keep retrying (once per second) until the hook is up.
        long nowNs = System.nanoTime();
        if (nativeSchedulingRequested && nowNs - nativeSchedulingLastAttemptNs < 1_000_000_000L) {
            return;
        }
        nativeSchedulingRequested = true;
        nativeSchedulingLastAttemptNs = nowNs;
        // Direct SurfaceView: queue mid-way in SurfaceFlinger's latch window (2.2 periods).
        // External renderers redraw on the app's Choreographer VSync, so the frame must reach
        // the SurfaceTexture half a period before that callback instead (2.7 periods).
        ModulePreferences.RendererMode rendererMode =
                ModulePreferences.loadVideoDetailRendererModeForHook();
        int callLeadPercent = rendererMode == ModulePreferences.RendererMode.SURFACE
                ? NATIVE_CALL_LEAD_DIRECT_PERCENT
                : rendererMode == ModulePreferences.RendererMode.TEXTURE_EXTERNAL
                ? NATIVE_CALL_LEAD_TEXTURE_PERCENT : NATIVE_CALL_LEAD_EXTERNAL_PERCENT;
        // External renderers: target the arrival mid-way between two app VSyncs directly (the
        // app-VSync-to-present distance is fixed in ms, so a lead in periods only fits one
        // refresh rate: 60 fps content switches the panel to 60 Hz).
        // Direct SurfaceView: queue mid-way in SurfaceFlinger's latch window, derived from the
        // FrameTimeline deadline instead of a device-specific lead in periods.
        // Surface external draws immediately on arrival (ExternalRenderImmediate), so its GL
        // output is scheduled into SurfaceFlinger's latch window like a direct SurfaceView
        // buffer; Texture keeps the app-VSync arrival grid (HWUI consumes on the app VSync).
        // SurfaceView paths follow the Media3 release model; Texture composes through HWUI,
        // whose window buffer carries no per-frame timestamp, so it keeps the arrival grid.
        int gridMode = rendererMode == ModulePreferences.RendererMode.TEXTURE_EXTERNAL
                ? NativeRefreshCap.GRID_ARRIVAL : NativeRefreshCap.GRID_MEDIA3;
        NativeRefreshCap.setMedia3SkipSameVsync(
                rendererMode == ModulePreferences.RendererMode.SURFACE_EXTERNAL);
        NativeRefreshCap.applyScheduleTuningFromPreferences();
        String result = NativeRefreshCap.setReleaseScheduling(true, callLeadPercent, gridMode);
        nativeSchedulingActive = result != null && result.contains("active");
        logOnce("native-release-scheduling:" + nativeSchedulingActive, result
                + " (java fallback=" + (nativeSchedulingActive ? "off" : "on") + ")");
    }

    private static final int NATIVE_CALL_LEAD_DIRECT_PERCENT = 220;
    private static final int NATIVE_CALL_LEAD_EXTERNAL_PERCENT = 245;   // measured: 350 was worse at 1/3x (29-34% vs 45-50% slots_ok), so the lead is not the lever here
    // Texture: the GL thread draws immediately on arrival (ExternalRenderImmediate) and the
    // TextureView is redrawn on every UI VSync (TextureViewVsyncInvalidator), so the GL output
    // only has to sit mid-way between two RenderThread updateTexImage calls: same phase as
    // Surface external.
    private static final int NATIVE_CALL_LEAD_TEXTURE_PERCENT = 245;

    private static long releaseLeadNs(long vsyncDurationNs) {
        long periodNs = vsyncDurationNs >= MIN_VSYNC_DURATION_NS
                && vsyncDurationNs <= MAX_VSYNC_DURATION_NS
                ? vsyncDurationNs : DEFAULT_RELEASE_LEAD_PERIOD_NS;
        return (periodNs * RELEASE_LEAD_PERCENT) / 100L;
    }

    private static long adjustedReleaseTime(CodecState state, FrameTiming timing,
                                             long unadjustedReleaseTimeNs, long nowNs) {
        if (timing.presentationTimeUs != state.pendingLastPresentationTimeUs) {
            state.lastAdjustedFrameIndex = state.pendingLastAdjustedFrameIndex;
            state.lastAdjustedReleaseTimeNs = state.pendingLastAdjustedReleaseTimeNs;
            state.lastAdjustedPresentationTimeUs = state.pendingLastPresentationTimeUs;
            state.lastVsyncHysteresisOffsetNs = state.pendingVsyncHysteresisOffsetNs;
        }

        long adjustedReleaseTimeNs = unadjustedReleaseTimeNs;
        if (state.lastAdjustedFrameIndex != INDEX_UNSET) {
            long elapsedReleaseTimeSinceLastFrameNs;
            if (state.frameDurationNs != TIME_UNSET) {
                long frameCount = timing.frameIndex - state.lastAdjustedFrameIndex;
                elapsedReleaseTimeSinceLastFrameNs = state.frameDurationNs * frameCount;
            } else {
                elapsedReleaseTimeSinceLastFrameNs =
                        (timing.presentationTimeUs - state.lastAdjustedPresentationTimeUs) * 1_000L;
            }
            long candidateAdjustedReleaseTimeNs =
                    state.lastAdjustedReleaseTimeNs + elapsedReleaseTimeSinceLastFrameNs;
            if (adjustmentAllowed(unadjustedReleaseTimeNs, candidateAdjustedReleaseTimeNs)) {
                adjustedReleaseTimeNs = candidateAdjustedReleaseTimeNs;
                predictedDeadlines++;
            } else {
                state.resetAdjustment();
            }
        }
        state.pendingLastAdjustedFrameIndex = timing.frameIndex;
        state.pendingLastAdjustedReleaseTimeNs = adjustedReleaseTimeNs;
        state.pendingLastPresentationTimeUs = timing.presentationTimeUs;

        VSyncSampler sampler = vsyncSampler;
        if (sampler == null) {
            return adjustedReleaseTimeNs;
        }
        // SurfaceFlinger holds a buffer only while desiredPresentTime is later than the
        // expectedPresentTime of the frame it is composing, so the grid to snap to is the
        // FrameTimeline expected-presentation grid, not the Choreographer frame-time grid that
        // Media3 uses with its 80% offset.  With that offset the timestamp is already due at
        // the previous composition and the buffer is shown as soon as it is queued, which
        // measured identically to an untimed release on NX809J.
        long vsyncDurationNs = sampler.vsyncDurationNs;
        long gridAnchorNs = state.updateGridAnchor(sampler.expectedPresentationTimeNs,
                vsyncDurationNs);
        if (gridAnchorNs == TIME_UNSET) {
            return adjustedReleaseTimeNs;
        }
        if (nativeSchedulingActive) {
            NativeRefreshCap.setVsyncGrid(gridAnchorNs, vsyncDurationNs,
                    sampler.sampledVsyncTimeNs, sampler.deadlineNs);
        }
        long snappedTimeNs = findClosestVsyncAndUpdateHysteresis(state, adjustedReleaseTimeNs,
                gridAnchorNs, vsyncDurationNs);
        vsyncSnaps++;
        // Slots must be strictly increasing: a re-anchored line may otherwise snap the next
        // frame onto the slot already taken by the previous one and SurfaceFlinger drops one.
        state.lastSlotCollisions = 0L;
        if (state.lastTargetTickNs != TIME_UNSET && snappedTimeNs <= state.lastTargetTickNs) {
            long shiftPeriods = (state.lastTargetTickNs - snappedTimeNs) / vsyncDurationNs + 1L;
            snappedTimeNs += shiftPeriods * vsyncDurationNs;
            state.pendingLastAdjustedReleaseTimeNs += shiftPeriods * vsyncDurationNs;
            state.lastSlotCollisions = shiftPeriods;
            slotCollisionShifts++;
        }
        // SurfaceFlinger commits about 1.25 periods before each present, so a target closer
        // than ~2.5 periods to "now" is already due at the next commit and is shown as soon
        // as it is queued.  Push such targets out by whole periods and move the smoothing
        // line with them so the following frames keep their spacing instead of all bumping.
        long minTargetNs = nowNs + (vsyncDurationNs * MIN_HOLD_LEAD_PERCENT) / 100L
                + MIN_HOLD_LEAD_MARGIN_NS;
        state.lastHoldBumpPeriods = 0L;
        if (snappedTimeNs < minTargetNs) {
            long bumpPeriods = (minTargetNs - snappedTimeNs + vsyncDurationNs - 1L)
                    / vsyncDurationNs;
            snappedTimeNs += bumpPeriods * vsyncDurationNs;
            state.pendingLastAdjustedReleaseTimeNs += bumpPeriods * vsyncDurationNs;
            state.lastHoldBumpPeriods = bumpPeriods;
            holdLeadBumps++;
        }
        state.lastTargetTickNs = snappedTimeNs;
        return snappedTimeNs - DESIRED_PRESENT_MARGIN_NS;
    }

    private static boolean adjustmentAllowed(long unadjustedReleaseTimeNs,
                                             long adjustedReleaseTimeNs) {
        // Media3 tolerates 20 ms here, but IJK's decisions jitter by only +-0.6 ms, and a
        // wide tolerance lets the line run ahead of late (decoder-starved) decisions, which
        // lengthens the hold and starves the decoder further.  Re-anchor early instead.
        return Math.abs(unadjustedReleaseTimeNs - adjustedReleaseTimeNs)
                <= LINE_TOLERANCE_NS;
    }

    private static long findClosestVsyncAndUpdateHysteresis(CodecState state, long releaseTimeNs,
                                                             long sampledVsyncTimeNs,
                                                             long vsyncDurationNs) {
        long vsyncCount = (releaseTimeNs - sampledVsyncTimeNs) / vsyncDurationNs;
        long snappedTimeNs = sampledVsyncTimeNs + vsyncDurationNs * vsyncCount;
        long snappedBeforeNs;
        long snappedAfterNs;
        if (releaseTimeNs <= snappedTimeNs) {
            snappedBeforeNs = snappedTimeNs - vsyncDurationNs;
            snappedAfterNs = snappedTimeNs;
        } else {
            snappedBeforeNs = snappedTimeNs;
            snappedAfterNs = snappedTimeNs + vsyncDurationNs;
        }
        long snappedAfterDiffNs = snappedAfterNs - releaseTimeNs;
        long snappedBeforeDiffNs = releaseTimeNs - snappedBeforeNs;
        long snappedDiffsDiffNs = Math.abs(snappedAfterDiffNs - snappedBeforeDiffNs);
        boolean shouldEvaluateHysteresis = snappedDiffsDiffNs < vsyncDurationNs / 2L;
        if (shouldEvaluateHysteresis) {
            long hysteresisRangeNs = vsyncDurationNs / 4L;
            boolean isInHysteresisRange = snappedDiffsDiffNs < hysteresisRangeNs;
            if (isInHysteresisRange) {
                if (state.lastVsyncHysteresisOffsetNs != 0) {
                    state.pendingVsyncHysteresisOffsetNs = state.lastVsyncHysteresisOffsetNs;
                } else {
                    state.pendingVsyncHysteresisOffsetNs = snappedAfterDiffNs < snappedBeforeDiffNs
                            ? -hysteresisRangeNs : hysteresisRangeNs;
                }
            } else {
                state.pendingVsyncHysteresisOffsetNs = 0;
            }
        } else {
            state.pendingVsyncHysteresisOffsetNs = state.lastVsyncHysteresisOffsetNs;
        }
        return snappedAfterDiffNs + state.pendingVsyncHysteresisOffsetNs < snappedBeforeDiffNs
                ? snappedAfterNs : snappedBeforeNs;
    }

    private static void maybeSetSurfaceFrameRate(CodecState state) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || state.surface == null
                || state.frameDurationNs == TIME_UNSET) {
            return;
        }
        float frameRate = 1_000_000_000f / state.frameDurationNs;
        if (frameRate < 23f || frameRate > 240f
                || Math.abs(frameRate - state.lastSurfaceFrameRate) < 0.5f) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                state.surface.setFrameRate(frameRate,
                        Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                        Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS);
            } else {
                state.surface.setFrameRate(frameRate,
                        Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
            }
            state.lastSurfaceFrameRate = frameRate;
            surfaceFrameRateHints++;
        } catch (Throwable throwable) {
            logOnce("surface-frame-rate", "Surface frame-rate hint unavailable="
                    + throwable.getClass().getSimpleName());
        }
    }

    private static void clearSurfaceFrameRate(CodecState state) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || state.surface == null
                || state.lastSurfaceFrameRate == 0f) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                state.surface.setFrameRate(0f,
                        Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                        Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS);
            } else {
                state.surface.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
            }
        } catch (Throwable ignored) {
            // The codec lifecycle must remain safe if the Surface has already been released.
        }
        state.lastSurfaceFrameRate = 0f;
    }

    private static void removeCodec(MediaCodec codec, CodecState state) {
        if (state != null) {
            synchronized (state) {
                clearSurfaceFrameRate(state);
                state.resetPosition();
            }
        }
        CODECS.remove(codec);
        PENDING_RENDER_LISTENERS.remove(codec);
        synchronized (ACTIVE_VIDEO_CODECS) {
            ACTIVE_VIDEO_CODECS.remove(codec);
            if (ACTIVE_VIDEO_CODECS.isEmpty()) {
                requestVsyncUnregistration();
            }
        }
    }

    /**
     * Per-frame CSV telemetry is a diagnostic: it builds a few hundred bytes of strings per
     * frame on the release thread, and the GC it caused showed up as 2-6 ms runnable delays on
     * the pipeline threads.  Only the hidden DebugLog switch turns it on (the fps overlay does
     * not); the release scheduling itself needs none of it.
     */
    private static boolean telemetryEnabled() {
        return DebugLog.enabled();
    }

    // Former user switches of the Java fallback path, fixed at the values every successful
    // measurement used.  The native scheduling path reads none of them.
    private static final boolean FIXED_CADENCE_COMPENSATION = false;
    private static final boolean IJK_EARLY_US_VSYNC_ANCHOR = false;

    private static boolean experimentsEnabled() {
        // Frame-sync release scheduling is a user feature, not a diagnostic; the overlay only
        // gates the telemetry-heavy experiments.
        return ModulePreferences.loadPlaybackDebugOverlayForHook()
                || ModulePreferences.loadMedia3FrameSyncForHook();
    }

    private static boolean isVideo(MediaFormat format) {
        if (format == null || !format.containsKey(MediaFormat.KEY_MIME)) {
            return false;
        }
        String mime = format.getString(MediaFormat.KEY_MIME);
        return mime != null && mime.startsWith("video/");
    }

    private static boolean isAudio(MediaFormat format) {
        if (format == null || !format.containsKey(MediaFormat.KEY_MIME)) {
            return false;
        }
        String mime = format.getString(MediaFormat.KEY_MIME);
        return mime != null && mime.startsWith("audio/");
    }

    private static int formatBitrate(MediaFormat format) {
        if (format == null || !format.containsKey(MediaFormat.KEY_BIT_RATE)) {
            return -1;
        }
        try {
            return format.getInteger(MediaFormat.KEY_BIT_RATE);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static int formatSampleRate(MediaFormat format) {
        if (format == null || !format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            return -1;
        }
        try {
            return format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static boolean isQtiDecoder(MediaCodec codec) {
        String name = codecName(codec).toLowerCase();
        return name.startsWith("c2.qti.") || name.startsWith("omx.qcom.");
    }

    private static String codecName(MediaCodec codec) {
        String name;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                name = codec.getName();
            } else {
                name = codec.getClass().getName();
            }
        } catch (Throwable ignored) {
            // Keep lifecycle hooks safe when a vendor codec rejects getName early.
            name = codec.getClass().getName();
        }
        return name + "#" + Integer.toHexString(System.identityHashCode(codec));
    }

    @SuppressWarnings("unchecked")
    private static List<String> supportedVendorParameters(MediaCodec codec) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return Collections.emptyList();
        }
        try {
            Object result = Xp.callMethod(codec, "getSupportedVendorParameters");
            if (!(result instanceof Collection)) {
                return Collections.emptyList();
            }
            List<String> names = new ArrayList<String>();
            for (Object value : (Collection<Object>) result) {
                if (value instanceof String) {
                    names.add((String) value);
                }
            }
            return names;
        } catch (Throwable throwable) {
            logOnce("qti-fence-query:" + codecName(codec),
                    "QTI vendor-parameter query unavailable="
                            + throwable.getClass().getSimpleName());
            return Collections.emptyList();
        }
    }

    private static synchronized void ensureVsyncSampler() {
        if (vsyncThread != null) {
            return;
        }
        Context context = currentApplicationContext();
        if (context == null) {
            vsyncSamplerMode = "no-context";
            return;
        }
        vsyncThread = new HandlerThread("Bili-Media3-VSync", Process.THREAD_PRIORITY_DISPLAY);
        vsyncThread.start();
        vsyncHandler = new Handler(vsyncThread.getLooper());
        vsyncHandler.post(new Runnable() {
            @Override
            public void run() {
                DisplayManager displayManager = (DisplayManager) context.getSystemService(
                        Context.DISPLAY_SERVICE);
                if (displayManager == null) {
                    vsyncSamplerMode = "no-display-manager";
                    return;
                }
                Choreographer choreographer;
                try {
                    choreographer = Choreographer.getInstance();
                } catch (RuntimeException exception) {
                    vsyncSamplerMode = "choreographer-error";
                    return;
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    vsyncSampler = new VSyncSamplerV33(choreographer, displayManager, vsyncHandler);
                    vsyncSamplerMode = "frame-timeline";
                } else {
                    vsyncSampler = new VSyncSamplerBase(choreographer, displayManager, vsyncHandler);
                    vsyncSamplerMode = "frame-callback";
                }
                synchronized (ACTIVE_VIDEO_CODECS) {
                    if (!ACTIVE_VIDEO_CODECS.isEmpty()) {
                        vsyncSampler.register();
                    }
                }
            }
        });
    }

    private static void requestVsyncRegistration() {
        Handler handler = vsyncHandler;
        if (handler == null) {
            return;
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                VSyncSampler sampler = vsyncSampler;
                if (sampler != null) {
                    synchronized (ACTIVE_VIDEO_CODECS) {
                        if (!ACTIVE_VIDEO_CODECS.isEmpty()) {
                            sampler.register();
                        }
                    }
                }
            }
        });
    }

    private static void requestVsyncUnregistration() {
        Handler handler = vsyncHandler;
        if (handler == null) {
            return;
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                VSyncSampler sampler = vsyncSampler;
                if (sampler != null) {
                    synchronized (ACTIVE_VIDEO_CODECS) {
                        if (ACTIVE_VIDEO_CODECS.isEmpty()) {
                            sampler.unregister();
                        }
                    }
                }
            }
        });
    }

    private static Context currentApplicationContext() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object application = Xp.callStaticMethod(activityThread,
                    "currentApplication");
            if (application instanceof Context) {
                return ((Context) application).getApplicationContext();
            }
        } catch (Throwable throwable) {
            logOnce("vsync-context", "VSync sampler context unavailable="
                    + throwable.getClass().getSimpleName());
        }
        return null;
    }

    static String installPlaybackClockHooks(ClassLoader classLoader) {
        if (playbackClockHooksInstalled) {
            return "IJK playback clock hooks already installed";
        }
        // The setSpeed/_setPropertyFloat hooks below are on the main-process API class.  In this (ijkservice)
        // process the speed arrives over AIDL at the native player's Java peer,
        // services.IjkMediaPlayerClient.setPropertyFloat(10003, rate) -> _setPropertyFloat;
        // IjkMediaPlayer.setSpeed never runs here, so without this the rate gate stayed 1.0.
        // Installed first and on its own: the IjkMediaPlayer hooks below can fail on a missing
        // method (NoSuchMethodError), which used to abort this one too.
        AudioTrackProbe.install();
        String clientHook;
        try {
            Class<?> clientClass = Xp.findClass(
                    "tv.danmaku.ijk.media.player.services.IjkMediaPlayerClient", classLoader);
            hookPlayerMethod(clientClass, "setPropertyFloat",
                    new Class<?>[]{int.class, float.class}, new Xp.Hook() {
                        @Override
                        protected void before(Xp.Param param) {
                            if (((Integer) param.args[0]).intValue()
                                    == FFP_PROP_FLOAT_PLAYBACK_RATE) {
                                float rate = forcedSpeed(((Number) param.args[1]).floatValue());
                                param.args[1] = rate;
                                onClientPlaybackRate(param.thisObject, rate);
                            }
                        }
                    });
            clientHook = " +IjkMediaPlayerClient.setPropertyFloat";
        } catch (Throwable throwable) {
            clientHook = " (IjkMediaPlayerClient.setPropertyFloat unavailable="
                    + throwable.getClass().getSimpleName() + ")";
        }
        // In this (ijkservice) process nobody calls IjkMediaPlayer.getCurrentPosition(): Bilibili's
        // main process asks over AIDL (IIjkMediaPlayer.getCurrentPosition), and the Stub
        // implementation here is IjkMediaPlayerClient.getCurrentPosition() (9.13 and 9.17 both
        // declare it).  Sampling the answer gives this process the player position, which is
        // the IJK master (audio) clock in milliseconds.
        try {
            Class<?> clientClass = Xp.findClass(
                    "tv.danmaku.ijk.media.player.services.IjkMediaPlayerClient", classLoader);
            hookPlayerMethod(clientClass, "getCurrentPosition", new Class<?>[0],
                    new Xp.Hook() {
                        @Override
                        protected void after(Xp.Param param) {
                            Object result = param.getResult();
                            if (result instanceof Number) {
                                latestPlaybackPositionUs = ((Number) result).longValue() * 1_000L;
                                latestPlaybackPositionSampleNs = System.nanoTime();
                                onClientPositionSample(param.thisObject, latestPlaybackPositionSampleNs);
                                onAudioClockSample(latestPlaybackPositionSampleNs,
                                        ((Number) result).longValue());
                            }
                        }
                    });
            clientHook += " +IjkMediaPlayerClient.getCurrentPosition";
        } catch (Throwable throwable) {
            clientHook += " (IjkMediaPlayerClient.getCurrentPosition unavailable="
                    + throwable.getClass().getSimpleName() + ")";
        }
        try {
            final Class<?> playerClass = Xp.findClass(
                    "tv.danmaku.ijk.media.player.IjkMediaPlayer", classLoader);
            hookPlayerMethod(playerClass, "getCurrentPosition", new Class<?>[0],
                    new Xp.Hook() {
                        @Override
                        protected void after(Xp.Param param) {
                            Object result = param.getResult();
                            if (result instanceof Number) {
                                latestPlaybackPositionUs = ((Number) result).longValue() * 1_000L;
                                latestPlaybackPositionSampleNs = System.nanoTime();
                                onAudioClockSample(latestPlaybackPositionSampleNs,
                                        ((Number) result).longValue());
                            }
                        }
                    });
            hookPlayerLifecycle(playerClass, "start", new Class<?>[0]);
            hookPlayerLifecycle(playerClass, "pause", new Class<?>[0]);
            hookPlayerLifecycle(playerClass, "stop", new Class<?>[0]);
            hookPlayerLifecycle(playerClass, "release", new Class<?>[0]);
            hookPlayerLifecycle(playerClass, "prepareAsync", new Class<?>[0]);
            hookPlayerLifecycle(playerClass, "seekTo", new Class<?>[]{long.class});
            // Playback speed: setSpeed(float) posts FFP_PROP_FLOAT_PLAYBACK_RATE (10003) to the
            // native player; both entry points feed the native scheduler's rate gate.
            hookPlayerMethod(playerClass, "setSpeed", new Class<?>[]{float.class},
                    new Xp.Hook() {
                        @Override
                        protected void before(Xp.Param param) {
                            float rate = forcedSpeed(((Number) param.args[0]).floatValue());
                            param.args[0] = rate;
                            onPlaybackRate(rate, "setSpeed");
                        }
                    });
            // _setPropertyFloat is not a Java method on 9.13/9.17 builds (rate goes through
            // setSpeed here and IjkMediaPlayerClient.setPropertyFloat in ijkservice); optional.
            String rawRateHook;
            try {
                hookPlayerMethod(playerClass, "_setPropertyFloat",
                        new Class<?>[]{int.class, float.class}, new Xp.Hook() {
                            @Override
                            protected void before(Xp.Param param) {
                                if (((Integer) param.args[0]).intValue() == FFP_PROP_FLOAT_PLAYBACK_RATE) {
                                    onPlaybackRate(((Number) param.args[1]).floatValue(),
                                            "_setPropertyFloat");
                                }
                            }
                        });
                rawRateHook = " +_setPropertyFloat";
            } catch (NoSuchMethodError missing) {
                rawRateHook = " (_setPropertyFloat absent; setSpeed+client path used)";
            }
            playbackClockHooksInstalled = true;
            return "installed IJK playback clock and lifecycle telemetry hooks" + clientHook + rawRateHook;
        } catch (Throwable throwable) {
            playbackClockHooksInstalled = true;
            return "IJK playback clock telemetry unavailable="
                    + throwable.getClass().getSimpleName() + clientHook;
        }
    }

    private static final int FFP_PROP_FLOAT_PLAYBACK_RATE = 10003;

    /**
     * Measurement switch: while {@code debug.biliextended.speed_milli} is set (333 = 0.333x), every playback-rate assignment is replaced
     * by it, so a round starts at the wanted speed instead of walking the 倍速 menu (3.7 s per round).  0 / unset = disabled.
     */
    private static float forcedSpeed(float requested) {
        int milli = DebugLog.readInt("debug.biliextended.speed_milli", 0);
        if (milli <= 0 || milli > 10000) {
            return requested;
        }
        float want = milli / 1000.0f;
        if (Math.abs(want - requested) > 0.0001f && DebugLog.enabled()) {
            Xp.log(TAG + ": forced playback rate " + want + " (player asked for " + requested + ")");
        }
        return want;
    }
    private static float latestPlaybackRate = 1.0f;

    // Per native player: the service can host a preview player next to the main one, and the
    // scheduler gate is global, so it stays bypassed while any live player runs off 1.0x.
    private static final Map<Object, Float> CLIENT_RATES =
            Collections.synchronizedMap(new WeakHashMap<Object, Float>());

    // When each client was last asked for its position or given a rate.  CLIENT_RATES is weak, but a released player stays in it until the GC runs, and one that was
    // left at 1.5x pinned the scheduler to 1.5x for the player started after it (measured 2026-11-10: native idealStepUs=5555 while the user played at 1.0x -- 28 over-limit
    // chain resets per second, 32 % of the frames dropped as "excess", 66 % of the panel rate, 0.3-0.5 s holes).  Bilibili polls the position of a live player about every
    // 100 ms, so a client that has not been asked for a few seconds is gone.
    private static final Map<Object, Long> CLIENT_SEEN_NS =
            Collections.synchronizedMap(new WeakHashMap<Object, Long>());
    private static final long CLIENT_LIVE_NS = 3_000_000_000L;
    private static final long CLIENT_RATE_RECHECK_NS = 500_000_000L;
    private static long clientRatesCheckedNs;

    /** The rate of the clients that are still alive: the first one that is off 1.0x, else 1.0. */
    private static float liveClientRate(long nowNs) {
        float effective = 1.0f;
        synchronized (CLIENT_RATES) {
            for (Map.Entry<Object, Float> entry : CLIENT_RATES.entrySet()) {
                final Float value = entry.getValue();
                if (value == null || Math.abs(value - 1.0f) < 0.01f) {
                    continue;
                }
                final Long seen = CLIENT_SEEN_NS.get(entry.getKey());
                if (seen == null || nowNs - seen > CLIENT_LIVE_NS) {
                    continue;   // released, not collected yet
                }
                effective = value;
                break;
            }
        }
        return effective;
    }

    private static void onClientPlaybackRate(Object client, float rate) {
        if (!(rate > 0f)) {
            return;
        }
        final long now = System.nanoTime();
        CLIENT_RATES.put(client, rate);
        CLIENT_SEEN_NS.put(client, now);
        onPlaybackRate(liveClientRate(now), "IjkMediaPlayerClient.setPropertyFloat");
    }

    /** A position sample proves its client is alive, and re-reads the live rate so that a released 2x player stops holding the scheduler at 2x. */
    private static void onClientPositionSample(Object client, long nowNs) {
        CLIENT_SEEN_NS.put(client, nowNs);
        if (nowNs - clientRatesCheckedNs >= CLIENT_RATE_RECHECK_NS) {
            clientRatesCheckedNs = nowNs;
            onPlaybackRate(liveClientRate(nowNs), "live IJK clients");
        }
    }

    // Media3 standard (VideoFrameReleaseControl.calculateEarlyTimeUs): every frame's release time
    // is derived from the audio position at that moment, earlyUs = (PTS - position) / speed, so a
    // wrong speed never accumulates and the video cannot run ahead of or behind the sound.  The
    // scheduler lives in native_refresh_cap.cpp (scheduleMedia3); this side feeds it:
    //  * every IJK position sample (IjkMediaPlayerClient.getCurrentPosition, the audio master
    //    clock in content time, anchored by the audio callback with the output latency removed)
    //    together with the time it was taken and the speed the clock really runs at;
    //  * the speed is the slope of the position over the last ~0.8 s, not the requested rate
    //    (Bilibili asks for 3.0 on a long press while the clock runs at ~2.0); at 1x it is
    //    exactly 1.0, so the noise of the slope never reaches normal playback.
    private static final long SPEED_WINDOW_NS = 800_000_000L;
    private static final long SPEED_MIN_SPAN_NS = 250_000_000L;
    private static final double SPEED_JUMP_MS = 400.0;
    private static final int SPEED_RING = 64;
    private static final Object CLOCK_LOCK = new Object();
    private static final long[] speedNs = new long[SPEED_RING];
    private static final long[] speedPosMs = new long[SPEED_RING];
    private static int speedCount;
    private static float requestedRate = 1.0f;
    // AudioTrackPositionTracker: a new position is accepted only within +-10 % of the elapsed
    // time of what the smoothed position predicts (MAX_POSITION_SMOOTHING_SPEED_CHANGE_PERCENT);
    // a drift beyond 1 s (a seek) is taken as it is.
    private static final double POSITION_SMOOTHING_PERCENT = 10.0;
    private static final double POSITION_JUMP_MS = 1000.0;
    private static double smoothedPositionMs = Double.NaN;
    private static long smoothedAtNs;
    // AudioTrackPositionTracker.maybeSampleSyncParams: the offset between the content position and
    // the system clock times the speed (position - clock * speed) is averaged over the last 10
    // samples, at any speed, and the average is reset when the speed changes.  The IJK clock is
    // anchored by audio callbacks and jitters by about +-15 ms (std 14 ms, measured 2026-11-05),
    // more than the 20 ms the frame-rate smoothing (adjustReleaseTime) tolerates.  For 2 s after a
    // speed request (the audio clock ramps to the new speed) the position is only slewed to the
    // samples within +-10 % of the elapsed time instead.
    // Project-tuned, not upstream: Media3's AudioTrackPositionTracker averages 10 samples taken 30 ms apart
    // and prefers AudioTrack.getTimestamp().  IJK's position wanders with a correlation time near a second,
    // so 60 samples (about 5 s) are needed to keep the 1x release time inside adjustReleaseTime's 20 ms
    // tolerance (measured 2026-11-05: >25 ms intervals per 30 s 4.8 with 10 samples, 0.17 with 60).
    private static final PositionOffsetRing RING = new PositionOffsetRing();
    private static long experimentCheckedNs;
    private static int experiment;
    private static final boolean AVERAGE_OFFSET_OFF_1X = true;
    private static final long STEADY_AFTER_NS = 2_000_000_000L;
    private static long rateChangedNs;

    private static void onAudioClockSample(long nowNs, long positionMs) {
        AudioTrackProbe.notePosition(nowNs, positionMs);
        final double speed;
        final double smoothed;
        synchronized (CLOCK_LOCK) {
            speed = clockSpeedLocked(nowNs, positionMs);
            smoothed = smoothPositionLocked(nowNs, positionMs, speed);
        }
        if (DebugLog.readInt("debug.biliextended.decide", 0) == 1) {
            android.util.Log.i("BilX", "SAMPLE t=" + nowNs / 1_000_000L + "ms raw=" + positionMs
                    + " smooth=" + String.format(java.util.Locale.US, "%.2f", smoothed) + " n=" + RING.count());
        }
        NativeRefreshCap.noteAudioClock(Math.round(smoothed * 1_000.0), nowNs, speed);
        if (Math.abs(speed - 1.0) >= 0.01) {
            // Off 1x the scheduler also divides the PTS step by the speed (frame duration, cadence).
            NativeRefreshCap.setPlaybackRateTrim((float) speed);
        }
    }

    private static void refreshExperimentLocked(long nowNs) {
        if (nowNs - experimentCheckedNs < 2_000_000_000L && experimentCheckedNs != 0L) {
            return;
        }
        experimentCheckedNs = nowNs;
        final int exp = DebugLog.readInt("debug.biliextended.exp", 0);
        experiment = exp;
        RING.setWindow(ExperimentPolicy.windowFor(exp));   // exp 1 = the old 10, 2 = 30, else 60; exp 4 changes smoothPositionLocked only
    }

    private static double smoothPositionLocked(long nowNs, long positionMs, double speed) {
        refreshExperimentLocked(nowNs);
        final boolean unity = Math.abs(requestedRate - 1.0f) < 0.01f;
        if (unity || (AVERAGE_OFFSET_OFF_1X && nowNs - rateChangedNs > STEADY_AFTER_NS)) {
            smoothedPositionMs = RING.smooth(nowNs, positionMs, speed);
            smoothedAtNs = nowNs;
            return smoothedPositionMs;
        }
        RING.reset();
        if (Double.isNaN(smoothedPositionMs)) {
            smoothedPositionMs = positionMs;
            smoothedAtNs = nowNs;
            return smoothedPositionMs;
        }
        final double elapsedMs = (nowNs - smoothedAtNs) / 1e6;
        final double predicted = smoothedPositionMs + elapsedMs * speed;
        final double delta = positionMs - predicted;
        if (Math.abs(delta) > POSITION_JUMP_MS || elapsedMs < 0.0 || elapsedMs > 1000.0) {
            smoothedPositionMs = positionMs;
        } else {
            final double maxStep = elapsedMs * speed * POSITION_SMOOTHING_PERCENT / 100.0;
            smoothedPositionMs = predicted + Math.max(-maxStep, Math.min(maxStep, delta));
        }
        smoothedAtNs = nowNs;
        return smoothedPositionMs;
    }


    // Until the slope is stable the requested rate would over-extrapolate the position (Bilibili
    // asks for 3.0 on a long press while the clock runs at ~2.0; 40-90 ms of sample age at the
    // wrong speed is more than the drop threshold).  2.0 is what was measured on NX809J with
    // Bilibili 9.13.0, not an upstream constant.
    private static final float MEASURED_SPEED_CAP = 2.0f;

    // Upper bound of a clock slope that is believed: the speed menu goes up to 10x (SpeedSettings.MAX_RATE).  It was 4.0, which
    // rejected the slope of a 5x long press and fell back to 2.0, so the native side scheduled at 2x (one frame per VSync).
    private static final double MAX_MEASURED_SPEED = 10.5;
    private static final double SPEED_SNAP_FRACTION = 0.10;
    private static float lastAppliedLogged;

    private static double fallbackSpeed() {
        return requestedRate > 1.0f ? Math.min(requestedRate, MEASURED_SPEED_CAP) : requestedRate;
    }

    /**
     * The speed to use while there are too few position samples for a slope (the first ~0.3-0.5 s after a start, a seek or a rate change).  The rate that really runs is
     * known without measuring (see clockSpeedLocked: min(request, IJK max)), so it is used from the first sample on.  The old fallback capped it at 2.0: a clip started or
     * sought at 4x was scheduled as 2x for that time (measured 2026-11-11, 4K60 AV1 at 4x, phone: frame duration 8.34 ms instead of 4.17, one frame per VSync and no
     * same-VSync skip, so the decoder was paced at 120 frames/s for 0.5 s, the video fell 130 ms behind the sound, and with 250 frames/s decoded against 240 needed it took
     * 4 s of irregular steps to recover; 3 of 8 rounds).  debug.biliextended.startrate=0 restores the old fallback.
     */
    private static double speedBeforeSlope() {
        final float appliedMax = NativeRefreshCap.observedMaxRate();
        if (requestedRate > 1.0f && appliedMax > 1.0f && DebugLog.readInt("debug.biliextended.appliedrate", 1) == 1
                && DebugLog.readInt("debug.biliextended.startrate", 1) == 1) {
            return Math.min(requestedRate, appliedMax);
        }
        return fallbackSpeed();
    }

    /** Speed of the audio clock: 1.0 at 1x, otherwise the slope of the recent samples. */
    private static double clockSpeedLocked(long nowNs, long positionMs) {
        if (Math.abs(requestedRate - 1.0f) < 0.01f) {
            speedCount = 0;
            return 1.0;
        }
        if (speedCount > 0) {
            final double expected = speedPosMs[speedCount - 1]
                    + (nowNs - speedNs[speedCount - 1]) / 1e6 * requestedRate;
            // A seek or a stall breaks the line.
            if (Math.abs(positionMs - expected) > SPEED_JUMP_MS * Math.max(1.0, requestedRate)) {
                speedCount = 0;
            }
        }
        if (speedCount == SPEED_RING) {
            System.arraycopy(speedNs, 1, speedNs, 0, SPEED_RING - 1);
            System.arraycopy(speedPosMs, 1, speedPosMs, 0, SPEED_RING - 1);
            speedCount = SPEED_RING - 1;
        }
        speedNs[speedCount] = nowNs;
        speedPosMs[speedCount] = positionMs;
        speedCount++;
        int first = 0;
        while (first < speedCount - 1 && nowNs - speedNs[first] > SPEED_WINDOW_NS) {
            first++;
        }
        final int n = speedCount - first;
        if (n < 4 || speedNs[speedCount - 1] - speedNs[first] < SPEED_MIN_SPAN_NS) {
            return speedBeforeSlope();
        }
        final double baseNs = speedNs[first];
        final double basePos = speedPosMs[first];
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        for (int i = first; i < speedCount; i++) {
            final double x = (speedNs[i] - baseNs) / 1e6;
            final double y = speedPosMs[i] - basePos;
            sx += x;
            sy += y;
            sxx += x * x;
            sxy += x * y;
        }
        final double denominator = n * sxx - sx * sx;
        if (denominator <= 0.0) {
            return speedBeforeSlope();
        }
        final double slope = (n * sxy - sx * sy) / denominator;
        // Bilibili's tempo is applied in software at exactly the requested rate; the slope of the
        // position samples only scatters around it (1/3x: 0.32-0.34, 0.25x: 0.237-0.242, measured
        // 2026-11-07), and at 1/3x each 1 % of scatter is ~0.25 ms of frame duration per frame,
        // enough to walk adjustReleaseTime past its 20 ms limit.  Within 10 % the requested rate is
        // used; a real mismatch (long press asks 3.0, clock runs ~2.0) still takes the slope.
        // The rate that really runs is known without measuring it: ffp_set_playback_rate stores min(request, GetMaxPlayRate) (disassembled,
        // libijkplayer 9.13.0 / 9.17.0).  The only time the clock ran slower than the request was that cap (long press 3.0 -> 2.0), and the
        // cap lift raises it to 10, so with the lift installed the applied rate is the request, or the cap if the request is above it.
        // debug.biliextended.appliedrate=0 goes back to the slope below.
        final float appliedMax = NativeRefreshCap.observedMaxRate();
        if (appliedMax > 1.0f && DebugLog.readInt("debug.biliextended.appliedrate", 1) == 1) {
            final float applied = Math.min(requestedRate, appliedMax);
            if (applied != lastAppliedLogged && DebugLog.enabled()) {
                lastAppliedLogged = applied;
                Xp.log(TAG + ": applied playback rate " + applied + " (request " + requestedRate + ", IJK max " + appliedMax + ")");
            }
            return applied;
        }
        // Slow speeds (< 1x) are always exactly the requested rate: the clock samples scatter by ~30 ms of content time, which is +-100 ms of real time at 0.25x, so the 0.8 s slope
        // left the +-10 % band about every fifth sample and the upward outliers (e.g. 0.285 for 0.25) were taken as the speed: the frame chain then advanced 29 ms instead of 33.3 per
        // frame, slid against the VSync grid and flipped single frames one slot early (measured 2026-11-09).  debug.biliextended.slowexact=0 restores the old behaviour.
        if (requestedRate < 0.99f && DebugLog.readInt("debug.biliextended.slowexact", 1) == 1) {
            return requestedRate;
        }
        if (slope > 0.0 && Math.abs(slope / requestedRate - 1.0) < SPEED_SNAP_FRACTION) {
            return requestedRate;
        }
        return slope > 0.25 && slope < MAX_MEASURED_SPEED ? slope : fallbackSpeed();
    }

    private static void onPlaybackRate(final float rate, String source) {
        if (!(rate > 0f) || rate == latestPlaybackRate) {
            return;
        }
        latestPlaybackRate = rate;
        synchronized (CLOCK_LOCK) {
            requestedRate = rate;
            speedCount = 0;
            rateChangedNs = System.nanoTime();
        }
        NativeRefreshCap.setPlaybackRate(rate);
        RateStateFile.onRateChanged(rate, rate, source);
        Xp.log(TAG + ": playback rate " + rate + " via " + source
                + (Math.abs(rate - 1.0f) < 0.01f ? " (VSync scheduling on)"
                        : " (video follows the audio clock)"));
    }

    private static void hookPlayerLifecycle(Class<?> playerClass, final String methodName,
                                            Class<?>[] parameterTypes) {
        try {
            hookPlayerMethod(playerClass, methodName, parameterTypes, new Xp.Hook() {
                @Override
                protected void before(Xp.Param param) {
                    latestPlayerLifecycle = methodName;
                }
            });
        } catch (Throwable throwable) {
            logOnce("player-lifecycle-hook:" + methodName,
                    "IJK lifecycle telemetry unavailable for " + methodName + ": "
                            + throwable.getClass().getSimpleName());
        }
    }

    private static void hookPlayerMethod(Class<?> playerClass, String methodName,
                                         Class<?>[] parameterTypes, Xp.Hook hook) {
        Object[] hookArgs = new Object[parameterTypes.length + 1];
        System.arraycopy(parameterTypes, 0, hookArgs, 0, parameterTypes.length);
        hookArgs[parameterTypes.length] = hook;
        Xp.findAndHookMethod(playerClass, methodName, hookArgs);
    }

    private static void recordCodecLifecycle(MediaCodec codec, String lifecycle, Object surface) {
        if (!telemetryEnabled()) {
            return;
        }
        String detail = surface == null ? "surface=null" : "surface=" + surface.getClass().getName();
    }

    private static void recordRenderListenerLifecycle(
            MediaCodec codec,
            String lifecycle,
            MediaCodec.OnFrameRenderedListener applicationListener,
            Handler handler,
            long listenerGeneration) {
        if (!telemetryEnabled()) {
            return;
        }
        String detail = "listenerPresent=" + (applicationListener != null)
                + ";handler=" + (handler == null ? "null" : handler.getClass().getName())
                + ";listenerGeneration=" + listenerGeneration;
    }

    private static void recordReleaseRequest(MediaCodec codec, int index, boolean render,
                                             String action, String detail) {
        if (!telemetryEnabled()) {
            return;
        }
        CodecState state = CODECS.get(codec);
        FrameTiming timing = state == null ? null : peekTiming(state, index);
        if (!render && state != null) {
            synchronized (state) {
                state.removeTiming(index);
            }
        }
        long nowNs = System.nanoTime();
        recordFrameEvent(codec, index, timing, nowNs, currentPlaybackPositionUs(nowNs),
                playbackPositionAgeMs(nowNs), state == null ? TIME_UNSET
                        : state.estimatedPositionUs(nowNs), TIME_UNSET, TIME_UNSET, action, render,
                "releaseOutputBuffer(bool)", detail);
    }

    private static void recordDirectTimedRelease(MediaCodec codec, int index, long releaseTimeNs) {
        if (!telemetryEnabled()) {
            return;
        }
        CodecState state = CODECS.get(codec);
        FrameTiming timing = state == null ? null : peekTiming(state, index);
        long nowNs = System.nanoTime();
        recordFrameEvent(codec, index, timing, nowNs, currentPlaybackPositionUs(nowNs),
                playbackPositionAgeMs(nowNs), state == null ? TIME_UNSET
                        : state.estimatedPositionUs(nowNs), TIME_UNSET, releaseTimeNs,
                "DIRECT_TIMED_RELEASE", true, "releaseOutputBuffer(time)", "");
    }

    private static FrameTiming peekTiming(CodecState state, int index) {
        synchronized (state) {
            FrameTiming timing = state.presentationTimes.get(index);
            return timing == null ? state.frameTimingsByBufferIndex.get(index) : timing;
        }
    }

    private static final ThreadLocal<Boolean> RELEASE_MARK_EMITTED = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return Boolean.FALSE;
        }
    };

    /**
     * bsbR (and bsbK for a deliberate drop) BEFORE releaseOutputBuffer (2026-11-12).  Written after the call it was up to ~9 ms late when the thread was
     * descheduled on the way back, i.e. after SurfaceFlinger had already queued the buffer; at 120 frames/s (8.3 ms apart) such a mark sits where the next
     * frame's mark belongs and no pairing rule can tell them apart (ft2 bronya 4x: 2.3 % of the presents unpaired).  Before the call it can never be later
     * than the queue.  Measurement only (DebugLog.monitor()); recordFrameEvent then skips its own R / K instant for this release.
     */
    private static void emitReleaseMarkBeforeCall(long ptsUs, boolean skip) {
        if (!DebugLog.monitor()) {
            return;
        }
        try {
            Trace.beginSection(ptsUs == TIME_UNSET ? "bsbR" : "bsbR:" + ptsUs);
            Trace.endSection();
            if (skip && ptsUs != TIME_UNSET) {
                Trace.beginSection("bsbK:" + ptsUs);
                Trace.endSection();
            }
            RELEASE_MARK_EMITTED.set(Boolean.TRUE);
        } catch (Throwable ignored) {
            // Measurement only.
        }
    }

    private static void recordFrameEvent(MediaCodec codec, int index, FrameTiming timing,
                                         long eventNs, long playbackPositionUs,
                                         long positionAgeMs, long estimatedPositionUs,
                                         long unadjustedReleaseNs, long releaseTimeNs,
                                         String action, boolean render, String api,
                                         String detail) {
        // perfetto trace: one instant per frame event, in the SAME trace as SurfaceFlinger's Queue/Present, so the module's decisions and
        // the presentation timeline share one clock.  This replaces the CSV telemetry (which needed an offset, lagged by up to 20 s after a
        // rate change, broke when truncated, and was 20-30 MB per capture).  Kept outside telemetryEnabled() so the CSV switch cannot
        // disable the trace.  Test-only since 2026-11-11 (DebugLog.monitor()): the section names are built by string concatenation for every frame event.
        if (DebugLog.monitor()) {
            long ptsTrace = timing == null ? TIME_UNSET : timing.presentationTimeUs;
            char kind = "TIMED_RELEASE_FIRED".equals(action) ? 'R'
                    : "FRAME_RENDERED".equals(action) ? 'F'
                    : "DEQUEUED".equals(action) ? 'D' : 0;
            if (kind == 'R' && RELEASE_MARK_EMITTED.get()) {
                RELEASE_MARK_EMITTED.set(Boolean.FALSE);       // bsbR (and bsbK) were written right before releaseOutputBuffer
                kind = 0;
            }
            if (kind != 0) {
                Trace.beginSection(ptsTrace == TIME_UNSET ? "bsb" + kind : "bsb" + kind + ":" + ptsTrace);
                Trace.endSection();
                // bsbK:<pts> = this frame was dropped on purpose by the native scheduler (releaseTs == -2: release(false), same-VSync skip,
                // late-frame skip or a cadence-lock decimation).  It follows the frame's bsbR, so a frame with bsbK never reached the screen.
                // The CSV used to carry this as releaseTs=-2; the trace had no way to tell "handed off" from "dropped".
                if (kind == 'R' && ptsTrace != TIME_UNSET && detail != null && detail.contains("releaseTs=-2;")) {
                    Trace.beginSection("bsbK:" + ptsTrace);
                    Trace.endSection();
                }
            }
            if (playbackPositionUs != TIME_UNSET) {
                Trace.beginSection("bsbP:" + playbackPositionUs + ":" + positionAgeMs);
                Trace.endSection();
            }
        }
        if (!telemetryEnabled()) {
            return;
        }
        long ptsUs = timing == null ? TIME_UNSET : timing.presentationTimeUs;
        long frameIndex = timing == null ? INDEX_UNSET : timing.frameIndex;
        long dequeuedAtNs = timing == null ? TIME_UNSET : timing.dequeuedAtNs;
        long earlyUs = playbackPositionUs == TIME_UNSET || ptsUs == TIME_UNSET
                ? TIME_UNSET : ptsUs - playbackPositionUs;
        VSyncSampler sampler = vsyncSampler;
        long sampledVsyncId = sampler == null ? TIME_UNSET : sampler.sampledVsyncId;
        long sampledVsyncNs = sampler == null ? TIME_UNSET : sampler.sampledVsyncTimeNs;
        long vsyncDurationNs = sampler == null ? TIME_UNSET : sampler.vsyncDurationNs;
        long expectedPresentationNs = sampler == null
                ? TIME_UNSET : sampler.expectedPresentationTimeNs;
        long frameDeadlineNs = sampler == null ? TIME_UNSET : sampler.deadlineNs;
        CodecState state = CODECS.get(codec);
        long frameDurationNs = TIME_UNSET;
        boolean fixedFrameRateSynced = false;
        if (state != null) {
            synchronized (state) {
                frameDurationNs = state.frameDurationNs;
                fixedFrameRateSynced = state.frameRateEstimator.isSynced();
            }
        }
    }

    static void recordWindowFrameMetrics(long metricsAvailableNs, long intendedVsyncNs,
                                         long vsyncTimestampNs, long deadlineNs,
                                         long totalDurationNs, long swapBuffersDurationNs,
                                         int dropCountSinceLastInvocation) {
        if (!telemetryEnabled()) {
            return;
        }
        VSyncSampler sampler = vsyncSampler;
        long sampledVsyncId = sampler == null ? TIME_UNSET : sampler.sampledVsyncId;
        long expectedPresentationNs = sampler == null
                ? TIME_UNSET : sampler.expectedPresentationTimeNs;
        String detail = "metricsAvailableNs=" + metricsAvailableNs
                + ";dropCountSinceLastInvocation=" + dropCountSinceLastInvocation
                + ";sampledVsyncId=" + sampledVsyncId
                + ";intendedVsyncNs=" + intendedVsyncNs
                + ";vsyncTimestampNs=" + vsyncTimestampNs
                + ";deadlineNs=" + deadlineNs
                + ";expectedPresentationNs=" + expectedPresentationNs
                + ";metricsAvailableMinusVsyncNs="
                + differenceOrUnset(metricsAvailableNs, vsyncTimestampNs)
                + ";expectedPresentationMinusVsyncNs="
                + differenceOrUnset(expectedPresentationNs, vsyncTimestampNs)
                + ";totalDurationNs=" + totalDurationNs
                + ";swapBuffersDurationNs=" + swapBuffersDurationNs;
    }

    static void recordWindowFrameMetricsListenerState(String state) {
        if (!telemetryEnabled()) {
            return;
        }
    }

    private static long differenceOrUnset(long left, long right) {
        return left == TIME_UNSET || right == TIME_UNSET ? TIME_UNSET : left - right;
    }

    private static long sampledVsyncAgeNs(VSyncSampler sampler, long nowNs) {
        if (sampler == null || sampler.sampledVsyncTimeNs == TIME_UNSET
                || sampler.sampledVsyncTimeNs > nowNs) {
            return TIME_UNSET;
        }
        return nowNs - sampler.sampledVsyncTimeNs;
    }


    private static void appendCsv(StringBuilder builder, String value) {
        if (value == null) {
            value = "";
        }
        builder.append('"').append(value.replace("\"", "\"\""))
                .append('"').append(',');
    }

    private static String telemetrySessionId() {
        return TELEMETRY_SESSION_ID;
    }

    private static long currentPlaybackPositionUs(long nowNs) {
        return latestPlaybackPositionUs;
    }

    private static IjkEarlyUsTiming readIjkEarlyUsTiming(long presentationTimeUs,
                                                          long nowNs) {
        // Bilibili 9.13.0 has no earlyUs probe (the 9.5.0 struct offsets were never ported), so
        // the anchor is permanently off; the Java fallback path uses the VSync sampler alone.
        if (!IJK_EARLY_US_VSYNC_ANCHOR || presentationTimeUs == TIME_UNSET) {
            return null;
        }
        long[] sample = NativeRefreshCap.getIjkEarlyUsForPts(presentationTimeUs);
        if (sample == null || sample.length < IJK_EARLY_US_SAMPLE_FIELD_COUNT) {
            ijkEarlyUsMisses++;
            return null;
        }
        long sampleTimestampNs = sample[0];
        long earlyUsNanos = sample[2];
        if (sampleTimestampNs <= 0L || sampleTimestampNs > nowNs) {
            ijkEarlyUsStaleSamples++;
            return null;
        }
        long ageNs = nowNs - sampleTimestampNs;
        if (ageNs > MAX_IJK_EARLY_US_SAMPLE_AGE_NS) {
            ijkEarlyUsStaleSamples++;
            return null;
        }
        if ((earlyUsNanos > 0L && sampleTimestampNs > Long.MAX_VALUE - earlyUsNanos)
                || (earlyUsNanos < 0L && sampleTimestampNs < Long.MIN_VALUE - earlyUsNanos)) {
            ijkEarlyUsStaleSamples++;
            return null;
        }
        return new IjkEarlyUsTiming(sampleTimestampNs, sample[1], earlyUsNanos, sample[3],
                ageNs, sampleTimestampNs + earlyUsNanos);
    }

    private static long playbackPositionAgeMs(long nowNs) {
        if (latestPlaybackPositionSampleNs == TIME_UNSET) {
            return TIME_UNSET;
        }
        return Math.max(0L, (nowNs - latestPlaybackPositionSampleNs) / 1_000_000L);
    }



    private static void maybeReport(long nowNs) {
        if (nowNs - lastReportNs < 1_000_000_000L) {
            return;
        }
        lastReportNs = nowNs;
        final float stateRequested;
        synchronized (CLOCK_LOCK) {
            stateRequested = requestedRate;
        }
        RateStateFile.publish(nowNs, stateRequested, latestPlaybackRate);   // the rate file a tool can read (see RateStateFile), written even with the debug log off
        // The first report always goes out so the log shows the scheduling is active; after that
        // the per-second statistics are diagnostics behind the hidden switch.
        if (reportedOnce && !DebugLog.enabled() && !DebugLog.statEnabled()) {
            return;
        }
        reportedOnce = true;
        VSyncSampler sampler = vsyncSampler;
        double vsyncMs = sampler == null || sampler.vsyncDurationNs == TIME_UNSET
                ? 0.0 : sampler.vsyncDurationNs / 1_000_000.0;
        if (nativeSchedulingActive) {
            Xp.log(TAG + ": native release scheduling "
                    + NativeRefreshCap.releaseSchedulingStatus());
        }
        Xp.log(TAG + ": Media3 sync timed=" + timedReleases
        + " predicted=" + predictedDeadlines
        + " resets=" + deadlineResets
                + " clockReanchors=" + clockReanchors
                + " clockLateFrames=" + clockLateFrames
                + " vsyncSnaps=" + vsyncSnaps
                + " ijkEarlyUsAnchors=" + ijkEarlyUsAnchors
                + " ijkEarlyUsMisses=" + ijkEarlyUsMisses
                + " ijkEarlyUsStale=" + ijkEarlyUsStaleSamples
                + " cadenceAnchors=" + cadenceAnchors
                + " cadenceExtraVsyncs=" + cadenceExtraVsyncs
                + " cadencePhaseCorrections=" + cadencePhaseCorrections
                + " cadenceFallbacks=" + cadenceFallbacks
                + " vsyncMode=" + vsyncSamplerMode
                + " vsyncMs=" + String.format(java.util.Locale.US, "%.3f", vsyncMs)
                + " qtiFenceConfigs=" + qtiFenceConfigurations
                + " surfaceRateHints=" + surfaceFrameRateHints);
    }

    private static void logOnce(String key, String message) {
        Xp.log(TAG + ": " + message);
    }


    private static final class PendingRenderListener {
        final MediaCodec.OnFrameRenderedListener listener;
        final Handler handler;

        PendingRenderListener(MediaCodec.OnFrameRenderedListener listener, Handler handler) {
            this.listener = listener;
            this.handler = handler;
        }
    }

    private static final class FrameTiming {
        final int bufferIndex;
        final long presentationTimeUs;
        final long frameIndex;
        final long dequeuedAtNs;
        final long renderGeneration;
        long releaseCallNs = TIME_UNSET;
        long unadjustedReleaseNs = TIME_UNSET;
        long scheduledReleaseNs = TIME_UNSET;
        volatile long scheduledCallNs = TIME_UNSET;
        long renderedNanoTimeNs = TIME_UNSET;
        long renderCallbackObservedNs = TIME_UNSET;
        long renderListenerGeneration = TIME_UNSET;

        FrameTiming(int bufferIndex, long presentationTimeUs, long frameIndex,
                    long dequeuedAtNs, long renderGeneration) {
            this.bufferIndex = bufferIndex;
            this.presentationTimeUs = presentationTimeUs;
            this.frameIndex = frameIndex;
            this.dequeuedAtNs = dequeuedAtNs;
            this.renderGeneration = renderGeneration;
        }
    }

    private static final class IjkEarlyUsTiming {
        final long sampleTimestampNs;
        final long matchedPtsUs;
        final long earlyUsNanos;
        final long filterDelayNanos;
        final long ageNs;
        final long releaseTimeNs;

        IjkEarlyUsTiming(long sampleTimestampNs, long matchedPtsUs, long earlyUsNanos,
                         long filterDelayNanos, long ageNs, long releaseTimeNs) {
            this.sampleTimestampNs = sampleTimestampNs;
            this.matchedPtsUs = matchedPtsUs;
            this.earlyUsNanos = earlyUsNanos;
            this.filterDelayNanos = filterDelayNanos;
            this.ageNs = ageNs;
            this.releaseTimeNs = releaseTimeNs;
        }
    }

    private static final class CadenceTiming {
        final long releaseTimeNs;
        final long targetVsyncTimeNs;
        final long extraVsyncs;
        final long correctionIntervalFrames;
        final long frameDurationNs;
        final long vsyncDurationNs;
        final long anchorFrameIndex;
        final long anchorPresentationTimeUs;
        final long anchorVsyncTimeNs;
        final long frameDelta;
        final long frameSlot;
        final long driftNs;
        final long phaseErrorNs;
        final boolean reanchored;
        final String reanchorReason;

        CadenceTiming(long releaseTimeNs, long targetVsyncTimeNs, long extraVsyncs,
                      long correctionIntervalFrames, long frameDurationNs, long vsyncDurationNs,
                      long anchorFrameIndex, long anchorPresentationTimeUs,
                      long anchorVsyncTimeNs, long frameDelta, long frameSlot, long driftNs,
                      long phaseErrorNs, boolean reanchored, String reanchorReason) {
            this.releaseTimeNs = releaseTimeNs;
            this.targetVsyncTimeNs = targetVsyncTimeNs;
            this.extraVsyncs = extraVsyncs;
            this.correctionIntervalFrames = correctionIntervalFrames;
            this.frameDurationNs = frameDurationNs;
            this.vsyncDurationNs = vsyncDurationNs;
            this.anchorFrameIndex = anchorFrameIndex;
            this.anchorPresentationTimeUs = anchorPresentationTimeUs;
            this.anchorVsyncTimeNs = anchorVsyncTimeNs;
            this.frameDelta = frameDelta;
            this.frameSlot = frameSlot;
            this.driftNs = driftNs;
            this.phaseErrorNs = phaseErrorNs;
            this.reanchored = reanchored;
            this.reanchorReason = reanchorReason;
        }
    }

    private static final class CodecState {
        final Map<Integer, FrameTiming> presentationTimes = new HashMap<Integer, FrameTiming>();
        final Map<Integer, FrameTiming> frameTimingsByBufferIndex =
                new HashMap<Integer, FrameTiming>();
        final Map<Long, ArrayDeque<FrameTiming>> pendingRenderTimings =
                new HashMap<Long, ArrayDeque<FrameTiming>>();
        final ArrayDeque<FrameTiming> pendingRenderOrder = new ArrayDeque<FrameTiming>();
        final Surface surface;
        final Object inputRing = new Object();
        final long[] inputRingPts = new long[INPUT_RING];
        final long[] inputRingAtNs = new long[INPUT_RING];
        int inputRingPos;
        final FixedFrameRateEstimator frameRateEstimator = new FixedFrameRateEstimator();
        long nextFrameIndex;
        long renderGeneration;
        long renderListenerGeneration;
        long renderCallbackCount;
        long renderCallbackExactMatchCount;
        long renderCallbackUnmatchedCount;
        long renderCallbackBurstCount;
        long lastRenderCallbackObservedNs = TIME_UNSET;
        long lastRenderCallbackNanoTimeNs = TIME_UNSET;
        MediaCodec.OnFrameRenderedListener applicationRenderListener;
        /** The handler Bilibili gave with its listener (null: the module's own); handed over in a hot reload. */
        Handler applicationRenderHandler;
        MediaCodec.OnFrameRenderedListener renderListenerWrapper;
        long frameDurationNs = TIME_UNSET;
        long pendingLastPresentationTimeUs = TIME_UNSET;
        long lastAdjustedPresentationTimeUs = TIME_UNSET;
        long pendingLastAdjustedReleaseTimeNs = TIME_UNSET;
        long lastAdjustedReleaseTimeNs = TIME_UNSET;
        long pendingLastAdjustedFrameIndex = INDEX_UNSET;
        long lastAdjustedFrameIndex = INDEX_UNSET;
        long pendingVsyncHysteresisOffsetNs;
        long lastVsyncHysteresisOffsetNs;
        long clockAnchorPresentationTimeUs = TIME_UNSET;
        long clockAnchorTimeNs = TIME_UNSET;
        long lastClockPresentationTimeUs = TIME_UNSET;
        float lastSurfaceFrameRate;
        long cadenceAnchorFrameIndex = INDEX_UNSET;
        long cadenceAnchorPresentationTimeUs = TIME_UNSET;
        long cadenceAnchorVsyncTimeNs = TIME_UNSET;
        long cadenceFrameDurationNs = TIME_UNSET;
        long cadenceVsyncDurationNs = TIME_UNSET;
        long cadenceLastFrameSlot = -1L;
        long cadenceLastExtraVsyncs;
        long gridAnchorNs = TIME_UNSET;
        long lastGridResidualNs = TIME_UNSET;
        long lastHoldBumpPeriods;
        long lastTargetTickNs = TIME_UNSET;
        long lastSlotCollisions;

        CodecState(Surface surface) {
            this.surface = surface;
        }

        /**
         * Keeps one expected-presentation grid.  SurfaceFlinger alternates its early/late
         * VSync configuration, so consecutive FrameTimeline samples can sit on grids about
         * half a period apart; snapping to whichever grid was sampled last makes targets
         * flip between slots.  Samples on the current grid refresh the anchor, others are
         * ignored.
         */
        long updateGridAnchor(long sampleNs, long periodNs) {
            if (periodNs < MIN_VSYNC_DURATION_NS || periodNs > MAX_VSYNC_DURATION_NS) {
                return TIME_UNSET;
            }
            if (sampleNs == TIME_UNSET) {
                return gridAnchorNs;
            }
            if (gridAnchorNs == TIME_UNSET) {
                gridAnchorNs = sampleNs;
                lastGridResidualNs = 0L;
                return gridAnchorNs;
            }
            long residualNs = Math.floorMod(sampleNs - gridAnchorNs, periodNs);
            if (residualNs > periodNs / 2L) {
                residualNs -= periodNs;
            }
            lastGridResidualNs = residualNs;
            if (Math.abs(residualNs) <= GRID_ANCHOR_TOLERANCE_NS) {
                gridAnchorNs = sampleNs;
            } else {
                gridAnchorRejects++;
            }
            return gridAnchorNs;
        }

        void addRenderCandidate(FrameTiming timing) {
            frameTimingsByBufferIndex.put(timing.bufferIndex, timing);
            ArrayDeque<FrameTiming> candidates = pendingRenderTimings.get(
                    timing.presentationTimeUs);
            if (candidates == null) {
                candidates = new ArrayDeque<FrameTiming>();
                pendingRenderTimings.put(timing.presentationTimeUs, candidates);
            }
            candidates.addLast(timing);
            pendingRenderOrder.addLast(timing);
            while (pendingRenderOrder.size() > MAX_PENDING_RENDER_TIMINGS) {
                FrameTiming evicted = pendingRenderOrder.removeFirst();
                removePendingRenderTiming(evicted);
            }
        }

        int pendingRenderTimingCount() {
            return pendingRenderOrder.size();
        }

        FrameTiming takeRenderCandidate(long presentationTimeUs, long renderedNanoTimeNs) {
            ArrayDeque<FrameTiming> candidates = pendingRenderTimings.get(presentationTimeUs);
            if (candidates == null || candidates.isEmpty()) {
                return null;
            }
            FrameTiming best = null;
            long bestDelta = Long.MAX_VALUE;
            for (FrameTiming candidate : candidates) {
                long anchorNs = candidate.scheduledReleaseNs != TIME_UNSET
                        ? candidate.scheduledReleaseNs : candidate.dequeuedAtNs;
                long delta = renderedNanoTimeNs <= 0L || anchorNs == TIME_UNSET
                        ? Long.MAX_VALUE : Math.abs(renderedNanoTimeNs - anchorNs);
                if (best == null || delta < bestDelta) {
                    best = candidate;
                    bestDelta = delta;
                }
            }
            if (best != null) {
                removePendingRenderTiming(best);
            }
            return best;
        }

        void removeTiming(int bufferIndex) {
            FrameTiming timing = frameTimingsByBufferIndex.remove(bufferIndex);
            presentationTimes.remove(bufferIndex);
            if (timing != null) {
                removePendingRenderTiming(timing);
            }
        }

        private void removePendingRenderTiming(FrameTiming timing) {
            ArrayDeque<FrameTiming> candidates = pendingRenderTimings.get(
                    timing.presentationTimeUs);
            if (candidates != null) {
                candidates.remove(timing);
                if (candidates.isEmpty()) {
                    pendingRenderTimings.remove(timing.presentationTimeUs);
                }
            }
            pendingRenderOrder.remove(timing);
            FrameTiming indexed = frameTimingsByBufferIndex.get(timing.bufferIndex);
            if (indexed == timing) {
                frameTimingsByBufferIndex.remove(timing.bufferIndex);
            }
        }

        void resetAdjustment() {
            pendingLastPresentationTimeUs = TIME_UNSET;
            lastAdjustedPresentationTimeUs = TIME_UNSET;
            pendingLastAdjustedReleaseTimeNs = TIME_UNSET;
            lastAdjustedReleaseTimeNs = TIME_UNSET;
            pendingLastAdjustedFrameIndex = INDEX_UNSET;
            lastAdjustedFrameIndex = INDEX_UNSET;
            pendingVsyncHysteresisOffsetNs = 0;
            lastVsyncHysteresisOffsetNs = 0;
            resetCadence();
        }

        private void resetCadence() {
            cadenceAnchorFrameIndex = INDEX_UNSET;
            cadenceAnchorPresentationTimeUs = TIME_UNSET;
            cadenceAnchorVsyncTimeNs = TIME_UNSET;
            cadenceFrameDurationNs = TIME_UNSET;
            cadenceVsyncDurationNs = TIME_UNSET;
            cadenceLastFrameSlot = -1L;
            cadenceLastExtraVsyncs = 0L;
        }

        void resetPosition() {
            lastTargetTickNs = TIME_UNSET;
            presentationTimes.clear();
            frameTimingsByBufferIndex.clear();
            pendingRenderTimings.clear();
            pendingRenderOrder.clear();
            frameRateEstimator.reset();
            frameDurationNs = TIME_UNSET;
            nextFrameIndex = 0;
            renderGeneration++;
            renderCallbackCount = 0L;
            renderCallbackExactMatchCount = 0L;
            renderCallbackUnmatchedCount = 0L;
            renderCallbackBurstCount = 0L;
            lastRenderCallbackObservedNs = TIME_UNSET;
            lastRenderCallbackNanoTimeNs = TIME_UNSET;
            clockAnchorPresentationTimeUs = TIME_UNSET;
            clockAnchorTimeNs = TIME_UNSET;
            lastClockPresentationTimeUs = TIME_UNSET;
            resetAdjustment();
        }

        long videoClockReleaseTimeNs(FrameTiming timing, long nowNs) {
            long presentationTimeUs = timing.presentationTimeUs;
            boolean noAnchor = clockAnchorPresentationTimeUs == TIME_UNSET
                    || clockAnchorTimeNs == TIME_UNSET;
            boolean backwards = lastClockPresentationTimeUs != TIME_UNSET
                    && presentationTimeUs < lastClockPresentationTimeUs;
            boolean discontinuity = lastClockPresentationTimeUs != TIME_UNSET
                    && presentationTimeUs - lastClockPresentationTimeUs
                    > MAX_CLOCK_DISCONTINUITY_US;
            if (noAnchor || backwards || discontinuity) {
                clockAnchorPresentationTimeUs = presentationTimeUs;
                clockAnchorTimeNs = nowNs;
                clockReanchors++;
            }
            lastClockPresentationTimeUs = presentationTimeUs;
            return clockAnchorTimeNs
                    + (presentationTimeUs - clockAnchorPresentationTimeUs) * 1_000L;
        }

        long estimatedPositionUs(long nowNs) {
            if (clockAnchorPresentationTimeUs == TIME_UNSET || clockAnchorTimeNs == TIME_UNSET) {
                return TIME_UNSET;
            }
            return clockAnchorPresentationTimeUs + (nowNs - clockAnchorTimeNs) / 1_000L;
        }

        CadenceTiming fixedCadenceReleaseTime(FrameTiming timing, long baseReleaseTimeNs,
                                               long nowNs, long sampledVsyncTimeNs,
                                               long vsyncDurationNs) {
            if (frameDurationNs == TIME_UNSET || frameDurationNs < MIN_FRAME_DURATION_NS
                    || frameDurationNs > MAX_FRAME_DURATION_NS
                    || sampledVsyncTimeNs == TIME_UNSET
                    || vsyncDurationNs < MIN_VSYNC_DURATION_NS
                    || vsyncDurationNs > MAX_VSYNC_DURATION_NS) {
                return null;
            }
            String reanchorReason = "none";
            boolean reset = cadenceAnchorFrameIndex == INDEX_UNSET;
            if (reset) {
                reanchorReason = "initial";
            } else if (timing.frameIndex < cadenceAnchorFrameIndex) {
                reset = true;
                reanchorReason = "frame_index_backwards";
            } else if (Math.abs(frameDurationNs - cadenceFrameDurationNs)
                    > CADENCE_DURATION_CHANGE_TOLERANCE_NS) {
                reset = true;
                reanchorReason = "frame_duration_changed";
            } else if (Math.abs(vsyncDurationNs - cadenceVsyncDurationNs)
                    > CADENCE_DURATION_CHANGE_TOLERANCE_NS) {
                reset = true;
                reanchorReason = "vsync_duration_changed";
            }
            if (reset) {
                long releaseOffsetNs = (vsyncDurationNs * VSYNC_OFFSET_PERCENT) / 100L;
                long earliestTargetVsyncNs = Math.max(baseReleaseTimeNs, nowNs)
                        + releaseOffsetNs;
                long firstSlot = VsyncCadenceMath.firstSlotAtOrAfter(sampledVsyncTimeNs,
                        earliestTargetVsyncNs, vsyncDurationNs);
                cadenceAnchorFrameIndex = timing.frameIndex;
                cadenceAnchorPresentationTimeUs = timing.presentationTimeUs;
                cadenceAnchorVsyncTimeNs = sampledVsyncTimeNs
                        + firstSlot * vsyncDurationNs;
                cadenceFrameDurationNs = frameDurationNs;
                cadenceVsyncDurationNs = vsyncDurationNs;
                cadenceLastFrameSlot = -1L;
                cadenceLastExtraVsyncs = 0L;
                cadenceAnchors++;
            }

            long frameDelta = timing.frameIndex - cadenceAnchorFrameIndex;
            if (frameDelta < 0L) {
                return null;
            }
            long driftPerFrameNs = cadenceFrameDurationNs - cadenceVsyncDurationNs;
            long driftNs = frameDelta * driftPerFrameNs;
            long extraVsyncs = driftNs >= 0L
                    ? driftNs / cadenceVsyncDurationNs
                    : -((-driftNs + cadenceVsyncDurationNs - 1L)
                            / cadenceVsyncDurationNs);
            long frameSlot = frameDelta + extraVsyncs;
            if (frameSlot <= cadenceLastFrameSlot) {
                return null;
            }
            if (extraVsyncs > cadenceLastExtraVsyncs) {
                cadenceExtraVsyncs += extraVsyncs - cadenceLastExtraVsyncs;
                cadenceLastExtraVsyncs = extraVsyncs;
            }
            cadenceLastFrameSlot = frameSlot;
            long targetVsyncTimeNs = cadenceAnchorVsyncTimeNs
                    + frameSlot * cadenceVsyncDurationNs;
            long sampledVsyncSlot = Math.round((double) (sampledVsyncTimeNs
                    - cadenceAnchorVsyncTimeNs) / cadenceVsyncDurationNs);
            long predictedSampledVsyncTimeNs = cadenceAnchorVsyncTimeNs
                    + sampledVsyncSlot * cadenceVsyncDurationNs;
            long phaseErrorNs = sampledVsyncTimeNs - predictedSampledVsyncTimeNs;
            long appliedPhaseCorrectionNs = 0L;
            if (Math.abs(phaseErrorNs) >= CADENCE_PHASE_CORRECTION_TOLERANCE_NS
                    && Math.abs(phaseErrorNs) < cadenceVsyncDurationNs / 2L) {
                cadenceAnchorVsyncTimeNs += phaseErrorNs;
                targetVsyncTimeNs += phaseErrorNs;
                appliedPhaseCorrectionNs = phaseErrorNs;
                cadencePhaseCorrections++;
            }
            long correctionIntervalFrames = driftPerFrameNs > 0L
                    ? Math.max(1L, Math.round((double) cadenceVsyncDurationNs
                            / driftPerFrameNs)) : 0L;
            return new CadenceTiming(
                    targetVsyncTimeNs - (cadenceVsyncDurationNs * VSYNC_OFFSET_PERCENT) / 100L,
                    targetVsyncTimeNs, extraVsyncs, correctionIntervalFrames,
                    cadenceFrameDurationNs, cadenceVsyncDurationNs, cadenceAnchorFrameIndex,
                    cadenceAnchorPresentationTimeUs, cadenceAnchorVsyncTimeNs, frameDelta,
                    frameSlot, driftNs, appliedPhaseCorrectionNs, reset, reanchorReason);
        }

        void anchorNativeClock(FrameTiming timing, long releaseTimeNs) {
            clockAnchorPresentationTimeUs = timing.presentationTimeUs;
            clockAnchorTimeNs = releaseTimeNs;
            lastClockPresentationTimeUs = timing.presentationTimeUs;
        }
    }

    /**
     * Media3 FixedFrameRateEstimator adapted from the official matcher algorithm.
     *
     * <p>The previous implementation used a per-frame 3:1 EMA. That can publish a misleading
     * frame rate after a seek, loop boundary, or irregular timestamp. Media3 requires a stable
     * run of matching timestamps before using the estimate for release prediction and Surface
     * frame-rate selection.</p>
     */
    private static final class FixedFrameRateEstimator {
        private Matcher currentMatcher = new Matcher();
        private Matcher candidateMatcher = new Matcher();
        private boolean candidateMatcherActive;
        private long lastFramePresentationTimeNs = TIME_UNSET;

        void reset() {
            currentMatcher.reset();
            candidateMatcher.reset();
            candidateMatcherActive = false;
            lastFramePresentationTimeNs = TIME_UNSET;
        }

        void onNextFrame(long framePresentationTimeNs) {
            if (framePresentationTimeNs == lastFramePresentationTimeNs) {
                return;
            }
            currentMatcher.onNextFrame(framePresentationTimeNs);
            if (currentMatcher.isSynced()) {
                candidateMatcherActive = false;
            } else if (lastFramePresentationTimeNs != TIME_UNSET) {
                if (!candidateMatcherActive || candidateMatcher.isLastFrameOutlier()) {
                    candidateMatcher.reset();
                    candidateMatcher.onNextFrame(lastFramePresentationTimeNs);
                }
                candidateMatcherActive = true;
                candidateMatcher.onNextFrame(framePresentationTimeNs);
            }
            if (candidateMatcherActive && candidateMatcher.isSynced()) {
                Matcher previousMatcher = currentMatcher;
                currentMatcher = candidateMatcher;
                candidateMatcher = previousMatcher;
                candidateMatcherActive = false;
            }
            lastFramePresentationTimeNs = framePresentationTimeNs;
        }

        long getFrameDurationNs() {
            return currentMatcher.isSynced() ? currentMatcher.getFrameDurationNs() : TIME_UNSET;
        }

        boolean isSynced() {
            return currentMatcher.isSynced();
        }

        private static final class Matcher {
            private long firstFramePresentationTimeNs;
            private long firstFrameDurationNs;
            private long lastFramePresentationTimeNs;
            private long frameCount;
            private long matchingFrameCount;
            private long matchingFrameDurationSumNs;
            private final boolean[] recentFrameOutlierFlags =
                    new boolean[CONSECUTIVE_MATCHING_FRAME_DURATIONS_FOR_SYNC];
            private int recentFrameOutlierCount;

            Matcher() {
                reset();
            }

            void reset() {
                firstFramePresentationTimeNs = TIME_UNSET;
                firstFrameDurationNs = TIME_UNSET;
                lastFramePresentationTimeNs = TIME_UNSET;
                frameCount = 0;
                matchingFrameCount = 0;
                matchingFrameDurationSumNs = 0;
                recentFrameOutlierCount = 0;
                Arrays.fill(recentFrameOutlierFlags, false);
            }

            boolean isSynced() {
                return frameCount > CONSECUTIVE_MATCHING_FRAME_DURATIONS_FOR_SYNC
                        && recentFrameOutlierCount == 0;
            }

            boolean isLastFrameOutlier() {
                if (frameCount == 0) {
                    return false;
                }
                return recentFrameOutlierFlags[getRecentFrameOutlierIndex(frameCount - 1)];
            }

            long getFrameDurationNs() {
                return matchingFrameCount == 0
                        ? TIME_UNSET : matchingFrameDurationSumNs / matchingFrameCount;
            }

            void onNextFrame(long framePresentationTimeNs) {
                if (frameCount == 0) {
                    firstFramePresentationTimeNs = framePresentationTimeNs;
                } else if (frameCount == 1) {
                    firstFrameDurationNs = framePresentationTimeNs - firstFramePresentationTimeNs;
                    matchingFrameDurationSumNs = firstFrameDurationNs;
                    matchingFrameCount = 1;
                } else {
                    long lastFrameDurationNs = framePresentationTimeNs - lastFramePresentationTimeNs;
                    int outlierIndex = getRecentFrameOutlierIndex(frameCount);
                    boolean isMatch = Math.abs(lastFrameDurationNs - firstFrameDurationNs)
                            <= MAX_MATCHING_FRAME_DIFFERENCE_NS;
                    if (isMatch) {
                        matchingFrameCount++;
                        matchingFrameDurationSumNs += lastFrameDurationNs;
                        if (recentFrameOutlierFlags[outlierIndex]) {
                            recentFrameOutlierFlags[outlierIndex] = false;
                            recentFrameOutlierCount--;
                        }
                    } else if (!recentFrameOutlierFlags[outlierIndex]) {
                        recentFrameOutlierFlags[outlierIndex] = true;
                        recentFrameOutlierCount++;
                    }
                }
                frameCount++;
                lastFramePresentationTimeNs = framePresentationTimeNs;
            }

            private static int getRecentFrameOutlierIndex(long frameCount) {
                return (int) (frameCount % CONSECUTIVE_MATCHING_FRAME_DURATIONS_FOR_SYNC);
            }
        }
    }

    private abstract static class VSyncSampler implements DisplayManager.DisplayListener {
        final Choreographer choreographer;
        final DisplayManager displayManager;
        final Handler handler;
        volatile long sampledVsyncId = TIME_UNSET;
        volatile long sampledVsyncTimeNs = TIME_UNSET;
        volatile long sampledAtNs = TIME_UNSET;
        volatile long vsyncDurationNs = TIME_UNSET;
        volatile long expectedPresentationTimeNs = TIME_UNSET;
        volatile long deadlineNs = TIME_UNSET;
        long previousExpectedPresentationTimeNs = TIME_UNSET;
        boolean registered;

        VSyncSampler(Choreographer choreographer, DisplayManager displayManager, Handler handler) {
            this.choreographer = choreographer;
            this.displayManager = displayManager;
            this.handler = handler;
        }

        abstract void register();

        abstract void unregister();

        final void publishNativeGrid() {
            publishNativeGridSample(expectedPresentationTimeNs, vsyncDurationNs,
                    sampledVsyncTimeNs, deadlineNs);
        }

        final long effectiveVsyncAnchorTimeNs(long nowNs) {
            return VsyncCadenceMath.projectFreshAnchor(sampledAtNs,
                    expectedPresentationTimeNs, sampledVsyncTimeNs, vsyncDurationNs, nowNs,
                    MAX_VSYNC_SAMPLE_AGE_NS, MIN_VSYNC_DURATION_NS, MAX_VSYNC_DURATION_NS);
        }

        @Override
        public final void onDisplayAdded(int displayId) {
            // No action required.
        }

        @Override
        public final void onDisplayRemoved(int displayId) {
            // No action required.
        }
    }

    private static final class VSyncSamplerBase extends VSyncSampler
            implements Choreographer.FrameCallback {
        VSyncSamplerBase(Choreographer choreographer, DisplayManager displayManager,
                         Handler handler) {
            super(choreographer, displayManager, handler);
        }

        @Override
        void register() {
            if (registered) {
                return;
            }
            registered = true;
            displayManager.registerDisplayListener(this, handler);
            choreographer.removeFrameCallback(this);
            choreographer.postFrameCallback(this);
            vsyncDurationNs = displayRefreshPeriodNs(displayManager);
        }

        @Override
        void unregister() {
            if (!registered) {
                return;
            }
            registered = false;
            displayManager.unregisterDisplayListener(this);
            choreographer.removeFrameCallback(this);
            sampledVsyncId = TIME_UNSET;
            sampledVsyncTimeNs = TIME_UNSET;
            sampledAtNs = TIME_UNSET;
            vsyncDurationNs = TIME_UNSET;
            expectedPresentationTimeNs = TIME_UNSET;
            deadlineNs = TIME_UNSET;
            previousExpectedPresentationTimeNs = TIME_UNSET;
        }

        @Override
        public void doFrame(long vsyncTimeNs) {
            if (!Generation.alive()) {
                return;
            }
            sampledVsyncId = TIME_UNSET;
            sampledVsyncTimeNs = vsyncTimeNs;
            sampledAtNs = System.nanoTime();
            expectedPresentationTimeNs = vsyncTimeNs;
            deadlineNs = TIME_UNSET;
            choreographer.removeFrameCallback(this);
            choreographer.postFrameCallback(this);
        }

        @Override
        public void onDisplayChanged(int displayId) {
            if (displayId == Display.DEFAULT_DISPLAY && registered) {
                choreographer.removeFrameCallback(this);
                choreographer.postFrameCallback(this);
                vsyncDurationNs = displayRefreshPeriodNs(displayManager);
            }
        }
    }

    private static final class VSyncSamplerV33 extends VSyncSampler
            implements Choreographer.VsyncCallback {
        VSyncSamplerV33(Choreographer choreographer, DisplayManager displayManager,
                        Handler handler) {
            super(choreographer, displayManager, handler);
        }

        @Override
        void register() {
            if (registered) {
                return;
            }
            registered = true;
            displayManager.registerDisplayListener(this, handler);
            choreographer.removeVsyncCallback(this);
            choreographer.postVsyncCallback(this);
        }

        @Override
        void unregister() {
            if (!registered) {
                return;
            }
            registered = false;
            displayManager.unregisterDisplayListener(this);
            handler.removeCallbacksAndMessages(null);
            choreographer.removeVsyncCallback(this);
            sampledVsyncId = TIME_UNSET;
            sampledVsyncTimeNs = TIME_UNSET;
            sampledAtNs = TIME_UNSET;
            vsyncDurationNs = TIME_UNSET;
            expectedPresentationTimeNs = TIME_UNSET;
            deadlineNs = TIME_UNSET;
            previousExpectedPresentationTimeNs = TIME_UNSET;
        }

        @Override
        public void onVsync(Choreographer.FrameData data) {
            if (!Generation.alive()) {
                return;
            }
            sampledVsyncTimeNs = data.getFrameTimeNanos();
            sampledAtNs = System.nanoTime();
            Choreographer.FrameTimeline preferred = data.getPreferredFrameTimeline();
            if (preferred == null) {
                sampledVsyncId = TIME_UNSET;
                expectedPresentationTimeNs = sampledVsyncTimeNs;
                deadlineNs = TIME_UNSET;
            } else {
                sampledVsyncId = preferred.getVsyncId();
                expectedPresentationTimeNs = preferred.getExpectedPresentationTimeNanos();
                deadlineNs = preferred.getDeadlineNanos();
            }
            Choreographer.FrameTimeline[] timelines = data.getFrameTimelines();
            long measuredDurationNs = TIME_UNSET;
            if (timelines.length >= 2) {
                measuredDurationNs = timelines[1].getExpectedPresentationTimeNanos()
                        - timelines[0].getExpectedPresentationTimeNanos();
            } else if (previousExpectedPresentationTimeNs != TIME_UNSET
                    && expectedPresentationTimeNs > previousExpectedPresentationTimeNs) {
                measuredDurationNs = expectedPresentationTimeNs
                        - previousExpectedPresentationTimeNs;
            }
            if (measuredDurationNs >= MIN_VSYNC_DURATION_NS
                    && measuredDurationNs <= MAX_VSYNC_DURATION_NS) {
                vsyncDurationNs = measuredDurationNs;
            }
            previousExpectedPresentationTimeNs = expectedPresentationTimeNs;
            publishNativeGrid();
            if (registered) {
                choreographer.postVsyncCallback(this);
            }
        }

        @Override
        public void onDisplayChanged(int displayId) {
            if (displayId == Display.DEFAULT_DISPLAY && registered) {
                handler.removeCallbacksAndMessages(null);
                choreographer.removeVsyncCallback(this);
                choreographer.postVsyncCallback(this);
            }
        }
    }

    private static long displayRefreshPeriodNs(DisplayManager displayManager) {
        Display display = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null || display.getRefreshRate() <= 0f) {
            return TIME_UNSET;
        }
        return (long) (1_000_000_000.0 / display.getRefreshRate());
    }
}
