package dev.xpramt.bilisurfacebridge;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Settings screen: four cards (smoothness, info overlay, audio, page behaviour), each with a
 * heading and one-line descriptions. LSPosed scope remains the activation authority.
 */
public final class MainActivity extends Activity {
    private static final int BACKGROUND = Color.rgb(16, 20, 24);
    private static final int CARD = Color.rgb(25, 31, 38);
    private static final int CARD_STROKE = Color.rgb(48, 59, 70);
    private static final int DIVIDER = Color.rgb(44, 54, 64);
    private static final int PRIMARY = Color.WHITE;
    private static final int ACCENT = Color.rgb(125, 190, 220);
    private static final int DESCRIPTION = Color.rgb(170, 178, 186);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String automationRenderer = getIntent().getStringExtra("automation_profile");
        Boolean automationProbe = getIntent().hasExtra("render_cadence_probe")
                ? Boolean.valueOf(getIntent().getBooleanExtra("render_cadence_probe", true))
                : null;
        Boolean automationMedia3FrameSync = getIntent().hasExtra("media3_frame_sync")
                ? Boolean.valueOf(getIntent().getBooleanExtra("media3_frame_sync", false))
                : null;
        Boolean automationFrameQueueWaitRoundUp = getIntent().hasExtra("frame_queue_wait_round_up")
                ? Boolean.valueOf(getIntent().getBooleanExtra("frame_queue_wait_round_up", false))
                : null;
        Boolean automationBlockFrameDrop = getIntent().hasExtra("block_ijk_frame_drop")
                ? Boolean.valueOf(getIntent().getBooleanExtra("block_ijk_frame_drop", true))
                : null;
        Boolean automationBlockComputeTargetDelay =
                getIntent().hasExtra("block_compute_target_delay")
                ? Boolean.valueOf(getIntent().getBooleanExtra("block_compute_target_delay", true))
                : null;
        if (getIntent().hasExtra("replace_story_video")) {
            ModulePreferences.setReplaceStoryVideoEnabled(
                    this, getIntent().getBooleanExtra("replace_story_video", false));
        }
        Window window = getWindow();
        window.setStatusBarColor(BACKGROUND);
        window.setNavigationBarColor(BACKGROUND);
        window.getDecorView().setSystemUiVisibility(0);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(24), dp(20), dp(32));
        content.setBackgroundColor(BACKGROUND);
        scroll.addView(content);

        final int horizontalPadding = dp(20);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            content.setPadding(horizontalPadding,
                    insets.getSystemWindowInsetTop() + dp(20),
                    horizontalPadding,
                    insets.getSystemWindowInsetBottom() + dp(28));
            return insets.consumeSystemWindowInsets();
        });

        TextView title = text("Bili Surface Bridge", 25, PRIMARY);
        title.setTypeface(null, Typeface.BOLD);
        content.addView(title);
        add(content, text("高幀率播放修正與資訊浮層", 14, ACCENT), 0, 5);
        add(content, text("作用域只勾選 Bilibili（tv.danmaku.bili）。變更後請完整關閉再開啟 Bilibili。"
                + "本模組不讀取帳號、Cookie、影片網址或授權資料。", 14, DESCRIPTION), 0, 10);

        // ---- 流暢度 ----
        LinearLayout smoothness = card(content, "流暢度");

        startItem(smoothness);
        add(smoothness, text("影片渲染器", 16, PRIMARY), 0, 12);
        add(smoothness, text("只套用一般影片詳情頁。建議 Surface external：保留縮放、旋轉與截圖，"
                + "並能帶時間戳給 Media3 Vsync 使用。", 13, DESCRIPTION), 0, 2);
        Spinner rendererSpinner = rendererSpinner(automationRenderer != null);
        add(smoothness, rendererSpinner, 0, 8);
        // Apply automation after the Spinner has been restored and its listener attached. Android
        // may restore a stale selection during onCreate; doing the final write here prevents that
        // UI restoration from silently changing the profile immediately before the runner reads it.
        if (automationRenderer != null) {
            ModulePreferences.applyAutomationProfile(this,
                    automationRenderer, automationProbe,
                    automationMedia3FrameSync,
                    automationFrameQueueWaitRoundUp, automationBlockFrameDrop,
                    automationBlockComputeTargetDelay);
            ModulePreferences.RendererMode appliedMode =
                    ModulePreferences.getVideoDetailRendererMode(this);
            rendererSpinner.setSelection(appliedMode.ordinal(), false);
        }

        switchRow(smoothness, "Media3 Vsync", "讓影片幀對齊螢幕刷新，減少高幀率微卡頓。",
                ModulePreferences.isMedia3FrameSyncEnabled(this),
                (button, checked) ->
                        ModulePreferences.setMedia3FrameSyncEnabled(MainActivity.this, checked));
        switchRow(smoothness, "高通解碼器輸出 fence", "讓高通解碼器提早交出幀以降低延遲；解碼器支援時才生效。",
                ModulePreferences.isQtiOutputFenceEnabled(this),
                (button, checked) ->
                        ModulePreferences.setQtiOutputFenceEnabled(MainActivity.this, checked));

        // ---- 資訊浮層 ----
        LinearLayout overlay = card(content, "資訊浮層");

        final TextView marginLabel = text("", 16, PRIMARY);
        final TextView marginNote = text("0～100 dp，放開即套用；只作用於 fps 浮層。", 13, DESCRIPTION);
        final SeekBar marginSlider = new SeekBar(this);
        // SeekBar has no minimum before API 26: progress 0 is MIN dp.
        final int minMargin = ModulePreferences.MIN_OVERLAY_TOP_MARGIN_DP;
        marginSlider.setMax(ModulePreferences.MAX_OVERLAY_TOP_MARGIN_DP - minMargin);
        marginSlider.setProgress(ModulePreferences.getOverlayTopMarginDp(this) - minMargin);
        marginLabel.setText(marginText(marginSlider.getProgress() + minMargin));
        marginSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                marginLabel.setText(marginText(progress + minMargin));
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                ModulePreferences.setOverlayTopMarginDp(
                        MainActivity.this, bar.getProgress() + minMargin);
            }
        });

        boolean fpsOverlayOn = ModulePreferences.isPlaybackDebugOverlayEnabled(this);
        switchRow(overlay, "fps 浮層", "畫面頂端單行：On | Avg 120fps | 1%Low 83fps | Surface Ext。",
                fpsOverlayOn, (button, checked) -> {
                    ModulePreferences.setPlaybackDebugOverlayEnabled(MainActivity.this, checked);
                    setMarginControlsEnabled(marginLabel, marginNote, marginSlider, checked);
                });
        switchRow(overlay, "影音格式", "例如 HEVC | Hi-Res 96KHz；與 fps 浮層同開時接在同一行後面，"
                + "單獨開則獨立顯示一行。",
                ModulePreferences.isPlaybackStreamOverlayEnabled(this),
                (button, checked) ->
                        ModulePreferences.setPlaybackStreamOverlayEnabled(
                                MainActivity.this, checked));

        startItem(overlay);
        add(overlay, marginLabel, 0, 12);
        add(overlay, marginNote, 0, 2);
        add(overlay, marginSlider, 0, 6);
        setMarginControlsEnabled(marginLabel, marginNote, marginSlider, fpsOverlayOn);

        // ---- 音訊 ----
        LinearLayout audio = card(content, "音訊");
        switchRow(audio, "自動挑選最佳音質", "點播時在獨立 DASH 音軌中挑頻寬最高者；關閉則保留 Bilibili 預設。",
                ModulePreferences.isAutoSelectBestAudioEnabled(this),
                (button, checked) ->
                        ModulePreferences.setAutoSelectBestAudioEnabled(
                                MainActivity.this, checked));

        // ---- 頁面行為 ----
        LinearLayout behaviour = card(content, "頁面行為");
        switchRow(behaviour, "直式 Story 影片改用一般影片頁",
                "把 Story 連結改走一般影片頁；不封鎖滑動手勢。",
                ModulePreferences.isReplaceStoryVideoEnabled(this),
                (button, checked) ->
                        ModulePreferences.setReplaceStoryVideoEnabled(
                                MainActivity.this, checked));

        setContentView(scroll);
    }

    private static String marginText(int dp) {
        return "距離螢幕頂端　" + dp + " dp";
    }

    private static void setMarginControlsEnabled(TextView label, TextView note, SeekBar slider,
                                                 boolean enabled) {
        slider.setEnabled(enabled);
        float alpha = enabled ? 1.0f : 0.4f;
        label.setAlpha(alpha);
        note.setAlpha(alpha);
        slider.setAlpha(alpha);
    }

    private Spinner rendererSpinner(final boolean automationProfileActive) {
        Spinner spinner = new Spinner(this, Spinner.MODE_DROPDOWN);
        ModulePreferences.RendererMode[] modes = ModulePreferences.RendererMode.values();
        String[] labels = new String[modes.length];
        for (int index = 0; index < modes.length; index++) {
            labels[index] = modes[index].label;
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item, labels) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                return rendererRow(super.getView(position, convertView, parent), false);
            }

            @Override
            public View getDropDownView(int position, View convertView,
                                        android.view.ViewGroup parent) {
                return rendererRow(super.getDropDownView(position, convertView, parent), true);
            }
        };
        spinner.setAdapter(adapter);
        spinner.setSelection(ModulePreferences.getVideoDetailRendererMode(this).ordinal(), false);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!automationProfileActive) {
                    ModulePreferences.setVideoDetailRendererMode(
                            MainActivity.this, ModulePreferences.RendererMode.values()[position]);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // A renderer is always selected.
            }
        });
        return spinner;
    }

    private View rendererRow(View row, boolean dropdown) {
        if (row instanceof TextView) {
            TextView text = (TextView) row;
            text.setTextColor(PRIMARY);
            text.setTextSize(16);
            text.setPadding(dp(12), dp(dropdown ? 12 : 8), dp(12), dp(dropdown ? 12 : 8));
            text.setBackgroundColor(dropdown ? Color.rgb(35, 42, 49) : Color.rgb(32, 39, 46));
        }
        return row;
    }

    /** A rounded card with a bold heading and a rule under it; items are added below. */
    private LinearLayout card(LinearLayout parent, String heading) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(16));
        GradientDrawable background = new GradientDrawable();
        background.setColor(CARD);
        background.setCornerRadius(dp(14));
        background.setStroke(dp(1), CARD_STROKE);
        card.setBackground(background);
        add(parent, card, 0, 18);
        TextView title = text(heading, 18, ACCENT);
        title.setTypeface(null, Typeface.BOLD);
        card.addView(title);
        addDivider(card, 10);
        return card;
    }

    /** Heading and its rule are the first two children; later items get a rule between them. */
    private void startItem(LinearLayout card) {
        if (card.getChildCount() > 2) {
            addDivider(card, 14);
        }
    }

    private void addDivider(LinearLayout parent, int top) {
        View line = new View(this);
        line.setBackgroundColor(DIVIDER);
        LinearLayout.LayoutParams parameters = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        parameters.setMargins(0, dp(top), 0, 0);
        parent.addView(line, parameters);
    }

    private Switch switchRow(LinearLayout card, String title, String description,
                             boolean checked, CompoundButton.OnCheckedChangeListener listener) {
        startItem(card);
        Switch row = new Switch(this);
        row.setText(title);
        row.setTextSize(16);
        row.setTextColor(PRIMARY);
        row.setChecked(checked);
        row.setOnCheckedChangeListener(listener);
        add(card, row, 0, 12);
        add(card, text(description, 13, DESCRIPTION), 0, 2);
        return row;
    }

    private TextView text(String value, int sizeSp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setGravity(Gravity.START);
        view.setLineSpacing(dp(3), 1.0f);
        return view;
    }

    private void add(LinearLayout parent, View child, int left, int top) {
        LinearLayout.LayoutParams parameters = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        parameters.setMargins(dp(left), dp(top), 0, 0);
        parent.addView(child, parameters);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
