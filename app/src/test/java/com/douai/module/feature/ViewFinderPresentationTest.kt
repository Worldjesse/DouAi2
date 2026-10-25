package com.douai.module.feature

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import com.douai.module.ui.ModuleTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Pixel previews and geometry checks of the real module-themed Miuix picker. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-notnight-mdpi", packageName = "com.douai.module")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ViewFinderPresentationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var host: ViewFinderTestHost? = null

    @After fun closePicker() { compose.runOnUiThread { host?.close(); host = null } }

    @Test @Config(qualifiers = "w360dp-h800dp-port-notnight-mdpi")
    fun portraitLight() = preview("360x800-light", false)

    @Test @Config(qualifiers = "w360dp-h800dp-port-night-mdpi")
    fun portraitDark() = preview("360x800-dark", true)

    @Test @Config(qualifiers = "w320dp-h480dp-port-notnight-mdpi")
    fun narrowLight() = preview("320x480-light", false)

    @Test @Config(qualifiers = "w320dp-h480dp-port-night-mdpi")
    fun narrowDark() = preview("320x480-dark", true)

    @Test @Config(qualifiers = "w800dp-h360dp-land-notnight-mdpi")
    fun landscapeLight() = preview("800x360-light", false)

    @Test @Config(qualifiers = "w800dp-h360dp-land-night-mdpi")
    fun landscapeDark() = preview("800x360-dark", true)

    @Test @Config(qualifiers = "w320dp-h480dp-port-notnight-mdpi")
    fun narrowLargeText() = preview("320x480-large-text", false, 1.3f)

    @Test @Config(qualifiers = "w640dp-h320dp-land-night-mdpi")
    fun shortLandscapeLargeText() = preview("640x320-large-text", true, 1.3f)

    @Suppress("DEPRECATION")
    private fun preview(name: String, dark: Boolean, fontScale: Float = 1f) {
        lateinit var previous: Configuration
        lateinit var fixture: ViewFinderTestHost
        compose.runOnUiThread {
            previous = Configuration(compose.activity.resources.configuration)
            val configuration = Configuration(previous).apply { this.fontScale = fontScale }
            compose.activity.resources.updateConfiguration(configuration, compose.activity.resources.displayMetrics)
            assertEquals("Picker must follow the module's system theme", dark, ModuleTheme.isDark(compose.activity))
            fixture = ViewFinderTestHost(compose.activity)
            host = fixture
            populateVideoFixture(fixture)
        }
        try {
            compose.waitForIdle()
            compose.runOnUiThread {
                assertTrue("The full-screen Compose chrome must let blank touches select the host", fixture.tap(fixture.target))
                assertEquals("Inspecting must not click the host action", 0, fixture.hostClicks)
            }
            compose.waitForIdle()
            val density = compose.activity.resources.displayMetrics.density
            checkPickerBounds(density, expanded = false)
            assertModulePaletteAndReadableDetails(dark)
            save("picker-selected-$name", capture("view_finder.chrome"))

            compose.onNodeWithTag("view_finder.expand").performClick()
            checkPickerBounds(density, expanded = true)
            save("picker-expanded-$name", capture("view_finder.chrome"))
            compose.onNodeWithTag("view_finder.expand").performClick()

            compose.onNodeWithTag("view_finder.tree").performClick()
            compose.onNodeWithTag("view_finder.tree_search").performTextReplacement("TextView")
            compose.waitForIdle()
            val page = bounds("view_finder.tree_page")
            for (tag in listOf("tree_search", "tree_count", "tree_list", "tree_refresh", "tree_back")) {
                compose.onNodeWithTag("view_finder.$tag", useUnmergedTree = true).assertIsDisplayed()
                assertInside(bounds("view_finder.$tag"), page, tag)
            }
            val list = bounds("view_finder.tree_list")
            assertTrue("View tree must retain a usable list with large text: $list", list.height >= 48 * density)
            assertFalseOverlap(bounds("view_finder.tree_count"), bounds("view_finder.tree_back"), "Tree count/footer")
            checkTouchTarget("view_finder.tree_back", density)
            checkTouchTarget("view_finder.tree_refresh", density)
            save("picker-tree-search-$name", captureTreeWindow())
            compose.onNodeWithTag("view_finder.tree_back").performClick()
            compose.onNodeWithTag("view_finder.tree_page").assertDoesNotExist()
        } finally {
            compose.runOnUiThread {
                fixture.close()
                compose.activity.resources.updateConfiguration(previous, compose.activity.resources.displayMetrics)
            }
        }
    }

    private fun checkPickerBounds(density: Float, expanded: Boolean) {
        compose.waitForIdle()
        val root = bounds("view_finder.chrome")
        val panel = bounds("view_finder.selection_panel")
        assertInside(panel, root, if (expanded) "Expanded Miuix panel" else "Miuix panel")
        val toolbarParts = listOf("drag", "exit", "refresh", "tree", "bounds").map { bounds("view_finder.$it") }
        val toolbar = Rect(toolbarParts.minOf { it.left }, toolbarParts.minOf { it.top },
            toolbarParts.maxOf { it.right }, toolbarParts.maxOf { it.bottom })
        assertInside(toolbar, root, "Toolbar")
        assertFalseOverlap(toolbar, panel, "Toolbar/selection panel")
        for (tag in listOf("exit", "refresh", "tree", "bounds", "overlap", "expand", "properties", "confirm", "hide")) {
            checkTouchTarget("view_finder.$tag", density)
        }
        if (expanded) {
            for (tag in listOf("上一个", "下一个", "父节点", "子节点", "selection_seekbar")) {
                compose.onNodeWithTag("view_finder.$tag", useUnmergedTree = true).assertIsDisplayed()
            }
        }
        for (callout in compose.onAllNodesWithTag("view_finder.selection_callout", useUnmergedTree = true)
            .fetchSemanticsNodes()) {
            assertInside(callout.boundsInRoot, root, "Callout")
            assertFalseOverlap(callout.boundsInRoot, toolbar, "Callout/toolbar")
            assertFalseOverlap(callout.boundsInRoot, panel, "Callout/panel")
        }
    }

    private fun assertModulePaletteAndReadableDetails(dark: Boolean) {
        val scheme = if (dark) darkColorScheme() else lightColorScheme()
        val panel = capture("view_finder.selection_panel")
        val background = dominantColor(panel)
        assertColorClose("Panel must use the same Miuix surface palette as module settings", scheme.surface.toArgb(), background)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("view_finder.selection_details", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(layouts) }
        assertTrue("Real Miuix details must expose text layout", layouts.isNotEmpty())
        val foreground = layouts.first().layoutInput.style.color.toArgb()
        assertColorClose("Details must use the module's secondary text palette", scheme.onSurfaceVariantSummary.toArgb(), foreground)
        assertTrue("Details should remain readable against the real panel", contrast(foreground, background) >= 3.0)
    }

    private fun checkTouchTarget(tag: String, density: Float) {
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true)
        node.assertIsDisplayed()
        val size = node.fetchSemanticsNode().boundsInRoot
        assertTrue("$tag should have a reachable touch target: $size",
            size.width >= 40 * density - 1 && size.height >= 40 * density - 1)
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun capture(tag: String): Bitmap = compose.onNodeWithTag(tag, useUnmergedTree = true)
        .captureToImage().asAndroidBitmap()

    private fun captureTreeWindow(): Bitmap {
        compose.onNodeWithTag("view_finder.tree_page", useUnmergedTree = true).assertIsDisplayed()
        lateinit var decor: View
        lateinit var window: Window
        compose.runOnUiThread {
            val dialog = checkNotNull(ShadowDialog.getLatestDialog())
            assertTrue("Tree preview must capture the visible tree dialog", dialog.isShowing)
            window = checkNotNull(dialog.window)
            decor = window.decorView
            val tree = decor.findViewWithTag<View>("view_finder.tree")
            assertTrue("The selected native window must own the Miuix tree composition", tree != null && tree.isAttachedToWindow)
            val width = window.attributes.width.takeIf { it > 0 } ?: decor.width
            val height = window.attributes.height.takeIf { it > 0 } ?: decor.height
            assertTrue("Tree dialog must have bounded screenshot dimensions", width > 0 && height > 0)
            decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, width, height)
        }
        compose.waitForIdle()
        lateinit var bitmap: Bitmap
        val result = AtomicInteger(-1)
        compose.runOnUiThread {
            // Resolve this Dialog explicitly: captureToImage follows HostComposeContext to the
            // Activity's window. PixelCopy retains the hardware path required by RuntimeShader.
            bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(window, bitmap, { result.set(it) }, Handler(Looper.getMainLooper()))
        }
        compose.waitUntil(timeoutMillis = 5_000) { result.get() != -1 }
        assertEquals("The actual tree window's hardware pixels must be copied", PixelCopy.SUCCESS, result.get())
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertTrue("The actual tree window must render its controls", pixels.toSet().size > 8)
        return bitmap
    }

    private fun assertInside(child: Rect, root: Rect, label: String) {
        assertTrue("$label outside $root: $child", child.width > 0 && child.height > 0 &&
            child.left >= root.left - 1 && child.top >= root.top - 1 &&
            child.right <= root.right + 1 && child.bottom <= root.bottom + 1)
    }

    private fun assertFalseOverlap(first: Rect, second: Rect, label: String) {
        assertTrue("$label overlap: $first / $second",
            first.right <= second.left || second.right <= first.left ||
                first.bottom <= second.top || second.bottom <= first.top)
    }

    private fun dominantColor(bitmap: Bitmap): Int {
        val counts = mutableMapOf<Int, Int>()
        for (y in 1 until bitmap.height - 1 step max(1, bitmap.height / 60)) {
            for (x in 1 until bitmap.width - 1 step max(1, bitmap.width / 60)) {
                val color = bitmap.getPixel(x, y)
                counts[color] = (counts[color] ?: 0) + 1
            }
        }
        return counts.maxByOrNull { it.value }!!.key
    }

    private fun assertColorClose(message: String, expected: Int, actual: Int) {
        assertTrue("$message: expected=${Integer.toHexString(expected)}, actual=${Integer.toHexString(actual)}",
            abs(AndroidColor.red(expected) - AndroidColor.red(actual)) <= 3 &&
                abs(AndroidColor.green(expected) - AndroidColor.green(actual)) <= 3 &&
                abs(AndroidColor.blue(expected) - AndroidColor.blue(actual)) <= 3)
    }

    private fun contrast(first: Int, second: Int): Double {
        fun luminance(color: Int): Double {
            fun linear(value: Int): Double {
                val channel = value / 255.0
                return if (channel <= 0.04045) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)
            }
            return linear(AndroidColor.red(color)) * 0.2126 + linear(AndroidColor.green(color)) * 0.7152 +
                linear(AndroidColor.blue(color)) * 0.0722
        }
        val a = luminance(first)
        val b = luminance(second)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    private fun populateVideoFixture(fixture: ViewFinderTestHost) {
        val activity = fixture.activity
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        fixture.content.background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF233747.toInt(), 0xFF101C29.toInt(), 0xFF3A3436.toInt()))
        fixture.target.text = "☆ 收藏"
        fixture.target.contentDescription = "示例收藏按钮"
        fixture.sibling.text = "↗ 分享"
        val title = TextView(activity).apply {
            text = "关注     推荐     精选"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(AndroidColor.WHITE)
        }
        fixture.content.addView(title, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48), Gravity.TOP))
        val scene = TextView(activity).apply {
            text = "视频画面\n本地界面示例"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(0xFF7894A5.toInt())
        }
        fixture.content.addView(scene, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(112), Gravity.CENTER))
    }

    private fun save(name: String, bitmap: Bitmap) {
        val override = System.getProperty("douai.viewFinder.miuixPreviewDir")
            ?: System.getenv("DOUAI_VIEW_FINDER_MIUIX_SCREENSHOTS")
        val directory = if (!override.isNullOrBlank()) File(override) else {
            var project = File(System.getProperty("user.dir")).absoluteFile
            while (project.parentFile != null && !File(project, "settings.gradle.kts").isFile) project = project.parentFile
            File(project, "work/view-finder-miuix-preview")
        }
        assertTrue(directory.isDirectory || directory.mkdirs())
        assertTrue(bitmap.width > 0 && bitmap.height > 0)
        File(directory, "$name.png").outputStream().use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
    }
}
