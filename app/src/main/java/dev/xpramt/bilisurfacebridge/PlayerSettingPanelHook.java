package dev.xpramt.bilisurfacebridge;

import android.content.Context;
import android.os.Bundle;
import android.widget.Toast;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Adds a "bilibili Ext" row to Bilibili's in-player settings panel (the right-hand panel of the
 * fullscreen player) and a secondary menu behind it, built from the panel's own row components
 * so both look like the original.
 *
 * <p>How the panel works (PlayerSettingFunctionWidget2, 9.17): onWidgetShow() decides which row
 * ids go into each card ({8 feedback, 9 edit player} is the last one) and builds every row with
 * O(id, position) where position is TOP / MIDDLE / BOTTOM / TOP_BOTTOM; the resulting list goes
 * to the adapter field with updateData(). We hook O(): before the first row of the feedback card
 * we add our row (a copy of the feedback row with another title, icon and click action) and push
 * the original first row down to MIDDLE / BOTTOM so the card keeps its rounded shape. Clicking
 * our row replaces the adapter content with our own list of the same row components; its first
 * row goes back by running onWidgetShow() again.
 *
 * <p>Everything is reflection against the host: the class and field names are Bilibili's, and
 * the rows are found by shape (int + enum parameters, a List result, an adapter with
 * updateData) rather than by obfuscated names. Any mismatch leaves the panel untouched.
 */
final class PlayerSettingPanelHook {
    private static final String TAG = "BiliSurfaceBridge";
    private static final String WIDGET_CLASS =
            "com.bilibili.playerbizcommonv2.widget.setting.PlayerSettingFunctionWidget2";
    private static final String SPACER_CLASS =
            "com.bilibili.playerbizcommonv2.widget.setting.channel.A";
    private static final int FEEDBACK_ROW = 8;
    private static final int EDIT_PLAYER_ROW = 9;
    private static final int SWITCH_TEMPLATE_ROW = 1;
    private static final String ROW_TITLE = "bilibili Ext";
    private static final String ROW_ICON = "nut-setting-line@500";
    private static final String BACK_ICON = "arrow-back-left-line@500";
    private static final int[] MARGIN_PRESETS_DP = {0, 4, 8, 12, 16, 24, 32, 48, 64, 100};

