package dev.xpramt.bilisurfacebridge;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;
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

import java.lang.ref.WeakReference;

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

    private static final int PANEL = Color.argb(245, 24, 30, 37);
    private static final int CARD = Color.rgb(33, 40, 48);
    private static final int DIVIDER = Color.rgb(52, 62, 73);
    private static final int PRIMARY = Color.WHITE;
    private static final int ACCENT = Color.rgb(125, 190, 220);
    private static final int DESCRIPTION = Color.rgb(172, 180, 188);
    private static final int CHIP = Color.rgb(46, 55, 65);
    private static final int ON_ACCENT = Color.rgb(16, 20, 24);

    private static WeakReference<View> open;

    private final Activity activity;
    private final Actions actions;
    private final float density;

    private InPlayerSettingsPanel(Activity activity, Actions actions) {
        this.activity = activity;
        this.actions = actions;
        this.density = activity.getResources().getDisplayMetrics().density;
    }

    static void show(Activity activity, Actions actions) {
        View existing = open == null ? null : open.get();
        if (existing != null && existing.getParent() instanceof ViewGroup) {
            ((ViewGroup) existing.getParent()).removeView(existing);
        }
        InPlayerSettingsPanel panel = new InPlayerSettingsPanel(activity, actions);
        View root = panel.build();
        open = new WeakReference<View>(root);
        activity.addContentView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    // ---- layout -------------------------------------------------------------------------

    private View build() {
        final FrameLayout root = new FrameLayout(activity);
        // Above the player's controls, which sit in the same window.
        root.setElevation(dp(48));

        // Transparent: the video stays as it is, a tap on it just closes the panel.
        View scrim = new View(activity);
        scrim.setOnClickListener(v -> dismiss(root));
        root.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        int width = Math.min(dp(300), Math.round(metrics.widthPixels * 0.40f));
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable background = new GradientDrawable();
        background.setColor(PANEL);
        background.setCornerRadii(new float[]{dp(18), dp(18), 0, 0, 0, 0, dp(18), dp(18)});
        panel.setBackground(background);
        panel.setClickable(true);
        panel.setPadding(dp(16), dp(12), dp(16), dp(12));
        FrameLayout.LayoutParams panelParameters = new FrameLayout.LayoutParams(
                width, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        root.addView(panel, panelParameters);

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("bilibili Ext", 20, PRIMARY);
        title.setTypeface(null, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView done = text("完成", 16, ON_ACCENT);
        done.setTypeface(null, Typeface.BOLD);
        done.setGravity(Gravity.CENTER);
        done.setPadding(dp(22), dp(10), dp(22), dp(10));
        done.setBackground(rounded(ACCENT, 20));
        done.setOnClickListener(v -> dismiss(root));
        header.addView(done);
        panel.addView(header);

        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(4), 0, dp(12));
        scroll.addView(content);
        panel.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        smoothness(card(content, "流暢度"));
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
        switchRow(card, "高通解碼器輸出 fence", "降低解碼延遲；下一支影片生效。",
                ModulePreferences.loadQtiOutputFenceForHook(),
                ModulePreferences.KEY_QTI_OUTPUT_FENCE, null);
    }

    private void overlay(LinearLayout card) {
        switchRow(card, "fps 浮層", "畫面頂端單行：On | Avg | 1%Low | 渲染器。",
                ModulePreferences.loadPlaybackDebugOverlayForHook(),
                ModulePreferences.KEY_PLAYBACK_DEBUG_OVERLAY, enabled -> {
                    PlaybackDebugOverlay.show(activity, "");
                    PlaybackDebugOverlay.refreshAll();
                });
        switchRow(card, "影音格式", "例如 HEVC | Hi-Res 96KHz；下一支影片生效。",
                ModulePreferences.loadPlaybackStreamOverlayForHook(),
                ModulePreferences.KEY_PLAYBACK_STREAM_OVERLAY, null);
        marginRow(card);
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
                HostSettings.set(key, isChecked);
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
        for (int index = 0; index < modes.length; index++) {
            final ModulePreferences.RendererMode mode = modes[index];
            TextView chip = text(mode.label, 14, PRIMARY);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(4), dp(12), dp(4), dp(12));
            chip.setOnClickListener(v -> {
                HostSettings.set(ModulePreferences.KEY_VIDEO_DETAIL_RENDERER_MODE, mode.name());
                paintChips(chips, modes, mode);
                try {
                    actions.applyRenderer(mode);
                } catch (Throwable throwable) {
                    logFailure("renderer", throwable);
                }
            });
            chips[index] = chip;
            LinearLayout.LayoutParams parameters = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
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

    /** A slider that applies as it moves, with − and + for single steps. */
    private void marginRow(LinearLayout card) {
        startRow(card);
        final int min = ModulePreferences.MIN_OVERLAY_TOP_MARGIN_DP;
        final int max = ModulePreferences.MAX_OVERLAY_TOP_MARGIN_DP;
        final TextView label = text("", 17, PRIMARY);
        card.addView(label, matchWrap(4));
        final SeekBar slider = new SeekBar(activity);
        slider.setMax(max - min);
        slider.setPadding(dp(8), dp(10), dp(8), dp(10));
        final int start = ModulePreferences.loadOverlayTopMarginDpForHook();
        slider.setProgress(start - min);
        label.setText(marginText(start));

        final Runnable save = () -> HostSettings.write(ModulePreferences.KEY_OVERLAY_TOP_MARGIN_DP,
                slider.getProgress() + min);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int dp = progress + min;
                label.setText(marginText(dp));
                ModulePreferences.setHostOverride(
                        ModulePreferences.KEY_OVERLAY_TOP_MARGIN_DP, dp);
                PlaybackDebugOverlay.refreshAll();
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                save.run();
            }
        });

        LinearLayout line = new LinearLayout(activity);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.addView(stepButton("−", () -> {
            slider.setProgress(Math.max(0, slider.getProgress() - 1));
            save.run();
        }));
        line.addView(slider, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        line.addView(stepButton("＋", () -> {
            slider.setProgress(Math.min(slider.getMax(), slider.getProgress() + 1));
            save.run();
        }));
        card.addView(line, matchWrap(0));
        card.addView(text("只作用於 fps 浮層；拖動時即時改變位置。", 13, DESCRIPTION), matchWrap(2));
    }

    private TextView stepButton(String symbol, final Runnable action) {
        TextView button = text(symbol, 20, PRIMARY);
        button.setGravity(Gravity.CENTER);
        button.setBackground(rounded(CHIP, 12));
        button.setMinWidth(dp(46));
        button.setMinHeight(dp(46));
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private static String marginText(int dp) {
        return "fps 浮層距離頂端　" + dp + " dp";
    }

    // ---- building blocks ----------------------------------------------------------------

    private LinearLayout card(LinearLayout parent, String heading) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(CARD, 16));
        card.setPadding(dp(16), dp(12), dp(16), dp(14));
        LinearLayout.LayoutParams parameters = matchWrap(14);
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

    private static void dismiss(View root) {
        if (root.getParent() instanceof ViewGroup) {
            ((ViewGroup) root.getParent()).removeView(root);
        }
    }

    private static void logFailure(String what, Throwable throwable) {
        de.robv.android.xposed.XposedBridge.log("BiliSurfaceBridge: in-player settings " + what
                + " failed: " + throwable.getClass().getSimpleName() + " "
                + throwable.getMessage());
    }

    static void toast(Activity activity, String message) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }
}
