package dev.xpramt.bilisurfacebridge;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;

import java.lang.reflect.Member;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Moves Bilibili's player performance sampling off the thread that delivers video frames (2026-11-12).
 *
 * <p>{@code IjkMediaPlayer$SomeWorkHandler} runs on the {@code ijk_java_player} HandlerThread, which is ALSO the thread SurfaceTexture's
 * frame-available callback is delivered on in SURFACE_EXTERNAL mode (that callback wakes {@code ijk_ext_render}, which draws the frame).
 * Its message 35, {@code TrySamplePerformance}, reads battery properties and makes a synchronous binder call into ijkservice
 * ({@code IIjkMediaPlayer.SamplePerformance}); measured on the tablet (sp1, module 225 probe): 71-126 ms per run, a few seconds after
 * every playback start and then about every 40 s.  Every frame-available message queued behind it waits, so the render thread sleeps
 * and the screen holds one picture for 80-144 ms (10-17 slots at 120 Hz); 5 of 5 first-round traces show the same 5+2 binder calls.
 *
 * <p>What the samples are used for (9.18.0 decompiled): they are summed into the item's performance statistic
 * ({@code IjkMediaPlayerItem.AddPerformanceSampleValue}); when the item is released, {@code reportPerformanceForSoftStrategy} hands the averaged
 * ijkservice CPU to native as {@code FFP_ITEM_PROP_INT64_NOTIFY_CPU_USAGE} (13) -- only when AV1 / H.266 SOFTWARE decoding is supported; the battery
 * level is kept for {@code GetCurBatteryLevel}.  Nothing else in the player reads them.
 *
 * <p>Mode {@code debug.biliextended.perfsample} (read at each call, cached 2 s):
 * <ul>
 *   <li>2 (default, user 2026-11-12: "直接封鎖 ... 我們不使用軟解"): BLOCK.  No sample is taken, nothing is added to the statistic, and the CPU-usage
 *   notify at release is skipped as well -- with no samples it would report 0 % CPU, a made-up number for the soft-decode strategy, so native gets
 *   nothing (as on a device without software AV1 / H.266).  Message 35 itself keeps running: its 30 s re-post, its counter and the
 *   {@code AbrParamsInterface.setEnableHighExperience()} call every 10th message are untouched.</li>
 *   <li>1: OFFLOAD.  The original method runs unchanged on the module thread {@code bsb_perf_sample}, followed there by the statistic update that
 *   belongs to it (one at a time; a sample that arrives while one is still running is dropped).</li>
 *   <li>0: Bilibili's own behaviour (for A/B).</li>
 * </ul>
 */
final class PerfSampleOffload {
    private static final String TAG = "BiliExtended";
    private static HandlerThread thread;
    private static Handler handler;
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static final ThreadLocal<Boolean> ON_WORKER = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> IN_MSG35 = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> SKIP_ADD = new ThreadLocal<>();
    private static volatile long moved;
    private static volatile long dropped;
    private static volatile long added;
    private static volatile long blocked;

    static int mode() {
        return DebugLog.readInt("debug.biliextended.perfsample", 2);
    }

    private PerfSampleOffload() {
    }

