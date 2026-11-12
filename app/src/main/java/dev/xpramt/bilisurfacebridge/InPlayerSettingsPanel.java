package dev.xpramt.bilisurfacebridge;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;


/**
 * The module's settings inside Bilibili's player: a panel on the right edge, drawn here instead of
 * with the player's own row components so the controls can be the ones that are easy to use
 * (real switches, a slider with fine steps, large touch targets). It is added over the player
 * like the overlays are, and closed by the button or a tap outside.
 */
final class InPlayerSettingsPanel {
    /** What only the player's own services can do. */
    interface Actions {
        void applyRenderer(ModulePreferences.RendererMode mode);
    }

    private static final int CARD = Color.rgb(33, 40, 48);
    private static final int DIVIDER = Color.rgb(52, 62, 73);
    private static final int PRIMARY = Color.WHITE;
    private static final int ACCENT = Color.rgb(125, 190, 220);
    private static final int DESCRIPTION = Color.rgb(172, 180, 188);
    private static final int CHIP = Color.rgb(46, 55, 65);
    private static final int ON_ACCENT = Color.rgb(16, 20, 24);

    // The panel that is showing, so opening another one closes it properly first.
    private static InPlayerSettingsPanel current;
    private static View currentRoot;

    private final Activity activity;
    private final Actions actions;
    private final float density;
    private View originalContent;
    private boolean fillBounds;
    private boolean flush;
    private Object backCallback;

    private InPlayerSettingsPanel(Activity activity, Actions actions) {
        this.activity = activity;
        this.actions = actions;
        this.density = activity.getResources().getDisplayMetrics().density;
    }

    static void show(Activity activity, Rect bounds, View originalContent, Actions actions) {
        show(activity, bounds, false, originalContent, actions);
    }

    /**
     * @param fillBounds true to also take the height and top of {@code bounds}: the bottom-sheet
     *                   menu's panel sits exactly where that menu was; the right-hand panel only
     *                   copies the width and left edge.
     */
    static void show(Activity activity, Rect bounds, boolean fillBounds, View originalContent,
                     Actions actions) {
        show(activity, bounds, fillBounds, false, originalContent, actions);
    }

