# 下载、语音、配置与本地分析的日志接入

本文件记录这些业务的实际证据边界；全模块功能矩阵及统一事件规范见同目录的日志改造交付文档。`SUCCESS` 的作用域由 `feature_id` 决定：解析成功、选择完成、音频准备完成均不代表媒体保存或宿主发送成功。

## 扫描结果与接入矩阵

| 功能 / 配置 | 入口与关键分支 | 异步关联 | 最终证据 / 保留的未知 |
| --- | --- | --- | --- |
| 原生下载增强；`downloadEnabled`、去水印、`download_custom_name`、`remove_download_limits`、下载目录 | `DownloadActionHook.beforeNativeClick`；禁用、原生默认设置、第三方独占、无来源、旧版存储权限不足、指定目录无来源 | 原有 Hook 路由事件；实际保存由独立 Task 持有 Operation | 原生透传明确 `host_result=unconfirmed`；模块队列不作为保存成功 |
| 下载长按菜单与作品源数据入口 | `showMenu` / `selectAction`；禁用、当前作品缺失、无可用动作、用户选择 | 菜单事件 + 后续业务 Task | 菜单展示和动作选择是入口证据 |
| 官方图片、视频、音频保存 | `MediaStoreDownloader.download*`；输入校验、HTTP 类型/大小、空文件/截断、关闭输出、MediaStore 发布、SAF、旧版 DownloadManager | `DownloadTasks.Task.operation` 显式传入 Transfer，worker 与 release 均 wrap | `output_committed`：目的地引用、字节、流关闭、发布结果；没有额外打开媒体文件读回，不声明画廊可见或格式可播放 |
| 官方实况保存 | `OfficialLivePhotoDownload.save`；图/视频来源、临时下载、宿主编码结果、独立文件输出、实况格式验证 | 同一 Task / Transfer | 编码 code 不等于成功；`live_photo_validated` 是宿主 `isLivePhoto` 加非空文件证据，随后仍须目的地提交证据 |
| 第三方直接下载 | `ThirdPartyDownload.startDirect`；GET 模板、签名模式、字符解码、下载字段、资产规划、目的地 | 同一 Task 内关联 GET、规划、每文件及元信息保存 | `parser_http_response` 只表示传输；所有资产及元信息关闭流后才提交 bundle 证据 |
| 第三方先解析再选择；独立直存配置 | `Session.begin/parsed/selected/close`；页面失效、用户取消、解析失败、选中子集、队列拒绝 | 选择 Operation wrap 主线程回调；解析和选中后保存继承 trace 的子 span | 选择完成单独标记 `selection_confirmed`，`media_save_result=pending`；实际下载 Task 给最终保存结果 |
| 下载取消、30 分钟预算、网络超时、重复任务/队列满 | `DownloadTasks`、`DownloadTransfer`、`ThirdPartyGetClient` | Operation 保存在 Task，取消不重新生成 trace | `CANCELLED`、`TIMED_OUT`、`SKIPPED`；网络超时与总预算超时可区别。原 UI 状态不改写 |
| 三方 / MiniMax / Android 离线 TTS；语音提供方、音色、速率、音高、接口与响应字段 | `SpeechSynthesis` / `LocalSpeechSynthesis`；配置不可用、文本、队列满、二次音频 URL、跨源凭据策略、超时、引擎拒绝、音频元信息 | 请求对象保存 Operation，worker、handler、TTS 初始化和回调显式传递；重复/迟到完成只记观察 | 本地非空音频、合法时长；三方音频还验证有音轨且无视频。`sent=false`，不把引擎接受 / HTTP 200 当音频完成 |
| 自动评论 / 私信前语音准备 | `SpeechPreparation.prepare/prepareComment`；owner 有效性、等待上限、入口时长限制、编码、迟到输出删除 | prepare Operation 包裹合成，Completion 保存相同 Operation | `comment_audio_prepared` / `im_audio_prepared`，记录源和编码时长、输出引用；`sent=false`。宿主发送确认由发送链路负责 |
| 三方音色目录、MiniMax 音色/API 模型/官方文档模型目录 | `SpeechHttpClient.fetchVoices`、`MiniMaxSpeechClient.fetch*` | `observeCatalog` 包裹完整传输与解析；独立 trace 或已有父 trace 子 span | `catalog_parsed` + 条数；`selection_saved=false`；取消/传输或解析失败独立终态 |
| 配置导出 / 预览 / 合并或替换导入 | `ConfigTransfer.export/inspect/apply`；类型/结构/secret 政策、缺少凭据、比较替换、回读 | 导入 Operation wrap Store 替换事务 | 导出仅证明内存备份文本已校验，`external_file_written=false`；预览 `applied=false`；导入快照匹配才能 SUCCESS，核对不可用为 UNCONFIRMED |
| 配置持久化 / 迁移 / 失败补偿 | `SettingsRepository.save/put*/replaceSnapshot`；旧推荐过滤键迁移、SharedPreferences commit 失败、回滚失败 | 替换事务继承导入 trace | `apply()` 仅记 `memory_applied`、`disk_commit=pending`；同步 commit 成功是本地存储证据，宿主同步仍未确认；不打印配置值 |
| 本地分析采集开关、浏览/动作写入、代次隔离、保留期、队列丢失 | `LocalAnalytics.setEnabled/write/enqueue`；禁用、空作品、过期代次、留存期外、队列满、存储失败关闭采集 | 已有上下文经 enqueue wrap 进入写线程；每次写事务独立 span | `sqlite_transaction_committed`，没有假称读回；队列拒绝有累计数量与容量；异常不再静默 |
| 本地统计快照与清空 | `LocalAnalytics.snapshot/clear`；读取中断、队列满同步读取、删除错误 | 各操作持有 Operation | 快照返回总量、丢失计数；清空必须数据库与 sidecar 均不存在 |
| 本地分析备份/校验/恢复与跨存储补偿 | `AnalyticsBackup.export/inspect/restore`；行数/字节/关联约束、配置和自述补偿 | restore wrap 配置与自述子操作 | SQLite 事务完成；明确 `cross_store_crash_atomic=false`，补偿阶段说明哪些存储曾变更和补偿错误数 |
| 用户自述 / 侧写反馈保存 | `LocalPortraitContext.save`；文件同步、替换、读失败 | 自述保存独立或继承备份 trace | `file_replaced` 与 `file_synced=true`；仅记录字符数/是否有反馈，不记自述或反馈内容 |
| 用户主动 AI 本地分析 | `AnalyticsAiAnalysis.prepare/Request.run/cancel`；未配置、聚合大小、网络、空/过长/不完整/格式错误、取消 | prepare Operation 保存进 Request，run wrap 传输；取消及迟到结果关联原请求 | 十一个字段与侧写均验证后 `analysis_schema_validated`；展示 UI 未确认，`displayed_to_user=unconfirmed` |

