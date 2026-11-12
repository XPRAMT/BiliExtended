package dev.xpramt.bilisurfacebridge;

import android.content.SharedPreferences;
import android.util.Log;

import java.util.Map;
import java.util.Set;


/** Preferences shared by the module UI and the injected Bilibili process. */
final class ModulePreferences {
    private static final String TAG = "BiliExtended";
    static final String PACKAGE_NAME = "dev.xpramt.bilisurfacebridge";
    static final String KEY_VIDEO_DETAIL_RENDERER_MODE = "video_detail_renderer_mode";
    static final String KEY_PLAYBACK_DEBUG_OVERLAY = "playback_debug_overlay";
    static final String KEY_PLAYBACK_STREAM_OVERLAY = "playback_stream_overlay";
    static final String KEY_AUTO_SELECT_BEST_AUDIO = "auto_select_best_audio";
    static final String KEY_RENDER_CADENCE_PROBE = "render_cadence_probe";
    static final String KEY_MEDIA3_FRAME_SYNC = "media3_frame_sync";
    static final String KEY_QTI_OUTPUT_FENCE = "qti_output_fence";
    static final String KEY_BLOCK_IJK_FRAME_DROP = "block_ijk_frame_drop";
    static final String KEY_BLOCK_COMPUTE_TARGET_DELAY = "block_compute_target_delay";
    static final String KEY_FRAME_QUEUE_WAIT_ROUND_UP = "frame_queue_wait_round_up";
    static final String KEY_MINIMAL_MEDIA3_PLAYER = "minimal_media3_player";
    static final String KEY_REPLACE_STORY_VIDEO = "replace_story_video";
    static final String KEY_NATIVE_RESOLUTION_OUTPUT = "native_resolution_output";
    static final String KEY_HDR_AUTO_SURFACE = "hdr_auto_surface";
    static final String KEY_OVERLAY_TOP_MARGIN_DP = "overlay_top_margin_dp";
    static final int DEFAULT_OVERLAY_TOP_MARGIN_DP = 12;
    static final int MIN_OVERLAY_TOP_MARGIN_DP = 0;
    static final int MAX_OVERLAY_TOP_MARGIN_DP = 100;
    // Overlay position: X from the left edge, Y from the top edge, in dp, stored per orientation
    // because the screen is a different size. X unset (-1) keeps the overlay centred.
    static final String KEY_OVERLAY_X_LAND = "overlay_x_dp_land";
    static final String KEY_OVERLAY_Y_LAND = "overlay_y_dp_land";
    static final String KEY_OVERLAY_X_PORT = "overlay_x_dp_port";
    static final String KEY_OVERLAY_Y_PORT = "overlay_y_dp_port";
    static final String KEY_OVERLAY_TEXT_SIZE_SP = "overlay_text_size_sp";
    static final int OVERLAY_X_UNSET = -1;
    /** Sanity bound only: the sliders stop at the real screen size. */
    static final int MAX_OVERLAY_POSITION_DP = 4000;
    static final int DEFAULT_OVERLAY_TEXT_SIZE_SP = 9;
    static final int MIN_OVERLAY_TEXT_SIZE_SP = 1;
    static final int MAX_OVERLAY_TEXT_SIZE_SP = 20;
    static final boolean DEFAULT_PLAYBACK_DEBUG_OVERLAY = false;
    static final boolean DEFAULT_PLAYBACK_STREAM_OVERLAY = false;
    static final boolean DEFAULT_AUTO_SELECT_BEST_AUDIO = false;
    static final boolean DEFAULT_RENDER_CADENCE_PROBE = false;
    static final boolean DEFAULT_MEDIA3_FRAME_SYNC = true;
    // IJK earlyUs is retained as an explicit experiment. On NX809J its native clock sample can
    // lead the release deadline by hundreds of milliseconds, so it must not be the safe default.
    static final boolean DEFAULT_QTI_OUTPUT_FENCE = false;   // off: with it on, the NX809J decoder (Android 16) fails to start and IJK falls back to SOFTWARE decoding (fb1, 2026-11-11: 6 of 6 steps)
    static final boolean DEFAULT_BLOCK_IJK_FRAME_DROP = false;
    static final boolean DEFAULT_BLOCK_COMPUTE_TARGET_DELAY = true;
    static final boolean DEFAULT_FRAME_QUEUE_WAIT_ROUND_UP = false;
    static final boolean DEFAULT_MINIMAL_MEDIA3_PLAYER = false;
    static final boolean DEFAULT_REPLACE_STORY_VIDEO = false;
    static final boolean DEFAULT_NATIVE_RESOLUTION_OUTPUT = false;
    static final boolean DEFAULT_HDR_AUTO_SURFACE = true;

