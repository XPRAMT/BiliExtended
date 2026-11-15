package dev.xpramt.bilisurfacebridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UpdateVersionTest {
    @Test
    public void newer() {
        assertTrue(UpdateVersion.isNewer("v1.5.2", "1.5.1"));
        assertTrue(UpdateVersion.isNewer("v1.6.0", "1.5.9"));
        assertTrue(UpdateVersion.isNewer("v2", "1.9.9"));
        assertTrue(UpdateVersion.isNewer("v1.5.1.1", "1.5.1"));
        assertFalse(UpdateVersion.isNewer("v1.5.1", "1.5.1"));
        assertFalse(UpdateVersion.isNewer("v1.5.0", "1.5.1"));
        assertFalse(UpdateVersion.isNewer("v1.5", "1.5.0"));
        assertFalse(UpdateVersion.isNewer("latest", "1.5.1"));
        assertFalse(UpdateVersion.isNewer(null, "1.5.1"));
    }

    @Test
    public void notesHalves() {
        String body = "> [!WARNING]\n> **需要** 新框架\n\n這份說明是相對於 1.5.0。\n\n### 新增\n\n- **支援 9.19.0**：見 [README](https://x)\n\n---\n\n"
                + "### New\n\n- **Bilibili 9.19.0 support**";
        String zh = UpdateVersion.notes(body, true, 1000);
        assertEquals("需要 新框架\n\n這份說明是相對於 1.5.0。\n\n新增\n\n• 支援 9.19.0：見 README", zh);
        assertEquals("New\n\n• Bilibili 9.19.0 support", UpdateVersion.notes(body, false, 1000));
        assertEquals(10, UpdateVersion.notes(body, true, 10).length());
    }
}
