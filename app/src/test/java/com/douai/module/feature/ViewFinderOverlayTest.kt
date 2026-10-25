package com.douai.module.feature

import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import com.douai.module.config.CustomViewLocator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/** Exercises the module's real Compose/Miuix controls against a native host View tree. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-notnight-mdpi", packageName = "com.douai.module")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ViewFinderOverlayTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var host: ViewFinderTestHost? = null

    @After fun closePicker() {
        compose.runOnUiThread { host?.close(); host = null }
    }

    @Test fun overlayUsesScreenCoordinatesAndConfirmsTheSelectedFeedView() {
        val fixture = openHost()
        compose.runOnIdle {
            assertSameWindow(fixture)
            assertTrue(fixture.picker.diagnosticSummary().contains("visibleBounds="))
        }
        tapTarget(fixture)
        compose.onNodeWithTag("view_finder.selection_panel").assertIsDisplayed()
        compose.onNodeWithTag("view_finder.confirm").performClick()
        compose.onNodeWithTag(positiveAction).performClick()
        compose.runOnIdle {
            assertEquals(1, fixture.saved.size)
            val locator = fixture.saved.single()
            assertEquals(CustomViewLocator.Scope.FEED, locator.scope)
            assertTrue(locator.resolve(fixture.decor, fixture.content) === fixture.target)
            assertTrue(fixture.picker.isClosed)
            assertEquals(0, countPickers(fixture.decor))
        }
    }

    @Test fun blankComposeAreaSelectsHostViewWithoutPerformingItsOriginalClick() {
        val fixture = openHost()
        // Dispatch through the complete ViewGroup, including the full-screen ComposeView.
        // Calling picker.onTouchEvent directly would miss a chrome layer swallowing empty space.
        tapTarget(fixture)
        compose.onNodeWithTag("view_finder.hide").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(0, fixture.hostClicks)
            assertEquals(1, fixture.saved.size)
            assertTrue(fixture.saved.single().resolve(fixture.decor, fixture.content) === fixture.target)
        }
    }

    @Test fun treeSearchFiltersAndSelectsTheActualMatchingView() {
        val fixture = openHost()
        compose.runOnUiThread {
            fixture.target.text = "Like target"
            fixture.sibling.text = "Like hidden"
            fixture.sibling.visibility = View.GONE
        }
        compose.onNodeWithTag("view_finder.tree").performClick()
        compose.onNodeWithTag("view_finder.tree_search").performTextReplacement("TEXTVIEW like")
        assertEquals(2, treeRows().fetchSemanticsNodes().size)
        compose.onNodeWithTag("view_finder.tree_visible").performClick()
        compose.onNodeWithTag("view_finder.tree_clickable").performClick()
        assertEquals(1, treeRows().fetchSemanticsNodes().size)
        rowWithText("Like target").performClick()
        compose.onNodeWithTag("view_finder.tree_search").assertDoesNotExist()
        compose.onNodeWithTag("view_finder.hide").performClick()
        compose.runOnIdle {
            assertEquals(1, fixture.saved.size)
            assertTrue(fixture.saved.single().resolve(fixture.decor, fixture.content) === fixture.target)
        }
    }

    @Test fun treeRefreshPreservesSearchAndIncludesNewViews() {
        val fixture = openHost()
        compose.onNodeWithTag("view_finder.tree").performClick()
        compose.onNodeWithTag("view_finder.tree_search").performTextReplacement("new item")
        assertEquals(0, treeRows().fetchSemanticsNodes().size)
        compose.runOnUiThread {
            fixture.content.addView(TextView(compose.activity).apply { text = "new item" },
                FrameLayout.LayoutParams(100, 100))
        }
        compose.onNodeWithTag("view_finder.tree_refresh").performClick()
        compose.onNodeWithTag("view_finder.tree_search").assertTextContains("new item")
        rowWithText("new item").assertIsDisplayed()
        compose.runOnUiThread { fixture.picker.close() }
        compose.onNodeWithTag("view_finder.tree_search").assertDoesNotExist()
    }

    @Test fun overlapButtonCyclesFromFrontViewToViewBehindIt() {
        val fixture = openHost(overlap = true)
        tapTarget(fixture)
        compose.onNodeWithTag("view_finder.properties").performClick()
        compose.runOnIdle {
            val details = latestAlert().window!!.decorView
                .findViewWithTag<TextView>("view_finder.properties_text")
            assertNotNull(details)
            assertTrue(details.text.toString().contains("front sibling"))
        }
        compose.onNodeWithTag(negativeAction).performClick()
        compose.onNodeWithTag("view_finder.overlap").assertIsEnabled().performClick()
        compose.onNodeWithTag("view_finder.hide").performClick()
        compose.runOnIdle {
            assertEquals(1, fixture.saved.size)
            assertTrue(fixture.saved.single().resolve(fixture.decor, fixture.content) === fixture.target)
        }
    }

    @Test fun propertiesCopyIncludesRuleMetadataAndClosesWithPicker() {
        val fixture = openHost()
        tapTarget(fixture)
        compose.onNodeWithTag("view_finder.properties").performClick()
        var snapshot = ""
        compose.runOnIdle {
            snapshot = latestAlert().window!!.decorView
                .findViewWithTag<TextView>("view_finder.properties_text").text.toString()
            assertTrue(snapshot.contains(TextView::class.java.name))
            assertTrue(snapshot.contains("信息流"))
        }
        compose.onNodeWithTag(positiveAction).performClick()
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(snapshot, clipboard.primaryClip!!.getItemAt(0).text.toString())
            assertTrue(fixture.saved.isEmpty())
        }
        compose.onNodeWithTag("view_finder.properties").performClick()
        lateinit var reopened: AlertDialog
        compose.runOnIdle { reopened = latestAlert() }
        compose.runOnUiThread { fixture.picker.close() }
        compose.runOnIdle { assertFalse(reopened.isShowing) }
        compose.onNodeWithTag("view_finder.chrome").assertDoesNotExist()
    }

    @Test fun savingPreviewRejectsRemovedOrStructurallyChangedTarget() {
        val fixture = openHost()
        tapTarget(fixture)
        compose.onNodeWithTag("view_finder.confirm").performClick()
        lateinit var editor: AlertDialog
        compose.runOnIdle { editor = latestAlert() }
        compose.runOnUiThread { fixture.content.removeView(fixture.target) }
        compose.onNodeWithTag(positiveAction).performClick()
        compose.runOnIdle {
            assertTrue(fixture.saved.isEmpty())
            assertFalse(fixture.picker.isClosed)
            assertTrue(editor.isShowing)
        }
    }

    @Test fun refreshUpdatesOverlapCountWithoutChangingTheSelection() {
        val fixture = openHost(overlap = true)
        tapTarget(fixture)
        val previous = textOf("view_finder.selection_count")
        compose.runOnUiThread {
            fixture.content.removeView(fixture.target)
            ShadowSystemClock.advanceBy(Duration.ofMillis(300))
            fixture.decor.viewTreeObserver.dispatchOnPreDraw()
        }
        compose.waitForIdle()
        assertNotEquals(previous, textOf("view_finder.selection_count"))
        compose.onNodeWithTag("view_finder.hide").performClick()
        compose.runOnIdle {
            assertEquals(1, fixture.saved.size)
            assertTrue(fixture.saved.single().resolve(fixture.decor, fixture.content) === fixture.sibling)
        }
    }

    @Test fun invisibleTreeSelectionKeepsMetadataAndExpandableNavigation() {
        val fixture = openHost()
        compose.runOnUiThread {
            fixture.target.text = "hidden target"
            fixture.target.visibility = View.INVISIBLE
        }
        compose.onNodeWithTag("view_finder.tree").performClick()
        compose.onNodeWithTag("view_finder.tree_search").performTextReplacement("hidden target")
        rowWithText("hidden target").performClick()
        compose.onNodeWithTag("view_finder.selection_panel").assertIsDisplayed()
        compose.runOnIdle { assertTrue(ViewFinderOverlay.screenBounds(fixture.target).isEmpty) }
        compose.onNodeWithTag("view_finder.父节点").assertDoesNotExist()
        compose.onNodeWithTag("view_finder.expand").performClick()
        compose.onNodeWithTag("view_finder.父节点").assertIsDisplayed()
        compose.onNodeWithTag("view_finder.子节点").assertIsDisplayed()
    }

    @Test fun toolbarAndSelectionPanelCanBeMovedThroughTheirComposeDragHandles() {
        val fixture = openHost()
        tapTarget(fixture)
        val toolbarBefore = compose.onNodeWithTag("view_finder.drag").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("view_finder.drag").performTouchInput {
            swipe(center, center + Offset(0f, 36f), durationMillis = 150)
        }
        val toolbarAfter = compose.onNodeWithTag("view_finder.drag").fetchSemanticsNode().boundsInRoot
        assertTrue("Toolbar drag must move the real chrome", toolbarAfter.top > toolbarBefore.top)
        val panelBefore = compose.onNodeWithTag("view_finder.selection_panel").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("view_finder.selection_drag").performTouchInput {
            swipe(center, center + Offset(0f, -36f), durationMillis = 150)
        }
        val panelAfter = compose.onNodeWithTag("view_finder.selection_panel").fetchSemanticsNode().boundsInRoot
        assertTrue("Panel drag must move the real Miuix panel", panelAfter.top < panelBefore.top)
    }

    private fun openHost(overlap: Boolean = false): ViewFinderTestHost {
        lateinit var fixture: ViewFinderTestHost
        compose.runOnUiThread { fixture = ViewFinderTestHost(compose.activity, overlap); host = fixture }
        compose.waitForIdle()
        return fixture
    }

    private fun tapTarget(fixture: ViewFinderTestHost) {
        compose.runOnUiThread { assertTrue("Empty chrome must pass selection to the picker", fixture.tap(fixture.target)) }
        compose.waitForIdle()
    }

    private fun textOf(tag: String): String = compose.onNodeWithTag(tag, useUnmergedTree = true)
        .fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }

    private fun treeRows() = compose.onAllNodes(treeRowMatcher, useUnmergedTree = true)

    private fun rowWithText(text: String) = compose.onNode(treeRowMatcher and
        (hasText(text, substring = true) or hasAnyDescendant(hasText(text, substring = true))),
        useUnmergedTree = true)

    private fun latestAlert() = ShadowDialog.getLatestDialog() as AlertDialog

    private fun assertSameWindow(fixture: ViewFinderTestHost) {
        assertTrue(fixture.picker.parent === fixture.decor)
    }

    private fun countPickers(view: View): Int = (if (view is ViewFinderOverlay) 1 else 0) +
        if (view is ViewGroup) (0 until view.childCount).sumOf { countPickers(view.getChildAt(it)) } else 0

    private companion object {
        const val positiveAction = "miuix.dialog.button.${Dialog.BUTTON_POSITIVE}"
        const val negativeAction = "miuix.dialog.button.${Dialog.BUTTON_NEGATIVE}"
        val treeRowMatcher = SemanticsMatcher("view finder tree row") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("view_finder.tree_row.") == true
        }
    }
}

/** Native synthetic host shared by behavioral and screenshot tests; no production UI is mocked. */
internal class ViewFinderTestHost(val activity: ComponentActivity, overlap: Boolean = false) : AutoCloseable {
    val content = FrameLayout(activity)
    val target = TextView(activity)
    val sibling = TextView(activity)
    val saved = mutableListOf<CustomViewLocator>()
    var hostClicks = 0
        private set
    val decor: ViewGroup
    val picker: ViewFinderOverlay

