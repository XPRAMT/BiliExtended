package dev.xpramt.bilisurfacebridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.Assume;
import org.junit.Test;

/**
 * Runs the dex shape queries over real Bilibili APKs when they are present locally (artifacts/ is not in version control; the test is
 * skipped elsewhere).  Expected classes were read from the decompiled APKs on 2026-11-13.
 */
public class DexScanTest {
    private static DexScan.Result scan(String relative) throws Exception {
        File apk = new File(System.getProperty("user.dir"), "../artifacts/" + relative);
        Assume.assumeTrue("APK not present: " + apk, apk.isFile());
        DexScan.Result result = new DexScan.Result();
        try (ZipFile zip = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.startsWith("classes") || !name.endsWith(".dex") || name.indexOf('/') >= 0) {
                    continue;
                }
                byte[] bytes = new byte[(int) entry.getSize()];
                try (InputStream in = zip.getInputStream(entry)) {
                    int read = 0;
                    while (read < bytes.length) {
                        int n = in.read(bytes, read, bytes.length - read);
                        if (n < 0) {
                            break;
                        }
                        read += n;
                    }
                }
                DexScan.scanDex(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN), result);
            }
        }
        System.out.println(relative + ": " + result.summary());
        return result;
    }

    @Test
    public void bilibili919() throws Exception {
        DexScan.Result result = scan("bili-9.19.0/base.apk");
        assertTrue(result.get(DexScan.SPEED_MENU).contains("q022"));
        assertTrue(result.get(DexScan.SPEED_ADAPTER).contains("jn82"));
        assertTrue(result.get(DexScan.SETTINGS_ADAPTER).contains("com.bilibili.app.gemini.ui.e"));
    }

    @Test
    public void bilibili918() throws Exception {
        DexScan.Result result = scan("bili-9.18.0-arm64-analysis/base.apk");
        assertTrue(result.get(DexScan.SPEED_MENU).contains("com.bilibili.playerbizcommonv2.utils.E"));
        assertTrue(result.get(DexScan.SETTINGS_ADAPTER).contains("com.bilibili.app.gemini.ui.f"));
        assertEquals(false, result.get(DexScan.SPEED_ADAPTER).isEmpty());
    }
}
