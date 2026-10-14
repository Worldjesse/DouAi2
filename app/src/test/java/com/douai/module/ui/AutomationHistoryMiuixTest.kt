package com.douai.module.ui

import android.app.AlertDialog
import android.app.Dialog
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import com.douai.module.ui.miuix.HostComposeEnvironment
import com.douai.module.config.AutomationHistoryStore
import java.util.ArrayDeque
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], qualifiers = "w360dp-h720dp-mdpi")
class AutomationHistoryMiuixTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val worker = ManualExecutor()
    private var page: AutomationHistoryDialog? = null

    @After fun closeDialog() {
        compose.runOnUiThread { page?.dialog()?.dismiss() }
        worker.shutdownNow()
    }

    @Test fun nonEmptyCommentHistoryMeasuresScrollsOpensDetailsAndClearsInsideMiuixDialog() {
        lateinit var store: AutomationHistoryStore
        compose.runOnUiThread {
            compose.activity.getSharedPreferences("douai_automation_history", 0).edit().clear().commit()
            store = AutomationHistoryStore(compose.activity)
            repeat(200) { index ->
                store.upsert(AutomationHistoryStore.Entry(
                    "comment-$index", AutomationHistoryStore.COMMENT, "target-$index",
                    "作者 $index", "原作品 $index", "生成评论 $index", "模型", "sent",
                    index.toLong() + 1, index.toLong() + 1,
                    if (index == 199) AutomationHistoryStore.DELIVERY_VOICE else AutomationHistoryStore.DELIVERY_TEXT,
                ))
            }
            page = AutomationHistoryDialog(compose.activity, AutomationHistoryStore.COMMENT, true, worker)
            page!!.dialog().show()
        }
        compose.waitForIdle()
        worker.runNextOnThread()
        compose.onNodeWithTag("automation.history.comment-199").assertIsDisplayed()
        compose.onNodeWithText("作者 199 · 发送成功 · 语音").assertIsDisplayed()
        compose.runOnIdle {
            val root = page!!.dialog().window!!.decorView
            val history = root.findViewWithTag<View>("automation.history")
            val moduleRoot = root.findViewWithTag<View>("miuix.dialog.root")
            assertTrue(history.findViewTreeLifecycleOwner() is HostComposeEnvironment)
            assertSame(moduleRoot.findViewTreeLifecycleOwner(), history.findViewTreeLifecycleOwner())
            assertSame(moduleRoot.findViewTreeSavedStateRegistryOwner(), history.findViewTreeSavedStateRegistryOwner())
            assertTrue(history.height > 0)
            assertTrue(history.height <= 460 * history.resources.displayMetrics.density)
        }
        compose.onNodeWithTag("automation.history.comment-0").assertDoesNotExist()
        compose.onNodeWithTag("automation.history")
            .performScrollToNode(hasTestTag("automation.history.comment-0"))
        compose.onNodeWithTag("automation.history.comment-0").assertIsDisplayed().performClick()
        val detail = ShadowDialog.getLatestDialog() as AlertDialog
        compose.runOnIdle {
            assertTrue(containsText(detail.window!!.decorView, "原作品 0"))
            assertTrue(containsText(detail.window!!.decorView, "生成评论 0"))
            assertTrue(containsText(detail.window!!.decorView, "发送方式：文本"))
        }
        compose.runOnUiThread { detail.dismiss() }
        compose.onNodeWithTag("miuix.dialog.button.${Dialog.BUTTON_NEUTRAL}").performClick()
        worker.runNextOnThread()
        compose.onNodeWithText("暂无记录").assertIsDisplayed()
        assertTrue(store.read(AutomationHistoryStore.COMMENT).isEmpty())
    }

    @Test fun parentDismissClosesHistoryDetail() {
        compose.runOnUiThread {
            compose.activity.getSharedPreferences("douai_automation_history", 0).edit().clear().commit()
            val store = AutomationHistoryStore(compose.activity)
            store.upsert(AutomationHistoryStore.Entry(
                "late", AutomationHistoryStore.COMMENT, "target", "作者", "作品", "评论",
                "模型", "sent", 1, 1,
            ))
            page = AutomationHistoryDialog(compose.activity, AutomationHistoryStore.COMMENT, true, worker)
            page!!.dialog().show()
        }
        compose.waitForIdle()
        worker.runNextOnThread()
        compose.onNodeWithTag("automation.history.late").assertIsDisplayed().performClick()
        val detail = ShadowDialog.getLatestDialog()
        compose.runOnUiThread { page!!.dialog().dismiss() }
        compose.runOnIdle { assertFalse(detail.isShowing) }
    }

    private fun containsText(view: View, needle: String): Boolean {
        if (view is TextView && view.text.toString().contains(needle)) return true
        return view is ViewGroup && (0 until view.childCount).any { containsText(view.getChildAt(it), needle) }
    }

    private class ManualExecutor : AbstractExecutorService() {
        private val tasks = ArrayDeque<Runnable>()
        private var stopped = false
        override fun execute(command: Runnable) { tasks.add(command) }
        fun runNextOnThread() {
            val task = tasks.poll() ?: error("History work was not queued")
            val thread = Thread(task, "test-history-worker")
            thread.start()
            thread.join(5000)
            check(!thread.isAlive) { "History work did not finish" }
        }
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): List<Runnable> {
            stopped = true
            return tasks.toList().also { tasks.clear() }
        }
        override fun isShutdown(): Boolean = stopped
        override fun isTerminated(): Boolean = stopped
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = stopped
    }
}
