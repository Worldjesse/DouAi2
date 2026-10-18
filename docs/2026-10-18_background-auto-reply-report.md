# 未进入聊天页时自动回复的日志分析

分析日期：2026-10-18。用户反馈“必须进入对应聊天界面才回复”。本文先保留用户日志与修复前源码快照的诊断，再追加主任务当前实现、原始宿主 APK 静态证据和本地回归结果。诊断及本文更新均离线；文档子任务没有运行 Gradle、连接设备或改动生产代码。

原始 ZIP、宿主 APK、`work/` 与 `artifacts/` 是本地证据或验证产物，不随公共仓库分发。下方路径不代表公开下载链接。

## 结论

日志能确认当前进程成功注册了原生 IM 接收/发送合同，出现两次原生 SDK 成功发送确认，并有一次自动回复准备/生成链路的 `SocketTimeoutException`。因此不能把问题概括为“自动回复钩子完全没装上”或“始终发送失败”。

但现有日志没有记录入站钩子何时触发、消息通过了哪些条件、会话缓存是否存在，无法证明用户收到的那条消息是否只有打开聊天页后才进入模块。源码也没有“当前聊天必须等于目标聊天”的统一条件。

修复前源码快照可确认的结构性盲区是：普通私聊/群聊只监听 `AbsDBProxy.insert("msg",...)`，未覆盖完整 SDK 入站通知；而 SDK/账户状态、消息字段和会话缓存条件都可静默放弃。尤其会话缓存为空时，消息 identity 已经进入去重集合，后续没有等待缓存就绪重试。它们不能仅凭该日志判定为本次唯一根因，但已被两项 RED 回归复现为本地入口缺口。本轮加入 SDK 全局回执和异步本地会话读取后，52 项专项检查全部通过、零跳过，详见文末；新版真机后台行为仍未验收。

## 范围、版本与证据保全

- `auth`：用户提供日志并请求诊断后台自动回复触发问题；允许离线读日志和代码。
- `in_scope`：`F:/qq/MobileFile/LSPosed_20261018_120223.zip`、保存的修复前源码快照、本轮 `NativeMessagingHooks` / `IncomingMessageSnapshot`、原始宿主 APK 合同与本地专项结果。
- `network_profile`：offline；日志仅展示时间、事件、类名及错误类别，不摘录聊天、UID、提示词、密钥或请求体。
- `artifact_root`：`work/auto-reply-background-20261018/`，已确认被 Git 忽略。源 ZIP 保持不变；只解出日志，保存 entry 清单、冒号改为连字符的 Windows 文件名映射、脱敏事件摘要及源码快照。
- `flavor`：null，日志和功能路径诊断。

ZIP 共 182 个 entry。当前模块日志为 `log/modules_2026-10-18T11:42:50.002053.log`，2203 行；另有对应 verbose、旧轮转日志、系统日志及历史 tombstone/ANR。`full.log` 共 124329 行，包含早期系统行，不能将其第一行当作本次抖音进程启动时间。

该包没有可以确认 DouAi `versionName/versionCode` 的目标模块记录。模块注册与宿主扫描成功只能证明这些运行时合同已加载，不能据此认定设备已经安装当前工作区待交付的 0.1.79。以下“修复前源码”部分以保存的旧快照为准；文末单独记录本轮实现，不将两者混作设备上 APK 的版本证明。

| 材料 | SHA-256 |
|---|---|
| 源 ZIP | `2C37C1FB9460ED86B192FE0F637F93ACE84D9FB5A59212F63367246D0B64C08A` |
| `full.log` | `D04299FE18D396CDAE67DD2A4877652A34231F728B9D9AC3D19C10D32C3C439F` |
| 当前模块日志 | `043158DE173AE9B07F940DD761F7AF36545A8D9F4696D211C4CE5B27B9C88727` |
| `source-snapshot/NativeMessagingHooks.java` | `0E4448B93E6BDC79FB7AA83DCC21FD78379B05C68BDC368A412E0B5470B40F09` |
| `source-snapshot/ReplyPolicy.java` | `99917461831426A435BA151F8AD30D8066944136894D730842AE476B2C5273CB` |
| `source-snapshot/AutomationDispatcher.java` | `C1DC897E1257DF100BD6A062991AF7B168BCF0DA98B1B98B5484E8222680B57F` |

## 日志时间线

时间直接使用设备日志记录。以下行号对应上述当前模块日志；同一事件在 verbose 中有重复，未按重复条目计数。

| 时间 | 行号 | 已确认事件 |
|---|---|---|
| 11:56:32.549 | 681 | `Host scan complete: rules=204, unique=204, ambiguous=0, notFound=0, cacheHit=true` |
| 11:56:32.651 | 707 | `Native IM receive and sender contracts registered` |
| 11:58:57.739 | 1106 | `Automatic reply outcome: sent` |
| 12:01:02.387 | 1777 | `Automatic reply failed: SocketTimeoutException` |
| 12:01:30.702 | 1780 | 再次 `Automatic reply outcome: sent` |

