# DouAi 结构化日志规范与排查方法

基于 2026-10-23 工作区（改造前提交 `04c8dce`，0.2.15/116）。覆盖台账见 [logging-coverage.md](logging-coverage.md)，自动化链路见 [logging-automation.md](logging-automation.md)，下载/语音/分析链路见 [logging-media.md](logging-media.md)。测试与交付状态见 [logging-validation.md](logging-validation.md)。

当前缺口修复、分层完整性边界与后续验收材料见 [2026-10-23 日志缺口修复交付报告](2026-10-23_logging-gap-repair-report.md)。该报告区分历史中间验证与本次最终测试；旧验证文档中的 APK/设备结论不追溯适用于本轮源码。

源码修复阶段曾进行单元测试，并在当时按要求停止新 APK 构建；这些记录保留原范围。后续已构建 [0.2.16 正式版](release-0.2.16.md)，该次本地应用测试与 Lint 按此前要求跳过，自动 CI 结果分别核对。尚未进行本版真机验收，历史设备日志只能证明其所属安装包的行为，不能作为下述 schema 2 修复已经在设备生效的证据。

`debug_logging` 同时控制模块本地日志和LSPosed输出：关闭时两处都使用 `log_mode=simple`，打开时两处都使用 `log_mode=detail`。这不是只影响显示页面的筛选；输出前由同一LogModePolicy投影，两个sink收到相同的模式与字段范围。

简单模式保留根操作开始/终态、功能启用状态、必要决策/资格原因、丢失摘要以及简要WARN/ERROR；省略普通stage/progress、逐键配置、配置选择、注册、常规Hook调用及子span明细。异常不会随调试开关消失，但关闭时不输出栈、cause链或参数/结果快照。要复核每一步和重试中的配置选择，须在复现前开启详细日志。模式切换不会追溯删除此前已经保存的详细记录，旧记录按原轮转/主动清空规则保留。

关闭确认与最终投递使用同一串行边界：在途发送结束后才确认关闭，排队记录交付时重新选取当前模式；启动缓冲回放也走这个边界。启动 JSON 递归保留对象/数组类型，坏记录逐条计入损失，不阻断后续回放。某异常首次明细尚未送达就被丢弃或改为 simple 时，不消耗以后再次观察该异常的详细上下文机会。

## 从一个功能还原事实

1. 找该 `feature_id` 的 `feature_state`，核对 `config_revision`、enabled、availability、hook_state。功能台账不是扫描规则表；安装成功也不是效果验证。
2. 找该功能的资格判定；详细模式另有首次Hook观察。`disabled`、`out_of_scope`、冷却、重复、前台失效等都保留原因。关闭详细日志时缺少Hook记录是模式选择，不能据此判断未触发或未安装。
3. 从 `operation_started.trace_id` 跟随根结果；详细模式可进一步跟随stage、attempt和各span。跨线程通过不可变Operation引用显式传递；重试保留trace。不要按线程、目标ID或邻近时间拼链。
4. 以根 `operation_finished` 读取结果、reason_code、evidence、last_stage 和耗时。`span_finished` 只说明子操作；Hook 返回、上传/发送接受、HTTP 200、Intent 发出均不能替代根业务结果。
5. 读导出的 `integrity_manifest`、`storage_integrity`、`startup_integrity`：丢失、清除、轮转、IO错误、尚未闭合的操作和旧会话中断决定这份证据是否足够。框架裁剪不可观察时明确为 unknown。导出包含 manifest_seq 与 best_effort_non_atomic：繁忙时清单和文件尾部不是跨业务事务快照，不能据此伪造原子一致性。

本地文件里的结构化 JSON 位于原有时间/等级/标签前缀后。每条 JSON 只有一条物理行，换行和控制字符转义。LSPosed 大记录使用 `event_part` 帧；按 event_id 分组、part_index 排序、检查 part_count 和 checksum_sha256 后才能重组，不能把片段当业务事件。

## 字段

当前写入 `schema_version="2"`。为兼容既有文本通道，普通事件标量仍多以 JSON 字符串编码（数字/布尔值亦如此）；manifest 的 `feature_states`、`observations`、`last_outcomes` 为真正 JSON 对象，内含的状态/结果也是对象，不能再按 Java Map 的显示文本解析。`open_operations`、`previous_incomplete`、`suppression_windows`、`error_ids`、`configuration_selections` 为真正数组；嵌套的 enabled/observed 等布尔字段及损失台账计数使用原生类型。字段不适用时省略或为 null。