    /** Media3 scheduleMedia3: release when releaseTs-now is within this many ms. */
    static final String KEY_MEDIA3_READY_WINDOW_MS = "media3_ready_window_ms";
    static final int DEFAULT_MEDIA3_READY_WINDOW_MS = 33;
    static final int MIN_MEDIA3_READY_WINDOW_MS = 1;
    static final int MAX_MEDIA3_READY_WINDOW_MS = 50;

    /** Media3 late-frame skip: skip when more than this many ms behind audio. */
    static final String KEY_MEDIA3_LATE_DROP_MS = "media3_late_drop_ms";
    static final int DEFAULT_MEDIA3_LATE_DROP_MS = 30;
    static final int MIN_MEDIA3_LATE_DROP_MS = 1;
    static final int MAX_MEDIA3_LATE_DROP_MS = 50;



    /**
     * High speeds (user, 2026-11-11: "倍速開關合併 打開就同時解除上限和加入選單"): one switch both lifts the player core's speed ceiling (up to 10x) and
     * adds the high speeds to the 倍速 menu.  Replaces the two switches lift_speed_cap and speed_menu_extra: while high_speed was never set, those old
     * values decide (on only if every old one that was set is on).
     */
    static final String KEY_HIGH_SPEED = "high_speed";
    static final boolean DEFAULT_HIGH_SPEED = true;
    private static final String LIFT_SPEED_CAP_OLD = "lift_speed_cap";
    private static final String SPEED_MENU_EXTRA_OLD = "speed_menu_extra";
    static final String KEY_SPEED_CAP_LIMIT = "speed_cap_limit";
    static final float DEFAULT_SPEED_CAP_LIMIT = SpeedSettings.MAX_RATE;
    static final String KEY_LONG_PRESS_SPEED = "long_press_speed";

    static boolean loadHighSpeedForHook() {
        try {
            SharedPreferences preferences = hookPreferences();
            if (preferences.contains(KEY_HIGH_SPEED)) {
                return preferences.getBoolean(KEY_HIGH_SPEED, DEFAULT_HIGH_SPEED);
            }
            return preferences.getBoolean(LIFT_SPEED_CAP_OLD, DEFAULT_HIGH_SPEED)
                    && preferences.getBoolean(SPEED_MENU_EXTRA_OLD, DEFAULT_HIGH_SPEED);
        } catch (Throwable ignored) {
            return DEFAULT_HIGH_SPEED;
        }
    }

    /** Effective native ceiling for the IJK service; 0 means leave Bilibili's own cap alone. */
    static float loadSpeedCapLimitForHook() {
        try {
            return SpeedSettings.effectiveCapLimit(loadHighSpeedForHook(),
                    hookPreferences().getFloat(KEY_SPEED_CAP_LIMIT, DEFAULT_SPEED_CAP_LIMIT));
        } catch (Throwable ignored) {
            return SpeedSettings.effectiveCapLimit(DEFAULT_HIGH_SPEED, DEFAULT_SPEED_CAP_LIMIT);
        }
    }

    /** The extra speeds in the 倍速 menu follow the same switch. */
    static boolean loadSpeedMenuExtraForHook() {
        return loadHighSpeedForHook();
    }

    /** NaN unless the user has set a long-press rate: only then does it replace Bilibili's. */
    static float loadLongPressSpeedOverrideForHook() {
        try {
            SharedPreferences preferences = hookPreferences();
            return SpeedSettings.longPressOverride(preferences.contains(KEY_LONG_PRESS_SPEED),
                    preferences.getFloat(KEY_LONG_PRESS_SPEED,
                            SpeedSettings.DEFAULT_LONG_PRESS_RATE));
        } catch (Throwable ignored) {
            return Float.NaN;
        }
    }

    static boolean loadHdrAutoSurfaceForHook() {
        try {
            return hookPreferences().getBoolean(KEY_HDR_AUTO_SURFACE, DEFAULT_HDR_AUTO_SURFACE);
        } catch (Throwable ignored) {
            return DEFAULT_HDR_AUTO_SURFACE;
        }
    }

    static boolean loadNativeResolutionOutputForHook() {
        try {
            return hookPreferences().getBoolean(
                    KEY_NATIVE_RESOLUTION_OUTPUT, DEFAULT_NATIVE_RESOLUTION_OUTPUT);
        } catch (Throwable ignored) {
            return DEFAULT_NATIVE_RESOLUTION_OUTPUT;
        }
    }


    static int clampMedia3ReadyWindowMs(int ms) {
        return Math.max(MIN_MEDIA3_READY_WINDOW_MS, Math.min(MAX_MEDIA3_READY_WINDOW_MS, ms));
    }