当前目标进程 PID 为 29072。`sent` 在代码中来自原生发送回调成功，不等于对方已读。SocketTimeout 日志没有附调用阶段或堆栈；自动回复任务涵盖媒体读取、AI 请求和可选语音准备，不能将这一条直接定为 IM 入站失败或具体模型服务故障。

系统窗口日志还记录了聊天 Fragment 的存在，例如 `full.log:63576` 的 11:59:56.327 和 `:100763` 的 12:00:56.759 出现 `RipsChatRoomFullScreenFragment` 调用链。它们只能确认这些时刻有聊天界面相关窗口行为，不含消息接收时间或目标会话身份，不能据此给两次 `sent` 逐条绑定“进入了对应聊天”。已将仅含类名/时间的记录保存为 `chat-activity-timeline.json`。

## 修复前源码快照的触发路径与静默条件

本节及紧随其后的诊断 Findings 描述哈希已记录的旧快照；“只监听 insert”和“无冷会话查询”已由文末当前实现处理。

1. `app/src/main/assets/douyin-host-rules.tsv:110` 将 `adv_im_db_insert` 定位为 `com.bytedance.im.core.internal.db.base.AbsDBProxy.insert(String,String,ContentValues):long`。
2. 快照 `NativeMessagingHooks.java:327` 安装这一方法的钩子；`:329` 只接收表名等于 `msg` 且第三参数为 ContentValues 的调用，原插入返回非负后才调用 `onIncoming`。
3. `captureSdk` 从 receiver 的 `imSdkContext` 字段捕获 SDK；另在 `adv_im_conversations` 调用时记录 SDK。没有 SDK 时 `ownUid()` 返回 0。
4. `onIncoming:371` 检查启用状态、账户、关注名单状态；`ReplyPolicy.incoming` 还要求消息发送者/会话/server ID/type/created_time 等字段、时间起点和接收范围均有效。
5. `:397` 将 identity 写入 `seen`，再延迟调度任务。任务在 `:410` 要求内存会话查询 `conversationExists` 成功，才继续生成。
6. 准备后经 `AutomationDispatcher` 处理预览/发送，再由原生 IM SDK 的回调报告结果。

这里没有专门等待“打开目标聊天”的条件：`onIncoming`、`ReplyTask.current`、`replyStillAllowed` 与 `sendText` 都不比较当前页面的会话 ID。`AutomationDispatcher.dispatch/tick` 也没有要求用户位于目标聊天；Activity 主要用于悬浮提示和详情。暂停正在显示的未发送详情会取消那一项，属于单独的详情交互规则，不能解释成统一的目标聊天门槛。

### 可确认的去重与会话缓存顺序

`seen.put(identity,true)` 发生在调度前。如果稍后的 `conversationExists` 因会话尚未进入 SDK 缓存而返回 false，任务直接退出，identity 不会移除，也没有就绪补偿。后续同 identity 再经过插入钩子会被去重，直至进程重启或 512 项 LRU 淘汰。这个代码顺序可以确认；本包没有会话缓存命中/缺失日志，不能确认用户这一次命中了它。

### 未被日志覆盖的关键边界

当前仅观察数据库某种插入，不代表已经覆盖宿主所有入站、批量写入、更新或内存通知路径。也没有在 SDK 就绪后查询未处理新消息的补扫入口。如果宿主在非聊天页接收消息时走另一写入/通知路径，而打开聊天后才走该插入，表现会与用户描述一致。这是基于入口覆盖的推断，尚需实际入站回调或存储调用证据验证。

同样，若 SDK 账户尚未可得、字段不完整、范围未命中或会话缓存为空，当前多数路径只 return。现有“规则唯一/钩子已注册”日志不足以排除这些情况，也不足以证明本次用户消息已被策略接受。

## 诊断阶段 Evidence → Findings → Path

| E-id | source_ref / 复核方式 | 内容 |
|---|---|---|
| E-001 | 源 ZIP；`archive-index.json` / `archive-entry-map.json` | 182 个 entry、限定落盘目录、日志哈希与文件名映射 |
| E-002 | 当前模块日志 `:681/:707/:1106/:1777/:1780`；`auto-reply-events.json` | 合同注册、两次成功确认、一次 SocketTimeout |
| E-003 | `full.log:63576/:100763`；`chat-activity-timeline.json` | 聊天 Fragment 的窗口事件，不包含入站关联 ID |
| E-004 | NativeMessagingHooks 快照 `:327/:329/:371/:397/:410/:611`；规则 TSV `:110` | 单一数据库入口、静默条件、先去重后查缓存 |
| E-005 | ReplyPolicy 快照 `incoming`；Dispatcher 快照 `dispatch/tick/onActivityPaused` | 策略条件与 Activity/目标聊天的真实边界 |

