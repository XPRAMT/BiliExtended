package dev.xpramt.bilisurfacebridge;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Finds Bilibili's obfuscated classes by their SHAPE in the APK's dex tables instead of by name (2026-11-13: 9.19.0 moved
 * {@code com.bilibili.playerbizcommonv2.utils.J} to the top-level {@code q022} and renamed the settings adapter, so every name list broke).
 * Only the dex header tables are read (strings, types, protos, methods, class definitions), never code; the result is a few class names
 * per query, which the hooks then check again with reflection.  One scan reads every classes*.dex once (about 300 MB inflated for 9.19);
 * it is cached per APK file (path, size, modification time) and per {@link #SCAN_VERSION}, so only the first start after a Bilibili
 * update pays it, on a background thread.
 *
 * <p>Queries (descriptors of kept, unobfuscated types only):
 * <ul>
 *   <li>{@link #SPEED_MENU}: classes declaring a method {@code List m(MediaResource, [Integer|boolean|int]...)} (the speed-menu builder;
 *       9.13/9.17/9.18 {@code (MediaResource, Integer, boolean, int)}, 9.19 {@code (MediaResource, Integer, int)}).</li>
 *   <li>{@link #SPEED_ADAPTER}: {@code RecyclerView.Adapter} subclasses declaring {@code void m(float, List)} (the speed-menu rows).</li>
 *   <li>{@link #SETTINGS_ADAPTER}: {@code RecyclerView.Adapter} subclasses declaring {@code void updateData(List)} (the settings dialog).</li>
 * </ul>
 */
final class DexScan {
    /** Raise when a query changes: older cache files are then scanned again. */
    static final int SCAN_VERSION = 1;
    static final String SPEED_MENU = "speed-menu";
    static final String SPEED_ADAPTER = "speed-adapter";
    static final String SETTINGS_ADAPTER = "settings-adapter";

    private static final String MEDIA_RESOURCE = "Lcom/bilibili/lib/media/resource/MediaResource;";
    private static final String LIST = "Ljava/util/List;";
    private static final String INTEGER = "Ljava/lang/Integer;";
    private static final String ADAPTER = "Landroidx/recyclerview/widget/RecyclerView$Adapter;";

    private DexScan() {
    }

    static final class Result {
        final Map<String, TreeSet<String>> classes = new HashMap<>();
        /** How the result was obtained, for the log: "cache" or "scan dex=N ms=M". */
        String how = "";

        List<String> get(String query) {
            TreeSet<String> set = classes.get(query);
            return set == null ? new ArrayList<String>() : new ArrayList<String>(set);
        }

        void add(String query, String className) {
            TreeSet<String> set = classes.get(query);
            if (set == null) {
                set = new TreeSet<>();
                classes.put(query, set);
            }
            set.add(className);
        }

        String summary() {
            StringBuilder out = new StringBuilder(how);
            for (String query : new String[]{SPEED_MENU, SPEED_ADAPTER, SETTINGS_ADAPTER}) {
                out.append(' ').append(query).append('=').append(get(query));
            }
            return out.toString();
        }
    }

    // ---- cache ---------------------------------------------------------------------------------------------------------------------

    private static String key(File apk) {
        return SCAN_VERSION + "|" + apk.getAbsolutePath() + "|" + apk.length() + "|" + apk.lastModified();
    }

    private static File cacheFile(String dataDir) {
        return dataDir == null ? null : new File(dataDir, "files/bilisurfacebridge/dexscan.txt");
    }

    /** The cached result for this APK, or null when there is none or it belongs to another APK / scan version. */
    static Result cached(String dataDir, String sourceDir) {
        File file = cacheFile(dataDir);
        if (file == null || sourceDir == null || !file.isFile()) {
            return null;
        }
        try (InputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int read = 0;
            while (read < bytes.length) {
                int n = in.read(bytes, read, bytes.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            String[] lines = new String(bytes, 0, read, StandardCharsets.UTF_8).split("\n");
            if (lines.length == 0 || !lines[0].equals("key\t" + key(new File(sourceDir)))) {
                return null;
            }
            Result result = new Result();
            result.how = "cache";
            for (int i = 1; i < lines.length; i++) {
                int tab = lines[i].indexOf('\t');
                if (tab > 0) {
                    result.add(lines[i].substring(0, tab), lines[i].substring(tab + 1));
                }
            }
            return result;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static void save(String dataDir, String sourceDir, Result result) {
        File file = cacheFile(dataDir);
        if (file == null || sourceDir == null) {
            return;
        }
        StringBuilder out = new StringBuilder("key\t").append(key(new File(sourceDir))).append('\n');
        for (Map.Entry<String, TreeSet<String>> entry : result.classes.entrySet()) {
            for (String name : entry.getValue()) {
                out.append(entry.getKey()).append('\t').append(name).append('\n');
            }
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            return;
        }
        File temp = new File(file.getPath() + ".tmp" + android.os.Process.myPid());
        try (FileOutputStream stream = new FileOutputStream(temp)) {
            stream.write(out.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            temp.delete();
            return;
        }
        if (!temp.renameTo(file)) {
            temp.delete();
        }
    }

    // ---- scan ----------------------------------------------------------------------------------------------------------------------

    /** Scans every classes*.dex of the APK (in parallel, one ZipFile per worker). */
    static Result scan(String sourceDir) throws Exception {
        long t0 = System.nanoTime();
        final List<String> names = new ArrayList<>();
        try (ZipFile zip = new ZipFile(sourceDir)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.startsWith("classes") && name.endsWith(".dex") && name.indexOf('/') < 0) {
                    names.add(name);
                }
            }
        }
        final Result result = new Result();
        int workers = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            List<Future<Result>> futures = new ArrayList<>();
            for (int w = 0; w < workers; w++) {
                final int worker = w;
                final int count = workers;
                futures.add(pool.submit(() -> {
                    Result partial = new Result();
                    try (ZipFile zip = new ZipFile(sourceDir)) {
                        for (int i = worker; i < names.size(); i += count) {
                            if (!Generation.alive()) {
                                break;
                            }
                            ZipEntry entry = zip.getEntry(names.get(i));
                            scanDex(read(zip, entry), partial);
                        }
                    }
                    return partial;
                }));
            }
            for (Future<Result> future : futures) {
                Result partial = future.get();
                for (Map.Entry<String, TreeSet<String>> entry : partial.classes.entrySet()) {
                    for (String name : entry.getValue()) {
                        result.add(entry.getKey(), name);
                    }
                }
            }
        } finally {
            pool.shutdownNow();
        }
        result.how = "scan dex=" + names.size() + " workers=" + workers + " ms=" + (System.nanoTime() - t0) / 1_000_000L;
        return result;
    }

    private static ByteBuffer read(ZipFile zip, ZipEntry entry) throws IOException {
        long size = entry.getSize();
        if (size <= 0 || size > Integer.MAX_VALUE) {
            throw new IOException("dex size " + size);
        }
        byte[] bytes = new byte[(int) size];
        try (InputStream in = zip.getInputStream(entry)) {
            int read = 0;
            while (read < bytes.length) {
                int n = in.read(bytes, read, bytes.length - read);
                if (n < 0) {
                    throw new IOException("short dex");
                }
                read += n;
            }
        }
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** One dex: header tables only.  Package-private for the host test. */
    static void scanDex(ByteBuffer dex, Result result) {
        final int stringCount = dex.getInt(0x38);
        final int stringOff = dex.getInt(0x3C);
        final int typeCount = dex.getInt(0x40);
        final int typeOff = dex.getInt(0x44);
        final int protoCount = dex.getInt(0x48);
        final int protoOff = dex.getInt(0x4C);
        final int methodCount = dex.getInt(0x58);
        final int methodOff = dex.getInt(0x5C);
        final int classCount = dex.getInt(0x60);
        final int classOff = dex.getInt(0x64);
        Strings strings = new Strings(dex, stringCount, stringOff);
        Types types = new Types(dex, strings, typeCount, typeOff);

        final int tMedia = types.find(MEDIA_RESOURCE);
        final int tList = types.find(LIST);
        final int tInteger = types.find(INTEGER);
        final int tAdapter = types.find(ADAPTER);
        final int tBool = types.find("Z");
        final int tInt = types.find("I");
        final int tFloat = types.find("F");
        final int tVoid = types.find("V");
        final int sUpdateData = strings.find("updateData");
        if (tList < 0) {
            return;
        }

        // protos: which shape each one is
        final boolean[] menuProto = new boolean[protoCount];
        final boolean[] floatListProto = new boolean[protoCount];
        final boolean[] listProto = new boolean[protoCount];
        for (int p = 0; p < protoCount; p++) {
            int base = protoOff + p * 12;
            int ret = dex.getInt(base + 4);
            int paramsOff = dex.getInt(base + 8);
            int n = paramsOff == 0 ? 0 : dex.getInt(paramsOff);
            if (ret == tList && tMedia >= 0 && n >= 2 && n <= 4 && param(dex, paramsOff, 0) == tMedia) {
                boolean ok = true;
                for (int i = 1; i < n; i++) {
                    int t = param(dex, paramsOff, i);
                    if (t != tInteger && t != tBool && t != tInt) {
                        ok = false;
                        break;
                    }
                }
                menuProto[p] = ok;
            }
            if (ret == tVoid && n == 2 && param(dex, paramsOff, 0) == tFloat && param(dex, paramsOff, 1) == tList) {
                floatListProto[p] = true;
            }
            if (ret == tVoid && n == 1 && param(dex, paramsOff, 0) == tList) {
                listProto[p] = true;
            }
        }
        // classes defined in this dex whose superclass is RecyclerView.Adapter
        final java.util.HashSet<Integer> adapters = new java.util.HashSet<>();
        final java.util.HashSet<Integer> defined = new java.util.HashSet<>();
        for (int c = 0; c < classCount; c++) {
            int base = classOff + c * 32;
            int cls = dex.getInt(base);
            defined.add(cls);
            if (tAdapter >= 0 && dex.getInt(base + 8) == tAdapter) {
                adapters.add(cls);
            }
        }
        for (int m = 0; m < methodCount; m++) {
            int base = methodOff + m * 8;
            int cls = dex.getShort(base) & 0xffff;
            int proto = dex.getShort(base + 2) & 0xffff;
            if (proto >= protoCount || !defined.contains(cls)) {
                continue;   // a reference to another dex's method: its own dex reports it
            }
            if (menuProto[proto]) {
                result.add(SPEED_MENU, types.className(cls));
            }
            if (floatListProto[proto] && adapters.contains(cls)) {
                result.add(SPEED_ADAPTER, types.className(cls));
            }
            if (listProto[proto] && sUpdateData >= 0 && dex.getInt(base + 4) == sUpdateData && adapters.contains(cls)) {
                result.add(SETTINGS_ADAPTER, types.className(cls));
            }
        }
    }

    private static int param(ByteBuffer dex, int typeListOff, int index) {
        return dex.getShort(typeListOff + 4 + index * 2) & 0xffff;
    }

    /** The string table: binary search over the sorted strings, decoding only what it touches. */
    private static final class Strings {
        final ByteBuffer dex;
        final int count;
        final int off;

        Strings(ByteBuffer dex, int count, int off) {
            this.dex = dex;
            this.count = count;
            this.off = off;
        }

        String get(int index) {
            int at = dex.getInt(off + index * 4);
            while ((dex.get(at) & 0x80) != 0) {   // skip the uleb128 utf16 length
                at++;
            }
            at++;
            StringBuilder out = new StringBuilder();
            while (true) {
                int a = dex.get(at++) & 0xff;
                if (a == 0) {
                    break;
                }
                if (a < 0x80) {
                    out.append((char) a);
                } else if ((a & 0xe0) == 0xc0) {
                    int b = dex.get(at++) & 0x3f;
                    out.append((char) (((a & 0x1f) << 6) | b));
                } else {
                    int b = dex.get(at++) & 0x3f;
                    int c = dex.get(at++) & 0x3f;
                    out.append((char) (((a & 0x0f) << 12) | (b << 6) | c));
                }
            }
            return out.toString();
        }

        int find(String target) {
            int lo = 0;
            int hi = count - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                int cmp = get(mid).compareTo(target);
                if (cmp == 0) {
                    return mid;
                }
                if (cmp < 0) {
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            return -1;
        }
    }

    /** The type table (sorted by string index). */
    private static final class Types {
        final ByteBuffer dex;
        final Strings strings;
        final int count;
        final int off;

        Types(ByteBuffer dex, Strings strings, int count, int off) {
            this.dex = dex;
            this.strings = strings;
            this.count = count;
            this.off = off;
        }

        int find(String descriptor) {
            int s = strings.find(descriptor);
            if (s < 0) {
                return -1;
            }
            int lo = 0;
            int hi = count - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                int value = dex.getInt(off + mid * 4);
                if (value == s) {
                    return mid;
                }
                if (Integer.compareUnsigned(value, s) < 0) {
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            return -1;
        }

        /** "Lcom/x/Y$Z;" -> "com.x.Y$Z". */
        String className(int type) {
            String descriptor = strings.get(dex.getInt(off + type * 4));
            return descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
        }
    }
}
