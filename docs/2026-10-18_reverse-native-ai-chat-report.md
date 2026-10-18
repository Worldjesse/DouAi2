# 抖音原生 AI 聊天界面适配记录

分析日期：2026-10-18。目标为工作区内抖音 40.6.0 APK 与已有 smali。工具为 PowerShell、Python 标准库 ZIP/二进制读取、Android SDK Build-Tools 36.0.0 `aapt2 dump xmltree`。范围和授权见 [native-ai-chat-scope.md](native-ai-chat-scope.md)，`network_profile=offline`，`flavor=null`。

## 结论与采用方案

可以直接使用宿主的原生聊天根布局、官方标题文字工厂、文字气泡及输入栏创建方法，由模块绑定联系人名、消息内容及交互。返回、头像和工具按钮局部由模块创建。根布局有三个实验分支，顶部、消息和底部区域的 ID 保持一致，但父子层级存在差别。独立使用时必须按模块输入栏的实际高度处理列表避让。

完整的 `RipsChatRoomFullScreenFragment` 可以通过 `key_session_info` 参数构造，但它会进入 Rips 依赖注入、官方会话查找与单聊组件装配。给它一个 `SessionInfo` 不会自动形成可由模块驱动的本地 AI 会话。本次选择官方布局/控件加 DouAi AI 引擎的接入方式，保留模块已有模型、API、历史和附件处理边界，不创建虚构官方 UID。

已完成源码集成、本地测试和签名 Release 构建，版本为 `0.1.77 / 78`。下文的静态合同与 Robolectric 交互结果不等于已经在真机加载成功；完整产物信息见 [本地更新记录](release-0.1.77.md)。

## 证据

反编译文件路径以下均相对于仓库根目录；`S26` 指 `douyin-40.6.0/apktool-no-res/smali_classes26`。APK 内 XML 行号来自 `aapt2 dump xmltree` 的 `line=` 字段。

| E-id | source_ref | 观察 | content_hash（SHA-256） |
|---|---|---|---|
| E-001 | `S26/X/0s7o.1.smali:22`，`ChatRoomRootUI2.smali:864` | 原生根布局工厂 `X.0s7o.LIZ()` 返回 `0x7f0d1954/1955/1956` | `45F3A0203AC77EAE068D0BFC0D45018A3E7A9B2C6FD3D56423F9E07B41AE0430`（工厂 smali） |
| E-002 | 源 APK `r/zw5.xml:2` | 默认根布局和共同槽位、275 dp 输入面板、0.5 dp 顶部分割线 | `6E0BADC6A9F6C5CB12F4499FEBAA40B740333EE60D6E875878887AD9A96243D3`（APK 内 XML 原始字节） |
| E-003 | 源 APK `r/zw6.xml:2`、`r/zw7.xml:2` | 两个实验分支保留共同槽位，`zw6` 消息列表层级不同 | `BB7579D26AEDB1A5B471B052E06104804C13363BA10073B510200C078DE9C6C0` / `D0C90E754ABAEBE5FC4BC607FF7036DAC71832882E332C126651A71CB8DC0B34` |
| E-004 | `S26/.../input/drawer/new/InputDrawerUI2.smali:1170` | `public static LJLJJLL(Context)` 直接创建官方 `ConstraintLayout` 输入栏，无需实例化 Rips UI | n/a（已有反编译文件，可按下方命令定位） |
| E-005 | `S26/.../root/RipsChatRoomFragment.smali:3677`、`:3760`、`:3954`、`:698` | Fragment 读取 Serializable SessionInfo，随后进入 Rips 绑定 | `7F8339615CB829BCDC11F474BB020374E011C016120D3C2A088AE24A06D97CE1` |
| E-006 | `S26/.../root/ChatRoomRootUI2.smali:406`、`:521`、`:554` 至 `:838` | 构造阶段查官方 Conversation 并依赖列表、输入、状态、壁纸、键盘等服务 | `5E5E66591352B6429216E2BC184B89B97C8AC9BC59A516C048D58749693E183E` |
| E-007 | `S26/X/0sIg.1.smali:187`、`:616`；`SessionInfo.smali:2678` | 单聊 SessionInfo 的 IMUser/UID 填充、Format 类型条件 | n/a（已有反编译文件，可按下方命令定位） |
| E-008 | `S26/X/0sBR.smali:63`、`ChatWallpaperHelper.smali:483` | 原生无参背景取色工厂及官方页面的 `setBackgroundColor` 使用点 | n/a（已有反编译文件，可按类和方法定位） |
| E-009 | `smali_classes3/.../title/component/ChatTitleUI.smali`、`smali_classes25/.../root/text/TextOnlyCellUI.smali`、`smali_classes38/.../vh/textd/IMMsgTextView.smali`、`smali_classes51/X/1bWb.1.smali:108`、`smali_classes51/X/1aLM.smali:7`、`S26/X/0ryb.3.smali:7` | 官方标题文字/文字气泡工厂、文本 setter、背景配置及输入背景工厂；精确合同见下表及实包合同测试 | n/a（已有反编译文件和原 APK 可复查） |

