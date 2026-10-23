# DouAi 日志缺口修复交付（2026-10-23）

本报告保留源码修复阶段的原始交付截点。后续 0.2.16 正式构建、发布及该次跳过本地测试的范围见 [0.2.16 发布记录](release-0.2.16.md)，不追溯改写下述历史结果。

本轮交付是 **0.2.15 / 116 工作区源码修复、文档和本地测试**。按用户要求未构建新 APK、未签名发布，也未做本轮真机验收。功能登记当前为 **58 项能力、28 个配置消费者族**，这不是 58 项真实宿主效果已经验收通过。

日志修复保留原有业务条件、配置读取时机、过滤规则、返回值、超时、重试、提供方选择和异常透传策略。日志中的成功只适用于已记录的证据范围；请求接受、Hook 返回、HTTP 200、上传完成或 Intent 发出均不能单独证明最终业务成功。

交付入口：

- [全功能覆盖矩阵](logging-coverage.md)：功能、配置、入口、Hook、分支、异步和结果证据。
- [统一事件规范](logging-events.md)：schema 2、关联、终态、模式、损失与完整性。
- [自动化接入与链路样例](logging-automation.md)：评论、私信、群聊、续火花及原生回调。
- [下载、语音、配置及分析接入与样例](logging-media.md)：产物、保存、上传与发送的证据边界。
- [调试操作说明](debug-logging.md)：同一开关控制模块日志和 LSPosed 的简单/详细输出。

## 证据、发现与修复路径

[历史实机核对报告](2026-10-23_logging-requirements-runtime-audit-report.md)保留当时结论。旧模块导出曾明确报告 Store 丢失 260 条；另一同会话对照缺少 28 条根终态、23 条开始和 11 条阶段。旧材料时间窗不一致，不能恢复全部缺失，也不能作为本轮源码已在设备运行的证明。

| 原问题与证据 | 当前源码修复 | 可核对位置与本地证据 |
| --- | --- | --- |
| 结构化事件全部被标成 basic，Store 的 128 条队列无法继续保护关键事件 | `DouAi-Evidence` 直接调用结构化写入路径，消除第二个结构化待写队列；legacy 文本仍用有界队列。启动缓冲保留完整 JSON、关键优先、后台回放；过载仍允许明确拒绝 | [DebugLog](../app/src/main/java/com/douai/module/diagnostics/DebugLog.java)、[DebugLogStore](../app/src/main/java/com/douai/module/diagnostics/DebugLogStore.java)；`DebugLogStoreTest`、`LogPipelineLoadTest` |
| 上游 lost_count=0 容易被误读成文件完整，旧丢失提示没有影响范围 | 核心、启动、存储分别使用有界 `LogLossLedger`，按原因、session、feature、event、trace、数量与 seq 包络说明；读取失败明确 `memory_fallback`，IO、序列化、清空、轮转各有说明 | [LogLossLedger](../app/src/main/java/com/douai/module/diagnostics/LogLossLedger.java)、`LogLossLedgerTest`、`DebugLogStoreTest` |
| 功能状态、观察和结果混在字符串中，安装可被误当执行 | schema 2 使用真正 JSON 对象/数组；启用、能力、Hook 安装、实际观察、最后结果分别建模。未知或部分依赖保留 unknown/partial；关闭不掩盖安装失败。当前台账 58 项，与扫描规则数量分开 | [FeatureLogCatalog](../app/src/main/java/com/douai/module/diagnostics/FeatureLogCatalog.java)、[DouAiLog](../app/src/main/java/com/douai/module/diagnostics/DouAiLog.java)；`FeatureLogCatalogTest`、`LogRepairContractsTest` |
| 配置引用不能代表完整配置版本，异步热更新后不能说明任务实际选用了什么 | 全配置按键排序、类型/长度边界及完整值计算会话加盐 SHA-256；接纳配置与进程当前配置分别记录。原业务会重读配置的位置另记实际选择，未为日志冻结或提前读取配置 | `LocalOptions.diagnosticRevision()`、`DouAiLog.configurationRevision()`；`LocalOptionsDiagnosticRevisionTest`、`LogRepairContractsTest`、自动评论及评论回复 fixture |
| 重复或迟到回调可能让根结果和计数难以解释 | 同一 Operation 只允许一次终态；回调持有原操作，迟到结果追加 `late_callback`，不覆盖原终态；按 1/2/4/8…次汇总。尝试失败与根结束分离，重试保留 trace | `DouAiLog.Operation`、`AutomationTrace`；`DouAiLogTest`、自动化及语音回调测试 |
| Hook 类型、结果概要、耗时经 legacy 适配后丢成引用 | 详细 Hook 使用独立结构化字段，保留安全类型/长度/布尔摘要及 `duration_us`；异常、限额和重复抑制有对应说明，Hook 返回仍不构成业务成功 | `DebugHookTrace`、`DouAiLog.hookDetail()`；`DebugHookTraceTest`、模式与投递测试 |
| 信息流 SKIPPED 混淆无命中和空页保护，只有 removed | 由同一次原模型分类返回既有短路顺序中的首命中规则，聚合 input/matched/removed/retained/output、空页保护和规则数量；区分无命中、空输入、空页保护和分类异常透传 | [FeedAdFilterHook](../app/src/main/java/com/douai/module/feature/FeedAdFilterHook.java)、[FeedFilterPolicy](../app/src/main/java/com/douai/module/feature/FeedFilterPolicy.java)；`FeedAdFilterHookTest`、`FeedFilterPolicyTest` |
| 简单模式与详细模式、模块文件与 LSPosed 的字段范围不一致 | 两处交付同一 `LogModePolicy` 投影；投递前重新检查当前开关，避免排队明细在关闭后继续输出；历史已落盘明细保留 | [LogModePolicy](../app/src/main/java/com/douai/module/diagnostics/LogModePolicy.java)、`LogModePolicyTest`、`LogModeDeliveryTest` |

