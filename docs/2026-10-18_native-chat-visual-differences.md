# 消息内 AI 聊天与官方单聊的视觉合同

分析日期：2026-10-18。范围为用户提供的两张截图、工作区抖音 40.6.0 原 APK 及已有 smali；分析过程离线，无真机和网络操作。授权与本地会话边界沿用 [native-ai-chat-scope.md](native-ai-chat-scope.md)。本记录仅区分截图观察、已确认的原生视图合同和模块的数据绑定，不表示启动了官方 IM 会话。`flavor=null`。

## 截图差异

已读取两张原始 1440×3200 截图。以下只描述可见差异，不从截图估算 dp、字体大小或圆角。

| 位置 | 图一：此前的 AI 界面 | 图二：官方单聊 | 本轮对应接入 |
|---|---|---|---|
| 消息行 | 收发气泡均没有逐条头像 | 可见发送消息右侧头像 | 每条消息使用原生头像工厂；自己一侧读取当前账户头像，AI 一侧使用配置头像 |
| 发送文字 | 蓝泡内黑字 | 蓝泡内白字 | 按原生收发背景配置的文字颜色字段绑定，不能统一使用输入框文字色 |
| 顶部动作 | 明显的铅笔、垃圾桶及字符返回符 | 原生返回、关注、更多入口 | 复用宿主图标/视图工厂，工具动作仍属于模块 |
| 消息上方 | 没有资料区域 | 大圆头像、名字与官方陌生人提示 | 复用大头像和名字的纯布局；模块显示本地 AI 联系人，不复制陌生人关系警告 |
| 气泡形状 | 短消息使圆角占比明显 | 图二为较长文字气泡 | 原始普通气泡半径为 12 dp；不能根据不同长度气泡的视觉比例自行认定应改成另一半径 |
| 底部 | 附件和发送的简化界面 | 原生输入入口和快捷卡片 | 由官方输入视图、图标与卡片工厂承载模块动作；不加载官方 IM 推荐数据 |

图二只出现一条发送消息，不能据此声明它验证了接收侧头像、接收字色或多行复用；这些合同来自下方静态证据。

## 每条消息使用真实原生头像和外壳

### 可直接调用的头像工厂

类 `com.ss.android.ugc.aweme.im.business.chat.msgcell.common.basic.AvatarCellUI`（classes25）有 public 无参构造器。按以下顺序调用同一个实例：

1. `LJJIIJ(Context, ViewGroup):View`，源码 `AvatarCellUI.smali:1342`。它仅创建 `com.ss.android.ugc.exview.ImAvatarImageView(Context)`，保存到该实例的 `q` 字段后返回，不要求 Message、SessionInfo 或 holder。
2. `getLayoutParams():ViewGroup.LayoutParams`，`:1655`，返回宽高一致的 `LinearLayout.LayoutParams`。

尺寸 lazy 是构造器传入的 `ALambdaS4464S0100000_30` 分支 `0x2ac`；按 packed-switch 核对，它对应 `:pswitch_34d`（同目录 lambda 文件约 `:48918`），只读取 `this.q.getContext()` 并调用 `X.0qhn.LIZJ(36, context)`。因此先创建头像、后读取参数的顺序可以独立使用；它不读取 `AbsCellUI.g` 或官方 holder。另一个 `s` lazy 才负责手势，独立界面不调用其业务初始化。

原生控件继承链为 `ImAvatarImageView` → `AvatarImageView` → `CircleImageView`（classes23）。`CircleImageView.init():602` 用 Fresco `RoundingParams.setRoundAsCircle(true)` 设置圆形；`AvatarImageView.init()` 使用占位图 `0x7f080cf3` 和 CENTER_CROP。通过普通 ImageView API 填本地 Bitmap 时，模块还应保留圆形裁剪，避免绕过 Fresco 图像层后显示方图。

### 头像容器与消息外壳

