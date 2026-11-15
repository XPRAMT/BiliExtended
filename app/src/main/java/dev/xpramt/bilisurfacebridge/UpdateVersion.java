package dev.xpramt.bilisurfacebridge;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure rules of the update check: compare a release tag with the installed version name, and turn the release notes into dialog text. */
final class UpdateVersion {
    private static final Pattern NUMBER = Pattern.compile("\\d+");

    private UpdateVersion() {
    }

    /** "v1.5.1" / "1.5.1" / "Bili Extended 1.5.1" -> [1, 5, 1]; empty when there is no number. */
    static List<Integer> parts(String version) {
        List<Integer> out = new ArrayList<>();
        if (version == null) {
            return out;
        }
        Matcher m = NUMBER.matcher(version);
        while (m.find()) {
            try {
                out.add(Integer.parseInt(m.group()));
            } catch (NumberFormatException e) {
                out.add(Integer.MAX_VALUE);
            }
        }
        return out;
    }

    /** True only when `latest` is a strictly higher version than `installed` (missing parts count as 0; unparsable -> false). */
    static boolean isNewer(String latest, String installed) {
        List<Integer> a = parts(latest);
        List<Integer> b = parts(installed);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            int x = i < a.size() ? a.get(i) : 0;
            int y = i < b.size() ? b.get(i) : 0;
            if (x != y) {
                return x > y;
            }
        }
        return false;
    }

    /**
     * The release notes for the dialog: the Chinese half (the notes are "Chinese --- English", per the project's release-note rule) or the
     * English half, without Markdown markup; links keep their text; at most `max` characters.
     */
    static String notes(String body, boolean chinese, int max) {
        if (body == null) {
            return "";
        }
        String text = body.replace("\r\n", "\n");
        String[] halves = text.split("\n-{3,}\\s*\n", 2);
        text = halves.length == 2 ? (chinese ? halves[0] : halves[1]) : text;
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n")) {
            String l = line.trim();
            if (l.matches(">?\\s*\\[!\\w+\\]")) {
                continue;   // GitHub alert marker line
            }
            l = l.replaceFirst("^>\\s?", "");
            l = l.replaceFirst("^#{1,6}\\s*", "");
            l = l.replaceAll("\\*\\*|__|`", "");
            l = l.replaceAll("\\[([^\\]]+)\\]\\([^)]+\\)", "$1");
            l = l.replaceFirst("^[-*]\\s+", "• ");
            if (l.isEmpty() && (out.length() == 0 || out.toString().endsWith("\n\n"))) {
                continue;
            }
            out.append(l).append('\n');
        }
        String result = out.toString().trim();
        if (result.length() > max) {
            result = result.substring(0, Math.max(0, max - 1)).trim() + "…";
        }
        return result;
    }
}
