package com.douai.module.ui;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Proxy;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module")
public final class AdaptationAcknowledgmentTest {
    @Before public void clearState() {
        RuntimeEnvironment.getApplication().getSharedPreferences(
                AdaptationAcknowledgment.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test public void confirmationPersistsForTheSameHostAndModuleIdentity() {
        AdaptationAcknowledgment first = store();
        assertFalse(first.isAcknowledged("host1-module1"));
        assertTrue(first.acknowledge("host1-module1"));
        assertTrue(store().isAcknowledged("host1-module1"));
        assertFalse(store().isAcknowledged("host2-module1"));
        assertFalse(store().isAcknowledged("host1-module2"));
    }

    @Test public void invalidIdentityCannotBeConfirmed() {
        AdaptationAcknowledgment acknowledgment = store();
        assertFalse(acknowledgment.acknowledge(null));
        assertFalse(acknowledgment.acknowledge(""));
        assertFalse(acknowledgment.isAcknowledged(null));
        assertFalse(acknowledgment.isAcknowledged(""));
    }

    @Test public void rollbackOnlyRemovesTheMatchingConfirmation() {
        AdaptationAcknowledgment acknowledgment = store();
        assertTrue(acknowledgment.acknowledge("host1-module1"));
        acknowledgment.forget("other");
        assertTrue(acknowledgment.isAcknowledged("host1-module1"));
        acknowledgment.forget("host1-module1");
        assertFalse(store().isAcknowledged("host1-module1"));
    }

    @Test public void failedDiskCommitDoesNotAcknowledgeItsInMemoryUpdate() {
        assertTrue(store().acknowledge("host1-module1"));
        SharedPreferences delegate = RuntimeEnvironment.getApplication().getSharedPreferences(
                AdaptationAcknowledgment.PREFERENCES, Context.MODE_PRIVATE);
        SharedPreferences failing = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                (proxy, method, arguments) -> {
                    if (!"edit".equals(method.getName())) return method.invoke(delegate, arguments);
                    SharedPreferences.Editor editor = delegate.edit();
                    return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                            new Class<?>[]{SharedPreferences.Editor.class}, (editorProxy, editorMethod, values) -> {
                                Object result = editorMethod.invoke(editor, values);
                                if ("commit".equals(editorMethod.getName())) return false;
                                return result instanceof SharedPreferences.Editor ? editorProxy : result;
                            });
                });
        AdaptationAcknowledgment acknowledgment = new AdaptationAcknowledgment(failing);
        assertFalse(acknowledgment.acknowledge("host1-module2"));
        assertFalse(acknowledgment.isAcknowledged("host1-module2"));
        assertTrue(acknowledgment.isAcknowledged("host1-module1"));
    }

    private static AdaptationAcknowledgment store() {
        return new AdaptationAcknowledgment(RuntimeEnvironment.getApplication());
    }
}
