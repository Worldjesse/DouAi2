package com.douai.module.ui.miuix

import android.content.Context
import android.view.View
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.douai.module.config.AutomationHistoryStore.Entry
import java.text.DateFormat
import java.util.Date
import java.util.function.Consumer
import java.util.function.Function
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** A single Compose list avoids ListView measuring detached Compose rows. */
class MiuixAutomationHistory(
    context: Context,
    private val onEntry: Consumer<Entry>,
    private val rowTitle: Function<Entry, String>,
) {
    private var currentEntries by mutableStateOf<List<Entry>>(emptyList())
    private var stateText by mutableStateOf("暂无记录")
    val view = ComposeView(context).apply {
        tag = "automation.history"
        id = View.generateViewId()
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        setContent {
            HostSettingsTheme {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp).testTag("automation.history")) {
                    if (currentEntries.isEmpty()) {
                        item {
                            BasicComponent(
                                title = stateText,
                                modifier = Modifier.testTag("automation.history.empty"),
                            )
                        }
                    } else {
                        itemsIndexed(currentEntries, key = { index, entry -> "${entry.id}:$index" }) { _, entry ->
                            BasicComponent(
                                title = rowTitle.apply(entry),
                                summary = DateFormat.getDateTimeInstance().format(Date(entry.createdAt)),
                                modifier = Modifier.testTag("automation.history.${entry.id}"),
                                onClick = { onEntry.accept(entry) },
                                bottomAction = {
                                    Text(
                                        entry.output,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        fontSize = 13.sp,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    fun setState(value: String) {
        currentEntries = emptyList()
        stateText = value
    }

    fun setEntries(value: List<Entry>) {
        currentEntries = value.toList()
        stateText = "暂无记录"
    }
}
