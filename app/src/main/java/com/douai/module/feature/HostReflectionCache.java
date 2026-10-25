package com.douai.module.feature;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** Caches accessors, never host values. Both weak keys and weak values allow a host loader to retire. */
final class HostReflectionCache {
    static final int DEFAULT_CAPACITY = 64;

    interface Resolver {
        Field field(Class<?> owner, String name) throws NoSuchFieldException;
        Method publicNoArgMethod(Class<?> owner, String name) throws NoSuchMethodException;
        Method declaredMethod(Class<?> owner, String name, String descriptor) throws NoSuchMethodException;
    }

    static final Resolver REFLECTION = new Resolver() {
        @Override public Field field(Class<?> owner, String name) throws NoSuchFieldException {
            for (Class<?> current = owner; current != null; current = current.getSuperclass()) {
                try {
                    Field field = current.getDeclaredField(name);
                    field.setAccessible(true);
                    return field;
                } catch (NoSuchFieldException ignored) { }
            }
            throw new NoSuchFieldException(owner.getName() + "." + name);
        }

        @Override public Method publicNoArgMethod(Class<?> owner, String name) throws NoSuchMethodException {
            // Match Class.getMethod semantics: inherited public methods are allowed.
            return owner.getMethod(name);
        }

        @Override public Method declaredMethod(Class<?> owner, String name, String descriptor)
                throws NoSuchMethodException {
            Method match = null;
            for (Method method : owner.getDeclaredMethods()) {
                if (!method.getName().equals(name) || !HostTargets.descriptor(method).equals(descriptor)) continue;
                if (match != null) throw new IllegalStateException("Ambiguous reflected method: " + name);
                match = method;
            }
            if (match == null) throw new NoSuchMethodException(owner.getName() + "." + name + descriptor);
            match.setAccessible(true);
            return match;
        }
    };

    private static final Resolution MISSING = new Resolution(null);
    private final Resolver resolver;
    private final int capacity;
    // Field/Method values point back to their declaring Class, so weak keys alone are insufficient.
    // Weak values break that cycle. A GC may evict accessors; the next call safely resolves them again.
    private final Map<Class<?>, WeakReference<Accessors>> classes = new WeakHashMap<>();

    HostReflectionCache() { this(REFLECTION, DEFAULT_CAPACITY); }

    HostReflectionCache(Resolver resolver, int capacity) {
        if (resolver == null || capacity < 1) throw new IllegalArgumentException("Invalid accessor cache");
        this.resolver = resolver;
        this.capacity = capacity;
    }

    Field field(Class<?> owner, String name) {
        Accessors accessors = accessors(owner);
        synchronized (accessors) {
            Resolution resolved = accessors.fields.get(name);
            if (resolved == null) {
                try { resolved = new Resolution(resolver.field(owner, name)); }
                catch (NoSuchFieldException absent) { resolved = MISSING; }
                accessors.fields.put(name, resolved);
            }
            return (Field) resolved.member;
        }
    }

    Method publicNoArgMethod(Class<?> owner, String name) {
        Accessors accessors = accessors(owner);
        synchronized (accessors) {
            Resolution resolved = accessors.publicMethods.get(name);
            if (resolved == null) {
                try { resolved = new Resolution(resolver.publicNoArgMethod(owner, name)); }
                catch (NoSuchMethodException absent) { resolved = MISSING; }
                accessors.publicMethods.put(name, resolved);
            }
            return (Method) resolved.member;
        }
    }

    Method declaredMethod(Class<?> owner, String name, String descriptor) {
        Accessors accessors = accessors(owner);
        synchronized (accessors) {
            String key = name + descriptor;
            Resolution resolved = accessors.declaredMethods.get(key);
            if (resolved == null) {
                try { resolved = new Resolution(resolver.declaredMethod(owner, name, descriptor)); }
                catch (NoSuchMethodException absent) { resolved = MISSING; }
                // Access failures and invocation failures are not evidence that a member is absent.
                accessors.declaredMethods.put(key, resolved);
            }
            return (Method) resolved.member;
        }
    }

    private Accessors accessors(Class<?> owner) {
        synchronized (classes) {
            WeakReference<Accessors> reference = classes.get(owner);
            Accessors cached = reference == null ? null : reference.get();
            if (cached == null) {
                cached = new Accessors();
                classes.put(owner, new WeakReference<>(cached));
            }
            return cached;
        }
    }

    private final class Accessors {
        // Separate maps let hot field/getter hits reuse the supplied name without allocating keys or lambdas.
        final Map<String, Resolution> fields = bounded();
        final Map<String, Resolution> publicMethods = bounded();
        final Map<String, Resolution> declaredMethods = bounded();
    }

    private Map<String, Resolution> bounded() {
        return new LinkedHashMap<>(16, .75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Resolution> eldest) {
                return size() > capacity;
            }
        };
    }

    private static final class Resolution {
        final AccessibleObject member;
        Resolution(AccessibleObject member) { this.member = member; }
    }
}
