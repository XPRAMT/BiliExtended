package dev.xpramt.bilisurfacebridge;


/**
 * Applies a change of the Media3 Vsync setting inside Bilibili's IJK service without restarting
 * it. The setting reaches this process a second or two after the in-player menu changed it, so
 * a daemon thread compares it once a second: switching off stops the native release scheduling
 * (the next timed release would otherwise keep it running), switching on starts the native
 * hooks and the deeper picture queue exactly as service start-up does.
 */
final class Media3SyncWatcher {
    private static final long INTERVAL_MS = 1000L;
    private static boolean started;

    private Media3SyncWatcher() {
    }

    static synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                boolean enabled = ModulePreferences.loadMedia3FrameSyncForHook();
                float speedCap = ModulePreferences.loadSpeedCapLimitForHook();
                while (Generation.alive()) {
                    try {
                        Thread.sleep(INTERVAL_MS);
                        // 高倍速 switched in the in-player menu: the native ceiling follows within a second (0 pauses the lift, Bilibili's own cap again)
                        float cap = ModulePreferences.loadSpeedCapLimitForHook();
                        if (cap != speedCap) {
                            speedCap = cap;
                            Xp.log("BiliExtended: high speed switched while running: " + NativeRefreshCap.installSpeedCapLift(cap));
                        }
                        boolean now = ModulePreferences.loadMedia3FrameSyncForHook();
                        if (now == enabled) {
                            continue;
                        }
                        enabled = now;
                        if (now) {
                            HookEntry.applyExperiments(null);
                        } else {
                            Media3FrameSynchronizer.disableNativeScheduling();
                        }
                        Xp.log("BiliExtended: Media3 Vsync switched "
                                + (now ? "on" : "off") + " while playing");
                    } catch (InterruptedException interrupted) {
                        return;
                    } catch (Throwable ignored) {
                        // Try again at the next change.
                    }
                }
            }
        }, "BiliExtended-vsync-watch");
        thread.setDaemon(true);
        thread.start();
    }
}