| F-id | severity | evidence_ids | confidence | location | status | 结论 |
|---|---|---|---|---|---|---|
| F-001 | n/a_re | E-002,E-004 | high | 原生接收/发送合同 | validated（日志+静态） | 钩子已注册，且两次发送收到 SDK 成功确认 |
| F-002 | n/a_re | E-002,E-003 | high | 本次故障证据 | validated（证据边界） | 有一次超时，但缺入站触发和阶段日志，不能证明仅 UI 入站或唯一根因 |
| F-003 | n/a_re | E-004,E-005 | high | 目标聊天门槛 | validated（静态） | 没有统一要求打开对应聊天的代码判断 |
| F-004 | n/a_re | E-004 | high | seen / conversationExists | validated（静态） | 会话缓存失败可在 identity 已去重后静默丢失，缺就绪重试；本次命中未证实 |
| F-005 | n/a_re | E-003,E-004 | medium | 入站覆盖 | candidate | 宿主非聊天页入站可能没有经过当前唯一 insert 钩子，需要运行时或 SDK 通知证据 |

P-001，`path_type=callflow`：宿主数据库 `msg` 插入 → 捕获 SDK/账户 → ReplyPolicy → 去重 → 会话缓存查询 → 模型/固定内容准备 → Dispatcher → 原生 SDK 回调。E-002 覆盖注册和最终结果；中间入站与拒绝原因缺日志，不能将窗口事件补作缺失证据。

## 诊断建议与真机验证边界

诊断阶段建议补充进程级入站通知、冷会话异步查询和不含正文/账号的原因码，已纳入本轮实现。新增日志将区分全局/数据库来源、账户不可用或变化、策略、额度、重复、队列满，以及冷会话开始/完成，使后续能区分“没收到事件”和“收到后被条件放弃”。

在同一进程中分别验证“停留推荐页收到新消息”“停留另一聊天收到新消息”“整应用后台收到新消息”，再进入对应聊天对照。每组记录收信、入口回调、策略结果、发送时间；不混用历史补载与真正新收消息。本次没有现场触发或设备权限，因此没有执行这些对照，也没有验证修复结果。

本记录只分析本次新增自动回复问题。附件与自动回复修复合并到同一轮最终重建，本文未创建额外版本或单独发布。

最终本地交付为 `0.1.79 / 80`，已合并附件与相册返回流程修正后重新构建、签名并归档。核心全量检查点 151 套件 / 1414 项通过；最后原生相册生命周期修正另有 35 项相关回归和 2 项有效差分回归，最终包与相关功能再执行 7 套件 / 115 项，全部零失败、零错误、零跳过。最终哈希、验证顺序与工件路径见 [release-0.1.79.md](release-0.1.79.md)。这些本地结果仍不替代推荐页、其他聊天页、应用后台的真实设备收信/发送验证。

## 本轮实现与原始 APK 合同

### 全局回执与 SDK 身份

当前 `NativeMessagingHooks.installReceive` 在保留数据库入口的同时，新增 `adv_im_global_received`：`X.1Qjr.onGetMessage(List,int,ReceiveMsgExtra):void`。钩子先保存入参列表，在宿主原方法正常返回后通过 `IncomingMessageSnapshot` 复制 Message 的发送者、server ID、时间、类型、会话、正文和扩展，复用原有 ReplyPolicy。删除消息及不符合原有策略的条目会跳过，没有构造虚假会话或依赖聊天 Activity。

原始抖音 40.6.0 APK 的 SDK 证据如下，均位于 `douyin-40.6.0/apktool-no-res/smali_classes46/`：

| 合同 | 实际类 / 方法 / 行号 | 说明 |
|---|---|---|
| 统一 SDK 回执入口 | `X/1Qjr.1.smali:5134`，`onGetMessage(List,int,ReceiveMsgExtra)` | 实现 `IObserverUtils` 的 SDK 通知工具，不是聊天 Fragment 回调 |
| SDK 上下文 | `X.1Qjr → X.1QjB → X.1QkP`；`X/1QkP.smali:7` 的 public final `imSdkContext:IMSdkContext` | 当前钩子可从接收对象继承字段捕获 SDK，而不等进入聊天后才捕获 |
| 在线通知来源 | `X/1QdT.smali:484` 的 `L2`，`:611` 调用统一入口 | source 0 的在线消息链路 |
| 拉取消息来源 | `X/1Qh6.smali:4708` 的 `N3`，`:4913` 调用统一入口 | source 4 的拉取消息链路，同样到达该入口 |

