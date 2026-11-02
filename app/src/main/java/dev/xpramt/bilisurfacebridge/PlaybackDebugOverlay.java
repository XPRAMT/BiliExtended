package dev.xpramt.bilisurfacebridge;

import android.app.Activity;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.FrameMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Lightweight, opt-in video-display FPS overlay for the normal Bilibili detail player. */
final class PlaybackDebugOverlay {
    private static final int FRAME_INTERVAL_CAPACITY = 1500;
    private static final int MINIMUM_INTERVAL_SAMPLES = 600;
    private static final long FRAME_INTERVAL_WINDOW_NANOS = 10_000_000_000L;
    private static final AtomicLong DISPLAYED_FRAMES = new AtomicLong();
    private static final Map<Activity, PlaybackDebugOverlay> OVERLAYS =
            Collections.synchronizedMap(new WeakHashMap<Activity, PlaybackDebugOverlay>());
    private static final Object FRAME_TIMING_LOCK = new Object();
    private static final long[] FRAME_INTERVALS_NANOS = new long[FRAME_INTERVAL_CAPACITY];
    private static final long[] FRAME_INTERVAL_END_NANOS = new long[FRAME_INTERVAL_CAPACITY];
    private static int frameIntervalCount;
    private static int frameIntervalNext;
    private static int frameIntervalOldest;
    private static long previousCallbackTimeNanos;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final OutlinedTextView label;
    private String renderer = "unknown";
    private long previousFrameCount;
    private long previousSampleTime;

    private int appliedTopMarginDp = -1;

    /** Shown only while the setting is on; the margin follows the setting. */
    private void applyState() {
        label.setVisibility(ModulePreferences.loadPlaybackDebugOverlayForHook()
                ? View.VISIBLE : View.GONE);
        applyTopMargin();
    }