| 用途 | 可调用合同 | 原生布局参数 |
|---|---|---|
| 整行外壳 | `X.0sSQ.LIZJ(Context):FrameLayout`，classes26 `X/0sSQ.smali:480` | `RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT)`，topMargin 6 dp；`CellComposeAdapter.onCreateViewHolder` 实际调用该工厂 |
| 头像壳 | `new AvatarContainerUI().LJFF(Context,ViewGroup):View`，classes25 `.../container/AvatarContainerUI.smali:747` | 根 FrameLayout `0x7f0a1dd8`，宽度 dimen `0x7f070719=36 dp`、高 36 dp |
| 头像槽 | 头像壳内 `IMStubView 0x7f0a1dd9` | 36×36 dp，装入上面的实际头像控件 |
| 附属标记槽 | `IMStubView 0x7f0a1de0` | 17×17 dp，END/BOTTOM；代码分支的 end/bottom margin 为 −2 dp，无标记时不加载官方业务 |

头像壳的代码创建分支和 XML 分支一致：后者为 layout `0x7f0d181c` → APK `r/zo+.xml`，定向 `aapt2 dump xmltree` 已核对。aapt2 对该 XML 的负 dimension 编码显示为很大的无符号值，−2 dp 的含义通过工厂中明确的取负代码交叉确认，不能按输出的巨值设置边距。

`AvatarContainerUI.LIZJ(CellInteract)` 负责真实消息的左右约束，使用 16 dp 的选择器区域、8 dp 的头像/内容间隔和 12 dp 的外侧约束。左右依据 `CellInteract.LJLJJLL()` 决定。该方法需要官方消息交互对象，模块不构造虚假对象调用；独立页面复用创建出来的原生视图，由本地消息角色安排左右槽位。若外层使用 ListView，其父级会转换 LayoutParams，原生 row 的 6 dp topMargin 必须等效保留。

## 普通文字气泡的真实内边距、字色与尺寸

### 12/8 内边距施加在背景和内容之间

`TextOnlyCellUI.LJJJJL(Context,ViewGroup)` 创建文字视图；它的代码分支及 fallback XML `0x7f0d1b6a` → `r/z=f.xml` 都没有设置文字 padding。这个事实不能解释成官方气泡没有内容内边距。

真正的边界来自 `com.ss.android.ugc.aweme.im.business.chat.msgcell.common.bg.NewBgContentCellUI`：

- public `LJJJIL():Rect`（classes25 `NewBgContentCellUI.smali:2822`）是纯取值方法，返回按宿主 display metrics 四舍五入后的 `12 dp / 8 dp / 12 dp / 8 dp`，顺序为 left/top/right/bottom；不依赖 holder、Message 或 SessionInfo。
- `LJJII(...)` 绑定阶段在 `:379` 调用上述方法，用 ConstraintSet 将内容槽 `0x7f0a1446` 四边 inset 到背景根 `0x7f0a7d26`。内边距属于背景和内容之间的约束，不是文字控件的初始 padding。
- public `LJJIIJ(Context,ViewGroup):View`（`:1591`）可以独立创建官方背景 ConstraintLayout 和内容槽，但创建阶段尚未应用上述 inset。完整绑定还处理真实消息与皮肤业务，不宜仅为间距调用。

最小本地接入可以只调用 `new NewBgContentCellUI().LJJJIL()`，将官方 Rect 应用于本地气泡背景内部的内容布局；若创建完整原生背景壳，则用同一 Rect 约束替换后的内容槽。不要简单移除原有 12/8 空间使文字贴边，也不要同时给背景和文字各加一遍造成双倍内边距。`IMMsgTextView.LJI(int,int,int,int)` 能将 padding 同步到两种底层文字渲染器，但普通气泡合同是上述外层 inset。

### 收发使用各自的原生文字色

`AbsTextCellLogic.smali:920` 取得 `X.1bWb.LIZ(isSelf):X.1aLM`；普通非撤回文字在 `:1381` 读取其 public `LIZLLL:int` 色资源字段。`TextOnlyCellUI.LJJJJI(...):1376` 和后续绑定把解析后的值应用到 `IMMsgTextView.setTextColor`。

| 角色 | `X.1aLM.LIZLLL` | 源资源实际值 |
|---|---|---|
| 自己 | `0x7f060094` | `#FFFFFFFF` |
| 对方 / AI | `0x7f060182` | 日间 `#FF161823`；night qualifier `#E6FFFFFF` |

