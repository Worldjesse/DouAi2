package com.douai.module.diagnostics;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import com.douai.module.BuildConfig;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = ScanDiagnosticsChannel.HOST_PACKAGE)
public final class ScanDiagnosticsChannelTest {
    @Test
    public void broadcastNamesFollowTheInstalledModulePackage() {
        assertEquals(BuildConfig.APPLICATION_ID + ".action.REQUEST_SCAN_DIAGNOSTICS", ScanDiagnosticsChannel.ACTION);
        assertEquals(BuildConfig.APPLICATION_ID + ".permission.REQUEST_SCAN_DIAGNOSTICS", ScanDiagnosticsChannel.PERMISSION);
    }

    @Test
    public void registersExportedReceiverBehindSignaturePermission() throws Exception {
        Application host = RuntimeEnvironment.getApplication();
        BroadcastReceiver receiver = ScanDiagnosticsChannel.registerHost(host, Bundle::new);
        Object shadow = shadowOf(host);
        List<?> registrations = (List<?>) shadow.getClass().getMethod("getRegisteredReceivers")
                .invoke(shadow);
        Object registration = registrations.stream().filter(item -> {
            try {
                return item.getClass().getField("broadcastReceiver").get(item) == receiver;
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError(exception);
            }
        }).findFirst().orElseThrow(AssertionError::new);

        assertEquals(ScanDiagnosticsChannel.PERMISSION,
                registration.getClass().getField("broadcastPermission").get(registration));
        int flags = registration.getClass().getField("flags").getInt(registration);
        assertEquals(Context.RECEIVER_EXPORTED, flags & Context.RECEIVER_EXPORTED);
        host.unregisterReceiver(receiver);
    }

    @Test
    public void orderedQueryGetsSnapshotAndUnorderedBroadcastIsIgnored() throws Exception {
        Application host = RuntimeEnvironment.getApplication();
        grantPermission(host, ScanDiagnosticsChannel.PERMISSION);
        AtomicInteger calls = new AtomicInteger();
        BroadcastReceiver receiver = ScanDiagnosticsChannel.registerHost(host, () -> {
            calls.incrementAndGet();
            Bundle snapshot = new Bundle();
            snapshot.putString("summary", "rules=8, unique=8");
            return snapshot;
        });
        try {
            receiver.onReceive(host, new Intent(ScanDiagnosticsChannel.ACTION));
            assertEquals(0, calls.get());

            AtomicReference<Bundle> reply = new AtomicReference<>();
            ScanDiagnosticsChannel.requestForHost(host, host.getPackageName(),
                    new Handler(Looper.getMainLooper()), reply::set);
            idleMainLooper();

            assertEquals(1, calls.get());
            assertNotNull(reply.get());
            assertEquals("rules=8, unique=8", reply.get().getString("summary"));
            Object shadow = shadowOf(host);
            assertNull(shadow.getClass().getMethod("getNextStartedActivity").invoke(shadow));
            assertNull(shadow.getClass().getMethod("getNextStartedService").invoke(shadow));
        } finally {
            host.unregisterReceiver(receiver);
        }
    }

    private static Object shadowOf(Object target) throws ReflectiveOperationException {
        Class<?> shadows = Class.forName("org.robolectric.Shadows");
        for (Method method : shadows.getMethods()) {
            if ("shadowOf".equals(method.getName()) && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].isInstance(target)) {
                return method.invoke(null, target);
            }
        }
        throw new NoSuchMethodException("Shadows.shadowOf(" + target.getClass() + ")");
    }

    private static void grantPermission(Context context, String permission) throws ReflectiveOperationException {
        Object shadow = shadowOf(context.getApplicationContext());
        shadow.getClass().getMethod("grantPermissions", String[].class)
                .invoke(shadow, (Object) new String[]{permission});
    }

    private static void idleMainLooper() throws ReflectiveOperationException {
        Object shadow = shadowOf(Looper.getMainLooper());
        shadow.getClass().getMethod("idle").invoke(shadow);
    }
}
