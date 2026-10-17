package com.douai.module.ui.miuix

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.Button as AndroidButton
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Close
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** The detached Java view model retains request and picker behavior; all visible controls use Miuix. */
class MiuixChatEditorSurface(activity: Activity, private val model: View, close: Runnable) : AutoCloseable {
    private val environment = HostComposeEnvironment(activity, close)
    private val input = model.findViewWithTag<EditText>("ai.chat.input")
    private val send = model.findViewWithTag<ImageButton>("ai.chat.send")
    private val attach = model.findViewWithTag<ImageButton>("ai.chat.attach")
    private var state by mutableStateOf(readState())
    val view = ComposeView(environment.context())

    init {
        view.tag = "miuix.chat.root"
        environment.attach(view)
        view.setContent { HostSettingsTheme { Content() } }
    }

    fun refresh() { state = readState() }
    fun bindBackNavigation(dialog: Dialog) = environment.bindBackNavigation(dialog)
    override fun close() = environment.close()

    private fun readState(): ChatState {
        val messages = model.findViewWithTag<View>("ai.chat.messages")
        val rows = when (messages) {
            is ListView -> (0 until messages.adapter.count).map { messages.adapter.getView(it, null, messages) }
            is ViewGroup -> (0 until messages.childCount).map { messages.getChildAt(it) }
            else -> emptyList()
        }
        val transcript = rows.map { messageRow ->
            val row = messageRow as ViewGroup
            val bubble = row.getChildAt(0) as LinearLayout
            val user = bubble.findViewWithTag<TextView>("ai.chat.user")
            val text = user ?: bubble.findViewWithTag("ai.chat.answer")
            ChatMessage(
                text, user != null, (bubble.getChildAt(0) as? TextView)?.text?.toString().orEmpty(),
                text.text.toString(), text.currentTextColor,
                (bubble.findViewWithTag<LinearLayout>("ai.chat.message.images")?.let { images ->
                    (0 until images.childCount).mapNotNull { child ->
                        (images.getChildAt(child) as? ImageView)?.bitmap()
                    }
                } ?: emptyList()),
                (0 until bubble.childCount).mapNotNull { child ->
                    bubble.getChildAt(child).takeIf {
                        it.tag == "ai.chat.document" && it.visibility == View.VISIBLE
                    }?.let { (it as? TextView)?.text?.toString() }
                },
                bubble.findViewWithTag<View>("ai.chat.retry")
                    ?.takeIf { it.visibility == View.VISIBLE },
            )
        }
        val attachments = model.findViewWithTag<HorizontalScrollView>("ai.chat.attachments")
            .getChildAt(0) as LinearLayout
        return ChatState(
            model.findViewWithTag<TextView>("ai.chat.title").text.toString(),
            model.findViewWithTag<ImageView>("ai.chat.contact_avatar")?.let { ContactAvatar(it.bitmap()) },
            model.findViewWithTag<View>("ai.chat.contact_edit")?.takeIf { it.visibility == View.VISIBLE },
            input.text.toString(),
            model.findViewWithTag<TextView>("ai.chat.model").text.toString(),
            model.findViewWithTag<TextView>("ai.chat.state").text.toString(),
            send.contentDescription.toString(), send.isEnabled, attach.isEnabled,
            transcript,
            (0 until attachments.childCount).map { index ->
                val item = attachments.getChildAt(index) as LinearLayout
                val content = item.getChildAt(0)
                DraftImage((content as? ImageView)?.bitmap(), (content as? TextView)?.text?.toString(),
                    item.getChildAt(1))
            },
        )
    }

