package dev.xpramt.bilisurfacebridge;

import android.graphics.Point;
import android.content.Context;
import android.content.ContextWrapper;
import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.Surface;
import android.view.ViewGroup;
import android.view.View;
import android.view.WindowManager;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.WeakHashMap;
import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;


/**
 * Selects the requested Bilibili renderer only for a normal video-detail player. This avoids
 * changing cards, ads, and embedded players. Stream information overlays may also attach to a
 * live-room Activity, without changing the live renderer. The module logs renderer and display
 * facts without reading media URLs, account data, cookies, or playback authorization data.
 */
public final class HookEntry extends XposedModule {
    private static final String TAG = "BiliExtended";
    private static final String TARGET_PACKAGE = "tv.danmaku.bili";
    private static final String IJK_SERVICE_PROCESS = "tv.danmaku.bili:ijkservice";
    private static final String FACTORY =
            "tv.danmaku.biliplayerimpl.render.DefaultVideoRenderLayerFactory";
    private static final String RENDER_CONTAINER =
            "tv.danmaku.biliplayerimpl.render.RenderContainerService";
    private static final String PLAYER_CONTAINER =
            "tv.danmaku.biliplayerimpl.PlayerContainerImpl";
    // Obfuscated names are only hints, tried first so hooks exist before the first player is
    // bound; every hint is checked against the stable superclass/interface shape, and the
    // runtime PlayerContainer discovery below covers versions with unknown names.
    private static final String[] FACTORY_HINTS = {"xy1.a", "Wr1.a"}; // 9.17.0, 9.13.0
    private static final String[] RENDER_CONTAINER_HINTS = {"xy1.q", "Wr1.r"};
    private static final String[] CONTROL_CONTAINER_HINTS = {
            "tv.danmaku.biliplayerimpl.controlcontainer.i",
            "tv.danmaku.biliplayerimpl.controlcontainer.f"};
    private static final String VIDEO_RENDER_LAYER_FACTORY =
            "tv.danmaku.render.core.VideoRenderLayerFactory";
    private static final String ABSTRACT_PLAYER_CONTAINER =
            "tv.danmaku.biliplayerv2.PlayerContainer";
    private static final String RENDER_CONTAINER_SERVICE_INTERFACE =
            "tv.danmaku.biliplayerv2.service.IRenderContainerService";
    private static final String CONTROL_CONTAINER_SERVICE_INTERFACE =
            "tv.danmaku.biliplayerv2.service.IControlContainerService";
    private static final String BIND_PLAYER_CONTAINER_PREFIX = "bindPlayerContainer$";
    private static final Set<String> HOOKED_CLASSES =
            Collections.synchronizedSet(new HashSet<String>());
    private static final Map<Object, Boolean> ATTACHED_LAYERS =
            Collections.synchronizedMap(new WeakHashMap<Object, Boolean>());
    private static final String RENDER_LAYER = "tv.danmaku.render.core.IVideoRenderLayer";
    private static final String RENDER_TYPE = "tv.danmaku.render.core.IVideoRenderLayer$Type";
    private static final String VIDEO_DETAIL_ACTIVITY =
            "com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity";
    private static final Set<String> REPORTED =
            Collections.synchronizedSet(new HashSet<String>());
    private static final Map<Object, String> FACTORY_CONTEXTS =
            Collections.synchronizedMap(new WeakHashMap<Object, String>());
    private static final Map<Object, WeakReference<Activity>> FACTORY_ACTIVITIES =
            Collections.synchronizedMap(new WeakHashMap<Object, WeakReference<Activity>>());
    private static final String CONTROL_CONTAINER_SERVICE =
            "tv.danmaku.biliplayerimpl.controlcontainer.ControlContainerService";
    private static final String IJK_SERVICE =
            "tv.danmaku.ijk.media.player.services.IjkMediaPlayerService";
    private static final String IJK_PLAYER = "tv.danmaku.ijk.media.player.IjkMediaPlayer";
    private static final String IJK_VIDEO_DISPLAY_CALLBACK =
            "tv.danmaku.ijk.media.player.IjkMediaPlayer$OnVideoDisplayCallback";

