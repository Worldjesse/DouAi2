# 抖音消息页 AI 联系人适配记录

2026-10-17，静态分析与本地 JVM 验证；报告类型为普通 APK 适配分析（flavor=null）。

联系人入口放在已识别的抖音消息列表上方。它保留原生列表的 ID、直接父容器和原生兄弟视图，通过独立的联系人区域打开 DouAi 对话窗。每个模型的联系人开关、显示名、头像独立于自动化功能所选模型。当前证据确认了源码合同和本地测试结果，未建立真机可见性或接口服务端识别能力的结论。

## 范围

授权及边界见 [ai-message-contacts-scope.md](ai-message-contacts-scope.md)。只使用工作区已有的抖音 40.6.0 静态解包结果；没有远程请求、原生私信发送或真机操作。相关 Android 依赖为宿主的 Fragment、RecyclerView、ConstraintLayout；模块通过反射匹配宿主类型，避免链接模块与宿主两套 AndroidX 类。

## Evidence

以下 `source_ref` 均相对于仓库。哈希用于记录本次读取的既有解包文件；解包内容未被修改。

| E-id | source_ref | 观察 | content_hash (SHA-256) |
| --- | --- | --- | --- |
| E-001 | `douyin-40.6.0/apktool-no-res/smali_classes26/com/ss/android/ugc/aweme/im/sdk/module/session/rips/MessagesFragment5.smali:826` | `onCreateView` 返回消息 Fragment 的独立 View；同目录已有 `MessagesFragment3` 分支 | `B87FC8DD5D8FF0F11437AD8E6B4CD3148B96185F4D2FCB87F4135C23CC118C04` |
| E-002 | `douyin-40.6.0/apktool-no-res/smali_classes26/com/ss/android/ugc/aweme/im/sdk/module/session/rips/list/SessionListUI.smali:1105` | 优化布局创建 ConstraintLayout；行 1201–1255 创建 SessionListCheckDrawRV，ID 为 `0x7f0aa848`，宽高均为 MATCH_PARENT；随后创建状态覆盖视图 | `31AE0CCC787757B6E2D027CED1A297FACD27574201BBDEB0619E26B9F945793E` |
| E-003 | `douyin-40.6.0/apktool-no-res/smali_classes26/com/ss/android/ugc/aweme/im/sdk/module/session/rips/list/MessageListRvLinearLayoutManager.smali:1` | 原生消息列表布局管理器是 final 类，继承 RecyclerView 的 LinearLayoutManager；SessionListUI:1697 获取该类型 | `4C1035ABDE8A632E55BEB491DE7D40D6BA5B83330F4CEE8DDB9504330C4ECF0E` |
| E-004 | `app/build/test-results/testDebugUnitTest/TEST-com.douai.module.feature.AiMessageEntryHookTest.xml` | 7 个测试，0 failure，0 error；JVM/Robolectric 加实际 ConstraintLayout 2.2.1 求解器，不是真机记录 | n/a（构建结果会被后续测试重写） |

从仓库根目录可复查静态合同：

```powershell
rg -n 'onCreateView|onViewCreated' douyin-40.6.0/apktool-no-res/smali_classes26/com/ss/android/ugc/aweme/im/sdk/module/session/rips/MessagesFragment5.smali
rg -n 'ConstraintLayout|SessionListCheckDrawRV|0x7f0aa848|LJLJJI\(' douyin-40.6.0/apktool-no-res/smali_classes26/com/ss/android/ugc/aweme/im/sdk/module/session/rips/list/SessionListUI.smali
rg -n '^.class|^.super' douyin-40.6.0/apktool-no-res/smali_classes26/com/ss/android/ugc/aweme/im/sdk/module/session/rips/list/MessageListRvLinearLayoutManager.smali
```

## Findings

| F-id | severity | evidence_ids | confidence | location | status | 结论 |
| --- | --- | --- | --- | --- | --- | --- |
| F-001 | n/a_re | E-001,E-003 | high | `AiMessageEntryHook.uniqueMessageList` | validated（静态） | 限定 MessagesFragment3/5，再匹配原生会话列表管理器；隐藏 Fragment、隐藏父 Fragment 和多目标布局不插入入口 |
| F-002 | n/a_re | E-002,E-004 | high | `AiMessageEntryHook.InlineContacts` | validated（本地） | 以兄弟联系人区域加原生列表顶部边距预留空间；不更改列表 ID/类型/直接父容器，原生兄弟约束仍引用原始列表 |
| F-003 | n/a_re | E-004 | high | `AiMessageEntryHookTest` | validated（本地） | 禁用时移除联系人区域并恢复模块仍拥有的参数；宿主重挂列表或自行换参数时安全清理；选择附件导致的 Activity pause 保留对话，destroy 关闭对话 |

## Path

P-001，`path_type=callflow`：Activity resumed → 延迟合并全局布局更新 → 读取并缓存序列化模型配置 → 查找可见消息 Fragment（F-001）→ 唯一原生会话列表 → 在受支持的 ConstraintLayout/FrameLayout 加入联系人区域（F-002）→ 用户点击时重新读取同 ID 且仍启用的模型 → `AiConversationDialog.show`。

P-002，`path_type=callflow`：Activity paused/配置关闭/宿主替换列表 → 移除模块联系人区域 → 仅恢复仍属于模块的 LayoutParams → 保留宿主新参数；Activity destroyed → 关闭该 Activity 对应的对话（F-003）。

## 验证与限制

执行以下命令完成 focused 验证：

```powershell
$env:ANDROID_HOME = 'C:\Users\95300\AppData\Local\Android\Sdk'
.\gradlew.bat :app:testDebugUnitTest --tests com.douai.module.feature.AiMessageEntryHookTest --console=plain
```

7 项测试通过，覆盖消息页定位、歧义拒绝、隐藏祖先、独立开关与改名、点击时配置复核、原生约束/ID/父容器保持、重挂清理、附件选择期间生命周期和销毁清理。实际 ConstraintLayout 2.2.1 求解器复现了静态证据中的 MATCH_PARENT 列表合同，并验证原生兄弟约束指向列表 ID 时仍有效。

布局参数通过原生 `(int,int)` 构造器新建，只复制公开的基本类型/String 字段和相对边距，保留每个参数独立的 ConstraintWidget。测试直接比较原始列表、调整后列表、联系人区域三个 Widget 的对象标识，并实测顶部区域下沿等于列表上沿、列表下沿仍到达容器底部。100 px 高的紧凑容器中，联系人区域最多占可用高度一半，原生列表仍保留至少一半；视图尺寸变化会重新计算高度。真实 HostSettingsStore 测试还验证了只保存联系人设置、没有额外布局事件或 Activity resume 时，偏好监听器会更新改名与禁用结果。

真机仍需确认各消息页分支的视图可见性、刷新/切换标签时的空间分配、头像与系统主题切换。联系人区域最多显示三个完整行，并受可用高度一半的限制，其他联系人可内部滚动访问，原生会话列表继续保留。未识别的宿主 Fragment、布局管理器或父容器保持原页面，等待后续适配；本记录不覆盖 API 协议和媒体识别服务端能力。

## Timeline

1. 2026-10-17：复用本地静态解包结果定位消息 Fragment、会话列表与原生父容器（E-001 至 E-003）。
2. 2026-10-17：实现联系人兄弟区域、模型点击时复核、配置缓存及生命周期清理（F-001 至 F-003）。
3. 2026-10-17：focused Robolectric 7 项测试通过，含实际约束求解器测量及 HostSettingsStore 偏好监听（E-004）；完整构建与其他功能测试由主任务统一执行。
