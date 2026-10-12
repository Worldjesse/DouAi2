package com.douai.module.ui;

import android.app.Activity;
import android.app.Dialog;
import android.widget.Button;

import com.douai.module.config.AiModelConfig;
import com.douai.module.config.AiModelSettings;
import com.douai.module.config.HostSettingsStore;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class ModelActivationStateTest {
    @Test public void switchingModelsUpdatesBothButtonsAndAllowsSwitchingBack() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        HostSettingsStore store = new HostSettingsStore(activity);
        ModuleSettingsScreen screen = new ModuleSettingsScreen(activity, store);
        try {
            AiModelConfig first = profile("first"), second = profile("second");
            new AiModelSettings(Arrays.asList(first, second), first.id).save(store);
            screen.createView();
            Method open = ModuleSettingsScreen.class.getDeclaredMethod("showAiModelSettings");
            open.setAccessible(true);
            open.invoke(screen);
            Dialog dialog = ShadowDialog.getLatestDialog();
            Button a = dialog.getWindow().getDecorView().findViewWithTag("ai.activate.first");
            Button b = dialog.getWindow().getDecorView().findViewWithTag("ai.activate.second");
            assertFalse(a.isEnabled());
            assertTrue(b.performClick());
            assertEquals("second", AiModelSettings.read(store).activeId);
            assertEquals("启用", a.getText().toString());
            assertTrue(a.isEnabled());
            assertEquals("已启用", b.getText().toString());
            assertFalse(b.isEnabled());
            assertTrue(a.performClick());
            assertEquals("first", AiModelSettings.read(store).activeId);
            assertFalse(a.isEnabled());
            assertTrue(b.isEnabled());
        } finally {
            screen.close();
            activity.finish();
        }
    }

    private static AiModelConfig profile(String id) throws Exception {
        return AiModelConfig.fromJson(new JSONObject().put("id", id).put("name", id)
                .put("apiEndpoint", "https://example.test/v1").put("modelName", "model"));
    }
}
