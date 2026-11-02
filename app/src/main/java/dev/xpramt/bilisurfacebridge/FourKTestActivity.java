package dev.xpramt.bilisurfacebridge;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;

/**
 * Test for panels whose physical resolution is higher than the UI (Sony Xperia XZ Premium and
 * other 4K phones): a SurfaceView whose buffer is set to the full physical size, filled with a
 * one-pixel line pattern. If the panel shows it natively, the lines are crisp and the layer goes
 * to the display hardware 1:1 (source = destination in SurfaceFlinger's HWC layer dump); if the
 * buffer is scaled, the pattern turns into moire or grey. Tap to close.
 */
public final class FourKTestActivity extends Activity implements SurfaceHolder.Callback {
    private static final String TAG = "BiliSurfaceBridge";

    private SurfaceView surfaceView;
    private int bufferWidth;
    private int bufferHeight;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        surfaceView = new SurfaceView(this);
        surfaceView.getHolder().addCallback(this);
        surfaceView.setOnClickListener(view -> finish());
        setContentView(surfaceView);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Display display = getWindowManager().getDefaultDisplay();
        ScreenPhysicalSize.Size physical = ScreenPhysicalSize.physical(display);
        Point logical = new Point();
        display.getRealSize(logical);
        // The buffer follows the orientation the screen is in now.
        boolean portrait = logical.y >= logical.x;
        bufferWidth = portrait ? physical.shortEdge : physical.longEdge;
        bufferHeight = portrait ? physical.longEdge : physical.shortEdge;
        Log.i(TAG, "4K test: logical " + logical.x + "x" + logical.y + ", asking for a "
                + bufferWidth + "x" + bufferHeight + " buffer");
        holder.setFixedSize(bufferWidth, bufferHeight);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.i(TAG, "4K test: surface is " + width + "x" + height + ", view is "
                + surfaceView.getWidth() + "x" + surfaceView.getHeight());
        Canvas canvas = holder.lockCanvas();
        if (canvas == null) {
            return;
        }
        try {
            int w = canvas.getWidth();
            int h = canvas.getHeight();
            canvas.drawColor(Color.BLACK);
            Paint white = new Paint();
            white.setColor(Color.WHITE);
            white.setAntiAlias(false);
            // Top band: one pixel on, one pixel off (only a 1:1 output keeps it crisp; to the eye
            // it is just fine grey at this pixel density).
            for (int x = 0; x < w; x += 2) {
                canvas.drawRect(x, 0, x + 1, h / 12f, white);
            }

            // Below it, the same small text twice. Left: drawn at the full buffer resolution.
            // Right: drawn at half the resolution and then enlarged 2x, which is what a 1080p
            // output looks like on this panel. On a native 4K output the left half is visibly
            // sharper than the right.
            String[] lines = {"The quick brown fox", "jumps over the lazy dog", "0123456789 ABCDEF",
                    "4K 4K 4K 4K 4K 4K 4K"};
            float textSize = Math.max(14f, w / 60f);
            int bandTop = Math.round(h / 12f) + 20;
            int bandHeight = Math.round(lines.length * textSize * 1.5f) + 20;
            Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
            text.setColor(Color.WHITE);
            text.setTextSize(textSize);
            for (int index = 0; index < lines.length; index++) {
                canvas.drawText(lines[index], 20, bandTop + textSize * (index + 1) * 1.5f, text);
            }
            Bitmap half = Bitmap.createBitmap(w / 4, Math.max(1, bandHeight / 2),
                    Bitmap.Config.ARGB_8888);
            Canvas halfCanvas = new Canvas(half);
            Paint halfText = new Paint(Paint.ANTI_ALIAS_FLAG);
            halfText.setColor(Color.WHITE);
            halfText.setTextSize(textSize / 2f);
            for (int index = 0; index < lines.length; index++) {
                halfCanvas.drawText(lines[index], 10,
                        textSize / 2f * (index + 1) * 1.5f, halfText);
            }
            Paint scale = new Paint(Paint.FILTER_BITMAP_FLAG);
            canvas.drawBitmap(half, null,
                    new android.graphics.Rect(w / 2 + 20, bandTop, w / 2 + 20 + w / 2, bandTop + bandHeight),
                    scale);
            Paint tag = new Paint(Paint.ANTI_ALIAS_FLAG);
            tag.setColor(Color.YELLOW);
            tag.setTextSize(textSize * 1.4f);
            canvas.drawText("LEFT: native " + w + "x" + h, 20, bandTop + bandHeight + textSize * 2f, tag);
            canvas.drawText("RIGHT: drawn at half, enlarged 2x", w / 2f + 20,
                    bandTop + bandHeight + textSize * 2f, tag);

            Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
            label.setTextSize(Math.max(40, w / 14f));
            label.setTextAlign(Paint.Align.CENTER);
            label.setColor(Color.WHITE);
            canvas.drawText("buffer " + w + "x" + h, w / 2f, h / 2f, label);
            label.setTextSize(label.getTextSize() * 0.5f);
            canvas.drawText("tap to close", w / 2f, h / 2f + label.getTextSize() * 2.2f, label);
        } finally {
            holder.unlockCanvasAndPost(canvas);
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
    }
}
