# 视图查找与自定义视图改进

日期：2026-10-25。项目与官方演示比较见 [GitHub 调研](view-finder-research-2026-10-25.md)。本记录对应构建前的源码专项阶段，当时沿用 `0.2.18 / 119`；0.2.19 功能基线见[原构建记录](release-0.2.19.md)。后续 Miuix 界面统一、删除闪退修正和最终正式包见 [0.2.20 记录](release-0.2.20.md)。

## 查找与显示

- 工具条提供退出、刷新、撤销、视图树和全部轮廓开关。默认只高亮选中目标，减少视频画面上的线条。
- 面板默认显示摘要及重叠、规则、属性、隐藏入口；展开后显示滑条、前后和父子导航。工具条与面板可拖动，横屏分放左右。标注会避让工具条与面板，空间不足时隐藏重复标注。
- 同点候选先检查更靠前的兄弟分支，再检查其子节点和父节点，使用 Z 与同 Z 下的插入顺序。重复点击同一点或点击“重叠”切换候选；刷新同步更新候选数量。
- 视图树按类名、完整资源名、文字、内容描述搜索，多个关键词同时匹配，可筛选仅可见或可交互控件。结果显示原编号、尺寸、内容摘要及当前选中标识。横屏将搜索与列表分列；查询和过滤条件在本次查找会话内保留。
- 属性详情可查看、选择和复制完整类名、父类与父视图、资源 ID、px/dp 尺寸、屏幕位置、可见性、Alpha/Z、点击和焦点状态、Padding/Margin、LayoutParams 类型、字号与文字颜色。密码内容不参与查询、摘要或复制。

## 自定义规则

保存前展示信息流/窗口作用域、唯一资源 ID 或结构路径策略及实际定位路径。确认时重新解析并要求仍指向所选 View；目标移除或结构变化会要求重新选择。规则仍使用 v2 JSON 和原 `identityKey()`，不将动态文字或坐标写入隐藏规则。

原生与 Miuix 自定义视图管理页同步显示数量、作用域和定位方式，点击条目可查看或主动复制定位元数据。删除与恢复链路保留。扫描根或目标位于查找浮层内部时拒绝定位，浮层自身不参与查找和隐藏。

## 本地验证

最终运行 `:app:testDebugUnitTest`，仅筛选以下 8 个套件，共 **177 项通过，0 失败、0 错误、0 跳过**。

| 套件 | 项数 |
| --- | ---: |
| CustomViewLocatorTest | 14 |
| ViewInspectorTest | 10 |
| ViewFinderOverlayTest | 8 |
| ViewFinderPresentationTest | 8 |
| ComponentHideSettingsPageTest | 10 |
| MiuixDialogInteractionTest | 3 |
| FeedUiHideHookTest | 53 |
| FeedGestureToolsHookTest | 71 |

`ViewFinderPresentationTest` 使用 Robolectric Native Graphics 和合成宿主页面，输出 **24 张 PNG**：360×800、320×480、800×360 的明暗主题，以及 320×480 与 640×320 的 1.3 倍字体；每种配置分别渲染选中、展开导航和搜索列表。检查工具条/面板边界与遮挡、关键按钮尺寸和裁字、可见标注避让、搜索列表高度及计数与底部按钮的布局。

- 窄屏选中预览（本地证据）：`work/view-finder-verification-20261025/screenshots/picker-selected-320x480-light.png`。
- 横屏展开预览（本地证据）：`work/view-finder-verification-20261025/screenshots/picker-expanded-640x320-large-text.png`。
- 横屏搜索预览（本地证据）：`work/view-finder-verification-20261025/screenshots/picker-tree-search-640x320-large-text.png`。
- 最终命令日志、JUnit、Lint、源码与预览摘要保存在 `work/view-finder-verification-20261025/`。

`:app:lintRelease` 通过，**0 错误、57 条警告**；`tools/lint_budget.py check` 通过，53 条预算内警告，4 条依赖/工具链版本提示，**0 新增代码警告**。未更改 Lint 基线。`git diff --check` 通过。

## 边界

未运行全量单元测试、宿主 APK/DEX 合同、正式 R8 构建、签名/对齐、CI、真机 LSPosed 或真实抖音页面验收。截图仅验证本地合成页面的布局与显示，不证明实际宿主手势、键盘/分屏/窗口变化或视频状态行为。

遍历限制为 2000 节点、64 层。特殊 `getChildDrawingOrder`、Canvas 内部绘制、WebView DOM、Compose 语义节点和其他独立窗口不在本轮展开范围；命中顺序使用公开 View API，超预算时保留有限候选，不宣称还原全部自定义绘制顺序。未直接引入外部调试 SDK 或复制其实现。