E-001、E-004 至 E-007 的复现命令以 `rg -n` 搜索对应类和方法；E-002、E-003 采用下方定向 XML 命令。二进制资源表的定点读取确认 ID 到 APK 内文件的映射，并以工厂 smali 和定向 XML 两种材料交叉核对。没有解包后保存 XML 的新增副本；哈希对应源 APK 中指定 ZIP entry 的字节。

## 原生根布局合同

`com.ss.android.ugc.aweme.im.business.chat.root.ChatRoomRootUI2.LJJLIIIIJ()` 调用 `X.0s7o.LIZ()`。后者按以下优先级选择布局。`X.1Zd0` 的 ABKey 是 `im_ds_chat_exp_pure_opt_2`（`smali_classes50/X/1Zd0.1.smali`），这些分支属于宿主实验配置，不是系统深浅色分支。

| 条件 | 资源 ID | APK 内文件 |
|---|---|---|
| `X.1Zd0.LIZ()` 为 true | `0x7f0d1955` | `r/zw6.xml` |
| 否则 `X.1Zd0.LIZIZ()` 为 true | `0x7f0d1956` | `r/zw7.xml` |
| 其他情况 | `0x7f0d1954` | `r/zw5.xml` |

注意 Windows 解包文件会为大小写相撞的混淆类增加数字后缀：这里要调用的是类 `X.0s7o`，其文件为 `0s7o.1.smali`；`0s7O.smali` 是另一个类。

| 用途 | ID、类型 | XML 合同及使用要求 |
|---|---|---|
| 根视图 | `0x7f0a203b`，`FrameLayout` | 三分支均为宽高 MATCH_PARENT |
| 主纵向区域 | `0x7f0a205c`，`LinearLayout` | vertical，宽高 MATCH_PARENT，内含顶槽与可伸展消息区 |
| 顶部槽 | `0x7f0a4246`，`FrameLayout` | 主纵向区域的直接子视图；MATCH_PARENT / WRAP_CONTENT，可加入官方标题布局 |
| 顶区占位及分割线 | `0x7f0a6dc2`，`CompatFgLinearLayout` | 内含 `ViewStub 0x7f0a2053/0x7f0a95bc` 和 0.5 dp 的 `View 0x7f0a2054`；它本身不是已经绑定的标题页 |
| 消息槽 | `0x7f0a2026`，`FrameLayout` | MATCH_PARENT / MATCH_PARENT。默认/1956 在可伸展的 `ChatDiggLayout 0x7f0a42f7` 内；1955 位于主列中 weight=1 的无 ID `FrameLayout` 内。应按 ID 和类型获取，不固定遍历父链层数 |
| 底部输入面板槽 | `0x7f0a6444`，`FrameLayout` | 位于根下独立、bottom gravity 的 `ConstraintLayout` 内；XML 为宽 0 dp、height 275 dp，并锚定同层 `0x7f0a7f97`。独立界面需改为实际输入内容高度，不沿用完整聊天键盘面板的初始高度 |
| 面板关联占位 | `0x7f0a7f97`，`View` | 初始 GONE、MATCH_PARENT / 0 dp，存在与输入槽相互引用的约束。调整槽布局时需同时处理关联约束，避免空白高度 |

