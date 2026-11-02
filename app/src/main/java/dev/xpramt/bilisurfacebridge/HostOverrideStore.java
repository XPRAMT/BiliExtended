package dev.xpramt.bilisurfacebridge;

import android.app.Application;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import de.robv.android.xposed.XposedHelpers;

/**
 * Settings changed from inside Bilibili, kept in a file in Bilibili's own data folder.
 *
 * <p>The module app owns the real preferences and Bilibili cannot always reach it (on some
 * phones its provider cannot be found from Bilibili at all), and Bilibili's processes cannot
 * share memory, so a change made in the main process has to reach the IJK service some other
 * way. Every Bilibili process reads this file (re-checked once a second).
 *
 * <p>An entry stores the module's own value at the time of the change. While the module's value
 * is still that one the entry wins; once the module's value differs (the user changed it in the
 * module app, or the provider write went through) the module's value is used again.
 */
final class HostOverrideStore {
    private static final long CHECK_INTERVAL_NS = 1_000_000_000L;
    private static final long FLUSH_DELAY_MS = 200L;
    private static final String FILE_NAME = "bilisurfacebridge/overrides.txt";

    private static final Object LOCK = new Object();
    private static final ScheduledExecutorService FLUSHER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "BiliSurfaceBridge-overrides");
                thread.setDaemon(true);
                return thread;
            });
    private static Map<String, String[]> entries = new HashMap<>();
    private static long lastCheckNs;
    private static long lastFileModified;
    private static boolean flushPending;

    private HostOverrideStore() {
    }

    /** {entry value, module value when it was set}, or null. */
    static String[] get(String key) {
        synchronized (LOCK) {
            refreshLocked();
            return entries.get(key);
        }
    }

    static void put(String key, String value, String moduleValue) {
        synchronized (LOCK) {
            refreshLocked();
            entries.put(key, new String[]{value, moduleValue});
            if (!flushPending) {
                flushPending = true;
                FLUSHER.schedule(new Runnable() {
                    @Override
                    public void run() {
                        flush();
                    }
                }, FLUSH_DELAY_MS, TimeUnit.MILLISECONDS);
            }
        }
    }

    private static void refreshLocked() {
        long now = System.nanoTime();
        if (flushPending || now - lastCheckNs < CHECK_INTERVAL_NS) {
            return;
        }
        lastCheckNs = now;
        File file = file();
        if (file == null || !file.isFile() || file.lastModified() == lastFileModified) {
            return;
        }
        Map<String, String[]> loaded = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\t", -1);
                if (parts.length == 3) {
                    loaded.put(parts[0], new String[]{parts[1], parts[2]});
                }
            }
            entries = loaded;
            lastFileModified = file.lastModified();
        } catch (IOException ignored) {
            // Keep what we have; try again at the next check.
        }
    }

    private static void flush() {
        Map<String, String[]> snapshot;
        synchronized (LOCK) {
            snapshot = new HashMap<>(entries);
        }
        File file = file();
        if (file != null) {
            File directory = file.getParentFile();
            if (directory != null && !directory.isDirectory()) {
                directory.mkdirs();
            }
            File temporary = new File(file.getPath() + ".tmp");
            try (Writer writer = new OutputStreamWriter(
                    new FileOutputStream(temporary), StandardCharsets.UTF_8)) {
                for (Map.Entry<String, String[]> entry : snapshot.entrySet()) {
                    writer.write(entry.getKey() + "\t" + entry.getValue()[0] + "\t"
                            + entry.getValue()[1] + "\n");
                }
            } catch (IOException ignored) {
                temporary.delete();
                synchronized (LOCK) {
                    flushPending = false;
                }
                return;
            }
            if (temporary.renameTo(file)) {
                synchronized (LOCK) {
                    lastFileModified = file.lastModified();
                }
            }
        }
        synchronized (LOCK) {
            flushPending = false;
        }
    }

    private static File file() {
        try {
            Application application = (Application) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentApplication");
            return application == null ? null : new File(application.getFilesDir(), FILE_NAME);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
