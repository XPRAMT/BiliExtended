package dev.xpramt.bilisurfacebridge;

import android.util.Log;
import android.view.Display;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * Native-resolution video output on panels whose physical resolution is higher than the UI.
 *
 * <p>On a Sony Xperia XZ Premium (and other 4K phones, or any screen driven at a lower size than
 * its panel) the UI is laid out at 1080p and scaled up by the display hardware, while a
 * SurfaceView whose buffer has the full physical size is scanned out 1:1. Bilibili redraws video
 * with GL into the SurfaceView's own surface, so the buffer is only as big as the view; here the
 * view's buffer is set to the view's size times the ratio of physical to logical resolution
 * ({@link SurfaceHolder#setFixedSize}). The view itself, and so the UI, keeps its size.
 */
final class NativeResolutionOutput {
    private static final String TAG = "BiliExtended";
    private static final int REQUESTED_TAG = 0x7f0b5b20;
    private static final List<WeakReference<SurfaceView>> TRACKED = new ArrayList<>();

    private NativeResolutionOutput() {
    }

    /** Follows the video layer: whenever it is laid out, its SurfaceView is brought up to date. */
    /** Buffer size over view size now in force; the external renderer's display rect follows it. */
    private static volatile double currentScale = 1.0;
    private static volatile WeakReference<Object> externalRender;
    private static boolean displayRectHooked;

    /**
     * The external renderer draws the picture into a pixel rectangle taken from the view's layout.
     * With a bigger buffer the rectangle must grow by the same factor, or the picture only fills
     * the top left corner.
     */
    private static synchronized void hookDisplayRect(ClassLoader loader) {
        if (displayRectHooked) {
            return;
        }
        displayRectHooked = true;
        try {
            Class<?> renderer = Class.forName(
                    "tv.danmaku.ijk.media.player.render.output.IJKPlayerExternalRender", false,
                    loader);
            Xp.findAndHookMethod(renderer, "setDisplayRect",
                    android.graphics.Rect.class, new Xp.Hook() {
                        @Override
                        protected void before(Xp.Param param) {
                            externalRender = new WeakReference<Object>(param.thisObject);
                        }
                    });
            Class<?> wrap = Class.forName(
                    "tv.danmaku.ijk.media.player.render.output.IJKTerminalRenderWrap", false,
                    loader);
            Xp.findAndHookMethod(wrap, "setDisplayRect",
                    android.graphics.Rect.class, new Xp.Hook() {
                        @Override
                        protected void before(Xp.Param param) {
                            double scale = currentScale;
                            android.graphics.Rect rect = (android.graphics.Rect) param.args[0];
                            if (scale > 1.0 && rect != null) {
                                param.args[0] = new android.graphics.Rect(
                                        (int) Math.round(rect.left * scale),
                                        (int) Math.round(rect.top * scale),
                                        (int) Math.round(rect.right * scale),
                                        (int) Math.round(rect.bottom * scale));
                            }
                        }
                    });
        } catch (Throwable throwable) {
            Log.w(TAG, "display rect hook unavailable: " + throwable.getClass().getSimpleName()
                    + " " + throwable.getMessage());
        }
    }

    /** Sends the renderer's own display rect through the hook again, with the current scale. */
    private static void refreshDisplayRect() {
        try {
            WeakReference<Object> reference = externalRender;
            Object renderer = reference == null ? null : reference.get();
            if (renderer == null) {
                return;
            }
            android.graphics.Rect rect = (android.graphics.Rect)
                    Xp.callMethod(renderer, "getDisplayRect");
            if (rect != null) {
                Xp.callMethod(renderer, "setDisplayRect",
                        new android.graphics.Rect(rect));
            }
        } catch (Throwable throwable) {
            Log.w(TAG, "display rect refresh failed: " + throwable.getClass().getSimpleName());
        }
    }

    /** Follows the video layer: whenever it is laid out, its SurfaceView is brought up to date. */
    private static boolean viewportProbe;

    /** Temporary diagnostic: what viewport the video renderer draws with. */
    private static synchronized void probeViewport() {
        if (viewportProbe) {
            return;
        }
        viewportProbe = true;
        String[][] targets = {{"android.opengl.GLES20", "glViewport"},
                {"android.opengl.GLES20", "glScissor"},
                {"android.opengl.GLES30", "glViewport"},
                {"android.opengl.GLES30", "glBlitFramebuffer"}};
        for (final String[] target : targets) {
            try {
                Class<?> owner = Class.forName(target[0]);
                Xp.Hook hook = new Xp.Hook() {
                    private final java.util.HashSet<String> seen = new java.util.HashSet<>();

                    @Override
                    protected void before(Xp.Param param) {
                        if (!Thread.currentThread().getName().contains("ext_render")) {
                            return;
                        }
                        String key = target[1] + java.util.Arrays.toString(param.args);
                        if (seen.add(key)) {
                            Log.i(TAG, "gl " + key);
                        }
                    }
                };
                for (java.lang.reflect.Method method : owner.getDeclaredMethods()) {
                    if (method.getName().equals(target[1])) {
                        Xp.hookMethod(method, hook);
                    }
                }
            } catch (Throwable throwable) {
                Log.w(TAG, "gl probe failed: " + throwable);
            }
        }
    }

    private static boolean surfaceProbe;

    private static synchronized void probeSurfaceChange(ClassLoader loader) {
        if (surfaceProbe) {
            return;
        }
        surfaceProbe = true;
        try {
            Class<?> type = Class.forName(
                    "tv.danmaku.ijk.media.player.render.output.IJKPlayerExternalRender", false,
                    loader);
            Xp.findAndHookMethod(type, "onSurfaceChange",
                    int.class, int.class, new Xp.Hook() {
                        @Override
                        protected void before(Xp.Param param) {
                            Log.i(TAG, "onSurfaceChange " + param.args[0] + "x" + param.args[1]);
                        }
                    });
        } catch (Throwable throwable) {
            Log.w(TAG, "surface probe failed: " + throwable);
        }
    }

    /** The render layers attached so far (Bilibili's views): a hot-reloaded generation attaches them again. */
    private static final List<WeakReference<Object>> LAYERS = new ArrayList<>();

    /** Hot reload: layers, tracked SurfaceViews, the renderer whose display rect is scaled, the scale in force. */
    static Object[] exportForHotReload() {
        List<Object> layers = new ArrayList<>();
        synchronized (LAYERS) {
            for (WeakReference<Object> reference : LAYERS) {
                if (reference.get() != null) {
                    layers.add(reference.get());
                }
            }
        }
        List<Object> views = new ArrayList<>();
        synchronized (TRACKED) {
            for (WeakReference<SurfaceView> reference : TRACKED) {
                if (reference.get() != null) {
                    views.add(reference.get());
                }
            }
        }
        WeakReference<Object> renderer = externalRender;
        return new Object[]{layers, views, renderer == null ? null : renderer.get(), Double.valueOf(currentScale)};
    }

    static void adoptAfterHotReload(Object exported) {
        if (!(exported instanceof Object[]) || ((Object[]) exported).length < 4) {
            return;
        }
        Object[] state = (Object[]) exported;
        if (state[3] instanceof Double) {
            currentScale = (Double) state[3];
        }
        if (state[2] != null) {
            externalRender = new WeakReference<Object>(state[2]);
        }
        if (state[1] instanceof List) {
            for (Object view : (List<?>) state[1]) {
                if (view instanceof SurfaceView) {
                    track((SurfaceView) view);
                }
            }
        }
        if (state[0] instanceof List) {
            for (Object layer : (List<?>) state[0]) {
                if (layer != null) {
                    attach(layer);
                }
            }
        }
    }

    static void attach(Object layer) {
        hookDisplayRect(layer.getClass().getClassLoader());
        synchronized (LAYERS) {
            LAYERS.add(new WeakReference<Object>(layer));
        }
        if (!(layer instanceof View)) {
            return;
        }
        final View root = (View) layer;
        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View view, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                if (!Generation.alive()) {
                    root.removeOnLayoutChangeListener(this);   // handed over: the next generation listens itself
                    return;
                }
                SurfaceView surfaceView = findSurfaceView(root, 0);
                if (surfaceView != null) {
                    track(surfaceView);
                    apply(surfaceView);
                }
            }
        });
    }

    /** Applies a changed setting now, to every video view seen so far. */
    static void refreshAll() {
        synchronized (TRACKED) {
            for (WeakReference<SurfaceView> reference : TRACKED) {
                final SurfaceView surfaceView = reference.get();
                if (surfaceView != null) {
                    surfaceView.post(new Runnable() {
                        @Override
                        public void run() {
                            apply(surfaceView);
                        }
                    });
                }
            }
        }
    }

    private static void track(SurfaceView surfaceView) {
        synchronized (TRACKED) {
            for (WeakReference<SurfaceView> reference : TRACKED) {
                if (reference.get() == surfaceView) {
                    return;
                }
            }
            TRACKED.add(new WeakReference<SurfaceView>(surfaceView));
        }
    }

    /** Sets, updates or removes the full-size buffer so that it matches the setting and the view. */
    private static void apply(SurfaceView view) {
        try {
            int[] requested = (int[]) view.getTag(REQUESTED_TAG);
            double scale = ModulePreferences.loadNativeResolutionOutputForHook() ? scale(view) : 1.0;
            int width = view.getWidth();
            int height = view.getHeight();
            SurfaceHolder holder = view.getHolder();
            if (scale > 1.0 && width > 0 && height > 0) {
                int bufferWidth = (int) Math.round(width * scale);
                int bufferHeight = (int) Math.round(height * scale);
                if (requested == null || requested[0] != bufferWidth
                        || requested[1] != bufferHeight) {
                    view.setTag(REQUESTED_TAG, new int[]{bufferWidth, bufferHeight});
                    currentScale = scale;
                    holder.setFixedSize(bufferWidth, bufferHeight);
                    refreshDisplayRect();
                    Log.i(TAG, "native resolution output: video buffer " + bufferWidth + "x"
                            + bufferHeight + " for a " + width + "x" + height + " view (x"
                            + scale + ")");
                }
            } else if (requested != null) {
                view.setTag(REQUESTED_TAG, null);
                currentScale = 1.0;
                holder.setSizeFromLayout();
                refreshDisplayRect();
                Log.i(TAG, "native resolution output: back to the view's own size");
            }
        } catch (Throwable throwable) {
            Log.w(TAG, "native resolution output failed: "
                    + throwable.getClass().getSimpleName() + " " + throwable.getMessage());
        }
    }

    /** Physical over logical resolution (long edges), 1.0 where the panel has nothing extra. */
    private static double scale(View view) {
        Display display = view.getDisplay();
        if (display == null) {
            display = ((WindowManager) view.getContext().getSystemService(
                    android.content.Context.WINDOW_SERVICE)).getDefaultDisplay();
        }
        ScreenPhysicalSize.Size physical = ScreenPhysicalSize.physical(display);
        ScreenPhysicalSize.Size logical = ScreenPhysicalSize.logical(display);
        if (physical.area() <= logical.area()) {
            return 1.0;
        }
        return (double) physical.longEdge / logical.longEdge;
    }

    private static SurfaceView findSurfaceView(View view, int depth) {
        if (view instanceof SurfaceView) {
            return (SurfaceView) view;
        }
        if (view instanceof ViewGroup && depth < 6) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                SurfaceView found = findSurfaceView(group.getChildAt(index), depth + 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
