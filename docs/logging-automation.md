# 自动化与原生消息日志接入

本文件按当前源码的业务入口列举接入范围。Hook 扫描规则是宿主契约证据，不是功能数量。状态、队列、发送、重试、超时参数和回调返回值沿用原实现；新增记录不构成真机兼容性结论。

## 入口与证据矩阵

| 功能 / 入口 | 配置与门禁 | 关联、阶段与异步边界 | 可验证结果与边界 |
| --- | --- | --- | --- |
| 自动评论 / 视频选中、图文选中、播放状态、回到前台、关系名单更新 | `native_auto_comment`、账号、前台/可见性、广告、已提交账本、作者范围、每日额度/用户冷却、`comment_mode`、图像/视频识别、语音、预览和延迟 | `AutoCommentHook.Task.trace` 是任务根；`AutoCommentQueue` 的 offer、延迟运行、回调绑定同一 trace；`attempt` 区分重试；媒体、请求发送、输出验证、语音准备、预览、账本占位、原生提交、回调、持久化、队列终态 | 原生受属请求成功回调且已有发送结果流程完成才记录 `success`。接受上传/原生 submit 返回、30 秒无回调为 `unconfirmed`。三次语音失败转文字记录 `voice_fallback`，保留原根 trace。 |
| 自动私信/群聊回复 / 本地 DB 与全局收信 | `native_auto_reply`、接收契约是否可用、账号切换、ReplyPolicy、关系范围/回复条件（ReplyPolicy 细分自己消息、历史消息、消息类型、群私聊开关和缺素材；GroupReplyPolicy 细分群名单、指定成员和未被提及；ContentScopePolicy 区分身份范围与关键词过滤）、已占位/重复、额度与冷却、队列容量、素材与 AI/固定内容、语音 | `ReplyTask.trace` 显式跨会话队列、看门狗、生成、媒体读取、模型流 I/O、预览、发送、结果 IO；记录会话未找到、空输出、策略变更等早退；队列和生成超时分别有阶段与期限 | SDK 成功回调证明发送被宿主确认；不等同对方阅读。语音要求宿主消息状态=2、正 server id、`s:status_code=0`。超时/持久化拥塞保留 `unconfirmed`，不能据此重试。 |
| 自动评论回复 / 前台轮询、评论 Push 提醒 | `comment_reply_enabled` + `native_auto_reply`、前台、账号、起点、每串上限、重复/账本、队列容量、额度、固定/AI/素材/语音设置 | `comment_reply_inbox` 根描述建立起点、扫描页、线程补查、过滤、入队计数；每个获选 `Task` 独立根记录素材、模型、语音、预览、账本占位和原生发布；`Operation.call` 将 Inbox/ThreadLookup 证据关联扫描根 | `baseline_persisted`/`scan_evaluated` 只证明扫描自身完成，明确不证明回复送达。原生请求接受保持待确认；发布回调才证明业务发送。claim 被拒、超过线程上限、历史起点、重复等有跳过说明。 |
| 自动续火花 / 定时 streakTick | `streak_enabled`、目标、时段、账号、SDK 可用、当日账本、pending、固定内容/HTTPS 内容接口 | 每个可发送目标建立 `streak` 根；记录内容准备、字符数、占位、SDK 回调、账本结果与 30 秒未知期限；原有重试/去重策略不变 | SDK 确认且完成状态落账为 `success`；回调失败为 `failed`；落账失败、发送后异常或回调超时为 `unconfirmed`。 |
| 自动点赞 / 可见卡片绑定 | 自动化总开关、点赞开关、非广告、未点赞、当前卡片、登录、有效 Activity、设置代次、去重 | 高频门禁用结构化 `eligibility` 状态变化/汇总；实际调用建立任务，说明当前卡片和 `userDigg=0` | 现有功能没有远端成功观察器，`likeMethod.invoke` 返回只能记录 `unconfirmed/native_like_requested`。不伪造点赞成功。 |
| 自动关注 / 选中卡片 | 自动化总开关、关注开关、非广告、followStatus=0、登录、去重、目标按钮与 Activity、设置代次、当前卡片 | 门禁用 `eligibility`；任务跨 `View.post` 绑定；异步再次验证卡片、按钮、状态和挂载；退出与异常有结果 | `performClick=true` 只记录 `unconfirmed/native_follow_click_accepted`；无远端关注关系确认。 |
| 自动私信草稿 / 原生编辑器绑定后 post | 总开关、草稿开关、配置文本、可见/挂载、纯文本模式、会话、已填入、用户原稿和最后一次防覆盖检查 | 状态汇总解释未填入原因；实际写入任务记录会话引用、字符数、是否观察到编辑器内容等于配置 | `success/draft_text_observed` 仅证明本地草稿匹配，`message_sent=false`；只观察到 setter 返回为 `unconfirmed`。 |
| 私聊文字转语音与替换语音 / 用户发送、长按语音、录音开始/完成 | `dm_tts` / `dm_voice_replace` / 长按选项、用户主动操作、`#` 前缀、有效草稿/编辑器/会话、音频 Logic、pending、30 秒去重、替换文件准备代次 | `DirectMessageSpeechHook.Pending.trace` 跨合成、编码、main 提交和受属上传队列；录音开始/取消/完成独立关联替换链路；准备文件只在验证元数据和新配置代次后成功 | 音频生成/替换准备成功只说明本地产物；路径与会话匹配的队列观察仍是 `unconfirmed`，不伪造远端发送成功。 |
| 评论替换语音 / 配置音频准备、原生上传 Input/Path/Listener/Notify | `comment_voice_replace`、配置文件、Context、准备代次、排除本模块合成音频 | 准备 worker 独立 trace；每次替换上传从 Input 显式绑定 Path→Listener，两个弱引用关联表各上限 128，驱逐说明不完整；30 秒上传回调观察期限；关闭、过期准备和迟到回调保留关联 | `mErrorCode=0` + 非空 `mVideoId` 只证明上传成功，最终 `unconfirmed/audio_uploaded_comment_publish_unobserved`，明确没有评论发布回执；非零错误证明上传失败；未知/缺字段继续等待期限。 |
| 评论文字转语音 / `#` 发布、长按语音评论 | `comment_tts` / 长按语音选项、Context、重入、自动发布保护、纯文本附件、手势目标、pending/配置代次 | 独立任务进入语音合成父 trace；记录准备、音频长度/字节、目标变化、忙、配置取消、原生提交；pendingTrace 负责关闭或设置变更的取消 | 合成产物可证明音频生成；原生提交缺少受属远端确认，因此最终 `unconfirmed/native_audio_comment_submitted`。 |
| 关系名单 / 设置触发、缓存失效、自动化使用 | 登录、所需关系类型、缓存有效、重试等待、账号代次、分页截止 | 每种实际拉取建立 `relationship_lists`；记录分页阶段和数量，账号引用而非 UID；过期 token 和取消明确 | `snapshot_published` 仅在账号代次验证且快照 publish 返回 true 后成功；旧账号结果不能标成功。 |
| 消息防撤回 / 消息表更新、isRecalled getter | 防撤回设置、MessageRecallPolicy、自己/他人身份和原始撤回状态 | 未修改路径汇总 policy_passthrough；替换 ContentValues 的实际 DB 调用建立任务，记录返回行数；getter 覆盖有消息引用 | `success` 只说明受保护本地 DB 更新返回正行数，`scope=local_database`；getter 记录 true→false。不声明服务器撤回行为改变。 |

