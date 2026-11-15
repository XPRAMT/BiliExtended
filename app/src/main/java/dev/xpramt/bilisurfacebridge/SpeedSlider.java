package dev.xpramt.bilisurfacebridge;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Replaces the full-screen speed menu's list with a vertical slider (user 2026-11-15): 20 evenly spaced stops
 * ({@link SpeedSliderScale#STOPS}), 10x at the top, applied when the finger lifts.
 *
 * <p>Where: when one of the speed-menu adapters (found by {@link DexScan}) is attached to its RecyclerView, the list is made invisible
 * (its layout slot stays) and the slider is added to the same parent with a copy of the list's layout params, on top of it.  How the
 * speed is applied: through the adapter's own listener -- the object a row click calls with the picked speed -- so Bilibili's own
 * speed path runs.  Only when the "high speed" switch is on; off leaves Bilibili's list untouched.
 */
final class SpeedSlider {
    private static final String TAG = "BiliExtended";
    private static final int PINK = 0xFFFB7299;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int TRACK = 0x4DFFFFFF;

    /** The adapter classes the slider takes over. */
    private static final Set<Class<?>> ADAPTERS = Collections.synchronizedSet(new java.util.HashSet<Class<?>>());
    /** Current speed per adapter, as the selection fix computed it. */
    private static final Map<Object, Float> CURRENT = Collections.synchronizedMap(new WeakHashMap<Object, Float>());
    /** The slider shown for each adapter. */
    private static final Map<Object, SliderView> VIEWS = Collections.synchronizedMap(new WeakHashMap<Object, SliderView>());
    private static final Map<View, Boolean> REPLACED = Collections.synchronizedMap(new WeakHashMap<View, Boolean>());
    private static volatile boolean installed;
    private static volatile boolean shownLogged;

    private SpeedSlider() {
    }

    /** Hooks RecyclerView.Adapter#onAttachedToRecyclerView once; `adapterClass` is one speed-menu adapter. */
    static String install(Class<?> adapterClass) {
        ADAPTERS.add(adapterClass);
        if (installed) {
            return "";
        }
        Class<?> base = adapterClass.getSuperclass();
        Class<?> recyclerView = base == null ? null : base.getDeclaringClass();
        if (recyclerView == null) {
            return " slider: RecyclerView not found";
        }
        try {
            Method attached = base.getMethod("onAttachedToRecyclerView", recyclerView);
            Xp.hookMethod(attached, new Xp.Hook() {
                @Override
                protected void after(Xp.Param param) {
                    final Object adapter = param.thisObject;
                    if (adapter == null || !ADAPTERS.contains(adapter.getClass()) || !Generation.alive()) {
                        return;
                    }
                    if (!ModulePreferences.loadSpeedMenuExtraForHook()) {
                        return;   // high speed off: Bilibili's own list
                    }
                    final View list = (View) param.args[0];
                    list.post(() -> replace(list, adapter));
                }
            });
            installed = true;
            return " +slider";
        } catch (Throwable throwable) {
            return " slider failed=" + throwable.getClass().getSimpleName();
        }
    }

    /** From the selection fix: the speed the panel shows as current. */
    static void noteCurrent(Object adapter, float speed) {
        CURRENT.put(adapter, speed);
        SliderView view = VIEWS.get(adapter);
        if (view != null) {
            view.post(view::syncFromAdapter);
        }
    }

    private static void replace(View list, Object adapter) {
        try {
            if (REPLACED.containsKey(list) || !(list.getParent() instanceof ViewGroup) || !Generation.alive()) {
                return;
            }
            if (list.getWidth() > 0 && list.getHeight() > 0 && list.getHeight() < list.getWidth()) {
                return;   // a horizontal list (another layout of the same adapter): leave it
            }
            Object listener = listener(adapter);
            if (listener == null) {
                Xp.log(TAG + ": speed slider skipped: no speed listener on " + adapter.getClass().getName());
                return;
            }
            ViewGroup parent = (ViewGroup) list.getParent();
            ViewGroup.LayoutParams params = copyParams(parent, list.getLayoutParams());
            // The list's own size, in pixels.  Its params say WRAP_CONTENT (the rows decide the width); a plain View given WRAP_CONTENT takes
            // all the room it is offered, which widened the whole panel to almost the full screen (2026-11-15 tablet: 37..2750 of 2880),
            // dimmed the video and swallowed the "tap outside to close" area.
            // Width: the slider measures its own content (user 2026-11-15: "寬度跟隨內容，盡量小，不裁切"); height: the list's.
            params.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            if (list.getHeight() > 0) {
                params.height = list.getHeight();
            }
            if (!ancestorsLogged) {
                ancestorsLogged = true;
                Xp.log(TAG + ": speed slider ancestors " + ancestors(list));
            }
            SliderView slider = new SliderView(list.getContext(), adapter);
            parent.addView(slider, parent.indexOfChild(list) + 1, params);
            // GONE, not INVISIBLE: the panel (FrameLayout, WRAP_CONTENT, 9.19 tablet) measures invisible children too and stayed as wide as the list.
            list.setVisibility(View.GONE);
            slider.takeOverBackdrop(parent);
            REPLACED.put(list, Boolean.TRUE);
            VIEWS.put(adapter, slider);
            if (!shownLogged) {
                shownLogged = true;
                Xp.log(TAG + ": speed slider shown in " + parent.getClass().getName() + " over " + list.getWidth() + "x" + list.getHeight()
                        + " adapter=" + adapter.getClass().getName());
            }
        } catch (Throwable throwable) {
            Xp.log(TAG + ": speed slider failed=" + throwable.getClass().getSimpleName() + " " + throwable.getMessage());
        }
    }

    private static volatile boolean ancestorsLogged;

    /** The list and four levels above it: class, size, layout width, background -- to see who draws the panel's dark backdrop. */
    private static String ancestors(View list) {
        StringBuilder out = new StringBuilder();
        View v = list;
        for (int level = 0; level < 5 && v != null; level++) {
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            android.graphics.drawable.Drawable bg = v.getBackground();
            out.append(" [").append(level).append(' ').append(v.getClass().getName())
                    .append(' ').append(v.getLeft()).append(',').append(v.getTop()).append(' ').append(v.getWidth()).append('x').append(v.getHeight())
                    .append(" lpW=").append(lp == null ? "-" : String.valueOf(lp.width))
                    .append(" pad=").append(v.getPaddingLeft()).append('/').append(v.getPaddingRight())
                    .append(" bg=").append(bg == null ? "-" : bg.getClass().getSimpleName()
                            + (bg instanceof android.graphics.drawable.ColorDrawable
                                    ? "#" + Integer.toHexString(((android.graphics.drawable.ColorDrawable) bg).getColor()) : ""))
                    .append(']');
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        return out.toString();
    }

    /** The parent's own copy of the list's params (ConstraintLayout keeps per-view state in them, so they are never shared). */
    private static ViewGroup.LayoutParams copyParams(ViewGroup parent, ViewGroup.LayoutParams source) throws Exception {
        for (Class<?> c = parent.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Method generate = c.getDeclaredMethod("generateLayoutParams", ViewGroup.LayoutParams.class);
                generate.setAccessible(true);
                return (ViewGroup.LayoutParams) generate.invoke(parent, source);
            } catch (NoSuchMethodException ignored) {
                // superclass
            }
        }
        return new ViewGroup.LayoutParams(source);
    }

    /** The adapter's click listener: an instance field whose interface has a void method taking one float. */
    private static Object listener(Object adapter) throws IllegalAccessException {
        for (Field field : adapter.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || !field.getType().isInterface()) {
                continue;
            }
            if (speedMethod(field.getType()) == null) {
                continue;
            }
            field.setAccessible(true);
            Object value = field.get(adapter);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Method speedMethod(Class<?> type) {
        for (Method method : type.getMethods()) {
            Class<?>[] ps = method.getParameterTypes();
            if (ps.length == 1 && ps[0] == float.class && method.getReturnType() == void.class && Modifier.isAbstract(method.getModifiers())) {
                return method;
            }
        }
        return null;
    }

    private static void apply(Object adapter, float speed) {
        try {
            Object listener = listener(adapter);
            Method method = listener == null ? null : speedMethod(listener.getClass().getInterfaces().length > 0
                    ? interfaceWithSpeed(listener.getClass()) : listener.getClass());
            if (method == null) {
                Xp.log(TAG + ": speed slider apply skipped: listener gone");
                return;
            }
            SpeedMenuHook.notePicked(speed);
            method.setAccessible(true);
            method.invoke(listener, speed);
            CURRENT.put(adapter, speed);
        } catch (Throwable throwable) {
            Xp.log(TAG + ": speed slider apply failed=" + throwable.getClass().getSimpleName() + " " + throwable.getMessage());
        }
    }

    private static Class<?> interfaceWithSpeed(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Class<?> i : c.getInterfaces()) {
                if (speedMethod(i) != null) {
                    return i;
                }
            }
        }
        return type;
    }

    /**
     * Opacity profile of Bilibili's own landscape side-panel backdrop, {@code drawable/bili_setting_function_shadow_landscape_linear}
     * (9.19: a 930 x 9 px black PNG, alpha 1..225 left to right): 25 evenly spaced samples, normalised to the darkest.  An S-shaped ease --
     * slow start, steep middle, long soft shoulder -- instead of a straight ramp.
     */
    static final float[] BILIBILI_FADE = {
            0.004f, 0.027f, 0.058f, 0.084f, 0.120f, 0.151f, 0.200f, 0.244f, 0.302f, 0.369f, 0.449f, 0.538f, 0.636f,
            0.724f, 0.800f, 0.853f, 0.893f, 0.929f, 0.947f, 0.964f, 0.978f, 0.987f, 0.991f, 0.991f, 1.000f};

    static android.graphics.LinearGradient fadeShader(float x0, float width, int color) {
        int n = BILIBILI_FADE.length;
        int[] colors = new int[n];
        float[] positions = new float[n];
        int alpha = color >>> 24;
        for (int i = 0; i < n; i++) {
            colors[i] = (Math.round(alpha * BILIBILI_FADE[i]) << 24) | (color & 0x00FFFFFF);
            positions[i] = i / (float) (n - 1);
        }
        return new android.graphics.LinearGradient(x0, 0f, x0 + width, 0f, colors, positions, android.graphics.Shader.TileMode.CLAMP);
    }

    /** A solid colour whose left `fade` pixels ease in from transparent along {@link #BILIBILI_FADE}. */
    private static final class FadeBackdrop extends android.graphics.drawable.Drawable {
        private final int color;
        private final float fade;
        private final Paint paint = new Paint();
        private int shaderFor = -1;

        FadeBackdrop(int color, float fade) {
            this.color = color;
            this.fade = fade;
            paint.setDither(true);
        }

        @Override
        public void draw(Canvas canvas) {
            android.graphics.Rect b = getBounds();
            if (shaderFor != b.left) {
                shaderFor = b.left;
                paint.setShader(fadeShader(b.left, fade, color));
            }
            canvas.drawRect(b, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter filter) {
            paint.setColorFilter(filter);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    /**
     * The slider: labels on the left, the track on the right, 10x at the top.  Width = content (fade + widest label + gap + thumb + right
     * margin), so nothing is clipped and nothing is wasted; the backdrop eases in over {@link #FADE_DP} left of the content.
     */
    private static final class SliderView extends View {
        /** Touch area: from this far left of the track (covers the labels) to the right edge. */
        private static final float TOUCH_WIDTH_DP = 150f;
        /** Space before the widest label inside the slider itself. */
        private static final float LEAD_DP = 8f;
        /** Transparent-to-dark ease added to the left of the panel (its original left padding fades too). */
        private static final float FADE_DP = 64f;
        private static final float LABEL_GAP_DP = 22f;
        private static final float THUMB_DP = 11f;
        private static final float RIGHT_DP = 18f;
        private static final float INSET_DP = 24f;
        private static final int BACKDROP = 0xE6111111;
        private final Paint backdrop = new Paint();
        private int backdropFor = -1;
        /** True when this view draws the backdrop itself (the panel had no plain colour background to take over). */
        private boolean ownBackdrop = true;
        private ViewGroup panel;
        private android.graphics.drawable.Drawable panelBackground;
        private int panelPaddingLeft;

        /**
         * The panel's own backdrop is a plain colour (9.19 tablet: #d6000000 on the FrameLayout around the list, padding 37/130): replace
         * it with the same colour faded in from transparent over its left padding plus {@link #FADE_DP}; put the original back when this
         * view leaves the screen.  Any other kind of backdrop is left alone and this view draws its own faded one.
         */
        void takeOverBackdrop(ViewGroup parent) {
            android.graphics.drawable.Drawable bg = parent.getBackground();
            if (!(bg instanceof android.graphics.drawable.ColorDrawable)) {
                return;
            }
            panel = parent;
            panelBackground = bg;
            panelPaddingLeft = parent.getPaddingLeft();
            int color = ((android.graphics.drawable.ColorDrawable) bg).getColor();
            int extra = Math.round(FADE_DP * density);
            // the ease gets its own room left of the content: the panel grows by `extra`, the labels stay on the solid part
            parent.setPadding(panelPaddingLeft + extra, parent.getPaddingTop(), parent.getPaddingRight(), parent.getPaddingBottom());
            parent.setBackground(new FadeBackdrop(color, panelPaddingLeft + extra));
            ownBackdrop = false;
            requestLayout();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (panel != null && panel.getBackground() instanceof FadeBackdrop) {
                panel.setBackground(panelBackground);
                panel.setPadding(panelPaddingLeft, panel.getPaddingTop(), panel.getPaddingRight(), panel.getPaddingBottom());
            }
        }
        private final Object adapter;
        private final float density;
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int current;
        private int preview = -1;

        SliderView(Context context, Object adapter) {
            super(context);
            this.adapter = adapter;
            this.density = context.getResources().getDisplayMetrics().density;
            text.setColor(WHITE);
            text.setTextAlign(Paint.Align.RIGHT);
            track.setColor(TRACK);
            track.setStrokeCap(Paint.Cap.ROUND);
            thumb.setColor(PINK);
            setClickable(true);
            syncFromAdapter();
        }

        void syncFromAdapter() {
            Float speed = CURRENT.get(adapter);
            current = SpeedSliderScale.nearestIndex(speed == null ? 1.0f : speed);
            setContentDescription("倍速 " + SpeedSliderScale.label(SpeedSliderScale.STOPS[current]));
            invalidate();
        }

        private float top() {
            return getPaddingTop() + INSET_DP * density;
        }

        private float bottom() {
            return getHeight() - getPaddingBottom() - INSET_DP * density;
        }

        private float trackX() {
            return getWidth() - (RIGHT_DP + THUMB_DP) * density;
        }

        /** Label size for a given view height: at most 15 sp, small enough that 19 rows never overlap. */
        private float textSizeFor(int height) {
            float usable = height - 2 * INSET_DP * density;
            float spacing = usable / (SpeedSliderScale.STOPS.length - 1);
            return Math.max(1f, Math.min(15 * getResources().getDisplayMetrics().scaledDensity, spacing * 0.72f));
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int height = MeasureSpec.getSize(heightSpec);
            text.setTextSize(textSizeFor(height));
            float widest = 0f;
            for (float stop : SpeedSliderScale.STOPS) {
                // bold measures wider than regular: the selected label is drawn bold
                text.setTypeface(Typeface.DEFAULT_BOLD);
                widest = Math.max(widest, text.measureText(SpeedSliderScale.label(stop)));
            }
            // the fade lives in the panel's padding when the panel backdrop was taken over; otherwise this view draws it and needs the room
            float lead = (ownBackdrop ? FADE_DP : LEAD_DP) * density;
            int want = (int) Math.ceil(lead + widest + (LABEL_GAP_DP + THUMB_DP + RIGHT_DP) * density);
            setMeasuredDimension(resolveSize(want, widthSpec), resolveSize(height, heightSpec));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (ownBackdrop) {
                if (backdropFor != getWidth()) {
                    backdropFor = getWidth();
                    backdrop.setShader(fadeShader(0f, Math.min(FADE_DP * density, getWidth()), BACKDROP));
                    backdrop.setDither(true);
                }
                canvas.drawRect(0f, 0f, getWidth(), getHeight(), backdrop);
            }
            float top = top();
            float bottom = bottom();
            if (bottom <= top) {
                return;
            }
            int count = SpeedSliderScale.STOPS.length;
            float size = textSizeFor(getHeight());
            text.setTextSize(size);
            float x = trackX();
            track.setStrokeWidth(4 * density);
            canvas.drawLine(x, top, x, bottom, track);
            int shown = preview >= 0 ? preview : current;
            for (int i = 0; i < count; i++) {
                float y = SpeedSliderScale.positionOf(i, top, bottom);
                track.setStrokeWidth(2 * density);
                canvas.drawLine(x - 7 * density, y, x + 7 * density, y, track);
                boolean on = i == shown;
                text.setColor(on ? PINK : WHITE);
                text.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                canvas.drawText(SpeedSliderScale.label(SpeedSliderScale.STOPS[i]), x - LABEL_GAP_DP * density, y + size * 0.35f, text);
            }
            float thumbY = SpeedSliderScale.positionOf(shown, top, bottom);
            thumb.setColor(PINK);
            canvas.drawCircle(x, thumbY, THUMB_DP * density, thumb);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (!Generation.alive()) {
                return false;
            }
            int index = SpeedSliderScale.indexAt(event.getY(), top(), bottom());
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    // The list this view covers can be much wider than the slider (tablet 9.19: x 37..2750 of 2880): a touch left of the
                    // labels is a tap on the scrim that closes the panel, never a speed pick.
                    if (event.getX() < trackX() - TOUCH_WIDTH_DP * density) {
                        return false;
                    }
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    preview = index;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (index != preview) {
                        preview = index;
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    preview = -1;
                    if (index != current) {
                        current = index;
                        final float picked = SpeedSliderScale.STOPS[index];
                        // After this UP has been dispatched, like View.performClick: the listener closes the panel and removes this view, and
                        // doing that in the middle of the dispatch left the player unable to show its controls afterwards (2026-11-15, tablet).
                        post(() -> apply(adapter, picked));
                    }
                    setContentDescription("倍速 " + SpeedSliderScale.label(SpeedSliderScale.STOPS[current]));
                    invalidate();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    preview = -1;
                    invalidate();
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }
    }
}
