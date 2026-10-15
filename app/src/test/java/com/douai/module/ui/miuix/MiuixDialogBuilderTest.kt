package com.douai.module.ui.miuix

import android.app.Activity
import android.app.Dialog
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.drawable.GradientDrawable
import android.widget.EditText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], packageName = "com.douai.module")
class MiuixDialogBuilderTest {
    private lateinit var activity: Activity

    @Before fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    }

    @Test fun promptSaveCallbackReceivesTheFullEditedValue() {
        var saved: String? = null
        val dialog = MiuixDialogBuilder.prompt(activity, false, "一级提示词", "原内容") { saved = it }
        dialog.show()
        dialog.window!!.decorView.findViewWithTag<EditText>("settings.input.一级提示词")
            .setText(" 记忆属性\n".repeat(3000))
        dialog.getButton(Dialog.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()
        assertEquals(" 记忆属性\n".repeat(3000), saved)
    }

    @Test fun nativeFallbackChangesWindowAndTextColorsWithoutLosingEditsOrSelection() {
        RuntimeEnvironment.setQualifiers("+notnight")
        val dialog = MiuixDialogBuilder.prompt(activity, false, "native theme", "initial") {}
        dialog.show()
        ShadowLooper.idleMainLooper()
        val input = dialog.window!!.decorView.findViewWithTag<EditText>("settings.input.native theme")
        input.setText("unsaved draft")
        input.setSelection(3)
        val lightText = input.currentTextColor
        val lightBackground = (dialog.window!!.decorView.background as GradientDrawable).color!!.defaultColor
        try {
            RuntimeEnvironment.setQualifiers("+night")
            activity.application.onConfigurationChanged(Configuration(Resources.getSystem().configuration))
            ShadowLooper.idleMainLooper()
            assertNotEquals(lightText, input.currentTextColor)
            assertNotEquals(lightBackground,
                (dialog.window!!.decorView.background as GradientDrawable).color!!.defaultColor)
            assertSame(input, dialog.window!!.decorView.findViewWithTag("settings.input.native theme"))
            assertEquals("unsaved draft", input.text.toString())
            assertEquals(3, input.selectionStart)

            RuntimeEnvironment.setQualifiers("+notnight")
            activity.application.onConfigurationChanged(Configuration(Resources.getSystem().configuration))
            ShadowLooper.idleMainLooper()
            assertEquals(lightText, input.currentTextColor)
            assertEquals(lightBackground,
                (dialog.window!!.decorView.background as GradientDrawable).color!!.defaultColor)
            assertEquals("unsaved draft", input.text.toString())
            assertEquals(3, input.selectionStart)
        } finally {
            dialog.dismiss()
        }
    }
}