## 身份和终态约定

`AutomationTrace` 是有界（512 项）历史 ID 到 Operation 的关联表，方便原有 dispatcher 复用业务任务的 trace。新业务实例调用 `start`，即使同一条收信因会话未就绪被重新接纳，也获得新 trace。任务、Pending 与 callback 显式持有自己的 Operation，老回调不会重新按 ID 查到新任务。关联表容量驱逐会记录 `correlation_evicted`，仍在运行的任务继续持有原 Operation。

`AutomationDispatcher` 的预览/单次发送回调只追加阶段，根终态由任务所有者确认：自动评论由队列完成处确认，私信/群聊回复由结果处理确认，评论回复由 complete 确认。单次失败准备重试不能先终结根。默认不把 `submitted` 映射成 `success`；`deferred` 映射为 `skipped`。截止期限失败映射为 `timed_out`；已发送但远端状态不清楚映射为 `unconfirmed`。

Operation 的终态不可被重复或迟到回调覆盖。迟到的真实成功记录补充证据，原先超时形成的 `unconfirmed` 记录保留，便于解释当时自动化为什么没有重试。进程直接中断无法执行 finally，必须结合统一日志入口的未完成操作快照、会话、序号与丢失说明判定记录完整性。

## 链路样例

以下是事件顺序示意，不是设备采样。公共字段使用统一规范，省略 wall_time、session_id 等重复字段；`T1` 表示同一 trace_id。

