package dev.xpramt.bilisurfacebridge;

import android.app.Activity;
import android.content.res.Configuration;
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

import java.lang.ref.WeakReference;
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

    private int appliedX = Integer.MIN_VALUE;
    private int appliedY = -1;
    private int appliedTextSizeSp = -1;

    private void applyState() {
        renderText();
    }

    private boolean landscape() {
        return label.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
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

    /**
     * Where the overlay's left edge is now, in dp: the stored X, or, while it is still centred,
     * wherever the centring put it, so a slider can start from what is on screen.
     */
    static int currentXDp(Activity activity, boolean landscape, int fallbackDp) {
        int stored = ModulePreferences.loadOverlayXDpForHook(landscape);
        if (stored >= 0) {
            return stored;
        }
        PlaybackDebugOverlay overlay = OVERLAYS.get(activity);
        if (overlay != null && overlay.label.getWidth() > 0) {
            return Math.round(overlay.label.getLeft()
                    / overlay.label.getResources().getDisplayMetrics().density);
        }
        return fallbackDp;
    }

    /** Position (X from the left, Y from the top, dp, per orientation) and text size. */
    private void applyLayout() {
        int sp = ModulePreferences.loadOverlayTextSizeSpForHook();
        if (sp != appliedTextSizeSp) {
            appliedTextSizeSp = sp;
            label.setTextSize(sp);
        }
        boolean landscape = landscape();
        int x = ModulePreferences.loadOverlayXDpForHook(landscape);
        int y = ModulePreferences.loadOverlayYDpForHook(landscape);
        if (x == appliedX && y == appliedY) {
            return;
        }
        appliedX = x;
        appliedY = y;
        ViewGroup.LayoutParams parameters = label.getLayoutParams();
        if (parameters instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams layout = (FrameLayout.LayoutParams) parameters;
            float density = label.getResources().getDisplayMetrics().density;
            layout.topMargin = Math.round(y * density);
            if (x == ModulePreferences.OVERLAY_X_UNSET) {
                layout.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                layout.leftMargin = 0;
            } else {
                layout.gravity = Gravity.TOP | Gravity.START;
                layout.leftMargin = Math.round(x * density);
            }
            label.setLayoutParams(layout);
        }
    }

    private final WeakReference<Activity> activityRef;
    private float lastFps;

    /**
     * Puts the label back if it was taken out of the window. The overlay is added once, when the
     * player's renderer is created; Bilibili's own screen can rebuild its content (a rotation
     * switches between the page's layouts, and setContentView() removes every view added to the
     * content area since), and nothing else would add it again.
     */
    private void reattachIfRemoved() {
        if (label.getParent() != null) {
            return;
        }
        Activity activity = activityRef.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        try {
            ViewGroup.LayoutParams layout = label.getLayoutParams();
            activity.addContentView(label, layout != null ? layout : new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL));
            appliedX = Integer.MIN_VALUE;  // lay it out again at the stored position
            android.util.Log.i("BiliSurfaceBridge", "info overlay was removed from "
                    + activity.getClass().getSimpleName() + "; attached it again");
        } catch (Throwable throwable) {
            android.util.Log.w("BiliSurfaceBridge", "info overlay could not be attached again: "
                    + throwable.getClass().getSimpleName());
        }
    }

    /**
     * The info line. Two independent parts joined with " | ": the fps part (Media3 Vsync state,
     * average fps, 1% low, renderer) follows the fps overlay switch, the stream format follows
     * the format switch. Nothing to show hides the line.
     */
    private void renderText() {
        StringBuilder text = new StringBuilder();
        if (ModulePreferences.loadPlaybackDebugOverlayForHook()) {
            text.append(ModulePreferences.loadMedia3FrameSyncForHook() ? "On" : "Off")
                    .append(" | Avg ").append(Math.round(lastFps)).append("fps | 1%Low ")
                    .append(callbackOnePercentLowText()).append(" | ").append(renderer);
        }
        String stream = PlaybackStreamOverlay.labelForDebugOverlay();
        if (!stream.isEmpty()) {
            if (text.length() > 0) {
                text.append(" | ");
            }
            text.append(stream);
        }
        label.setText(text);
        label.setVisibility(text.length() > 0 ? View.VISIBLE : View.GONE);
        applyLayout();
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
            reattachIfRemoved();
            renderText();
            mainHandler.postDelayed(this, 1000L);
        }
    };

    private PlaybackDebugOverlay(Activity activity) {
        activityRef = new WeakReference<Activity>(activity);
        label = new OutlinedTextView(activity);
        label.setTextColor(Color.WHITE);
        label.setTextSize(ModulePreferences.loadOverlayTextSizeSpForHook());
        label.setOutlineWidthPx(dp(activity, 1));
        label.setGravity(Gravity.CENTER);
        label.setPadding(dp(activity, 2), dp(activity, 1), dp(activity, 2), dp(activity, 1));
        label.setBackgroundColor(Color.TRANSPARENT);
        label.setClickable(false);
        label.setFocusable(false);
        FrameLayout.LayoutParams parameters = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        parameters.topMargin = dp(activity, ModulePreferences.loadOverlayYDpForHook(false));
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
        overlay.reattachIfRemoved();
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
        switch (HdrVideoRenderer.effectiveMode(
                ModulePreferences.loadVideoDetailRendererModeForHook())) {
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
