package dev.xpramt.bilisurfacebridge;

import android.graphics.Point;
import android.view.Display;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Locale;

/**
 * The physical resolution of the built-in screen, where it differs from what the system shows.
 *
 * <p>Some phones (Sony Xperia XZ Premium and other 4K models) drive the UI at 1080p while the
 * panel is 4K. Apps normally see only the 1080p size: {@code wm size} and
 * {@link Display#getRealSize} report it. Two non-root sources still tell the truth:
 * <ul>
 *   <li>the display's supported modes ({@link Display#getSupportedModes()}, API 23+): the XZ
 *       Premium lists a 2160x3840 mode next to the active 1080x1920 one;</li>
 *   <li>the system property {@code sys.display-size} ("3840x2160" there), the one Media3 reads
 *       on Android TV for the same reason.</li>
 * </ul>
 * Sizes are returned as long edge x short edge, so orientation never matters.
 */
final class ScreenPhysicalSize {
    private static final String PROPERTY = "sys.display-size";

    /** A size with the source it came from. */
    static final class Size {
        final int longEdge;
        final int shortEdge;
        final String source;

        Size(int a, int b, String source) {
            this.longEdge = Math.max(a, b);
            this.shortEdge = Math.min(a, b);
            this.source = source;
        }

        long area() {
            return (long) longEdge * shortEdge;
        }

        /** Portrait-first text, e.g. 2160x3840. */
        @Override
        public String toString() {
            return shortEdge + "x" + longEdge;
        }
    }

    private ScreenPhysicalSize() {
    }

    /** What the system tells apps: the size the UI is laid out in. */
    static Size logical(Display display) {
        Point real = new Point();
        display.getRealSize(real);
        return new Size(real.x, real.y, "Display.getRealSize");
    }

    /** The largest mode in the display's mode list (API 23+), or null. */
    static Size largestMode(Display display) {
        Display.Mode[] modes = display.getSupportedModes();
        Size best = null;
        if (modes != null) {
            for (Display.Mode mode : modes) {
                Size size = new Size(mode.getPhysicalWidth(), mode.getPhysicalHeight(),
                        "Display.getSupportedModes");
                if (best == null || size.area() > best.area()) {
                    best = size;
                }
            }
        }
        return best;
    }

    /** {@code sys.display-size} ("WxH"), or null when the property is missing or unreadable. */
    static Size fromProperty() {
        String value = readProperty(PROPERTY);
        if (value == null) {
            return null;
        }
        String[] parts = value.trim().toLowerCase(Locale.ROOT).split("x");
        if (parts.length != 2) {
            return null;
        }
        try {
            return new Size(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()),
                    PROPERTY);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * The physical size: the biggest of the sources that answered, never smaller than the
     * logical size. Where nothing is bigger, the screen has no hidden resolution and this is
     * just the logical size.
     */
    static Size physical(Display display) {
        Size best = logical(display);
        Size mode = largestMode(display);
        if (mode != null && mode.area() > best.area()) {
            best = mode;
        }
        Size property = fromProperty();
        if (property != null && property.area() > best.area()) {
            best = property;
        }
        return best;
    }

    /** True when the panel is bigger than the size the UI is laid out in. */
    static boolean hasHiddenResolution(Display display) {
        return physical(display).area() > logical(display).area();
    }

    /** The display mode (id) whose physical size equals {@code size}, or -1. */
    static int modeIdFor(Display display, Size size) {
        Display.Mode[] modes = display.getSupportedModes();
        if (modes != null) {
            for (Display.Mode mode : modes) {
                if (Math.max(mode.getPhysicalWidth(), mode.getPhysicalHeight()) == size.longEdge
                        && Math.min(mode.getPhysicalWidth(), mode.getPhysicalHeight())
                        == size.shortEdge) {
                    return mode.getModeId();
                }
            }
        }
        return -1;
    }

    /** Multi-line description for the diagnostics card and the log. */
    static String describe(Display display) {
        StringBuilder text = new StringBuilder();
        Size logical = logical(display);
        text.append("App 看到的尺寸：").append(logical).append('\n');
        Display.Mode current = display.getMode();
        text.append("目前顯示模式：id ").append(current.getModeId()).append("　")
                .append(current.getPhysicalWidth()).append('x')
                .append(current.getPhysicalHeight()).append('\n');
        Display.Mode[] modes = display.getSupportedModes();
        text.append("支援的顯示模式：");
        if (modes == null || modes.length == 0) {
            text.append("無");
        } else {
            for (int index = 0; index < modes.length; index++) {
                Display.Mode mode = modes[index];
                text.append(index == 0 ? "" : "、").append("id ").append(mode.getModeId())
                        .append(' ').append(mode.getPhysicalWidth()).append('x')
                        .append(mode.getPhysicalHeight());
            }
        }
        text.append('\n');
        Size property = fromProperty();
        text.append(PROPERTY).append("：").append(property == null ? "沒有" : property)
                .append('\n');
        Size physical = physical(display);
        text.append("物理解析度：").append(physical);
        if (physical.area() > logical.area()) {
            text.append("（比 UI 大，來源 ").append(physical.source).append("）");
        } else {
            text.append("（與 UI 相同，沒有隱藏的解析度）");
        }
        return text.toString();
    }

    private static String readProperty(String key) {
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            Object value = properties.getMethod("get", String.class).invoke(null, key);
            if (value instanceof String && !((String) value).isEmpty()) {
                return (String) value;
            }
        } catch (Throwable ignored) {
            // Hidden API: newer systems may refuse it; the getprop command is the fallback.
        }
        try {
            Process process = new ProcessBuilder("getprop", key).redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                return line == null || line.trim().isEmpty() ? null : line.trim();
            }
        } catch (Throwable ignored) {
            return null;
        }
    }
}
