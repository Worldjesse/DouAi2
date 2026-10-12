package com.douai.module.config;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class ComponentHideSettingsTest {
    private SharedPreferences preferences() {
        SharedPreferences prefs = RuntimeEnvironment.getApplication()
                .getSharedPreferences("component-test", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        return prefs;
    }

    @Test
    public void startsDisabledWithIndependentOpaqueComponents() {
        ComponentHideSettings settings = ComponentHideSettings.readFrom(preferences());
        assertFalse(settings.configured);
        assertFalse(settings.enabled);
        assertFalse(settings.uniformEnabled);
        assertTrue(settings.hideOnSwitch);
        assertTrue(settings.showOnPause);
        assertFalse(settings.openCommentsOnShow);
        for (ComponentHideSettings.Component component : ComponentHideSettings.Component.values()) {
            assertEquals(255, settings.alpha(component));
        }
    }

    @Test
    public void preservesLegacyActivationUntilTheNewMasterIsSaved() {
        SharedPreferences prefs = preferences();
        prefs.edit().putBoolean("hide_interface", true).commit();
        assertTrue(ComponentHideSettings.readFrom(prefs).enabled);
        assertFalse(ComponentHideSettings.readFrom(prefs).configured);
        prefs.edit().putBoolean(ComponentHideSettings.ENABLED, false).commit();
        ComponentHideSettings settings = ComponentHideSettings.readFrom(prefs);
        assertTrue(settings.configured);
        assertFalse(settings.enabled);
    }

    @Test
    public void firstEditMigratesExistingOpacityAndKeepsNavigationUntouched() {
        SharedPreferences prefs = preferences();
        prefs.edit().putString("video_right_alpha", "35").commit();
        SettingsRepository repository = new SettingsRepository(prefs);
        ComponentHideSettings.initialize(repository);
        ComponentHideSettings migrated = ComponentHideSettings.readFrom(prefs);
        assertTrue(migrated.configured);
        assertTrue(migrated.enabled);
        assertEquals(89, migrated.alpha(ComponentHideSettings.Component.LIKE));
        assertEquals(255, migrated.alpha(ComponentHideSettings.Component.TOP_NAV));
        assertEquals(255, migrated.alpha(ComponentHideSettings.Component.DESCRIPTION));
        repository.putString(ComponentHideSettings.Component.LIKE.key, "70");
        ComponentHideSettings.initialize(repository);
        assertEquals(70, ComponentHideSettings.readFrom(prefs).alpha(ComponentHideSettings.Component.LIKE));
    }

    @Test
    public void uniformOverrideDoesNotEraseIndividualChoices() {
        SharedPreferences prefs = preferences();
        prefs.edit().putString(ComponentHideSettings.Component.LIKE.key, "-2")
                .putString(ComponentHideSettings.Component.PROGRESS.key, "70")
                .putBoolean(ComponentHideSettings.UNIFORM_ENABLED, true)
                .putString(ComponentHideSettings.UNIFORM_ALPHA, "-1").commit();
        ComponentHideSettings uniform = ComponentHideSettings.readFrom(prefs);
        assertEquals(-1, uniform.alpha(ComponentHideSettings.Component.LIKE));
        assertEquals(70, uniform.individualAlpha(ComponentHideSettings.Component.PROGRESS));
        prefs.edit().putBoolean(ComponentHideSettings.UNIFORM_ENABLED, false).commit();
        ComponentHideSettings independent = ComponentHideSettings.readFrom(prefs);
        assertEquals(-2, independent.alpha(ComponentHideSettings.Component.LIKE));
        assertEquals(70, independent.alpha(ComponentHideSettings.Component.PROGRESS));
    }

    @Test
    public void invalidStoredValuesFallBackAndBothAdaptersAgree() {
        SharedPreferences prefs = preferences();
        prefs.edit().putBoolean(ComponentHideSettings.ENABLED, true)
                .putString(ComponentHideSettings.Component.AVATAR.key, "-3")
                .putString(ComponentHideSettings.Component.PROGRESS.key, "256")
                .putString(ComponentHideSettings.UNIFORM_ALPHA, "nan").commit();
        ComponentHideSettings direct = ComponentHideSettings.readFrom(prefs);
        ComponentHideSettings store = ComponentHideSettings.readFrom(new SettingsRepository(prefs));
        assertEquals(255, direct.individualAlpha(ComponentHideSettings.Component.AVATAR));
        assertEquals(255, direct.individualAlpha(ComponentHideSettings.Component.PROGRESS));
        assertEquals(255, direct.uniformAlpha);
        assertEquals(direct.enabled, store.enabled);
        assertEquals(direct.configured, store.configured);
        assertEquals(-2, ComponentHideSettings.parseAlpha("-2", 255));
        assertEquals(-1, ComponentHideSettings.parseAlpha("-1", 255));
        assertEquals(0, ComponentHideSettings.parseAlpha("0", 255));
    }
}
