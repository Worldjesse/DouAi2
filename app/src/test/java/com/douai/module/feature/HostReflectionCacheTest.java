package com.douai.module.feature;

import org.junit.Test;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public final class HostReflectionCacheTest {
    public static class Parent { private String inherited = "parent"; }
    public static final class Model extends Parent {
        public String value = "first";
        public boolean fail;
        public String value() {
            if (fail) throw new IllegalStateException("temporarily unavailable");
            return value;
        }
        public String overloaded() { return "zero"; }
        public String overloaded(String value) { return value; }
    }

    @Test public void repeatedAccessResolvesOnceAndReadsCurrentValues() throws Exception {
        CountingResolver resolver = new CountingResolver();
        HostReflectionCache cache = new HostReflectionCache(resolver, 128);
        Model owner = new Model();
        for (int i = 0; i < 1000; i++) {
            owner.value = Integer.toString(i);
            assertEquals(owner.value, cache.field(Model.class, "value").get(owner));
            assertEquals(owner.value, cache.publicNoArgMethod(Model.class, "value").invoke(owner));
        }
        assertEquals(1, resolver.fields.get());
        assertEquals(1, resolver.methods.get());
        assertEquals("parent", cache.field(Model.class, "inherited").get(owner));
    }

    @Test public void repeatedMissingAccessIsNegativelyCached() {
        CountingResolver resolver = new CountingResolver();
        HostReflectionCache cache = new HostReflectionCache(resolver, 128);
        for (int i = 0; i < 1000; i++) {
            assertNull(cache.field(Model.class, "missing"));
            assertNull(cache.publicNoArgMethod(Model.class, "missing"));
            assertNull(cache.declaredMethod(Model.class, "missing", "()V"));
        }
        assertEquals(1, resolver.fields.get());
        assertEquals(1, resolver.methods.get());
        assertEquals(1, resolver.declared.get());
    }

    @Test public void cachedMethodsKeepFullSignaturesAndInvocationFailuresDoNotPoisonThem() throws Exception {
        CountingResolver resolver = new CountingResolver();
        HostReflectionCache cache = new HostReflectionCache(resolver, 128);
        Model owner = new Model();
        Method zero = cache.declaredMethod(Model.class, "overloaded", "()Ljava/lang/String;");
        Method one = cache.declaredMethod(Model.class, "overloaded", "(Ljava/lang/String;)Ljava/lang/String;");
        assertEquals("zero", zero.invoke(owner));
        assertEquals("one", one.invoke(owner, "one"));
        assertEquals(2, resolver.declared.get());
        owner.fail = true;
        assertNull(HostTargets.invoke(owner, "value"));
        owner.fail = false; owner.value = "recovered";
        assertEquals("recovered", HostTargets.invoke(owner, "value"));
        assertEquals("recovered", HostTargets.read(owner, "value"));
        assertEquals("parent", HostTargets.read(owner, "inherited"));
        assertNull(HostTargets.read(owner, "missing"));
        assertThrows(NoSuchFieldException.class, () -> HostTargets.field(Model.class, "missing"));
    }

    @Test public void cacheIsBoundedIncludingMissingMembers() {
        CountingResolver resolver = new CountingResolver();
        HostReflectionCache cache = new HostReflectionCache(resolver, 2);
        assertNotNull(cache.field(Model.class, "value"));
        assertNull(cache.field(Model.class, "missing-one"));
        assertNull(cache.field(Model.class, "missing-two"));
        assertNotNull(cache.field(Model.class, "value"));
        assertEquals("an evicted accessor is safely resolved again", 4, resolver.fields.get());
    }

    @Test public void reclaimedWeakCacheCanBeRebuiltWithoutRetainingOldModelValues() throws Exception {
        CountingResolver resolver = new CountingResolver();
        HostReflectionCache cache = new HostReflectionCache(resolver, 128);
        Model owner = new Model();
        assertEquals("first", cache.field(Model.class, "value").get(owner));
        Field entries = HostReflectionCache.class.getDeclaredField("classes");
        entries.setAccessible(true);
        java.util.Map<?, ?> classes = (java.util.Map<?, ?>) entries.get(cache);
        synchronized (classes) {
            assertEquals(1, classes.size());
            Object reference = classes.get(Model.class);
            assertTrue("Values must also be weak: Field and Method retain their declaring Class",
                    reference instanceof java.lang.ref.WeakReference<?>);
            ((java.lang.ref.WeakReference<?>) reference).clear();
        }
        owner.value = "after collection";
        assertEquals("after collection", cache.field(Model.class, "value").get(owner));
        assertEquals(2, resolver.fields.get());
    }

    @Test public void sameNamedClassesFromDifferentLoadersNeverShareMembers() throws Exception {
        String name = Model.class.getName();
        byte[] bytes;
        try (InputStream input = Model.class.getResourceAsStream("/" + name.replace('.', '/') + ".class")) {
            assertNotNull(input);
            bytes = input.readAllBytes();
        }
        String parentName = Parent.class.getName();
        byte[] parentBytes;
        try (InputStream input = Parent.class.getResourceAsStream("/" + parentName.replace('.', '/') + ".class")) {
            assertNotNull(input);
            parentBytes = input.readAllBytes();
        }
        ByteLoader first = new ByteLoader(parentName, parentBytes);
        ByteLoader second = new ByteLoader(parentName, parentBytes);
        Class<?> left = first.define(name, bytes);
        Class<?> right = second.define(name, bytes);
        HostReflectionCache cache = new HostReflectionCache();
        assertNotSame(left, right);
        assertSame(left, cache.field(left, "value").getDeclaringClass());
        assertSame(right, cache.field(right, "value").getDeclaringClass());
        assertSame(left, cache.publicNoArgMethod(left, "value").getDeclaringClass());
        assertSame(right, cache.publicNoArgMethod(right, "value").getDeclaringClass());
    }

    @Test public void concurrentFirstAccessPerformsOneResolution() throws Exception {
        CountingResolver resolver = new CountingResolver();
        HostReflectionCache cache = new HostReflectionCache(resolver, 128);
        ExecutorService workers = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> tasks = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) tasks.add(workers.submit(() -> {
                start.await();
                for (int j = 0; j < 250; j++) {
                    assertNotNull(cache.field(Model.class, "value"));
                    assertNull(cache.publicNoArgMethod(Model.class, "missing"));
                }
                return null;
            }));
            start.countDown();
            for (Future<?> task : tasks) task.get(5, TimeUnit.SECONDS);
            assertEquals(1, resolver.fields.get());
            assertEquals(1, resolver.methods.get());
        } finally { workers.shutdownNow(); }
    }

    private static final class CountingResolver implements HostReflectionCache.Resolver {
        final AtomicInteger fields = new AtomicInteger();
        final AtomicInteger methods = new AtomicInteger();
        final AtomicInteger declared = new AtomicInteger();
        @Override public Field field(Class<?> owner, String name) throws NoSuchFieldException {
            fields.incrementAndGet(); return HostReflectionCache.REFLECTION.field(owner, name);
        }
        @Override public Method publicNoArgMethod(Class<?> owner, String name) throws NoSuchMethodException {
            methods.incrementAndGet(); return HostReflectionCache.REFLECTION.publicNoArgMethod(owner, name);
        }
        @Override public Method declaredMethod(Class<?> owner, String name, String descriptor)
                throws NoSuchMethodException {
            declared.incrementAndGet(); return HostReflectionCache.REFLECTION.declaredMethod(owner, name, descriptor);
        }
    }

    private static final class ByteLoader extends ClassLoader {
        ByteLoader(String parentName, byte[] parentBytes) { super(null); defineClass(parentName, parentBytes, 0, parentBytes.length); }
        Class<?> define(String name, byte[] bytes) { return defineClass(name, bytes, 0, bytes.length); }
    }
}