默认 XML 的关键行是 `zw5.xml:29` 主列、`:36` 顶槽、`:95` 消息槽、`:302` 底部 ConstraintLayout、`:306` 输入面板。对应 `zw6` 行号为 29/36/47/313/318，`zw7` 为 29/36/95/309/313。

根布局保留大量官方业务覆盖层和 ViewStub。完整官方页面通过组件逻辑绑定、填充和调整这些区域；单独 inflate 只会创建布局。模块应只绑定本次需要的区域，并消除未使用的覆盖层对点击和尺寸的影响。

三个 XML 的根 `FrameLayout` 都没有背景属性。移除业务覆盖层后不能依靠根布局自动得到不透明背景。官方 `ChatWallpaperHelper` 使用 `X.0sBR.LIZIZ():int` 设置背景色；该无参静态方法读取宿主主题缓存，或选择 `0x7f06106d` / `0x7f061073` 与宿主自定义主题取色分支，不依赖 SessionInfo。模块采用同一个取色合同，并检查结果不透明后应用到根，避免全屏对话露出底下的消息页。

## 已采用的独立控件合同

集成桥接位于 `app/src/main/java/com/douai/module/ui/HostNativeChatViews.java`。它只创建视图和读取主题资源；标题返回按钮、联系人头像入口、附件和发送动作由模块绑定。官方根布局的三个实际槽位被重新挂入同一主纵向区域，消息区使用 weight=1、输入区域按内容包裹，从而移除原本需要官方 IM 逻辑调节的 275 dp 覆盖面板。

| 组件 | 官方合同 | 模块接入 |
|---|---|---|
| 标题文字 | `...im.business.chat.feature.title.component.ChatTitleUI.LJLIIL(int, Context):DmtTextView` | 用标题 ID `0x7f0a900f` 创建，显示联系人名；返回、头像和右侧动作仍由模块组织 |
| 完整输入栏 | `...input.drawer.new.InputDrawerUI2.LJLJJLL(Context):ConstraintLayout` | `SearchableEditText 0x7f0a7d32`、左动作槽 `0x7f0a6a27`、右动作槽 `0x7f0a9daf`；隐藏无业务监听器的内置 emoji 入口 `0x7f0a4058` |
| 输入背景 | `X.0ryb.LIZ(Context):Drawable` | 应用到输入主体 `0x7f0a0ef3`，不构造 InputDrawerUI2 的 Rips 实例 |
| 文字气泡视图 | `...msgcell.common.content.root.text.TextOnlyCellUI()` → `LJJJJL(Context,ViewGroup):View` | 获取 `...msgcell.vh.textd.IMMsgTextView 0x7f0a1dbf`，仅调用文字、颜色、宽度和字号 setter |
| 双侧气泡背景 | `X.1bWb.LIZ(boolean):X.1aLM`，其 public `LIZ:int` | false 对应 `0x7f083ab1`，true 对应 `0x7f083ab4`；按消息角色重新绑定背景，避免 ListView 复用串色 |
| 页面背景 | `X.0sBR.LIZIZ():int` | 使用宿主当前原生主题色，并检查 alpha 为 255 |

标题、文字气泡、文字控件和气泡背景类分别位于宿主 classes3、classes25、classes38、classes51；根、输入和输入背景工厂位于 classes26。桥接仅接受抖音 `40.6.0 (400601)`，启动时预检收发两种文字气泡。缺类、资源或方法、返回类型不符时明确提示适配失败并结束本次打开，不静默改用模块旧对话样式。

## 输入栏与完整 Fragment 的边界

