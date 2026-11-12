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

        TextView title = text("Bili Extended", 25, PRIMARY);
        title.setTypeface(null, Typeface.BOLD);
        content.addView(title);
        add(content, text("高幀率播放修正與資訊浮層", 14, ACCENT), 0, 5);
        add(content, text("作用域只勾選 Bilibili（tv.danmaku.bili）。更新模組後請完整關閉再開啟 Bilibili。"
                + "本模組不讀取帳號、Cookie、影片網址或授權資料。", 14, DESCRIPTION), 0, 10);

        // ---- 設定 ----
        LinearLayout settings = card(content, "設定在哪裡");
        startItem(settings);
        add(settings, text("所有設定都在 Bilibili 裡調整", 16, PRIMARY), 0, 12);
        add(settings, text("播放影片時打開播放器選單（全螢幕右側或半螢幕底部）的「Bili Extended」。"
                + "設定只存在 Bilibili 自己的資料夾，本 App 不保存任何設定；清除 Bilibili 的資料會一併清除。", 13, DESCRIPTION), 0, 2);

        // ---- 螢幕資訊（診斷） ----
        LinearLayout screen = card(content, "螢幕資訊");
        try {
            String report = ScreenPhysicalSize.describe(getWindowManager().getDefaultDisplay());
            android.util.Log.i("BiliExtended", "screen info: " + report);
            startItem(screen);
            add(screen, text(report, 14, PRIMARY), 0, 12);
            add(screen, text("螢幕的物理解析度可能比 UI 高（例如 Sony Xperia XZ Premium 的 4K 面板跑 1080p UI）。"
                    + "這是診斷資訊，不影響其他設定。", 13, DESCRIPTION), 0, 6);
            if (ScreenPhysicalSize.hasHiddenResolution(getWindowManager().getDefaultDisplay())) {
                TextView test = text("4K 輸出測試（全螢幕，點一下關閉）", 16, Color.rgb(16, 20, 24));
                test.setGravity(Gravity.CENTER);
                test.setPadding(dp(16), dp(12), dp(16), dp(12));
                android.graphics.drawable.GradientDrawable background =
                        new android.graphics.drawable.GradientDrawable();
                background.setColor(ACCENT);
                background.setCornerRadius(dp(12));
                test.setBackground(background);
                test.setOnClickListener(view -> startActivity(
                        new android.content.Intent(MainActivity.this, FourKTestActivity.class)));
                add(screen, test, 0, 12);
            }
        } catch (Throwable throwable) {
            startItem(screen);
            add(screen, text("無法讀取：" + throwable.getClass().getSimpleName(), 14, PRIMARY), 0, 12);
        }

        setContentView(scroll);
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
