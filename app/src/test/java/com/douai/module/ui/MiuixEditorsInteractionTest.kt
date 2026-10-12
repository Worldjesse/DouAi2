package com.douai.module.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextRange
import com.douai.module.config.AiModelConfig
import com.douai.module.feature.ChatImage
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h720dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MiuixEditorsInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var chat: AiTestChatDialog? = null
    private var prompt: PromptEditorDialog? = null
    private val worker = ManualWorker()
    private val session = FakeSession()

    @After fun closeEditors() { compose.runOnUiThread { chat?.dismiss(); prompt?.dismiss() } }

    @Test fun promptUsesMiuixInputAndCommitsExactWhitespaceOnlyOnSave() {
        var saved: String? = null
        val value = " 记忆设定\n保留末尾空格  \n".repeat(400)
        compose.runOnUiThread {
            prompt = PromptEditorDialog(compose.activity, "一级提示词", "旧值", { saved = it }, true)
            prompt!!.show()
        }
        compose.onNodeWithTag("miuix.prompt").assertIsDisplayed()
        compose.onNodeWithTag("settings.input.一级提示词").performTextReplacement(value)
        compose.onNodeWithTag("prompt.save").performClick()
        compose.runOnIdle { assertEquals(value, saved); assertFalse(prompt!!.isShowing) }
        compose.runOnUiThread {
            prompt = PromptEditorDialog(compose.activity, "一级提示词", value, { saved = it }, true)
            prompt!!.show()
        }
        compose.onNodeWithTag("settings.input.一级提示词").performTextReplacement("取消的更改")
        compose.onNodeWithTag("prompt.cancel").performClick()
        compose.runOnIdle { assertEquals(value, saved); assertFalse(prompt!!.isShowing) }
    }

    @Test fun chatFailureAndRetryUseTheExistingPairAndKeepTheNextDraft() {
        openChat()
        compose.onNodeWithTag("ai.chat.input").performTextReplacement("第一次请求")
        compose.onNodeWithTag("ai.chat.send").performClick()
        compose.onNodeWithTag("ai.chat.input").performTextReplacement("下一条草稿")
        session.failure = java.net.SocketTimeoutException()
        completeRequest()
        compose.onNodeWithText("下一条草稿").assertIsDisplayed()
        compose.onNodeWithTag("ai.chat.retry").performClick()
        session.failure = null
        completeRequest()
        compose.onAllNodesWithTag("ai.chat.user").assertCountEquals(1)
        compose.onAllNodesWithTag("ai.chat.answer").assertCountEquals(1)
        compose.onNodeWithText("模型回复").assertIsDisplayed()
        compose.onNodeWithText("下一条草稿").assertIsDisplayed()
        compose.onNodeWithTag("ai.chat.retry").assertDoesNotExist()
        assertEquals(listOf("第一次请求", "第一次请求"), session.prompts)
    }

    @Test fun stopBeforeExecutionAllowsRetryAndClearDiscardsQueuedReplies() {
        openChat()
        compose.onNodeWithTag("ai.chat.input").performTextReplacement("停止这条请求")
        compose.onNodeWithTag("ai.chat.send").performClick()
        compose.onNodeWithTag("ai.chat.send").performClick()
        compose.onNodeWithTag("ai.chat.send").assertIsNotEnabled()
        completeRequest()
        assertTrue(session.prompts.isEmpty())
        compose.onNodeWithTag("ai.chat.retry").assertIsDisplayed()
        compose.onNodeWithTag("ai.chat.retry").performClick()
        compose.runOnUiThread { worker.runNext() }
        compose.onNodeWithTag("ai.chat.clear").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("ai.chat.answer").assertDoesNotExist()
        compose.onNodeWithTag("ai.chat.user").assertDoesNotExist()
    }

    @Test fun imageOnlySendAndAttachmentRemovalWorkThroughMiuixControls() {
        openChat()
        compose.onNodeWithTag("ai.chat.send").assertIsNotEnabled()
        compose.runOnUiThread { chat!!.addImage(image()) }
        compose.onNodeWithTag("ai.chat.remove_image").performClick()
        compose.onNodeWithTag("ai.chat.attachments").assertDoesNotExist()
        compose.onNodeWithTag("ai.chat.send").assertIsNotEnabled()
        compose.runOnUiThread { chat!!.addImage(image()) }
        compose.onNodeWithTag("ai.chat.send").performClick()
        completeRequest()
        compose.onNodeWithTag("ai.chat.attachments").assertDoesNotExist()
        compose.onNodeWithTag("ai.chat.image").assertIsDisplayed()
        assertEquals(listOf(""), session.prompts)
        assertEquals(1, session.images.single().size)
    }

    @Test fun documentsRenderAndCanBeRemovedOrSentWithoutImages() {
        openChat()
        compose.runOnUiThread { chat!!.addDocument("偏好.txt", "喜欢白茶") }
        compose.onNodeWithTag("ai.chat.document_name").assertIsDisplayed()
        compose.onNodeWithTag("ai.chat.remove_document").performClick()
        compose.onNodeWithTag("ai.chat.attachments").assertDoesNotExist()
        compose.runOnUiThread { chat!!.addDocument("偏好.txt", "喜欢白茶") }
        compose.onNodeWithTag("ai.chat.send").performClick()
        completeRequest()
        compose.onNodeWithTag("ai.chat.document").assertIsDisplayed()
        compose.onNodeWithTag("ai.chat.attachments").assertDoesNotExist()
    }

    @Test fun promptPreservesMiddleInsertionSelectionAndCopiesTheSelectedText() {
        var saved: String? = null
        compose.runOnUiThread {
            prompt = PromptEditorDialog(compose.activity, "提示词光标", "前后内容", { saved = it }, true)
            prompt!!.show()
        }
        val input = compose.onNodeWithTag("settings.input.提示词光标")
        input.performTextInputSelection(TextRange(2))
        input.performTextInput("中间")
        assertEquals(TextRange(4), input.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        input.performTextInputSelection(TextRange(2, 4))
        input.performSemanticsAction(SemanticsActions.CopyText) { it() }
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals("中间", clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
        compose.onNodeWithTag("prompt.save").performClick()
        compose.runOnIdle { assertEquals("前后中间内容", saved) }
    }

    @Test fun chatPreservesMiddleInsertionAndSelectedCopyBeforeSending() {
        openChat()
        val input = compose.onNodeWithTag("ai.chat.input")
        input.performTextReplacement("前后内容")
        input.performTextInputSelection(TextRange(2))
        input.performTextInput("中间")
        assertEquals(TextRange(4), input.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        input.performTextInputSelection(TextRange(2, 4))
        input.performSemanticsAction(SemanticsActions.CopyText) { it() }
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals("中间", clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
        compose.onNodeWithTag("ai.chat.send").performClick()
        completeRequest()
        assertEquals(listOf("前后中间内容"), session.prompts)
    }

    // API 28 uses Miuix's path renderer; native Canvas snapshots cannot render RuntimeShader on API 33+.
    @Test @Config(sdk = [28], qualifiers = "w296dp-h560dp-mdpi")
    fun narrowEditorsKeepControlsInsideTheirFloatingWindows() { verifyEditorLayoutAndPreview() }

    @Test @Config(sdk = [28], qualifiers = "w640dp-h360dp-land-mdpi")
    fun landscapeEditorsKeepControlsInsideTheirFloatingWindows() { verifyEditorLayoutAndPreview() }

    private fun verifyEditorLayoutAndPreview() {
        openChat()
        compose.onNodeWithTag("ai.chat.input").performTextReplacement("检查多行对话和图片。".repeat(6))
        compose.onNodeWithTag("ai.chat.send").performClick()
        completeRequest()
        compose.runOnUiThread { chat!!.addImage(image()) }
        compose.runOnUiThread { chat!!.addDocument("参考.txt", "作品背景") }
        compose.onNodeWithTag("ai.chat.input").performTextReplacement("下一条草稿\n".repeat(6))
        compose.onNodeWithTag("ai.chat.send").assertIsDisplayed()
        compose.onNodeWithTag("ai.chat.messages").assertIsDisplayed()
        val input = compose.onNodeWithTag("ai.chat.input").getUnclippedBoundsInRoot()
        val send = compose.onNodeWithTag("ai.chat.send").getUnclippedBoundsInRoot()
        assertTrue(input.right <= send.left)
        assertEquals(48f, (send.bottom - send.top).value, 0.1f)
        compose.onNodeWithTag("ai.chat.document_name").assertIsDisplayed()
        savePreview("miuix.chat", "chat")
        compose.runOnUiThread {
            chat!!.dismiss()
            prompt = PromptEditorDialog(compose.activity, "一级提示词", "记忆设定\n保留身份与交流风格。\n".repeat(30), {}, true)
            prompt!!.show()
        }
        compose.onNodeWithTag("settings.input.一级提示词").assertIsDisplayed()
        compose.onNodeWithTag("prompt.save").assertIsDisplayed()
        compose.onNodeWithTag("prompt.cancel").assertIsDisplayed()
        val promptInput = compose.onNodeWithTag("settings.input.一级提示词").getUnclippedBoundsInRoot()
        val promptSave = compose.onNodeWithTag("prompt.save").getUnclippedBoundsInRoot()
        assertTrue(promptInput.bottom <= promptSave.top)
        savePreview("miuix.prompt", "prompt")
    }

    private fun savePreview(tag: String, name: String) {
        val directory = System.getenv("DOUAI_CHAT_SCREENSHOTS")?.let(::File) ?: return
        assertTrue(directory.isDirectory || directory.mkdirs())
        compose.onNodeWithTag(tag).assertIsDisplayed()
        compose.runOnUiThread {
            val window = (if (name == "chat") chat!! else prompt!!).window!!
            val decor = window.decorView
            val bounds = window.attributes
            decor.measure(View.MeasureSpec.makeMeasureSpec(bounds.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(bounds.height, View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, bounds.width, bounds.height)
            val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(bitmap))
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue("Editor preview did not render its controls", pixels.toSet().size > 8)
            File(directory, "miuix-$name-${bitmap.width}x${bitmap.height}.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            bitmap.recycle()
        }
    }

    private fun openChat() {
        compose.runOnUiThread {
            val config = AiModelConfig.fromJson(JSONObject().put("id", "compose-chat")
                .put("name", "测试模型").put("apiEndpoint", "https://example.com/v1")
                .put("modelName", "test-model"))
            chat = AiTestChatDialog(compose.activity, config, { session }, worker, true)
            chat!!.show()
        }
        compose.onNodeWithTag("miuix.chat").assertIsDisplayed()
    }

    private fun completeRequest() { compose.runOnUiThread { worker.runNext() }; compose.waitForIdle() }

    private fun image(): ChatImage {
        val bitmap = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.GREEN)
        val bytes = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)
        bitmap.recycle()
        return ChatImage("image/png", bytes.toByteArray())
    }

    private class FakeSession : AiTestChatDialog.Session {
        val prompts = mutableListOf<String>()
        val images = mutableListOf<List<ChatImage>>()
        var failure: Exception? = null
        override fun reply(message: String, images: List<ChatImage>): String {
            prompts += message
            this.images += images.toList()
            failure?.let { throw it }
            return "模型回复"
        }
        override fun cancel() = Unit
    }

    private class ManualWorker : AbstractExecutorService() {
        private val tasks = ArrayDeque<Runnable>()
        private var stopped = false
        fun runNext() { tasks.poll()?.run() }
        override fun execute(command: Runnable) { check(!stopped); tasks.add(command) }
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> { stopped = true; return tasks.toMutableList().also { tasks.clear() } }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = stopped
    }
}