```text
T1 operation_started feature=auto_comment trigger=feed_selection
T1 stage queue_admitted target_ref=ref_... delay_seconds=3
T1 stage waiting delay_ms=3000 queue_size=1
T1 stage attempt attempt=1
T1 stage eligibility_recheck epoch_current=true cancelled=false
T1 stage media recognize_images=true recognize_video=false
T1 stage media_ready images=1 duration_ms=120
T1 stage model_request model_ref=ref_... images=1
T1 stage model_transport_sent body_bytes=... business_success=false
T1 stage model_http_response http_status=200 business_success=false
T1 stage model_stream_progress transport=stream stage=stream_complete chars=...
T1 stage model_output_validated chars=28 delivery_confirmed=false
T1 stage model_output protocol_reply_received=true business_success=false
T1 stage preview approved=false delivery=text
T1 stage preview_wait seconds=... ready=true
T1 stage dispatch_send ack_deadline_ms=30000
T1 stage submission_reserved durable=true invoked=false
T1 stage native_invoking reservation=true business_success=false
T1 stage native_submission_accepted direct=true callback_seen=false business_success=false
T1 stage native_ack status=sent timed_out=false evidence=...
T1 stage native_callback status=sent invoked=true uncertain=false
T1 stage publish_result status=sent
T1 stage dispatch_result status=sent evidence=native_sender_acknowledged
T1 operation_finished outcome=success reason=none evidence=owned_native_success_callback;ledger_completion_attempted=true;attempts=1
```

重试链路保持同一 T1，失败出现 exception 和 retrying，而不是 operation_finished：

```text
T1 attempt=1 model_request -> exception http_status=429 -> retrying delay_ms=1000
T1 attempt=2 model_request -> model_output -> native_submit
T1 acknowledgment_timeout deadline_ms=30000 remote_outcome=unknown retry_safe=false
T1 operation_finished outcome=unconfirmed reason=submitted_unconfirmed
T1 late_callback stage=late_native_ack status=sent  # 不改先前终态
```

没有执行的功能从 feature_state 和 eligibility 解释：例如 `auto_like/eligibility/already_liked` 说明宿主 userDigg 非零；`auto_comment/eligibility/activity_background` 说明不处于 dispatcher 认可的前台；`auto_comment_reply/scan_eligibility/disabled_or_background` 同时给出 enabled/foreground；这些是限频的状态观察，不为每个高频 Hook 新建任务。

## 本地验证与限制

新增 `AutomationTraceTest` 验证：同一任务重试与异线程回调保持 trace；过时 attempt 不结束新 attempt；submitted 后迟到成功不产生第二个根终态；相同收信重新接纳获得新根，旧回调不污染新根。保留原有 AutoCommentQueue、AutomationDispatcher、NativeMessagingHooks、NativeAudioMessageSender、CommentReplyHook、NativeCommentPublisherResponse、NativeCommentSpeechHook、ChatCompletionClient 等回归测试，测试执行结果由统一验证报告记录。

这些检查是 JVM/宿主模拟与编译检查，不证明当前抖音安装包、ROM、服务端状态或真实账号发送行为。自动点赞、自动关注和手动语音评论目前的正确验收结果可以是“结果未确认”，因为新增日志不会通过改变业务发送参数、额外网络查询或新增重试来制造成功证据。
