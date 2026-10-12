package com.douai.module.config;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Collections;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class HostSettingsMigrationTest {
    @Test
    public void importsLegacyValuesExactlyOnceAndThenKeepsHostValuesIndependent() {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences module = context.getSharedPreferences("legacy-module", Context.MODE_PRIVATE);
        SharedPreferences host = context.getSharedPreferences("host-app", Context.MODE_PRIVATE);
        module.edit().clear().putBoolean("hide_ads", true).putString("keyword", "live")
                .putInt("minimum_like_count", 25).putStringSet("hidden_ids", Collections.singleton("one"))
                .commit();
        host.edit().clear().commit();

        assertTrue(HostSettingsMigration.copyOnce(module, host));
        assertTrue(host.getBoolean("hide_ads", false));
        assertEquals("live", host.getString("keyword", ""));
        assertEquals(25, host.getInt("minimum_like_count", 0));
        assertEquals(Collections.singleton("one"), host.getStringSet("hidden_ids", Collections.emptySet()));

        host.edit().putBoolean("hide_ads", false).commit();
        module.edit().putBoolean("hide_ads", true).putString("keyword", "new-module-value").commit();
        assertFalse(HostSettingsMigration.copyOnce(module, host));
        assertFalse(host.getBoolean("hide_ads", true));
        assertEquals("live", host.getString("keyword", ""));
    }

    @Test
    public void recommendationMerchantSettingInheritsLegacyCombinedValueOnce() {
        SharedPreferences preferences = RuntimeEnvironment.getApplication()
                .getSharedPreferences("recommendation-migration", Context.MODE_PRIVATE);
        preferences.edit().clear().putBoolean("hide_recommendation_cards", true).commit();

        SettingsRepository repository = new SettingsRepository(preferences);
        assertTrue(repository.load().hideRecommendationCards);
        assertTrue(repository.load().hideRecommendationMerchants);

        preferences.edit().putBoolean("hide_recommendation_merchants", false).commit();
        assertFalse(repository.load().hideRecommendationMerchants);
    }
}
