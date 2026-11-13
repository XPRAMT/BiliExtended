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


/**
 * Adds a "Bili Extended" row to Bilibili's in-player settings panel (the right-hand panel of the
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
    private static final String TAG = "BiliExtended";
    private static final String WIDGET_CLASS =
            "com.bilibili.playerbizcommonv2.widget.setting.PlayerSettingFunctionWidget2";
    private static final int FEEDBACK_ROW = 8;
    private static final int EDIT_PLAYER_ROW = 9;
    private static final String ROW_TITLE = "Bili Extended";
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
            Class<?> widgetClass = Xp.findClassIfExists(WIDGET_CLASS, classLoader);
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
            unit = Xp.getStaticObjectField(
                    classLoader.loadClass("kotlin.Unit"), "INSTANCE");
            Xp.hookMethod(build, new RowBuilderHook());
            for (Method method : widgetClass.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length == 1 && parameters[0] == Context.class
                        && View.class.isAssignableFrom(method.getReturnType())) {
                    Xp.hookMethod(method, new Xp.Hook() {
                        @Override
                        protected void after(Xp.Param param) {
                            if (param.getResult() instanceof View) {
                                content = new WeakReference<>((View) param.getResult());
                            }
                        }
                    });
                    break;
                }
            }
            return "in-player settings panel hooked (" + build.getName() + "); "
                    + SettingsPageEntry.install(classLoader);
        } catch (Throwable throwable) {
            return "in-player settings panel hook unavailable="
                    + throwable.getClass().getSimpleName();
        }
    }

    /** The newest render container service seen: the dialog entry has no widget to ask. */
    private static WeakReference<Object> renderService;

    /** Hot reload: the activity and the render service seen last (Bilibili objects). */
    static Object[] exportForHotReload() {
        return new Object[]{topActivity == null ? null : topActivity.get(), renderService == null ? null : renderService.get()};
    }

    static void adoptAfterHotReload(Object exported) {
        if (exported instanceof Object[]) {
            Object[] state = (Object[]) exported;
            if (state.length > 0 && state[0] instanceof Activity) {
                noteActivity((Activity) state[0]);
            }
            if (state.length > 1 && state[1] != null) {
                noteRenderService(state[1]);
            }
        }
    }

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
                        : (View) Xp.callMethod(fragment, "getListView");
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
                Class<?> fragment = Xp.findClassIfExists(
                        "androidx.preference.PreferenceFragmentCompat", loader);
                if (fragment == null) {
                    return "settings page entry: PreferenceFragmentCompat not found";
                }
                int hooked = Xp.hookAllMethods(fragment, "setPreferenceScreen",
                        new Xp.Hook() {
                            @Override
                            protected void after(Xp.Param param) {
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
            Object anchor = Xp.callMethod(screen, "findPreference", ANCHOR_KEY);
            if (anchor == null || Xp.callMethod(screen, "findPreference", OUR_KEY) != null) {
                return;
            }
            Object group = Xp.callMethod(anchor, "getParent");
            if (group == null) {
                return;
            }
            Context context = (Context) Xp.callMethod(anchor, "getContext");
            int anchorOrder = (Integer) Xp.callMethod(anchor, "getOrder");
            Object ours;
            try {
                ours = Xp.newInstance(anchor.getClass(), context);
            } catch (Throwable throwable) {
                ours = Xp.newInstance(Xp.findClass(
                        "androidx.preference.Preference", anchor.getClass().getClassLoader()),
                        context);
            }
            Xp.callMethod(ours, "setKey", OUR_KEY);
            Xp.callMethod(ours, "setTitle", (CharSequence) ROW_TITLE);
            Xp.callMethod(ours, "setPersistent", false);
            Xp.callMethod(ours, "setLayoutResource",
                    Xp.callMethod(anchor, "getLayoutResource"));
            Xp.callMethod(ours, "setWidgetLayoutResource",
                    Xp.callMethod(anchor, "getWidgetLayoutResource"));
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
            int count = (Integer) Xp.callMethod(group, "getPreferenceCount");
            for (int index = 0; index < count; index++) {
                Object sibling = Xp.callMethod(group, "getPreference", index);
                int order = (Integer) Xp.callMethod(sibling, "getOrder");
                if (order > anchorOrder) {
                    Xp.callMethod(sibling, "setOrder", order + 1);
                }
            }
            Xp.callMethod(ours, "setOrder", anchorOrder + 1);
            Object added = Xp.callMethod(group, "addPreference", ours);
            android.util.Log.i(TAG, "settings page entry added=" + added + " order="
                    + (anchorOrder + 1) + " class=" + ours.getClass().getName());
        }

        private static void openFromSettingsPage(Object listener, Object preference) {
            try {
                Context context = preference == null ? null
                        : (Context) Xp.callMethod(preference, "getContext");
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
    /** The bottom-sheet entry; the adapter candidates come from {@link DexScan} (9.18 com.bilibili.app.gemini.ui.f, 9.19 ...ui.e). */
    static String installDialogEntry(ClassLoader loader, List<String> adapters) {
        return SettingsDialogEntry.install(loader, adapters);
    }

    private static final class SettingsDialogEntry {
        private static Object ourComponent;
        private static volatile boolean dialogEntryLogged;

        static String install(ClassLoader loader, List<String> adapters) {
            try {
                if (hostLoader == null) {
                    return "settings dialog entry: in-player panel not hooked";
                }
                Xp.Hook updateHook = new Xp.Hook() {
                    @Override
                    protected void before(Xp.Param param) {
                        try {
                            insert(param);
                        } catch (Throwable throwable) {
                            logOnce("dialog-insert", throwable);
                        }
                    }
                };
                // Every RecyclerView.Adapter with updateData(List): insert() only acts on a list of the settings dialog's own rows
                // (VideoSettingSwitch / VideoSettingDescription), so the other adapters pass through untouched.
                int hooked = 0;
                for (String name : adapters) {
                    Class<?> adapter = Xp.findClassIfExists(name, loader);
                    if (adapter == null) {
                        continue;
                    }
                    for (java.lang.reflect.Method method : adapter.getDeclaredMethods()) {
                        Class<?>[] ps = method.getParameterTypes();
                        if ("updateData".equals(method.getName()) && ps.length == 1 && ps[0] == List.class
                                && !java.lang.reflect.Modifier.isAbstract(method.getModifiers())) {
                            Xp.hookMethod(method, updateHook);
                            hooked++;
                        }
                    }
                }
                if (hooked == 0) {
                    return "settings dialog entry: adapter not found (candidates " + adapters + ")";
                }
                Xp.hookAllMethods(android.app.Dialog.class, "show", new Xp.Hook() {
                    @Override
                    protected void after(Xp.Param param) {
                        lastDialog = new WeakReference<>((android.app.Dialog) param.thisObject);
                    }
                });
                return "settings dialog entry hooked updateData=" + hooked + " " + adapters;
            } catch (Throwable throwable) {
                return "settings dialog entry unavailable=" + throwable.getClass().getSimpleName();
            }
        }

        /**
         * The row classes are obfuscated in some versions (9.13: ...channel.D / .j), so rows are
         * recognised by their data object's toString(), which Kotlin generates from the original
         * names: "VideoSettingSwitch(" and "VideoSettingDescription(".
         */
        private static String kind(Object component) {
            if (component == null) {
                return "";
            }
            try {
                for (Field field : component.getClass().getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    field.setAccessible(true);
                    Object data = field.get(component);
                    String text = data == null ? "" : String.valueOf(data);
                    if (text.startsWith("VideoSettingSwitch(")) {
                        return "switch";
                    }
                    if (text.startsWith("VideoSettingDescription(")) {
                        return "description";
                    }
                }
            } catch (Throwable ignored) {
                // Not a row we know.
            }
            return "";
        }

        private static void insert(Xp.Param param) throws Throwable {
            if (param.args.length != 1 || !(param.args[0] instanceof List)) {
                return;
            }
            List<?> original = (List<?>) param.args[0];
            int lastSwitch = -1;
            for (int index = 0; index < original.size(); index++) {
                if (kind(original.get(index)).equals("switch")) {
                    lastSwitch = index;
                }
            }
            if (lastSwitch < 0) {
                return;   // not the settings dialog's rows (another adapter of the same shape)
            }
            if (hasTitle(original)) {
                // The full-screen panel's rows go through this adapter too, and its own hook has
                // already added the entry there.
                return;
            }
            int anchor = -1;
            for (int index = lastSwitch + 1; index < original.size(); index++) {
                if (kind(original.get(index)).equals("description")) {
                    anchor = index;
                    break;
                }
            }
            if (anchor < 0) {
                return;
            }
            if (ourComponent == null) {
                ourComponent = original.get(anchor).getClass().getConstructors()[0].newInstance(
                        copyData(original.get(anchor)));
            }
            List<Object> merged = new ArrayList<>(original);
            merged.add(anchor + 1, ourComponent);
            if (!dialogEntryLogged) {
                dialogEntryLogged = true;
                Xp.log(TAG + ": settings dialog entry added at " + (anchor + 1) + " of " + merged.size() + " via "
                        + param.method.getDeclaringClass().getName());
            }
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

        /**
         * A data object for our row: a field-by-field copy of the anchor row's data ("VideoSettingDescription(...)", which keeps its arrow
         * icon, colours and style), with title, icon and click replaced and its exposure / badge cleared.  The fields are told apart by
         * the property names Kotlin writes into toString ("title=", "onClick=", ...), never by position, name or constructor shape: 9.18 had
         * a 17-argument constructor, 9.19 has 15 (R8 dropped constant ones), and the field names are obfuscated in both.
         */
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
            Class<?> dataClass = anchorData.getClass();
            List<Field> fields = new ArrayList<>();
            for (Field field : dataClass.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    field.setAccessible(true);
                    fields.add(field);
                }
            }
            Object copy = allocate(dataClass);
            for (Field field : fields) {
                field.set(copy, field.get(anchorData));
            }
            java.util.Map<String, Field> byLabel = labelFields(copy, fields);
            Object onClick = function0(new Runnable() {
                @Override
                public void run() {
                    openFromDialog();
                }
            });
            if (!byLabel.containsKey("title") || !byLabel.containsKey("onClick")) {
                throw new IllegalStateException("title/onClick not found in " + byLabel.keySet());
            }
            byLabel.get("title").set(copy, ROW_TITLE);
            byLabel.get("onClick").set(copy, onClick);
            setIfPresent(byLabel, copy, "iconUrl", "");
            setIfPresent(byLabel, copy, "iconRes", ROW_ICON);
            setIfPresent(byLabel, copy, "onTitleEndIconClick", null);
            setIfPresent(byLabel, copy, "titleEndIconRes", null);
            setIfPresent(byLabel, copy, "badgeInfo", null);
            setIfPresent(byLabel, copy, "exposeEntry", null);
            return copy;
        }

        private static void setIfPresent(java.util.Map<String, Field> byLabel, Object target, String label, Object value) {
            Field field = byLabel.get(label);
            if (field == null || (value == null && field.getType().isPrimitive())) {
                return;
            }
            try {
                field.set(target, value);
            } catch (Throwable ignored) {
                // type mismatch: keep the anchor's value
            }
        }

        /** An instance without running a constructor (the copy is filled field by field). */
        private static Object allocate(Class<?> type) throws Exception {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            return unsafeClass.getMethod("allocateInstance", Class.class).invoke(theUnsafe.get(null), type);
        }

        /**
         * toString property name -> field, found by probing: each field is set to a value whose text is unique (a String or int marker,
         * an interface proxy whose toString is the marker, or null for any other non-null object), toString is read, the property whose
         * value changed is taken, and the field is put back.
         */
        private static java.util.Map<String, Field> labelFields(Object target, List<Field> fields) throws Exception {
            java.util.Map<String, Field> out = new java.util.HashMap<>();
            String base = String.valueOf(target);
            for (int i = 0; i < fields.size(); i++) {
                Field field = fields.get(i);
                Class<?> type = field.getType();
                Object original = field.get(target);
                final String marker = "BsbProbe" + i + "x";
                Object probe;
                boolean byMarker = true;
                if (type == String.class) {
                    probe = marker;
                } else if (type == int.class) {
                    probe = 735190000 + i;
                } else if (type.isInterface()) {
                    probe = Proxy.newProxyInstance(type.getClassLoader() != null ? type.getClassLoader() : hostLoader, new Class<?>[]{type},
                            (proxy, method, args) -> {
                                if ("toString".equals(method.getName())) return marker;
                                if ("hashCode".equals(method.getName())) return 0;
                                if ("equals".equals(method.getName())) return proxy == args[0];
                                return null;
                            });
                } else if (!type.isPrimitive() && original != null) {
                    probe = null;
                    byMarker = false;
                } else {
                    continue;
                }
                String text;
                try {
                    field.set(target, probe);
                    text = String.valueOf(target);
                } finally {
                    field.set(target, original);
                }
                int at;
                if (byMarker) {
                    String needle = type == int.class ? String.valueOf(735190000 + i) : marker;
                    at = text.indexOf("=" + needle);
                } else {
                    at = -1;
                    int n = Math.min(text.length(), base.length());
                    for (int k = 0; k < n; k++) {
                        if (text.charAt(k) != base.charAt(k)) {
                            at = text.lastIndexOf('=', k);
                            if (at < 0 || !text.startsWith("null", at + 1)) {
                                at = -1;   // the change did not start at a property value: ambiguous, skip
                            }
                            break;
                        }
                    }
                }
                if (at <= 0) {
                    continue;
                }
                int start = at;
                while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) {
                    start--;
                }
                if (start < at) {
                    out.put(text.substring(start, at), field);
                }
            }
            return out;
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

    /** Switches the live video layer of the newest player, whichever page asked. */
    static void switchRendererTo(ModulePreferences.RendererMode mode) {
        switchRendererOn(renderService == null ? null : renderService.get(), mode);
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
                    Object target = Xp.getStaticObjectField(
                            method.getParameterTypes()[0], mode.bilibiliTypeField);
                    Object current = Xp.callMethod(service, "getCurrentLayerType");
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
    private static final class RowBuilderHook extends Xp.Hook {
        private static final String EXTRA_INSERT = "bsb_insert";

        @Override
        protected void before(Xp.Param param) {
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
        protected void after(Xp.Param param) {
            if (!Boolean.TRUE.equals(param.getObjectExtra(EXTRA_INSERT))) {
                return;
            }
            try {
                int id = (Integer) param.args[0];
                // invokeOriginalMethod skips this hook, so the copy keeps the position we ask for.
                List<?> template = (List<?>) Xp.invokeOriginalMethod(
                        buildRow, param.thisObject, new Object[]{id, position("TOP")});
                Object ourRow = template.get(0);
                Object data = Xp.getObjectField(
                        Xp.getObjectField(ourRow, "a"), "a");
                final Object widget = param.thisObject;
                Xp.setObjectField(data, "a", ROW_TITLE);
                Xp.setObjectField(data, "c", ROW_ICON);
                Xp.setObjectField(data, "i", function0(() -> openPanel(widget)));
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
            Context context = (Context) Xp.callMethod(widget, "getMContext");
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
                    Object target = Xp.getStaticObjectField(
                            method.getParameterTypes()[0], mode.bilibiliTypeField);
                    Object current = Xp.callMethod(service, "getCurrentLayerType");
                    if (!target.equals(current)) {
                        Xp.callMethod(service, "setExactlyVideoLayerType", target);
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
        Xp.log(TAG + ": in-player settings panel " + key + " failed: "
                + throwable.getClass().getSimpleName() + " " + throwable.getMessage());
    }
}