    @Composable private fun Content() {
        val snapshot = state
        val transcript = rememberLazyListState()
        LaunchedEffect(snapshot.messages) {
            if (snapshot.messages.isNotEmpty()) transcript.animateScrollToItem(snapshot.messages.lastIndex)
        }
        BoxWithConstraints(
            Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp))
                .background(MiuixTheme.colorScheme.background).testTag("miuix.chat"),
        ) {
            val compact = maxHeight < 400.dp
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = if (compact) 4.dp else 10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    snapshot.avatar?.let { avatar ->
                        if (avatar.bitmap != null) Image(avatar.bitmap.asImageBitmap(),
                            contentDescription = snapshot.title + "头像",
                            modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp))
                                .testTag("ai.chat.contact_avatar"))
                        else Image(painterResource(android.R.drawable.sym_def_app_icon),
                            contentDescription = snapshot.title + "头像",
                            modifier = Modifier.size(32.dp).testTag("ai.chat.contact_avatar"))
                    }
                    Text(snapshot.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 4.dp).testTag("ai.chat.title"))
                    snapshot.contactEdit?.let { action -> NativeAction(action, android.R.drawable.ic_menu_edit,
                        "修改联系人", "ai.chat.contact_edit") }
                    model.findViewWithTag<View>("ai.chat.memory").takeIf { it.visibility == View.VISIBLE }?.let { action ->
                        NativeAction(action, android.R.drawable.ic_menu_info_details,
                            "查看一级提示词", "ai.chat.memory")
                    }
                    NativeAction(model.findViewWithTag("ai.chat.clear"), android.R.drawable.ic_menu_delete,
                        "清空对话", "ai.chat.clear")
                    IconButton(onClick = { model.findViewWithTag<View>("ai.chat.close").performClick() },
                        modifier = Modifier.size(48.dp).testTag("ai.chat.close")) {
                        Icon(MiuixIcons.Basic.Close, contentDescription = "关闭")
                    }
                }
                Text(snapshot.model, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp).testTag("ai.chat.model"),
                    fontSize = 13.sp)
                if (snapshot.status.isNotEmpty()) Text(snapshot.status, fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("ai.chat.state"))
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().padding(top = if (compact) 4.dp else 10.dp)
                        .testTag("ai.chat.messages"),
                    state = transcript,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(snapshot.messages, key = { System.identityHashCode(it.identity) }) { message ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement =
                            if (message.user) Arrangement.End else Arrangement.Start) {
                            Column(
                                Modifier.fillMaxWidth(0.94f)
                                    .background(if (message.user) MiuixTheme.colorScheme.secondaryContainer
                                        else MiuixTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                                    .padding(12.dp),
                            ) {
                                Text(message.role, fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.testTag("ai.chat.role"))
                                SelectionContainer {
                                    Text(message.text, color = hostThemeColor(message.color), modifier = Modifier.padding(top = 4.dp)
                                        .testTag(if (message.user) "ai.chat.user" else "ai.chat.answer"))
                                }
                                message.images.forEach { image -> Image(image.asImageBitmap(), contentDescription = "图片",
                                    modifier = Modifier.fillMaxWidth().height(96.dp).padding(top = 6.dp)
                                        .testTag("ai.chat.image")) }
                                message.documents.forEach { name -> Text(name, fontSize = 13.sp,
                                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("ai.chat.document")) }
                                message.retry?.let { retry ->
                                    TextButton("重新发送", onClick = { retry.performClick() },
                                        enabled = (snapshot.sendEnabled || snapshot.attachEnabled)
                                            && snapshot.sendDescription == "发送",
                                        modifier = Modifier.testTag("ai.chat.retry"))
                                }
                            }
                        }
                    }
                }
                if (snapshot.images.isNotEmpty()) Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("ai.chat.attachments"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    snapshot.images.forEach { draft ->
                        draft.bitmap?.let { bitmap -> Image(bitmap.asImageBitmap(), contentDescription = "图片",
                            modifier = Modifier.size(if (compact) 42.dp else 56.dp).testTag("ai.chat.image")) }
                        draft.name?.let { name -> Text(name, fontSize = 13.sp, maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.size(width = 120.dp, height = 48.dp).testTag("ai.chat.document_name")) }
                        IconButton(onClick = { draft.remove.performClick() }, modifier = Modifier.size(48.dp)
                            .testTag(if (draft.name == null) "ai.chat.remove_image" else "ai.chat.remove_document")) {
                            Icon(MiuixIcons.Basic.Close, contentDescription = if (draft.name == null) "移除图片" else "移除文件")
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = if (compact) 4.dp else 8.dp),
                    verticalAlignment = Alignment.Bottom) {
                    NativeAction(attach, android.R.drawable.ic_menu_add, "添加附件", "ai.chat.attach",
                        snapshot.attachEnabled)
                    TextField(
                        value = snapshot.draft,
                        onValueChange = { input.setText(it); input.setSelection(input.length()) },
                        label = "输入消息", useLabelAsPlaceholder = true,
                        maxLines = if (compact) 2 else 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                        modifier = Modifier.weight(1f).heightIn(max = if (compact) 92.dp else 136.dp)
                            .testTag("ai.chat.input"),
                    )
                    IconButton(onClick = { send.performClick() }, enabled = snapshot.sendEnabled,
                        modifier = Modifier.size(48.dp).testTag("ai.chat.send")
                            .semantics { if (!snapshot.sendEnabled) disabled() }) {
                        if (snapshot.sendDescription == "发送") Icon(
                            painterResource(android.R.drawable.ic_menu_send), contentDescription = "发送",
                            modifier = Modifier.size(24.dp))
                        else Icon(MiuixIcons.Basic.Close, contentDescription = "停止请求")
                    }
                }
            }
        }
    }

    @Composable private fun NativeAction(action: View, resource: Int, description: String, tag: String,
                                         enabled: Boolean = true) {
        IconButton(onClick = { action.performClick() }, enabled = enabled,
            modifier = Modifier.size(48.dp).testTag(tag).semantics { if (!enabled) disabled() }) {
            Icon(painterResource(resource), contentDescription = description, modifier = Modifier.size(24.dp))
        }
    }

    private data class ChatState(val title: String, val avatar: ContactAvatar?, val contactEdit: View?,
                                 val draft: String, val model: String, val status: String,
                                 val sendDescription: String, val sendEnabled: Boolean, val attachEnabled: Boolean,
                                 val messages: List<ChatMessage>, val images: List<DraftImage>)
    private data class ContactAvatar(val bitmap: Bitmap?)
    private data class ChatMessage(val identity: View, val user: Boolean, val role: String, val text: String, val color: Int,
                                   val images: List<Bitmap>, val documents: List<String>, val retry: View?)
    private data class DraftImage(val bitmap: Bitmap?, val name: String?, val remove: View)
    private fun ImageView.bitmap() = (drawable as? BitmapDrawable)?.bitmap
}

