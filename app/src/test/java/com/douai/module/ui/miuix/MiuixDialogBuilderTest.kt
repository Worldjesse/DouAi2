package com.douai.module.ui.miuix

import android.app.Activity
import android.app.Dialog
import android.widget.EditText
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
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
}