## 配置与异步任务的实际依据

`config_revision` 固定为操作接纳时的配置摘要；`process_config_revision` 是事件产生时进程当前版本。两者在热更新后可以不同，不能把当前进程版本回写成旧任务的配置。子操作默认继承仍开放父操作的配置来源，显式传入实际快照的入口则使用该快照。

自动评论 Task 和私信/群聊 ReplyTask 在原接纳位置使用对应 `LocalOptions.diagnosticRevision()`。评论回复保持原有实时读取行为：生成时记录 `configuration_selected/live_at_generation`，发送门禁重读时记录 `live_send_guard`，终态汇总已记录的配置选择。这样能解释“接纳时是什么、生成时选了什么、发送前又检查了什么”，不改变热更新或取消条件。

配置摘要不是 `ref_...` 显示引用；完整值参与摘要，长字符串相同长度的尾部变化也应改变摘要。逐键配置只记录受控元信息和引用，不输出密钥、关键词、正文或完整值。

## 安静阶段与中断记录

关闭明细后，普通 stage 可以不向模块日志或 LSPosed 输出，但 Operation 仍更新内部阶段快照和私有 journal 的 dirty 版本。Evidence worker 在队列为空且 journal 有变更时使用约 1 秒的唤醒检查，不再依赖下一条对外日志才尝试持久化。因此简单模式的安静不等同于没有内部阶段跟踪。

journal 只保存受限的开放操作、会话、trace、配置来源与最后阶段；它不保存正文，也不是持久业务任务账本。下次进程启动读取已落地快照后，以 `previous_session_incomplete/process_or_logging_interrupted` 说明旧操作结果未知。1 秒是后台检查与合并节奏，受调度和 IO 影响，不保证强杀、OOM、磁盘满或首次持久化之前的中断都能恢复；失败计入 `journal_failures`。

重复回调只追加观察；当前无回调、无原有期限的任务继续显示 `incomplete_result_pending`。本轮没有为日志增加业务超时、网络请求或重试。

## 两处输出的 OFF/ON 约定

| 全局调试日志 | 模块日志与 LSPosed 的共同内容 | 省略或边界 |
| --- | --- | --- |
| OFF，simple | 功能状态、根开始/终态、必要决策、简要异常及完整性/丢失说明 | 普通常规阶段、子操作明细、逐键配置、Hook 参数类型/结果概要/耗时和异常栈不输出；简要 evidence 有长度预算 |
| ON，detail | 简单模式内容，加阶段、尝试、子操作、配置选择、typed Hook 安全概要及受限异常栈 | 高频记录仍按变化/窗口/限额汇总；不采集评论、私信、AI正文或凭据 |

