package com.douai.module.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.douai.module.config.AiModelConfig
import com.douai.module.config.AiModelSettings
import com.douai.module.config.HostSettingsStore
import com.douai.module.config.SpeechSettings
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h720dp-mdpi")
class GestureQuickDialogInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var dialog: android.app.AlertDialog? = null
    private var nestedDialog: android.app.AlertDialog? = null
    private val store by lazy { HostSettingsStore(compose.activity) }

    @After fun closeDialogs() {
        compose.runOnUiThread {
            nestedDialog?.dismiss()
            dialog?.dismiss()
        }
    }

    @Test fun featureShortcutsPersistBothAutomationSwitchesAndOpenVoicePicker() {
        var voicePickerOpened = 0
        compose.runOnUiThread {
            clearPreferences()
            dialog = GestureQuickFeaturesDialog.create(
                compose.activity,
                store,
                { voicePickerOpened++ },
                {})
            dialog!!.show()
        }

        compose.onNodeWithTag("settings.toggle.自动评论").assertIsDisplayed().performClick()
        compose.onNodeWithTag("settings.toggle.自动回复").assertIsDisplayed().performClick()
        compose.onNodeWithTag("settings.option.切换音色").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertTrue(store.getBoolean("native_auto_comment", false))
            assertTrue(store.getBoolean("native_auto_reply", false))
            assertEquals(1, voicePickerOpened)
        }
    }

    @Test fun modelQuickSwitchChangesOnlyTheActiveProfile() {
        val first = model("profile-one", "One", "model-one")
        val second = model("profile-two", "Two", "model-two")
        compose.runOnUiThread {
            clearPreferences()
            AiModelSettings(listOf(first, second), first.id).save(store)
            dialog = GestureQuickModelDialog.create(compose.activity, store, {})
            dialog!!.show()
        }

        compose.onNodeWithTag("miuix.dialog.choice.1").assertIsDisplayed().performClick()
        compose.runOnIdle {
            val current = AiModelSettings.read(store)
            assertEquals(second.id, current.activeId)
            assertEquals(2, current.configs.size)
            assertEquals(first.apiEndpoint, current.configs[0].apiEndpoint)
        }
    }

    @Test fun cachedProviderVoiceCanBeChangedWithoutOpeningFullSpeechSettings() {
        compose.runOnUiThread {
            clearPreferences()
            store.putString(SpeechSettings.PROVIDER, SpeechSettings.THIRD_PARTY)
            store.putString(
                "tts_voice_sample",
                "[{\"id\":\"voice-a\",\"name\":\"Voice A\"},{\"id\":\"voice-b\",\"name\":\"Voice B\"}]")
            dialog = GestureQuickVoiceDialog.create(compose.activity, store, {}) { nestedDialog = it }
            dialog!!.show()
        }

        compose.onNodeWithTag("miuix.dialog.choice.1").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals("voice-b", store.getString(SpeechSettings.SELECTED_VOICE, ""))
            assertEquals("Voice B", store.getString(SpeechSettings.SELECTED_VOICE_NAME, ""))
        }
    }

    private fun clearPreferences() {
        compose.activity.applicationContext
            .getSharedPreferences("douai_module_settings", 0)
            .edit()
            .clear()
            .commit()
    }

    private fun model(id: String, name: String, modelName: String): AiModelConfig =
        AiModelConfig.fromJson(
            JSONObject()
                .put("id", id)
                .put("name", name)
                .put("apiEndpoint", "https://example.test/v1")
                .put("modelName", modelName),
        )
}