读取器继续兼容旧 schema 1 文件与旧文本，须按记录的 schema_version 分支；旧版 manifest 的 Map 显示文本不应被当作 schema 2 的对象。结构化结果统计只认根终态，不把兼容文本条数当业务任务数。

例如，以下仅示意清单的JSON结构，不是设备采集结果：

```json
{"schema_version":"2","feature_states":{"download":{"enabled":true,"availability":"partial","hook_state":"installed","observation":{"observed":false},"last_outcome":null}},"observations":{},"last_outcomes":{}}
```

| 字段 | 含义/来源 |
| --- | --- |
| event_id / event | UUID事件标识 / 稳定事件名 |
| session_id / seq | 本次进程运行UUID / 进程内单调分配序号，异步出队顺序可能不同 |
| wall_time / monotonic_ns | 带偏移的墙上时间 / 事件产生时的单调时钟；耗时只用单调时钟 |
| module_version / build_id | APK版本 / versionCode（不是源码提交或唯一APK摘要；验收包摘要另列） |
| host_version / process_name / pid | 当前宿主及进程；尚未初始化时 unknown/0 |
| level / feature_id | 内部严重度 / 源码业务功能标识；框架外层D级不改变内部等级 |
| log_mode | 两个sink统一投影后的simple/detail；关闭时保留简要业务证据，详细字段省略 |
| config_revision / config_revision_source | 操作接纳时固定的配置摘要 / 来源；子操作默认继承开放父操作，也可显式指定实际选用的配置快照；无操作事件观察进程当前版本 |
| process_config_revision | 事件产生时的进程当前配置摘要；热更新后可能与操作的 config_revision 不同，不回写旧操作 |
| effective_config_revision / config_selection_policy / configuration_selections | 业务实际选择配置时的版本及策略；`configuration_selected` 记录选择，终态汇总已记录的选择，最多16项 |
| trace_id / span_id / parent_span_id / linked_trace_id | 逻辑操作 / 阶段或子操作 / 父span / 已结束的上游操作；重试不换trace |
| stage / attempt / duration_ms | 当前阶段 / 尝试次数 / 从本操作开始的总耗时 |
| outcome / reason_code / evidence | 终态 / 可机器区分的原因 / 最小事实摘要及证据范围 |
| last_stage / stage_ms | 结束阶段 / 阶段耗时表（位于 evidence，最多24种阶段） |
| error_id / error_context_first / exception_class / cause_chain / stack | 同一 Throwable 在有界弱身份表内复用 error_id；首次带最多3层cause、每层8帧及裁剪栈，再次记录复用ID，不反复打印整栈 |
| exception_message / exception_message_ref / error_ids | 静态安全原因摘要 / 会话内原消息引用 / 本操作及根操作已关联异常ID集合（各最多8项）；不把任意异常正文当安全文本 |
| hook_id / phase / args / result / duration_us | Hook明细：稳定标识、返回/抛出等阶段、安全类型/长度/布尔摘要、拦截器调用耗时；不表示业务耗时或业务成功 |
| context_missing | 该事件没有业务上下文；初始化和独立状态观察可以如此 |
| truncated | evidence在1024字符预算处截断；不伪装完整正文 |
| local_lost_before_enqueue | 当前事件入队前、操作期间观察到的本地丢失快照；不能替代导出清单 |
| integrity | 要求核对manifest/storage；迟到、恢复、开放操作另有明确完整性状态 |

target、URI、消息、作品、模型输入输出只记录数量、长度及 `ref_...` 引用。引用由随机 session 盐、输入长度和前4096字符摘要得到，同会话可关联；不是匿名性承诺，不用于业务去重，也不是文件完整性摘要。

配置修订单独使用 `configurationRevision(values)`：按键排序，对完整值做带类型与长度边界的 SHA-256 摘要，并加入 session 盐；字符串集合按内容排序，不裁到前4096字符。只在已提交配置发生变化时计算，不输出原配置值。`ref_...` 显示引用与 `cfg_...` 配置修订不能互换，跨会话不保证摘要相同。

简单模式普通事件字段白名单为schema_version、event_id、session_id、seq、wall_time、level、feature_id、event、trace_id、linked_trace_id、outcome、reason_code、evidence、duration_ms、last_stage、config_revision、exception_class、exception_message、error_id及error_ids，并追加log_mode。evidence最多160字符，exception_message最多128字符；error_ids最多3项，多余数写error_ids_omitted_count。stack/cause_chain/stage_ms/configuration_selections/args/result/duration_us等不输出；终态evidence里附加的stage_ms同样剥除。该投影不重新采集业务数据，也不将原先未确认结果提升为成功。

## 结果与事件字典

