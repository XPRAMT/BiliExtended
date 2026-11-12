package dev.xpramt.bilisurfacebridge;

import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;

/**
 * Thin helpers over the libxposed API 102: before/after hooks on top of {@code hook().intercept()}, and the reflection helpers the hooks need (the
 * modern API ships none).  A callback never throws into Bilibili: an exception in it is logged and the original call goes on (PASSTHROUGH, so a
 * result or exception set by a callback is what the caller sees).
 */
final class Xp {
    private static final String TAG = "BiliExtended";

    private static volatile XposedModule module;
    private static final Map<Object, Map<String, Object>> EXTRAS = Collections.synchronizedMap(new WeakHashMap<>());

    private Xp() {
    }

    static void init(XposedModule instance) {
        module = instance;
    }

    /**
     * The framework's module log, which LSPosed also writes to logcat (tag LSPosedFramework, as XposedBridge.log did: the measurement tools read
     * logcat); plain logcat only while the framework is not reachable.  Lines carry the build marker so two generations can be told apart.
     */
    static void log(String message) {
        String line = "[" + BuildConfig.BUILD_MARKER + "] " + message;
        XposedModule current = module;
        if (current != null) {
            try {
                current.log(Log.INFO, TAG, line);
                return;
            } catch (Throwable ignored) {
                // fall back to logcat
            }
        }
        Log.i(TAG, line);
    }

    /** One call of a hooked method.  Arguments may be replaced in place; a result set in before skips the original. */
    static final class Param {
        final Object thisObject;
        final Object[] args;
        final Member method;
        private Object result;
        private Throwable throwable;
        private boolean done;
        private Map<String, Object> extras;

        Param(Object thisObject, Object[] args, Member method) {
            this.thisObject = thisObject;
            this.args = args;
            this.method = method;
        }

        Object getResult() {
            return result;
        }

        boolean hasThrowable() {
            return throwable != null;
        }

        Throwable getThrowable() {
            return throwable;
        }

        void setResult(Object value) {
            result = value;
            throwable = null;
            done = true;
        }

        void setThrowable(Throwable value) {
            throwable = value;
            result = null;
            done = true;
        }

        /** State shared between before and after of the same call. */
        void setObjectExtra(String key, Object value) {
            if (extras == null) {
                extras = new HashMap<>();
            }
            extras.put(key, value);
        }

        Object getObjectExtra(String key) {
            return extras == null ? null : extras.get(key);
        }
    }

    abstract static class Hook {
        final int priority;

        Hook() {
            this(XposedInterface.PRIORITY_DEFAULT);
        }

        Hook(int priority) {
            this.priority = priority;
        }

        protected void before(Param param) throws Throwable {
        }

        protected void after(Param param) throws Throwable {
        }
    }

    /** Every method this generation hooked (as text): a hot reload compares the old generation's list with the new one's. */
    private static final Set<String> HOOKED = Collections.synchronizedSet(new java.util.TreeSet<String>());

    static java.util.ArrayList<String> hookedMethods() {
        synchronized (HOOKED) {
            return new java.util.ArrayList<String>(HOOKED);
        }
    }

    static HookHandle hookMethod(Member member, Hook callback) {
        XposedModule current = module;
        if (current == null) {
            throw new IllegalStateException("module not loaded");
        }
        if (!(member instanceof Executable)) {
            throw new IllegalArgumentException("not an executable: " + member);
        }
        HookHandle handle = current.hook((Executable) member)
                .setPriority(callback.priority)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept(chain -> run(chain, callback));
        HOOKED.add(member.getDeclaringClass().getName() + "#" + member.getName() + "/" + ((Executable) member).getParameterCount());
        return handle;
    }