开关控制输出，而不只是页面筛选。关闭不会删除先前已经落盘的详细记录；只看新产生的记录需先清空再复现。清空不会取消业务或更换 `session_id`，后续业务回调仍会产生新记录。

投递时的模式检查、本地写入和框架输出使用同一串行边界；关闭确认等待在途发送完成，之后的排队记录按 simple 投影。启动回放使用相同边界，递归保留 JSON 对象与数组，单条坏记录计入 `startup_replay_invalid_record`，其后正常终态仍可继续回放。首次异常上下文若只以简单格式交付，或在交付前被丢弃，后续开启明细再观察同一异常仍有机会输出受限的首次详细上下文。

## 存储和完整性边界

结构化事件从证据后台线程直接写文件；legacy 队列仍有 128 条和 512Ki 字符预算，启动缓冲最多 128 条/128KiB，内存尾部 128KiB，文件两份各 1MiB。文件操作先经同进程串行监视器，再取得跨进程 `FileLock`，避免同 JVM 的重叠锁异常及跨进程互写损坏。

各层损失台账每层最多 64 组，超出明确写出聚合溢出数和身份信息被省略的事实。`first_seq/last_seq` 只是最小/最大包络，不能解释成其间每个事件都丢失。存储层 `lost_count` 表示拒绝或未持久化，内存可能仍保留；清空/轮转读取身份后再删除，所以相关项使用 requested/candidate 语义，并需同时检查 IO 失败和 `last_clear_status`。日志正文不进入损失台账。

清空通过 generation 隔离本进程上游队列、旧 Store 在途写入和启动回放。**不同宿主进程的旧待发队列是否同时清空仍未确认**：文件锁只保证文件操作串行，本轮没有实现所有进程的清空广播或共享队列 epoch，不能声称跨进程旧队列绝不会回流。

导出是 `best_effort_non_atomic` 快照，不能替代跨业务事务或服务端账本。外部 LSPosed 裁剪、强杀前未投递记录等仍可能未知；任何一层记录不完整，都不能靠另一层 `lost_count=0` 宣布全链路完整。

## 验证记录与样例口径

下面是**本次最终统一模式与安静 journal 修复之前的中间验证**，保留其原始证据，不把中间通过扩张成最新源码全量通过：

| 中间批次 | 当时结果 | 证据 |
| --- | --- | --- |
| 受影响范围回归 | 125 套件，1464 次测试执行，0 失败、0 错误、3 跳过；3 项为未配置 `DOUAI_TEST_APK` 的宿主 APK 合同测试 | [regression/summary.json](../artifacts/logging-gap-repair-20261023/regression/summary.json)、同目录 `gradle.log` 与 `junit/` |
| 定向确认 | 24 套件，330 次测试执行，0 失败、0 错误、0 跳过 | [confirmation/summary.json](../artifacts/logging-gap-repair-20261023/confirmation/summary.json)、同目录 `gradle.log` 与 `junit/` |
| 当时源码复测 | 24 套件，330 次测试执行，0 失败、0 错误、0 跳过；目录名 final-source 表示当时截点，不能代替后续修改的验证 | [final-source/summary.json](../artifacts/logging-gap-repair-20261023/final-source/summary.json)、同目录 `gradle.log` 与 `junit/` |

本轮后续验证的实际记录如下，各批次独立计数，不能相加解释成独立用例总数：

