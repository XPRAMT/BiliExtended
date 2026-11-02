package dev.xpramt.bilisurfacebridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XSharedPreferences;

/** Preferences shared by the module UI and the injected Bilibili process. */
final class ModulePreferences {
    private static final String TAG = "BiliSurfaceBridge";
    static final String PACKAGE_NAME = "dev.xpramt.bilisurfacebridge";
    static final String PREFS_NAME = "module";
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
    static final boolean DEFAULT_QTI_OUTPUT_FENCE = true;
    static final boolean DEFAULT_BLOCK_IJK_FRAME_DROP = false;
    static final boolean DEFAULT_BLOCK_COMPUTE_TARGET_DELAY = true;
    static final boolean DEFAULT_FRAME_QUEUE_WAIT_ROUND_UP = false;
    static final boolean DEFAULT_MINIMAL_MEDIA3_PLAYER = false;
    static final boolean DEFAULT_REPLACE_STORY_VIDEO = false;
    static final boolean DEFAULT_NATIVE_RESOLUTION_OUTPUT = false;

    static boolean isNativeResolutionOutputEnabled(Context context) {
        return preferences(context).getBoolean(
                KEY_NATIVE_RESOLUTION_OUTPUT, DEFAULT_NATIVE_RESOLUTION_OUTPUT);
    }

    static void setNativeResolutionOutputEnabled(Context context, boolean enabled) {
        commitPreferences(context, preferences(context).edit()
                .putBoolean(KEY_NATIVE_RESOLUTION_OUTPUT, enabled));
    }

    static boolean loadNativeResolutionOutputForHook() {
        try {
            return hookPreferences().getBoolean(
                    KEY_NATIVE_RESOLUTION_OUTPUT, DEFAULT_NATIVE_RESOLUTION_OUTPUT);
        } catch (Throwable ignored) {
            return DEFAULT_NATIVE_RESOLUTION_OUTPUT;
        }
    }

    private static volatile boolean automationProfileLocksRenderer = false;

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

    static RendererMode getVideoDetailRendererMode(Context context) {
        return rendererMode(preferences(context));
    }

    static void setVideoDetailRendererMode(Context context, RendererMode mode) {
        if (automationProfileLocksRenderer) {
            return;
        }
        commitPreferences(context, preferences(context).edit()
                .putString(KEY_VIDEO_DETAIL_RENDERER_MODE, mode.name()));
    }

    static boolean isPlaybackDebugOverlayEnabled(Context context) {
        return preferences(context).getBoolean(
                KEY_PLAYBACK_DEBUG_OVERLAY, DEFAULT_PLAYBACK_DEBUG_OVERLAY);
    }

    static void setPlaybackDebugOverlayEnabled(Context context, boolean enabled) {
        commitPreferences(context, preferences(context).edit()
                .putBoolean(KEY_PLAYBACK_DEBUG_OVERLAY, enabled));
    }

    static boolean isPlaybackStreamOverlayEnabled(Context context) {
        return preferences(context).getBoolean(
                KEY_PLAYBACK_STREAM_OVERLAY, DEFAULT_PLAYBACK_STREAM_OVERLAY);
    }

    static void setPlaybackStreamOverlayEnabled(Context context, boolean enabled) {
        commitPreferences(context, preferences(context).edit()
                .putBoolean(KEY_PLAYBACK_STREAM_OVERLAY, enabled));
    }

    static boolean isAutoSelectBestAudioEnabled(Context context) {
        return preferences(context).getBoolean(
                KEY_AUTO_SELECT_BEST_AUDIO, DEFAULT_AUTO_SELECT_BEST_AUDIO);
    }

    static int getOverlayTopMarginDp(Context context) {
        return clampOverlayTopMargin(preferences(context).getInt(
                KEY_OVERLAY_TOP_MARGIN_DP, DEFAULT_OVERLAY_TOP_MARGIN_DP));
    }

