package dev.xpramt.bilisurfacebridge;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Adds a "bilibili Ext" row to Bilibili's in-player settings panel (the right-hand panel of the
 * fullscreen player). Tapping it opens {@link InPlayerSettingsPanel}.
 *
 * <p>How the panel works (PlayerSettingFunctionWidget2): onWidgetShow() decides which row ids go
 * into each card ({8 feedback, 9 edit player} is the last one) and builds every row with
 * O(id, position) where position is TOP / MIDDLE / BOTTOM / TOP_BOTTOM. We hook O(): before the
 * first row of the feedback card we add our row, a copy of the feedback row with another title,
 * icon and click action, and push the original first row down to MIDDLE / BOTTOM so the card
 * keeps its rounded shape.
 *
 * <p>Everything is reflection against the host: the class names are Bilibili's, and the rows
 * are found by shape (int + enum parameters, a List result) rather than by obfuscated names.
 * Any mismatch leaves the panel untouched.
 */
final class PlayerSettingPanelHook {
    private static final String TAG = "BiliSurfaceBridge";
    private static final String WIDGET_CLASS =
            "com.bilibili.playerbizcommonv2.widget.setting.PlayerSettingFunctionWidget2";
    private static final int FEEDBACK_ROW = 8;
    private static final int EDIT_PLAYER_ROW = 9;
    private static final String ROW_TITLE = "bilibili Ext";
    private static final String ROW_ICON = "nut-setting-line@500";

    private static ClassLoader hostLoader;
    private static Method buildRow;
    private static Class<?> positionType;
    private static Class<?> function0Class;
    private static Object unit;

    private PlayerSettingPanelHook() {
    }