    static int clampMedia3LateDropMs(int ms) {
        return Math.max(MIN_MEDIA3_LATE_DROP_MS, Math.min(MAX_MEDIA3_LATE_DROP_MS, ms));
    }


    static int loadMedia3ReadyWindowMsForHook() {
        try {
            return clampMedia3ReadyWindowMs(hookPreferences().getInt(
                    KEY_MEDIA3_READY_WINDOW_MS, DEFAULT_MEDIA3_READY_WINDOW_MS));
        } catch (Throwable ignored) {
            return DEFAULT_MEDIA3_READY_WINDOW_MS;
        }
    }

    static int loadMedia3LateDropMsForHook() {
        try {
            return clampMedia3LateDropMs(hookPreferences().getInt(
                    KEY_MEDIA3_LATE_DROP_MS, DEFAULT_MEDIA3_LATE_DROP_MS));
        } catch (Throwable ignored) {
            return DEFAULT_MEDIA3_LATE_DROP_MS;
        }
    }


    private ModulePreferences() {
    }

    enum RendererMode {
        TEXTURE_EXTERNAL("Texture", "TypeTextureViewWithExternalRender"),
        SURFACE_EXTERNAL("Surface external", "TypeSurfaceViewWithExternalRender"),
        SURFACE("Surface", "TypeSurfaceView");

        final String label;
        final String bilibiliTypeField;

        RendererMode(String label, String bilibiliTypeField) {
            this.label = label;
            this.bilibiliTypeField = bilibiliTypeField;
        }

        static RendererMode fromStoredValue(String value) {
            for (RendererMode mode : values()) {
                if (mode.name().equals(value)) {
                    return mode;
                }
            }
            return null;
        }
    }

    static int loadOverlayTopMarginDpForHook() {
        try {
            return clampOverlayTopMargin(hookPreferences().getInt(
                    KEY_OVERLAY_TOP_MARGIN_DP, DEFAULT_OVERLAY_TOP_MARGIN_DP));
        } catch (Throwable ignored) {
            return DEFAULT_OVERLAY_TOP_MARGIN_DP;
        }
    }

    private static int clampOverlayTopMargin(int dp) {
        return Math.max(MIN_OVERLAY_TOP_MARGIN_DP, Math.min(MAX_OVERLAY_TOP_MARGIN_DP, dp));
    }

    static String overlayXKey(boolean landscape) {
        return landscape ? KEY_OVERLAY_X_LAND : KEY_OVERLAY_X_PORT;
    }

    static String overlayYKey(boolean landscape) {
        return landscape ? KEY_OVERLAY_Y_LAND : KEY_OVERLAY_Y_PORT;
    }

    static int loadOverlayXDpForHook(boolean landscape) {
        try {
            int value = hookPreferences().getInt(overlayXKey(landscape), OVERLAY_X_UNSET);
            return value < 0 ? OVERLAY_X_UNSET : Math.min(value, MAX_OVERLAY_POSITION_DP);
        } catch (Throwable ignored) {
            return OVERLAY_X_UNSET;
        }
    }

    static int loadOverlayYDpForHook(boolean landscape) {
        try {
            return clampPosition(hookPreferences().getInt(
                    overlayYKey(landscape), loadOverlayTopMarginDpForHook()));
        } catch (Throwable ignored) {
            return DEFAULT_OVERLAY_TOP_MARGIN_DP;
        }
    }

    static int loadOverlayTextSizeSpForHook() {
        try {
            return clampTextSize(hookPreferences().getInt(
                    KEY_OVERLAY_TEXT_SIZE_SP, DEFAULT_OVERLAY_TEXT_SIZE_SP));
        } catch (Throwable ignored) {
            return DEFAULT_OVERLAY_TEXT_SIZE_SP;
        }
    }

    private static int clampPosition(int dp) {
        return Math.max(0, Math.min(MAX_OVERLAY_POSITION_DP, dp));
    }

    private static int clampTextSize(int sp) {
        return Math.max(MIN_OVERLAY_TEXT_SIZE_SP, Math.min(MAX_OVERLAY_TEXT_SIZE_SP, sp));
    }

    static boolean loadReplaceStoryVideoForHook() {
        try {
            return hookPreferences().getBoolean(
                    KEY_REPLACE_STORY_VIDEO, DEFAULT_REPLACE_STORY_VIDEO);
        } catch (Throwable ignored) {
            return DEFAULT_REPLACE_STORY_VIDEO;
        }
    }

    static RendererMode loadVideoDetailRendererModeForHook() {
        try {
            SharedPreferences preferences = hookPreferences();
            return rendererMode(preferences);
        } catch (Throwable ignored) {
            return RendererMode.SURFACE_EXTERNAL;
        }
    }

