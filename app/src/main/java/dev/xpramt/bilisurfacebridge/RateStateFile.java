package dev.xpramt.bilisurfacebridge;

import android.app.Application;
import android.os.Process;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * The playback rate the scheduler is really following, published as a small file in Bilibili's own data folder
 * ({@code files/bilisurfacebridge/rate_state_<process>.json}) about once a second and at once when the rate changes.
 *
 * <p>Why a file: the module logged {@code playback rate X via ...} only when the rate CHANGED, so a batch that stayed at 1.0x
 * left nothing to check, and logcat's ring buffer pushes old lines out anyway.  A tool that wants to know "is the scheduler
 * at 1.0x now?" reads this file as root (it needs a live process id and a fresh {@code updated_ms}); no log, no property
 * (an app process may not set {@code debug.*} properties) and no change of any system setting.
 *
 * <p>Fields: process, pid, updated_ms (wall clock), requested_rate (what the scheduler follows), latest_rate (last rate the
 * clients reported), max_rate (IJK's maximum, 0 = unknown), rate_unity / ideal_step_us (from the native status line:
 * rateUnity=1 means the VSync scheduling is on, idealStepUs is the frame step it uses), source (what last changed the rate).
 * The write goes through a temporary file and a rename, on a background thread, so the frame path never waits for storage.
 */
final class RateStateFile {
    private static final String DIR = "bilisurfacebridge";
    private static final long INTERVAL_NS = 1_000_000_000L;
    private static final Pattern UNITY = Pattern.compile("rateUnity=(-?\\d+)");
    private static final Pattern STEP = Pattern.compile("idealStepUs=(-?\\d+)");
    /** Hot reload, old generation: the writer thread ends after what is queued (the next generation has its own). */
    static void shutdownForHotReload() {
        WRITER.shutdown();
    }

    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "bsb-rate-state");
        thread.setDaemon(true);
        return thread;
    });

    private static long lastNs;
    private static String lastSource = "start";
    private static File target;
    private static String processName;

    private RateStateFile() {
    }

    /** Remember what last changed the rate and publish right away. */
    static void onRateChanged(float requested, float latest, String source) {
        synchronized (RateStateFile.class) {
            lastSource = source == null ? "?" : source;
            lastNs = 0L;
        }
        publish(System.nanoTime(), requested, latest);
    }

    /** Publish at most once a second; cheap enough to call from the per-frame report hook. */
    static void publish(long nowNs, float requested, float latest) {
        if (!DebugLog.monitor()) {
            return;     // test-only (user, 2026-11-11): a tool that wants this file switches debug.biliextended.stat on for the length of the test
        }
        final String source;
        synchronized (RateStateFile.class) {
            if (nowNs - lastNs < INTERVAL_NS && lastNs != 0L) {
                return;
            }
            lastNs = nowNs;
            source = lastSource;
        }
        final String nativeStatus = NativeRefreshCap.releaseSchedulingStatus();
        final float max = NativeRefreshCap.observedMaxRate();
        final String json = String.format(Locale.US,
                "{\"process\":\"%s\",\"pid\":%d,\"updated_ms\":%d,\"requested_rate\":%.4f,\"latest_rate\":%.4f,\"max_rate\":%.2f,"
                        + "\"rate_unity\":%s,\"ideal_step_us\":%s,\"source\":\"%s\",\"qti_fence_configs\":%d,\"qti_fence_last\":\"%s\",%s}",
                processName(), Process.myPid(), System.currentTimeMillis(), requested, latest, max,
                find(UNITY, nativeStatus), find(STEP, nativeStatus), source.replace("\"", "'"),
                Media3FrameSynchronizer.qtiFenceConfigs(), Media3FrameSynchronizer.qtiFenceLast().replace("\"", "'"),
                Media3FrameSynchronizer.decodeLatencyJson());
        try {
            WRITER.execute(() -> write(json));
        } catch (Throwable ignored) {
            // executor shut down: nothing to publish to
        }
    }

    private static String find(Pattern pattern, String text) {
        if (text != null) {
            final Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return "null";
    }

    private static String processName() {
        if (processName == null) {
            String name = null;
            try {
                name = (String) Xp.callStaticMethod(
                        Xp.findClass("android.app.ActivityThread", null), "currentProcessName");
            } catch (Throwable ignored) {
                // keep null
            }
            processName = name == null ? "unknown" : name;
        }
        return processName;
    }

    private static File targetFile() {
        if (target == null) {
            try {
                final Application application = (Application) Xp.callStaticMethod(
                        Xp.findClass("android.app.ActivityThread", null), "currentApplication");
                if (application != null) {
                    final String suffix = processName().replaceAll("[^A-Za-z0-9]+", "_");
                    final File dir = new File(application.getFilesDir(), DIR);
                    //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs();
                    target = new File(dir, "rate_state_" + suffix + ".json");
                }
            } catch (Throwable ignored) {
                // the application does not exist yet: try again with the next publish
            }
        }
        return target;
    }

    private static void write(String json) {
        final File file = targetFile();
        if (file == null) {
            return;
        }
        final File temp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        temp.renameTo(file);
    }
}
