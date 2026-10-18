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
import androidx.compose.ui.test.onAllNodesWithText
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
import com.douai.module.config.AiModelSettings
import com.douai.module.config.FeatureSettings
import com.douai.module.config.SettingsStore
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
    private var modelEditor: AiModelEditor? = null
    private var automationSettings: AutomationSettingsDialog? = null
    private val worker = ManualWorker()
    private val session = FakeSession()

    @After fun closeEditors() { compose.runOnUiThread {
        chat?.dismiss(); prompt?.dismiss(); modelEditor?.close(); automationSettings?.close()
    } }

    @Test fun automationDialogUsesMiuixTogglesAndStopControl() {
        var settings = FeatureSettings.defaults()
        val values = mutableMapOf<String, Boolean>()
        val store = object : SettingsStore {
            override fun load() = settings
            override fun save(value: FeatureSettings) { settings = value }
            override fun getBoolean(key: String, fallback: Boolean) = values[key] ?: fallback
            override fun getString(key: String, fallback: String) = fallback
            override fun putBoolean(key: String, value: Boolean) { values[key] = value }
            override fun putString(key: String, value: String) = Unit
            override fun isInHost() = true
        }
        compose.runOnUiThread {
            automationSettings = AutomationSettingsDialog(compose.activity, store, true)
            automationSettings!!.dialog().show()
        }
        compose.onNodeWithTag("settings.toggle.自动点赞").assertIsNotEnabled()
        compose.onNodeWithTag("settings.toggle.自动化总开关").performClick()
        compose.onNodeWithTag("settings.toggle.自动点赞").performClick()
        compose.runOnIdle { assertTrue(settings.automationEnabled); assertTrue(settings.autoLikeEnabled) }
        compose.onNodeWithTag("automation.stop").performClick()
        compose.onNodeWithTag("settings.toggle.自动点赞").assertIsNotEnabled()
        compose.runOnIdle {
            assertFalse(settings.automationEnabled)
            assertFalse(settings.autoLikeEnabled)
            assertFalse(store.getBoolean("native_auto_reply", true))
            assertFalse(store.getBoolean("native_auto_comment", true))
            assertFalse(store.getBoolean("streak_enabled", true))
        }
    }

    @Test @Config(sdk = [28, 35])
    fun modelEditorSavesThroughMiuixFieldsAndRefreshesOnlyAfterPersistence() {
        val values = mutableMapOf<String, String>()
        val store = object : SettingsStore {
            override fun load() = FeatureSettings.defaults()
            override fun save(settings: FeatureSettings) = Unit
            override fun getBoolean(key: String, fallback: Boolean) = fallback
            override fun getString(key: String, fallback: String) = values[key] ?: fallback
            override fun putBoolean(key: String, value: Boolean) = Unit
            override fun putString(key: String, value: String) { values[key] = value }
            override fun isInHost() = true
        }
        val config = AiModelConfig.fromJson(JSONObject().put("id", "miuix-editor")
            .put("name", "旧配置名称").put("apiEndpoint", "https://example.com/v1")
            .put("modelName", "saved-model"))
        AiModelSettings(listOf(config), config.id).save(store)
        var refreshes = 0
        compose.runOnUiThread {
            modelEditor = AiModelEditor(compose.activity, store, config, true, { refreshes++ })
            modelEditor!!.show { dialog, onDismiss ->
                dialog.setOwnerActivity(compose.activity)
                dialog.setOnDismissListener { onDismiss.run() }
                dialog.show()
            }
        }
        compose.waitForIdle()
        compose.runOnUiThread {
            val avatar = modelEditor!!.dialog().window!!.decorView
                .findViewWithTag<android.widget.ImageView>("ai.message_contact_avatar_preview")
            val parent = avatar.parent as android.view.ViewGroup
            assertTrue(avatar.isShown && avatar.width > 0)
            assertTrue(kotlin.math.abs(avatar.left + avatar.width / 2f - parent.width / 2f) <= 1f)
        }
        saveModelEditorPreview("top")
        compose.onNodeWithTag("settings.input.配置名称").assertIsDisplayed()
            .performTextReplacement("更新配置名称")
        compose.onNodeWithTag("settings.input.联系人名字（留空使用配置名称）")
            .performTextReplacement("消息小助手")
        compose.runOnUiThread {
            modelEditor!!.dialog().window!!.decorView.findViewWithTag<android.widget.ScrollView>("ai.model.scroll")
                .fullScroll(android.view.View.FOCUS_DOWN)
        }
        compose.waitForIdle()
        saveModelEditorPreview("bottom")
        compose.onNodeWithTag("ai.message_contact_enabled").performClick()
        compose.onNodeWithTag("miuix.dialog.button.-1").performClick()
        compose.runOnIdle {
            assertFalse(modelEditor!!.dialog().isShowing)
            assertEquals("更新配置名称", AiModelSettings.read(store).selected().name)
            assertEquals("saved-model", AiModelSettings.read(store).selected().modelName)
            assertTrue(AiModelSettings.read(store).selected().messageContactEnabled)
            assertEquals("消息小助手", AiModelSettings.read(store).selected().messageContactDisplayName())
            assertEquals(1, refreshes)
        }
    }

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

    @Test @Config(sdk = [28], qualifiers = "w296dp-h560dp-mdpi")
    fun contactConversationShowsCustomIdentityAndEditableProfileInMiuix() {
        var edited = false
        compose.runOnUiThread {
            val config = AiModelConfig.fromJson(JSONObject().put("id", "miuix-contact")
                .put("name", "配置名称").put("apiEndpoint", "https://example.com/v1")
                .put("modelName", "test-model").put("messageContactEnabled", true)
                .put("messageContactName", "小助手").put("messageContactAvatar", image().dataUrl()))
            chat = AiTestChatDialog(compose.activity, config, { session }, worker, worker,
                true, { null }, true)
            chat!!.setContactEditAction { edited = true }
            chat!!.show()
            worker.runNext()
        }
        compose.onNodeWithTag("ai.chat.title").assertIsDisplayed()
        compose.onNodeWithText("测试对话").assertDoesNotExist()
        compose.onNodeWithTag("ai.chat.contact_avatar").assertIsDisplayed()
        compose.onNodeWithTag("ai.chat.contact_edit").assertIsDisplayed()
        compose.onNodeWithTag("ai.chat.input").performTextReplacement("你好")
        compose.onNodeWithTag("ai.chat.send").performClick()
        completeRequest()
        compose.onAllNodesWithText("小助手").assertCountEquals(2)
        compose.onNodeWithText("模型回复").assertIsDisplayed()
        compose.onNodeWithTag("ai.chat.contact_edit").performClick()
        compose.runOnIdle { assertTrue(edited) }
        val title = compose.onNodeWithTag("ai.chat.title").getUnclippedBoundsInRoot()
        assertTrue("Contact title must remain legible on narrow screens", (title.right - title.left).value >= 60f)
        savePreview("miuix.chat", "contact-chat")
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
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("ai.chat.image").fetchSemanticsNodes().isNotEmpty()
        }
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
            val window = (if (name == "prompt") prompt!! else chat!!).window!!
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

    private fun saveModelEditorPreview(position: String) {
        // Software Canvas cannot replay the API 33+ RuntimeShader used by Miuix.
        // Keep interaction/layout assertions on both SDKs; export pixels on SDK 28.
        if (android.os.Build.VERSION.SDK_INT >= 33) return
        val directory = System.getenv("DOUAI_MODEL_SCREENSHOTS")?.let(::File) ?: return
        assertTrue(directory.isDirectory || directory.mkdirs())
        compose.runOnUiThread {
            val decor = modelEditor!!.dialog().window!!.decorView
            val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(bitmap))
            File(directory, "model-editor-$position.png").outputStream().use {
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
