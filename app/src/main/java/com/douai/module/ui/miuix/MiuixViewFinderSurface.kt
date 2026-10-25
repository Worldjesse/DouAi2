package com.douai.module.ui.miuix

import android.app.Activity
import android.graphics.Rect
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

/** The canvas/controller remains native; every visible finder control shares the module's Miuix UI. */
class MiuixViewFinderSurface(activity: Activity, private val actions: Actions) : AutoCloseable {
    interface Actions {
        fun onClose()
        fun onRefresh()
        fun onUndo()
        fun onTree()
        fun onToggleBounds()
        fun onPrevious()
        fun onNext()
        fun onParent()
        fun onChild()
        fun onOverlap()
        fun onProperties()
        fun onRules()
        fun onHide()
        fun onIndex(index: Int)
    }

    private val environment = HostComposeEnvironment(activity, Runnable(actions::onClose))
    private var snapshot by mutableStateOf(Snapshot())
    private var closed = false
    val view = ComposeView(environment.context())

    init {
        view.tag = "douai.view_finder.chrome"
        view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        environment.attach(view)
        // A plain host Activity has no AndroidX owner on its decor. Keep the recomposer scoped
        // to our own lifecycle instead of asking WindowRecomposerPolicy to inspect host roots.
        view.setParentCompositionContext(view.createLifecycleAwareWindowRecomposer(lifecycle = environment.lifecycle))
        view.setContent { HostSettingsTheme { Content() } }
    }

    fun update(
        hasSelection: Boolean,
        title: String,
        resourceName: String,
        dimensions: String,
        depth: Int,
        selectedIndex: Int,
        total: Int,
        candidateIndex: Int,
        candidateCount: Int,
        canSave: Boolean,
        showAllBounds: Boolean,
        selectedBounds: Rect,
    ) {
        if (closed) return
        snapshot = Snapshot(hasSelection, title, resourceName, dimensions, depth, selectedIndex,
            total, candidateIndex, candidateCount, canSave, showAllBounds, Rect(selectedBounds))
    }

    override fun close() {
        if (closed) return
        closed = true
        environment.close()
    }