    init {
        val metrics = activity.resources.displayMetrics
        val density = metrics.density
        fun dp(value: Int) = (value * density).toInt()
        content.setBackgroundColor(0xFF293A48.toInt())
        target.id = android.R.id.text1
        target.text = "back target"
        target.contentDescription = "native target"
        target.gravity = Gravity.CENTER
        target.setTextColor(android.graphics.Color.WHITE)
        target.setOnClickListener { hostClicks++ }
        sibling.id = android.R.id.text2
        sibling.text = "front sibling"
        sibling.gravity = Gravity.CENTER
        sibling.setTextColor(android.graphics.Color.WHITE)
        val top = maxOf(dp(128), (metrics.heightPixels * 0.32f).toInt())
        content.addView(target, FrameLayout.LayoutParams(dp(112), dp(48), Gravity.TOP or Gravity.RIGHT).apply {
            topMargin = top
            rightMargin = dp(20)
        })
        content.addView(sibling, FrameLayout.LayoutParams(dp(112), dp(48), Gravity.TOP or Gravity.RIGHT).apply {
            topMargin = if (overlap) top else top + dp(96)
            rightMargin = dp(20)
        })
        activity.setContentView(content)
        decor = activity.window.decorView as ViewGroup
        val existing = CustomViewLocator.capture(decor, content, target)
        picker = ViewFinderOverlay.show(activity, decor, content, object : ViewFinderOverlay.Listener {
            override fun onConfirm(locator: CustomViewLocator): Boolean { saved += locator; return true }
            override fun existingCustomViews(): List<CustomViewLocator> = listOfNotNull(existing)
        }) ?: error("Picker should open in the host window")
    }

    fun tap(view: View): Boolean {
        val bounds = ViewFinderOverlay.screenBounds(view)
        check(!bounds.isEmpty) { "Synthetic host target must have visible screen bounds" }
        val origin = IntArray(2).also(picker::getLocationOnScreen)
        val time = SystemClock.uptimeMillis()
        fun event(action: Int, eventTime: Long) = MotionEvent.obtain(time, eventTime, action,
            bounds.exactCenterX(), bounds.exactCenterY(), 0).apply {
            offsetLocation(-origin[0].toFloat(), -origin[1].toFloat())
        }
        val down = event(MotionEvent.ACTION_DOWN, time)
        val up = event(MotionEvent.ACTION_UP, time + 16)
        return try {
            val started = picker.dispatchTouchEvent(down)
            val finished = picker.dispatchTouchEvent(up)
            started && finished
        } finally { down.recycle(); up.recycle() }
    }

    override fun close() { picker.close() }
}
