package dev.xpramt.bilisurfacebridge;

import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;


/**
 * Adds 2.5x/3x/4x/5x/6x/7x/8x/9x/10x to Bilibili's playback-speed menu and optionally overrides the
 * long-press rate.  Both live in one obfuscated utility class (9.13 {@code ...playerbizcommonv2.utils.F},
 * 9.18 {@code utils.E}, 9.19 the top-level {@code q022}) that {@link DexScan} finds by method shape, never by name:
 * <pre>
 *   static List  menu(MediaResource, [Integer|boolean|int]...)   // speed entries "PlaybackSpeedOption(speed, enabled)"
 *   static float longPress(MediaResource)                         // long-press rate
 * </pre>
 * (9.18 {@code (MediaResource, Integer, boolean, int)}, 9.19 {@code (MediaResource, Integer, int)}: only the shape is relied on.)
 * Every list the hook touches must hold Kotlin's {@code PlaybackSpeedOption(...)} rows (toString keeps the original name), so a
 * same-shaped method elsewhere is left alone.  Labels, persistence and the click handler stay Bilibili's own; the player core's
 * ceiling is lifted separately by the native speed-cap hook.
 */
final class SpeedMenuHook {
    private static final String TAG = "BiliExtended";
    private static final String MEDIA_RESOURCE = "com.bilibili.lib.media.resource.MediaResource";
    /** Kotlin data-class toString of a menu entry (9.18 and 9.19). */
    static final String OPTION_PREFIX = "PlaybackSpeedOption(";

    private SpeedMenuHook() {
    }

    static boolean isSpeedOptionList(Object value) {
        if (!(value instanceof List) || ((List<?>) value).isEmpty()) {
            return false;
        }
        Object first = ((List<?>) value).get(0);
        return first != null && String.valueOf(first).startsWith(OPTION_PREFIX);
    }

    static String install(ClassLoader classLoader, DexScan.Result scan) {
        Class<?> mediaResource;
        try {
            mediaResource = classLoader.loadClass(MEDIA_RESOURCE);
        } catch (Throwable throwable) {
            return "speed menu hook skipped: MediaResource not found";
        }
        List<String> owners = scan.get(DexScan.SPEED_MENU);
        StringBuilder result = new StringBuilder();
        int menus = 0;
        int longPresses = 0;
        for (String name : owners) {
            Class<?> candidate = Xp.findClassIfExists(name, classLoader);
            if (candidate == null) {
                continue;
            }
            try {
                for (Method method : candidate.getDeclaredMethods()) {
                    if (!Modifier.isStatic(method.getModifiers())) {
                        continue;
                    }
                    Class<?>[] parameters = method.getParameterTypes();
                    if (List.class.equals(method.getReturnType()) && isMenuShape(parameters, mediaResource)) {
                        Xp.hookMethod(method, new MenuHook());
                        menus++;
                        result.append(' ').append(candidate.getName()).append('.').append(method.getName());
                    } else if (float.class.equals(method.getReturnType()) && parameters.length == 1
                            && mediaResource.equals(parameters[0])) {
                        Xp.hookMethod(method, new LongPressHook());
                        longPresses++;
                    }
                }
            } catch (Throwable throwable) {
                result.append(' ').append(name).append(" failed=").append(throwable.getClass().getSimpleName());
            }
        }
        if (menus == 0) {
            return "speed menu hook skipped: speed list builder not found (candidates " + owners + ")";
        }
        return "speed menu hook installed menus=" + menus + result + " long-press=" + longPresses
                + installLowSpeedUi(classLoader, scan.get(DexScan.SPEED_ADAPTER));
    }

    /** (MediaResource, then only Integer / boolean / int), 2 to 4 parameters. */
    private static boolean isMenuShape(Class<?>[] parameters, Class<?> mediaResource) {
        if (parameters.length < 2 || parameters.length > 4 || !mediaResource.equals(parameters[0])) {
            return false;
        }
        for (int i = 1; i < parameters.length; i++) {
            if (!Integer.class.equals(parameters[i]) && !boolean.class.equals(parameters[i]) && !int.class.equals(parameters[i])) {
                return false;
            }
        }
        return true;
    }

    private static final String THIRD_RAW = String.valueOf(SpeedMenuItems.ONE_THIRD);