    @Composable
    private fun Content() {
        val state = snapshot
        val density = LocalDensity.current
        val insets = LocalView.current.rootWindowInsets
        val safeOrigin = IntOffset(insets?.systemWindowInsetLeft ?: 0, insets?.systemWindowInsetTop ?: 0)
        val safeRight = insets?.systemWindowInsetRight ?: 0
        val safeBottom = insets?.systemWindowInsetBottom ?: 0
        var expanded by remember { mutableStateOf(false) }
        // No background or pointer modifier belongs on this full-screen box. AndroidComposeView
        // declines touches outside its actual controls so the parent can pick the host's View.
        BoxWithConstraints(Modifier.fillMaxSize().testTag("view_finder.chrome")) {
            val rootSize = with(density) { IntSize(
                (maxWidth.roundToPx() - safeOrigin.x - safeRight).coerceAtLeast(1),
                (maxHeight.roundToPx() - safeOrigin.y - safeBottom).coerceAtLeast(1),
            ) }
            val availableWidth = with(density) { rootSize.width.toDp() }
            val availableHeight = with(density) { rootSize.height.toDp() }
            val landscape = availableWidth > availableHeight
            val margin = with(density) { 8.dp.roundToPx() }
            var toolbarSize by remember(maxWidth, maxHeight) { mutableStateOf(IntSize.Zero) }
            var panelSize by remember(maxWidth, maxHeight) { mutableStateOf(IntSize.Zero) }
            var toolbarDrag by remember(maxWidth, maxHeight) { mutableStateOf(Offset.Zero) }
            var panelDrag by remember(maxWidth, maxHeight) { mutableStateOf(Offset.Zero) }
            val toolbarWidth = if (landscape) 96.dp else minOf(400.dp, availableWidth - 16.dp)
            val panelWidth = if (landscape) minOf(340.dp, availableWidth * 0.46f)
                else minOf(420.dp, availableWidth - 16.dp)
            val toolbarBase = IntOffset(
                if (landscape) margin else (rootSize.width - toolbarSize.width) / 2,
                margin,
            )
            val toolbarPosition = boundedPosition(toolbarBase, toolbarDrag, toolbarSize, rootSize, margin)
            val panelBase = IntOffset(
                if (landscape) rootSize.width - panelSize.width - margin
                else (rootSize.width - panelSize.width) / 2,
                rootSize.height - panelSize.height - margin,
            )
            val panelPosition = boundedPosition(panelBase, panelDrag, panelSize, rootSize, margin)
            val maxPanelHeight = (availableHeight - if (landscape) 16.dp else 84.dp).coerceAtLeast(100.dp)

            Toolbar(
                state, landscape,
                Modifier.offset { toolbarPosition + safeOrigin }.width(toolbarWidth)
                    .onSizeChanged { toolbarSize = it },
                onDrag = { delta ->
                    val next = boundedPosition(toolbarPosition, delta, toolbarSize, rootSize, margin)
                    toolbarDrag = Offset((next.x - toolbarBase.x).toFloat(), (next.y - toolbarBase.y).toFloat())
                },
            )
            if (state.hasSelection) {
                SelectionPanel(
                    state, expanded, { expanded = !expanded },
                    Modifier.offset { panelPosition + safeOrigin }.width(panelWidth).heightIn(max = maxPanelHeight)
                        .onSizeChanged { panelSize = it },
                    onDrag = { delta ->
                        val next = boundedPosition(panelPosition, delta, panelSize, rootSize, margin)
                        panelDrag = Offset((next.x - panelBase.x).toFloat(), (next.y - panelBase.y).toFloat())
                    },
                )
                if (maxHeight >= 560.dp && !state.bounds.isEmpty
                    && toolbarSize != IntSize.Zero && panelSize != IntSize.Zero) {
                    val cardWidth = with(density) { minOf(220.dp, availableWidth - 32.dp).roundToPx() }
                    val cardHeight = with(density) { 52.dp.roundToPx() }
                    val gap = with(density) { 8.dp.roundToPx() }
                    val bounds = Rect(state.bounds).apply { offset(-safeOrigin.x, -safeOrigin.y) }
                    val candidateLeft = bounds.left.coerceIn(margin,
                        (rootSize.width - cardWidth - margin).coerceAtLeast(margin))
                    val positions = listOf(bounds.top - cardHeight - gap, bounds.bottom + gap)
                    val toolbarBounds = Rect(toolbarPosition.x, toolbarPosition.y,
                        toolbarPosition.x + toolbarSize.width, toolbarPosition.y + toolbarSize.height)
                    val panelBounds = Rect(panelPosition.x, panelPosition.y,
                        panelPosition.x + panelSize.width, panelPosition.y + panelSize.height)
                    val callout = positions.map { top ->
                        Rect(candidateLeft, top, candidateLeft + cardWidth, top + cardHeight)
                    }.firstOrNull {
                        it.top >= margin && it.bottom <= rootSize.height - margin
                            && !Rect.intersects(it, toolbarBounds) && !Rect.intersects(it, panelBounds)
                    }
                    if (callout != null) {
                        Column(Modifier.offset { IntOffset(callout.left, callout.top) + safeOrigin }
                            .width(with(density) { cardWidth.toDp() }).height(52.dp)
                            .clip(RoundedCornerShape(12.dp)).background(MiuixTheme.colorScheme.surface)
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .testTag("view_finder.selection_callout")) {
                            Text(state.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                color = MiuixTheme.colorScheme.onSurface,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(state.dimensions, fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            } else {
                Column(Modifier.align(if (landscape) Alignment.BottomEnd else Alignment.BottomCenter)
                    .padding(start = with(density) { safeOrigin.x.toDp() } + 12.dp,
                        end = with(density) { safeRight.toDp() } + 12.dp,
                        bottom = with(density) { safeBottom.toDp() } + 12.dp)
                    .width(minOf(320.dp, availableWidth - 24.dp))
                    .clip(RoundedCornerShape(16.dp)).background(MiuixTheme.colorScheme.surface)
                    .padding(16.dp).testTag("view_finder.guide")) {
                    Text("点选屏幕中的控件", fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        color = MiuixTheme.colorScheme.onSurface)
                    Text("也可在视图树中按名称、文字或 ID 查找", fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }

    @Composable
    private fun Toolbar(state: Snapshot, vertical: Boolean, modifier: Modifier, onDrag: (Offset) -> Unit) {
        val colors = MiuixTheme.colorScheme
        CompositionLocalProvider(LocalContentColor provides colors.onSurface) {
            val card = modifier.shadow(8.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp))
                .background(colors.surface).shieldCardTouches().padding(4.dp).testTag("view_finder.toolbar")
            if (vertical) {
                Column(card, horizontalAlignment = Alignment.CenterHorizontally) {
                    DragHandle("view_finder.drag", "拖动查找工具栏",
                        Modifier.fillMaxWidth().height(36.dp), onDrag)
                    SmallAction("退出", "exit", actions::onClose, Modifier.fillMaxWidth())
                    SmallAction("刷新", "refresh", actions::onRefresh, Modifier.fillMaxWidth())
                    SmallAction("视图树", "tree", actions::onTree, Modifier.fillMaxWidth())
                    SmallAction(if (state.showAllBounds) "轮廓开" else "轮廓", "bounds",
                        actions::onToggleBounds, Modifier.fillMaxWidth(),
                        stateDescription = if (state.showAllBounds) "显示全部轮廓" else "仅显示选中轮廓")
                }
            } else {
                Row(card, verticalAlignment = Alignment.CenterVertically) {
                    DragHandle("view_finder.drag", "拖动查找工具栏",
                        Modifier.width(36.dp).height(44.dp), onDrag)
                    SmallAction("退出", "exit", actions::onClose, Modifier.weight(1f))
                    SmallAction("刷新", "refresh", actions::onRefresh, Modifier.weight(1f))
                    SmallAction("视图树", "tree", actions::onTree, Modifier.weight(1.15f))
                    SmallAction(if (state.showAllBounds) "轮廓开" else "轮廓", "bounds",
                        actions::onToggleBounds, Modifier.weight(1f),
                        stateDescription = if (state.showAllBounds) "显示全部轮廓" else "仅显示选中轮廓")
                }
            }
        }
    }

    @Composable
    private fun SelectionPanel(
        state: Snapshot, expanded: Boolean, onExpand: () -> Unit,
        modifier: Modifier, onDrag: (Offset) -> Unit,
    ) {
        val colors = MiuixTheme.colorScheme
        CompositionLocalProvider(LocalContentColor provides colors.onSurface) {
            Column(modifier.shadow(10.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp))
                .background(colors.surface).shieldCardTouches().padding(horizontal = 12.dp)
                .testTag("view_finder.selection_panel")) {
                DragHandle("view_finder.selection_drag", "拖动选择面板",
                    Modifier.fillMaxWidth().height(22.dp), onDrag)
                Column(Modifier.weight(1f, fill = false).fillMaxWidth()
                    .verticalScroll(rememberScrollState())) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(state.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(end = 4.dp))
                        SmallAction(
                            if (state.candidateCount > 1 && state.candidateIndex >= 0)
                                "重叠 ${state.candidateIndex + 1}/${state.candidateCount}" else "重叠",
                            "overlap", actions::onOverlap, Modifier.width(88.dp),
                            enabled = state.candidateCount > 1,
                        )
                        SmallAction(if (expanded) "收起" else "展开", "expand", onExpand,
                            Modifier.width(48.dp), stateDescription = if (expanded) "已展开导航" else "已折叠导航")
                    }
                    Text("${state.resourceName}\n${state.dimensions} · 层级 ${state.depth}",
                        color = colors.onSurfaceVariantSummary, fontSize = 12.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().testTag("view_finder.selection_details"))
                    if (expanded) {
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            SmallAction("上一个", "上一个", actions::onPrevious, Modifier.weight(1f),
                                enabled = state.selectedIndex > 0)
                            SmallAction("下一个", "下一个", actions::onNext, Modifier.weight(1f),
                                enabled = state.selectedIndex in 0 until state.total - 1)
                            SmallAction("父节点", "父节点", actions::onParent, Modifier.weight(1f))
                            SmallAction("子节点", "子节点", actions::onChild, Modifier.weight(1f))
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            val maximum = (state.total - 1).coerceAtLeast(1)
                            Slider(
                                value = state.selectedIndex.coerceIn(0, maximum).toFloat(),
                                onValueChange = { value ->
                                    val index = value.roundToInt().coerceIn(0, (state.total - 1).coerceAtLeast(0))
                                    if (index != state.selectedIndex) actions.onIndex(index)
                                },
                                valueRange = 0f..maximum.toFloat(),
                                enabled = state.total > 1,
                                modifier = Modifier.weight(1f).height(44.dp)
                                    .testTag("view_finder.selection_seekbar").semantics {
                                        contentDescription = "切换控件"
                                        stateDescription = "${state.selectedIndex + 1} / ${state.total}"
                                    },
                            )
                            SmallAction("撤销", "undo", actions::onUndo, Modifier.width(56.dp))
                        }
                    }
                    Text("${state.selectedIndex + 1} / ${state.total}", fontSize = 11.sp,
                        color = colors.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 4.dp).testTag("view_finder.selection_count"))
                    Spacer(Modifier.height(6.dp))
                }
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    SmallAction("属性", "properties", actions::onProperties, Modifier.weight(1f))
                    SmallAction("规则", "confirm", actions::onRules, Modifier.weight(1f), enabled = state.canSave)
                    Button(onClick = actions::onHide, enabled = state.canSave,
                        colors = ButtonDefaults.buttonColorsPrimary(), minWidth = 0.dp, minHeight = 44.dp,
                        insideMargin = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        modifier = Modifier.weight(1.15f).height(44.dp).testTag("view_finder.hide")) {
                        Text("隐藏", fontSize = 14.sp, maxLines = 1)
                    }
                }
            }
        }
    }

    @Composable
    private fun SmallAction(
        title: String, tag: String, onClick: () -> Unit, modifier: Modifier = Modifier,
        enabled: Boolean = true, stateDescription: String? = null,
    ) {
        TextButton(title, onClick = onClick, enabled = enabled,
            minWidth = 0.dp, minHeight = 44.dp, cornerRadius = 12.dp,
            insideMargin = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
            textStyle = MiuixTheme.textStyles.button.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
            modifier = modifier.height(44.dp).testTag("view_finder.$tag").semantics {
                if (stateDescription != null) this.stateDescription = stateDescription
            })
    }

    @Composable
    private fun DragHandle(tag: String, description: String, modifier: Modifier, onDrag: (Offset) -> Unit) {
        val latestDrag by rememberUpdatedState(onDrag)
        Box(modifier.testTag(tag).semantics { contentDescription = description }
            .pointerInput(Unit) {
                detectDragGestures { change, amount -> change.consume(); latestDrag(amount) }
            }, contentAlignment = Alignment.Center) {
            Box(Modifier.size(28.dp, 4.dp).clip(RoundedCornerShape(2.dp))
                .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.38f)))
        }
    }

    private fun Modifier.shieldCardTouches(): Modifier = pointerInput(Unit) {
        // A hit on this card belongs to Compose. Observing without consuming also lets Miuix
        // buttons, sliders, scrolling and the drag handle keep their own gesture ownership.
        awaitPointerEventScope {
            while (true) awaitPointerEvent(PointerEventPass.Final)
        }
    }

    private fun boundedPosition(base: IntOffset, drag: Offset, size: IntSize, root: IntSize, margin: Int): IntOffset =
        IntOffset((base.x + drag.x).roundToInt().coerceIn(margin,
            (root.width - size.width - margin).coerceAtLeast(margin)),
            (base.y + drag.y).roundToInt().coerceIn(margin,
                (root.height - size.height - margin).coerceAtLeast(margin)))

    private data class Snapshot(
        val hasSelection: Boolean = false,
        val title: String = "",
        val resourceName: String = "",
        val dimensions: String = "",
        val depth: Int = 0,
        val selectedIndex: Int = -1,
        val total: Int = 0,
        val candidateIndex: Int = -1,
        val candidateCount: Int = 0,
        val canSave: Boolean = false,
        val showAllBounds: Boolean = false,
        val bounds: Rect = Rect(),
    )
}