这些字段在 classes51 `AFLambdaS1810S0000000_54.smali:11429/:11638` 初始化，ARGB 值与 `resources.arsc` 定点读取交叉核对。模块自己的错误提示可使用错误色，普通成功文字应按消息角色读取官方配置。

| 参数 | 原生合同 | 证据 |
|---|---|---|
| 字号 | 16 sp | `TextOnlyCellUI.LJJJJL` 与 `r/z=f.xml` 的 `0x7f0407eb` |
| 额外行间距 | 3 dp | dimen `0x7f07076e` |
| 创建时最大宽度 | 244 dp | dimen `0x7f070770`；真实绑定阶段会根据屏宽、头像和侧栏重新计算，244 不是所有设备最终固定宽度 |
| 最小宽度 | 0 dp | dimen `0x7f070771` |
| 普通气泡圆角 | 12 dp | drawable `0x7f083ab1/0x7f083ab4` → `r/e+k.xml` / `r/e+n.xml`，corners 引用 dimen `0x7f07070f` |
| 边框 | 0 dp | 两个 shape 的 stroke width |
| 发送普通背景 | `#FF6B90F5` | `0x7f083ab4` solid 引用 `0x7f0611a5`；这是源 APK 默认资源值，个性皮肤可另走业务分支 |
| 接收普通背景 | 日间 `#0D161823`、夜间 `#26FFFFFF` | `0x7f083ab1` 的颜色资源 `0x7f061071`，以及 `X.0qn3.LJII(GradientDrawable)` 的原生染色合同 |

圆角和字色必须在 ListView 复用时随角色重新绑定，不能只在行首次创建时设置一次。源资源的 12 dp 圆角和模块之前同为 12 dp 的内边距是不同概念。

### 创建时 244 dp 不是最终动态宽度

classes26 `X/0sNm.1.smali:17` 的 public static `LIZ(Context,int,boolean):int` 可独立调用，不要求 Rips。传 `(context, 0, true)` 时，确定使用普通分支，返回 `min(X.0ysE.LJFF(0,0,context) - 2 * dimen0x7f070713, dimen0x7f070714)`，单位是像素。资源表的三个相关值均只存在默认 qualifier，没有其他夜间、屏宽或密度替代值：

| 资源 ID | 默认值 | 用途 |
|---|---|---|
| `0x7f070713` | 56 dp | 普通宽度预算两侧各扣除的尺寸 |
| `0x7f070714` | 300 dp | 普通分支的上限 |
| `0x7f070715` | 505 dp | `X.0sNm.LIZIZ(Context)` 的另一宽度上限 |

所以普通 helper 的预算为 `min(宿主可用宽度 - 112 dp, 300 dp)`。`TextOnlyCellUI.LJJJLIIL`（`:3906`）使用该 helper 后，在实际消息且已存在 `X.0zFz.LIZ(type,isSelf)` 缓存 Rect 时进一步扣除左右 inset；`:788` 才将最终结果交给 `IMMsgTextView.setMaxWidthPx`。`X.0zFz`（classes30 `0zFz.1.smali:66`）读取 Keva 中 `lfp/rtp/ttp/btp`，缓存缺失返回 null。因此静态 helper 的返回值不能直接宣称为“恒定含 inset”或“恒定不含 inset”的最终文字宽度；也不能把 276 dp 写成所有原生会话固定 textMax。

本地独立界面已有明确的 12+12 dp 外层 inset，可将 helper 返回值作为完整气泡的预算，再扣本地实际左右 inset 得到文字宽度，防止额外撑宽。这里的减法是独立布局与原生预算之间的换算。宿主 `X.0sMT.LJIIJ():boolean` 控制 `TextOnlyCellUI` 的另一优化宽度路径；该路径在常见无额外偏移分支使用 `min(chatWidth,500 dp)-136 dp`，也说明创建时资源 244 dp 不能作最终上限。

## 当前账户头像和资料区域

当前账户读取合同是 classes41 `AccountProxyService.userService():IAccountUserService`（`:309`）→ `IAccountUserService.getCurUser():profile.model.User`（`:186`）→ User 的 public `avatarThumb:UrlModel` 字段（`:263`）。此样本没有可调用的 `User.getAvatarThumb()`；同名 `$annotations` 方法不是 getter。

