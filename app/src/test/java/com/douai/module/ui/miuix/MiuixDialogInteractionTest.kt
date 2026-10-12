package com.douai.module.ui.miuix

import android.app.AlertDialog
import android.app.Dialog
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h720dp-mdpi")
class MiuixDialogInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var dialog: AlertDialog? = null

    @After fun closeDialog() { compose.runOnUiThread { dialog?.dismiss() } }

    @Test fun messageAndDynamicActionStateRenderInMiuixAndValidationCanKeepTheDialogOpen() {
        var saved = 0
        compose.runOnUiThread {
            dialog = MiuixDialogBuilder(compose.activity, true).setTitle("模型配置")
                .setMessage("完整消息正文").setNegativeButton("取消", null)
                .setNeutralButton("删除", null).setPositiveButton("保存并启用", null).create()
            dialog!!.show()
            dialog!!.getButton(Dialog.BUTTON_NEUTRAL).visibility = View.GONE
            dialog!!.getButton(Dialog.BUTTON_POSITIVE).isEnabled = false
            dialog!!.getButton(Dialog.BUTTON_POSITIVE).setOnClickListener { saved++ }
        }
        compose.onNodeWithText("完整消息正文").assertIsDisplayed()
        compose.onNodeWithTag("miuix.dialog.button.${Dialog.BUTTON_NEUTRAL}").assertDoesNotExist()
        compose.onNodeWithTag("miuix.dialog.button.${Dialog.BUTTON_POSITIVE}").assertIsNotEnabled()
        compose.runOnUiThread { dialog!!.getButton(Dialog.BUTTON_POSITIVE).isEnabled = true }
        compose.onNodeWithTag("miuix.dialog.button.${Dialog.BUTTON_POSITIVE}").performClick()
        compose.runOnIdle {
            assertEquals(1, saved)
            assertTrue(dialog!!.isShowing)
        }
        compose.onNodeWithTag("miuix.dialog.button.${Dialog.BUTTON_NEGATIVE}").performClick()
        compose.runOnIdle { assertFalse(dialog!!.isShowing) }
    }

    @Test fun singleChoiceKeepsTheDialogOpenAndUsesTheOriginalCallback() {
        var selected = -1
        compose.runOnUiThread {
            dialog = MiuixDialogBuilder(compose.activity, true).setTitle("回复模式")
                .setSingleChoiceItems(arrayOf("固定", "AI"), 0) { _, index -> selected = index }
                .setPositiveButton("保存", null).create()
            dialog!!.show()
        }
        compose.onNodeWithTag("miuix.dialog.choice.1").performClick()
        compose.runOnIdle {
            assertEquals(1, selected)
            assertEquals(1, dialog!!.listView.checkedItemPosition)
            assertTrue(dialog!!.isShowing)
        }
    }
}