    /** The process this module instance runs in (one instance per process). */
    private String processName;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        Xp.init(this);
        processName = param.getProcessName();
    }

    /** Bilibili's class loader and data folder the hooks were installed with: what a hot-reloaded generation needs to install them again. */
    private ClassLoader installedLoader;
    private String installedDataDir;

    /**
     * Hot reload (autoHotReload=true): a new module version installed while Bilibili runs takes over without restarting it.
     *
     * <p>Old generation, here.  After an update the new generation loads its OWN copy of the native library from the new APK (measured 2026-11-11:
     * both copies mapped, separate globals), so this copy first gives every IJK GOT slot it replaced back and stops its threads (nativeRetire);
     * then every module loop stops (Generation), the overlays and the panel leave the screen, and what the next generation needs is handed over:
     * the decoders that are playing with Bilibili's listeners, the playback rate, the overlay text, the activities.  Declined (the old code keeps
     * running until Bilibili restarts) when the native side cannot hand back (legacy inline probes).
     */
    @Override
    public boolean onHotReloading(XposedModuleInterface.HotReloadingParam param) {
        if (installedLoader == null) {
            return false;   // nothing installed in this process
        }
        long t0 = System.nanoTime();
        String nativeResult = NativeRefreshCap.retireIfLoaded();
        if (nativeResult.startsWith("refused")) {
            log("hot reload declined in " + processName + ": " + nativeResult);
            return false;
        }
        final java.util.HashMap<String, Object> handover = new java.util.HashMap<String, Object>();
        handover.put("loader", installedLoader);
        handover.put("hookedMethods", Xp.hookedMethods());
        handover.put("dataDir", installedDataDir);
        Generation.retire();
        Generation.runQuietly(() -> Media3FrameSynchronizer.exportForHotReload(handover));
        Generation.runQuietly(BiliSettings::flushNow);
        Generation.runQuietly(RateStateFile::shutdownForHotReload);
        Generation.runQuietly(PerfSampleOffload::shutdownForHotReload);
        if (TARGET_PACKAGE.equals(processName)) {
            handover.put("containers", exportContainers());
            handover.put("compositorSurface", ExternalRenderImmediate.exportForHotReload());
            handover.put("nativeResolution", NativeResolutionOutput.exportForHotReload());
            handover.put("streamLabels", Generation.exportLabels(PlaybackStreamOverlay.class));
            Generation.onMainThread(() -> {
                handover.put("debugOverlay", PlaybackDebugOverlay.retireForHotReload());
                handover.put("streamOverlay", PlaybackStreamOverlay.retireForHotReload());
                handover.put("textureViews", TextureViewVsyncInvalidator.exportForHotReload());
                handover.put("vsyncRelay", Boolean.valueOf(AppVsyncRelay.isRunning()));
                handover.put("panelEntry", PlayerSettingPanelHook.exportForHotReload());
                InPlayerSettingsPanel.closeForHotReload();
            }, 1500L);
        }
        param.setSavedInstanceState(handover);
        Object codecs = handover.get("m3.codecs");
        log("hot reloading: handed over process=" + processName + " pid=" + android.os.Process.myPid() + " native=" + nativeResult
                + " codecs=" + (codecs instanceof java.util.List ? ((java.util.List<?>) codecs).size() : 0) + " rate=" + handover.get("m3.rate")
                + " took=" + (System.nanoTime() - t0) / 1_000_000L + "ms");
        return true;
    }

    /** New generation: install every hook again and take over what the old one handed over (see onHotReloading). */
    @Override
    public void onHotReloaded(XposedModuleInterface.HotReloadedParam param) {
        long t0 = System.nanoTime();
        int oldHooks = param.getOldHookHandles() == null ? -1 : param.getOldHookHandles().size();
        super.onHotReloaded(param);   // removes the old generation's hooks
        Xp.init(this);
        processName = param.getProcessName();
        Object saved = param.getSavedInstanceState();
        if (!(saved instanceof java.util.Map)) {
            log("hot reloaded in " + processName + " without a hand-over: hooks are installed at the next Bilibili start");
            return;
        }
        final java.util.Map<?, ?> handover = (java.util.Map<?, ?>) saved;
        installedLoader = (ClassLoader) handover.get("loader");
        installedDataDir = (String) handover.get("dataDir");
        BiliSettings.init(installedDataDir);
        install(processName, installedLoader);
        String adopted = "";
        if (IJK_SERVICE_PROCESS.equals(processName)) {
            applyExperiments(null);   // the service already started: its native hooks would otherwise wait for the next one
            adopted = Media3FrameSynchronizer.adoptAfterHotReload(handover);
        }
        if (TARGET_PACKAGE.equals(processName)) {
            final int containers = rediscoverContainers(handover.get("containers"), installedLoader);
            adopted = "containers=" + containers + " compositor=" + ExternalRenderImmediate.adoptAfterHotReload(handover.get("compositorSurface")) + " ";
            Generation.importLabels(PlaybackStreamOverlay.class, handover.get("streamLabels"));
            Generation.onMainThread(() -> {
                PlayerSettingPanelHook.adoptAfterHotReload(handover.get("panelEntry"));
                NativeResolutionOutput.adoptAfterHotReload(handover.get("nativeResolution"));
                if (Boolean.TRUE.equals(handover.get("vsyncRelay"))) {
                    AppVsyncRelay.start();
                }
                TextureViewVsyncInvalidator.adoptAfterHotReload(handover.get("textureViews"));
                for (Activity activity : activities(handover.get("streamOverlay"))) {
                    attachStreamOverlayToActivity(activity);
                }
                // Always, like attachDebugOverlayForPlayback: the overlay object also runs the slow-playback keepalive, which must not
                // depend on the fps overlay switch (with the switch off the label just stays hidden).
                for (Activity activity : activities(handover.get("debugOverlay"))) {
                    PlaybackDebugOverlay.show(activity, "");
                }
            }, 1500L);
            adopted += "overlays=" + activities(handover.get("streamOverlay")).size() + "/" + activities(handover.get("debugOverlay")).size();
        }
        log("hot reloaded in process=" + processName + " pid=" + android.os.Process.myPid() + " oldHooks=" + oldHooks + " " + adopted
                + " took=" + (System.nanoTime() - t0) / 1_000_000L + "ms");
        // What the old generation had hooked that this one has not (yet): hooks installed on events that happened before the reload.
        java.util.List<String> missing = new java.util.ArrayList<String>();
        if (handover.get("hookedMethods") instanceof java.util.List) {
            java.util.List<String> now = Xp.hookedMethods();
            for (Object method : (java.util.List<?>) handover.get("hookedMethods")) {
                if (!now.contains(String.valueOf(method))) {
                    missing.add(String.valueOf(method));
                }
            }
        }
        log("hot reload: hooks of the old generation not installed again=" + missing.size() + (missing.isEmpty() ? "" : " " + missing));
    }

    private static java.util.List<Activity> activities(Object handedOver) {
        java.util.List<Activity> result = new java.util.ArrayList<Activity>();
        if (handedOver instanceof java.util.List) {
            for (Object item : (java.util.List<?>) handedOver) {
                if (item instanceof Activity && !((Activity) item).isFinishing() && !((Activity) item).isDestroyed()) {
                    result.add((Activity) item);
                }
            }
        }
        return result;
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName()) || !param.isFirstPackage()) {
            return;
        }
        installedDataDir = param.getApplicationInfo() == null ? null : param.getApplicationInfo().dataDir;
        BiliSettings.init(installedDataDir);
        installedLoader = param.getClassLoader();
        install(processName, installedLoader);
    }

    private void install(String processName, ClassLoader classLoader) {
        log("loaded for Bilibili; no media or account data is collected");
        boolean minimalMedia3Enabled = ModulePreferences.loadMinimalMedia3PlayerForHook();
        log("minimal Media3 preference=" + minimalMedia3Enabled
                + " process=" + processName);
        if (minimalMedia3Enabled) {
            logOnce("minimal-media3-player:" + processName,
                    Media3MinimalPlayerBridge.install(classLoader));
        } else {
            logOnce("stream-selection-probe:" + processName,
                    Media3MinimalPlayerBridge.installStreamSelectionProbe(
                            classLoader));
        }
        // Bisect switch (2026-11-09): `debug.biliextended.off` is a bit mask read once per process start; 1 = no ijkservice hooks, 2 = no external-render hooks (main),
        // 4 = no picture-queue / decoder-format relay hooks (main), 8 = no UI hooks (main), 16 = no display-counter / factory / container / lifecycle hooks.  Used to find
        // which part of the module causes the ~500 ms codec stalls; 0 / unset = everything installed (normal).
        final int off = DebugLog.readInt("debug.biliextended.off", 0);
        if (IJK_SERVICE_PROCESS.equals(processName)) {
            if ((off & 1) == 0) {
                hookIjkService(classLoader);
            }
            return;
        }
        if (TARGET_PACKAGE.equals(processName)) {
            // The IjkMediaPlayer Java API is driven from the main process (it forwards to the
            // service over AIDL), so player option hooks belong here, not in the service.
            if ((off & 4) == 0) {
            installPictureQueueSizeHook(classLoader);
            // Installed whatever the setting says; each hook checks Media3 Vsync when it runs, so
            // the in-player menu can switch it on without restarting Bilibili.
            logOnce("main-process-decoder-format",
                    Media3FrameSynchronizer.installMainProcessDecoderFormatRelay());
            }
            if ((off & 2) == 0) {
            logOnce("external-render-immediate", ExternalRenderImmediate.install());
            logOnce("textureview-vsync-invalidator", TextureViewVsyncInvalidator.install());
            logOnce("external-render-timestamps", ExternalRenderTimestamps.install());
            }
            logOnce("java-player-stall-probe", JavaPlayerStallProbe.install());
            logOnce("perf-sample-offload", PerfSampleOffload.install(classLoader));
        }
        // Installed regardless of the setting, which the hooks read when they fire: under NPatch
        // manager mode the module preferences are unreadable until Bilibili's Application
        // exists, so a load-time check would always see the default.
        if (TARGET_PACKAGE.equals(processName) && (off & 8) == 0) {
            logOnce("replace-story-video", StoryRouteHook.install(classLoader));
            logOnce("speed-menu", SpeedMenuHook.install(classLoader));
            logOnce("player-settings-panel",
                    PlayerSettingPanelHook.install(classLoader));
        }
        if ((off & 16) == 0) {
            hookIjkDisplayFrameCounter(classLoader);
            hookFactory(classLoader);
            hookRenderContainer(classLoader);
            hookPlaybackActivityLifecycle(classLoader);
        }
    }

    private void hookIjkDisplayFrameCounter(ClassLoader classLoader) {
        try {
            Class<?> ijkPlayerClass = Xp.findClass(IJK_PLAYER, classLoader);
            Class<?> callbackClass = Xp.findClass(IJK_VIDEO_DISPLAY_CALLBACK, classLoader);
            Xp.findAndHookMethod(ijkPlayerClass, "setOnVideoDisplayCallback", callbackClass,
                    new Xp.Hook() {
                        @Override
                        protected void before(Xp.Param param) {
                            // Always counting, so the overlay can be switched on mid-playback.
                            Object delegate = param.args[0];
                            if (delegate != null && Proxy.isProxyClass(delegate.getClass())
                                    && Proxy.getInvocationHandler(delegate)
                                    instanceof PlaybackDebugOverlay.FrameCallbackProxy) {
                                return;
                            }
                            param.args[0] = PlaybackDebugOverlay.frameCallback(
                                    callbackClass, delegate);
                        }
                    });
            Xp.findAndHookMethod(ijkPlayerClass, "start", new Xp.Hook() {
                @Override
                protected void before(Xp.Param param) {
                    try {
                        PlaybackDebugOverlay.resetFrameTiming();
                        Object existingCallback = Xp.getObjectField(
                                param.thisObject, "mOnVideoDisplayCallback");
                        if (existingCallback != null && Proxy.isProxyClass(existingCallback.getClass())
                                && Proxy.getInvocationHandler(existingCallback)
                                instanceof PlaybackDebugOverlay.FrameCallbackProxy) {
                            return;
                        }
                        Xp.callMethod(param.thisObject, "setOnVideoDisplayCallback",
                                PlaybackDebugOverlay.frameCallback(callbackClass, existingCallback));
                    } catch (Throwable throwable) {
                        logOnce("ijk-debug-start", "IJK debug callback unavailable: "
                                + throwable.getClass().getSimpleName());
                    }
                }
            });
            log("hooked IJK video-display callback for debug fps");
            if (ModulePreferences.loadRenderCadenceProbeForHook()) {
                logOnce("p2-render-cadence-probe", RenderCadenceProbe.install());
            } else {
                logOnce("p2-render-cadence-probe-disabled",
                        "P2 render cadence probe disabled by profile");
            }
        } catch (Throwable throwable) {
            log("IJK debug fps hook unavailable: " + throwable.getClass().getSimpleName());
        }
    }

    // Not limited to one device: the native hooks verify libijkplayer's build-id and entry
    // instructions before patching anything.
    private void hookIjkService(ClassLoader classLoader) {
        try {
            if (ModulePreferences.loadPlaybackDebugOverlayForHook()
                    && ModulePreferences.loadRenderCadenceProbeForHook()) {
                logOnce("p2-render-cadence-probe-service",
                        RenderCadenceProbe.install());
            }
            // bisect bits for the ijkservice (2026-11-09, `debug.biliextended.off`): 32 = no native hooks / pictq patch / thread boost, 64 = no MediaCodec hooks,
            // 128 = no playback-clock hooks, 256 = no sync watcher
            final int off = DebugLog.readInt("debug.biliextended.off", 0);
            Class<?> serviceClass = Xp.findClass(IJK_SERVICE, classLoader);
            if ((off & 32) == 0) {
            Xp.findAndHookMethod(serviceClass, "loadLibrariesOnce", Bundle.class,
                    new Xp.Hook() {
                        @Override
                        protected void after(Xp.Param param) {
                            applyExperiments(param.thisObject);
                        }
                    });
            Xp.findAndHookMethod(serviceClass, "onBind", Intent.class,
                    new Xp.Hook() {
                        @Override
                        protected void after(Xp.Param param) {
                            applyExperiments(param.thisObject);
                        }
            });
            }
            if ((off & 64) == 0) {
                logOnce("mediacodec-frame-sync-hooks", Media3FrameSynchronizer.install());
            }
            if ((off & 128) == 0) {
                logOnce("media3-playback-clock-hooks",
                        Media3FrameSynchronizer.installPlaybackClockHooks(classLoader));
            }
            if ((off & 256) == 0) {
                Media3SyncWatcher.start();
            }
            log("hooked IjkMediaPlayerService device=" + Build.MODEL);
        } catch (Throwable throwable) {
            log("IJK refresh-cap hook unavailable: " + throwable.getClass().getSimpleName());
        }
    }

    /**
     * IJK keeps three decoded pictures ahead of display; together with the two buffers
     * SurfaceFlinger holds, that leaves the decoder's output pool almost no slack, and any
     * VSync-aligned release delays the pool by up to a period.  Two pictures are enough for a
     * hardware decoder and give the pool one buffer back.  Option category 4 = player.
     */
    private static final int PICTURE_QUEUE_SIZE = 12;
    /**
     * `debug.biliextended.pictq` (E4/P): unset or anything outside 12..16 keeps the shipped 12; 13..16 deepens the queue.  Read when the IJK player option is set (main process) and when the
     * ijkservice applies its experiments, i.e. once per process start: changing it needs a new Bilibili process (force-stop), not just a new playback.
     */
    private static int pictureQueueSize() {
        int value = DebugLog.readInt("debug.biliextended.pictq", PICTURE_QUEUE_SIZE);
        return PictureQueuePolicy.resolve(value);
    }
    private static final ThreadLocal<Boolean> INTERNAL_OPTION_CALL = new ThreadLocal<Boolean>();

    // Disabled: Bilibili 9.13.0's IJK rejects the option ("Value 2 for parameter
    // 'video-pictq-size' out of range [3 - 16]"), so the three-picture queue cannot be
    // reduced from the Java API.  Kept for the option log, which showed that the app itself
    // only sets start-on-prepared through IjkMediaPlayer.setOption.
    private static final boolean IJK_PICTURE_QUEUE_OVERRIDE = true;

    private void installPictureQueueSizeHook(ClassLoader classLoader) {
        if (!IJK_PICTURE_QUEUE_OVERRIDE || !ModulePreferences.loadMedia3FrameSyncForHook()) {
            return;
        }
        try {
            final Class<?> playerClass = Xp.findClass(IJK_PLAYER, classLoader);
            Xp.Hook optionHook = new Xp.Hook() {
                @Override
                protected void before(Xp.Param param) {
                    try {
                        Xp.callMethod(param.thisObject, "_setOptionLong", 4,
                                "video-pictq-size", 2L);
                        logOnce("ijk-pictq-size:" + param.method.getName(),
                                "IJK video-pictq-size set to 2 before " + param.method.getName());
                    } catch (Throwable throwable) {
                        logOnce("ijk-pictq-size-failed", "IJK video-pictq-size option failed="
                                + throwable.getClass().getSimpleName());
                    }
                }
            };
            StringBuilder names = new StringBuilder();
            for (java.lang.reflect.Method method : playerClass.getDeclaredMethods()) {
                String name = method.getName().toLowerCase(java.util.Locale.ROOT);
                if (name.contains("speed") || name.contains("rate") || name.contains("prepare")
                        || name.contains("option") || name.contains("start")
                        || name.contains("datasource") || name.contains("item")) {
                    names.append(method.getName()).append('(')
                            .append(method.getParameterTypes().length).append(") ");
                }
            }
            logOnce("ijk-player-methods", "IjkMediaPlayer methods: " + names);
            // Bilibili configures the player through setOption(int, String, long/String) before
            // preparing; the first such call per instance is the hook point (prepareAsync and
            // the item path never fired on 9.13.0).
            final java.util.Set<Object> configured = java.util.Collections.newSetFromMap(
                    new java.util.WeakHashMap<Object, Boolean>());
            Xp.Hook firstOptionHook = new Xp.Hook() {
                @Override
                protected void before(Xp.Param param) {
                    if (Boolean.TRUE.equals(INTERNAL_OPTION_CALL.get())) {
                        return;
                    }
                    if (param.args.length == 3 && param.args[1] instanceof String) {
                        String optionName = (String) param.args[1];
                        logOnce("ijk-option:" + param.args[0] + ":" + optionName,
                                "IJK option " + param.args[0] + " " + optionName + "="
                                        + param.args[2]);
                        if ("video-pictq-size".equals(optionName)
                                && param.args[2] instanceof Long) {
                            param.args[2] = (long) pictureQueueSize();
                            logOnce("ijk-pictq-override", "IJK video-pictq-size overridden to "
                                    + pictureQueueSize());
                        }
                    }
                    synchronized (configured) {
                        if (!configured.add(param.thisObject)) {
                            return;
                        }
                    }
                    try {
                        INTERNAL_OPTION_CALL.set(Boolean.TRUE);
                        Xp.callMethod(param.thisObject, "setOption", 4,
                                "video-pictq-size", (long) pictureQueueSize());
                        logOnce("ijk-pictq-size", "IJK video-pictq-size set to "
                                + pictureQueueSize() + " on first setOption");
                    } catch (Throwable throwable) {
                        logOnce("ijk-pictq-size-failed", "IJK video-pictq-size option failed="
                                + throwable.getClass().getSimpleName() + " " + throwable.getMessage());
                    } finally {
                        INTERNAL_OPTION_CALL.set(Boolean.FALSE);
                    }
                }
            };
            int hooked = Xp.hookAllMethods(playerClass, "setOption", firstOptionHook).size();
            // Bilibili may configure through IjkMediaPlayerItem instead; list and hook its
            // option setters as well (the item forwards options to the native player).
            try {
                Class<?> itemClass = Xp.findClass(
                        "tv.danmaku.ijk.media.player.IjkMediaPlayerItem", classLoader);
                StringBuilder itemNames = new StringBuilder();
                for (java.lang.reflect.Method method : itemClass.getDeclaredMethods()) {
                    String name = method.getName().toLowerCase(java.util.Locale.ROOT);
                    if (name.contains("option") || name.contains("prepare") || name.contains("start")
                            || name.contains("datasource") || name.contains("init")) {
                        itemNames.append(method.getName()).append('(')
                                .append(method.getParameterTypes().length).append(") ");
                    }
                }
                logOnce("ijk-item-methods", "IjkMediaPlayerItem methods: " + itemNames);
                hooked += Xp.hookAllMethods(itemClass, "setOption", firstOptionHook).size();
            } catch (Throwable throwable) {
                logOnce("ijk-item-class", "IjkMediaPlayerItem unavailable="
                        + throwable.getClass().getSimpleName());
            }
            for (String name : new String[] {"prepareAsync", "_prepareAsync", "setIjkMediaPlayerItem"}) {
                hooked += Xp.hookAllMethods(playerClass, name, optionHook).size();
            }
            log("hooked IjkMediaPlayer prepare paths for picture queue size=" + hooked);
        } catch (Throwable throwable) {
            log("picture queue size hook unavailable: " + throwable.getClass().getSimpleName());
        }
    }

    static void applyExperiments(Object service) {
        boolean debugOverlay = ModulePreferences.loadPlaybackDebugOverlayForHook();
        boolean blockDrop = ModulePreferences.loadBlockIjkFrameDropForHook();
        boolean blockComputeTargetDelay =
                ModulePreferences.loadComputeTargetDelayBlockedForHook();
        boolean frameQueueWaitRoundUp =
                ModulePreferences.loadFrameQueueWaitRoundUpForHook();
        boolean media3FrameSync = ModulePreferences.loadMedia3FrameSyncForHook();
        if (media3FrameSync) {
            // libijkplayer.so is loaded by the time the service runs; rewrite the option's
            // default and minimum before any ffp is created.  The online detail page never
            // calls IjkMediaPlayer.setOption, so the main-process injection alone left the
            // queue at 3 there.  Log each distinct result so a "not loaded" first attempt
            // does not hide the later success.
            String pictqPatch = NativeRefreshCap.patchPictureQueueRange(pictureQueueSize());
            logOnce("ijk-pictq-range:" + pictqPatch, pictqPatch);
        }
        // Independent of the telemetry hooks; installs once, later calls only refresh the limit.
        // Failure (version mismatch, library not yet bound) leaves Bilibili's own cap in place.
        String speedCap = NativeRefreshCap.installSpeedCapLift(
                ModulePreferences.loadSpeedCapLimitForHook());
        logOnce("ijk-speed-cap-lift:" + speedCap, speedCap);
        if (IjkExperimentActivation.shouldStartNativeHooks(debugOverlay, blockDrop,
                blockComputeTargetDelay, frameQueueWaitRoundUp, media3FrameSync)) {
            logOnce("ijk-refresh-telemetry",
                    NativeRefreshCap.startRefreshTelemetry(rawSamplePath()));
            // Keep frame-drop blocking independent from Media3 timed release so the A/B test can
            // disable PTS/VSync release scheduling without restoring IJK's original drop path.
            logOnce("ijk-frame-drop-block:" + blockDrop,
                    NativeRefreshCap.setIjkFrameDropBlocked(blockDrop));
            logOnce("compute-target-delay-block:" + blockComputeTargetDelay,
                    NativeRefreshCap.setComputeTargetDelayBlocked(blockComputeTargetDelay));
            logOnce("frame-queue-wait-round-up:" + frameQueueWaitRoundUp,
                    NativeRefreshCap.setFrameQueueWaitRoundUp(frameQueueWaitRoundUp));
        }
    }

    private static String rawSamplePath() {
        int userId = android.os.Process.myUid() / 100000;
        File directory = new File("/data/user/" + userId + "/"
                + TARGET_PACKAGE + "/files/bilisurfacebridge");
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            return "";
        }
        // The native sample CSV is removed together with the Java telemetry (user, 2026-11-08: 完全移除 CSV 相關程式).
        // Returning "" tells the native side there is no file to write, so it stops sampling to disk.
        return "";
    }

    private static Class<?> hintedClass(ClassLoader classLoader, String[] hints, String parent) {
        Class<?> parentClass = Xp.findClassIfExists(parent, classLoader);
        if (parentClass == null) {
            return null;
        }
        for (String hint : hints) {
            Class<?> candidate = Xp.findClassIfExists(hint, classLoader);
            if (candidate != null && parentClass.isAssignableFrom(candidate)
                    && !candidate.isInterface()) {
                return candidate;
            }
        }
        return null;
    }

    private void hookFactory(ClassLoader classLoader) {
        try {
            Class<?> legacy = Xp.findClassIfExists(FACTORY, classLoader);
            if (legacy != null) {
                hookFactoryClass(legacy, "b", classLoader);
                return;
            }
            Class<?> hinted = hintedClass(classLoader, FACTORY_HINTS, VIDEO_RENDER_LAYER_FACTORY);
            if (hinted != null) {
                hookFactoryClass(hinted, "onCreate", classLoader);
            } else {
                log("video render factory name hint not found; waiting for runtime discovery");
            }
            // Any other concrete factory is hooked the first time one is constructed.
            Class<?> base = Xp.findClass(VIDEO_RENDER_LAYER_FACTORY, classLoader);
            Xp.hookAllConstructors(base, new Xp.Hook() {
                @Override
                protected void after(Xp.Param param) {
                    Class<?> concrete = param.thisObject.getClass();
                    if (concrete.getName().startsWith("com.bilibili.bililive.")) {
                        return;
                    }
                    hookFactoryClass(concrete, "onCreate", concrete.getClassLoader());
                }
            });
        } catch (Throwable throwable) {
            log("factory hook unavailable: " + throwable.getClass().getSimpleName());
        }
    }

    private void hookFactoryClass(Class<?> factoryClass, String methodName, ClassLoader classLoader) {
        if (!HOOKED_CLASSES.add("factory:" + factoryClass.getName())) {
            return;
        }
        try {
            final Class<?> typeClass = Xp.findClass(RENDER_TYPE, classLoader);
            Xp.findAndHookMethod(factoryClass, methodName,
                    typeClass, new Xp.Hook() {
                @Override
                protected void before(Xp.Param param) {
                    Object requestedType = param.args[0];
                    Object configuredType = configuredRendererType(param.thisObject);
                    // The offline-cache player lives in UnitedPlaylistActivity and uses
                    // the same factory, so the renderer choice applies to both pages.
                    String context = factoryContext(param.thisObject);
                    if (PlaybackActivityScope.isVideoDetailActivity(context)
                            || PlaybackActivityScope.isVideoPlaylistActivity(context)) {
                        ModulePreferences.RendererMode mode = HdrVideoRenderer.effectiveMode(
                                ModulePreferences.loadVideoDetailRendererModeForHook());
                        if (ModulePreferences.loadMinimalMedia3PlayerForHook()) {
                            mode = ModulePreferences.RendererMode.SURFACE;
                        }
                        Object selectedRenderer = Xp.getStaticObjectField(
                                typeClass, mode.bilibiliTypeField);
                        param.args[0] = selectedRenderer;
                        logOnce("detail-renderer:" + mode.name(),
                                "selected video-detail renderer=" + mode.label
                                        + " requested=" + requestedType
                                        + " configured=" + configuredType);
                    } else {
                        logOnce("factory:" + String.valueOf(requestedType) + ':'
                                        + String.valueOf(configuredType) + ':' + context,
                                "kept Bilibili renderer request=" + requestedType
                                        + ", configured=" + configuredType
                                        + ", context=" + context);
                    }
                }

                @Override
                protected void after(Xp.Param param) {
                    Object layer = param.getResult();
                    reportLayer("factory", layer);
                    attachDebugOverlayForPlayback(param.thisObject, layer);
                    if (layer != null) {
                        NativeResolutionOutput.attach(layer);
                    }
                    if (SURFACE_FRAME_RATE_VOTE && layer != null
                            && isVideoPlaybackActivity(factoryContext(param.thisObject))) {
                        voteSurfaceFrameRate(layer);
                    }
                }
            });
            log("hooked video render factory=" + factoryClass.getName());
        } catch (Throwable throwable) {
            HOOKED_CLASSES.remove("factory:" + factoryClass.getName());
            log("factory hook unavailable class=" + factoryClass.getName() + ": "
                    + throwable.getClass().getSimpleName());
        }
    }

    private void hookRenderContainer(final ClassLoader classLoader) {
        try {
            Class<?> legacy = Xp.findClassIfExists(RENDER_CONTAINER, classLoader);
            if (legacy != null) {
                hookRenderContainerClass(legacy, classLoader);
            } else {
                Class<?> hinted = hintedClass(classLoader, RENDER_CONTAINER_HINTS,
                        RENDER_CONTAINER_SERVICE_INTERFACE);
                if (hinted != null) {
                    hookRenderContainerClass(hinted, classLoader);
                } else {
                    log("render container name hint not found; waiting for runtime discovery");
                }
            }
            hookPlayerContainerDiscovery(classLoader);
        } catch (Throwable throwable) {
            log("render-container hook unavailable: " + throwable.getClass().getSimpleName());
        }
    }

    /**
     * Version-independent discovery: every player is a subclass of the unobfuscated
     * {@code tv.danmaku.biliplayerv2.PlayerContainer}, whose public getters return the render
     * and control container services. Their concrete classes are hooked when first seen, and
     * the factory context is recorded directly, so a renamed obfuscated class still works.
     */
    private void hookPlayerContainerDiscovery(final ClassLoader classLoader) {
        Class<?> base = Xp.findClassIfExists(ABSTRACT_PLAYER_CONTAINER, classLoader);
        if (base == null) {
            log("PlayerContainer discovery unavailable: class missing");
            return;
        }
        Xp.hookAllConstructors(base, new Xp.Hook() {
            @Override
            protected void after(Xp.Param param) {
                hookPlayerContainerClass(param.thisObject.getClass(), classLoader);
            }
        });
    }

    /** The player containers whose services were discovered (Bilibili objects): a hot-reloaded generation discovers them again. */
    private static final Map<Object, Boolean> DISCOVERED_CONTAINERS =
            Collections.synchronizedMap(new WeakHashMap<Object, Boolean>());

    private void hookPlayerContainerClass(final Class<?> concrete, final ClassLoader classLoader) {
        if (!HOOKED_CLASSES.add("player-container:" + concrete.getName())) {
            return;
        }
        try {
            int hooked = Xp.hookAllMethods(concrete, "onCreate", new Xp.Hook() {
                @Override
                protected void after(Xp.Param createParam) {
                    DISCOVERED_CONTAINERS.put(createParam.thisObject, Boolean.TRUE);
                    discoverPlayerServices(createParam.thisObject, classLoader);
                }
            }).size();
            log("hooked player container discovery class=" + concrete.getName()
                    + " onCreate=" + hooked);
        } catch (Throwable throwable) {
            log("player container discovery hook failed: "
                    + throwable.getClass().getSimpleName());
        }
    }

    /** Hot reload, old generation: the containers discovered so far. */
    private static java.util.List<Object> exportContainers() {
        synchronized (DISCOVERED_CONTAINERS) {
            return new java.util.ArrayList<Object>(DISCOVERED_CONTAINERS.keySet());
        }
    }

    /** Hot reload, new generation: the hooks that are normally installed when a player is created, for the players that already exist. */
    private int rediscoverContainers(Object handedOver, ClassLoader classLoader) {
        int count = 0;
        if (handedOver instanceof java.util.List) {
            for (Object container : (java.util.List<?>) handedOver) {
                if (container == null) {
                    continue;
                }
                try {
                    hookPlayerContainerClass(container.getClass(), classLoader);
                    DISCOVERED_CONTAINERS.put(container, Boolean.TRUE);
                    discoverPlayerServices(container, classLoader);
                    count++;
                } catch (Throwable throwable) {
                    log("hot reload: player container not rediscovered: " + throwable.getClass().getSimpleName());
                }
            }
        }
        return count;
    }

    private void discoverPlayerServices(Object playerContainer, ClassLoader classLoader) {
        Object renderService = null;
        Object controlService = null;
        try {
            renderService = Xp.callMethod(playerContainer, "getRenderContainerService");
        } catch (Throwable ignored) {
            // Reported below as missing.
        }
        try {
            controlService = Xp.callMethod(playerContainer, "getControlContainerService");
        } catch (Throwable ignored) {
            // Reported below as missing.
        }
        if (renderService != null) {
            hookRenderContainerClass(renderService.getClass(), classLoader);
            PlayerSettingPanelHook.noteRenderService(renderService);
            recordFactoryContext(renderService, playerContainer);
        }
        if (controlService != null) {
            hookControlContainerClass(controlService.getClass());
        }
        logOnce("player-services:" + playerContainer.getClass().getName(),
                "player services discovered container=" + playerContainer.getClass().getName()
                        + " render=" + (renderService == null ? "missing"
                                : renderService.getClass().getName())
                        + " control=" + (controlService == null ? "missing"
                                : controlService.getClass().getName()));
    }

    private static Object fieldOfType(Object owner, String typeName) {
        for (Class<?> type = owner.getClass(); type != null && type != Object.class;
                type = type.getSuperclass()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                Class<?> fieldType = field.getType();
                if (fieldType.isPrimitive()) {
                    continue;
                }
                if (isAssignableToName(fieldType, typeName)) {
                    try {
                        field.setAccessible(true);
                        Object value = field.get(owner);
                        if (value != null) {
                            return value;
                        }
                    } catch (Throwable ignored) {
                        // Try the next matching field.
                    }
                }
            }
        }
        return null;
    }

    private static boolean isAssignableToName(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (current.getName().equals(name)) {
                return true;
            }
            for (Class<?> iface : current.getInterfaces()) {
                if (isAssignableToName(iface, name)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Context playerContainerContext(Object playerContainer) {
        if (playerContainer == null) {
            return null;
        }
        try {
            Object context = Xp.callMethod(playerContainer, "getContext");
            if (context instanceof Context) {
                return (Context) context;
            }
        } catch (Throwable ignored) {
            // Fall back to a Context-typed field.
        }
        Object context = fieldOfType(playerContainer, Context.class.getName());
        return context instanceof Context ? (Context) context : null;
    }

    private void recordFactoryContext(Object renderService, Object playerContainer) {
        Object factory = fieldOfType(renderService, VIDEO_RENDER_LAYER_FACTORY);
        Context context = playerContainerContext(playerContainer);
        if (factory == null || context == null) {
            return;
        }
        String contextName = context.getClass().getName();
        FACTORY_CONTEXTS.put(factory, contextName);
        Activity activity = activityFromContext(context);
        if (activity != null) {
            FACTORY_ACTIVITIES.put(factory, new WeakReference<Activity>(activity));
        }
        if (!contextName.startsWith("com.bilibili.bililive.")) {
            hookFactoryClass(factory.getClass(), "onCreate", factory.getClass().getClassLoader());
        }
        logOnce("factory-context:" + contextName, "player factory context=" + contextName);
    }

    private void hookRenderContainerClass(Class<?> containerClass, ClassLoader classLoader) {
        if (!HOOKED_CLASSES.add("render-container:" + containerClass.getName())) {
            return;
        }
        try {
            Class<?> layerClass = Xp.findClass(RENDER_LAYER, classLoader);
            int layerMethods = 0;
            int bindMethods = 0;
            for (java.lang.reflect.Method method : containerClass.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (java.lang.reflect.Modifier.isStatic(method.getModifiers())
                        || parameters.length != 1) {
                    continue;
                }
                if (method.getReturnType() == void.class && parameters[0] == layerClass) {
                    // The attach method's name is obfuscated and shares this shape with the
                    // detach/replace methods; a layer counts as attached once its view has a
                    // parent after the call, and is reported once per layer instance.
                    Xp.hookMethod(method, new Xp.Hook() {
                        @Override
                        protected void after(Xp.Param param) {
                            Object layer = param.args[0];
                            if (layer == null || param.hasThrowable()) {
                                return;
                            }
                            try {
                                Object view = Xp.callMethod(layer, "getView");
                                if (!(view instanceof View) || ((View) view).getParent() == null) {
                                    return;
                                }
                            } catch (Throwable ignored) {
                                return;
                            }
                            if (ATTACHED_LAYERS.put(layer, Boolean.TRUE) != null) {
                                return;
                            }
                            reportLayer("attached", layer);
                            if (ModulePreferences.loadMedia3FrameSyncForHook()) {
                                PipelineThreadBoost.scheduleRenderThreadBoost();
                                AppVsyncRelay.start();
                            }
                        }
                    });
                    layerMethods++;
                } else if (method.getName().startsWith(BIND_PLAYER_CONTAINER_PREFIX)) {
                    Xp.hookMethod(method, new Xp.Hook() {
                        @Override
                        protected void after(Xp.Param param) {
                            try {
                                recordFactoryContext(param.thisObject, param.args[0]);
                            } catch (Throwable throwable) {
                                log("player-container context unavailable: "
                                        + throwable.getClass().getSimpleName());
                            }
                        }
                    });
                    bindMethods++;
                }
            }
            log("hooked video render container=" + containerClass.getName()
                    + " layerMethods=" + layerMethods + " bindMethods=" + bindMethods);
        } catch (Throwable throwable) {
            HOOKED_CLASSES.remove("render-container:" + containerClass.getName());
            log("render-container hook unavailable class=" + containerClass.getName() + ": "
                    + throwable.getClass().getSimpleName());
        }
    }

    private Object configuredRendererType(Object factory) {
        try {
            Object playerParams = fieldOfType(factory, "tv.danmaku.biliplayerv2.PlayerParamsV2");
            if (playerParams == null) {
                playerParams = Xp.getObjectField(factory, "a");
            }
            try {
                Object config = Xp.callMethod(playerParams, "getConfig");
                return Xp.callMethod(config, "getVideoRenderLayerType");
            } catch (Throwable ignored) {
                Object configuration = Xp.getObjectField(playerParams, "c");
                return Xp.getObjectField(configuration, "l");
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String factoryContext(Object factory) {
        String contextName = FACTORY_CONTEXTS.get(factory);
        return contextName == null ? "unknown" : contextName;
    }

    /**
     * SurfaceFlinger decides the panel mode from the buffer cadence unless the layer votes.
     * The codec-side Surface.setFrameRate() never reaches the BLAST layer from the service
     * process (votes stayed "Heuristic" and the panel flipped to 60 Hz when late frames made
     * the stream look like 60 fps), so vote on the SurfaceView's SurfaceControl here, in the
     * process that owns it.  Only while frame sync is on: the scheduler paces onto the
     * panel's VSync grid, so 120 Hz is wanted for any content rate.
     */
    private void voteSurfaceFrameRate(Object layer) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || !ModulePreferences.loadMedia3FrameSyncForHook()) {
            return;
        }
        final SurfaceView surfaceView = findSurfaceView(layer, 0);
        if (surfaceView == null) {
            logOnce("frame-rate-vote-no-surfaceview:" + layer.getClass().getName(),
                    "no SurfaceView under renderer " + layer.getClass().getName());
            return;
        }
        if (surfaceView.getTag(FRAME_RATE_VOTE_TAG) != null) {
            return;
        }
        surfaceView.setTag(FRAME_RATE_VOTE_TAG, Boolean.TRUE);
        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                applyFrameRateVote(surfaceView);
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                applyFrameRateVote(surfaceView);
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                // The vote dies with the SurfaceControl.
            }
        });
        applyFrameRateVote(surfaceView);
    }

    // Disabled: NX809J's SurfaceFlinger (Android 16, MRR panel) accepted the vote
    // ("ExplicitExactOrMultiple 120 Hz") but then reset it every frame ("Reset layer to
    // ignore explicit vote on MRR") and initiated a display mode change ~88 times per
    // second; the panel effectively ran at 87 Hz.  Leaving the heuristic alone keeps 120 Hz
    // as long as the buffer cadence is right.
    private static final boolean SURFACE_FRAME_RATE_VOTE = false;
    private static final int FRAME_RATE_VOTE_TAG = 0x7f0b5b1d;
    private static final float FRAME_SYNC_PANEL_RATE = 120f;

    private void applyFrameRateVote(SurfaceView surfaceView) {
        try {
            SurfaceControl control = surfaceView.getSurfaceControl();
            if (control == null || !control.isValid()) {
                return;
            }
            new SurfaceControl.Transaction()
                    .setFrameRate(control, FRAME_SYNC_PANEL_RATE,
                            Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                            Surface.CHANGE_FRAME_RATE_ALWAYS)
                    .apply();
            logOnce("frame-rate-vote", "voted " + FRAME_SYNC_PANEL_RATE
                    + " Hz on the video SurfaceView layer");
        } catch (Throwable throwable) {
            logOnce("frame-rate-vote-failed", "SurfaceControl frame-rate vote failed="
                    + throwable.getClass().getSimpleName());
        }
    }

    private static SurfaceView findSurfaceView(Object view, int depth) {
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

    private void reportLayer(String source, Object layer) {
        if (layer == null) {
            return;
        }
        String className = layer.getClass().getName();
        logOnce(source + ':' + className, source + " renderer=" + className);
        if (layer instanceof View) {
            reportDisplay((View) layer, className);
        }
    }

    private void attachDebugOverlayForPlayback(Object factory, Object layer) {
        // The fps overlay is always attached and hides itself when switched off, so the
        // in-player menu can turn it on mid-playback.
        boolean debugOverlayEnabled = true;
        if (!isVideoPlaybackActivity(factoryContext(factory)) || !(layer instanceof View)) {
            return;
        }
        WeakReference<Activity> reference = FACTORY_ACTIVITIES.get(factory);
        Activity activity = reference == null ? null : reference.get();
        if (activity == null) {
            logOnce("debug-overlay:activity-missing",
                    "debug overlay waiting for video-detail Activity");
            return;
        }
        DecoderFormatRelay.installMainProcessReceiver(activity);
        String rendererClass = layer.getClass().getName();
        if (debugOverlayEnabled) {
            PlaybackDebugOverlay.show(activity, rendererClass);
            logOnce("debug-overlay:" + rendererClass,
                    "debug overlay attached renderer=" + rendererClass);
        }
        // The format text is part of the info overlay line; no separate stream label.
    }

    private boolean isVideoPlaybackActivity(String contextName) {
        return PlaybackActivityScope.isVideoPlaybackActivity(contextName);
    }

    /**
     * Attach the stream overlay from playback activity lifecycle. Newer Bilibili versions no
     * longer expose the legacy render-layer factory used by the original attachment path.
     */
    private void hookPlaybackActivityLifecycle(ClassLoader classLoader) {
        // Installed whatever the setting says now: embedded patching cannot read settings this
        // early, and PlaybackStreamOverlay.show() checks the setting each time it runs.
        try {
            Xp.findAndHookMethod(Activity.class, "onPostResume",
                    new Xp.Hook() {
                        @Override
                        protected void after(Xp.Param param) {
                            if (param.thisObject instanceof Activity) {
                                attachStreamOverlayToActivity((Activity) param.thisObject);
                            }
                        }
                    });
            logOnce("stream-activity-hook",
                    "hooked Activity.onPostResume for playback stream overlay");
        } catch (Throwable throwable) {
            log("live stream activity hook unavailable: "
                    + throwable.getClass().getSimpleName());
        }
        installControlContainerVisibilityHook(classLoader);
    }

    private void installControlContainerVisibilityHook(ClassLoader classLoader) {
        if (!REPORTED.add("control-container-service-visibility-hook")) {
            return;
        }
        Class<?> serviceClass = Xp.findClassIfExists(
                CONTROL_CONTAINER_SERVICE, classLoader);
        if (serviceClass == null) {
            serviceClass = hintedClass(classLoader, CONTROL_CONTAINER_HINTS,
                    CONTROL_CONTAINER_SERVICE_INTERFACE);
        }
        if (serviceClass == null) {
            log("control container name hint not found; waiting for runtime discovery");
            return;
        }
        hookControlContainerClass(serviceClass);
    }

    private void hookControlContainerClass(Class<?> serviceClass) {
        if (!HOOKED_CLASSES.add("control-container:" + serviceClass.getName())) {
            return;
        }
        try {
            Xp.Hook visibilityHook = new Xp.Hook() {
                @Override
                protected void after(Xp.Param param) {
                    if (param.hasThrowable()) {
                        return;
                    }
                    Activity activity = activityFromControlContainerService(param.thisObject);
                    if (activity == null || !PlaybackActivityScope.isVideoPlaybackActivity(
                            activity.getClass().getName())) {
                        return;
                    }
                    boolean visible;
                    try {
                        Object isShowing = Xp.callMethod(
                                param.thisObject, "isShowing");
                        visible = isShowing instanceof Boolean
                                ? ((Boolean) isShowing).booleanValue()
                                : "show".equals(param.method.getName());
                    } catch (Throwable ignored) {
                        visible = "show".equals(param.method.getName());
                    }
                    PlaybackStreamOverlay.setPlaybackControlsVisible(activity, visible);
                    logOnce("control-container-state:" + visible + ':'
                                    + activity.getClass().getName(),
                            "Bilibili control container state="
                                    + (visible ? "shown" : "hidden")
                                    + " activity=" + activity.getClass().getName());
                }
            };
            Xp.findAndHookMethod(serviceClass, "show", visibilityHook);
            Xp.findAndHookMethod(serviceClass, "hide", visibilityHook);
            log("hooked Bilibili control container show/hide class="
                    + serviceClass.getName());
        } catch (Throwable throwable) {
            HOOKED_CLASSES.remove("control-container:" + serviceClass.getName());
            log("Bilibili ControlContainerService visibility hook unavailable class="
                    + serviceClass.getName() + ": " + throwable.getClass().getSimpleName());
        }
    }

    private Activity activityFromControlContainerService(Object service) {
        try {
            Object playerContainer = fieldOfType(service, ABSTRACT_PLAYER_CONTAINER);
            if (playerContainer == null) {
                playerContainer = fieldOfType(service, PLAYER_CONTAINER);
            }
            Context context = playerContainerContext(playerContainer);
            if (context != null) {
                Activity activity = activityFromContext(context);
                if (activity != null) {
                    return activity;
                }
            }
        } catch (Throwable ignored) {
            // Fall back to the actual control-container View if the owner context is unavailable.
        }
        try {
            Object controlContainer = fieldOfType(service, View.class.getName());
            if (controlContainer instanceof View) {
                return activityFromContext(((View) controlContainer).getContext());
            }
        } catch (Throwable ignored) {
            // The semantic event is ignored if neither known owner exposes an Activity context.
        }
        return null;
    }

    private void attachStreamOverlayToActivity(Activity activity) {
        PlayerSettingPanelHook.noteActivity(activity);
        String activityName = activity.getClass().getName();
        if (!PlaybackActivityScope.isVideoPlaybackActivity(activityName)) {
            return;
        }
        DecoderFormatRelay.installMainProcessReceiver(activity);
        if (PlaybackActivityScope.isLiveRoomActivity(activityName)) {
            PlaybackStreamOverlay.beginLiveAudioBitrateMeasurement();
        }
        PlaybackStreamOverlay.show(activity);
        logOnce("stream-activity-overlay:" + activityName,
                "stream overlay attached activity=" + activityName);
    }

    private Activity activityFromContext(Context context) {
        while (context instanceof ContextWrapper && !(context instanceof Activity)) {
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) {
                return null;
            }
            context = next;
        }
        return context instanceof Activity ? (Activity) context : null;
    }

    private void reportDisplay(View view, String rendererClass) {
        try {
            Display display = view.getDisplay();
            if (display == null) {
                WindowManager windowManager = (WindowManager) view.getContext()
                        .getSystemService(android.content.Context.WINDOW_SERVICE);
                display = windowManager == null ? null : windowManager.getDefaultDisplay();
            }
            if (display == null) {
                logOnce("display:none", "display unavailable for " + rendererClass);
                return;
            }
            Point appSize = new Point();
            display.getSize(appSize);
            DisplayMetrics realMetrics = new DisplayMetrics();
            display.getRealMetrics(realMetrics);
            Display.Mode mode = display.getMode();
            Display.Mode[] modes = display.getSupportedModes();
            StringBuilder message = new StringBuilder("display renderer=")
                    .append(rendererClass)
                    .append(" app=").append(appSize.x).append('x').append(appSize.y)
                    .append(" real=").append(realMetrics.widthPixels).append('x')
                    .append(realMetrics.heightPixels)
                    .append(" active=").append(mode.getPhysicalWidth()).append('x')
                    .append(mode.getPhysicalHeight()).append('@').append(mode.getRefreshRate())
                    .append(" supportedModes=").append(modes.length)
                    .append(" hdrTypes=");
            if (android.os.Build.VERSION.SDK_INT >= 24
                    && display.getHdrCapabilities() != null) {
                int[] hdrTypes = display.getHdrCapabilities().getSupportedHdrTypes();
                for (int index = 0; index < hdrTypes.length; index++) {
                    if (index > 0) {
                        message.append(',');
                    }
                    message.append(hdrTypes[index]);
                }
            } else {
                message.append("none");
            }
            logOnce("display:" + rendererClass + ':' + appSize.x + 'x' + appSize.y + ':'
                    + realMetrics.widthPixels + 'x' + realMetrics.heightPixels + ':'
                    + mode.getPhysicalWidth() + 'x' + mode.getPhysicalHeight(), message.toString());
        } catch (Throwable throwable) {
            log("display report failed: " + throwable.getClass().getSimpleName());
        }
    }

    private static void logOnce(String key, String message) {
        if (REPORTED.add(key)) {
            log(message);
            if (message.contains("renderer=")) {
                Log.i(TAG, message);
            }
        }
    }

    private static void log(String message) {
        String line = TAG + ": " + message;
        Xp.log(line);
        Log.i(TAG, message);
    }
}
