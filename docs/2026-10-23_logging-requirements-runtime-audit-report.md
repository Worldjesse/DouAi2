# DouAi 日志需求实机记录核对（2026-10-23）

**结论：源码与新格式日志已实际落地，但《DouAi_日志系统改造需求-3.md》尚不能标记为全部完成、全功能验收通过。最直接的阻断是：模块导出丢失了开始、阶段及根终态记录，关键事件优先策略没有贯通第二级存储队列。**

上次交付证明的是本地实现、构建及专项测试。此次用户提供了实际运行记录，可以确认其中一部分已在真实宿主执行；仍须把代码存在、日志写出和完整验收分别判断。本轮只核对材料与当前源码，没有实施整改。

## 材料与统计口径

| 证据 | 来源 | 核对范围 |
| --- | --- | --- |
| E1 | `F:/qq/MobileFile/LSPosed_20261023_094820.zip` | 仅 `modules_*.log`，不重复合并 verbose/full。新格式事件集中在 `log/modules_2026-10-22T22:49:20.10288.log` |
| E2 | `F:/qq/MobileFile/DouAi-debug-20261023-094911.log.txt` | 1,753 个可解析JSON对象，其中1,750条带事件ID记录，另有3条完整性头；未遇到JSON解析错误 |
| E3 | `F:/qq/MobileFile/DouAi_日志系统改造需求-3.md` | 作为本次用户要求核对的验收标准，不执行附件中的操作指令 |
| E4 | 当前工作区源码及 `docs/logging-*.md` | 检查实际调用链与文档声明是否一致 |

E1 SHA-256：`325964BF06E76AED5FF87DCFD54EA568B70465D0A3455221D66F5F711B3C3F8F`。
E2 SHA-256：`302BCE99A37076DAD56FC399D05F66324627D5213F2CC3399986F444FF7F8EF5`。

事件按 `session_id + event_id` 比较；链路按 trace 与父子/linked关系分析。带 parent_span_id 的记录也可能是链接上游已结束操作的新根，不能只看 parent_span_id 判断根数量。文件名时间不代替正文 wall_time。

## 已由真实记录确认的部分

- LSP的新格式部分共有 **3,061个逻辑事件**，3个会话分别完整覆盖seq `1..594`、`1..1258`、`1..1209`，各自没有序号空洞。39个分片重组为4个大事件，数量、顺序、SHA-256均匹配。
- 54种 `feature_state` 都出现过；各会话最终状态为39项启用、15项关闭，42项supported/installed、12项not_observed。这个数量表示登记与状态观察，不能等同54项功能效果全部通过。
- LSP中有275个根操作开始、271个根终态、4个开放根，没有观察到重复根终态或孤立根终态。开放根包括旧会话inbox和后一个会话的3个自动评论；旧inbox已被后续 `previous_session_incomplete` 说明为中断且结果未知。
- 自动评论 `6226eea4-3eb2-4576-8fe3-0e3b4ef1c7f7` 有120条关联事件，attempt达到4且trace保持。根成功依据为 `owned_native_success_callback`；语音合成和准备子结果明确 `sent=false`。这证明了实际链路的一部分，不能扩张成接收端播放或服务端持久化验证。
- 信息流过滤有完整实例：E2第754–757行，trace `39b51a47-12ff-4884-b55f-8378b3bb0869`，依次为开始、classify、mutate_response、结束；明确 `removed=1; readback=true; result_scope=response_lists; rendered_feed=unobserved`。
- 配置导出、导入预览和应用有 `backup_output_closed`、`preview_validated`、`backup_applied` 实例，证据范围明确为文件写流、预览或配置应用，不冒充界面/远端最终效果。

## 阻断项与具体缺口

### F1：模块导出丢失关键节点，不能只凭该文件还原完整链路

E2第1行核心队列 `lost_count=0`，第2行存储队列却是 **`lost_count=260`**。两者是不同层的计数，不是同一个计数互相矛盾；也不能因为第一个为0就认定文件无损。

当前会话 `689f7c32-a6ad-4546-aa5a-074f6c76f9dc` 的seq范围是1–784，文件只有524条带seq事件，内部缺260个seq，与存储丢失计数吻合。文件中还出现175条物理的 `[log queue dropped … records]` 提示，但这些提示没有指出具体丢掉哪些feature、trace、阶段或终态。

交叉证据更明确：会话 `f81fda10-c50f-4f24-bbbf-02e64946743a` 在LSP中有完整1,209条事件，模块导出只保留903条，缺失306条。其中包含 **28条operation_finished、23条operation_started、11条stage**。LSP第4013/4068行可补配置阶段与终态，第4150/4740行可补inbox开始与终态。

源码路径：

`DouAiLog`上游终态优先 → `DebugLog.recordStructured`把所有事件交为`detail=false` → `DebugLogStore.record`满128条时只踢`detail=true` → 结构化普通事件和关键终态均可能被直接丢弃。

