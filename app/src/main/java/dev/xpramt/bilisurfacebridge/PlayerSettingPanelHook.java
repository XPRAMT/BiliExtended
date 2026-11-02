package dev.xpramt.bilisurfacebridge;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Rect;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
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
    /** The original panel's list view: its bounds are the panel without the shadow. */
    private static WeakReference<View> content;

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
            for (Method method : widgetClass.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length == 1 && parameters[0] == Context.class
                        && View.class.isAssignableFrom(method.getReturnType())) {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.getResult() instanceof View) {
                                content = new WeakReference<>((View) param.getResult());
                            }
                        }
                    });
                    break;
                }
            }
            return "in-player settings panel hooked (" + build.getName() + "); "
                    + SettingsDialogEntry.install(classLoader) + "; "
                    + SettingsPageEntry.install(classLoader);
        } catch (Throwable throwable) {
            return "in-player settings panel hook unavailable="
                    + throwable.getClass().getSimpleName();
        }
    }

    /** The newest render container service seen: the dialog entry has no widget to ask. */
    private static WeakReference<Object> renderService;

    static void noteRenderService(Object service) {
        renderService = new WeakReference<>(service);
    }

    private static WeakReference<Activity> topActivity;
    private static WeakReference<android.app.Dialog> lastDialog;

    /** Called for every Activity that resumes; the bottom-sheet entry needs one to show on. */
    static void noteActivity(Activity activity) {
        topActivity = new WeakReference<>(activity);
    }

    /**
     * The same entry on Bilibili's own Settings page: a row between "Playback settings" and
     * "Download settings". The page is an AndroidX PreferenceFragmentCompat (tablet:
     * WideBiliPreferencesFragment); after it sets its PreferenceScreen we add a preference that
     * looks like the "PlaySetting" one, ordered right after it.
     */
    private static final class SettingsPageEntry {
        private static final String ANCHOR_KEY = "PlaySetting";
        private static final String OUR_KEY = "bsb_ext";
        /** The Settings page fragment: its list's bounds are the area the panel fills. */
        private static WeakReference<Object> page;

        private static Rect listBounds() {
            try {
                Object fragment = page == null ? null : page.get();
                View list = fragment == null ? null
                        : (View) XposedHelpers.callMethod(fragment, "getListView");
                if (list == null || !list.isAttachedToWindow() || list.getWidth() <= 0) {
                    return null;
                }
                int[] location = new int[2];
                list.getLocationOnScreen(location);
                Rect bounds = new Rect(location[0], location[1], location[0] + list.getWidth(),
                        location[1] + list.getHeight());
                // The list sits inside the page with a margin on each side (the cards of the
                // original rows are inset); the panel takes the page's full width.
                // The first container above the list that is wider than it.
                View wider = list;
                while (wider.getParent() instanceof View) {
                    wider = (View) wider.getParent();
                    if (wider.getWidth() > list.getWidth()) {
                        int[] widerLocation = new int[2];
                        wider.getLocationOnScreen(widerLocation);
                        bounds.left = widerLocation[0];
                        bounds.right = widerLocation[0] + wider.getWidth();
                        break;
                    }
                }
                return bounds;
            } catch (Throwable throwable) {
                logOnce("settings-page-bounds", throwable);
                return null;
            }
        }

        static String install(ClassLoader loader) {
            try {
                Class<?> fragment = XposedHelpers.findClassIfExists(
                        "androidx.preference.PreferenceFragmentCompat", loader);
                if (fragment == null) {
                    return "settings page entry: PreferenceFragmentCompat not found";
                }
                int hooked = XposedBridge.hookAllMethods(fragment, "setPreferenceScreen",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    page = new WeakReference<>(param.thisObject);
                                    insert(param.args.length > 0 ? param.args[0] : null);
                                } catch (Throwable throwable) {
                                    logOnce("settings-page", throwable);
                                }
                            }
                        }).size();
                return "settings page entry hooked=" + hooked;
            } catch (Throwable throwable) {
                return "settings page entry unavailable=" + throwable.getClass().getSimpleName();
            }
        }

        private static void insert(Object screen) throws Throwable {
            if (screen == null) {
                return;
            }
            Object anchor = XposedHelpers.callMethod(screen, "findPreference", ANCHOR_KEY);
            if (anchor == null || XposedHelpers.callMethod(screen, "findPreference", OUR_KEY) != null) {
                return;
            }
            Object group = XposedHelpers.callMethod(anchor, "getParent");
            if (group == null) {
                return;
            }
            Context context = (Context) XposedHelpers.callMethod(anchor, "getContext");
            int anchorOrder = (Integer) XposedHelpers.callMethod(anchor, "getOrder");
            Object ours;
            try {
                ours = XposedHelpers.newInstance(anchor.getClass(), context);
            } catch (Throwable throwable) {
                ours = XposedHelpers.newInstance(XposedHelpers.findClass(
                        "androidx.preference.Preference", anchor.getClass().getClassLoader()),
                        context);
            }
            XposedHelpers.callMethod(ours, "setKey", OUR_KEY);
            XposedHelpers.callMethod(ours, "setTitle", (CharSequence) ROW_TITLE);
            XposedHelpers.callMethod(ours, "setPersistent", false);
            XposedHelpers.callMethod(ours, "setLayoutResource",
                    XposedHelpers.callMethod(anchor, "getLayoutResource"));
            XposedHelpers.callMethod(ours, "setWidgetLayoutResource",
                    XposedHelpers.callMethod(anchor, "getWidgetLayoutResource"));
            // The listener interface's name is obfuscated: take its type from the setter.
            Method setter = null;
            for (Method method : ours.getClass().getMethods()) {
                if (method.getName().equals("setOnPreferenceClickListener")
                        && method.getParameterTypes().length == 1) {
                    setter = method;
                    break;
                }
            }
            if (setter == null) {
                throw new IllegalStateException("click listener setter not found");
            }
            final Class<?> listener = setter.getParameterTypes()[0];
            setter.invoke(ours, Proxy.newProxyInstance(
                    hostLoader, new Class<?>[]{listener}, new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            if (method.getDeclaringClass() == Object.class) {
                                return method.getName().equals("hashCode")
                                        ? System.identityHashCode(proxy)
                                        : method.getName().equals("equals") ? proxy == args[0]
                                        : "bsb-click";
                            }
                            openFromSettingsPage(proxy, args == null ? null : args[0]);
                            return Boolean.TRUE;
                        }
                    }));
            // Make room: everything after the anchor moves one place down, then ours goes in.
            int count = (Integer) XposedHelpers.callMethod(group, "getPreferenceCount");
            for (int index = 0; index < count; index++) {
                Object sibling = XposedHelpers.callMethod(group, "getPreference", index);
                int order = (Integer) XposedHelpers.callMethod(sibling, "getOrder");
                if (order > anchorOrder) {
                    XposedHelpers.callMethod(sibling, "setOrder", order + 1);
                }
            }
            XposedHelpers.callMethod(ours, "setOrder", anchorOrder + 1);
            Object added = XposedHelpers.callMethod(group, "addPreference", ours);
            android.util.Log.i(TAG, "settings page entry added=" + added + " order="
                    + (anchorOrder + 1) + " class=" + ours.getClass().getName());
        }

        private static void openFromSettingsPage(Object listener, Object preference) {
            try {
                Context context = preference == null ? null
                        : (Context) XposedHelpers.callMethod(preference, "getContext");
                while (context instanceof ContextWrapper && !(context instanceof Activity)) {
                    context = ((ContextWrapper) context).getBaseContext();
                }
                Activity activity = context instanceof Activity ? (Activity) context
                        : topActivity == null ? null : topActivity.get();
                if (activity == null) {
                    throw new IllegalStateException("no activity");
                }
                InPlayerSettingsPanel.show(activity, listBounds(), true, true, null,
                        new InPlayerSettingsPanel.Actions() {
                            @Override
                            public void applyRenderer(ModulePreferences.RendererMode mode) {
                                switchRendererOn(renderService == null ? null
                                        : renderService.get(), mode);
                            }
                        });
            } catch (Throwable throwable) {
                logOnce("settings-page-open", throwable);
            }
        }
    }

    /**
     * The same entry in the bottom-sheet settings menu (windowed player, phones in portrait):
     * a row right after "More playback settings". The sheet's RecyclerView takes its rows from
     * the shared component adapter's updateData(List of UIComponent); we add a
     * VideoSettingDescriptionComponent whose data is built like the "More playback settings" row.
     */
    private static final class SettingsDialogEntry {
        private static final String ADAPTER = "com.bilibili.app.gemini.ui.f";
        private static final String DESCRIPTION =
                "com.bilibili.playerbizcommonv2.widget.setting.channel.VideoSettingDescriptionComponent";
        private static final String SWITCH =
                "com.bilibili.playerbizcommonv2.widget.setting.channel.VideoSettingSwitchComponent";
        // Mask bits of the data class's synthetic constructor: use the default for iconUrl (2),
        // visible (8), description (16), title end icon (128), its click (512), badge (1024),
        // the four colours and the exposure entry.
        private static final int DEFAULTS = 2 | 8 | 16 | 128 | 512 | 1024
                | 2048 | 4096 | 8192 | 16384 | 65536;
        private static Object ourComponent;

        static String install(ClassLoader loader) {
            try {
                final Class<?> adapter = XposedHelpers.findClassIfExists(ADAPTER, loader);
                final Class<?> description = XposedHelpers.findClassIfExists(DESCRIPTION, loader);
                final Class<?> switchRow = XposedHelpers.findClassIfExists(SWITCH, loader);
                if (adapter == null || description == null || switchRow == null) {
                    return "settings dialog entry: classes not found";
                }
                XposedBridge.hookAllMethods(android.app.Dialog.class, "show", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        lastDialog = new WeakReference<>((android.app.Dialog) param.thisObject);
                    }
                });
                int hooked = XposedBridge.hookAllMethods(adapter, "updateData",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                try {
                                    insert(param, description, switchRow);
                                } catch (Throwable throwable) {
                                    logOnce("dialog-insert", throwable);
                                }
                            }
                        }).size();
                return "settings dialog entry hooked updateData=" + hooked;
            } catch (Throwable throwable) {
                return "settings dialog entry unavailable=" + throwable.getClass().getSimpleName();
            }
        }

        private static void insert(XC_MethodHook.MethodHookParam param, Class<?> description,
                                   Class<?> switchRow) throws Throwable {
            if (param.args.length != 1 || !(param.args[0] instanceof List)) {
                return;
            }
            List<?> original = (List<?>) param.args[0];
            if (hasTitle(original)) {
                // The full-screen panel's rows go through this adapter too, and its own hook has
                // already added the entry there.
                return;
            }
            int lastSwitch = -1;
            for (int index = 0; index < original.size(); index++) {
                if (switchRow.isInstance(original.get(index))) {
                    lastSwitch = index;
                }
            }
            if (lastSwitch < 0) {
                return;
            }
            int anchor = -1;
            for (int index = lastSwitch + 1; index < original.size(); index++) {
                if (description.isInstance(original.get(index))) {
                    anchor = index;
                    break;
                }
            }
            if (anchor < 0) {
                return;
            }
            if (ourComponent == null) {
                ourComponent = description.getConstructors()[0].newInstance(
                        copyData(original.get(anchor)));
            }
            List<Object> merged = new ArrayList<>(original);
            merged.add(anchor + 1, ourComponent);
            param.args[0] = merged;
        }

        /** True when some row (or its data object) already carries our title. */
        private static boolean hasTitle(List<?> rows) {
            for (Object row : rows) {
                if (row == null) {
                    continue;
                }
                for (Field field : row.getClass().getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    try {
                        field.setAccessible(true);
                        Object value = field.get(row);
                        if (ROW_TITLE.equals(value)) {
                            return true;
                        }
                        if (value != null && value.getClass().getName().startsWith("com.bilibili")) {
                            for (Field inner : value.getClass().getDeclaredFields()) {
                                if (java.lang.reflect.Modifier.isStatic(inner.getModifiers())
                                        || inner.getType() != String.class) {
                                    continue;
                                }
                                inner.setAccessible(true);
                                if (ROW_TITLE.equals(inner.get(value))) {
                                    return true;
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                        // Unreadable field: not a title holder.
                    }
                }
            }
            return false;
        }

        /** A data object for our row, taking the arrow icon and style from the anchor row. */
        private static Object copyData(Object anchorComponent) throws Exception {
            Object anchorData = null;
            for (Field field : anchorComponent.getClass().getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    field.setAccessible(true);
                    anchorData = field.get(anchorComponent);
                    break;
                }
            }
            if (anchorData == null) {
                throw new IllegalStateException("anchor data missing");
            }
            List<Field> fields = new ArrayList<>();
            for (Field field : anchorData.getClass().getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    field.setAccessible(true);
                    fields.add(field);
                }
            }
            // Declaration order = constructor order: ... endIconUrl, endIconRes, ..., style, expose.
            Object endIconRes = fields.get(6).get(anchorData);
            Object style = fields.get(15).get(anchorData);
            java.lang.reflect.Constructor<?> constructor = null;
            for (java.lang.reflect.Constructor<?> candidate
                    : anchorData.getClass().getConstructors()) {
                if (candidate.getParameterTypes().length == 17) {
                    constructor = candidate;
                }
            }
            if (constructor == null) {
                throw new IllegalStateException("data constructor missing");
            }
            Object onClick = function0(new Runnable() {
                @Override
                public void run() {
                    openFromDialog();
                }
            });
            Object[] args = new Object[17];
            args[0] = ROW_TITLE;
            args[1] = "";
            args[2] = ROW_ICON;
            args[5] = endIconRes;
            args[7] = onClick;
            args[10] = 0;
            args[11] = 0;
            args[12] = 0;
            args[13] = 0;
            args[14] = style;
            args[16] = DEFAULTS;
            return constructor.newInstance(args);
        }

        /** The bottom sheet's content area on screen (view id content_layout), or null. */
        private static Rect sheetBounds(android.app.Dialog dialog, Activity activity) {
            try {
                if (dialog.getWindow() == null) {
                    return null;
                }
                int id = activity.getResources().getIdentifier("content_layout", "id",
                        activity.getPackageName());
                View view = id == 0 ? null : dialog.getWindow().getDecorView().findViewById(id);
                if (view == null || !view.isAttachedToWindow() || view.getWidth() <= 0) {
                    return null;
                }
                int[] location = new int[2];
                view.getLocationOnScreen(location);
                return new Rect(location[0], location[1],
                        location[0] + view.getWidth(), location[1] + view.getHeight());
            } catch (Throwable throwable) {
                logOnce("dialog-bounds", throwable);
                return null;
            }
        }

        private static void openFromDialog() {
            try {
                Activity activity = topActivity == null ? null : topActivity.get();
                if (activity == null) {
                    throw new IllegalStateException("no activity");
                }
                android.app.Dialog dialog = lastDialog == null ? null : lastDialog.get();
                // Where the menu sits now, read before it closes: the panel takes its place.
                Rect sheet = dialog == null ? null : sheetBounds(dialog, activity);
                if (dialog != null && dialog.isShowing()) {
                    dialog.dismiss();
                }
                InPlayerSettingsPanel.show(activity, sheet, true, null,
                        new InPlayerSettingsPanel.Actions() {
                            @Override
                            public void applyRenderer(ModulePreferences.RendererMode mode) {
                                switchRendererOn(renderService == null ? null
                                        : renderService.get(), mode);
                            }
                        });
            } catch (Throwable throwable) {
                logOnce("dialog-open", throwable);
            }
        }
    }

    /** Same as {@link #switchRenderer} when the render container service is known directly. */
    private static void switchRendererOn(Object service, ModulePreferences.RendererMode mode) {
        try {
            if (service == null) {
                throw new IllegalStateException("render service not seen yet");
            }
            for (Method method : service.getClass().getMethods()) {
                if (method.getName().equals("setExactlyVideoLayerType")
                        && method.getParameterTypes().length == 1) {
                    Object target = XposedHelpers.getStaticObjectField(
                            method.getParameterTypes()[0], mode.bilibiliTypeField);
                    Object current = XposedHelpers.callMethod(service, "getCurrentLayerType");
                    if (!target.equals(current)) {
                        method.invoke(service, target);
                    }
                    return;
                }
            }
            throw new IllegalStateException("setExactlyVideoLayerType not found");
        } catch (Throwable throwable) {
            logOnce("renderer-dialog", throwable);
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
            InPlayerSettingsPanel.show((Activity) context, contentBounds(),
                    content == null ? null : content.get(),
                    new InPlayerSettingsPanel.Actions() {
                        @Override
                        public void applyRenderer(ModulePreferences.RendererMode mode) {
                            switchRenderer(widget, mode);
                        }
                    });
        } catch (Throwable throwable) {
            logOnce("open", throwable);
        }
    }

    /** Where the original panel's content sits on screen, or null if it cannot be measured. */
    private static Rect contentBounds() {
        View view = content == null ? null : content.get();
        if (view == null || !view.isAttachedToWindow() || view.getWidth() <= 0) {
            return null;
        }
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return new Rect(location[0], location[1],
                location[0] + view.getWidth(), location[1] + view.getHeight());
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