## 结果与完整性

每个 Task 拥有不可变 Operation，并传给 Transfer。队列请求、被执行、来源/传输、阶段变化、文件关闭、输出提交、任务释放沿用同一 trace。保存方法在成功完成原有关闭流和发布流程后才调用 `saved(...)`；Task 原业务返回“completed”却没有这种证据时，仅将**日志**终态标记 `UNCONFIRMED/save_evidence_missing`，不改变原 UI、返回值、统计行为。

下载进度不逐块打印。Transfer 只在 phase 变化、文件完成及关键证据点记录；重复 phase 不产生阶段日志。目录、文件名、模板、URL、凭据、正文与自述均不直接进入这些结构化事件；以会话加盐的 `reference()` 关联。异常使用统一入口，未知 exception message 不直接输出。

Operation 的幂等终态负责重复/迟到回调记录。业务清理继续使用原机制；日志不新增重试或切换提供方。进程被杀时，未产生终态的 Task、TTS、分析请求不能推断成功，须结合日志完整性摘要和未结束操作清单解释。进程前最后一段日志可能来不及刷出，日志丢失及系统日志截断也不应推断为“功能没触发”。

## 完整链路样例

以下为规范化示例，不是真机记录；省略每行共有的版本、会话、时间、进程与 seq 字段。