    static String install(ClassLoader classLoader) {
        try {
            Class<?> work = Xp.findClass("tv.danmaku.ijk.media.player.IjkMediaPlayer$SomeWorkHandler", classLoader);
            Class<?> player = Xp.findClass("tv.danmaku.ijk.media.player.IjkMediaPlayer", classLoader);
            Class<?> item = Xp.findClass("tv.danmaku.ijk.media.player.IjkMediaPlayerItem", classLoader);
            Class<?> sample = Xp.findClass("tv.danmaku.ijk.media.player.IjkPerformanceManager$SampleValue", classLoader);
            // Bilibili 9.18.0 (decompiled): handleMessage(35) = re-post 35 in 30 s; TrySamplePerformance(player); player.mItem.AddPerformanceSampleValue(
            // player.mainSamplePerfromance, player.ijkSamplePerfromance); count, and every 10th sample AbrParamsInterface.setEnableHighExperience().
            // The Add right after the sample would read the PREVIOUS sample once the sample runs elsewhere, so in a message-35 call the Add moves to the
            // module thread too and runs right after the sample it belongs to; the re-post, the counter and the ABR call stay where they are.
            Xp.findAndHookMethod(work, "handleMessage", android.os.Message.class, new Xp.Hook() {
                @Override
                protected void before(Xp.Param param) {
                    IN_MSG35.set(((android.os.Message) param.args[0]).what == 35);
                }

                @Override
                protected void after(Xp.Param param) {
                    IN_MSG35.set(Boolean.FALSE);
                    SKIP_ADD.set(Boolean.FALSE);
                }
            });
            Xp.findAndHookMethod(item, "AddPerformanceSampleValue", sample, sample, new Xp.Hook() {
                @Override
                protected void before(Xp.Param param) {
                    if (mode() == 2 || Boolean.TRUE.equals(SKIP_ADD.get())) {
                        SKIP_ADD.set(Boolean.FALSE);
                        param.setResult(null);                    // the module thread adds this sample once it exists
                    }
                }
            });
            Xp.findAndHookMethod(item, "reportPerformanceForSoftStrategy", new Xp.Hook() {
                @Override
                protected void before(Xp.Param param) {
                    if (mode() == 2) {
                        param.setResult(null);                    // no samples: do not report 0 % CPU to the soft-decode strategy
                    }
                }
            });
            Xp.findAndHookMethod(work, "TrySamplePerformance", player, new Xp.Hook() {
                @Override
                protected void before(Xp.Param param) {
                    final int m = mode();
                    if (Boolean.TRUE.equals(ON_WORKER.get()) || m == 0) {
                        return;                                   // the copy we posted, or switched off: run here as Bilibili wrote it
                    }
                    if (m == 2) {
                        param.setResult(null);                    // blocked: no sample (its Add is skipped by the hook above)
                        blocked++;
                        if (blocked == 1 || DebugLog.monitor()) {
                            Xp.log(TAG + ": " + stats() + " (performance sample blocked)");
                        }
                        return;
                    }
                    final Member method = param.method;
                    final Object self = param.thisObject;
                    final Object[] args = param.args.clone();
                    final boolean addAfter = Boolean.TRUE.equals(IN_MSG35.get());
                    param.setResult(null);                        // void: skip it on ijk_java_player
                    if (addAfter) {
                        SKIP_ADD.set(Boolean.TRUE);               // the Add that follows in handleMessage(35) belongs to this sample
                    }
                    if (!BUSY.compareAndSet(false, true)) {
                        dropped++;                                // a sample is still running: drop this one and its Add
                        return;
                    }
                    Handler h = worker();
                    if (h == null) {
                        BUSY.set(false);
                        return;
                    }
                    moved++;
                    h.post(() -> {
                        ON_WORKER.set(Boolean.TRUE);
                        try {
                            if (Generation.alive()) {
                                Xp.invokeOriginalMethod(method, self, args);
                                if (addAfter) {
                                    Object p = args[0];
                                    Object it = p == null ? null : Xp.getObjectField(p, "mItem");
                                    if (it != null) {
                                        Xp.callMethod(it, "AddPerformanceSampleValue", Xp.getObjectField(p, "mainSamplePerfromance"),
                                                Xp.getObjectField(p, "ijkSamplePerfromance"));
                                        added++;
                                    }
                                }
                            }
                        } catch (Throwable t) {
                            Xp.log(TAG + ": performance sample on the module thread failed: " + t);
                        } finally {
                            ON_WORKER.set(Boolean.FALSE);
                            BUSY.set(false);
                            if (DebugLog.monitor()) {
                                Xp.log(TAG + ": " + stats() + " (last sample " + (addAfter ? "message 35, added to the item" : "start of playback") + ")");
                            }
                        }
                    });
                }
            });
            return "performance sampling hooks installed (mode " + mode() + ": 2 block, 1 module thread, 0 Bilibili's own)";
        } catch (Throwable t) {
            return "performance sampling offload unavailable=" + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    private static synchronized Handler worker() {
        if (!Generation.alive()) {
            return null;
        }
        if (handler == null) {
            thread = new HandlerThread("bsb_perf_sample", Process.THREAD_PRIORITY_BACKGROUND);
            thread.start();
            handler = new Handler(thread.getLooper());
        }
        return handler;
    }

    static String stats() {
        return "perf samples blocked=" + blocked + " moved=" + moved + " added=" + added + " dropped=" + dropped;
    }

    /** Hot reload: stop the thread (a sample already running finishes; nothing new is accepted because Generation is retired). */
    static synchronized void shutdownForHotReload() {
        if (thread != null) {
            thread.quitSafely();
            thread = null;
            handler = null;
        }
    }
}
