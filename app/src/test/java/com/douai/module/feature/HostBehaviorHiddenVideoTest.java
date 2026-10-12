package com.douai.module.feature;

import com.douai.module.config.LocalOptions;
import com.douai.module.scan.HostCandidate;
import com.douai.module.scan.HostScanReport;
import com.douai.module.scan.RuleScanResult;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;

import static org.junit.Assert.assertEquals;

/** The full-feed override must be gated as well as its base deletion method. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class HostBehaviorHiddenVideoTest {
    public static class BasePanel {
        int baseDeletes;

        public void deleteAweme(int position, Object aweme) {
            baseDeletes++;
        }
    }

    public static final class FullPanel extends BasePanel {
        int fullDeletes;

        @Override public void deleteAweme(int position, Object aweme) {
            fullDeletes++;
            super.deleteAweme(position, aweme);
        }
    }

    @Test public void preventSkipHiddenBlocksFullFeedOverrideBeforeItDeletesData() throws Throwable {
        Harness harness = new Harness();
        Method base = BasePanel.class.getMethod("deleteAweme", int.class, Object.class);
        Method full = FullPanel.class.getMethod("deleteAweme", int.class, Object.class);
        HostBehaviorHooks hooks = HostBehaviorHooks.install(harness.framework, report(base, full),
                getClass().getClassLoader(), null,
                new LocalOptions(Map.of("prevent_skip_hidden", true)), ignored -> { });
        FullPanel panel = new FullPanel();

        harness.invoke(full, panel, 3, new Object());

        assertEquals(0, panel.fullDeletes);
        assertEquals(0, panel.baseDeletes);
        assertEquals(0, harness.proceeded);
        hooks.close();
    }

    private static HostScanReport report(Method base, Method full) {
        Map<String, Method> methods = new LinkedHashMap<>();
        methods.put("adv_ui_delete_aweme", base);
        methods.put("adv_ui_delete_aweme_full_feed", full);
        ArrayList<RuleScanResult> results = new ArrayList<>();
        for (Map.Entry<String, Method> entry : methods.entrySet()) {
            Method method = entry.getValue();
            results.add(new RuleScanResult(entry.getKey(), "fixture", Collections.singletonList(
                    new HostCandidate("fixture", "classes.dex", HostTargets.descriptor(method.getDeclaringClass()),
                            method.getName(), HostTargets.descriptor(method)))));
        }
        return new HostScanReport("fixture", results, false);
    }

    private static final class Harness {
        int proceeded;
        final Map<Method, XposedInterface.Hooker> interceptors = new LinkedHashMap<>();
        final XposedInterface framework = (XposedInterface) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] { XposedInterface.class },
                (proxy, method, args) -> {
                    if (!method.getName().equals("hook")) return null;
                    Method target = (Method) args[0];
                    return Proxy.newProxyInstance(getClass().getClassLoader(),
                            new Class<?>[] { XposedInterface.HookBuilder.class },
                            (builder, operation, values) -> {
                                if (!operation.getName().equals("intercept")) return builder;
                                interceptors.put(target, (XposedInterface.Hooker) values[0]);
                                return Proxy.newProxyInstance(getClass().getClassLoader(),
                                        new Class<?>[] { XposedInterface.HookHandle.class },
                                        (handle, action, inputs) -> {
                                            if (action.getName().equals("unhook")) interceptors.remove(target);
                                            return null;
                                        });
                            });
                });

        Object invoke(Method target, Object owner, Object... arguments) throws Throwable {
            XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[] { XposedInterface.Chain.class },
                    (proxy, method, args) -> {
                        if (method.getName().equals("getThisObject")) return owner;
                        if (method.getName().equals("getExecutable")) return target;
                        if (method.getName().equals("getArgs")) return java.util.Arrays.asList(arguments);
                        if (method.getName().equals("getArg")) return arguments[(Integer) args[0]];
                        if (method.getName().equals("proceed")) {
                            proceeded++;
                            return target.invoke(owner, args == null ? arguments : (Object[]) args[0]);
                        }
                        return null;
                    });
            XposedInterface.Hooker hooker = interceptors.get(target);
            return hooker == null ? target.invoke(owner, arguments) : hooker.intercept(chain);
        }
    }
}
