package com.douai.module.ui

import android.app.Activity
import android.net.Uri
import android.view.View
import android.widget.ScrollView
import android.widget.Spinner
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.douai.module.config.FeatureSettings
import com.douai.module.config.SettingsStore
import com.douai.module.diagnostics.DebugLog
import com.douai.module.ui.miuix.HostComposeContext
import com.douai.module.ui.miuix.MiuixFormControls
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.function.BooleanSupplier
import java.util.function.Consumer

/** Real Compose measurement; the native-only viewer tests cannot cover FormSpinner's policy. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-mdpi", packageName = "com.douai.module")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DebugLogMiuixLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var page: DebugLogDialog? = null

    @After fun closePage() {
        compose.runOnUiThread {
            page?.dialog()?.dismiss()
            page?.close()
            page = null
        }
    }

    @Test fun hostMiuixViewerMeasuresItsActualPhoneWidthWithTheFilterSpinnerComposed() {
        showHostViewer()
        compose.waitForIdle()
        compose.onNodeWithTag("debug.logs.category").assertIsDisplayed()
        compose.runOnIdle {
            val decor = page!!.dialog().window!!.decorView
            val spinner = decor.findViewWithTag<Spinner>("debug.logs.category")
            assertNotNull(spinner)
            assertTrue("The host path must exercise the real Miuix proxy", spinner.javaClass.name.endsWith("FormSpinner"))
            assertTrue(MiuixFormControls.viewFor(spinner) is androidx.compose.ui.platform.ComposeView)
            measure(decor, 360, 800)
        }
        compose.waitForIdle()
    }

    @Test fun hostMiuixViewerWholeScrollContentRemainsMeasurableAtNarrowDialogWidths() {
        showHostViewer()
        compose.waitForIdle()
        for (width in intArrayOf(296, 260, 220)) {
            compose.runOnIdle {
                val scroll = page!!.dialog().window!!.decorView.findViewWithTag<ScrollView>("debug.logs.scroll")
                assertNotNull(scroll)
                // Measuring the ScrollView also measures off-screen rows; visibility alone misses them.
                measure(scroll, width, 620)
            }
            compose.waitForIdle()
        }
    }

    private fun showHostViewer() {
        compose.runOnUiThread {
            val hostResourceContext = HostComposeContext.create(compose.activity)
            page = DebugLogDialog(hostResourceContext, MemoryStore(), true,
                FakeSource(), NoExport(), java.util.concurrent.Executor { it.run() })
            page!!.dialog().show()
        }
    }

    private fun measure(view: View, widthDp: Int, heightDp: Int) {
        val density = compose.activity.resources.displayMetrics.density
        val width = (widthDp * density).toInt()
        val height = (heightDp * density).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private class FakeSource : DebugLogDialog.Source {
        private val text = "2026-10-22 00:00:01.000 D/DouAi [detail] " +
            "[DouAi.MessageJson] hook=DouAi.AutoAudio.1 stage=after json={\"duration\":2117}\n" +
            "2026-10-22 00:00:02.000 D/DouAi [basic] Automatic reply outcome: sent\n"
        override fun read(callback: Consumer<DebugLogDialog.Snapshot>) {
            callback.accept(DebugLogDialog.Snapshot(true, true, text))
        }
        override fun enabled(enabled: Boolean, callback: Consumer<Boolean>) { callback.accept(true) }
        override fun clear(callback: Consumer<Boolean>) { callback.accept(true) }
        override fun export(callback: Consumer<DebugLogDialog.Snapshot>) {
            callback.accept(DebugLogDialog.Snapshot(true, true, text))
        }
    }

    private class NoExport : DebugLogDialog.Exporter {
        override fun choose(activity: Activity, callback: Consumer<Uri>): AutoCloseable = AutoCloseable { }
        override fun write(uri: Uri, text: String, valid: BooleanSupplier) { error("Export is outside this layout test") }
    }

    private class MemoryStore : SettingsStore {
        private var settings = FeatureSettings.defaults()
        override fun load(): FeatureSettings = settings
        override fun save(value: FeatureSettings) { settings = value }
        override fun getBoolean(key: String, fallback: Boolean): Boolean = key == DebugLog.KEY || fallback
        override fun getString(key: String, fallback: String): String = fallback
        override fun putBoolean(key: String, value: Boolean) = Unit
        override fun putString(key: String, value: String) = Unit
        override fun isInHost(): Boolean = true
    }
}