    static void setOverlayTopMarginDp(Context context, int dp) {
        commitPreferences(context, preferences(context).edit()
                .putInt(KEY_OVERLAY_TOP_MARGIN_DP, clampOverlayTopMargin(dp)));
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

    /** -1 when never set (centred). */
    static int getOverlayXDp(Context context, boolean landscape) {
        int value = preferences(context).getInt(overlayXKey(landscape), OVERLAY_X_UNSET);
        return value < 0 ? OVERLAY_X_UNSET : Math.min(value, MAX_OVERLAY_POSITION_DP);
    }

    /** Falls back to the single top margin this setting replaced. */
    static int getOverlayYDp(Context context, boolean landscape) {
        return clampPosition(preferences(context).getInt(
                overlayYKey(landscape), getOverlayTopMarginDp(context)));
    }

    static int getOverlayTextSizeSp(Context context) {
        return clampTextSize(preferences(context).getInt(
                KEY_OVERLAY_TEXT_SIZE_SP, DEFAULT_OVERLAY_TEXT_SIZE_SP));
    }

    /** Stores one of the overlay position / size settings; false for any other key. */
    static boolean setOverlayInt(Context context, String key, int value) {
        int stored;
        if (KEY_OVERLAY_X_LAND.equals(key) || KEY_OVERLAY_X_PORT.equals(key)) {
            stored = value < 0 ? OVERLAY_X_UNSET : Math.min(value, MAX_OVERLAY_POSITION_DP);
        } else if (KEY_OVERLAY_Y_LAND.equals(key) || KEY_OVERLAY_Y_PORT.equals(key)) {
            stored = clampPosition(value);
        } else if (KEY_OVERLAY_TEXT_SIZE_SP.equals(key)) {
            stored = clampTextSize(value);
        } else {
            return false;
        }
        commitPreferences(context, preferences(context).edit().putInt(key, stored));
        return true;
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

    static boolean isReplaceStoryVideoEnabled(Context context) {
        return preferences(context).getBoolean(
                KEY_REPLACE_STORY_VIDEO, DEFAULT_REPLACE_STORY_VIDEO);
    }

    static void setReplaceStoryVideoEnabled(Context context, boolean enabled) {
        commitPreferences(context, preferences(context).edit()
                .putBoolean(KEY_REPLACE_STORY_VIDEO, enabled));
    }

    static boolean loadReplaceStoryVideoForHook() {
        try {
            return hookPreferences().getBoolean(
                    KEY_REPLACE_STORY_VIDEO, DEFAULT_REPLACE_STORY_VIDEO);
        } catch (Throwable ignored) {
            return DEFAULT_REPLACE_STORY_VIDEO;
        }
    }

    static void setAutoSelectBestAudioEnabled(Context context, boolean enabled) {
        commitPreferences(context, preferences(context).edit()
                .putBoolean(KEY_AUTO_SELECT_BEST_AUDIO, enabled));
    }

    static boolean isMedia3FrameSyncEnabled(Context context) {
        return preferences(context).getBoolean(KEY_MEDIA3_FRAME_SYNC, DEFAULT_MEDIA3_FRAME_SYNC);
    }

    static void setMedia3FrameSyncEnabled(Context context, boolean enabled) {
        commitPreferences(context, preferences(context).edit()
                .putBoolean(KEY_MEDIA3_FRAME_SYNC, enabled));
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

    static boolean loadMinimalMedia3PlayerForHook() {
        try {
            SharedPreferences preferences = hookPreferences();
            boolean enabled = preferences.getBoolean(
                    KEY_MINIMAL_MEDIA3_PLAYER, DEFAULT_MINIMAL_MEDIA3_PLAYER);
            String source;
            if (HOOK_VIEW.delegate() instanceof XSharedPreferences) {
                File preferenceFile = ((XSharedPreferences) HOOK_VIEW.delegate()).getFile();
                source = " file=" + preferenceFile.getAbsolutePath()
                        + " readable=" + preferenceFile.canRead()
                        + " length=" + preferenceFile.length()
                        + " modified=" + preferenceFile.lastModified();
            } else {
                source = " source=provider";
            }
            Log.i(TAG, "[DEBUG-PREF-01] minimal_media3_player=" + enabled
                    + " contains=" + preferences.contains(KEY_MINIMAL_MEDIA3_PLAYER)
                    + " keys=" + preferences.getAll().keySet() + source);
            return enabled;
        } catch (Throwable ignored) {
            Log.e(TAG, "[DEBUG-PREF-01] minimal_media3_player read failed"
                    + "=" + ignored.getClass().getSimpleName());
            return DEFAULT_MINIMAL_MEDIA3_PLAYER;
        }
    }

    static boolean isQtiOutputFenceEnabled(Context context) {
        return preferences(context).getBoolean(KEY_QTI_OUTPUT_FENCE, DEFAULT_QTI_OUTPUT_FENCE);
    }

    static void setQtiOutputFenceEnabled(Context context, boolean enabled) {
        commitPreferences(context, preferences(context).edit()
                .putBoolean(KEY_QTI_OUTPUT_FENCE, enabled));
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

    /** Apply a deterministic renderer A/B baseline from the unattended test runner. */
    static boolean applyAutomationProfile(Context context, String rendererModeValue,
                                            Boolean renderCadenceProbe,
                                            Boolean media3FrameSync,
                                            Boolean frameQueueWaitRoundUp) {
        return applyAutomationProfile(context, rendererModeValue, renderCadenceProbe,
                media3FrameSync, frameQueueWaitRoundUp, null, null);
    }

    /**
     * The measurement runner's A/B knobs (IJK block switches, frame-queue wait rounding, render
     * cadence probe) exist only here: they are not user settings.  The block switches default
     * to the historical A/B baseline (true) when null.
     */
    static boolean applyAutomationProfile(Context context, String rendererModeValue,
                                            Boolean renderCadenceProbe,
                                            Boolean media3FrameSync,
                                            Boolean frameQueueWaitRoundUp,
                                            Boolean blockIjkFrameDrop,
                                            Boolean blockComputeTargetDelay) {
        RendererMode mode = RendererMode.fromStoredValue(rendererModeValue);
        if (mode == null) {
            return false;
        }
        automationProfileLocksRenderer = true;
        SharedPreferences.Editor editor = preferences(context).edit()
                .putString(KEY_VIDEO_DETAIL_RENDERER_MODE, mode.name())
                .putBoolean(KEY_PLAYBACK_DEBUG_OVERLAY, true)
                .putBoolean(KEY_MEDIA3_FRAME_SYNC,
                        media3FrameSync != null && media3FrameSync.booleanValue())
                .putBoolean(KEY_BLOCK_IJK_FRAME_DROP,
                        blockIjkFrameDrop == null || blockIjkFrameDrop.booleanValue())
                .putBoolean(KEY_BLOCK_COMPUTE_TARGET_DELAY,
                        blockComputeTargetDelay == null
                                || blockComputeTargetDelay.booleanValue())
                .putBoolean(KEY_FRAME_QUEUE_WAIT_ROUND_UP,
                        frameQueueWaitRoundUp != null
                                && frameQueueWaitRoundUp.booleanValue());
        if (renderCadenceProbe != null) {
            editor.putBoolean(KEY_RENDER_CADENCE_PROBE, renderCadenceProbe.booleanValue());
        }
        return commitPreferences(context, editor);
    }

    /**
     * LSPosed's xposedsharedprefs bridge can only refresh module preferences when the source XML
     * remains readable outside the module process. Android 9+ ignores the legacy
     * MODE_WORLD_READABLE flag for modern-target applications, so make the committed file
     * explicitly world-readable after every write.
     */
    private static boolean commitPreferences(Context context, SharedPreferences.Editor editor) {
        if (!editor.commit()) {
            return false;
        }
        return makePreferencesWorldReadable(context);
    }

    private static boolean makePreferencesWorldReadable(Context context) {
        try {
            File preferencesFile = new File(
                    new File(context.getApplicationInfo().dataDir, "shared_prefs"),
                    PREFS_NAME + ".xml");
            return preferencesFile.exists() && preferencesFile.setReadable(true, false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    // The hooks read preferences several times per video frame on the IJK release thread;
    // parsing the XML each time cost CPU and garbage in the hot path (GC bursts starved the
    // pipeline threads).  Reads come from an in-memory view re-checked at most every two seconds.
    //
    // One source of truth: the module app's own preferences, read live through its provider.
    // The copy a framework keeps for XSharedPreferences (LSPosed/Vector sync it only when it
    // feels like it, so it can be stale) is only the fallback for when the provider cannot be
    // reached; settings changed inside Bilibili that could not be written to the module app
    // live in HostOverrideStore (see there).
    private static final long HOOK_PREFERENCES_TTL_NS = 2_000_000_000L;
    private static final long PROVIDER_RETRY_NS = 10_000_000_000L;
    private static XSharedPreferences cachedHookPreferences;
    private static long cachedHookPreferencesNs;
    private static volatile SharedPreferences providerSnapshot;
    private static volatile boolean providerRefreshRunning;
    private static volatile long providerCheckedNs;
    private static volatile boolean providerApplicationMissing;

    private static synchronized SharedPreferences hookPreferences() {
        long now = System.nanoTime();
        SharedPreferences snapshot = providerSnapshot;
        if (snapshot == null) {
            if (providerCheckedNs == 0 || now - providerCheckedNs > PROVIDER_RETRY_NS) {
                // Synchronous, so startup hooks see the user's settings. Before the Application
                // exists nothing was tried, so the next read tries again at once.
                providerApplicationMissing = false;
                snapshot = readProviderSnapshot();
                providerCheckedNs = providerApplicationMissing ? 0 : now;
                if (snapshot != null) {
                    providerSnapshot = snapshot;
                }
            }
        } else if (now - providerCheckedNs > HOOK_PREFERENCES_TTL_NS && !providerRefreshRunning) {
            // Later refreshes stay off the hot path: the provider call may start the module
            // app's process.
            providerCheckedNs = now;
            providerRefreshRunning = true;
            Thread refresh = new Thread(() -> {
                try {
                    SharedPreferences fresh = readProviderSnapshot();
                    if (fresh != null) {
                        providerSnapshot = fresh;
                    }
                } finally {
                    providerRefreshRunning = false;
                }
            }, "BiliSurfaceBridge-prefs");
            refresh.setDaemon(true);
            refresh.start();
        }
        if (snapshot != null) {
            return HOOK_VIEW.delegateTo(snapshot);
        }
        // Provider unreachable: the framework's copy of the file, if this process can read it.
        boolean stale = cachedHookPreferences == null
                || now - cachedHookPreferencesNs > HOOK_PREFERENCES_TTL_NS;
        if (cachedHookPreferences == null) {
            cachedHookPreferences = new XSharedPreferences(PACKAGE_NAME, PREFS_NAME);
        }
        if (stale) {
            cachedHookPreferences.reload();
            cachedHookPreferencesNs = now;
        }
        return HOOK_VIEW.delegateTo(cachedHookPreferences);
    }

    /** Re-reads the provider now, so a value just written to the module app is what hooks see. */
    static void refreshProviderSnapshot() {
        SharedPreferences fresh = readProviderSnapshot();
        if (fresh != null) {
            providerSnapshot = fresh;
            providerCheckedNs = System.nanoTime();
        }
    }

    // Values the in-player menu changed, kept by HostOverrideStore: the module app owns the
    // real preferences and cannot always be reached from Bilibili.
    private static final OverridePreferences HOOK_VIEW = new OverridePreferences();

    /** Records a change made inside Bilibili; it takes effect in every Bilibili process. */
    static void setHostOverride(String key, Object value) {
        hookPreferences();  // makes sure the module's own preferences are known
        SharedPreferences module = HOOK_VIEW.delegate();
        if (module == null) {
            return;
        }
        String moduleValue;
        if (value instanceof Boolean) {
            // An unset key reads as the opposite of the new value (see OverridePreferences).
            moduleValue = String.valueOf(module.getBoolean(key, !(Boolean) value));
        } else if (value instanceof Integer) {
            moduleValue = String.valueOf(module.getInt(key, Integer.MIN_VALUE));
        } else {
            moduleValue = String.valueOf(module.getString(key, ""));
        }
        HostOverrideStore.put(key, String.valueOf(value), moduleValue);
    }

    /** What the hooks read: the override first, everything else from the real preferences. */
    private static final class OverridePreferences implements SharedPreferences {
        private volatile SharedPreferences delegate;

        OverridePreferences delegateTo(SharedPreferences base) {
            delegate = base;
            return this;
        }

        SharedPreferences delegate() {
            return delegate;
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            String[] entry = HostOverrideStore.get(key);
            if (entry != null && entry[1].equals(String.valueOf(
                    delegate.getBoolean(key, !Boolean.parseBoolean(entry[0]))))) {
                return Boolean.parseBoolean(entry[0]);
            }
            return delegate.getBoolean(key, defValue);
        }

        @Override
        public String getString(String key, String defValue) {
            String[] entry = HostOverrideStore.get(key);
            if (entry != null && entry[1].equals(String.valueOf(delegate.getString(key, "")))) {
                return entry[0];
            }
            return delegate.getString(key, defValue);
        }

        @Override
        public int getInt(String key, int defValue) {
            String[] entry = HostOverrideStore.get(key);
            if (entry != null && entry[1].equals(String.valueOf(
                    delegate.getInt(key, Integer.MIN_VALUE)))) {
                try {
                    return Integer.parseInt(entry[0]);
                } catch (NumberFormatException ignored) {
                    // Fall through to the module's value.
                }
            }
            return delegate.getInt(key, defValue);
        }

        @Override
        public boolean contains(String key) {
            return delegate.contains(key);
        }

        @Override
        public Map<String, ?> getAll() {
            return delegate.getAll();
        }

        @Override
        public Set<String> getStringSet(String key, Set<String> defValues) {
            return delegate.getStringSet(key, defValues);
        }

        @Override
        public long getLong(String key, long defValue) {
            return delegate.getLong(key, defValue);
        }

        @Override
        public float getFloat(String key, float defValue) {
            return delegate.getFloat(key, defValue);
        }

        @Override
        public Editor edit() {
            throw new UnsupportedOperationException("read-only module preferences");
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener) {
        }

        @Override
        public void unregisterOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener) {
        }
    }

    private static SharedPreferences readProviderSnapshot() {
        try {
            Object application = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            if (!(application instanceof Context)) {
                providerApplicationMissing = true;
                return null;
            }
            android.os.Bundle bundle = ((Context) application).getContentResolver().call(
                    ModulePreferencesProvider.URI, ModulePreferencesProvider.METHOD_SNAPSHOT,
                    null, null);
            if (bundle == null) {
                logProviderOnce("module preferences provider returned nothing");
                return null;
            }
            if (!providerLogged) {
                providerLogged = true;
                Log.i(TAG, "module preferences read from provider keys="
                        + new java.util.TreeSet<>(bundle.keySet()));
            }
            return new SnapshotPreferences(bundle);
        } catch (Throwable throwable) {
            logProviderOnce("module preferences provider unavailable="
                    + throwable.getClass().getSimpleName());
            return null;
        }
    }

    private static volatile boolean providerLogged;
    private static volatile boolean providerFailureLogged;

    private static void logProviderOnce(String message) {
        if (!providerFailureLogged) {
            providerFailureLogged = true;
            Log.w(TAG, message);
        }
    }

    private static SharedPreferences preferences(Context context) {
        try {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_WORLD_READABLE);
        } catch (SecurityException ignored) {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
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
}