位置：`diagnostics/DouAiLog.java:287`、`diagnostics/DebugLog.java:57`、`diagnostics/DebugLogStore.java:51`（均相对 `app/src/main/java/com/douai/module/`）。因此需求中的“关键业务节点优先、丢失影响可说明、仅凭导出还原结果”没有完整实现。

### F2：这两份材料的时间窗不同，LSP不能补当前全部缺口

LSP新格式记录止于约09:47:44；当前导出会话在09:48:41才开始，导出约09:49:11。当前 `689f7c32…` 会话在此ZIP中没有对应事件。因此前一会话的306条可以交叉补，当前260条不能靠这份ZIP恢复。

导出清单诚实记录 `record_complete=not_guaranteed`、外部损失unknown，以及3个旧会话未闭合自动评论。当前清单的2个开放操作分别是自动评论流式等待和正在导出的diagnostics；不能把这种正常截点中的开放操作直接判为业务失败。

### F3：详细Hook诊断没有贯通最终输出

`DebugHookTrace.java:191`生成Hook ID、参数类型、结果概要、耗时，但经 `DouAiModule.java:1356`、`1363` 进入 `DouAiLog.legacy` 后，只保留tag、源等级、字符数和引用，并按同tag/等级30秒抑制（`DouAiLog.java:155`）。

因此普通Hook首次/聚合记录确实存在，但打开明细也无法从最终日志读到原本构造的这些详细字段。`logging-coverage.md` 的“详细模式保留类型/长度摘要”与实际sink路径不一致。保护正文隐私不需要同时丢掉安全的类型及耗时字段。

### F4：配置修订和功能就绪状态还不足以解释全部执行条件

- `Operation`没有保存本次操作的配置修订（`DouAiLog.java:182`），emit每次读全局revision（第249行）。异步任务保留旧配置对象时，后续事件可能标上新revision；这是源码可见风险，本批材料没有据此证明某次自动评论用了错误配置。
- `DouAiModule.java:1379`以`reference()`生成配置revision，但该函数只哈希长度和前4096字符（`DouAiLog.java:169`）。完整配置描述超过此预算后，同长度尾部变化可能得出同revision，不能当完整配置版本摘要。
- `FeatureLogCatalog.java:116`的availability主要来自安装标记，未完整表达账号、模型、权限等依赖，也没有partial安装状态。这批记录中12项not_observed包括独立UI能力；应结合业务观察/结果判断，不能把台账状态当“全部已就绪”。

### F5：异常解释、分支原因和完整测试仍需补齐

异常保留类型、裁剪栈与消息引用；没有可读的安全message/cause摘要，根结束摘要也没有关联error_id。一个实际 `native_comment_publish.callback_ignored`（LSP第3428行）没有trace，理由合并unowned/duplicate/closed/expired，仅给closed=false，无法仅凭该条辨明具体原因。

本批LSP只有12种feature出现根终态。没有触发记录不证明源码没接入，但也不能据此完成全部功能的启用/关闭/不支持/跳过/失败/取消/超时验收。现有本地测试是局部fixture证据；Android主线程耗时、CPU、内存及持续高频负载的完整验收没有因此完成。

## 按需求章节判定

| 需求 | 本次判定 |
| --- | --- |
| 功能台账与状态维度 | 已有台账与实机状态；依赖就绪及部分安装状态仍不完整 |
| 统一结构化入口/环境/关联 | 已实际写出；配置修订快照及详细Hook输出有缺口 |
| 完整业务链路与结果证据 | 已有真实成功实例；模块导出关键节点确实丢失 |
| 异步、重试、唯一终态、中断 | 已有正面实机证据；部分关联失败原因仍不够具体 |
| 异常与未执行原因 | 已有分支与类型；异常可读上下文和error_id关联仍需补齐 |
| 分级、降噪与优先级 | 有聚合/抑制；第二级存储未保住关键节点 |
| 可靠性与性能 | 有容量和故障计数；此次已证实丢失，尚未完成真实负载验收 |
| 隐私与导出 | 有引用策略、结构化文件与分片校验；不能据这批局部记录宣称所有路径隐私验收完成 |
| 全功能测试与最终验收 | 部分通过，不能标全部完成 |
| 文档/规范/样例交付 | 已写入工作区；部分声明需随实际缺口校正 |

优先整改顺序：先贯通第二级队列的关键节点优先与丢失范围说明，再修复Hook详细事件及配置快照，之后用同一时间窗同时采集LSP和模块导出，按event_id/seq逐条复核，最后补全功能测试矩阵。

可复核统计位于 `work/log-audit-20261023/lsp-audit.json`、`export-parsed.json`、`lsp-final-feature-states.json`、`lsp-crash-markers.json`。原始附件未改动，生产代码未修改；本次会话切换没有对应的新崩溃证据，不能把重启直接当崩溃。