    private static Object run(XposedInterface.Chain chain, Hook callback) throws Throwable {
        if (!Generation.alive()) {
            // Hot reload: this generation handed over (its threads are stopped, its native copy unhooked) but the framework has not removed its
            // hooks yet.  Nothing of the old code may run any more: straight to the original.
            return chain.proceed();
        }
        Param param = new Param(chain.getThisObject(), chain.getArgs().toArray(), chain.getExecutable());
        try {
            callback.before(param);
        } catch (Throwable t) {
            log("hook before failed in " + chain.getExecutable().getName() + ": " + Log.getStackTraceString(t));
            param.result = null;
            param.throwable = null;
            param.done = false;
        }
        if (!param.done) {
            try {
                param.result = chain.proceed(param.args);
            } catch (Throwable t) {
                param.throwable = t;
            }
        }
        try {
            callback.after(param);
        } catch (Throwable t) {
            log("hook after failed in " + chain.getExecutable().getName() + ": " + Log.getStackTraceString(t));
        }
        if (param.throwable != null) {
            throw param.throwable;
        }
        return param.result;
    }

    static Set<HookHandle> hookAllMethods(Class<?> clazz, String name, Hook callback) {
        Set<HookHandle> handles = new HashSet<>();
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getName().equals(name) && !Modifier.isAbstract(method.getModifiers())) {
                handles.add(hookMethod(method, callback));
            }
        }
        return handles;
    }

    static Set<HookHandle> hookAllConstructors(Class<?> clazz, Hook callback) {
        Set<HookHandle> handles = new HashSet<>();
        for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
            handles.add(hookMethod(constructor, callback));
        }
        return handles;
    }

    static HookHandle findAndHookMethod(Class<?> clazz, String name, Object... typesThenHook) {
        Hook callback = lastAsHook(typesThenHook);
        return hookMethod(findMethodExact(clazz, name, resolveTypes(typesThenHook, clazz.getClassLoader())), callback);
    }

    static HookHandle findAndHookMethod(String className, ClassLoader loader, String name, Object... typesThenHook) {
        return findAndHookMethod(findClass(className, loader), name, typesThenHook);
    }

    static HookHandle findAndHookConstructor(Class<?> clazz, Object... typesThenHook) {
        Hook callback = lastAsHook(typesThenHook);
        Class<?>[] types = resolveTypes(typesThenHook, clazz.getClassLoader());
        try {
            Constructor<?> constructor = clazz.getDeclaredConstructor(types);
            constructor.setAccessible(true);
            return hookMethod(constructor, callback);
        } catch (NoSuchMethodException e) {
            throw new NoSuchMethodError(clazz.getName() + "#<init>");
        }
    }

    /** Calls the original method, bypassing every hook on it. */
    static Object invokeOriginalMethod(Member member, Object thisObject, Object[] args) throws Throwable {
        XposedModule current = module;
        if (current == null || !(member instanceof Method)) {
            throw new IllegalStateException("cannot invoke the original of " + member);
        }
        try {
            return current.getInvoker((Method) member).setType(XposedInterface.Invoker.Type.ORIGIN).invoke(thisObject, args);
        } catch (InvocationTargetException e) {
            throw e.getCause() == null ? e : e.getCause();
        }
    }

    private static Hook lastAsHook(Object[] typesThenHook) {
        if (typesThenHook.length == 0 || !(typesThenHook[typesThenHook.length - 1] instanceof Hook)) {
            throw new IllegalArgumentException("last argument must be a Hook");
        }
        return (Hook) typesThenHook[typesThenHook.length - 1];
    }

    private static Class<?>[] resolveTypes(Object[] typesThenHook, ClassLoader loader) {
        Class<?>[] types = new Class<?>[typesThenHook.length - 1];
        for (int i = 0; i < types.length; i++) {
            Object item = typesThenHook[i];
            if (item instanceof Class) {
                types[i] = (Class<?>) item;
            } else if (item instanceof String) {
                types[i] = findClass((String) item, loader);
            } else {
                throw new IllegalArgumentException("parameter type #" + i + " is neither Class nor String");
            }
        }
        return types;
    }

    static Method findMethodExact(Class<?> clazz, String name, Class<?>... types) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                Method method = c.getDeclaredMethod(name, types);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                // try the superclass
            }
        }
        throw new NoSuchMethodError(clazz.getName() + "#" + name);
    }

    // ---- classes and fields ----

    static Class<?> findClass(String name, ClassLoader loader) {
        try {
            return Class.forName(name, false, loader);
        } catch (ClassNotFoundException e) {
            throw new NoClassDefFoundError(name);
        }
    }

    static Class<?> findClassIfExists(String name, ClassLoader loader) {
        try {
            return Class.forName(name, false, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    private static Field findField(Class<?> clazz, String name) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // try the superclass
            }
        }
        throw new NoSuchFieldError(clazz.getName() + "#" + name);
    }

    static Object getObjectField(Object object, String name) {
        try {
            return findField(object.getClass(), name).get(object);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    static void setObjectField(Object object, String name, Object value) {
        try {
            findField(object.getClass(), name).set(object, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    static Object getStaticObjectField(Class<?> clazz, String name) {
        try {
            return findField(clazz, name).get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    static int getIntField(Object object, String name) {
        try {
            return findField(object.getClass(), name).getInt(object);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    static long getLongField(Object object, String name) {
        try {
            return findField(object.getClass(), name).getLong(object);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- calls ----

    static Object callMethod(Object object, String name, Object... args) {
        return invoke(object.getClass(), object, name, args);
    }

    static Object callStaticMethod(Class<?> clazz, String name, Object... args) {
        return invoke(clazz, null, name, args);
    }

    static Object newInstance(Class<?> clazz, Object... args) {
        for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
            if (accepts(constructor.getParameterTypes(), args)) {
                try {
                    constructor.setAccessible(true);
                    return constructor.newInstance(args);
                } catch (InvocationTargetException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    throw new IllegalStateException(clazz.getName() + ".<init> threw " + cause, cause);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        throw new NoSuchMethodError(clazz.getName() + "#<init> with " + args.length + " args");
    }

    private static Object invoke(Class<?> clazz, Object target, String name, Object[] args) {
        Method method = findMethodForArgs(clazz, name, args);
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException(name + " threw " + cause, cause);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Method findMethodForArgs(Class<?> clazz, String name, Object[] args) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                if (method.getName().equals(name) && accepts(method.getParameterTypes(), args)) {
                    method.setAccessible(true);
                    return method;
                }
            }
        }
        for (Class<?> iface : allInterfaces(clazz)) {
            for (Method method : iface.getDeclaredMethods()) {
                if (method.getName().equals(name) && accepts(method.getParameterTypes(), args)) {
                    method.setAccessible(true);
                    return method;
                }
            }
        }
        throw new NoSuchMethodError(clazz.getName() + "#" + name + " with " + args.length + " args");
    }

    private static List<Class<?>> allInterfaces(Class<?> clazz) {
        List<Class<?>> result = new ArrayList<>();
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            for (Class<?> iface : c.getInterfaces()) {
                result.add(iface);
                result.addAll(allInterfaces(iface));
            }
        }
        return result;
    }

    private static boolean accepts(Class<?>[] types, Object[] args) {
        if (types.length != args.length) {
            return false;
        }
        for (int i = 0; i < types.length; i++) {
            Object arg = args[i];
            if (arg == null) {
                if (types[i].isPrimitive()) {
                    return false;
                }
            } else if (!box(types[i]).isInstance(arg)) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == boolean.class) return Boolean.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        return type;
    }

    // ---- extra per-object state ----

    static Object getAdditionalInstanceField(Object object, String key) {
        Map<String, Object> values = EXTRAS.get(object);
        return values == null ? null : values.get(key);
    }

    static void setAdditionalInstanceField(Object object, String key, Object value) {
        synchronized (EXTRAS) {
            Map<String, Object> values = EXTRAS.get(object);
            if (values == null) {
                values = new HashMap<>();
                EXTRAS.put(object, values);
            }
            values.put(key, value);
        }
    }
}
