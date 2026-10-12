package com.douai.module.ui.miuix

import android.content.Context
import android.view.View
import android.widget.ImageButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.douai.module.feature.FeedGestureToolsHook
import java.util.function.IntConsumer
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.TextButton

object MiuixExtraControls {
    @JvmStatic
    fun iconAction(context: Context, action: ImageButton, resource: Int): View =
        ComposeView(context).apply {
            tag = action.tag
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                HostSettingsTheme {
                    IconButton(onClick = { action.performClick() }, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(resource), contentDescription = action.contentDescription?.toString())
                    }
                }
            }
        }

    @JvmStatic
    fun gestureGrid(context: Context, prefix: String, initial: Array<String>, choose: IntConsumer): GestureGrid =
        GestureGrid(context, prefix, initial, choose)

    class GestureGrid(context: Context, prefix: String, initial: Array<String>, choose: IntConsumer) {
        private val actions = mutableStateListOf(*initial)
        val view = ComposeView(context).apply {
            tag = "settings.gesture.grid.$prefix"
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                HostSettingsTheme {
                    Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        repeat(3) { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                repeat(3) { column ->
                                    val index = row * 3 + column
                                    val key = "$prefix" + "_" + FeedGestureToolsHook.REGION_IDS[index]
                                    TextButton(
                                        text = FeedGestureToolsHook.REGION_LABELS[index] + "\n" + actions[index],
                                        onClick = { choose.accept(index) },
                                        modifier = Modifier.weight(1f).heightIn(min = 96.dp)
                                            .testTag("settings.gesture.$key"),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        fun setAction(index: Int, label: String) { actions[index] = label }
    }
}