    /** @param flush no side margin: the cards reach the left and right edge of {@code bounds}. */
    static void show(Activity activity, Rect bounds, boolean fillBounds, boolean flush,
                     View originalContent, Actions actions) {
        if (current != null && currentRoot != null) {
            current.close(currentRoot);
        }
        InPlayerSettingsPanel panel = new InPlayerSettingsPanel(activity, actions);
        panel.originalContent = originalContent;
        panel.fillBounds = fillBounds;
        panel.flush = flush;
        View root = panel.build(bounds);
        if (originalContent != null) {
            // Our cards do not fill the panel; the original rows must not show through.
            originalContent.setVisibility(View.INVISIBLE);
        }
        current = panel;
        currentRoot = root;
        activity.addContentView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    // ---- layout -------------------------------------------------------------------------

    private View build(final Rect bounds) {
        final FrameLayout root = new FrameLayout(activity);
        // Above the player's controls, which sit in the same window.
        root.setElevation(dp(48));
        listenForBack(root);

        // Transparent: the video stays as it is, a tap on it just closes the panel.
        View scrim = new View(activity);
        scrim.setOnClickListener(v -> close(root));
        root.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        // Wide screens (tablet, phone in landscape): a side panel of 300 dp at most 40% of the
        // width. A phone in portrait would leave only ~140 dp, so there it takes nearly the
        // whole width.
        int width = metrics.widthPixels < dp(600)
                ? Math.min(metrics.widthPixels - dp(24), dp(420))
                : Math.min(dp(300), Math.round(metrics.widthPixels * 0.40f));
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        // No background of its own: only the cards are drawn, like the original panel. Still
        // clickable so a tap between the cards does not count as a tap outside.
        panel.setClickable(true);
        panel.setPadding(flush ? 0 : dp(16), 0, flush ? 0 : dp(16), 0);
        final FrameLayout.LayoutParams panelParameters = new FrameLayout.LayoutParams(
                width, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        root.addView(panel, panelParameters);
        if (bounds != null) {
            // Same width and position as the original panel (without its shadow); the root's own
            // offset on screen is only known once it is laid out.
            root.post(new Runnable() {
                @Override
                public void run() {
                    int[] origin = new int[2];
                    root.getLocationOnScreen(origin);
                    panelParameters.gravity = Gravity.START;
                    panelParameters.width = bounds.width();
                    panelParameters.leftMargin = bounds.left - origin[0];
                    if (fillBounds) {
                        panelParameters.gravity = Gravity.START | Gravity.TOP;
                        panelParameters.height = bounds.height();
                        panelParameters.topMargin = bounds.top - origin[1];
                    }
                    panel.setLayoutParams(panelParameters);
                }
            });
        }

        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, 0);
        scroll.addView(content);
        panel.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        scroll.post(() -> scroll.scrollTo(0, 0));

        smoothness(card(content, "流暢度"));
        speed(card(content, "倍速"));
        overlay(card(content, "資訊浮層"));
        audio(card(content, "音訊"));
        behaviour(card(content, "頁面行為"));
        return root;
    }

    private void smoothness(LinearLayout card) {
        renderer(card);
        switchRow(card, "Media3 Vsync", "讓影片幀對齊螢幕刷新；切換後約 1～2 秒生效。",
                ModulePreferences.loadMedia3FrameSyncForHook(),
                ModulePreferences.KEY_MEDIA3_FRAME_SYNC, null);
        scheduleSliders(card);
        switchRow(card, "HDR 影片自動用 Surface", "播 HDR 影片時改用直連 Surface，才會觸發系統 HDR；其他影片維持你選的渲染器。",
                ModulePreferences.loadHdrAutoSurfaceForHook(),
                ModulePreferences.KEY_HDR_AUTO_SURFACE,
                enabled -> HdrVideoRenderer.reevaluate());
        if (ScreenPhysicalSize.hasHiddenResolution(activity.getWindowManager().getDefaultDisplay())) {
            switchRow(card, "原生解析度輸出", "影片以螢幕物理解析度輸出，UI 維持原尺寸。",
                    ModulePreferences.loadNativeResolutionOutputForHook(),
                    ModulePreferences.KEY_NATIVE_RESOLUTION_OUTPUT,
                    enabled -> NativeResolutionOutput.refreshAll());
        }
    }


    /** Live Media3 schedule knobs: ready window, late-drop threshold (the handoff gate is always on; a too-early frame is re-checked at the exact moment it becomes ready; no interval to set). */
    private void scheduleSliders(LinearLayout card) {
        sliderRow(card, "距離預定上屏的門檻", "ms",
                ModulePreferences.MIN_MEDIA3_READY_WINDOW_MS,
                ModulePreferences.MAX_MEDIA3_READY_WINDOW_MS,
                ModulePreferences.loadMedia3ReadyWindowMsForHook(),
                liveSchedule(ModulePreferences.KEY_MEDIA3_READY_WINDOW_MS,
                        NativeRefreshCap::setMedia3ReadyWindowMs),
                save(ModulePreferences.KEY_MEDIA3_READY_WINDOW_MS),
                "距離預定上屏還超過這個時間就不交，等下次再問。");
        sliderRow(card, "比聲音晚到多少毫秒允許丟幀", "ms",
                ModulePreferences.MIN_MEDIA3_LATE_DROP_MS,
                ModulePreferences.MAX_MEDIA3_LATE_DROP_MS,
                ModulePreferences.loadMedia3LateDropMsForHook(),
                liveSchedule(ModulePreferences.KEY_MEDIA3_LATE_DROP_MS,
                        NativeRefreshCap::setMedia3LateDropMs),
                save(ModulePreferences.KEY_MEDIA3_LATE_DROP_MS),
                "比聲音晚超過這個時間才允許丟掉這張，讓畫面追上聲音。");
    }

    /** Applies while dragging: host override + native atomic, no disk write yet. */
    private IntAction liveSchedule(final String key, final IntAction nativeApply) {
        return value -> {
            ModulePreferences.set(key, value);
            nativeApply.run(value);
        };
    }

    private void overlay(LinearLayout card) {
        switchRow(card, "fps 浮層", "畫面頂端：On | Avg | 1%Low | 渲染器。",
                ModulePreferences.loadPlaybackDebugOverlayForHook(),
                ModulePreferences.KEY_PLAYBACK_DEBUG_OVERLAY, enabled -> {
                    PlaybackDebugOverlay.show(activity, "");
                    PlaybackDebugOverlay.refreshAll();
                });
        switchRow(card, "影音格式", "例如 HEVC | Hi-Res 96KHz；只管顯示或隱藏，兩項都開時顯示在同一行。",
                ModulePreferences.loadPlaybackStreamOverlayForHook(),
                ModulePreferences.KEY_PLAYBACK_STREAM_OVERLAY, enabled -> PlaybackDebugOverlay.refreshAll());
        positionRows(card);
    }

    private void audio(LinearLayout card) {
        switchRow(card, "自動挑選最佳音質", "點播挑頻寬最高的音軌；下一支影片生效。",
                ModulePreferences.loadAutoSelectBestAudioForHook(),
                ModulePreferences.KEY_AUTO_SELECT_BEST_AUDIO, null);
    }

    private void behaviour(LinearLayout card) {
        switchRow(card, "直式 Story 改用一般影片頁", "把 Story 連結改走一般影片頁；立即生效。",
                ModulePreferences.loadReplaceStoryVideoForHook(),
                ModulePreferences.KEY_REPLACE_STORY_VIDEO, null);
    }

    private void speed(LinearLayout card) {
        switchRow(card, "高倍速（最高 10x）",
                "解除播放核心的倍速上限（原本 60 fps 以上最高 2x、其餘 3x），並在倍速選單最前面加入 10x～2.5x；約 1 秒後生效，倍速選單下次打開時更新。高幀率或 4K 的高倍速可能解碼不及。",
                ModulePreferences.loadHighSpeedForHook(),
                ModulePreferences.KEY_HIGH_SPEED, null);
        float longPress = ModulePreferences.loadLongPressSpeedOverrideForHook();
        sliderRow(card, "長按加速倍率", "x", Math.round(SpeedSettings.MIN_RATE * 2), Math.round(SpeedSettings.MAX_RATE * 2),
                Math.round((Float.isNaN(longPress) ? SpeedSettings.DEFAULT_LONG_PRESS_RATE : longPress) * 2), 2,
                value -> { },
                value -> ModulePreferences.set(ModulePreferences.KEY_LONG_PRESS_SPEED, value / 2.0f),
                "沒調過時沿用 Bilibili 的 3x；調整後下次長按生效。超過 3x 需開「高倍速」。");
    }

    // ---- rows ---------------------------------------------------------------------------

    private interface OnChanged {
        void run(boolean enabled);
    }

    private void switchRow(LinearLayout card, String title, String description, boolean checked,
                           final String key, final OnChanged after) {
        startRow(card);
        Switch row = new Switch(activity);
        row.setText(title);
        row.setTextSize(17);
        row.setTextColor(PRIMARY);
        row.setMinHeight(dp(52));
        row.setChecked(checked);
        row.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean isChecked) {
                ModulePreferences.set(key, isChecked);
                if (after != null) {
                    try {
                        after.run(isChecked);
                    } catch (Throwable throwable) {
                        logFailure("switch " + key, throwable);
                    }
                }
            }
        });
        card.addView(row, matchWrap(0));
        card.addView(text(description, 13, DESCRIPTION), matchWrap(0));
    }

    /** Three large buttons: tapping one switches the video layer right away. */
    private void renderer(LinearLayout card) {
        startRow(card);
        card.addView(text("影片渲染器", 17, PRIMARY), matchWrap(4));
        final ModulePreferences.RendererMode[] modes = ModulePreferences.RendererMode.values();
        final TextView[] chips = new TextView[modes.length];
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        // Baseline alignment would shift the two-line chip against the one-line ones.
        row.setBaselineAligned(false);
        for (int index = 0; index < modes.length; index++) {
            final ModulePreferences.RendererMode mode = modes[index];
            TextView chip = text(mode.label, 14, PRIMARY);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(4), 0, dp(4), 0);
            chip.setOnClickListener(v -> {
                ModulePreferences.set(ModulePreferences.KEY_VIDEO_DETAIL_RENDERER_MODE, mode.name());
                paintChips(chips, modes, mode);
                try {
                    actions.applyRenderer(mode);
                } catch (Throwable throwable) {
                    logFailure("renderer", throwable);
                }
            });
            chips[index] = chip;
            // One fixed height for all three, enough for the two-line "Surface external".
            LinearLayout.LayoutParams parameters = new LinearLayout.LayoutParams(0, dp(56), 1f);
            parameters.setMargins(index == 0 ? 0 : dp(6), dp(6), 0, 0);
            row.addView(chip, parameters);
        }
        paintChips(chips, modes, ModulePreferences.loadVideoDetailRendererModeForHook());
        card.addView(row, matchWrap(0));
        card.addView(text("點選後立即切換，畫面會短暫重建。", 13, DESCRIPTION), matchWrap(6));
    }

    private void paintChips(TextView[] chips, ModulePreferences.RendererMode[] modes,
                            ModulePreferences.RendererMode selected) {
        for (int index = 0; index < chips.length; index++) {
            boolean on = modes[index] == selected;
            chips[index].setBackground(rounded(on ? ACCENT : CHIP, 12));
            chips[index].setTextColor(on ? ON_ACCENT : PRIMARY);
            chips[index].setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    private interface IntAction {
        void run(int value);
    }

    private boolean landscape() {
        return activity.getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
    }

    /** X and Y of the overlay, each up to the real screen size; kept per orientation. */
    private void positionRows(LinearLayout card) {
        final boolean landscape = landscape();
        DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        int widthDp = Math.round(metrics.widthPixels / metrics.density);
        int heightDp = Math.round(metrics.heightPixels / metrics.density);
        final String xKey = ModulePreferences.overlayXKey(landscape);
        final String yKey = ModulePreferences.overlayYKey(landscape);
        String orientation = landscape ? "橫向" : "直向";
        sliderRow(card, "水平位置 X", "dp", 0, widthDp,
                PlaybackDebugOverlay.currentXDp(activity, landscape, widthDp / 3),
                live(xKey), save(xKey),
                "從左邊算起，最大是螢幕寬度；" + orientation + "與另一個方向分開儲存。");
        sliderRow(card, "垂直位置 Y", "dp", 0, heightDp,
                ModulePreferences.loadOverlayYDpForHook(landscape),
                live(yKey), save(yKey), "從上緣算起，最大是螢幕高度。");
        sliderRow(card, "字體大小", "sp", ModulePreferences.MIN_OVERLAY_TEXT_SIZE_SP,
                ModulePreferences.MAX_OVERLAY_TEXT_SIZE_SP,
                ModulePreferences.loadOverlayTextSizeSpForHook(),
                live(ModulePreferences.KEY_OVERLAY_TEXT_SIZE_SP),
                save(ModulePreferences.KEY_OVERLAY_TEXT_SIZE_SP), null);
    }

    /** Applies while dragging, without a write per step. */
    private IntAction live(final String key) {
        return value -> {
            ModulePreferences.set(key, value);
            PlaybackDebugOverlay.refreshAll();
        };
    }

    /** Stored when the finger lifts or a step button is pressed. */
    private IntAction save(final String key) {
        return value -> ModulePreferences.set(key, value);
    }

    /** A slider with a label, small − and + for single steps, and an optional note. */
    private void sliderRow(LinearLayout card, final String name, final String unit, final int min,
                           final int max, int initial, final IntAction live, final IntAction save,
                           String note) {
        sliderRow(card, name, unit, min, max, initial, 1, live, save, note);
    }

    /** divisor 2: the slider counts half units (the label shows progress / 2). */
    private void sliderRow(LinearLayout card, final String name, final String unit, final int min,
                           final int max, int initial, final int divisor, final IntAction live, final IntAction save,
                           String note) {
        startRow(card);
        final TextView label = text("", 17, PRIMARY);
        card.addView(label, matchWrap(4));
        final SeekBar slider = new SeekBar(activity);
        slider.setMax(Math.max(1, max - min));
        slider.setPadding(dp(6), dp(8), dp(6), dp(8));
        final int start = Math.max(min, Math.min(max, initial));
        slider.setProgress(start - min);
        label.setText(valueText(name, start, divisor, unit));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                label.setText(valueText(name, progress + min, divisor, unit));
                live.run(progress + min);
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                save.run(bar.getProgress() + min);
            }
        });

        LinearLayout line = new LinearLayout(activity);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setBaselineAligned(false);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.addView(stepButton("−", () -> {
            slider.setProgress(Math.max(0, slider.getProgress() - 1));
            save.run(slider.getProgress() + min);
        }));
        line.addView(slider, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        line.addView(stepButton("+", () -> {
            slider.setProgress(Math.min(slider.getMax(), slider.getProgress() + 1));
            save.run(slider.getProgress() + min);
        }));
        card.addView(line, matchWrap(0));
        if (note != null) {
            card.addView(text(note, 13, DESCRIPTION), matchWrap(2));
        }
    }

    private static String valueText(String name, int value, int divisor, String unit) {
        return name + "　" + (divisor == 1 ? String.valueOf(value)
                : String.format(java.util.Locale.ROOT, "%.1f", value / (double) divisor)) + " " + unit;
    }

    /** A small round button. */
    private TextView stepButton(String symbol, final Runnable action) {
        TextView button = text(symbol, 18, PRIMARY);
        button.setGravity(Gravity.CENTER);
        button.setIncludeFontPadding(false);
        button.setBackground(rounded(CHIP, 17));
        button.setWidth(dp(34));
        button.setHeight(dp(34));
        button.setOnClickListener(v -> action.run());
        return button;
    }

    // ---- building blocks ----------------------------------------------------------------

    private LinearLayout card(LinearLayout parent, String heading) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(CARD, 16));
        card.setPadding(dp(16), dp(12), dp(16), dp(14));
        LinearLayout.LayoutParams parameters = matchWrap(0);
        parent.addView(card, parameters);
        TextView title = text(heading, 15, ACCENT);
        title.setTypeface(null, Typeface.BOLD);
        card.addView(title);
        return card;
    }

    /** A rule between rows (not before the first one, which follows the heading). */
    private void startRow(LinearLayout card) {
        View line = new View(activity);
        line.setBackgroundColor(DIVIDER);
        LinearLayout.LayoutParams parameters = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        parameters.setMargins(0, dp(10), 0, dp(6));
        card.addView(line, parameters);
    }

    private TextView text(String value, int sizeSp, int color) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setLineSpacing(dp(2), 1.0f);
        return view;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap(int topDp) {
        LinearLayout.LayoutParams parameters = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        parameters.topMargin = dp(topDp);
        return parameters;
    }

    private int dp(int value) {
        return Math.round(value * density);
    }

    /**
     * Back closes the panel. The root holds the focus (its children cannot take it, or the key
     * would go to them and not reach us); on Android 13+ the system's back callback is
     * registered as well, for the case where the app receives back that way.
     */
    private void listenForBack(final FrameLayout root) {
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);
        root.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        root.setOnKeyListener((view, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_BACK) {
                return false;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                close(root);
            }
            return true;
        });
        root.post(root::requestFocus);
        if (Build.VERSION.SDK_INT >= 33) {
            registerBackCallback(root);
        }
    }

    private void registerBackCallback(final View root) {
        android.window.OnBackInvokedCallback callback = () -> close(root);
        backCallback = callback;
        activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback);
    }

    /** Hot reload, old generation (main thread). */
    static void closeForHotReload() {
        InPlayerSettingsPanel panel = current;
        View root = currentRoot;
        if (panel != null && root != null) {
            panel.close(root);
        }
    }

    private void close(View root) {
        if (backCallback != null && Build.VERSION.SDK_INT >= 33) {
            activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                    (android.window.OnBackInvokedCallback) backCallback);
            backCallback = null;
        }
        if (root.getParent() instanceof ViewGroup) {
            ((ViewGroup) root.getParent()).removeView(root);
        }
        if (originalContent != null) {
            originalContent.setVisibility(View.VISIBLE);
        }
        if (current == this) {
            current = null;
            currentRoot = null;
        }
    }

    private static void logFailure(String what, Throwable throwable) {
        Xp.log("BiliExtended: in-player settings " + what
                + " failed: " + throwable.getClass().getSimpleName() + " "
                + throwable.getMessage());
    }

    static void toast(Activity activity, String message) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }
}