| outcome | 允许依据 |
| --- | --- |
| success | 在 evidence 明确的范围内确认，例如有效评论对象及业务码、本地输出流关闭并发布、当前View属性回读 |
| failed | 明确失败返回、异常、错误业务码、存储失败等；没有依据不推测服务端原因 |
| skipped | 配置、权限/范围、重复、前置条件等拒绝；实际未执行 |
| cancelled | 用户取消、配置/账号/目标/页面变化、被替换等正常取消 |
| timed_out | 本地等待期限届满；远端业务结果通常仍未知，evidence 必须说明 |
| unconfirmed | 提交/调用被接受，但最终效果无可观察证据 |

| event | 用途 |
| --- | --- |
| feature_state | enabled、availability、hook_state和配置结论；观察/最后结果在manifest分别记录 |
| operation_started | 接纳业务操作；以UUID建立根trace或同trace子span |
| stage | 实际阶段事实；不能由阶段名推出终态 |
| stage_summary | 同一操作、同阶段及同attempt的高频progress合并数与最后摘要；换阶段或终态前补汇总，不逐字节输出进度 |
| configuration_selected | 在原业务实际读取/选择配置处记录effective版本；不改变原有读配置时机，不把接纳版本冒充后续重读版本 |
| operation_finished / span_finished | 根操作唯一终态 / 子操作唯一终态 |
| exception | 尝试/阶段异常，尚不意味着根任务结束 |
| late_callback | 已确定终态后收到重复/迟到结果；保留原终态，每1/2/4/8…次汇总 |
| window_summary | 同一功能、事件及上下文30秒重复窗口计数 |
| hook_* | 安装、回调、返回、抛出等调用事实；不是业务完成 |
| hook_detail / hook_detail_suppression | 独立结构化安全明细 / 包装层抽样损失说明；明细不经过 legacy，核心相同摘要抑制另见window_summary及manifest |
| legacy_diagnostic | 旧日志迁移摘要：标签、源级别、字符数及引用，不保留正文，不认定业务结果 |
| session_started | 本次进程开始，私有journal核对由后台执行 |
| previous_session_incomplete | 上个进程journal中开放的span；链接旧session/trace及last_stage，结果未知 |
| snapshot_incomplete | 导出等待超过3秒或快照任务队列满，待写事件可能缺失 |
| integrity_manifest / storage_integrity / startup_integrity | 分层丢失、主动清除、存储与启动缓冲、开放操作、旧会话记录 |

其余功能专用事件的语义、分支及证据路径见覆盖矩阵；所有业务事件走同一入口，不用英文文本中的 success/failed 词推断结果。

## 生命周期、关联与中断

Operation自身保存UUID、阶段、尝试及终态，终态决策同步保护。wrap/wrapConsumer/call 只在本次执行绑定ThreadLocal并在finally恢复；ThreadLocal本身不跨线程。begin在仍开放的操作中创建同trace子span。上游选择/手势已结束而后续确认或保存才开始时，新业务根通过 linked_trace_id/parent_span_id 明确链接，不能把选择成功当后续发送/保存成功。宿主签名无法传参处使用有界关联表，回调对象仍保存原Operation，不按时间相近猜测并发关联。

每个Operation的 `config_revision` 在接纳时确定。后续进程热更新仅改变 `process_config_revision`；业务原本会重读配置的地方通过 `operation.configuration(effectiveRevision, policy)` 显式记录 `configuration_selected`，不会为日志而冻结或提前读取业务设置。排查重试需同时看接纳版本、选择事件与终态的 `configuration_selections`。

root只有一次 operation_finished。尝试失败写 exception/stage，重试循环退出后才结束root。迟到回调不覆盖终态。没有回调也没有可观察超时的原业务，不额外增加业务定时器或网络请求，保持开放操作并在导出中标 `incomplete_result_pending`。

后台journal按进程名保存当前session开放span（最多8个进程文件，仅存受限活动清单，每份不超过512KiB；保留期淘汰计入journal_files_evicted）；进程异常退出后，下次启动逐条记录旧session、trace和last_stage为 `process_or_logging_interrupted`，不猜是系统杀进程、崩溃或业务失败。journal也可能因强杀、磁盘满、未首次落地而缺失，因此不是持久任务账本，不保证所有中断都可恢复。持久提交的事实仍以现有 SubmissionLedger 为准，跨重启不伪造旧进程trace继续运行。

## 容量、性能与隐私