/** The edit value is never trimmed or filtered, including large, multiline prompts. */
class MiuixPromptEditorSurface(
    activity: Activity,
    private val title: String,
    private val input: EditText,
    private val save: AndroidButton,
    private val cancel: AndroidButton,
    close: Runnable,
) : AutoCloseable {
    private val environment = HostComposeEnvironment(activity, close)
    private var draft by mutableStateOf(input.text.toString())
    private val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { draft = s.toString() }
        override fun afterTextChanged(s: Editable?) = Unit
    }
    val view = ComposeView(environment.context())

    init {
        view.tag = "miuix.prompt.root"
        input.addTextChangedListener(watcher)
        environment.attach(view)
        view.setContent { HostSettingsTheme { Content() } }
    }

    fun bindBackNavigation(dialog: Dialog) = environment.bindBackNavigation(dialog)
    override fun close() { input.removeTextChangedListener(watcher); environment.close() }

    @Composable private fun Content() {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        Column(Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp))
            .background(MiuixTheme.colorScheme.background).padding(16.dp).testTag("miuix.prompt")) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp))
            TextField(
                value = draft,
                onValueChange = { input.setText(it) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                modifier = Modifier.weight(1f).fillMaxWidth().focusRequester(focus)
                    .testTag("settings.input.$title"),
            )
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton("取消", onClick = { cancel.performClick() },
                    modifier = Modifier.weight(1f).testTag("prompt.cancel"))
                Button(onClick = { save.performClick() }, colors = ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.weight(1f).testTag("prompt.save")) { Text("保存") }
            }
        }
    }
}