`com.ss.android.ugc.aweme.im.business.chat.feature.input.drawer.new.InputDrawerUI2.LJLJJLL(android.content.Context)` 是 public static，返回 `androidx.constraintlayout.widget.ConstraintLayout`。方法从 Context 计算 dp、创建输入栏子视图并设置官方 ID，根输入栏 ID 为 `0x7f0a5774`。它无需构造 `InputDrawerUI2` 实例，适合作为模块聊天的官方视图来源；发送、附件等监听器由模块绑定，避免启动官方消息发送逻辑。

对照的完整 Fragment 最小参数入口如下，属于宿主进程中的反射调用合同，不是本次选用的功能路径：

1. 构造 `com.ss.android.ugc.aweme.im.business.chat.root.RipsChatRoomFullScreenFragment()`。
2. 将 `com.ss.android.ugc.aweme.im.sdk.chat.SessionInfo` 放入 `Bundle.putSerializable("key_session_info", sessionInfo)`，随后 `setArguments(bundle)`。
3. `RipsChatRoomFragment.onCreate` 从参数取出 SessionInfo；缺失或类型不符时提示并结束 Activity。
4. `LM()` 调用 `X.0rTw.LIZ(Context, SessionInfo, false, fragment, fragment.p1())`，根据 chatType 绑定 Format 或其他 RoomBinder。

单聊子类为 `X.0sIg`，无参构造；`k0(IMUser)` 设置 `fromUser`。其 `U()` 在 conversationId 为空时解析对方 UID 为正 long，再调用 `ConversationModel.Companion.LIZ(long)` 生成单聊会话 ID。`SessionInfo.LLLLII(String)` 可以设置 conversationId，`LLLILZJ(int)` 可以设置 chatType，但手工填写这些字段不等于创建官方数据库中的 Conversation。

`SessionInfo.LLILZ()` 仅检查 chatType 是否为 7。Format 路由仍进入 Format 专用业务服务，`FormatEnterChatRequestServiceLogic` 继承 `AbsEnterChatRequestServiceLogic`，并通过 conversationId 查 `ConversationListModel`。没有发现可以只设置 `chatType=7` 就获得独立本地 AI UI 的合同；本次不复用官方 Format 请求和发送业务。

`ChatRoomRootUI2` 构造依赖 SessionInfo、官方 Conversation，以及 `ListApi`、`ListLogicApi`、`PanelApi`、`TopBarFadingServiceLogic`、`InputDrawerLogic2`、`ChatRoomStateServiceLogic`、`ChatWallpaperServiceLogic`、`KeyboardServiceLogic` 和 `ChatPadServiceLogic`。这是选择独立视图复用方案的直接依据，不将“Fragment 可以 new”解释为完整本地会话已经可工作。

## Findings 与调用路径

| F-id | severity | evidence_ids | confidence | location | status | 结论 |
|---|---|---|---|---|---|---|
| F-001 | n/a_re | E-001,E-002,E-003 | high | `X.0s7o.LIZ` / 根 XML | validated（静态） | 官方根布局三分支可用统一槽 ID 适配，消息槽父链不能写死 |
| F-002 | n/a_re | E-002,E-003 | high | `inputPanel 0x7f0a6444` | validated（静态） | 275 dp 是原始完整面板占位；独立 AI 页面需要按实际输入高度避让 |
| F-003 | n/a_re | E-004,E-006 | high | `InputDrawerUI2.LJLJJLL` | validated（静态） | 官方输入栏可独立创建；完整 RootUI 则依赖 Rips 服务 |
| F-004 | n/a_re | E-005,E-006,E-007 | high | `RipsChatRoomFragment.onCreate` | validated（静态） | Serializable SessionInfo 是 Fragment 参数合同，不能替代官方会话与发送数据层 |

P-001，`path_type=callflow`：消息页 AI 联系人 → 模块聊天入口 → `X.0s7o.LIZ()` 选择官方根布局（F-001）→ 获取顶槽、消息槽和输入区域 → 独立创建官方标题/气泡/输入栏视图 → 绑定模块历史、模型和 API → 按输入实际高度同步列表可用区域（F-002）。最后的功能绑定由集成代码及后续本地检查确认。