原生加载入口为 classes47 `ImFrescoHelper.LJIIIZ(AvatarImageView,UrlModel,boolean,int,String,int):void`（`:1085`），可传入 `(avatar, thumb, false, 0, "DouAi.NativeChat", 0)`。它走宿主已有图像缓存与正常图像加载；读取当前 User 本身不请求陌生人的信息。不应把自己一侧默认绑定为 AI 配置头像；只有账户或图像加载不可用时使用占位图。AI 一侧使用模块已保存的本地头像，不借此向官方 IM 构造 AI 账户。

资料区域已有独立官方创建入口：classes3 `...msgcell.vh.strangerbigcard.StrangerTopAvatarCellUI` 无参构造器 → public `LJJIIJ(Context,ViewGroup):View`（`:301`），方法只 inflate layout `0x7f0d1b61`，不绑定关系和网络业务。资源表映射到 `r/z9=.xml`，XML 核对得到：

- 根 `LinearLayout 0x7f0a5b63`：vertical，宽 MATCH_PARENT、高 WRAP_CONTENT；左右 padding 56 dp，top padding 8 dp。
- `AvatarImageView 0x7f0a10d9`：96×96 dp，水平居中。
- `DmtTextView 0x7f0a8192`：marginTop 16 dp，居中、单行、ellipsize，textColor `0x7f060182`。

模块只填入 AI 联系人名和头像。图二的陌生人沟通安全提示属于真实账户关系业务，不适合复制到本地 AI 会话。顶部导航图标及快捷卡片分别由 `LeaveChatUI.LJLIIL(Context,ImageView)`、`AutoRTLImageView(Context,AttributeSet)` 与 `X.0sWV.LIZIZ(ViewGroup):ConstraintLayout` 提供；它们的视图创建不意味着绑定了官方关注/消息业务。

## Evidence、Findings 与路径

| E-id | source_ref / repro_command | content_hash（SHA-256）或 n/a |
|---|---|---|
| E-001 | 图一 `C:/Users/95300/AppData/Local/Temp/codex-clipboard-ab02f31c-8606-4d43-b6fd-7fb94e2dea1b.jpg`；图二同目录 `codex-clipboard-ce4aa0c0-34ef-49ad-912b-c601ff52a79a.jpg`；用 view_image 阅读 | `77700C802BD1B6D52F9573B86CFD92C601EB01BFE4F2A869EBBEF0EF1F6C83F2` / `AB9E6BED6FC2240A880F9BE424F097D0FE8CE05E1DA11F06268DE58B4EB5DF92` |
| E-002 | classes25 `AvatarCellUI.smali:1342/:1655`、lambda `0x2ac`；classes23 `CircleImageView.smali:602`；`rg -n 'LJJIIJ|getLayoutParams|setRoundAsCircle'` | AvatarCellUI：`B0098763976CC2020623488252DB9777695C449D0B8A5DCDAC06F114D34AAD42` |
| E-003 | `aapt2 dump xmltree --file 'r/zo+.xml' <源APK>`；classes26 `X/0sSQ.smali:480` | n/a（原 APK 内 XML 与已有反编译，可重复读取） |
| E-004 | classes25 `NewBgContentCellUI.smali:379/:2822`；`rg -n 'LJJJIL|ConstraintSet'` | n/a（静态代码合同） |
| E-005 | classes25 `AbsTextCellLogic.smali:920/:1381`；classes51 `AFLambdaS1810S0000000_54.smali:11429/:11638`；资源表定点读取 | n/a（静态代码和二进制资源合同） |
| E-006 | `aapt2 dump xmltree --file 'r/z=f.xml' <源APK>`、`r/e+k.xml`、`r/e+n.xml`；资源表 dimen `0x7f07070f/076e/0770/0771` | n/a（原 APK 内资源） |
| E-007 | classes41 AccountProxyService / IAccountUserService / User，classes47 ImFrescoHelper 的上述签名；`rg -n 'userService|getCurUser|avatarThumb|LJIIIZ'` | n/a（静态代码合同） |
| E-008 | classes3 `StrangerTopAvatarCellUI.smali:301`；`aapt2 dump xmltree --file 'r/z9=.xml' <源APK>` | n/a（独立创建代码和 XML） |

