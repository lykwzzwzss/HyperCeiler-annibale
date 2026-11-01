package com.sevtinge.hyperceiler.libhook.utils.hookapi.faceisland.core;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 补丁内使用的反射缓存。所有宿主/插件对象都通过它访问，避免直接依赖宿主类。
 */
final class FaceIslandReflect {
    private static final Map<Class<?>, Map<String, WeakReference<Field>>> FIELDS = new WeakHashMap<>();
    private static final Map<Class<?>, Map<MethodKey, WeakReference<Method>>> METHODS = new WeakHashMap<>();

    private static final class MethodKey {
        final int hash;
        final String name;
        final boolean[] nulls;
        final WeakReference<Class<?>>[] types;

        MethodKey(String name, Object[] args) {
            this.name = name;
            this.types = new WeakReference[args.length];
            this.nulls = new boolean[args.length];
            int value = name.hashCode();
            for (int i = 0; i < args.length; i++) {
                Class<?> type = args[i] == null ? null : args[i].getClass();
                this.types[i] = new WeakReference<>(type);
                this.nulls[i] = type == null;
                value = (value * 31) + System.identityHashCode(type);
            }
            this.hash = value;
        }

        @Override
        public int hashCode() {
            return this.hash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof MethodKey)) {
                return false;
            }
            MethodKey key = (MethodKey) other;
            if (!this.name.equals(key.name) || this.types.length != key.types.length) {
                return false;
            }
            for (int i = 0; i < this.types.length; i++) {
                if (this.nulls[i] != key.nulls[i]) {
                    return false;
                }
                if (!this.nulls[i] && (this.types[i].get() == null || this.types[i].get() != key.types[i].get())) {
                    return false;
                }
            }
            return true;
        }
    }

    private FaceIslandReflect() {
    }

    static Object field(Object owner, String name) throws Exception {
        Field field;
        if (owner == null) {
            throw new IllegalStateException("字段所属对象尚未绑定: " + name);
        }
        Class<?> ownerType = owner.getClass();
        synchronized (FIELDS) {
            Map<String, WeakReference<Field>> values = FIELDS.get(ownerType);
            WeakReference<Field> cached = values == null ? null : values.get(name);
            field = cached != null ? cached.get() : null;
        }
        if (field == null) {
            Class<?> type = owner.getClass();
            while (true) {
                if (type == null) {
                    break;
                }
                try {
                    field = type.getDeclaredField(name);
                    break;
                } catch (NoSuchFieldException e) {
                    type = type.getSuperclass();
                }
            }
            if (field == null) {
                throw new NoSuchFieldException(ownerType.getName() + "#" + name);
            }
            field.setAccessible(true);
            synchronized (FIELDS) {
                Map<String, WeakReference<Field>> values2 = FIELDS.get(ownerType);
                if (values2 == null) {
                    values2 = new HashMap<>();
                    FIELDS.put(ownerType, values2);
                }
                values2.put(name, new WeakReference<>(field));
            }
        }
        return field.get(owner);
    }

    static Object optionalField(Object owner, String name) {
        try {
            return field(owner, name);
        } catch (Exception e) {
            return null;
        }
    }

    static Object call(Object owner, String name, Object... args) throws Exception {
        Method found;
        if (owner == null) {
            throw new IllegalStateException("方法所属对象尚未绑定: " + name);
        }
        Class<?> ownerType = owner.getClass();
        MethodKey key = new MethodKey(name, args);
        synchronized (METHODS) {
            Map<MethodKey, WeakReference<Method>> values = METHODS.get(ownerType);
            WeakReference<Method> cached = values == null ? null : values.get(key);
            found = cached != null ? cached.get() : null;
        }
        if (found == null) {
            for (Class<?> type = owner.getClass(); type != null && found == null; type = type.getSuperclass()) {
                Method[] declaredMethods = type.getDeclaredMethods();
                int length = declaredMethods.length;
                int i = 0;
                while (true) {
                    if (i < length) {
                        Method method = declaredMethods[i];
                        if (method.getName().equals(name) && method.getParameterTypes().length == args.length) {
                            Class<?>[] parameters = method.getParameterTypes();
                            boolean compatible = true;
                            for (int i2 = 0; i2 < parameters.length; i2++) {
                                Class<?> expected = boxed(parameters[i2]);
                                if (args[i2] != null) {
                                    if (!expected.isInstance(args[i2])) {
                                        compatible = false;
                                        break;
                                    }
                                } else {
                                    if (parameters[i2].isPrimitive()) {
                                        compatible = false;
                                        break;
                                    }
                                }
                            }
                            if (compatible) {
                                found = method;
                                break;
                            }
                        }
                        i++;
                    } else {
                        break;
                    }
                }
            }
            if (found == null) {
                throw new NoSuchMethodException(ownerType.getName() + "#" + name);
            }
            found.setAccessible(true);
            synchronized (METHODS) {
                Map<MethodKey, WeakReference<Method>> values2 = METHODS.get(ownerType);
                if (values2 == null) {
                    values2 = new HashMap<>();
                    METHODS.put(ownerType, values2);
                }
                if (values2.size() > 128) {
                    values2.clear();
                }
                values2.put(key, new WeakReference<>(found));
            }
        }
        try {
            return found.invoke(owner, args);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) {
                throw ((Exception) cause);
            }
            throw new IllegalStateException(name, cause);
        }
    }

    static Object flow(Object stateFlow) throws Exception {
        return call(stateFlow, "getValue", new Object[0]);
    }

    static boolean bool(Object value) {
        return Boolean.TRUE.equals(value);
    }

    static boolean boolField(Object owner, String name) {
        return bool(optionalField(owner, name));
    }

    static int number(Object value, int fallback) {
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    private static Class<?> boxed(Class<?> type) {
        return type == Integer.TYPE ? Integer.class
            : type == Boolean.TYPE ? Boolean.class
            : type == Long.TYPE ? Long.class
            : type == Float.TYPE ? Float.class
            : type == Double.TYPE ? Double.class
            : type;
    }
}
