package com.douai.module.ui.miuix

import android.app.AlertDialog
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.SemanticsMatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h720dp-mdpi")
class MiuixFormControlsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @After fun closeChoices() {
        compose.runOnUiThread { ShadowDialog.getLatestDialog()?.dismiss() }
    }

    @Test fun inputUsesNativeFiltersWatchersErrorsAndPreservesAnUnchangedSelection() {
        lateinit var input: EditText
        lateinit var parent: LinearLayout
        var changed = 0
        var posted = false
        compose.runOnUiThread {
            input = MiuixFormControls.input(compose.activity, "配置名称", "原始", false)
            input.tag = "form.name"
            input.filters = arrayOf(InputFilter.LengthFilter(6))
            input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { changed++ }
                override fun afterTextChanged(s: Editable?) = Unit
            })
            parent = mount(input)
        }
        compose.onNodeWithTag("form.name").performTextReplacement("新的配置名称AB")
        compose.runOnIdle {
            assertEquals("新的配置名称", input.text.toString())
            assertTrue(changed > 0)
            assertSame(input, parent.findViewWithTag("form.name"))
            assertTrue(input.isAttachedToWindow)
            assertEquals(0, input.width)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, input.importantForAccessibility)
            input.post { posted = true }
            input.setText("extern")
            input.setSelection(2)
            input.setText("extern")
            input.error = "请输入有效配置"
        }
        compose.onNodeWithTag("form.name").assert(SemanticsMatcher.expectValue(
                SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("extern")))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange,
                androidx.compose.ui.text.TextRange(2)))
        compose.onNodeWithText("请输入有效配置").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(2, input.selectionStart)
            assertTrue(posted)
        }
    }

    @Test fun secretInputHasPasswordSemanticsAndExternalEditableChangesRefreshTheField() {
        lateinit var input: EditText
        compose.runOnUiThread {
            input = MiuixFormControls.input(compose.activity, "API 密钥", "secret", true)
            input.tag = "form.key"
            mount(input)
        }
        compose.onNodeWithTag("form.key").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.runOnUiThread { input.text.replace(0, input.text.length, "updated") }
        compose.onNodeWithTag("form.key").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.InputText, androidx.compose.ui.text.AnnotatedString("updated")))
    }

    @Test fun switchesAndButtonsShareJavaCallbacksAndDynamicEnabledState() {
        lateinit var check: CheckBox
        lateinit var button: Button
        var checked = false
        var clicked = 0
        compose.runOnUiThread {
            check = MiuixFormControls.checkbox(compose.activity, "限制输出", false)
            check.tag = "form.limit"
            check.setOnCheckedChangeListener { _, value -> checked = value }
            button = MiuixFormControls.button(compose.activity)
            button.tag = "form.fetch"
            button.text = "获取模型列表"
            button.setOnClickListener { clicked++ }
            mount(check, button)
        }
        compose.onNodeWithTag("form.limit").assertIsOff().performClick()
        compose.onNodeWithTag("form.fetch").performClick()
        compose.runOnIdle {
            assertTrue(checked)
            assertTrue(check.isChecked)
            assertEquals(1, clicked)
            button.text = "正在获取"
            button.isEnabled = false
            check.isChecked = false
        }
        compose.onNodeWithText("正在获取").assertIsDisplayed()
        compose.onNodeWithTag("form.fetch").assertIsNotEnabled()
        compose.onNodeWithTag("form.limit").assertIsOff()
        compose.runOnUiThread { check.isChecked = true }
        compose.onNodeWithTag("form.limit").assertIsOn()
    }

    @Test fun spinnerShowsMiuixChoicesAndRetainsNativeSelectionCallbacks() {
        lateinit var spinner: Spinner
        val selections = mutableListOf<Int>()
        compose.runOnUiThread {
            spinner = MiuixFormControls.spinner(compose.activity)
            spinner.tag = "form.provider"
            spinner.contentDescription = "供应商"
            spinner.adapter = ArrayAdapter(compose.activity, android.R.layout.simple_spinner_dropdown_item,
                arrayOf("供应商甲", "供应商乙", "供应商丙"))
            spinner.setSelection(1)
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    selections.add(position)
                }
            }
            mount(spinner)
        }
        compose.onNodeWithText("供应商乙").assertIsDisplayed()
        compose.runOnIdle { selections.clear() }
        compose.onNodeWithTag("form.provider").performClick()
        val chooser = ShadowDialog.getLatestDialog() as AlertDialog
        compose.onNodeWithText("供应商").assertIsDisplayed()
        compose.onNodeWithTag("miuix.dialog.choice.2").performClick()
        compose.onNodeWithText("供应商丙").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(2, spinner.selectedItemPosition)
            assertEquals("供应商丙", spinner.selectedItem)
            assertEquals(listOf(2), selections)
            assertTrue(!chooser.isShowing)
        }
        compose.runOnUiThread { spinner.setSelection(0) }
        compose.onNodeWithText("供应商甲").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(2, 0), selections) }
    }

    private fun mount(vararg controls: View): LinearLayout = LinearLayout(compose.activity).apply {
        orientation = LinearLayout.VERTICAL
        controls.forEach { addView(MiuixFormControls.viewFor(it),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)) }
        compose.activity.setContentView(this)
    }
}
