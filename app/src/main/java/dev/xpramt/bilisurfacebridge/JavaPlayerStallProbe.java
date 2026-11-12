package dev.xpramt.bilisurfacebridge;

import android.app.Application;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Parcel;
import android.os.Trace;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Diagnostic (2026-11-12): what Bilibili's {@code ijk_java_player} thread is doing when the external render thread waits ~80-107 ms for its
 * frame-available message.  Every first round after a cold start (5 of 5 tablet traces) shows that thread making 5 synchronous binder calls into
 * ijkservice and 2 into system_server ~12-13 s into playback, while the frame-available messages queue behind it.
 *
 * Only installed when the measurement switch ({@code debug.biliextended.stat} / {@code .log}) is on when Bilibili starts, so a normal user never
 * gets it.  On that thread it records:
 * <ul>
 *   <li>every Handler message that ran longer than 10 ms: trace instant {@code bsbJ:<ms>:<handler class>:<callback class>:<what>};</li>
 *   <li>every binder call longer than 2 ms: trace instant {@code bsbB:<ms>:<interface>:<code>} and, for the first 40, the Java stack;</li>
 * </ul>
 * both also appended to {@code files/bilisurfacebridge/stall_probe_main.txt} (logcat is too small to keep them).
 */
final class JavaPlayerStallProbe {
    private static final String THREAD = "ijk_java_player";
    private static final long SLOW_MESSAGE_NS = 10_000_000L;
    private static final long SLOW_BINDER_NS = 2_000_000L;
    private static final int MAX_STACKS = 40;
    private static final ThreadLocal<long[]> STARTS = new ThreadLocal<long[]>() {
        @Override
        protected long[] initialValue() {
            return new long[2];
        }
    };
    private static int stacks;
    private static File file;

    private JavaPlayerStallProbe() {
    }

    static String install() {
        if (!DebugLog.monitor()) {
            return "java player stall probe: off (measurement switch not set at start)";
        }
        StringBuilder result = new StringBuilder("java player stall probe:");
        try {
            Xp.findAndHookMethod(Handler.class, "dispatchMessage", Message.class, new Xp.Hook() {
                @Override
                protected void before(Xp.Param param) {
                    if (THREAD.equals(Thread.currentThread().getName())) {
                        STARTS.get()[0] = System.nanoTime();
                    }
                }

                @Override
                protected void after(Xp.Param param) {
                    if (!THREAD.equals(Thread.currentThread().getName())) {
                        return;
                    }
                    long dur = System.nanoTime() - STARTS.get()[0];
                    if (dur < SLOW_MESSAGE_NS) {
                        return;
                    }
                    Message m = (Message) param.args[0];
                    String cb = m.getCallback() == null ? "-" : m.getCallback().getClass().getName();
                    String line = "bsbJ:" + dur / 1_000_000L + ":" + param.thisObject.getClass().getName() + ":" + cb + ":" + m.what;
                    mark(line);
                    append(line);
                }
            });
            result.append(" Handler.dispatchMessage");
        } catch (Throwable t) {
            result.append(" dispatchMessage-unavailable=").append(t.getClass().getSimpleName());
        }
        try {
            Class<?> proxy = Xp.findClass("android.os.BinderProxy", null);
            Xp.findAndHookMethod(proxy, "transact", int.class, Parcel.class, Parcel.class, int.class, new Xp.Hook() {
                @Override
                protected void before(Xp.Param param) {
                    if (THREAD.equals(Thread.currentThread().getName())) {
                        STARTS.get()[1] = System.nanoTime();
                    }
                }

                @Override
                protected void after(Xp.Param param) {
                    if (!THREAD.equals(Thread.currentThread().getName())) {
                        return;
                    }
                    long dur = System.nanoTime() - STARTS.get()[1];
                    if (dur < SLOW_BINDER_NS) {
                        return;
                    }
                    String iface;
                    try {
                        iface = ((IBinder) param.thisObject).getInterfaceDescriptor();
                    } catch (Throwable t) {
                        iface = "?";
                    }
                    String line = "bsbB:" + dur / 1_000_000L + ":" + iface + ":" + param.args[0];
                    mark(line);
                    StringBuilder sb = new StringBuilder(line);
                    if (stacks < MAX_STACKS) {
                        stacks++;
                        StackTraceElement[] st = new Throwable().getStackTrace();
                        for (int i = 0; i < st.length && i < 40; i++) {
                            String s = st[i].toString();
                            if (s.startsWith("dev.xpramt.") || s.contains("LSPHooker") || s.contains("libxposed")) {
                                continue;
                            }
                            sb.append("\n    at ").append(s);
                        }
                    }
                    append(sb.toString());
                }
            });
            result.append(" BinderProxy.transact");
        } catch (Throwable t) {
            result.append(" transact-unavailable=").append(t.getClass().getSimpleName());
        }
        return result.toString();
    }

    private static void mark(String name) {
        try {
            Trace.beginSection(name.length() > 120 ? name.substring(0, 120) : name);
            Trace.endSection();
        } catch (Throwable ignored) {
        }
    }

    private static synchronized void append(String line) {
        try {
            if (file == null) {
                Application app = (Application) Xp.callStaticMethod(Xp.findClass("android.app.ActivityThread", null), "currentApplication");
                if (app == null) {
                    return;
                }
                File dir = new File(app.getFilesDir(), "bilisurfacebridge");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                file = new File(dir, "stall_probe_main.txt");
            }
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write((System.currentTimeMillis() + " " + System.nanoTime() + " " + line + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }
}