    /** Applies a changed setting right away instead of at the next one-second tick. */
    static void refreshAll() {
        synchronized (OVERLAYS) {
            for (final PlaybackDebugOverlay overlay : OVERLAYS.values()) {
                overlay.mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        overlay.renderText();
                    }
                });
            }
        }
    }

    private void applyTopMargin() {
        int marginDp = ModulePreferences.loadOverlayTopMarginDpForHook();
        if (marginDp == appliedTopMarginDp) {
            return;
        }
        appliedTopMarginDp = marginDp;
        ViewGroup.LayoutParams parameters = label.getLayoutParams();
        if (parameters instanceof FrameLayout.LayoutParams) {
            ((FrameLayout.LayoutParams) parameters).topMargin =
                    Math.round(marginDp * label.getResources().getDisplayMetrics().density);
            label.setLayoutParams(parameters);
        }
    }

    private float lastFps;

    /** The single info line: Media3 Vsync state, fps, 1% low, renderer, then the stream format. */
    private void renderText() {
        String media3State = ModulePreferences.loadMedia3FrameSyncForHook() ? "On" : "Off";
        StringBuilder text = new StringBuilder(media3State)
                .append(" | Avg ").append(Math.round(lastFps)).append("fps | 1%Low ")
                .append(callbackOnePercentLowText()).append(" | ").append(renderer);
        String stream = PlaybackStreamOverlay.labelForDebugOverlay();
        if (!stream.isEmpty()) {
            text.append(" | ").append(stream);
        }
        label.setText(text);
        applyState();
    }

    private final Runnable update = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.elapsedRealtime();
            long frames = DISPLAYED_FRAMES.get();
            long elapsed = Math.max(1L, now - previousSampleTime);
            lastFps = (frames - previousFrameCount) * 1000.0f / elapsed;
            previousFrameCount = frames;
            previousSampleTime = now;
            renderText();
            mainHandler.postDelayed(this, 1000L);
        }
    };

    private PlaybackDebugOverlay(Activity activity) {
        label = new OutlinedTextView(activity);
        label.setTextColor(Color.WHITE);
        label.setTextSize(9.0f);
        label.setOutlineWidthPx(dp(activity, 1));
        label.setGravity(Gravity.CENTER);
        label.setPadding(dp(activity, 2), dp(activity, 1), dp(activity, 2), dp(activity, 1));
        label.setBackgroundColor(Color.TRANSPARENT);
        label.setClickable(false);
        label.setFocusable(false);
        FrameLayout.LayoutParams parameters = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        parameters.topMargin = dp(activity, ModulePreferences.loadOverlayTopMarginDpForHook());
        activity.addContentView(label, parameters);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            try {
                activity.getWindow().addOnFrameMetricsAvailableListener(
                        new Window.OnFrameMetricsAvailableListener() {
                            @Override
                            public void onFrameMetricsAvailable(Window window, FrameMetrics metrics,
                                                                int dropCountSinceLastInvocation) {
                                try {
                                    Media3FrameSynchronizer.recordWindowFrameMetrics(
                                            System.nanoTime(),
                                            metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP),
                                            metrics.getMetric(FrameMetrics.VSYNC_TIMESTAMP),
                                            metrics.getMetric(FrameMetrics.DEADLINE),
                                            metrics.getMetric(FrameMetrics.TOTAL_DURATION),
                                            metrics.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION),
                                            dropCountSinceLastInvocation);
                                } catch (Throwable ignored) {
                                    // FrameMetrics can be unavailable during window teardown.
                                }
                            }
                        }, mainHandler);
                Media3FrameSynchronizer.recordWindowFrameMetricsListenerState("REGISTERED");
            } catch (Throwable exception) {
                Media3FrameSynchronizer.recordWindowFrameMetricsListenerState(
                        "REGISTER_FAILED_" + exception.getClass().getSimpleName());
            }
        }
        previousFrameCount = DISPLAYED_FRAMES.get();
        previousSampleTime = SystemClock.elapsedRealtime();
        applyState();
        mainHandler.postDelayed(update, 1000L);
    }

    private static String callbackOnePercentLowText() {
        synchronized (FRAME_TIMING_LOCK) {
            if (frameIntervalCount < MINIMUM_INTERVAL_SAMPLES) {
                return "--";
            }
            long[] sortedIntervals = new long[frameIntervalCount];
            for (int index = 0; index < frameIntervalCount; ++index) {
                sortedIntervals[index] = FRAME_INTERVALS_NANOS[
                        (frameIntervalOldest + index) % FRAME_INTERVAL_CAPACITY];
            }
            Arrays.sort(sortedIntervals);
            int worstSampleCount = Math.max(1,
                    (int) Math.ceil(frameIntervalCount * 0.01));
            long slowestIntervalTotal = 0L;
            for (int index = sortedIntervals.length - worstSampleCount;
                    index < sortedIntervals.length; ++index) {
                slowestIntervalTotal += sortedIntervals[index];
            }
            double onePercentLowFps = (worstSampleCount * 1_000_000_000.0)
                    / slowestIntervalTotal;
            return Math.round(onePercentLowFps) + "fps";
        }
    }

    static void recordFrameCallback() {
        DISPLAYED_FRAMES.incrementAndGet();
        long now = SystemClock.elapsedRealtimeNanos();
        synchronized (FRAME_TIMING_LOCK) {
            if (previousCallbackTimeNanos != 0L) {
                long interval = now - previousCallbackTimeNanos;
                if (interval > 0L && interval <= 1_000_000_000L) {
                    discardExpiredIntervals(now);
                    if (frameIntervalCount == FRAME_INTERVAL_CAPACITY) {
                        frameIntervalOldest = (frameIntervalOldest + 1) % FRAME_INTERVAL_CAPACITY;
                        --frameIntervalCount;
                    }
                    FRAME_INTERVALS_NANOS[frameIntervalNext] = interval;
                    FRAME_INTERVAL_END_NANOS[frameIntervalNext] = now;
                    frameIntervalNext = (frameIntervalNext + 1) % FRAME_INTERVAL_CAPACITY;
                    ++frameIntervalCount;
                }
            }
            previousCallbackTimeNanos = now;
        }
    }

    static void resetFrameTiming() {
        synchronized (FRAME_TIMING_LOCK) {
            frameIntervalCount = 0;
            frameIntervalNext = 0;
            frameIntervalOldest = 0;
            previousCallbackTimeNanos = 0L;
        }
    }

    private static void discardExpiredIntervals(long now) {
        while (frameIntervalCount > 0
                && now - FRAME_INTERVAL_END_NANOS[frameIntervalOldest]
                        > FRAME_INTERVAL_WINDOW_NANOS) {
            frameIntervalOldest = (frameIntervalOldest + 1) % FRAME_INTERVAL_CAPACITY;
            --frameIntervalCount;
        }
    }

    static void show(Activity activity, String rendererClass) {
        PlaybackDebugOverlay overlay = OVERLAYS.get(activity);
        if (overlay == null) {
            overlay = new PlaybackDebugOverlay(activity);
            OVERLAYS.put(activity, overlay);
        }
        overlay.renderer = rendererName(rendererClass);
    }

    static Object frameCallback(Class<?> callbackClass, Object delegate) {
        return Proxy.newProxyInstance(callbackClass.getClassLoader(), new Class<?>[]{callbackClass},
                new FrameCallbackProxy(delegate,
                        ModulePreferences.loadRenderCadenceProbeForHook()));
    }

    static final class FrameCallbackProxy implements InvocationHandler {
        private final Object delegate;
        private final boolean renderCadenceProbeEnabled;

        FrameCallbackProxy(Object delegate, boolean renderCadenceProbeEnabled) {
            this.delegate = delegate;
            this.renderCadenceProbeEnabled = renderCadenceProbeEnabled;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("onVideoDisplay".equals(method.getName())) {
                RenderCadenceProbe.DisplayScope scope =
                        renderCadenceProbeEnabled
                                ? RenderCadenceProbe.beginDisplayCallback() : null;
                try {
                    recordFrameCallback();
                    if (delegate == null) {
                        return 0;
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                } finally {
                    if (scope != null) {
                        RenderCadenceProbe.endDisplayCallback(scope);
                    }
                }
            }
            if (delegate != null) {
                try {
                    return method.invoke(delegate, args);
                } catch (InvocationTargetException exception) {
                    throw exception.getCause();
                }
            }
            if (method.getReturnType() == Integer.TYPE) {
                return 0;
            }
            return null;
        }
    }

    // Bilibili's renderer classes are obfuscated (tv.danmaku.render.core.c), so the label comes
    // from the renderer mode the module selected.
    private static String rendererName(String rendererClass) {
        switch (ModulePreferences.loadVideoDetailRendererModeForHook()) {
            case TEXTURE_EXTERNAL:
                return "Texture";
            case SURFACE_EXTERNAL:
                return "Surface Ext";
            default:
                return "Surface";
        }
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