源 APK 是仓库根目录 `抖音-40.6.0_40609900_20260922 (400601).apk`；aapt2 使用本机 `C:/Users/95300/AppData/Local/Android/Sdk/build-tools/36.0.0/aapt2.exe`。上述五个定向 XML 命令全部 exit code 0。临时截图的文件路径可能被系统清理，哈希用于标识本次用户提供的图片。

| F-id | severity | evidence_ids | confidence | location | status | 结论 |
|---|---|---|---|---|---|---|
| F-001 | n/a_re | E-001,E-002,E-003 | high | AvatarCellUI / X.0sSQ | validated（静态） | 可以为每行复用原生圆头像和官方外壳，无须伪造 IM 会话 |
| F-002 | n/a_re | E-004,E-006 | high | NewBgContentCellUI.LJJJIL | validated（静态） | 普通气泡内容 inset 为 12/8/12/8 dp，绑定阶段生效，不能因文字工厂初始无 padding 而删除 |
| F-003 | n/a_re | E-001,E-005,E-006 | high | X.1aLM / TextOnlyCellUI | validated（静态） | 发送白字、角色分色与官方半径有明确合同；截图长度差异不是另改半径的证据 |
| F-004 | n/a_re | E-007,E-002 | high | 当前 User 与 ImFrescoHelper | validated（静态） | 自己一侧应加载当前账户头像；AI 一侧使用本地配置 |
| F-005 | n/a_re | E-001,E-008 | high | StrangerTopAvatarCellUI | validated（静态） | 可独立复用 96 dp 大头像和名字的资料区域，关系提示保持官方业务边界 |

P-001，`path_type=callflow`：本地消息角色 → 原生 row shell（F-001）→ AvatarCellUI 创建头像并取原生尺寸 → 自己读取本地账户 avatarThumb / AI 使用配置头像（F-004）→ TextOnlyCellUI 创建文字 → 从背景配置解析角色字色（F-003）→ NewBgContentCellUI 提供内容 inset（F-002）→ 本地消息绑定和复用。

P-002，`path_type=callflow`：消息页 AI 联系人 → StrangerTopAvatarCellUI 纯布局工厂（F-005）→ 填本地联系人名/头像 → 显示本地 AI 资料区域。未调用官方陌生人关系、关注或聊天发送绑定。

## 验证与时间线

已集成到 `0.1.78 / 79`。实包合同测试扩至 6 项，覆盖导航、头像/尺寸、气泡 inset、资料区域、行壳、快捷卡片、账户头像字段、宿主加载与宽度方法的签名/字段检查，并检查资料区域 XML 的控件类型，最终运行全部通过。

先用视觉测试复现蓝泡暗字和标题缺更多菜单两项失败，再确认修复后的三项视觉用例通过：逐条圆头像/角色颜色、原生菜单/附件入口、长消息换行和宽度预算。已人工查看 `work/native-chat-visual-20261018/` 的 before、after、after-long 预览；其中宿主资源以测试 fixtures 模拟，不能冒充真机截图。模型配置页也完成居中头像、点击操作菜单及底部开关的交互/渲染核对。

最终全量回归为 149 个套件、1374 项，0 失败、0 错误、0 跳过；初始全量出现的测试堆内存问题已通过减少 DEX-backed 缓存、释放套件缓存及设置 1 GB 测试堆解决。最终日志 `artifacts/native-chat-visual-full-tests-verified.log`，汇总 `artifacts/native-chat-visual-validation-summary.json`。Release 构建、Lint 和最终归档 v3 签名核对通过，产物见 [0.1.78 本地更新](release-0.1.78.md)。静态合同和本地渲染通过仍不等于真机视觉验收。

1. 2026-10-18：读取用户前后两张截图，记录可见差异。
2. 2026-10-18：追踪原生头像、行壳、文字色、背景资源与尺寸。
3. 2026-10-18：找到背景绑定阶段的真实内容 inset，修正仅观察文字工厂初始 padding 可能造成的误读。
4. 2026-10-18：核对当前账户头像字段、正常图像加载入口和独立资料布局，将可调用合同交付实现方。
5. 2026-10-18：完成界面与模型配置页修正，闭环视觉回归，解决测试内存问题后通过完整验证并归档 0.1.78。
