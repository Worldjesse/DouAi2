package com.douai.module.ui.miuix

import android.content.res.Configuration
import android.content.res.Resources
import android.content.ComponentCallbacks
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import com.douai.module.ui.ModuleTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h720dp-notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HostSettingsThemeTest {
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val rules = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            val application = RuntimeEnvironment.getApplication()
            val packageManager = application.packageManager
            val component = ComponentName(application, ComponentActivity::class.java)
            val info = ActivityInfo(packageManager.getActivityInfo(component, 0))
            info.configChanges = info.configChanges or ActivityInfo.CONFIG_UI_MODE
            shadowOf(packageManager).addOrUpdateActivity(info)
        }
    }).around(compose)

    @Test
    fun independentRowsSharingAResourceContextBothReceiveThemeChanges() {
        val backgrounds = mutableMapOf<String, Color>()
        val rows = mutableListOf<ComposeView>()
        val activity = compose.activity
        compose.runOnUiThread {
            val context = HostComposeContext.create(compose.activity)
            val parent = LinearLayout(compose.activity).apply { orientation = LinearLayout.VERTICAL }
            for (tag in listOf("first", "second")) {
                val view = ComposeView(context)
                rows += view
                context.bindConfiguration(view)
                view.setContent {
                    HostSettingsTheme {
                        var draft by remember { mutableStateOf(tag) }
                        val background = MiuixTheme.colorScheme.background
                        SideEffect { backgrounds[tag] = background }
                        BasicTextField(draft, { draft = it }, Modifier.testTag(tag))
                    }
                }
                parent.addView(view)
            }
            compose.activity.setContentView(parent)
        }
        compose.onNodeWithTag("first").performTextReplacement("first draft")
        compose.onNodeWithTag("second").performTextReplacement("second draft")
        compose.runOnUiThread {
            assertSame("Activity identity before night switch", activity, compose.activity)
            for (row in rows) assertTrue("Shared row must attach before night switch; activity=${activity.lifecycle.currentState}",
                row.isAttachedToWindow)
        }
        try {
            for (dark in listOf(true, false)) {
                compose.runOnUiThread { changeNightMode(dark) }
                compose.waitForIdle()
                val expected = if (dark) darkColorScheme().background else lightColorScheme().background
                assertEquals(expected, backgrounds["first"])
                assertEquals(expected, backgrounds["second"])
                compose.onNodeWithTag("first").assertTextEquals("first draft")
                compose.onNodeWithTag("second").assertTextEquals("second draft")
            }
        } finally {
            compose.runOnUiThread { compose.activity.setContentView(android.view.View(compose.activity)) }
        }
    }

    @Test
    fun activeThemeChangesBothWaysWithoutReplacingCompositionOrDraft() {
        lateinit var environment: HostComposeEnvironment
        lateinit var view: ComposeView
        var background = Color.Unspecified
        var foreground = Color.Unspecified
        var rememberedIdentity: Any? = null
        val activity = compose.activity
        compose.runOnUiThread {
            environment = HostComposeEnvironment(compose.activity, Runnable {})
            view = ComposeView(environment.context()).also(environment::attach)
            view.setContent {
                HostSettingsTheme {
                    val identity = remember { Any() }
                    var draft by remember { mutableStateOf("initial") }
                    val current = MiuixTheme.colorScheme.background
                    val currentForeground = LocalContentColor.current
                    SideEffect {
                        background = current
                        foreground = currentForeground
                        rememberedIdentity = identity
                    }
                    BasicTextField(draft, { draft = it }, Modifier.testTag("theme.draft"))
                }
            }
            compose.activity.setContentView(view)
        }
        compose.onNodeWithTag("theme.draft").performTextReplacement("unsaved configuration")
        compose.waitForIdle()
        val identity = rememberedIdentity
        assertEquals(lightColorScheme().background, background)
        assertEquals(lightColorScheme().onBackground, foreground)
        compose.runOnUiThread {
            assertSame("Activity identity before night switch", activity, compose.activity)
            assertTrue("Module view must attach before night switch; activity=${activity.lifecycle.currentState}, module=${environment.lifecycle.currentState}",
                view.isAttachedToWindow)
        }
        try {
            compose.runOnUiThread {
                changeNightMode(true)
                assertSame("Activity identity after night switch", activity, compose.activity)
                assertEquals("Module resource callback must observe night",
                    Configuration.UI_MODE_NIGHT_YES,
                    environment.context().resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
                assertTrue("Environment must stay active", view.isAttachedToWindow)
            }
            compose.waitForIdle()
            assertEquals(darkColorScheme().background, background)
            assertEquals("Unstyled Miuix titles and icons need the dark foreground",
                darkColorScheme().onBackground, foreground)
            assertNotEquals(lightColorScheme().background, background)
            assertSame(identity, rememberedIdentity)
            compose.onNodeWithTag("theme.draft").assertTextEquals("unsaved configuration")

            compose.runOnUiThread {
                changeNightMode(false)
            }
            compose.waitForIdle()
            assertEquals(lightColorScheme().background, background)
            assertEquals(lightColorScheme().onBackground, foreground)
            assertSame(identity, rememberedIdentity)
            compose.onNodeWithTag("theme.draft").assertTextEquals("unsaved configuration")
        } finally {
            compose.runOnUiThread {
                compose.activity.setContentView(android.view.View(compose.activity))
                environment.close()
            }
        }
    }

    @Test
    fun nativePromptUsesSystemColorsAndRetainsTextDuringSwitch() {
        lateinit var input: EditText
        lateinit var dialog: android.app.AlertDialog
        val activity = compose.activity
        compose.runOnUiThread {
            dialog = MiuixDialogBuilder.prompt(compose.activity, true, "theme prompt", "draft") {}
            dialog.show()
        }
        compose.waitForIdle()
        compose.runOnUiThread {
            input = dialog.window!!.decorView.findViewWithTag("settings.input.theme prompt")
            input.setText("unsaved text")
        }
        val lightText = input.currentTextColor
        compose.runOnUiThread {
            assertSame("Activity identity before night switch", activity, compose.activity)
            assertTrue("Native input must attach before night switch; activity=${activity.lifecycle.currentState}, dialog=${dialog.isShowing}, decor=${dialog.window!!.decorView.isAttachedToWindow}",
                input.isAttachedToWindow)
        }
        try {
            compose.runOnUiThread {
                changeNightMode(true)
                assertSame("Activity identity after night switch", activity, compose.activity)
                assertTrue("Native editor must stay attached", input.isAttachedToWindow)
            }
            compose.waitForIdle()
            assertNotEquals(lightText, input.currentTextColor)
            assertEquals("unsaved text", input.text.toString())
            compose.runOnUiThread {
                changeNightMode(false)
            }
            compose.waitForIdle()
            assertEquals(lightText, input.currentTextColor)
            assertEquals("unsaved text", input.text.toString())
        } finally {
            compose.runOnUiThread { dialog.dismiss() }
        }
    }

    private fun changeNightMode(dark: Boolean) {
        var observed = 0
        val observer = object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) { observed++ }
            override fun onLowMemory() = Unit
        }
        compose.activity.application.registerComponentCallbacks(observer)
        try {
            // Model a host that handles uiMode itself, retaining its Activity/window configuration.
            val system = Resources.getSystem()
            val configuration = Configuration(system.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            @Suppress("DEPRECATION")
            system.updateConfiguration(configuration, system.displayMetrics)
            assertEquals("System night signal", dark, ModuleTheme.isDark(compose.activity))
            compose.activity.application.onConfigurationChanged(configuration)
            assertTrue("Application configuration callback must be dispatched", observed > 0)
        } finally {
            compose.activity.application.unregisterComponentCallbacks(observer)
        }
    }
}
