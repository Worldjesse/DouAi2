package com.douai.module.feature;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.douai.module.config.LocalOptions;
import com.douai.module.scan.HostCandidate;
import com.douai.module.scan.HostScanReport;
import com.douai.module.scan.RuleScanResult;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class HostBehaviorViewTest {
    public static final class Panel {
        public View i = new View(RuntimeEnvironment.getApplication());
        public View j = new View(RuntimeEnvironment.getApplication());
        public void spaces() { }
    }

    public static final class Profile {
        public final TextView y;
        public final TextView w;
        public final TextView x;
        public final TextView F;
        public final LinearLayout root;
        public boolean own;
        public Profile(Context context) {
            root = new LinearLayout(context);
            y = new TextView(context); w = new TextView(context);
            x = new TextView(context); F = new TextView(context);
            root.addView(y); root.addView(w); root.addView(x); root.addView(F);
            y.setText("12"); w.setText("34"); x.setText("56"); F.setText("78");
        }
        public void bind(Object user) { }
        public boolean own() { return own; }
        public View root() { return root; }
    }

    @Test public void blackSpacesApplyLiveAndRestoreOwnedVisibility() throws Throwable {
        Harness harness = new Harness();
        Method spaces = Panel.class.getMethod("spaces");
        HostBehaviorHooks hooks = HostBehaviorHooks.install(harness.framework, report(Map.of(
                "adv_ui_black_spaces", spaces)), getClass().getClassLoader(), null,
                new LocalOptions(Collections.emptyMap()), ignored -> { });
        Panel panel = new Panel();
        panel.j.setVisibility(View.INVISIBLE);
        harness.invoke(spaces, panel);
        assertEquals(1, harness.proceeded);
        assertEquals(View.VISIBLE, panel.i.getVisibility());
        hooks.setOptions(new LocalOptions(Map.of("hide_video_black_edges", true)));
        assertEquals(View.GONE, panel.i.getVisibility());
        assertEquals(View.GONE, panel.j.getVisibility());
        hooks.setOptions(new LocalOptions(Collections.emptyMap()));
        assertEquals(View.VISIBLE, panel.i.getVisibility());
        assertEquals(View.INVISIBLE, panel.j.getVisibility());
        hooks.close();
    }

    @Test public void restorationPreservesANewHostVisibility() throws Throwable {
        Harness harness = new Harness();
        Method spaces = Panel.class.getMethod("spaces");
        HostBehaviorHooks hooks = HostBehaviorHooks.install(harness.framework, report(Map.of(
                "adv_ui_black_spaces", spaces)), getClass().getClassLoader(), null,
                new LocalOptions(Map.of("hide_video_black_edges", true)), ignored -> { });
        Panel panel = new Panel();
        harness.invoke(spaces, panel);
        panel.i.setVisibility(View.INVISIBLE);
        hooks.close();
        assertEquals(View.INVISIBLE, panel.i.getVisibility());
        assertEquals(View.VISIBLE, panel.j.getVisibility());
    }

    @Test public void profileSpoofOnlyChangesOwnProfileAndRestoresText() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Profile profile = new Profile(activity);
        activity.setContentView(profile.root);
        Harness harness = new Harness();
        Method bind = Profile.class.getMethod("bind", Object.class);
        ProfileSpoofHook hook = ProfileSpoofHook.install(harness.framework, report(Map.of(
                "adv_profile_render", bind, "adv_profile_own", Profile.class.getMethod("own"),
                "adv_profile_root", Profile.class.getMethod("root"))), getClass().getClassLoader(),
                activity, new LocalOptions(Map.of("spoof_profile", true, "profile_like_count", "9001",
                        "profile_following_count", "9002", "profile_follower_count", "9003",
                        "profile_mutual_count", "9004")), ignored -> { });
        harness.invoke(bind, profile);
        assertEquals("12", profile.y.getText().toString());
        profile.own = true;
        harness.invoke(bind, profile);
        assertEquals("9001", profile.y.getText().toString());
        assertEquals("9002", profile.w.getText().toString());
        assertEquals("9003", profile.x.getText().toString());
        assertEquals("9004", profile.F.getText().toString());
        hook.setOptions(new LocalOptions(Collections.emptyMap()));
        assertEquals("12", profile.y.getText().toString());
        assertEquals("34", profile.w.getText().toString());
        assertEquals("56", profile.x.getText().toString());
        assertEquals("78", profile.F.getText().toString());
        hook.close();
        activity.finish();
    }

    private static HostScanReport report(Map<String, Method> methods) {
        java.util.ArrayList<RuleScanResult> results = new java.util.ArrayList<>();
        for (Map.Entry<String, Method> entry : methods.entrySet()) {
            Method method = entry.getValue();
            results.add(new RuleScanResult(entry.getKey(), "fixture", Collections.singletonList(
                    new HostCandidate("fixture", "classes.dex", HostTargets.descriptor(method.getDeclaringClass()),
                            method.getName(), HostTargets.descriptor(method)))));
        }
        return new HostScanReport("fixture", results, false);
    }

    private static final class Harness {
        final Map<Method, XposedInterface.Hooker> interceptors = new LinkedHashMap<>();
        int proceeded;
        final XposedInterface framework = (XposedInterface) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { XposedInterface.class }, (proxy, method, args) -> {
                    if (!method.getName().equals("hook")) return null;
                    Method target = (Method) args[0];
                    return Proxy.newProxyInstance(getClass().getClassLoader(),
                            new Class<?>[] { XposedInterface.HookBuilder.class }, (builder, operation, values) -> {
                                if (!operation.getName().equals("intercept")) return builder;
                                interceptors.put(target, (XposedInterface.Hooker) values[0]);
                                return Proxy.newProxyInstance(getClass().getClassLoader(),
                                        new Class<?>[] { XposedInterface.HookHandle.class }, (handle, action, inputs) -> {
                                            if (action.getName().equals("unhook")) interceptors.remove(target);
                                            return null;
                                        });
                            });
                });

        void invoke(Method target, Object owner) throws Throwable {
            XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] { XposedInterface.Chain.class }, (proxy, method, args) -> {
                        if (method.getName().equals("getThisObject")) return owner;
                        if (method.getName().equals("getExecutable")) return target;
                        if (method.getName().equals("getArgs")) return Collections.emptyList();
                        if (method.getName().startsWith("proceed")) { proceeded++; return null; }
                        return null;
                    });
            interceptors.get(target).intercept(chain);
        }
    }
}
