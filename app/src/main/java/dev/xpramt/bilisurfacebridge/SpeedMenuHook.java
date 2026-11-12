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
 * long-press rate.  Both live in one obfuscated utility class of
 * {@code com.bilibili.playerbizcommonv2.utils} (9.13: {@code F}, 9.17: {@code J}) that is found
 * by method signature, never by name alone:
 * <pre>
 *   static List  menu(MediaResource, Integer, boolean, int)   // speed entries (float, boolean)
 *   static float longPress(MediaResource)                       // long-press rate
 * </pre>
 * Labels, selection, persistence and the click handler stay Bilibili's own; the player core's
 * ceiling is lifted separately by the native speed-cap hook.
 */
final class SpeedMenuHook {
    private static final String TAG = "BiliExtended";
    private static final String PACKAGE = "com.bilibili.playerbizcommonv2.utils.";
    private static final String MEDIA_RESOURCE = "com.bilibili.lib.media.resource.MediaResource";

    private SpeedMenuHook() {
    }

    static String install(ClassLoader classLoader) {
        Class<?> mediaResource;
        try {
            mediaResource = classLoader.loadClass(MEDIA_RESOURCE);
        } catch (Throwable throwable) {
            return "speed menu hook skipped: MediaResource not found";
        }
        Method menu = null;
        Method longPress = null;
        Class<?> owner = null;
        for (String name : candidateNames()) {
            Class<?> candidate;
            try {
                candidate = Class.forName(PACKAGE + name, false, classLoader);
            } catch (Throwable ignored) {
                continue;
            }
            Method foundMenu = null;
            Method foundLongPress = null;
            try {
                for (Method method : candidate.getDeclaredMethods()) {
                    if (!Modifier.isStatic(method.getModifiers())) {
                        continue;
                    }
                    Class<?>[] parameters = method.getParameterTypes();
                    if (List.class.equals(method.getReturnType()) && parameters.length == 4
                            && mediaResource.equals(parameters[0])
                            && Integer.class.equals(parameters[1])
                            && boolean.class.equals(parameters[2])
                            && int.class.equals(parameters[3])) {
                        foundMenu = method;
                    } else if (float.class.equals(method.getReturnType()) && parameters.length == 1
                            && mediaResource.equals(parameters[0])) {
                        foundLongPress = method;
                    }
                }
            } catch (Throwable ignored) {
                continue;
            }
            if (foundMenu != null) {
                owner = candidate;
                menu = foundMenu;
                longPress = foundLongPress;
                break;
            }
        }
        if (menu == null) {
            return "speed menu hook skipped: speed list builder not found";
        }
        StringBuilder result = new StringBuilder("speed menu hook installed in ")
                .append(owner.getSimpleName());
        try {
            Xp.hookMethod(menu, new MenuHook());
        } catch (Throwable throwable) {
            return "speed menu hook failed=" + throwable.getClass().getSimpleName();
        }
        if (longPress != null) {
            try {
                Xp.hookMethod(longPress, new LongPressHook());
                result.append(" +long-press");
            } catch (Throwable throwable) {
                result.append(" long-press failed=").append(throwable.getClass().getSimpleName());
            }
        } else {
            result.append(" (long-press method not found)");
        }
        result.append(installLowSpeedUi(classLoader));
        return result.toString();
    }

    private static final String WIDGET_PACKAGE = "com.bilibili.playerbizcommonv2.widget.speed.";
    private static final String THIRD_RAW = String.valueOf(SpeedMenuItems.ONE_THIRD);

    /**
     * Bilibili formats speeds with String.valueOf (1/3 -> "0.33333334") and marks the selected row
     * with |speed - current| < 0.1, which makes 1/3 and 0.25 the same row.  Rewrite the label text
     * wherever it reaches a TextView (menu row, toast) and recompute selection with 0.001.
     */
    private static String installLowSpeedUi(ClassLoader classLoader) {
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
        for (String name : candidateNames()) {
            Class<?> candidate;
            try {
                candidate = Class.forName(WIDGET_PACKAGE + name, false, classLoader);
            } catch (Throwable ignored) {
                continue;
            }
            try {
                if (!androidx_adapter(candidate)) {
                    continue;
                }
                for (Method method : candidate.getDeclaredMethods()) {
                    Class<?>[] ps = method.getParameterTypes();
                    if (ps.length == 2 && float.class.equals(ps[0]) && List.class.equals(ps[1])
                            && void.class.equals(method.getReturnType())) {
                        Xp.hookMethod(method, new SelectionHook());
                        try {
                            Xp.hookMethod(candidate.getDeclaredMethod("onClick",
                                    android.view.View.class), new Xp.Hook() {
                                @Override
                                protected void before(Xp.Param param) {
                                    float picked = rowSpeed(((android.view.View) param.args[0]).getTag());
                                    if (!Float.isNaN(picked)) {
                                        lastPicked = picked;
                                    }
                                }
                            });
                        } catch (Throwable ignored) {
                            // selection then follows Bilibili's own current speed only
                        }
                        return out.append(" +select ").append(name).toString();
                    }
                }
            } catch (Throwable ignored) {
                // next
            }
        }
        return out.append(" (select adapter not found)").toString();
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

    private static final class SelectionHook extends Xp.Hook {
        @Override
        protected void after(Xp.Param param) {
            try {
                float current = (Float) param.args[0];
                final float picked = lastPicked;
                if (!Float.isNaN(picked) && Math.abs(current - picked) < 0.1f) {
                    current = picked;
                }
                for (Field f : param.thisObject.getClass().getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || !List.class.isAssignableFrom(f.getType())) {
                        continue;
                    }
                    f.setAccessible(true);
                    for (Object row : (List<?>) f.get(param.thisObject)) {
                        Field speed = null;
                        List<Field> bools = new ArrayList<>();
                        for (Field rf : row.getClass().getDeclaredFields()) {
                            if (Modifier.isStatic(rf.getModifiers())) continue;
                            if (float.class.equals(rf.getType())) speed = rf;
                            else if (boolean.class.equals(rf.getType())) bools.add(rf);
                        }
                        if (speed == null || bools.size() != 2) return;
                        speed.setAccessible(true);
                        // selected flag defaults false, enabled defaults true: pick by declared order "b" (selected).
                        Field selected = null;
                        for (Field b : bools) {
                            if ("b".equals(b.getName()) || false) selected = b;
                        }
                        if (selected == null) selected = bools.get(0);
                        selected.setAccessible(true);
                        selected.setBoolean(row, Math.abs(speed.getFloat(row) - current) < 0.001f);
                    }
                    return;
                }
            } catch (Throwable throwable) {
                Log.w(TAG, "speed selection fix failed=" + throwable);
            }
        }
    }

    /** Single letters first (9.13 F, 9.17 J), then two-letter names. */
    private static List<String> candidateNames() {
        List<String> names = new ArrayList<>();
        String letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        for (int i = 0; i < letters.length(); i++) {
            names.add(String.valueOf(letters.charAt(i)));
        }
        for (int i = 0; i < letters.length(); i++) {
            for (int j = 0; j < letters.length(); j++) {
                names.add("" + letters.charAt(i) + letters.charAt(j));
            }
        }
        return names;
    }

    private static final class MenuHook extends Xp.Hook {
        private Constructor<?> itemConstructor;
        private Field speedField;
        private boolean failureLogged;

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        protected void after(Xp.Param param) {
            try {
                if (!ModulePreferences.loadSpeedMenuExtraForHook()) {
                    return;
                }
                Object returned = param.getResult();
                if (!(returned instanceof List) || ((List) returned).isEmpty()) {
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
