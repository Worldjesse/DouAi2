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