```jsonl
{"trace_id":"D1","feature_id":"third_party_download","event":"operation_started","reason_code":"triggered","evidence":"trigger=user_download"}
{"trace_id":"D1","feature_id":"third_party_download","event":"stage","stage":"queue_requested","evidence":"business_result=pending"}
{"trace_id":"D1","feature_id":"third_party_download","event":"stage","stage":"queue_admitted","evidence":"accepted=true; business_result=pending"}
{"trace_id":"D1","feature_id":"third_party_download","event":"stage","stage":"parser_request","evidence":"template_ref=ref_x; signature=none; business_result=pending"}
{"trace_id":"D1","feature_id":"third_party_download","event":"stage","stage":"parser_http_response","evidence":"status=200; result=transport_only"}
{"trace_id":"D1","feature_id":"third_party_download","event":"stage","stage":"parser_text_decoded","evidence":"bytes=240; chars=230; plan_validated=false; saved=false"}
{"trace_id":"D1","feature_id":"third_party_download","event":"stage","stage":"http_response","evidence":"status=200; result=transport_only; source_ref=ref_y"}
{"trace_id":"D1","feature_id":"third_party_download","event":"stage","stage":"file_closed","evidence":"completedFiles=1; totalFiles=2; bytes=13; expectedBytes=13"}
{"trace_id":"D1","feature_id":"third_party_download","event":"stage","stage":"file_closed","evidence":"completedFiles=2; totalFiles=2; bytes=310; expectedBytes=310"}
{"trace_id":"D1","feature_id":"third_party_download","event":"stage","stage":"output_committed","evidence":"destination=document_bundle; output_ref=ref_a; bytes=-1; streams_closed=true; read_back_verified=false"}
{"trace_id":"D1","feature_id":"third_party_download","event":"stage","stage":"output_committed","evidence":"destination=document_bundle; output_ref=ref_b; bytes=-1; streams_closed=true; read_back_verified=false"}
{"trace_id":"D1","feature_id":"third_party_download","event":"stage","stage":"work_returned","evidence":"completedFiles=2; totalFiles=2; saveEvidence=2"}
{"trace_id":"D1","feature_id":"third_party_download","event":"operation_finished","outcome":"success","reason_code":"completed","evidence":"completedFiles=2; totalFiles=2; saveEvidence=2; bytes=310"}
```

`bytes=-1` 表示 bundle 提交记录没有单项字节统计；之前相应的 `file_closed` 给出实际字节与期待字节。不能把 -1 解释为零字节或文件读回已验证。

故障链路：`http_response(status=200)` → 输出流抛 IOException → `exception(stage=download_work)` → bundle 原逻辑删除所有已创建 URI → `operation_finished(outcome=failed, saveEvidence=0)`。即便 HTTP 已成功，结果仍为保存失败。

语音链路：`provider_selected` → `queued` → `provider_request` → `audio_received` → `audio_validated` → `callback_pending` → `validated_audio_ready(sent=false)`。若返回音频样式字节但元信息含视频轨，终态为 `FAILED/invalid_audio`，不会产生合成 SUCCESS；取消后的回调只能留下 late callback，不会覆盖取消结果。

## 可执行验证

- `MediaLoggingEvidenceTest`：真实 JVM/假 SAF Provider 完成文件写入并核对字节；HTTP 200 后输出失败的清理与日志；无提交证据 UNCONFIRMED；重复释放仅一个终态；取消和超时不同；URL、文件名、说明文本和异常秘密不泄漏。
- `SpeechSynthesisTest.http200AudioBytesRequireMetadataEvidenceBeforeBusinessSuccess`：HTTP 200 + 音频样式字节不能绕过音频元信息验证；日志含传输状态但最终失败，无敏感文本或 API key。
- 既有 `DownloadCancellationTest`、`DownloadDocumentTreeTest`、`LocalMediaStorageTest`、`ThirdPartyDownloadSessionTest`、`OfficialLivePhotoDownloadTest`、`SpeechSynthesisTest`、`SpeechPreparationTest`、`SpeechHttpClientTest`、`MiniMaxSpeechClientTest`、`ConfigTransferTest`、`LocalAnalyticsTest` 与分析/备份测试继续验证原业务语义。

统一运行结果由总交付文档记录。此文件不声称真机 LSPosed、ROM 相册可见性、实际网络服务或宿主发送行为已经验收。