    /**
     * Bilibili formats speeds with String.valueOf (1/3 -> "0.33333334") and marks the selected row
     * with |speed - current| < 0.1, which makes 1/3 and 0.25 the same row.  Rewrite the label text
     * wherever it reaches a TextView (menu row, toast) and recompute selection with 0.001.
     */
    private static String installLowSpeedUi(ClassLoader classLoader, List<String> adapters) {
        StringBuilder out = new StringBuilder();
        try {
            Xp.hookMethod(android.widget.TextView.class.getMethod("setText",
                    CharSequence.class, android.widget.TextView.BufferType.class), new Xp.Hook() {
                @Override
                protected void before(Xp.Param param) {
                    Object text = param.args[0];
                    if (text != null && !(text instanceof android.text.Spanned)) {
                        String s = text.toString();
                        if (s.contains(THIRD_RAW)) {
                            param.args[0] = s.replace(THIRD_RAW, "0.33");
                        }
                    }
                }
            });
            out.append(" +label");
        } catch (Throwable throwable) {
            out.append(" label failed=").append(throwable.getClass().getSimpleName());
        }
        // Candidates from DexScan: RecyclerView.Adapter subclasses with void m(float, List).  Each hook acts only on a list of
        // PlaybackSpeedOption rows, so another adapter of the same shape is left alone.
        int hooked = 0;
        for (String name : adapters) {
            Class<?> candidate = Xp.findClassIfExists(name, classLoader);
            if (candidate == null || !androidx_adapter(candidate)) {
                continue;
            }
            try {
                for (Method method : candidate.getDeclaredMethods()) {
                    Class<?>[] ps = method.getParameterTypes();
                    if (ps.length == 2 && float.class.equals(ps[0]) && List.class.equals(ps[1])
                            && void.class.equals(method.getReturnType()) && !Modifier.isStatic(method.getModifiers())) {
                        Xp.hookMethod(method, new SelectionHook());
                        hooked++;
                        try {
                            Xp.hookMethod(candidate.getDeclaredMethod("onClick",
                                    android.view.View.class), new Xp.Hook() {
                                @Override
                                protected void before(Xp.Param param) {
                                    Object tag = ((android.view.View) param.args[0]).getTag();
                                    if (tag != null && tag.getClass() == rowClass) {
                                        float picked = rowSpeed(tag);
                                        if (!Float.isNaN(picked)) {
                                            lastPicked = picked;
                                        }
                                    }
                                }
                            });
                        } catch (Throwable ignored) {
                            // selection then follows Bilibili's own current speed only
                        }
                    }
                }
            } catch (Throwable ignored) {
                // next
            }
        }
        return out.append(" select-adapters=").append(hooked).append(' ').append(adapters).toString();
    }

    private static boolean androidx_adapter(Class<?> c) {
        Class<?> s = c.getSuperclass();
        return s != null && s.getName().endsWith("RecyclerView$Adapter");
    }

    /**
     * The speed last picked in the menu.  Bilibili's own "current speed" for the panel keeps the
     * old value when the new one is within its 0.1 tolerance (1/3 -> 0.25 measured 2026-11-07:
     * the player ran 0.25, the panel still reported ~1/3), so a nearby report is taken as this.
     */
    private static volatile float lastPicked = Float.NaN;
    /** The adapter's own row class, learnt from the first speed list it was given. */
    private static volatile Class<?> rowClass;
    private static volatile boolean selectionLogged;

    private static float rowSpeed(Object row) {
        if (row == null) return Float.NaN;
        for (Field rf : row.getClass().getDeclaredFields()) {
            if (!Modifier.isStatic(rf.getModifiers()) && float.class.equals(rf.getType())) {
                try {
                    rf.setAccessible(true);
                    return rf.getFloat(row);
                } catch (Throwable ignored) {
                    return Float.NaN;
                }
            }
        }
        return Float.NaN;
    }