| 批次 | 实际结果 | 本地证据 |
| --- | --- | --- |
| 简单/详细模式首次定向验证 | 25 套件、340 项，0 失败、0 错误、0 跳过；位于启动数组回放与投递边界补修之前 | [simple-detail/summary.json](../artifacts/logging-gap-repair-20261023/simple-detail/summary.json) |
| 扩大范围首次回归 | 127 套件、1480 项，2 失败、0 错误、0 跳过；两项配置导入测试在 simple 下期待详细异常引用，随后仅为这些详细证据用例显式开启明细，保留原断言 | [mode-regression-first/summary.json](../artifacts/logging-gap-repair-20261023/mode-regression-first/summary.json) |
| 最终生产源码范围回归 | 128 套件、1484 项，1 失败、0 错误、0 跳过；双通道投递 4 项、模式策略 6 项、启动回放 3 项全部通过。唯一失败为下载历史上限测试在异步清理完成前检查数量 | [final-mode-regression/summary.json](../artifacts/logging-gap-repair-20261023/final-mode-regression/summary.json) |
| 测试时序修正后定向确认 | 6 套件、63 项，0 失败、0 错误、0 跳过；覆盖核心日志16、双通道4、模式6、启动回放3、下载取消/历史12、配置导入UI22 | [final-mode-confirmation/summary.json](../artifacts/logging-gap-repair-20261023/final-mode-confirmation/summary.json) |

下载用例改为有界等待历史清理完成后检查 50 项上限，保留活动任务不可丢弃的原断言，没有修改下载生产逻辑。该调整后未再次执行整轮1484项，因此不能声称最后一轮1484项全部通过。各目录保存 `gradle.log` 和原始 `junit/`，保留失败批次用于核对。

本次只运行 `:app:testDebugUnitTest` 及其编译依赖，未运行 APK 组装、签名、lint、完整项目全套测试或真机检查。既有 `app/build/outputs/apk/release/app-release.apk` 仍为5,000,492字节，SHA-256为 `027950A39DB6FE226E2F6BC754A7AFD990BF79949AEDFA575978E3BCC16FB7B9`；它不包含本轮源码修改。

本地 fixture 包括：真实入口在噪声队列饱和时保留接纳的关键三阶段 trace；Store 不可写时明确 memory fallback 与受影响 trace/seq；清空隔离、轮转、完整 JSON/UTF-8；配置热更新及实时选择；重复/迟到回调；双通道 OFF/ON 与关闭后历史明细保留；信息流无命中/空页保护/分类失败透传及单次模型分类。具体结果以相应批次保存的 JUnit 为准，不能将测试代码存在等同测试已通过。

[自动化样例](logging-automation.md#链路样例)、[媒体样例](logging-media.md#完整链路样例)与[历史 fixture JSONL](../artifacts/logging-validation-20261023/examples/async-retry-unconfirmed.jsonl)均是合成或规范化示例，不是本轮手机采样，也不是服务器回执。旧 [logging-validation.md](logging-validation.md) 的全量、APK 合同、签名及构建结论属于其当时交付，不追溯算作本轮修复的构建或设备验证。

本轮生成的 schema 2 fixture 另存于 [详细模式异步重试样例](../artifacts/logging-gap-repair-20261023/final-mode-regression/examples/async-retry-unconfirmed.jsonl)和同目录6份 `outcome-*.jsonl` 简单模式终态样例；后者均只有根开始及根终态，六种结果不会因关闭明细合并为同一状态。

## 下一次设备验收需要的材料

本轮先完成源码和本地验证；需要用户另行允许后构建、安装包含这些修改的新 APK，才能开始设备验收。保持版本号不变时尤其要记录新安装包 SHA-256，不能只靠 0.2.15 / 116 判断是否已安装本次修复。

1. 在新包宿主中开启全局调试日志，清空历史记录，选择一个功能复现，并记下操作时间、开关状态和实际可见结果。
2. 同一次复现结束后，**先导出模块日志，再导出 LSPosed 日志**，让后者覆盖前者的导出截点；保留完整文件，避免只截取附近几行。
3. 按 `session_id + event_id` 对照，较大 LSPosed 事件先完整重组并验证校验值，再用 trace、父子/linked 关系检查根开始、阶段、唯一终态和结果依据。
4. 核对模式及分层完整性清单，解释抑制、丢失、轮转、主动清空和开放操作；跨进程或外部传输未知时保留未知结论。

只有上述新包、新会话材料才能继续证明设备端修复情况。JVM/fixture 不证明真实抖音回调、接收端显示或播放、ROM 渲染、服务器持久化，也不构成全 58 项功能全部验收通过。
