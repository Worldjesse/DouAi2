package com.douai.module.config;

import android.content.SharedPreferences;
import com.douai.module.DouAiModule;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class ConfigurationSnapshotTest {
    @Test public void allFeatureReadersUseOneCaptureAndIgnoreLaterChanges() {
        Map<String, Object> values = new HashMap<>();
        values.put("automation_enabled", true);
        values.put("auto_play", true);
        values.put("component_hide_enabled", true);
        values.put("component_alpha_like", "64");
        AtomicInteger reads = new AtomicInteger();

        ConfigurationSnapshot snapshot = ConfigurationSnapshot.readFrom(preferences(values, reads));
        values.clear();

        assertEquals(1, reads.get());
        assertTrue(snapshot.features.automationEnabled);
        assertTrue(snapshot.extended.autoPlay);
        assertTrue(snapshot.options.enabled("automation_enabled"));
        assertTrue(snapshot.extended.components.enabled);
        assertEquals(64, snapshot.extended.components.alpha(ComponentHideSettings.Component.LIKE));
        assertEquals(Boolean.TRUE, snapshot.values.get("automation_enabled"));
        ConfigurationSnapshot cleared = ConfigurationSnapshot.readFrom(preferences(values, reads));
        assertFalse(cleared.features.automationEnabled);
        assertFalse(cleared.extended.autoPlay);
        assertFalse(cleared.options.enabled("automation_enabled"));
    }

    @Test public void capturedCollectionsCannotChangeAfterPublication() {
        Set<String> names = new HashSet<>();
        names.add("original");
        Map<String, Object> values = new HashMap<>();
        values.put("names", names);
        ConfigurationSnapshot snapshot = ConfigurationSnapshot.readFrom(preferences(values, new AtomicInteger()));
        names.clear();
        assertEquals(java.util.Collections.singleton("original"), snapshot.values.get("names"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.values.clear());
    }

    @Test public void invalidTypedSettingsRejectTheWholeSnapshot() {
        Map<String, Object> values = new HashMap<>();
        values.put("automation_enabled", true);
        values.put("auto_play", "invalid");
        assertThrows(ClassCastException.class,
                () -> ConfigurationSnapshot.readFrom(preferences(values, new AtomicInteger())));
    }

    @Test public void modulePublishesOneVersionWhenLiveValuesChangeDuringCapture() throws Exception {
        Map<String, Object> live = new HashMap<>();
        live.put("automation_enabled", true);
        live.put("auto_play", true);
        SharedPreferences changing = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                (proxy, method, arguments) -> {
                    if ("getAll".equals(method.getName())) {
                        Map<String, Object> captured = new HashMap<>(live);
                        live.clear();
                        live.put("automation_enabled", false);
                        live.put("auto_play", false);
                        return captured;
                    }
                    throw new AssertionError("Live getter used after capture: " + method.getName());
                });
        DouAiModule module = module();
        invoke(module, "updateConfiguration", changing);

        assertTrue(((FeatureSettings) field(module, "currentSettings")).automationEnabled);
        assertTrue(((ExtendedSettings) field(module, "extendedSettings")).autoPlay);
        assertTrue(((LocalOptions) field(module, "localOptions")).enabled("auto_play"));
        assertEquals(Boolean.TRUE, ((Map<?, ?>) field(module, "lastConfigurationValues")).get("auto_play"));
        assertEquals(Boolean.FALSE, live.get("auto_play"));
    }

    @Test public void moduleRetainsEveryPreviousObjectWhenCaptureOrParsingFails() throws Exception {
        DouAiModule module = module();
        Map<String, Object> values = new HashMap<>();
        values.put("automation_enabled", true);
        values.put("auto_play", true);
        invoke(module, "updateConfiguration", preferences(values, new AtomicInteger()));
        String[] fields = {"currentSettings", "extendedSettings", "localOptions", "lastConfigurationValues"};
        Object[] previous = new Object[fields.length];
        for (int index = 0; index < fields.length; index++) previous[index] = field(module, fields[index]);

        SharedPreferences failing = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                (proxy, method, arguments) -> { throw new IllegalStateException("Read failed"); });
        invoke(module, "updateConfiguration", failing);
        for (int index = 0; index < fields.length; index++) assertSame(previous[index], field(module, fields[index]));

        values.put("automation_enabled", false);
        values.put("auto_play", "invalid");
        invoke(module, "updateConfiguration", preferences(values, new AtomicInteger()));
        for (int index = 0; index < fields.length; index++) assertSame(previous[index], field(module, fields[index]));
    }

    @Test public void moduleCoalescesMultipleKeyNotificationsBeforeReading() throws Exception {
        DouAiModule module = module();
        AtomicInteger reads = new AtomicInteger();
        AtomicReference<SharedPreferences.OnSharedPreferenceChangeListener> listener = new AtomicReference<>();
        SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                (proxy, method, arguments) -> {
                    if ("registerOnSharedPreferenceChangeListener".equals(method.getName())) {
                        listener.set((SharedPreferences.OnSharedPreferenceChangeListener) arguments[0]);
                        return null;
                    }
                    if ("getAll".equals(method.getName())) {
                        reads.incrementAndGet();
                        return java.util.Collections.emptyMap();
                    }
                    throw new AssertionError("Unexpected live getter: " + method.getName());
                });
        invoke(module, "registerSettingsListener", preferences);
        listener.get().onSharedPreferenceChanged(preferences, "automation_enabled");
        listener.get().onSharedPreferenceChanged(preferences, "auto_play");
        listener.get().onSharedPreferenceChanged(preferences, null);
        assertEquals(0, reads.get());
        ShadowLooper.idleMainLooper();
        assertEquals(1, reads.get());
    }

    @Test public void failedImportCannotPublishTransientAutomationStateBeforeRollback() throws Exception {
        android.content.Context context = androidx.test.core.app.ApplicationProvider.getApplicationContext();
        SharedPreferences actual = context.getSharedPreferences("failed-import-publication", 0);
        actual.edit().clear().putBoolean("automation_enabled", false)
                .putBoolean("native_auto_reply", false).commit();
        java.util.concurrent.CountDownLatch committedToMemory = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch returnFailure = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger commits = new AtomicInteger();
        SharedPreferences blocked = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                (proxy, method, arguments) -> {
                    if (!"edit".equals(method.getName())) return method.invoke(actual, arguments);
                    SharedPreferences.Editor editor = actual.edit();
                    return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                            new Class<?>[]{SharedPreferences.Editor.class}, (editorProxy, call, values) -> {
                                Object result = call.invoke(editor, values);
                                if ("commit".equals(call.getName()) && commits.incrementAndGet() == 1) {
                                    committedToMemory.countDown();
                                    assertTrue(returnFailure.await(5, java.util.concurrent.TimeUnit.SECONDS));
                                    return false;
                                }
                                return result == editor ? editorProxy : result;
                            });
                });
        SettingsRepository repository = new SettingsRepository(blocked);
        Map<String, ?> before = repository.snapshot();
        Map<String, Object> replacement = new HashMap<>(before);
        replacement.put("automation_enabled", true);
        replacement.put("native_auto_reply", true);
        DouAiModule module = module();
        invoke(module, "updateConfiguration", blocked);
        invoke(module, "registerSettingsListener", blocked);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread importer = new Thread(() -> {
            try { repository.replaceSnapshot(before, replacement); }
            catch (Throwable error) { failure.set(error); }
        });
        importer.start();
        try {
            assertTrue(committedToMemory.await(2, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue("The failed disk write has already changed live preferences memory",
                    actual.getBoolean("automation_enabled", false));
            ShadowLooper.idleMainLooper();
            assertFalse("A failed import must never enable live automation, even before rollback",
                    ((FeatureSettings) field(module, "currentSettings")).automationEnabled);
            assertFalse(ConfigurationSnapshot.readFrom(blocked).options.enabled("native_auto_reply"));
        } finally {
            returnFailure.countDown();
            importer.join(2000);
            assertFalse("Import must finish its rollback", importer.isAlive());
            ShadowLooper.idleMainLooper();
        }
        assertTrue(failure.get() instanceof IllegalStateException);
        assertEquals(before, actual.getAll());
        assertFalse(((FeatureSettings) field(module, "currentSettings")).automationEnabled);
    }

    @Test public void successfulImportDefersQueuedReloadAndPublishesOneFinalSnapshot() throws Exception {
        android.content.Context context = androidx.test.core.app.ApplicationProvider.getApplicationContext();
        SharedPreferences actual = context.getSharedPreferences("successful-import-publication", 0);
        actual.edit().clear().putBoolean("automation_enabled", false)
                .putBoolean("native_auto_reply", false).commit();
        AtomicInteger captures = new AtomicInteger();
        SharedPreferences counted = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                (proxy, method, arguments) -> {
                    if ("getAll".equals(method.getName())) captures.incrementAndGet();
                    return method.invoke(actual, arguments);
                });
        DouAiModule module = module();
        invoke(module, "updateConfiguration", counted);
        invoke(module, "registerSettingsListener", counted);
        actual.edit().putString("keyword", "reload queued before import").commit();
        ConfigImportTransaction transaction = ConfigImportTransaction.begin(counted, actual.getAll());
        try {
            captures.set(0);
            actual.edit().putBoolean("automation_enabled", true).putBoolean("native_auto_reply", true).commit();
            ShadowLooper.idleMainLooper();
            assertEquals("Queued and new reloads must wait instead of reading imported memory", 0, captures.get());
            assertFalse(((FeatureSettings) field(module, "currentSettings")).automationEnabled);
            assertFalse(ConfigurationSnapshot.readFrom(counted).features.automationEnabled);
        } finally { transaction.close(); }
        ShadowLooper.idleMainLooper();
        assertEquals("All notifications and the queued reload settle into one final publication", 1, captures.get());
        assertTrue(((FeatureSettings) field(module, "currentSettings")).automationEnabled);
        assertTrue(((LocalOptions) field(module, "localOptions")).enabled("native_auto_reply"));
    }

    @Test @Config(sdk = 28)
    public void emptyReplaceImportPublishesDefaultsWhenClearDoesNotNotifyListeners() throws Exception {
        android.content.Context context = androidx.test.core.app.ApplicationProvider.getApplicationContext();
        SharedPreferences actual = context.getSharedPreferences("empty-replace-publication-api28", 0);
        actual.edit().clear().putBoolean("automation_enabled", true)
                .putBoolean("native_auto_reply", true).commit();
        SettingsRepository target = new SettingsRepository(actual);
        SharedPreferences empty = context.getSharedPreferences("empty-backup-api28", 0);
        empty.edit().clear().commit();
        DouAiModule module = module();
        invoke(module, "updateConfiguration", actual);
        invoke(module, "registerSettingsListener", actual);
        assertTrue(((FeatureSettings) field(module, "currentSettings")).automationEnabled);

        String backup = ConfigTransfer.export(new SettingsRepository(empty), false);
        ConfigTransfer.apply(target, ConfigTransfer.inspect(target, backup, ConfigTransfer.Mode.REPLACE));
        ShadowLooper.idleMainLooper();

        assertTrue("An empty replacement must not introduce a notification preference", actual.getAll().isEmpty());
        assertFalse("Android 9 must stop old automation even though clear sends no preference notification",
                ((FeatureSettings) field(module, "currentSettings")).automationEnabled);
        assertFalse(((LocalOptions) field(module, "localOptions")).enabled("native_auto_reply"));
    }

    @Test @Config(sdk = 28)
    public void rebindingModuleReleasesOldImportObserverAndPreferenceListener() throws Exception {
        android.content.Context context = androidx.test.core.app.ApplicationProvider.getApplicationContext();
        SharedPreferences previous = context.getSharedPreferences("previous-import-owner-api28", 0);
        SharedPreferences actual = context.getSharedPreferences("current-import-owner-api28", 0);
        previous.edit().clear().putBoolean("automation_enabled", true).commit();
        actual.edit().clear().putBoolean("automation_enabled", false).commit();
        AtomicInteger captures = new AtomicInteger();
        SharedPreferences current = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                (proxy, method, arguments) -> {
                    if ("getAll".equals(method.getName())) captures.incrementAndGet();
                    return method.invoke(actual, arguments);
                });
        DouAiModule module = module();
        invoke(module, "registerSettingsListener", previous);
        invoke(module, "updateConfiguration", previous);
        ConfigImportTransaction transaction = ConfigImportTransaction.begin(previous, previous.getAll());
        try {
            invoke(module, "registerSettingsListener", current);
            invoke(module, "updateConfiguration", current);
            captures.set(0);
            previous.edit().clear().commit();
        } finally { transaction.close(); }
        previous.edit().putString("keyword", "old preference notification").commit();
        ShadowLooper.idleMainLooper();
        assertEquals("Old imports and preference events must not reload a rebound module", 0, captures.get());
        assertFalse(((FeatureSettings) field(module, "currentSettings")).automationEnabled);

        try (ConfigImportTransaction ignored = ConfigImportTransaction.begin(current, actual.getAll())) {
            captures.set(0);
            actual.edit().clear().commit();
        }
        ShadowLooper.idleMainLooper();
        assertEquals("The new registration must still publish clear-only imports", 1, captures.get());
    }

    @Test public void settledCallbacksAreDeduplicatedPerOwnerAndClosingTwiceDoesNotRepeatThem() {
        android.content.Context context = androidx.test.core.app.ApplicationProvider.getApplicationContext();
        SharedPreferences preferences = context.getSharedPreferences("import-owner-dedup", 0);
        preferences.edit().clear().putBoolean("automation_enabled", false).commit();
        Object firstOwner = new Object(), secondOwner = new Object();
        AtomicInteger first = new AtomicInteger(), second = new AtomicInteger();
        ConfigImportTransaction.Registration registration = ConfigImportTransaction.observe(
                preferences, firstOwner, first::incrementAndGet);
        ConfigImportTransaction transaction = ConfigImportTransaction.begin(preferences, preferences.getAll());
        try {
            for (int index = 0; index < 5; index++) {
                assertTrue(ConfigImportTransaction.deferUntilSettled(preferences, firstOwner, first::incrementAndGet));
                assertNull(ConfigurationSnapshot.readForPublication(preferences, firstOwner, first::incrementAndGet));
                assertTrue(ConfigImportTransaction.deferUntilSettled(preferences, secondOwner, second::incrementAndGet));
            }
            assertEquals(0, first.get());
            assertEquals(0, second.get());
        } finally { transaction.close(); registration.close(); }
        transaction.close();
        assertEquals(1, first.get());
        assertEquals(1, second.get());
        assertFalse(ConfigImportTransaction.deferUntilSettled(preferences, firstOwner, first::incrementAndGet));
    }

    private static DouAiModule module() {
        DouAiModule module = new DouAiModule();
        XposedInterface framework = (XposedInterface) Proxy.newProxyInstance(
                XposedInterface.class.getClassLoader(), new Class<?>[]{XposedInterface.class},
                (proxy, method, arguments) -> null);
        module.attachFramework(framework, () -> { });
        return module;
    }

    private static void invoke(DouAiModule module, String methodName, SharedPreferences preferences) throws Exception {
        Method method = DouAiModule.class.getDeclaredMethod(methodName, SharedPreferences.class);
        method.setAccessible(true);
        method.invoke(module, preferences);
    }

    private static Object field(DouAiModule module, String fieldName) throws Exception {
        Field field = DouAiModule.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(module);
    }

    private static SharedPreferences preferences(Map<String, ?> values, AtomicInteger reads) {
        return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, (proxy, method, arguments) -> {
                    if ("getAll".equals(method.getName())) {
                        reads.incrementAndGet();
                        return values;
                    }
                    throw new AssertionError("Live preferences accessed after capture: " + method.getName());
                });
    }
}