    /**
     * After Bilibili filled its rows from (current, options): each row has one float (speed) and two booleans (selected, enabled).
     * Which boolean is "selected" is read from what Bilibili just did -- it set exactly the rows within 0.1 of current -- instead of
     * from the obfuscated field name; then selection is recomputed with 0.001 so 1/3 and 0.25 are different rows.
     */
    private static final class SelectionHook extends Xp.Hook {
        @Override
        protected void after(Xp.Param param) {
            try {
                if (!isSpeedOptionList(param.args[1])) {
                    return;
                }
                final float given = (Float) param.args[0];
                float current = given;
                final float picked = lastPicked;
                if (!Float.isNaN(picked) && Math.abs(current - picked) < 0.1f) {
                    current = picked;
                }
                for (Field f : param.thisObject.getClass().getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || !List.class.isAssignableFrom(f.getType())) {
                        continue;
                    }
                    f.setAccessible(true);
                    List<?> rows = (List<?>) f.get(param.thisObject);
                    if (rows == null || rows.isEmpty()) {
                        continue;
                    }
                    Class<?> cls = rows.get(0).getClass();
                    Field speed = null;
                    List<Field> bools = new ArrayList<>();
                    for (Field rf : cls.getDeclaredFields()) {
                        if (Modifier.isStatic(rf.getModifiers())) continue;
                        if (float.class.equals(rf.getType())) speed = rf;
                        else if (boolean.class.equals(rf.getType())) bools.add(rf);
                    }
                    if (speed == null || bools.size() != 2) {
                        continue;
                    }
                    speed.setAccessible(true);
                    Field selected = null;
                    for (Field b : bools) {
                        b.setAccessible(true);
                        boolean matches = true;
                        for (Object row : rows) {
                            if (b.getBoolean(row) != (Math.abs(given - speed.getFloat(row)) < 0.1f)) {
                                matches = false;
                                break;
                            }
                        }
                        if (matches && (selected == null || "b".equals(b.getName()))) {
                            selected = b;
                        }
                    }
                    if (selected == null) {
                        return;   // unknown layout: keep Bilibili's own selection
                    }
                    rowClass = cls;
                    int marked = 0;
                    for (Object row : rows) {
                        boolean on = Math.abs(speed.getFloat(row) - current) < 0.001f;
                        selected.setBoolean(row, on);
                        marked += on ? 1 : 0;
                    }
                    if (!selectionLogged) {
                        selectionLogged = true;
                        Xp.log(TAG + ": speed menu selection " + param.thisObject.getClass().getName() + " rows=" + rows.size()
                                + " selectedField=" + selected.getName() + " current=" + current + " selected=" + marked);
                    }
                    return;
                }
            } catch (Throwable throwable) {
                Log.w(TAG, "speed selection fix failed=" + throwable);
            }
        }
    }

    private static final class MenuHook extends Xp.Hook {
        private Constructor<?> itemConstructor;
        private Field speedField;
        private boolean failureLogged;
        private boolean successLogged;

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        protected void after(Xp.Param param) {
            try {
                if (!ModulePreferences.loadSpeedMenuExtraForHook()) {
                    return;
                }
                Object returned = param.getResult();
                if (!isSpeedOptionList(returned)) {
                    return;
                }
                List<Object> original = (List<Object>) returned;
                Object first = original.get(0);
                if (itemConstructor == null) {
                    itemConstructor = first.getClass().getConstructor(float.class, boolean.class);
                    for (Field field : first.getClass().getDeclaredFields()) {
                        if (!Modifier.isStatic(field.getModifiers())
                                && float.class.equals(field.getType())) {
                            field.setAccessible(true);
                            speedField = field;
                            break;
                        }
                    }
                }
                if (speedField == null) {
                    throw new NoSuchFieldException("speed field");
                }
                List<Object> rebuilt = new ArrayList<>();
                for (float extra : SpeedMenuItems.EXTRA) {
                    rebuilt.add(itemConstructor.newInstance(extra, Boolean.TRUE));
                }
                Iterator<Object> iterator = original.iterator();
                while (iterator.hasNext()) {
                    Object item = iterator.next();
                    if (!SpeedMenuItems.isExtra(speedField.getFloat(item))) {
                        rebuilt.add(item);
                    }
                }
                for (float extra : SpeedMenuItems.EXTRA_LOW) {
                    rebuilt.add(itemConstructor.newInstance(extra, Boolean.TRUE));
                }
                if (!successLogged) {
                    successLogged = true;
                    Xp.log(TAG + ": speed menu entries " + original.size() + " -> " + rebuilt.size() + " via " + param.method.getDeclaringClass().getName());
                }
                // Same list instance when it is mutable, a new one otherwise.
                try {
                    original.clear();
                    original.addAll(rebuilt);
                } catch (UnsupportedOperationException immutable) {
                    param.setResult(rebuilt);
                }
            } catch (Throwable throwable) {
                if (!failureLogged) {
                    failureLogged = true;
                    Log.w(TAG, "speed menu extra entries failed=" + throwable);
                }
            }
        }
    }

    private static final class LongPressHook extends Xp.Hook {
        @Override
        protected void after(Xp.Param param) {
            try {
                float override = ModulePreferences.loadLongPressSpeedOverrideForHook();
                if (!Float.isNaN(override)) {
                    param.setResult(override);
                }
            } catch (Throwable ignored) {
                // Keep Bilibili's own rate.
            }
        }
    }
}