这些证据证明在线和拉取链路可以到达同一个 SDK 入口，不证明全部设备消息、全部历史同步类型均已真机验证。新的全局及数据库输入继续用同一 identity 去重，避免两条路径对同一条消息重复发送。

### 冷会话使用 SDK 异步本地读取

原始 APK 的同步 `ConversationListModel.getConversation(String)`（`:20536`）转到 `X.1QW8.getConversation`（`X/1QW8.smali:199`），最终读取 `IMMemoryCache.get`（`:239`）。同步返回 null 仅表示当前缓存未命中，不能直接说明本地数据库不存在会话。

`ConversationListModel.getConversationAsync(String,IRequestListener)`（`:20997`）进入 `LJJZZI`（`:5432`）；该方法先试同步缓存，再用 `DbType.CONV` / `DbAction.READ` 调用 `executeDB`（`:5661/:5665/:5694`）。`Y/ATRunnableS33S1110000_49.smali:936` 调用本地 `IIMConversationDaoDelegate.getConversation`，配套 callback 将结果交给 `onSuccess` 并通过 `ConversationListModel.b3` 回填缓存。该合同是 SDK 调度的本地存储读取，不是为了生成一个会话而发起网络创建请求。

当前实现只在回复 worker 上等待该异步结果，最多 10 秒，不阻塞 SDK 回执或 UI 线程。任务暂存解析出的 Conversation，后续缓存仍未命中时可以使用这个任务内 fallback；会话 ID 和单聊/群聊类型必须匹配。它没有建立第二份永久会话缓存。

每个 ReplyTask 固定 SDK 对象、账户和 generation，等待后、准备过程中和发送前继续校验。迟到 callback 仅完成局部 holder，不能发送或复活取消任务。会话读取失败及队列满会释放该次 `seen` 占位，后续相同消息回执可以重试；其他已有账户、范围、时间、额度和去重策略继续生效。

## 本地 RED → GREEN 验证

以下结果来自主任务已保存的测试产物；本次文档更新没有重跑 Gradle。

1. **RED：2 项全部失败，零跳过。** 旧实现运行 `globalFreshMessageRepliesWithoutDatabaseInsertionOrAChatActivity` 与 `coldDatabaseConversationResolvesAsynchronouslyWithoutOpeningAChat`，分别缺少全局入口和冷会话本地读取能力。证据为 `artifacts/native-background-reply-red.log` 与 `artifacts/native-background-reply-red-junit.xml`；后者记录 `tests=2, failures=2, errors=0, skipped=0`。
2. **GREEN：52/52 通过，零失败、零错误、零跳过。** `artifacts/background-auto-reply-focused.log` 记录 BUILD SUCCESSFUL；对应 JUnit XML 为 NativeAutoReplyIntegrationTest 38、NativeMessagingHooksTest 5、BackgroundMessageHostContractTest 6、HostAdaptationTest 3，总计 52。

专项覆盖无数据库插入/无聊天 Activity 的新鲜全局回执、冷会话异步读取、全局与数据库双入口去重、冷查询失败后的同 server ID 重试、返回错误会话拒绝、历史/本人/切号不发送，以及冷会话语音发送。6 项原始宿主 APK 合同检查验证了全局入口及上下文、在线/拉取调用链、应用全局 observer 注册、Message getter、同步内存查询和异步本地数据库读取；本次没有因 `DOUAI_TEST_APK` 缺失而跳过。

| 新证据 / Finding | 引用 | 结论与状态 |
|---|---|---|
| E-006 | 原始 APK 上述 smali；`BackgroundMessageHostContractTest` 6 项 | 全局入口和冷会话查询合同，validated（静态+实包合同） |
| E-007 | RED 日志/JUnit，GREEN 日志和 4 套件 XML | 旧路径缺口可由本地用例复现，当前 52 项通过 |
| F-006，severity=n/a_re，confidence=high，evidence_ids=E-006,E-007，location=NativeMessagingHooks / IncomingMessageSnapshot | 当前实现 | 全局回执与任务内冷会话 fallback 已实现并通过本地验证；状态 validated（本地） |

P-002，`path_type=callflow`：SDK 统一回执 / 数据库入口 → 捕获 SDK 并复制策略快照 → 账户/策略/额度与去重 → worker 同步试缓存、缺失则异步读本地数据库 → 匹配任务内会话对象 → 模型或固定回复准备 → 发送前校验 → 原生 SDK 回调。此路径由 E-006/E-007 支持；不能将测试 fixtures 的成功写成设备新版本已经验收。

本轮未进行新版真机后台收信、进程保活或实际模型网络回归。用户旧日志中的 `SocketTimeoutException` 仍是独立的超时证据，全局回执和冷会话修复不能证明它已消失。最终 APK、全量回归、签名和哈希由主任务在统一构建后写入发布记录，本文不提前填报。
