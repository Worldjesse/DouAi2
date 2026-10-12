package com.douai.module.feature;

import android.content.ContentValues;
import android.content.Context;
import com.douai.module.config.LocalOptions;
import com.douai.module.scan.HostCandidate;
import com.douai.module.scan.HostScanReport;
import com.douai.module.scan.RuleScanResult;
import io.github.libxposed.api.XposedInterface;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class NativeMessagingHooksTest {
    public static final class Sdk { public long getUid() { return 100; } }
    public static final class Database {
        public Sdk imSdkContext = new Sdk();
        ContentValues received;
        public int update(String table, ContentValues values, String where, String[] arguments) {
            received = values;
            return 1;
        }
        public Boolean local(Message message) { message.ext.put("s:is_recalled", "true"); return true; }
    }
    public static final class Message {
        public ConcurrentHashMap<String, String> ext = new ConcurrentHashMap<>();
        public String getUuid() { return "fixture-uuid"; }
        public ConcurrentHashMap<String, String> getExt() { return ext; }
        public boolean isRecalled() { return "true".equals(ext.get("s:is_recalled")); }
    }

    static final class Framework {
        final Map<Method, XposedInterface.Hooker> hooks = new HashMap<>();
        final AtomicInteger revoked = new AtomicInteger();
        final XposedInterface api = (XposedInterface) Proxy.newProxyInstance(XposedInterface.class.getClassLoader(),
                new Class<?>[]{XposedInterface.class}, (proxy, method, arguments) -> {
                    if (!"hook".equals(method.getName())) return null;
                    Method target = (Method) arguments[0];
                    return Proxy.newProxyInstance(XposedInterface.HookBuilder.class.getClassLoader(),
                            new Class<?>[]{XposedInterface.HookBuilder.class}, (builder, call, values) -> {
                                if (!"intercept".equals(call.getName())) return builder;
                                hooks.put(target, (XposedInterface.Hooker) values[0]);
                                return Proxy.newProxyInstance(XposedInterface.HookHandle.class.getClassLoader(),
                                        new Class<?>[]{XposedInterface.HookHandle.class}, (handle, action, unused) -> {
                                            if ("unhook".equals(action.getName())) revoked.incrementAndGet();
                                            return null;
                                        });
                            });
                });

        Object invoke(Method target, Object receiver, Object... input) throws Throwable {
            XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(
                    XposedInterface.Chain.class.getClassLoader(), new Class<?>[]{XposedInterface.Chain.class},
                    (proxy, method, arguments) -> {
                        switch (method.getName()) {
                            case "getThisObject": return receiver;
                            case "getArg": return input[(Integer) arguments[0]];
                            case "getArgs": return Arrays.asList(input);
                            case "proceed":
                                Object[] selected = arguments == null || arguments.length == 0 ? input : (Object[]) arguments[0];
                                return target.invoke(receiver, selected);
                            default: throw new UnsupportedOperationException(method.getName());
                        }
                    });
            return hooks.get(target).intercept(chain);
        }
    }

    private static HostScanReport report() throws Exception {
        List<RuleScanResult> rules = new ArrayList<>();
        add(rules, "adv_im_db_update", Database.class.getMethod("update", String.class, ContentValues.class, String.class, String[].class));
        add(rules, "adv_im_uid", Sdk.class.getMethod("getUid"));
        add(rules, "adv_im_message_recalled", Message.class.getMethod("isRecalled"));
        add(rules, "adv_im_message_ext", Message.class.getMethod("getExt"));
        add(rules, "adv_im_message_uuid", Message.class.getMethod("getUuid"));
        add(rules, "adv_im_local_recall", Database.class.getMethod("local", Message.class));
        return new HostScanReport("fixture", rules, false);
    }

    private static void add(List<RuleScanResult> rules, String id, Method method) {
        rules.add(new RuleScanResult(id, "message", Collections.singletonList(new HostCandidate("fixture.apk",
                "classes.dex", HostTargets.descriptor(method.getDeclaringClass()), method.getName(), HostTargets.descriptor(method)))));
    }

    private static LocalOptions options(boolean self, boolean other) {
        Map<String, Object> values = new HashMap<>();
        values.put("prevent_message_recall", true);
        values.put("recall_self", self);
        values.put("recall_other", other);
        return new LocalOptions(values);
    }

    @Test public void databaseAndLiveModelAreBothProtectedAndPartialUpdateGetsAMarker() throws Throwable {
        Framework framework = new Framework();
        Context context = RuntimeEnvironment.getApplication();
        NativeMessagingHooks hook = NativeMessagingHooks.install(framework.api, report(), getClass().getClassLoader(),
                context, options(true, true), ignored -> { });
        try {
            Database database = new Database();
            ContentValues values = new ContentValues();
            values.put("ext", "{\"s:is_recalled\":\"true\",\"s:recall_uid\":\"200\"}");
            values.put("content", "撤回提示");
            Method update = Database.class.getMethod("update", String.class, ContentValues.class, String.class, String[].class);
            assertEquals(1, framework.invoke(update, database, "msg", values, "msg_uuid=?", new String[]{"fixture-uuid"}));
            assertNotSame(values, database.received);
            assertFalse(database.received.containsKey("content"));
            assertTrue(context.getSharedPreferences("douai_message_state", Context.MODE_PRIVATE)
                    .getBoolean("recall.100.fixture-uuid", false));
            Message message = new Message();
            message.ext.put("s:is_recalled", "true");
            message.ext.put("s:recall_uid", "200");
            assertEquals(false, framework.invoke(Message.class.getMethod("isRecalled"), message));
            assertEquals("true", message.ext.get("s:is_recalled"));
            hook.setOptions(new LocalOptions(Collections.emptyMap()));
            assertEquals(true, framework.invoke(Message.class.getMethod("isRecalled"), message));
        } finally { hook.close(); }
        assertEquals(3, framework.revoked.get());
    }

    @Test public void localOwnRecallWithoutActorDoesNotUseTheOtherUsersSwitch() throws Throwable {
        Framework framework = new Framework();
        NativeMessagingHooks hook = NativeMessagingHooks.install(framework.api, report(), getClass().getClassLoader(),
                RuntimeEnvironment.getApplication(), options(false, true), ignored -> { });
        try {
            Database database = new Database();
            Message message = new Message();
            framework.invoke(Database.class.getMethod("local", Message.class), database, message);
            assertEquals(true, framework.invoke(Message.class.getMethod("isRecalled"), message));
            hook.setOptions(options(true, false));
            assertEquals(false, framework.invoke(Message.class.getMethod("isRecalled"), message));
        } finally { hook.close(); }
    }

    @Test public void handledStreakResetDoesNotClearNewRecordsAfterProcessRestart() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        android.content.SharedPreferences records = context.getSharedPreferences("douai_message_state", Context.MODE_PRIVATE);
        LocalOptions reset = new LocalOptions(Collections.singletonMap("streak_reset_token", "once"));
        Framework first = new Framework();
        NativeMessagingHooks initial = NativeMessagingHooks.install(first.api, report(), getClass().getClassLoader(),
                context, reset, ignored -> { });
        records.edit().putBoolean("streak.100.2026-10-10.200", true).apply();
        initial.close();
        NativeMessagingHooks restarted = NativeMessagingHooks.install(new Framework().api, report(), getClass().getClassLoader(),
                context, reset, ignored -> { });
        try {
            restarted.setOptions(reset);
            assertTrue(records.getBoolean("streak.100.2026-10-10.200", false));
            assertEquals("once", records.getString("streak.reset_token", ""));
            restarted.setOptions(new LocalOptions(Collections.singletonMap("streak_reset_token", "again")));
            assertFalse(records.contains("streak.100.2026-10-10.200"));
            assertEquals("again", records.getString("streak.reset_token", ""));
        } finally { restarted.close(); }
    }
}