    static String install(ClassLoader classLoader) {
        try {
            Class<?> widgetClass = XposedHelpers.findClassIfExists(WIDGET_CLASS, classLoader);
            if (widgetClass == null) {
                return "in-player settings panel not found";
            }
            Method build = null;
            for (Method method : widgetClass.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length == 2 && parameters[0] == int.class
                        && parameters[1].isEnum() && List.class.isAssignableFrom(
                                method.getReturnType())) {
                    build = method;
                    break;
                }
            }
            if (build == null) {
                return "in-player settings panel: row builder not found";
            }
            build.setAccessible(true);
            hostLoader = classLoader;
            buildRow = build;
            positionType = build.getParameterTypes()[1];
            function0Class = classLoader.loadClass("kotlin.jvm.functions.Function0");
            unit = XposedHelpers.getStaticObjectField(
                    classLoader.loadClass("kotlin.Unit"), "INSTANCE");
            XposedBridge.hookMethod(build, new RowBuilderHook());
            return "in-player settings panel hooked (" + build.getName() + ")";
        } catch (Throwable throwable) {
            return "in-player settings panel hook unavailable="
                    + throwable.getClass().getSimpleName();
        }
    }

    /** Puts our row in front of the first row of the feedback card. */
    private static final class RowBuilderHook extends XC_MethodHook {
        private static final String EXTRA_INSERT = "bsb_insert";

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                int id = (Integer) param.args[0];
                if (id != FEEDBACK_ROW && id != EDIT_PLAYER_ROW) {
                    return;
                }
                String position = ((Enum<?>) param.args[1]).name();
                if (position.equals("TOP")) {
                    param.args[1] = position("MIDDLE");
                } else if (position.equals("TOP_BOTTOM")) {
                    param.args[1] = position("BOTTOM");
                } else {
                    return;
                }
                param.setObjectExtra(EXTRA_INSERT, Boolean.TRUE);
            } catch (Throwable throwable) {
                logOnce("before", throwable);
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (!Boolean.TRUE.equals(param.getObjectExtra(EXTRA_INSERT))) {
                return;
            }
            try {
                int id = (Integer) param.args[0];
                // invokeOriginalMethod skips this hook, so the copy keeps the position we ask for.
                List<?> template = (List<?>) XposedBridge.invokeOriginalMethod(
                        buildRow, param.thisObject, new Object[]{id, position("TOP")});
                Object ourRow = template.get(0);
                Object data = XposedHelpers.getObjectField(
                        XposedHelpers.getObjectField(ourRow, "a"), "a");
                final Object widget = param.thisObject;
                XposedHelpers.setObjectField(data, "a", ROW_TITLE);
                XposedHelpers.setObjectField(data, "c", ROW_ICON);
                XposedHelpers.setObjectField(data, "i", function0(() -> openPanel(widget)));
                List<Object> merged = new ArrayList<>();
                merged.add(ourRow);
                merged.addAll((List<?>) param.getResult());
                param.setResult(merged);
            } catch (Throwable throwable) {
                // The original rows were already rebuilt as MIDDLE / BOTTOM; better a slightly
                // square corner than a missing row, so there is nothing to undo.
                logOnce("after", throwable);
            }
        }
    }

    // ---- opening the settings panel ---------------------------------------------------------

    private static void openPanel(final Object widget) {
        try {
            Context context = (Context) XposedHelpers.callMethod(widget, "getMContext");
            while (context instanceof ContextWrapper && !(context instanceof Activity)) {
                context = ((ContextWrapper) context).getBaseContext();
            }
            if (!(context instanceof Activity)) {
                throw new IllegalStateException("no Activity behind the panel context");
            }
            InPlayerSettingsPanel.show((Activity) context, new InPlayerSettingsPanel.Actions() {
                @Override
                public void applyRenderer(ModulePreferences.RendererMode mode) {
                    switchRenderer(widget, mode);
                }
            });
        } catch (Throwable throwable) {
            logOnce("open", throwable);
        }
    }

    /**
     * Switches the live video layer: the player's render container service can recreate it with
     * another type, and our factory hook then resolves to the mode that was just stored.
     */
    private static void switchRenderer(Object widget, ModulePreferences.RendererMode mode) {
        try {
            for (Field field : widget.getClass().getDeclaredFields()) {
                for (Method method : field.getType().getMethods()) {
                    if (!method.getName().equals("setExactlyVideoLayerType")
                            || method.getParameterTypes().length != 1) {
                        continue;
                    }
                    field.setAccessible(true);
                    Object service = field.get(widget);
                    Object target = XposedHelpers.getStaticObjectField(
                            method.getParameterTypes()[0], mode.bilibiliTypeField);
                    Object current = XposedHelpers.callMethod(service, "getCurrentLayerType");
                    if (!target.equals(current)) {
                        XposedHelpers.callMethod(service, "setExactlyVideoLayerType", target);
                    }
                    return;
                }
            }
            logOnce("renderer-service", new IllegalStateException("render service not found"));
        } catch (Throwable throwable) {
            logOnce("renderer", throwable);
        }
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static Object position(String name) {
        for (Object constant : positionType.getEnumConstants()) {
            if (((Enum<?>) constant).name().equals(name)) {
                return constant;
            }
        }
        throw new IllegalArgumentException(name);
    }

    private static Object function0(final Runnable action) {
        return Proxy.newProxyInstance(hostLoader, new Class<?>[]{function0Class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if (method.getDeclaringClass() == Object.class) {
                            return method.getName().equals("toString") ? "bsb-action"
                                    : method.getName().equals("hashCode")
                                    ? System.identityHashCode(proxy) : proxy == args[0];
                        }
                        try {
                            action.run();
                        } catch (Throwable throwable) {
                            logOnce("click", throwable);
                        }
                        return unit;
                    }
                });
    }

    private static final Set<String> LOGGED = new HashSet<>();

    private static void logOnce(String key, Throwable throwable) {
        synchronized (LOGGED) {
            if (!LOGGED.add(key)) {
                return;
            }
        }
        XposedBridge.log(TAG + ": in-player settings panel " + key + " failed: "
                + throwable.getClass().getSimpleName() + " " + throwable.getMessage());
    }
}
