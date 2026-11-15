package dev.xpramt.bilisurfacebridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Update check (user 2026-11-15; modelled on RedMagicX's GitHub "releases/latest" check and LS_Augment's skip / remind-later dialog).
 *
 * <p>When Bilibili's main process starts and "自動檢查更新" is on, a background thread asks GitHub for the latest release -- at most once
 * per {@link #CHECK_INTERVAL_MS} since the last successful check -- and compares its tag with this module's version name.  A newer
 * release is offered once per process, on the next Bilibili page that is not a player, in a dialog: 前往下載 (opens the release page in a
 * browser), 跳過此版本 (that tag is not offered again; later ones are), 稍後提醒 (not again for {@link #REMIND_LATER_MS}).  No new
 * version or no network: silent.  "立即檢查" in the Bili Extended panel ignores the interval, the skip and the reminder.
 *
 * <p>State (not a setting, so not in settings.txt): {@code files/bilisurfacebridge/update_state.txt}, {@code key<TAB>value} lines,
 * times as epoch milliseconds.  The request carries only a generic User-Agent.
 */
final class UpdateCheck {
    private static final String TAG = "BiliExtended";
    static final String RELEASES_LATEST_URL = "https://api.github.com/repos/XPRAMT/BiliExtended/releases/latest";
    static final String RELEASES_PAGE_URL = "https://github.com/XPRAMT/BiliExtended/releases";
    static final long CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000;
    static final long REMIND_LATER_MS = 24L * 60 * 60 * 1000;
    private static final int TIMEOUT_MS = 10_000;
    private static final int NOTES_MAX = 1500;
    private static final long SHOW_DELAY_MS = 1500L;

    private static volatile File stateFile;
    private static volatile Release pending;
    private static volatile boolean shownThisProcess;
    private static volatile boolean started;

    private UpdateCheck() {
    }

    static final class Release {
        final String tag;
        final String url;
        final String body;

        Release(String tag, String url, String body) {
            this.tag = tag;
            this.url = url;
            this.body = body;
        }
    }

    /** Main process start (also after a hot reload): the automatic check, in the background. */
    static void start(String dataDir) {
        if (dataDir == null || started) {
            return;
        }
        started = true;
        stateFile = new File(dataDir, "files/bilisurfacebridge/update_state.txt");
        Thread thread = new Thread(() -> {
            try {
                if (!ModulePreferences.loadAutoUpdateCheckForHook()) {
                    return;
                }
                Map<String, String> state = readState();
                long now = System.currentTimeMillis();
                long last = longOf(state.get("last_check_ms"));
                Release release;
                if (now - last >= CHECK_INTERVAL_MS || state.get("latest_tag") == null) {
                    release = fetch();
                    state.put("last_check_ms", String.valueOf(now));
                    state.put("latest_tag", release.tag);
                    state.put("latest_url", release.url);
                    writeState(state);
                    writeNotes(release.body);
                    Xp.log(TAG + ": update check " + stamp(now) + " latest=" + release.tag + " installed=" + BuildConfig.VERSION_NAME);
                } else {
                    release = new Release(state.get("latest_tag"), state.get("latest_url"), readNotes());
                    Xp.log(TAG + ": update check skipped (last " + stamp(last) + ", every 24 h) cached latest=" + release.tag);
                }
                if (!UpdateVersion.isNewer(release.tag, BuildConfig.VERSION_NAME)) {
                    return;
                }
                if (release.tag.equals(state.get("skipped_tag"))) {
                    Xp.log(TAG + ": update " + release.tag + " skipped by the user");
                    return;
                }
                long remind = longOf(state.get("remind_after_ms"));
                if (now < remind) {
                    Xp.log(TAG + ": update " + release.tag + " reminder deferred until " + stamp(remind));
                    return;
                }
                pending = release;
            } catch (Throwable throwable) {
                Xp.log(TAG + ": update check failed=" + throwable.getClass().getSimpleName() + " " + throwable.getMessage());
            }
        }, "BiliExtended-update");
        thread.setDaemon(true);
        thread.start();
    }

    /** From Activity.onPostResume: offer a pending update once, on a page that is not a player. */
    static void onActivityResumed(final Activity activity) {
        final Release release = pending;
        if (release == null || shownThisProcess || activity == null
                || PlaybackActivityScope.isVideoPlaybackActivity(activity.getClass().getName())) {
            return;
        }
        shownThisProcess = true;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!Generation.alive() || activity.isFinishing() || activity.isDestroyed()) {
                shownThisProcess = false;   // try again on the next page
                return;
            }
            pending = null;
            showDialog(activity, release, true);
        }, SHOW_DELAY_MS);
    }

    /** "立即檢查" in the panel: ignores the interval, the skipped version and the reminder. */
    static void checkNow(final Activity activity) {
        Toast.makeText(activity, "正在檢查 Bili Extended 更新…", Toast.LENGTH_SHORT).show();
        Thread thread = new Thread(() -> {
            Release release = null;
            String error = null;
            try {
                release = fetch();
                Map<String, String> state = readState();
                state.put("last_check_ms", String.valueOf(System.currentTimeMillis()));
                state.put("latest_tag", release.tag);
                state.put("latest_url", release.url);
                writeState(state);
                writeNotes(release.body);
            } catch (Throwable throwable) {
                error = throwable.getClass().getSimpleName() + (throwable.getMessage() == null ? "" : "：" + throwable.getMessage());
            }
            final Release found = release;
            final String failure = error;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                if (failure != null) {
                    Toast.makeText(activity, "檢查更新失敗：" + failure, Toast.LENGTH_LONG).show();
                } else if (UpdateVersion.isNewer(found.tag, BuildConfig.VERSION_NAME)) {
                    showDialog(activity, found, false);
                } else {
                    Toast.makeText(activity, "已是最新版本（" + BuildConfig.VERSION_NAME + "）", Toast.LENGTH_SHORT).show();
                }
            });
        }, "BiliExtended-update-now");
        thread.setDaemon(true);
        thread.start();
    }

    private static void showDialog(final Activity activity, final Release release, boolean automatic) {
        try {
            boolean chinese = Locale.getDefault().getLanguage().startsWith("zh");
            String notes = UpdateVersion.notes(release.body, chinese, NOTES_MAX);
            String message = "目前版本：" + BuildConfig.VERSION_NAME + "\n最新版本：" + release.tag.replaceFirst("^[vV]", "")
                    + (notes.isEmpty() ? "" : "\n\n" + notes);
            AlertDialog dialog = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Bili Extended 有新版本")
                    .setMessage(message)
                    .setPositiveButton("前往下載", (d, w) -> {
                        try {
                            String url = release.url != null && release.url.startsWith("https://") ? release.url : RELEASES_PAGE_URL;
                            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        } catch (Throwable throwable) {
                            Toast.makeText(activity, "無法開啟瀏覽器：" + RELEASES_PAGE_URL, Toast.LENGTH_LONG).show();
                        }
                    })
                    .setNeutralButton("跳過此版本", (d, w) -> updateState("skipped_tag", release.tag))
                    .setNegativeButton("稍後提醒", (d, w) ->
                            updateState("remind_after_ms", String.valueOf(System.currentTimeMillis() + REMIND_LATER_MS)))
                    .create();
            dialog.show();
            Xp.log(TAG + ": update dialog shown " + release.tag + (automatic ? " (automatic)" : " (manual)")
                    + " on " + activity.getClass().getName());
        } catch (Throwable throwable) {
            Xp.log(TAG + ": update dialog failed=" + throwable.getClass().getSimpleName() + " " + throwable.getMessage());
        }
    }

    // ---- network --------------------------------------------------------------------------------------------------------------------

    private static Release fetch() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(RELEASES_LATEST_URL).openConnection();
        try {
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "BiliExtended-update-check");
            int code = connection.getResponseCode();
            if (code != 200) {
                throw new IllegalStateException("HTTP " + code);
            }
            JSONObject json = new JSONObject(readAll(connection.getInputStream()));
            String tag = json.optString("tag_name", "");
            if (tag.isEmpty()) {
                throw new IllegalStateException("no tag_name");
            }
            return new Release(tag, json.optString("html_url", RELEASES_PAGE_URL), json.optString("body", ""));
        } finally {
            connection.disconnect();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        try (InputStream input = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = input.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                if (out.size() > 1_000_000) {
                    throw new IllegalStateException("response too large");
                }
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    // ---- state ----------------------------------------------------------------------------------------------------------------------

    private static synchronized Map<String, String> readState() {
        Map<String, String> out = new LinkedHashMap<>();
        File file = stateFile;
        if (file == null || !file.isFile()) {
            return out;
        }
        for (String line : readFile(file).split("\n")) {
            int tab = line.indexOf('\t');
            if (tab > 0) {
                out.put(line.substring(0, tab), line.substring(tab + 1));
            }
        }
        return out;
    }

    private static synchronized void writeState(Map<String, String> state) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> e : state.entrySet()) {
            if (e.getValue() != null) {
                out.append(e.getKey()).append('\t').append(e.getValue().replace('\n', ' ')).append('\n');
            }
        }
        writeFile(stateFile, out.toString());
    }

    private static void updateState(String key, String value) {
        new Thread(() -> {
            Map<String, String> state = readState();
            state.put(key, value);
            writeState(state);
            Xp.log(TAG + ": update state " + key + "=" + value);
        }, "BiliExtended-update-state").start();
    }

    private static File notesFile() {
        File file = stateFile;
        return file == null ? null : new File(file.getParentFile(), "update_notes.txt");
    }

    private static void writeNotes(String body) {
        writeFile(notesFile(), body == null ? "" : body);
    }

    private static String readNotes() {
        File file = notesFile();
        return file != null && file.isFile() ? readFile(file) : "";
    }

    private static String readFile(File file) {
        try (InputStream in = new FileInputStream(file)) {
            return readAll(in);
        } catch (Throwable throwable) {
            return "";
        }
    }

    private static void writeFile(File file, String text) {
        if (file == null) {
            return;
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            return;
        }
        File temp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable throwable) {
            temp.delete();
            return;
        }
        if (!temp.renameTo(file)) {
            temp.delete();
        }
    }

    private static long longOf(String value) {
        try {
            return value == null ? 0L : Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** Wall-clock time with its zone offset, for the log. */
    private static String stamp(long ms) {
        return ms <= 0 ? "never" : new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT).format(new Date(ms));
    }
}
