package com.douai.module.diagnostics;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.app.PendingIntent;
import android.os.Bundle;
import com.douai.module.BuildConfig;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;

import java.time.Duration;
import java.util.List;
import java.lang.reflect.Method;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = ScanDiagnosticsChannel.HOST_PACKAGE)
public final class DebugLogChannelTest {
    @Test public void exportedHostReceiverRetainsSignatureBoundaryAndIgnoresUnorderedInput() throws Exception {
        Context context = namedContext(ScanDiagnosticsChannel.HOST_PACKAGE);
        BroadcastReceiver receiver = DebugLogChannel.registerHost(context);
        try {
            receiver.onReceive(context, new Intent(DebugLogChannel.ACTION).setPackage(context.getPackageName()).putExtra("operation", "clear"));
            assertEquals(ScanDiagnosticsChannel.PERMISSION, DebugLogChannel.PERMISSION);
            assertEquals(BuildConfig.APPLICATION_ID + ".action.REQUEST_DEBUG_LOG", DebugLogChannel.ACTION);
            List<?> registrations = (List<?>) shadowOf(RuntimeEnvironment.getApplication()).getClass().getMethod("getRegisteredReceivers").invoke(shadowOf(RuntimeEnvironment.getApplication()));
            Object registration = registrations.stream().filter(item -> {
                try { return item.getClass().getField("broadcastReceiver").get(item) == receiver; }
                catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            }).findFirst().orElseThrow(AssertionError::new);
            assertEquals(ScanDiagnosticsChannel.PERMISSION, registration.getClass().getField("broadcastPermission").get(registration));
        } finally { context.unregisterReceiver(receiver); }
    }

    @Test public void systemCreatorProofWorksWhenHostCannotSeeModulePackage() throws Exception {
        Context module = namedContext(BuildConfig.APPLICATION_ID);
        PendingIntent caller = PendingIntent.getBroadcast(module, 391, new Intent("debug-test").setPackage(BuildConfig.APPLICATION_ID), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Object proof = shadowOf(caller);
        proof.getClass().getMethod("setCreatorPackage", String.class).invoke(proof, BuildConfig.APPLICATION_ID);
        proof.getClass().getMethod("setCreatorUid", int.class).invoke(proof, 12345);
        assertEquals(BuildConfig.APPLICATION_ID, caller.getCreatorPackage());
        Object manager = shadowOf(RuntimeEnvironment.getApplication().getPackageManager());
        manager.getClass().getMethod("removePackage", String.class).invoke(manager, BuildConfig.APPLICATION_ID);
        assertTrue(DebugLogChannel.authorized(module, new Intent(DebugLogChannel.ACTION).putExtra("caller", caller)));
        assertFalse(DebugLogChannel.authorized(module, new Intent(DebugLogChannel.ACTION)));
        proof.getClass().getMethod("setCreatorPackage", String.class).invoke(proof, "unrelated.package");
        assertFalse(DebugLogChannel.authorized(module, new Intent(DebugLogChannel.ACTION).putExtra("caller", caller)));
    }

    private static Context namedContext(String packageName) {
        return new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public String getPackageName() { return packageName; }
            @Override public Context getApplicationContext() { return this; }
        };
    }

    private static Object shadowOf(Object target) throws Exception {
        for (Method method : Class.forName("org.robolectric.Shadows").getMethods()) {
            if (method.getName().equals("shadowOf") && method.getParameterCount() == 1 && method.getParameterTypes()[0].isInstance(target)) return method.invoke(null, target);
        }
        throw new NoSuchMethodException("shadowOf");
    }

    @Test public void exportChunksUseOneStableSnapshotAndRejectReplacedOrExpiredSessions() {
        String text = "first-" + "x".repeat(80_000);
        Bundle first = DebugLogChannel.startExport(text);
        String id = first.getString("session");
        StringBuilder joined = new StringBuilder(first.getString("text"));
        while (joined.length() < text.length()) {
            Bundle part = DebugLogChannel.chunk(id, joined.length());
            assertTrue(part.getBoolean("ok")); assertTrue(part.getString("text").length() <= DebugLogChannel.CHUNK_CHARS);
            joined.append(part.getString("text"));
        }
        assertEquals(text, joined.toString());
        DebugLogChannel.startExport("replacement");
        assertFalse(DebugLogChannel.chunk(id, 0).getBoolean("ok"));
        Bundle current = DebugLogChannel.startExport("temporary");
        ShadowSystemClock.advanceBy(Duration.ofSeconds(31));
        assertFalse(DebugLogChannel.chunk(current.getString("session"), 0).getBoolean("ok"));
    }
}