- 结构化业务队列256条，关键记录包含operation_started、stage/stage_summary、exception、根/子终态、旧会话中断及WARN/ERROR，优先保护关键记录。核心队列全为关键记录时仍可能拒绝新记录，不能承诺零损失。活动span256、窗口256、功能状态256、观察/结果128项，均有上界。
- 事件入口捕获受限安全字段和不可变上下文，后台 `DouAi-Evidence` 完成JSON序列化及墙钟格式化，再向文件与框架发送；不保留宿主View/Activity/消息对象，不调用未知对象toString。重复状态30秒内计数；同一阶段/attempt的progress在1秒窗口合并，切换阶段/attempt或结束时保留汇总。
- Hook内部仍维护首次、返回/抛出状态变化及64/128/256等幂次计数，关闭详细日志时不输出常规Hook观察，也不读取参数或详细时钟。打开明细后，包装层每5秒最多200条普通记录、每Hook 8条；耗尽普通额度后另允许每Hook 2条异常、全局20条异常。核心还按Hook/phase对相同安全摘要做30秒抑制，变化摘要可更新，耗时差异不会让同摘要逐次刷屏。`sampled/sample_policy` 描述抽样范围，不能据单条明细计算完整调用分布；限额、抑制见汇总与清单。
- 明细直接使用 `hookDetail` 输出args/result/duration_us等安全字段，正常明细为DEBUG，TRACE级也有独立映射；严重度与debug偏好分离。关闭debug仍保留异常的简单投影，省略args/result/duration_us和栈；未采集的内部时长不能被理解成零。预期的包不存在查询保持原生结果语义，不当作业务错误。
- 文件沿用宿主私有 `files/douai_debug_logs`、单文件1MiB/总2MiB轮转、128KiB内存尾部。结构化事件由证据工作线程直接写Store，不再进入第二个Store待写队列；旧文本才沿用128条/512Ki字符队列。启动缓冲最多128条/128KiB，优先保留关键记录；启动回放在后台执行。快照线程池1工作线程+4等待任务。
- 单字段1024字符，异常3层cause/8帧，阶段表24项；json控制字符转义。异常消息仅转换为超时、取消、连接关闭等静态安全说明，未知消息省略并保留引用；error_id弱身份表最多128项，回收/淘汰后不保证继续复用。完整媒体/评论/私信/AI正文不写日志，密钥、签名、Cookie、Authorization、URL查询在进入缓冲前处理；所有对外sink都只接收已处理内容。
- `LogLossLedger` 在核心、启动缓冲和Store层分别记录已知影响范围：reason、session_id、feature_id、event、trace_id、count、first_seq/last_seq。每层最多64组，超出部分只保留aggregate_overflow_count与序号包络，身份可能跨会话。min/max仅说明包络，绝不表示中间序号全丢失；台账不保留业务正文。核心看loss_details，启动及存储看各自affected_records，不能用上游lost_count=0替代文件完整性。
- debug_logging控制两个sink的简要/详细输出；错误及核心业务结果在关闭时仍有简要记录。当前没有新增“正文采集”开关，也不因调试而写正文。
- 清空先隔离上游事件generation，再清空存储；主动清除计数、仍在进行的业务和结果概况可继续显示。clear不是取消业务任务。
- 无法承诺OOM、强杀、磁盘满、框架外部裁剪时零丢失。导出清单说明本地已知损失、轮转/主动清除和未知外部损失；缺少终态或任一片段均保留不完整结论。

## 接入规则

```java
DouAiLog.Operation operation = DouAiLog.begin("feature_id", "user_or_host_trigger");
executor.execute(operation.wrap(() -> {
    operation.stage("validate", "target_present=true");
    // 原业务处理，不改变条件、请求或重试策略。
    operation.finish(DouAiLog.Outcome.UNCONFIRMED, "request_accepted", "final_effect=unobserved");
}));
```

同步明确结果应使用具体证据结束；catch中记录异常并保持原异常传播/业务降级规则。不能因为日志检测失败改变原业务返回值。新增功能须更新 FeatureLogCatalog、覆盖矩阵及针对真实状态转换的测试；禁止新增旁路 Log.*、println 或直接框架输出。框架传输adapter是唯一允许的底层例外。

独立模块应用在启动时初始化自己的私有日志。“导出”仍读取原宿主通道；“导出模块进程日志”只导出当前模块包的日志，宿主不在线时也可使用。两份日志保留不同session与进程标识，不假装跨包直接读取，也不把本地可用冒充宿主连接成功。

历史版本已经保存的明细继续受原轮转策略保留；新版写入规则不追溯改写旧文件，删除旧记录需主动清空。结构化结果统计只认根终态；旧文本保留兼容解析，其计数不等于新业务任务数。
