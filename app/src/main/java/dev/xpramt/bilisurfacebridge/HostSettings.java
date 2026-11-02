package dev.xpramt.bilisurfacebridge;

import android.content.Context;
import android.os.Bundle;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Changes a module setting from inside Bilibili. The module app owns the preferences, so the
 * value goes to its provider; until it comes back through the normal read path (one or two
 * seconds), a local override makes the hooks in this process see the new value immediately.
 */
final class HostSettings {
    private HostSettings() {
    }

    /** {@code value} is a Boolean, an Integer or a String, as the provider whitelist expects. */
    static void set(final String key, final Object value) {
        // A failure here must never take the player down with it.
        try {
            ModulePreferences.setHostOverride(key, value);
        } catch (Throwable throwable) {
            XposedBridge.log("BiliSurfaceBridge: setting " + key + " override failed: "
                    + throwable.getClass().getSimpleName() + " " + throwable.getMessage());
        }
        write(key, value);
    }

    /** Why the provider cannot be reached, for the log (package visibility, not found, ...). */
    private static String diagnose(Context context) {
        try {
            boolean visible = true;
            try {
                context.getPackageManager().getPackageInfo(ModulePreferences.PACKAGE_NAME, 0);
            } catch (Throwable throwable) {
                visible = false;
            }
            boolean provider = context.getPackageManager()
                    .resolveContentProvider(ModulePreferencesProvider.AUTHORITY, 0) != null;
            return "packageVisible=" + visible + " providerResolved=" + provider;
        } catch (Throwable throwable) {
            return "diagnosis failed " + throwable.getClass().getSimpleName();
        }
    }

    /** Only the provider write: for a slider that applies live and saves when released. */
    static void write(final String key, final Object value) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // The application context: the player's own contexts wrap the content
                    // resolver and do not know other apps' providers.
                    Context context = (Context) XposedHelpers.callStaticMethod(
                            XposedHelpers.findClass("android.app.ActivityThread", null),
                            "currentApplication");
                    Bundle extras = new Bundle();
                    if (value instanceof Boolean) {
                        extras.putBoolean(ModulePreferencesProvider.EXTRA_VALUE, (Boolean) value);
                    } else if (value instanceof Integer) {
                        extras.putInt(ModulePreferencesProvider.EXTRA_VALUE, (Integer) value);
                    } else {
                        extras.putString(ModulePreferencesProvider.EXTRA_VALUE,
                                String.valueOf(value));
                    }
                    Bundle result = context.getContentResolver().call(
                            ModulePreferencesProvider.URI, ModulePreferencesProvider.METHOD_SET,
                            key, extras);
                    if (result == null || !result.getBoolean("ok")) {
                        XposedBridge.log("BiliSurfaceBridge: setting " + key + " was not applied");
                    } else {
                        // The module app has it now: it alone is the source again.
                        ModulePreferences.refreshProviderSnapshot();
                        HostOverrideStore.remove(key);
                    }
                } catch (Throwable throwable) {
                    String why = "";
                    try {
                        why = " (" + diagnose((Context) XposedHelpers.callStaticMethod(
                                XposedHelpers.findClass("android.app.ActivityThread", null),
                                "currentApplication")) + ")";
                    } catch (Throwable ignored) {
                        // The diagnosis is only a hint.
                    }
                    XposedBridge.log("BiliSurfaceBridge: setting " + key + " not written to the "
                            + "module app (" + throwable.getClass().getSimpleName() + ")" + why
                            + "; kept inside Bilibili");
                }
            }
        }, "BiliSurfaceBridge-set").start();
    }
}
