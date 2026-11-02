package dev.xpramt.bilisurfacebridge;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * HDR videos play through the direct Surface renderer.
 *
 * <p>Bilibili's external renderers redraw every frame with GL into an 8-bit sRGB surface, which
 * turns an HDR (BT.2020 PQ) video into a tone-mapped SDR picture. The direct Surface renderer
 * hands MediaCodec's own output to the display, so SurfaceFlinger sees the HDR dataspace and the
 * system HDR path is used. While an HDR video is selected (Bilibili quality 125 "HDR" or 126
 * "Dolby Vision") and the setting is on, the video-detail renderer is {@code Surface}; when a
 * different quality or video follows, the configured renderer comes back.
 */
final class HdrVideoRenderer {
    private static final String TAG = "BiliSurfaceBridge";
    private static final int QUALITY_HDR = 125;
    private static final int QUALITY_DOLBY_VISION = 126;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile boolean hdrVideo;
    /** True while we moved the live renderer to Surface ourselves and owe it back. */
    private static volatile boolean forcedSurface;

    private HdrVideoRenderer() {
    }

    /** Called with every video quality Bilibili selects or switches to. */
    static void noteVideoQuality(int qualityId) {
        boolean hdr = qualityId == QUALITY_HDR || qualityId == QUALITY_DOLBY_VISION;
        if (hdr == hdrVideo) {
            return;
        }
        hdrVideo = hdr;
        Log.i(TAG, "HDR video " + (hdr ? "selected" : "left") + " (quality " + qualityId + ")");
        reevaluate();
    }

    /** What the renderer factory should use now: Surface for HDR, the configured mode otherwise. */
    static ModulePreferences.RendererMode effectiveMode(ModulePreferences.RendererMode configured) {
        if (hdrVideo && ModulePreferences.loadHdrAutoSurfaceForHook()) {
            return ModulePreferences.RendererMode.SURFACE;
        }
        return configured;
    }

    /** Moves the live player to the renderer that {@link #effectiveMode} now asks for. */
    static void reevaluate() {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    ModulePreferences.RendererMode configured =
                            ModulePreferences.loadVideoDetailRendererModeForHook();
                    boolean wantSurface = hdrVideo && ModulePreferences.loadHdrAutoSurfaceForHook();
                    if (wantSurface && configured != ModulePreferences.RendererMode.SURFACE) {
                        forcedSurface = true;
                        PlayerSettingPanelHook.switchRendererTo(
                                ModulePreferences.RendererMode.SURFACE);
                        Log.i(TAG, "HDR video: renderer switched to Surface");
                    } else if (!wantSurface && forcedSurface) {
                        forcedSurface = false;
                        PlayerSettingPanelHook.switchRendererTo(configured);
                        Log.i(TAG, "HDR video over: renderer back to " + configured.label);
                    }
                } catch (Throwable throwable) {
                    Log.w(TAG, "HDR renderer switch failed: " + throwable.getClass().getSimpleName());
                }
            }
        });
    }
}
