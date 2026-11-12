package dev.xpramt.bilisurfacebridge;

import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * This generation of the module's code, for hot reload (libxposed API 102).
 *
 * <p>When a new module version is installed while Bilibili runs, the framework asks the running generation to step down
 * (HookEntry.onHotReloading), removes its hooks and loads the new code in a fresh class loader (HookEntry.onHotReloaded).  The old classes stay
 * alive as long as something references them: Choreographer callbacks, Handler messages, listeners on Bilibili's objects.  So every recurring loop
 * of the module checks {@link #alive()} and stops rescheduling itself once this generation retired, and what the next generation needs (the
 * decoders that are playing, the playback rate, the overlay text) is handed over as framework / Bilibili objects only -- never instances of the
 * module's own classes, which would keep the old class loader alive.
 */
final class Generation {
    private static volatile boolean retired;

    private Generation() {
    }

    static boolean alive() {
        return !retired;
    }

    static void retire() {
        retired = true;
    }

    /** Runs on the main thread and waits for it (at most `timeoutMs`): views may only be touched there. */
    static void onMainThread(Runnable action, long timeoutMs) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runQuietly(action);
            return;
        }
        CountDownLatch done = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            runQuietly(action);
            done.countDown();
        });
        try {
            done.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    static void runQuietly(Runnable action) {
        try {
            action.run();
        } catch (Throwable throwable) {
            Xp.log("BiliExtended: hot reload step failed: " + throwable);
        }
    }

    /**
     * The volatile static String / int / long / float fields of `type` (display state such as the stream overlay's labels), for the next generation.
     * Booleans are left out on purpose: those are "installed" / "started" flags that the new generation must set itself.
     */
    static Map<String, Object> exportLabels(Class<?> type) {
        Map<String, Object> values = new HashMap<>();
        for (Field field : type.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            Class<?> t = field.getType();
            if (!Modifier.isStatic(modifiers) || !Modifier.isVolatile(modifiers) || Modifier.isFinal(modifiers)
                    || !(t == String.class || t == int.class || t == long.class || t == float.class)) {
                continue;
            }
            try {
                field.setAccessible(true);
                values.put(field.getName(), field.get(null));
            } catch (Throwable ignored) {
                // skip it
            }
        }
        return values;
    }

    static void importLabels(Class<?> type, Object exported) {
        if (!(exported instanceof Map)) {
            return;
        }
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) exported).entrySet()) {
            try {
                Field field = type.getDeclaredField(String.valueOf(entry.getKey()));
                if (Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers())) {
                    field.setAccessible(true);
                    field.set(null, entry.getValue());
                }
            } catch (Throwable ignored) {
                // the field is gone or changed type in the new version
            }
        }
    }
}
