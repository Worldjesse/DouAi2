# Android 视图查看项目与 DouAi 自定义视图查找改进

检索日期：2026-10-25（Asia/Shanghai）。本记录针对宿主内的 `ViewFinderOverlay` 与自定义隐藏规则，不把 Android Studio 的调试能力视为第三方 Release 宿主的现成功能。证据来自项目自己的 README、固定提交源码、LICENSE 和 GitHub API；未安装外部 SDK、运行外部应用、修改宿主 APK 或发布远程内容。

## 优先参考

| 项目 | 已核对的能力 | 适合 DouAi 的借鉴点 | 适用边界 |
| --- | --- | --- | --- |
| [eleme/UETool](https://github.com/eleme/UETool) | `ValidViews` 切换候选、重复选择父节点、属性列表与属性复制、自定义属性 provider。 | 同一点重叠控件切换；类名、资源名、状态、尺寸、Padding、TextView 属性；复制诊断信息。 | 官方依赖示例使用 debugImplementation，Release 使用 no-op；不能据 README 宣称当前宿主兼容。 |
| [gojek/draftsman](https://github.com/gojek/draftsman) | 设备内查看 View；同范围多个 View 弹出候选；尺寸使用 px/dp/sp，显示 Margin/Padding 与文字属性。 | 无需电脑的候选列表、明确单位、可移动信息面板。 | README 说可集成到普通构建，但没有证明 LSPosed 注入及抖音适配。 |
| [YvesCheung/UInspector](https://github.com/YvesCheung/UInspector) | 层级与属性面板；按绘制顺序和 Z 轴选中；通过 provider 扩展自定义 View/LayoutParams。 | 层叠命中顺序；把属性提取和展示分开；后续按类型增加图片与自定义布局属性。 | 官方要求 debug 构建；命中实现含私有字段反射，不适合直接照搬到宿主。 |
| [didi/DoKit](https://github.com/didi/DoKit) | ViewCheck 对候选列表前后切换，显示类名、ID、尺寸、背景、间距和文字属性。 | 查找面板上的候选前后切换及属性组织。 | 整体是大型调试工具集；README 明确 Release 未经实际验证。 |

“优先”指与当前模块形态接近、源码可核对，不代表本轮做了各项目的运行性能排名。上述功能和依赖边界分别见 [UETool README](https://github.com/eleme/UETool/blob/e4967a5fae89f94561917ab95ca40c6e414a25ec/README.md)、[Draftsman README](https://github.com/gojek/draftsman/blob/b0909fb15abad702dd8b8a985f7c60e2f92bdbf0/README.md)、[UInspector README](https://github.com/YvesCheung/UInspector/blob/b52b3bf01c332fee0ad163c58028c06058340693/Readme.md)、[DoKit README](https://github.com/didi/DoKit/blob/626827cddb2feb2f3aee87a52a064b4e5ca2bed4/README.md)。

## 源码证据

- UETool [AttrsDialog.java](https://github.com/eleme/UETool/blob/e4967a5fae89f94561917ab95ca40c6e414a25ec/uetool/src/main/java/me/ele/uetool/AttrsDialog.java#L75-L96)：`notifyValidViewItemInserted` 构建候选条目，`selectView` 回调处理明确选择；[L195-L225](https://github.com/eleme/UETool/blob/e4967a5fae89f94561917ab95ca40c6e414a25ec/uetool/src/main/java/me/ele/uetool/AttrsDialog.java#L195-L225) 在属性允许复制时调用 `Util.clipText`。
- UETool [UETCore.java](https://github.com/eleme/UETool/blob/e4967a5fae89f94561917ab95ca40c6e414a25ec/uetool/src/main/java/me/ele/uetool/UETCore.java#L21-L63)：常规属性包含 Class、Id、ResName、Tag、Clickable、Focused、尺寸、Alpha、背景与四边 Padding；[TextView 分支](https://github.com/eleme/UETool/blob/e4967a5fae89f94561917ab95ca40c6e414a25ec/uetool/src/main/java/me/ele/uetool/UETCore.java#L78-L92) 展示文本、字号与颜色。
- UInspector [AndroidHitTest.kt](https://github.com/YvesCheung/UInspector/blob/b52b3bf01c332fee0ad163c58028c06058340693/api/src/main/java/com/pitaya/mobile/uinspector/hierarchy/AndroidHitTest.kt#L47-L74)：子节点按 drawing order 与 `View.z` 排序，再由上到下命中。其前段会尝试 `mFirstTouchTarget` 反射；DouAi 可以参考排序思路，优先使用公开 API 和显式候选选择。
- UInspector [UInspectorHierarchyPanel.kt](https://github.com/YvesCheung/UInspector/blob/b52b3bf01c332fee0ad163c58028c06058340693/impl/src/main/java/com/pitaya/mobile/uinspector/impl/hierarchy/UInspectorHierarchyPanel.kt#L24-L69)：单独显示类名/ID 层级，并突出目标；[自定义 View 文档](https://github.com/YvesCheung/UInspector/blob/b52b3bf01c332fee0ad163c58028c06058340693/docs/uinspector-optional-custom-view.md) 通过 `ViewPropertiesPlugin` 与 parser 扩展属性。
- DoKit [ViewCheckInfoDoKitView.java](https://github.com/didi/DoKit/blob/626827cddb2feb2f3aee87a52a064b4e5ca2bed4/Android/dokit/src/main/java/com/didichuxing/doraemonkit/kit/viewcheck/ViewCheckInfoDoKitView.java#L102-L159)：候选前后切换、按候选数量控制按钮、输出类名/ID/尺寸；[额外属性](https://github.com/didi/DoKit/blob/626827cddb2feb2f3aee87a52a064b4e5ca2bed4/Android/dokit/src/main/java/com/didichuxing/doraemonkit/kit/viewcheck/ViewCheckInfoDoKitView.java#L160-L199) 包含背景、Padding、Margin、文字颜色和字号。

## 显示效果与易用性：实际查看官方演示后的比较

本节实际查看了 UInspector 的 3 张官方图、Draftsman 的 3 张官方图、UETool 两个 GIF 各 6 个分散帧，以及 DoKit README 的平台总览图。GIF 抽帧用于检查画面变化，不等同于在设备上验证触摸或动画流畅度。下面将画面观察、源码/README 说明与工程建议分开表述。

| 项目与实际查看材料 | 画面中可以直接观察的效果 | 对易用性的判断与采用建议 |
| --- | --- | --- |
| UETool：[移动/检查演示](https://github.com/eleme/UETool/blob/e4967a5fae89f94561917ab95ca40c6e414a25ec/art/move_view.gif)、[图片属性演示](https://github.com/eleme/UETool/blob/e4967a5fae89f94561917ab95ca40c6e414a25ec/art/show_image_uri.gif) | 左上方小圆点可展开蓝色工具条；选中时红色标尺标注 dp；深色属性卡按字段名/值分列，出现复制成功提示；卡片区域仍能透出宿主内容。 | 紧凑工具条适合查找时保留视野。尺寸标尺能解释选中边界；半透明属性卡里宿主文字透出，复杂视频画面上会影响阅读。模块适合保留小工具条，将完整属性放到更实的背景上。 |
| Draftsman：[文字属性](https://github.com/gojek/draftsman/blob/b0909fb15abad702dd8b8a985f7c60e2f92bdbf0/draftsman/art/draftsman-text.png)、[同区域候选](https://github.com/gojek/draftsman/blob/b0909fb15abad702dd8b8a985f7c60e2f92bdbf0/draftsman/art/draftsman-nested-views.png)、[Padding](https://github.com/gojek/draftsman/blob/b0909fb15abad702dd8b8a985f7c60e2f92bdbf0/draftsman/art/draftsman-padding.png) | 属性卡覆盖上部目标附近，有关闭与退出按钮、dp/px 切换；同边界的 3 层 View 以嵌套矩形展示；Padding 使用浅绿色区域和尺寸标注。 | 嵌套矩形易于解释“同一个地方有多个组件”，但类名与层级标签在窄幅样图内较密。属性卡覆盖被选对象会妨碍确认；模块宜让面板可移开/折叠，并以列表展示候选类名、资源名、当前序号。 |
| UInspector：[属性图](https://github.com/YvesCheung/UInspector/blob/b52b3bf01c332fee0ad163c58028c06058340693/art/properties_preview.jpeg)、[层级图](https://github.com/YvesCheung/UInspector/blob/b52b3bf01c332fee0ad163c58028c06058340693/art/hierarchy_preview_new.jpeg)、[候选图](https://github.com/YvesCheung/UInspector/blob/b52b3bf01c332fee0ad163c58028c06058340693/art/targets_preview_new.jpeg) | 底部用 Properties/Hierarchy/Targets 页签分组；属性旁以盒图解释 Margin/Padding；Targets 用不同颜色区分父、子、兄弟候选，配相同颜色的虚线框；层级图突出类名、资源 ID、布局/Fragment 信息。 | 内容分组与颜色对应关系值得借鉴。全量彩色虚线和长类名会增加画面密度；大块半透明面板内宿主黑字透出，影响属性阅读。模块宜把常用摘要和完整详情分层，默认强调当前选中边界，全量轮廓由用户开关。 |
| DoKit：[官方总览图](https://pt-starimg.didistatic.com/static/starimg/img/WRUdiWDsw71626696344680.jpg) | 只能直接看到按常用工具、性能工具等分组的入口面板；这张图不是 Android 组件检查的详情演示。 | 本轮没有取得并实际查看组件检查专用截图，不能声称已验证其选中反馈、遮挡或窄屏效果。可确定的前后候选按钮和详情布局来自源码，不是画面观察。 |

DoKit 的 [详情布局 XML](https://github.com/didi/DoKit/blob/626827cddb2feb2f3aee87a52a064b4e5ca2bed4/Android/dokit/src/main/res/layout/dk_float_view_check_info.xml) 将前后按钮放在两侧，类名、ID、尺寸、描述纵向排列；[绘制浮层源码](https://github.com/didi/DoKit/blob/626827cddb2feb2f3aee87a52a064b4e5ca2bed4/Android/dokit/src/main/java/com/didichuxing/doraemonkit/kit/viewcheck/ViewCheckDrawDoKitView.java#L43-L70) 表明边框层不接收触摸并根据当前目标刷新。这些是源码确认，实际可读性仍需渲染/设备验收。

上述已查看图和 GIF 没有展示视图树的关键词搜索入口；这只表明演示中没展示，不能据此断言项目完全没有搜索功能。UETool 重复选择父节点、UInspector 单击检查/双击执行原点击则由 README 的操作说明确认，抽样静态帧不足以独立证明完整手势流程。

本轮可直接采用的显示与交互准则：

1. 当前选中节点使用明确的高亮边框与低透明度填充；普通节点轮廓提供开关，避免视频页布满线条。候选入口显示数量与当前序号，使“选中了谁”和“还能选谁”始终清楚。
2. 常驻面板只展示类名、资源名、简短文本、尺寸和候选状态；完整属性、定位路径、复制功能进入独立详情。信息过长时换行/滚动，不依赖把字号缩小。
3. 查找工具条和信息面板可拖动；信息面板可折叠。属性背景应足够实，避免宿主画面文字穿透；关闭、搜索、候选、详情、规则入口使用明确文字。
4. 视图树搜索和过滤固定在列表上方，结果数量与空结果提示可见；点击结果后回到高亮目标，恢复上一查询时保持位置。不要让键盘和底部操作同时压缩到无法使用。
5. 窄屏与横屏用限制高度、可滚动内容和可换行字段验证；测试真实长类名、无 ID、自定义控件、同点多候选，以及视频背景下的对比度。官方演示不是 DouAi 这些场景的验收证据。

官方媒体原件、抽帧检查图和 URL 清单仅保存在本地 `work/view-finder-research-20261025/`。其中 UETool GIF 为 101/158 帧，本轮分别查看了第 0/20/40/60/80/100 与 0/31/63/94/126/157 帧。未运行这些外部应用。

## 维护状态快照

以下是本轮直接读取 GitHub API 和仓库页面的结果。`pushed_at` 可能涉及非默认分支的推送，与默认分支最近提交日期不同；不能把仓库的 `updated_at` 当作代码更新日期。本表不使用会随时变化的 Star 数排名。

| 项目 | 默认分支固定提交 | 提交日期（UTC） | 仓库状态及 API |
| --- | --- | --- | --- |
| UETool | `e4967a5fae89f94561917ab95ca40c6e414a25ec` | 2022-11-01 | 未归档；[元数据](https://api.github.com/repos/eleme/UETool)、[默认分支提交](https://api.github.com/repos/eleme/UETool/commits?per_page=1) |
| Draftsman | `b0909fb15abad702dd8b8a985f7c60e2f92bdbf0` | 2022-04-14 | 未归档；pushed_at 为 2023-03-20；[元数据](https://api.github.com/repos/gojek/draftsman)、[提交](https://api.github.com/repos/gojek/draftsman/commits?per_page=1) |
| UInspector | `b52b3bf01c332fee0ad163c58028c06058340693` | 2025-11-27 | 未归档；默认分支 2.x；[元数据](https://api.github.com/repos/YvesCheung/UInspector)、[提交](https://api.github.com/repos/YvesCheung/UInspector/commits?per_page=1) |
| DoKit | `626827cddb2feb2f3aee87a52a064b4e5ca2bed4` | 2025-07-18 | 未归档；pushed_at 为 2026-10-20；[元数据](https://api.github.com/repos/didi/DoKit)、[提交](https://api.github.com/repos/didi/DoKit/commits?per_page=1) |

其他候选：

- [AndroidViewClient](https://github.com/dtmilano/AndroidViewClient) 是 Python/ADB 与 UI Automator 层级测试路线，适合以后对查找流程做设备验证；它不提供本模块需要的同进程 View 实例。该判断基于 [README](https://github.com/dtmilano/AndroidViewClient/blob/master/README.md) 的架构与操作方式；[API](https://api.github.com/repos/dtmilano/AndroidViewClient) 返回未归档、pushed_at 为 2026-09-21。
- [Flipper](https://github.com/facebook/flipper) 是桌面移动调试平台，仓库页面明确已于 2025-09-26 归档；不作为新增长期依赖。
- [Scalpel](https://github.com/JakeWharton/scalpel) 已于 2025-03-22 归档，README 明确弃用并指向 Android Studio Layout Inspector；只保留层级视觉设计参考。
- [Android Studio Layout Inspector](https://developer.android.com/studio/debug/layout-inspector) 的组件树、属性、重叠候选和隔离子树很适合借鉴交互，但官方工具默认连接可调试进程，不能把它直接当作 Release 宿主内的替代实现。

## 对当前模块的实施建议

本地阅读范围为 `app/src/main/java/com/douai/module/feature/ViewFinderOverlay.java` 和 `app/src/main/java/com/douai/module/config/CustomViewLocator.java`。以下是结合本地结构提出的改造建议，不是外部项目已证明的抖音兼容结论。

1. **视图树可搜索、可筛选。** 对当前有界快照做类名、完整资源名、文字、contentDescription 的查询；支持多关键词及容器/叶子/可操作控件过滤，显示命中数量。搜索应在已有快照内完成，不为每次键入重新遍历宿主。搜索便利性不应变成以动态文本保存隐藏规则。
2. **同点候选明确切换。** 点击位置保留所有可见命中节点，提供同点候选列表与切换入口；同级重叠节点参考 Z 与绘制顺序。保留父子导航和完整树浏览，避免“更深层必然是最上层”的假设。
3. **属性详情和复制。** 显示完整类名、资源名、文本/描述、屏幕坐标、px/dp 尺寸、Padding/Margin、可见性、Alpha、Z、点击状态、TextView 字号/颜色，以及结构定位路径。内容可选择或由显式按钮复制。密码或输入控件内容需要沿用模块现有诊断隐私约束，不应自动记录到日志。
4. **保存前解释定位方式。** 让用户知道规则属于当前 Feed 还是 Window，采用唯一资源名还是结构路径/命名祖先锚点；继续使用现有 `CustomViewLocator` 的歧义拒绝与严格结构验证。已有规则序列化格式不必为搜索功能变更。
5. **保留安全边界。** 模块查找浮层及子节点继续排除；根节点不作为隐藏目标；刷新后失效节点必须清空选择/候选；有界遍历达到上限时明确提示。点击、撤销、拖动、暂停播放与关闭恢复应做回归。

本轮建议先落实前四项基础查找能力；图片加载器私有属性、Compose 语义树、多窗口私有反射、取色/截图、任意属性编辑不属于必要前置条件。普通 Android View 遍历不会自动得到 Canvas 内绘制对象、WebView DOM 或每个 Compose 节点。

## 许可证与引入方式

UETool 的 [MIT LICENSE](https://github.com/eleme/UETool/blob/e4967a5fae89f94561917ab95ca40c6e414a25ec/LICENSE) 要求复制或实质性使用其代码时保留版权及许可声明。Draftsman、UInspector、DoKit 分别使用 [Apache-2.0](https://github.com/gojek/draftsman/blob/b0909fb15abad702dd8b8a985f7c60e2f92bdbf0/LICENSE)、[Apache-2.0](https://github.com/YvesCheung/UInspector/blob/b52b3bf01c332fee0ad163c58028c06058340693/LICENSE)、[Apache-2.0](https://github.com/didi/DoKit/blob/626827cddb2feb2f3aee87a52a064b4e5ca2bed4/LICENSE)；复制代码时按各文件版权、LICENSE/NOTICE 与修改标识要求处理。

适合本次的实现方式是参考可验证的交互和排序设计，在模块现有 Android API/界面体系内实现。直接装入完整调试 SDK 会把初始化、第三方依赖、私有 API 和额外窗口管理一起带入宿主，这些成本与本次的视图查找改进不相称。上述为工程取舍，最终是否包含第三方代码以实施后的 diff 为准。

本笔记仅形成调研和实施依据；新增功能的单元测试、Lint、构建及真机验收结果应由本轮实际执行记录另行给出。
