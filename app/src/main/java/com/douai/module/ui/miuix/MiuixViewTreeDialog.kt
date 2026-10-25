package com.douai.module.ui.miuix

import android.app.Activity
import android.app.Dialog
import android.graphics.Color as AndroidColor
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toDrawable
import com.douai.module.ui.HostDialogBounds
import com.douai.module.ui.ModuleTheme
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Close
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** A module-owned tree browser. Search state belongs to the current finder session only. */
class MiuixViewTreeDialog private constructor() {
    class Entry(
        val view: View,
        val index: Int,
        val depth: Int,
        val className: String,
        val resourceName: String,
        val summary: String,
        val dimensions: String,
        val visible: Boolean,
        val actionable: Boolean,
    )

    class Snapshot(val entries: List<Entry>, val truncated: Boolean)

    interface Listener {
        fun onSelect(view: View): Boolean
        fun onRefresh(): Snapshot
        fun onQueryChanged(query: String, visibleOnly: Boolean, actionableOnly: Boolean)
        fun matches(view: View, query: String): Boolean
    }

    companion object {
        @JvmStatic
        fun show(
            activity: Activity,
            initial: Snapshot,
            selected: View?,
            query: String,
            visibleOnly: Boolean,
            actionableOnly: Boolean,
            listener: Listener,
        ): Dialog {
            lateinit var dialog: Dialog
            val environment = HostComposeEnvironment(activity, Runnable { dialog.dismiss() })
            dialog = object : Dialog(environment.context(), ModuleTheme.dialogTheme(environment.context())) {
                override fun onStop() {
                    environment.close()
                    super.onStop()
                }
            }
            dialog.setOwnerActivity(activity)
            dialog.setCanceledOnTouchOutside(true)
            dialog.setOnDismissListener { environment.close() }
            val view = ComposeView(environment.context()).apply { tag = "view_finder.tree" }
            environment.attach(view)
            view.setContent {
                HostSettingsTheme {
                    TreeContent(activity, initial, selected, query, visibleOnly, actionableOnly, listener,
                        close = { dialog.dismiss() })
                }
            }
            dialog.setContentView(view)
            dialog.window?.apply {
                setBackgroundDrawable(AndroidColor.TRANSPARENT.toDrawable())
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                attributes = attributes.apply { dimAmount = 0.45f }
            }
            try {
                dialog.show()
                environment.bindBackNavigation(dialog)
                HostDialogBounds.install(activity, dialog, false)
            } catch (failure: RuntimeException) {
                dialog.dismiss()
                environment.close()
                throw failure
            }
            return dialog
        }

        @Composable
        private fun TreeContent(
            activity: Activity,
            initial: Snapshot,
            selected: View?,
            query: String,
            visibleOnly: Boolean,
            actionableOnly: Boolean,
            listener: Listener,
            close: () -> Unit,
        ) {
            var snapshot by remember { mutableStateOf(Snapshot(initial.entries.toList(), initial.truncated)) }
            var search by remember { mutableStateOf(query) }
            var visible by remember { mutableStateOf(visibleOnly) }
            var actionable by remember { mutableStateOf(actionableOnly) }
            val focus = LocalFocusManager.current
            val matching = remember(snapshot, search, visible, actionable) {
                snapshot.entries.filter { entry ->
                    (!visible || entry.visible) && (!actionable || entry.actionable)
                        && listener.matches(entry.view, search)
                }
            }
            val refresh: () -> Unit = {
                focus.clearFocus()
                try {
                    val latest = listener.onRefresh()
                    snapshot = Snapshot(latest.entries.toList(), latest.truncated)
                } catch (_: RuntimeException) {
                    Toast.makeText(activity, "刷新失败，请稍后重试", Toast.LENGTH_SHORT).show()
                }
            }
            Scaffold(
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp))
                    .background(MiuixTheme.colorScheme.background, RoundedCornerShape(20.dp))
                    .testTag("view_finder.tree_page"),
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
            ) { padding ->
                BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
                    // The controls can scroll independently when an IME or landscape window is short.
                    val compact = maxHeight < 360.dp
                    val columns = compact || maxWidth > 500.dp && maxWidth > maxHeight
                    Column(Modifier.fillMaxSize()) {
                        Row(Modifier.fillMaxWidth().height(if (compact) 44.dp else 56.dp)
                            .padding(start = 20.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("View 树", fontSize = if (compact) 18.sp else 20.sp,
                                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            IconButton(onClick = close, modifier = Modifier.size(40.dp)
                                .testTag("view_finder.tree_close")) {
                                Icon(MiuixIcons.Basic.Close, contentDescription = "关闭视图树")
                            }
                        }
                        val filters: @Composable () -> Unit = {
                            TextField(
                                value = search,
                                onValueChange = {
                                    search = it
                                    listener.onQueryChanged(search, visible, actionable)
                                },
                                label = "搜索视图",
                                useLabelAsPlaceholder = true,
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("view_finder.tree_search"),
                            )
                            if (!compact) Text("类名 / ID / 文字 / 描述", fontSize = 11.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(start = 8.dp, top = 4.dp))
                            if (columns) {
                                Filter("仅可见", "view_finder.tree_visible", visible) {
                                    visible = !visible
                                    listener.onQueryChanged(search, visible, actionable)
                                }
                                Filter("可交互", "view_finder.tree_clickable", actionable) {
                                    actionable = !actionable
                                    listener.onQueryChanged(search, visible, actionable)
                                }
                            } else Row(Modifier.fillMaxWidth()) {
                                Column(Modifier.weight(1f)) {
                                    Filter("仅可见", "view_finder.tree_visible", visible) {
                                        visible = !visible
                                        listener.onQueryChanged(search, visible, actionable)
                                    }
                                }
                                Column(Modifier.weight(1f)) {
                                    Filter("可交互", "view_finder.tree_clickable", actionable) {
                                        actionable = !actionable
                                        listener.onQueryChanged(search, visible, actionable)
                                    }
                                }
                            }
                        }
                        val results: @Composable (Modifier) -> Unit = { modifier ->
                            Results(snapshot, matching, selected, modifier) { entry ->
                                focus.clearFocus()
                                val accepted = try { listener.onSelect(entry.view) }
                                catch (_: RuntimeException) { false }
                                if (accepted) close()
                                else Toast.makeText(activity, "视图已变化，请刷新后重新选择", Toast.LENGTH_SHORT).show()
                            }
                        }
                        if (columns) {
                            Row(Modifier.weight(1f).fillMaxWidth()) {
                                Column(Modifier.weight(0.42f).fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(start = 12.dp, end = 8.dp)) { filters() }
                                results(Modifier.weight(0.58f).fillMaxSize())
                            }
                        } else {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { filters() }
                            results(Modifier.weight(1f).fillMaxWidth())
                        }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (compact) 4.dp else 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton("返回", onClick = close,
                                modifier = Modifier.weight(1f).testTag("view_finder.tree_back"))
                            Button(onClick = refresh, colors = ButtonDefaults.buttonColorsPrimary(),
                                modifier = Modifier.weight(1f).testTag("view_finder.tree_refresh")) {
                                Text("刷新")
                            }
                        }
                    }
                }
            }
        }

        @Composable
        private fun Filter(title: String, tag: String, checked: Boolean, change: () -> Unit) {
            Row(Modifier.fillMaxWidth().height(44.dp)
                .toggleable(value = checked, role = Role.Checkbox, onValueChange = { change() })
                .testTag(tag), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(state = if (checked) ToggleableState.On else ToggleableState.Off,
                    onClick = null, modifier = Modifier.size(44.dp))
                Text(title, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        @Composable
        private fun Results(snapshot: Snapshot, matching: List<Entry>, selected: View?,
                            modifier: Modifier, choose: (Entry) -> Unit) {
            Column(modifier) {
                Text(buildString {
                    append("匹配 ").append(matching.size).append(" / ").append(snapshot.entries.size)
                    if (snapshot.truncated) append(" · 已达扫描上限")
                }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("view_finder.tree_count"), fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("view_finder.tree_list")) {
                    if (matching.isEmpty()) item {
                        BasicComponent(title = "没有匹配的视图", summary = "调整关键词或筛选条件，或刷新当前窗口")
                    }
                    items(matching, key = { it.index }) { entry ->
                        val current = entry.view === selected
                        val shape = RoundedCornerShape(12.dp)
                        var row = Modifier.fillMaxWidth()
                            .padding(start = (entry.depth.coerceIn(0, 4) * 6).dp, end = 4.dp, bottom = 4.dp)
                            .clip(shape)
                        if (current) row = row.background(MiuixTheme.colorScheme.secondaryContainer, shape)
                            .border(1.dp, MiuixTheme.colorScheme.primary, shape)
                        BasicComponent(
                            title = "#${entry.index + 1} ${entry.className.substringAfterLast('.')}",
                            summary = (entry.resourceName.ifEmpty { "无资源 ID" } + "\n" + entry.summary).trimEnd(),
                            modifier = row.testTag("view_finder.tree_row.${entry.index}")
                                .semantics { this.selected = current },
                            onClick = { choose(entry) },
                            bottomAction = {
                                Text(entry.dimensions, fontSize = 11.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.padding(top = 4.dp))
                            },
                        )
                    }
                    item { Spacer(Modifier.height(4.dp)) }
                }
            }
        }
    }
}
