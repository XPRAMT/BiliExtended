package dev.xpramt.bilisurfacebridge;

import android.app.Application;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The module's settings: one file in Bilibili's own data folder, {@code files/bilisurfacebridge/settings.txt}, one {@code key<TAB>value} per line.
 * Only the in-player Bili Extended menu writes it; the module app stores nothing (user, 2026-11-11).  A key that is not in the file means "program
 * default" (the DEFAULT_ constants of ModulePreferences).
 *
 * <p>Bilibili's processes (main, ijkservice) share the uid but not memory, so every one of them reads the file, re-checking its modification time at
 * most once a second; reads on the hot path cost a map lookup.  Writes are batched (200 ms) and replace the file atomically (write + rename).
 *
 * <p>First start after the update: the file does not exist yet, so the values the in-player menu had kept in {@code overrides.txt} (until module 207
 * the only values that reached Bilibili on the phone) are imported once.
 */
final class BiliSettings {
    private static final String TAG = "BiliExtended";
    private static final long CHECK_INTERVAL_NS = 1_000_000_000L;
    private static final long FLUSH_DELAY_MS = 200L;
    private static final String DIR = "bilisurfacebridge";
    private static final String FILE_NAME = "settings.txt";
    private static final String LEGACY_OVERRIDES = "overrides.txt";