    private static ClassLoader hostLoader;
    private static Method buildRow;
    private static Class<?> positionType;
    private static Class<?> function0Class;
    private static Object unit;
    private static Object stateFlowFacade;

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
            stateFlowFacade = classLoader.loadClass("kotlinx.coroutines.flow.StateFlowKt");
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
                logOnce("panel-before", throwable);
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (!Boolean.TRUE.equals(param.getObjectExtra(EXTRA_INSERT))) {
                return;
            }
            try {
                int id = (Integer) param.args[0];
                Object ourRow = descriptionRow(param.thisObject, id, "TOP");
                if (ourRow == null) {
                    // Could not build ours: put the original first row back where it was.
                    return;
                }
                Object data = rowData(ourRow);
                XposedHelpers.setObjectField(data, "a", ROW_TITLE);
                XposedHelpers.setObjectField(data, "c", ROW_ICON);
                final Object widget = param.thisObject;
                XposedHelpers.setObjectField(data, "i", function0(() -> showMenu(widget)));
                List<Object> merged = new ArrayList<>();
                merged.add(ourRow);
                merged.addAll((List<?>) param.getResult());
                param.setResult(merged);
            } catch (Throwable throwable) {
                logOnce("panel-after", throwable);
            }
        }
    }

    // ---- the secondary menu --------------------------------------------------------------

    private static void showMenu(Object widget) {
        try {
            List<Object> components = new ArrayList<>();
            Object back = descriptionRow(widget, FEEDBACK_ROW, "TOP_BOTTOM");
            Object backData = rowData(back);
            XposedHelpers.setObjectField(backData, "a", ROW_TITLE);
            XposedHelpers.setObjectField(backData, "c", BACK_ICON);
            XposedHelpers.setObjectField(backData, "g", "");
            XposedHelpers.setObjectField(backData, "i", function0(() -> showMain(widget)));
            addSpacer(components, 20);
            components.add(component(back));

            addSpacer(components, 16);
            final ModulePreferences.RendererMode[] modes = ModulePreferences.RendererMode.values();
            Object renderer = descriptionRow(widget, FEEDBACK_ROW, "TOP");
            final Object rendererFlow = newFlow(
                    ModulePreferences.loadVideoDetailRendererModeForHook().label);
            Object rendererData = rowData(renderer);
            XposedHelpers.setObjectField(rendererData, "a", "影片渲染器");
            XposedHelpers.setObjectField(rendererData, "c", "pen-write-square-line@500");
            XposedHelpers.setObjectField(rendererData, "e", rendererFlow);
            XposedHelpers.setObjectField(rendererData, "i", function0(() -> {
                ModulePreferences.RendererMode current =
                        ModulePreferences.loadVideoDetailRendererModeForHook();
                ModulePreferences.RendererMode next = modes[(current.ordinal() + 1) % modes.length];
                setValue(rendererFlow, next.label);
                send(widget, ModulePreferences.KEY_VIDEO_DETAIL_RENDERER_MODE, next.name(), true);
            }));
            components.add(component(renderer));
            components.add(component(switchRow(widget, "Media3 Vsync", "flip-line@500",
                    ModulePreferences.loadMedia3FrameSyncForHook(),
                    ModulePreferences.KEY_MEDIA3_FRAME_SYNC, true, "MIDDLE")));
            components.add(component(switchRow(widget, "高通解碼器輸出 fence", "headset-flash-aduio-line@500",
                    ModulePreferences.loadQtiOutputFenceForHook(),
                    ModulePreferences.KEY_QTI_OUTPUT_FENCE, true, "BOTTOM")));

            addSpacer(components, 16);
            components.add(component(switchRow(widget, "fps 浮層", "playsetting-line@500",
                    ModulePreferences.loadPlaybackDebugOverlayForHook(),
                    ModulePreferences.KEY_PLAYBACK_DEBUG_OVERLAY, false, "TOP")));
            components.add(component(switchRow(widget, "影音格式", "closed-caption-text-square-line@500",
                    ModulePreferences.loadPlaybackStreamOverlayForHook(),
                    ModulePreferences.KEY_PLAYBACK_STREAM_OVERLAY, false, "MIDDLE")));
            Object margin = descriptionRow(widget, FEEDBACK_ROW, "BOTTOM");
            final Object marginFlow = newFlow(marginText(
                    ModulePreferences.loadOverlayTopMarginDpForHook()));
            Object marginData = rowData(margin);
            XposedHelpers.setObjectField(marginData, "a", "fps 浮層距離頂端");
            XposedHelpers.setObjectField(marginData, "c", "arrow-sort-vertical-line@500");
            XposedHelpers.setObjectField(marginData, "e", marginFlow);
            XposedHelpers.setObjectField(marginData, "i", function0(() -> {
                int next = nextMargin(ModulePreferences.loadOverlayTopMarginDpForHook());
                setValue(marginFlow, marginText(next));
                send(widget, ModulePreferences.KEY_OVERLAY_TOP_MARGIN_DP, next, false);
            }));
            components.add(component(margin));

            addSpacer(components, 16);
            components.add(component(switchRow(widget, "自動挑選最佳音質", "headset-audio-line@500",
                    ModulePreferences.loadAutoSelectBestAudioForHook(),
                    ModulePreferences.KEY_AUTO_SELECT_BEST_AUDIO, false, "TOP")));
            components.add(component(switchRow(widget, "直式 Story 改用一般影片頁", "next-line@500",
                    ModulePreferences.loadReplaceStoryVideoForHook(),
                    ModulePreferences.KEY_REPLACE_STORY_VIDEO, false, "BOTTOM")));
            addSpacer(components, 20);

            XposedHelpers.callMethod(XposedHelpers.getObjectField(widget, adapterFieldName(widget)),
                    "updateData", components);
        } catch (Throwable throwable) {
            logOnce("panel-menu", throwable);
            toast(widget, "bilibili Ext 選單無法開啟");
        }
    }

    private static void showMain(Object widget) {
        try {
            XposedHelpers.callMethod(widget, "onWidgetShow");
        } catch (Throwable throwable) {
            logOnce("panel-back", throwable);
        }
    }

    // ---- row building --------------------------------------------------------------------

    /** A copy of the feedback row (title, icon, value text, arrow and click action). */
    private static Object descriptionRow(Object widget, int templateId, String position)
            throws Throwable {
        return firstRow(widget, templateId, position);
    }

    /** A copy of the "skip titles and endings" switch row with our state and action. */
    private static Object switchRow(final Object widget, String title, String icon,
                                    boolean checked, final String key, final boolean needsRestart,
                                    String position) throws Throwable {
        Object row = firstRow(widget, SWITCH_TEMPLATE_ROW, position);
        Object data = rowData(row);
        final Object flow = newFlow(checked);
        XposedHelpers.setObjectField(data, "a", title);
        XposedHelpers.setObjectField(data, "c", icon);
        XposedHelpers.setObjectField(data, "d", flow);
        XposedHelpers.setObjectField(data, "e", function0(() -> {
            boolean next = !Boolean.TRUE.equals(XposedHelpers.callMethod(flow, "getValue"));
            setValue(flow, next);
            send(widget, key, next, needsRestart);
        }));
        return row;
    }

    private static Object firstRow(Object widget, int id, String position) throws Throwable {
        // invokeOriginalMethod skips our own hook, so the template keeps the position we ask for.
        List<?> rows = (List<?>) XposedBridge.invokeOriginalMethod(
                buildRow, widget, new Object[]{id, position(position)});
        return rows.get(0);
    }

    /** The row object (RunningUIComponent) wraps the component in field "a". */
    private static Object component(Object row) {
        return XposedHelpers.getObjectField(row, "a");
    }

    /** The component holds its settings object in field "a". */
    private static Object rowData(Object row) {
        return XposedHelpers.getObjectField(component(row), "a");
    }

    private static void addSpacer(List<Object> components, int heightDp) {
        try {
            Class<?> spacer = hostLoader.loadClass(SPACER_CLASS);
            components.add(spacer.getConstructor(int.class).newInstance(heightDp));
        } catch (Throwable ignored) {
            // The cards just sit closer together.
        }
    }

    private static String adapterFieldName(Object widget) {
        for (java.lang.reflect.Field field : widget.getClass().getDeclaredFields()) {
            for (Method method : field.getType().getMethods()) {
                if (method.getName().equals("updateData") && method.getParameterTypes().length == 1
                        && List.class.isAssignableFrom(method.getParameterTypes()[0])) {
                    return field.getName();
                }
            }
        }
        throw new IllegalStateException("adapter field not found");
    }

    // ---- small helpers -------------------------------------------------------------------

    private static Object position(String name) {
        for (Object constant : positionType.getEnumConstants()) {
            if (((Enum<?>) constant).name().equals(name)) {
                return constant;
            }
        }
        throw new IllegalArgumentException(name);
    }

    private static Object newFlow(Object initial) {
        return XposedHelpers.callStaticMethod(
                (Class<?>) stateFlowFacade, "MutableStateFlow", initial);
    }

    private static void setValue(Object flow, Object value) {
        XposedHelpers.callMethod(flow, "setValue", value);
    }

    private static String marginText(int dp) {
        return dp + " dp";
    }

    private static int nextMargin(int current) {
        for (int preset : MARGIN_PRESETS_DP) {
            if (preset > current) {
                return preset;
            }
        }
        return MARGIN_PRESETS_DP[0];
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
                            logOnce("panel-click", throwable);
                        }
                        return unit;
                    }
                });
    }

    /**
     * The module app owns the preferences, so the write goes to its provider; off the UI thread
     * because the call may have to start the module's process.
     */
    private static void send(final Object widget, final String key, final Object value,
                             final boolean needsRestart) {
        final Context context = (Context) XposedHelpers.callMethod(widget, "getMContext");
        if (needsRestart) {
            toast(widget, "重新開啟 Bilibili 後生效");
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Bundle extras = new Bundle();
                    if (value instanceof Boolean) {
                        extras.putBoolean(ModulePreferencesProvider.EXTRA_VALUE, (Boolean) value);
                    } else if (value instanceof Integer) {
                        extras.putInt(ModulePreferencesProvider.EXTRA_VALUE, (Integer) value);
                    } else {
                        extras.putString(ModulePreferencesProvider.EXTRA_VALUE, String.valueOf(value));
                    }
                    Bundle result = context.getContentResolver().call(
                            ModulePreferencesProvider.URI, ModulePreferencesProvider.METHOD_SET,
                            key, extras);
                    if (result == null || !result.getBoolean("ok")) {
                        XposedBridge.log(TAG + ": setting " + key + " was not applied");
                    }
                } catch (Throwable throwable) {
                    logOnce("panel-send", throwable);
                }
            }
        }, "BiliSurfaceBridge-set").start();
    }

    private static void toast(Object widget, String message) {
        try {
            Context context = (Context) XposedHelpers.callMethod(widget, "getMContext");
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
            // A missing toast is not worth failing for.
        }
    }

    private static final java.util.Set<String> LOGGED = new java.util.HashSet<>();

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