    static boolean loadPlaybackDebugOverlayForHook() {
        try {
            SharedPreferences preferences = hookPreferences();
            return preferences.getBoolean(
                    KEY_PLAYBACK_DEBUG_OVERLAY, DEFAULT_PLAYBACK_DEBUG_OVERLAY);
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean loadPlaybackStreamOverlayForHook() {
        try {
            SharedPreferences preferences = hookPreferences();
            return preferences.getBoolean(
                    KEY_PLAYBACK_STREAM_OVERLAY, DEFAULT_PLAYBACK_STREAM_OVERLAY);
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean loadAutoSelectBestAudioForHook() {
        try {
            SharedPreferences preferences = hookPreferences();
            return preferences.getBoolean(
                    KEY_AUTO_SELECT_BEST_AUDIO, DEFAULT_AUTO_SELECT_BEST_AUDIO);
        } catch (Throwable ignored) {
            return DEFAULT_AUTO_SELECT_BEST_AUDIO;
        }
    }

    static boolean loadRenderCadenceProbeForHook() {
        try {
            SharedPreferences preferences = hookPreferences();
            return preferences.getBoolean(KEY_RENDER_CADENCE_PROBE,
                    DEFAULT_RENDER_CADENCE_PROBE);
        } catch (Throwable ignored) {
            return DEFAULT_RENDER_CADENCE_PROBE;
        }
    }

    static boolean loadMedia3FrameSyncForHook() {
        try {
            SharedPreferences preferences = hookPreferences();
            return preferences.getBoolean(KEY_MEDIA3_FRAME_SYNC, DEFAULT_MEDIA3_FRAME_SYNC);
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean loadQtiOutputFenceForHook() {
        try {
            return hookPreferences().getBoolean(KEY_QTI_OUTPUT_FENCE, DEFAULT_QTI_OUTPUT_FENCE);
        } catch (Throwable ignored) {
            return DEFAULT_QTI_OUTPUT_FENCE;
        }
    }

    static boolean loadBlockIjkFrameDropForHook() {
        try {
            SharedPreferences preferences = hookPreferences();
            return preferences.getBoolean(
                    KEY_BLOCK_IJK_FRAME_DROP, DEFAULT_BLOCK_IJK_FRAME_DROP);
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean loadComputeTargetDelayBlockedForHook() {
        try {
            SharedPreferences preferences = hookPreferences();
            return preferences.getBoolean(KEY_BLOCK_COMPUTE_TARGET_DELAY,
                    DEFAULT_BLOCK_COMPUTE_TARGET_DELAY);
        } catch (Throwable ignored) {
            return DEFAULT_BLOCK_COMPUTE_TARGET_DELAY;
        }
    }

    static boolean loadFrameQueueWaitRoundUpForHook() {
        try {
            SharedPreferences preferences = hookPreferences();
            return preferences.getBoolean(KEY_FRAME_QUEUE_WAIT_ROUND_UP,
                    DEFAULT_FRAME_QUEUE_WAIT_ROUND_UP);
        } catch (Throwable ignored) {
            return DEFAULT_FRAME_QUEUE_WAIT_ROUND_UP;
        }
    }

    private static RendererMode rendererMode(SharedPreferences preferences) {
        RendererMode stored = RendererMode.fromStoredValue(
                preferences.getString(KEY_VIDEO_DETAIL_RENDERER_MODE, null));
        if (stored != null) {
            return stored;
        }
        return RendererMode.SURFACE_EXTERNAL;
    }

    /**
     * Where every setting lives: a file in Bilibili's own data folder (BiliSettings), written by the in-player Bili Extended menu.  The module app
     * keeps no settings (user, 2026-11-11: settings only in Bilibili, the module stores none).  Why: the module app's copy never reached Bilibili on
     * the phone -- the provider call failed (package visibility) and the framework copy XSharedPreferences pointed at had no file -- so every
     * value Bilibili used came from the in-player menu or the program default anyway.
     */
    private static SharedPreferences hookPreferences() {
        return BiliSettings.view();
    }

    /** Stores a setting changed in the in-player menu; every Bilibili process sees it within a second. */
    static void set(String key, Object value) {
        BiliSettings.put(key, String.valueOf(value));
    }

    static boolean loadMinimalMedia3PlayerForHook() {
        try {
            return hookPreferences().getBoolean(KEY_MINIMAL_MEDIA3_PLAYER, DEFAULT_MINIMAL_MEDIA3_PLAYER);
        } catch (Throwable ignored) {
            return DEFAULT_MINIMAL_MEDIA3_PLAYER;
        }
    }
}
