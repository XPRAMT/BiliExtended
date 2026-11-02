package dev.xpramt.bilisurfacebridge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import java.util.Map;

/**
 * Module preferences for processes that cannot reach this app's files: a snapshot for
 * frameworks that cannot share the XML (NPatch manager mode: XSharedPreferences points at this
 * app's private data directory), and a whitelisted "set" for Bilibili's in-player menu.
 * Only Bilibili and this app may call it.
 */
public final class ModulePreferencesProvider extends ContentProvider {
    static final String AUTHORITY = ModulePreferences.PACKAGE_NAME + ".prefs";
    static final Uri URI = Uri.parse("content://" + AUTHORITY);
    static final String METHOD_SNAPSHOT = "snapshot";
    /** call("set", key, extras{"value": Boolean | String | Integer}): Bilibili's in-player menu. */
    static final String METHOD_SET = "set";
    static final String EXTRA_VALUE = "value";
    private static final String BILIBILI_PACKAGE = "tv.danmaku.bili";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        boolean snapshotRequest = METHOD_SNAPSHOT.equals(method);
        boolean setRequest = METHOD_SET.equals(method);
        if (!snapshotRequest && !setRequest) {
            return null;
        }
        String caller = getCallingPackage();
        if (!BILIBILI_PACKAGE.equals(caller) && !ModulePreferences.PACKAGE_NAME.equals(caller)) {
            return null;
        }
        Context context = getContext();
        if (context == null) {
            return null;
        }
        if (setRequest) {
            Bundle result = new Bundle();
            result.putBoolean("ok", applySetting(context, arg, extras));
            return result;
        }
        Bundle snapshot = new Bundle();
        Map<String, ?> values = context.getSharedPreferences(
                ModulePreferences.PREFS_NAME, Context.MODE_PRIVATE).getAll();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Boolean) {
                snapshot.putBoolean(entry.getKey(), (Boolean) value);
            } else if (value instanceof String) {
                snapshot.putString(entry.getKey(), (String) value);
            } else if (value instanceof Integer) {
                snapshot.putInt(entry.getKey(), (Integer) value);
            } else if (value instanceof Long) {
                snapshot.putLong(entry.getKey(), (Long) value);
            } else if (value instanceof Float) {
                snapshot.putFloat(entry.getKey(), (Float) value);
            }
        }
        return snapshot;
    }

    /** Only the settings the in-player menu offers, with their expected types and ranges. */
    private static boolean applySetting(Context context, String key, Bundle extras) {
        if (key == null || extras == null || !extras.containsKey(EXTRA_VALUE)) {
            return false;
        }
        Object value = extras.get(EXTRA_VALUE);
        if (value instanceof Boolean) {
            boolean enabled = (Boolean) value;
            switch (key) {
                case ModulePreferences.KEY_MEDIA3_FRAME_SYNC:
                    ModulePreferences.setMedia3FrameSyncEnabled(context, enabled);
                    return true;
                case ModulePreferences.KEY_QTI_OUTPUT_FENCE:
                    ModulePreferences.setQtiOutputFenceEnabled(context, enabled);
                    return true;
                case ModulePreferences.KEY_PLAYBACK_DEBUG_OVERLAY:
                    ModulePreferences.setPlaybackDebugOverlayEnabled(context, enabled);
                    return true;
                case ModulePreferences.KEY_PLAYBACK_STREAM_OVERLAY:
                    ModulePreferences.setPlaybackStreamOverlayEnabled(context, enabled);
                    return true;
                case ModulePreferences.KEY_AUTO_SELECT_BEST_AUDIO:
                    ModulePreferences.setAutoSelectBestAudioEnabled(context, enabled);
                    return true;
                case ModulePreferences.KEY_REPLACE_STORY_VIDEO:
                    ModulePreferences.setReplaceStoryVideoEnabled(context, enabled);
                    return true;
                default:
                    return false;
            }
        }
        if (value instanceof String
                && ModulePreferences.KEY_VIDEO_DETAIL_RENDERER_MODE.equals(key)) {
            ModulePreferences.RendererMode mode =
                    ModulePreferences.RendererMode.fromStoredValue((String) value);
            if (mode == null) {
                return false;
            }
            ModulePreferences.setVideoDetailRendererMode(context, mode);
            return true;
        }
        if (value instanceof Integer && ModulePreferences.KEY_OVERLAY_TOP_MARGIN_DP.equals(key)) {
            ModulePreferences.setOverlayTopMarginDp(context, (Integer) value);
            return true;
        }
        return false;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