    private static final Object LOCK = new Object();
    private static final ScheduledExecutorService FLUSHER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "BiliExtended-settings");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile File filesDir;
    private static Map<String, String> values = Collections.emptyMap();
    private static long lastCheckNs;
    private static long lastFileModified = -1;
    private static boolean flushPending;
    private static boolean loggedLoad;
    private static volatile boolean legacyImported;
    private static final View VIEW = new View();

    private BiliSettings() {
    }

    /** Bilibili's data folder, known from the package's ApplicationInfo before its Application exists. */
    static void init(String dataDir) {
        if (dataDir != null && filesDir == null) {
            filesDir = new File(dataDir, "files");
        }
    }

    static SharedPreferences view() {
        return VIEW;
    }

    static String get(String key) {
        synchronized (LOCK) {
            refreshLocked();
            return values.get(key);
        }
    }

    static Map<String, String> all() {
        synchronized (LOCK) {
            refreshLocked();
            return new TreeMap<>(values);
        }
    }

    static void put(String key, String value) {
        synchronized (LOCK) {
            refreshLocked();
            Map<String, String> next = new HashMap<>(values);
            next.put(key, value);
            values = next;
            scheduleFlushLocked();
        }
    }

    /** Hot reload, old generation: write what is pending now (the new generation reads the file). */
    static void flushNow() {
        boolean pending;
        synchronized (LOCK) {
            pending = flushPending;
        }
        if (pending) {
            flush();
        }
        FLUSHER.shutdown();   // hot reload: this generation writes no more (its flusher thread ends)
    }

    private static void scheduleFlushLocked() {
        if (!flushPending) {
            flushPending = true;
            try {
                FLUSHER.schedule(BiliSettings::flush, FLUSH_DELAY_MS, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.RejectedExecutionException retired) {
                flushPending = false;   // this generation handed over (hot reload): the new one owns the file
            }
        }
    }

    private static void refreshLocked() {
        long now = System.nanoTime();
        if (flushPending || (lastCheckNs != 0 && now - lastCheckNs < CHECK_INTERVAL_NS)) {
            return;
        }
        lastCheckNs = now;
        File file = file(FILE_NAME);
        if (file == null) {
            return;
        }
        if (!file.isFile()) {
            if (lastFileModified == -1 && importLegacyLocked()) {
                scheduleFlushLocked();
            } else if (lastFileModified > 0) {
                values = Collections.emptyMap();   // removed from outside (a test tool's restore): program defaults again
            }
            lastFileModified = 0;
            return;
        }
        if (file.lastModified() == lastFileModified) {
            return;
        }
        Map<String, String> loaded = read(file, false);
        if (loaded != null) {
            values = loaded;
            lastFileModified = file.lastModified();
            if (!loggedLoad) {
                loggedLoad = true;
                Log.i(TAG, "settings " + file.getPath() + " " + new TreeMap<>(loaded));
            }
        }
    }

    /** The in-player menu's values kept by module 206 and earlier (key, value, module value at the change[, revision]). */
    private static boolean importLegacyLocked() {
        File legacy = file(LEGACY_OVERRIDES);
        if (legacy == null || !legacy.isFile()) {
            return false;
        }
        Map<String, String> imported = read(legacy, true);
        if (imported == null || imported.isEmpty()) {
            return false;
        }
        values = imported;
        legacyImported = true;
        Log.i(TAG, "settings imported from " + legacy.getPath() + " " + new TreeMap<>(imported));
        return true;
    }

    private static Map<String, String> read(File file, boolean legacy) {
        Map<String, String> loaded = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\t", -1);
                if (legacy ? parts.length >= 3 : parts.length == 2) {
                    loaded.put(parts[0], parts[1]);
                }
            }
            return loaded;
        } catch (IOException ignored) {
            return null;   // keep what we have; try again at the next check
        }
    }

    private static void flush() {
        Map<String, String> snapshot;
        synchronized (LOCK) {
            snapshot = new TreeMap<>(values);
        }
        File file = file(FILE_NAME);
        if (file != null) {
            File directory = file.getParentFile();
            if (directory != null && !directory.isDirectory()) {
                directory.mkdirs();
            }
            File temporary = new File(file.getPath() + ".tmp");
            boolean written = false;
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(temporary), StandardCharsets.UTF_8)) {
                for (Map.Entry<String, String> entry : snapshot.entrySet()) {
                    writer.write(entry.getKey() + "\t" + entry.getValue() + "\n");
                }
                written = true;
            } catch (IOException exception) {
                Log.w(TAG, "settings not written: " + exception.getMessage());
                temporary.delete();
            }
            if (written && temporary.renameTo(file)) {
                synchronized (LOCK) {
                    lastFileModified = file.lastModified();
                }
                if (legacyImported) {
                    // Imported once: renamed so no later process imports it again.
                    legacyImported = false;
                    File legacy = file(LEGACY_OVERRIDES);
                    if (legacy != null && legacy.isFile()) {
                        legacy.renameTo(file(LEGACY_OVERRIDES + ".imported"));
                    }
                }
            }
        }
        synchronized (LOCK) {
            flushPending = false;
        }
    }

    private static File file(String name) {
        File dir = filesDir;
        if (dir == null) {
            try {
                Application application = (Application) Class.forName("android.app.ActivityThread")
                        .getMethod("currentApplication").invoke(null);
                if (application != null) {
                    dir = application.getFilesDir();
                    filesDir = dir;
                }
            } catch (Throwable ignored) {
                // Not known yet: defaults until it is.
            }
        }
        return dir == null ? null : new File(new File(dir, DIR), name);
    }

    /** Read-only SharedPreferences over the file, so the ModulePreferences readers keep their shape. */
    private static final class View implements SharedPreferences {
        @Override
        public Map<String, ?> getAll() {
            return all();
        }

        @Override
        public String getString(String key, String defValue) {
            String value = get(key);
            return value == null ? defValue : value;
        }

        @Override
        public Set<String> getStringSet(String key, Set<String> defValues) {
            return defValues;
        }

        @Override
        public int getInt(String key, int defValue) {
            String value = get(key);
            try {
                return value == null ? defValue : Integer.parseInt(value.trim());
            } catch (NumberFormatException ignored) {
                return defValue;
            }
        }

        @Override
        public long getLong(String key, long defValue) {
            String value = get(key);
            try {
                return value == null ? defValue : Long.parseLong(value.trim());
            } catch (NumberFormatException ignored) {
                return defValue;
            }
        }

        @Override
        public float getFloat(String key, float defValue) {
            String value = get(key);
            try {
                return value == null ? defValue : Float.parseFloat(value.trim());
            } catch (NumberFormatException ignored) {
                return defValue;
            }
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            String value = get(key);
            if ("true".equals(value)) {
                return true;
            }
            if ("false".equals(value)) {
                return false;
            }
            return defValue;
        }

        @Override
        public boolean contains(String key) {
            return get(key) != null;
        }

        @Override
        public Editor edit() {
            throw new UnsupportedOperationException("settings are written with BiliSettings.put");
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        }

        @Override
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        }
    }
}