P-002，`path_type=callflow`：官方完整 Fragment 接收 `key_session_info`（F-004）→ Rips 注入 → RoomBinder/SingleChatComposer → 官方 Conversation 查找与多组件依赖（F-003）→ 官方业务页面。该路径仅静态阅读，没有作为模块的本地会话实施。

## 复现与验证状态

在仓库根目录、PowerShell 下运行以下命令可独立核对三个原始布局。源 APK 文件名必须保持一致，aapt2 路径取本机已确认的 SDK 安装位置。

```powershell
$aapt = "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0\aapt2.exe"
$apk = '抖音-40.6.0_40609900_20260922 (400601).apk'
& $aapt dump xmltree --file 'r/zw5.xml' $apk
& $aapt dump xmltree --file 'r/zw6.xml' $apk
& $aapt dump xmltree --file 'r/zw7.xml' $apk
rg -n 'LIZ\(\)I|0x7f0d195[456]' douyin-40.6.0/apktool-no-res/smali_classes26/X/0s7o.1.smali
rg -n 'LJLJJLL\(Landroid/content/Context|0x7f0a5774' douyin-40.6.0/apktool-no-res/smali_classes26/com/ss/android/ugc/aweme/im/business/chat/feature/input/drawer/new/InputDrawerUI2.smali
```

三个定向 `xmltree` 命令均返回 exit code 0，结果与 APK 内二进制 XML 的直接结构读取一致。该操作没有访问网络或设备。系统资源属性在该混淆 APK 中常显示数字属性 ID，判断布局时保留原始数值和层级，不依赖反编译器生成的可读名称。

新增 `app/src/test/java/com/douai/module/scan/NativeChatHostContractTest.java` 使用 `DOUAI_TEST_APK` 指向原 APK，检查核心反射方法的访问级别/参数/返回类型、气泡背景字段和三个二进制根布局的槽位类型。3 项实包合同测试已通过，包括最终追加的接收方气泡染色方法 `X.0qn3.LJII(GradientDrawable)`。

`NativeAiConversationTest` 的 6 项交互测试通过，覆盖实际反射工厂接入、可见输入框、本地 AI 发送与草稿、停止/清空后的迟到回调隔离、历史图片/文档、附件失败重试、多行输入高度及 IME 避让。测试中的宿主工厂是 fixtures，不是真实抖音进程；原始 APK 合同核对与模拟交互结论分别保留。

完整回归执行 148 个套件、1361 项测试。第一次安装包检查与构建并行，读到此前 0.1.76 包，导致“原生桥接类存在”检查失败；其余检查通过。随后顺序完成新包构建，对归档的 0.1.77 APK 与原始宿主重新执行 6 项合同检查，全部通过、无跳过。按每项测试最新结果汇总：1361 项，0 失败、0 错误、0 跳过。原始两轮 XML 报告分别保存在 `artifacts/native-ai-chat-full-test-results/`、`artifacts/native-ai-chat-final-contract-results/`，汇总为 `artifacts/native-ai-chat-validation-summary.json`。

Release 构建和 Lint 通过，0 错误、49 警告；最终归档 v3 签名有效，证书与 0.1.76 实际归档一致，构建输出与最终归档 SHA-256 一致。APK 合同还确认桥接类通过 R8 保留，测试宿主类没有打入正式包。

真机尚未验证：宿主 Context/资源加载、三个实验分支的真实可见性、深浅色切换、IME 避让、附件选择返回、长列表滚动和消息标签切换。官方服务端 AI 能力与接口协议不在本报告范围内。

## 时间线

1. 2026-10-18：追踪官方 SessionInfo → Fragment → RoomBinder → RootUI 的参数与依赖合同。
2. 2026-10-18：定位三个根布局资源，通过资源表定点读取和 `aapt2 dump xmltree` 核对槽位、层级及维度。
3. 2026-10-18：确定采用官方布局/控件与模块本地会话驱动方案，向集成方交付根布局合同及输入面板避让要求。
4. 2026-10-18：完成原生界面集成、6 项交互测试、3 项原始 APK 合同测试和完整回归；顺序构建后复验最终包，记录最终测试汇总与签名/hash。
