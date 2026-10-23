# DouAi 日志覆盖矩阵（2026-10-23）

此台账以当前 `FeatureLogCatalog` 源码和配置消费者为准：58 项可识别能力，覆盖 `ConfigurationCatalog.Feature` 的全部 28 个消费者功能族。功能数量与 DEX 扫描规则、Hook 方法数量无关。`HostFeatureCatalog` 只是安装契约，不作为业务清单。

本文件说明已接入的位置和证据边界，不能替代真机验收。JVM/本地fixture能证明模块分支与日志契约，不能证明真实抖音回调、ROM渲染、网络结果或LSPosed导出完整。只看到安装成功、Hook返回、HTTP接受、入队、Intent发出都不能推断业务成功。

本矩阵保留日志缺口源码修复及接入位置，后续已构建 [0.2.16 正式版](release-0.2.16.md)；该次本地测试和 Lint 按此前要求跳过，尚未做本版真机验收。当前写入规范为 schema 2，schema 1 历史文件仍兼容读取，不能把旧设备日志当成修复后的验证结果。

`debug_logging` 关闭时模块本地和LSPosed都只输出简单日志（log_mode=simple）：根开始/终态、功能状态、必要决策、损失及简要异常。打开后两处统一输出详细模式（log_mode=detail），才能看到阶段、子span、逐键配置及常规Hook安全明细。本矩阵描述完整接入能力，不能把简单模式主动省略的明细当成漏接；复核完整过程应先开启详细日志再复现。

## 如何核对任一功能

1. 以 `feature_state` 核对配置修订、`enabled`、`availability` 和 `hook_state`。`pending` 不能当关闭；`not_observed` 不能当能力正常。启用与能力是独立维度：关闭的功能仍可显示 `supported/installed` 或 `unavailable/installation_failed`，关闭开关不掩盖宿主不支持或安装失败；依赖未知或只观察到部分依赖时保留unknown/partial。
2. 以根业务 `operation_started` 和决策区分资格拒绝与实际执行；详细模式另有首次Hook观察。简单模式没有普通Hook记录不能证明未触发，`hook_returned` 永远只是调用观察。
3. 按 `trace_id` 和 `span_id` 读取阶段、异步/重试及最终状态。读取 `reason_code` 和 `evidence` 中的证据范围；本地View/DTO成功不能扩张成服务端结果。
4. 查看 `integrity_manifest` 的丢弃/限流/落地异常、开放操作、旧会话中断与最后结果。schema 2的feature_states/observations/last_outcomes是真JSON对象，观察与结果保留source_feature_id/result_scope；再核对storage/startup各层影响台账。记录不完整或外部传输未知时，应保留这个结论。

## 全能力覆盖矩阵

配置列中的枚举与后面的配置依赖表关联。`always` 表示入口能力不受本表布尔开关控制，实际业务仍有独立资格/授权/模型/生命周期判断。空安装键的UI能力为 `hook_state=not_required`，触发仍须由observation证明；启动时没有真实依赖证据则availability保持unknown，不伪造其已就绪、已触发或已完成。

| 功能ID及源码 | 配置消费者及启用证据 | 触发入口/Hook | 关键分支 | 链路/异步 | 结果证据及范围 |
| --- | --- | --- | --- | --- | --- |
| `feed_filter` · 推荐流筛选<br>`FeedAdFilterHook` | `FEED_FILTER`<br>`feed_filter_enabled` | Retrofit成功包装；主推荐流响应 | 总开关/无活动规则/非主流/模型不符/空列表保护/分类失败/写失败回滚 | classify → mutate_response → forward_response | 响应列表对象回读、删除数量；SUCCESS仅表示响应列表已变更，屏幕呈现未知 |
| `download` · 媒体下载<br>`DownloadActionHook` | `DOWNLOAD`<br>`download_enabled` | 原生保存菜单、保存入口；与手势保存共用下载层 | 官方/三方互斥、权限、选片、取消、写入失败 | 选择 → 下载 → 落盘/取消 | DownloadTasks/MediaStoreDownloader 文件字节及目标回读；入队不是保存成功 |
| `native_auto_reply` · 私信及群聊自动回复<br>`NativeMessagingHooks` | `REPLY`<br>`auto_reply_enabled` | 原生消息接收、前后台通知、会话补查 | 开关/自己消息/作用域/关系/关键字/去重/频率/账号变化 | 接收 → 策略 → AI/固定内容 → 原生发送回调 | 回调/消息标识等宿主证据；仅提交则UNCONFIRMED；私聊与群聊共享配置 |
| `streak` · 定时续火花<br>`NativeMessagingHooks/StreakSendLedger` | `REPLY`<br>`automation_enabled && streak_enabled` | 定时窗口、目标会话遍历 | 开关/时段/目标/当日账本/未知结果/重试/配置代次 | scheduled_window → ledger reservation → content → native send → callback | 每目标独立trace；本地账本与远端发送分开；submitted保持UNCONFIRMED，未知结果不自动重发 |
| `auto_comment` · 自动评论<br>`AutoCommentHook` | `COMMENT_AUTOMATION`<br>`auto_comment_enabled` | 视频/图文选择及播放状态 | 关闭/失效卡片/广告/作用域/日限额/冷却/重复/语音异步 | 选择 → queue → compose → publish → callback | 原生发布结果/评论对象；上传接受不是评论成功 |
| `comment_reply` · 评论回复<br>`CommentReplyHook` | `COMMENT_REPLY`<br>`comment_reply_enabled` | 评论通知拉取、推送及前台补扫 | 通道开关/父功能/线程/作者作用域/重复/限额/过期 | inbox → dispatch → reply publisher → callback | 评论原生回调；收件轮询是子操作，不是回复成功 |
| `feed_ui` · 视频图文组件隐藏及透明度<br>`FeedUiHideHook` | `FEED_UI`<br>`feed_ui_active` | 视频/图文生命周期、布局变更、播放暂停 | 组件配置/旧配置回退/临时显示/资源不唯一/宿主复写 | 匹配 → apply → view_properties_readback | 首次/宿主重置时回读 visibility/alpha/clickable/focusable；SUCCESS限当前View |
| `auto_speed` · 自动倍速<br>`PlaybackBehaviorHooks` | `PLAYBACK`<br>`auto_speed` | 播放器/Manager 播放、恢复、速度设置 | 关闭/手势优先/owned reentry/宿主失配/恢复 | 速度决策 → setter/已存在getter → restore | 已有getter相等可证当前速度；setter返回未回读记UNCONFIRMED |
| `auto_play` · 连播及评论暂停<br>`AutoPlayHook` | `AUTO_PLAY`<br>`auto_play_enabled` | 当前视频播放完成 | 会话代次/暂停/触摸/评论展开/前台/持有者/滚动/边界/重复 | 验证选择 → native_advance → selection evidence | 原生接受但选择未观察=UNCONFIRMED；同步选择回调变更仅证卡片切换 |
| `gesture` · 九宫格手势及工具栏<br>`FeedGestureToolsHook` | `GESTURE`<br>`gesture_enabled` | 九宫格长按/双击、工具栏和菜单 | 未配置/原生拦截/外部布局/选中卡片切换/重复 | dispatch → 对应动作；速度含3次异步readback | 手势速度getter回读可成功；保存/打开评论/查找器等仅分发仍未确认 |
| `prevent_message_recall` · 消息防撤回<br>`NativeMessagingHooks` | `MESSAGE_RECALL`<br>`prevent_message_recall` | 原生消息数据库变更 | 开关/自己与他人/撤回记录/对象形态 | 校验 → 本地撤回拦截 → 记录 | 业务限本地消息变更；不推断服务端撤回状态 |
| `spoof_profile` · 本地个人资料数量<br>`ProfileSpoofHook` | `SPOOF`<br>`spoof_profile` | 本人资料渲染、pre-draw状态变化 | 开关/非本人/无窗口/视图切换/恢复 | 配置数量 → 当前TextView回读 | 只输出字段引用及resource id；当前文本相等不等于真实账户数量 |
| `follow_lists` · 好友关注粉丝及群成员列表<br>`NativeFollowLists` | `FOLLOW_LISTS`<br>`always` | 策略预热/手动刷新及分页 | 缓存/刷新中/host能力/部分分页/失败 | relationship_lists refresh → page merge → publish | 列表/页数及快照完整性；不记录成员姓名ID正文 |
| `automation_dispatch` · 自动化调度提示及历史<br>`AutomationDispatcher` | `AUTOMATION_DISPATCH`<br>`automation_dispatch_enabled` | 自动化接收、延迟执行、完成提示和历史 | 关闭/策略拒绝/限额/取消/媒体准备失败 | eligible → delay → content → dispatch → outcome | AutomationTrace与原生发送/评论回调；分发成功不升级业务成功 |
| `dm_speech` · 私聊语音替换及文本转语音<br>`DirectMessageSpeechHook` | `DIRECT_MESSAGE_SPEECH`<br>`dm_speech_enabled` | 私聊发送、语音上传、长按语音 | TTS/替换/路径/长度/账号/会话变更/取消 | speech preparation → audio sender → callback | 生成文件不等于消息发出；语音异步保持trace |
| `media_model` · 下载限制及媒体模型<br>`MediaModelHook` | `MEDIA_MODEL`<br>`remove_download_limits` | 媒体卡片绑定 | 关闭/无已有媒体/无videoControl/已应用/字段错误回滚 | apply_flags → download_presentation_flags | 本地DTO字段回读；SUCCESS不意味着下载或绕过服务端限制 |
| `comment_enhancements` · 评论后缀及颜色<br>`CommentEnhancementsHook` | `COMMENT_ENHANCEMENTS`<br>`comment_enhancements_enabled` | 评论发布请求工厂 | 非评论接口/无适用变更/后缀/颜色/音频长度补正 | prepare → request_fields_transformed → forward_request | 仅请求变换，最终发布回调未在此观察=UNCONFIRMED |
| `comment_voice` · 评论语音替换<br>`CommentVoiceHook` | `COMMENT_VOICE`<br>`comment_voice_replace` | 评论音频上传及通知 | 关闭/路径无效/异步接管/重试/回调 | 替换 → 上传 → 发布链 | 下游speech/native publisher；音频上传返回不能证明评论发布 |
| `comment_tts` · 评论文本转语音<br>`NativeCommentSpeechHook` | `NATIVE_COMMENT_SPEECH`<br>`comment_tts` | 评论发布模型、文本转语音及#长按 | 关闭/无正文/同草稿处理中/语音失败/迟到回调 | TTS → 文件 → 原生attachment → submit | 音频阶段与评论发布分开；缺发布回调保持UNCONFIRMED |
| `auto_like` · 自动点赞<br>`AutoLikeHook` | `AUTO_LIKE`<br>`auto_like_enabled` | 当前卡片绑定 | 关闭/广告/已点赞/非当前/未登录/Activity/重复 | 资格 → 原生like调用 | 无业务回调契约时UNCONFIRMED，避免把void返回当点赞成功 |
| `auto_follow` · 自动关注<br>`AutoFollowHook` | `AUTO_FOLLOW`<br>`auto_follow_enabled` | 当前卡片选择 | 关闭/已关注/未登录/按钮/Activity/卡片切换/重复 | 资格 → post → click | post保持trace；performClick被接受不等于关注成功 |
| `auto_message` · 私聊草稿<br>`AutoMessageDraftHook` | `AUTO_MESSAGE`<br>`auto_message_enabled` | 原生私聊编辑器绑定 | 关闭/空内容/非纯文本/用户已有草稿/已预填/切会话 | prefill → draft_apply → editor readback | editor文本匹配仅证明草稿；message_sent=false |
| `comment_tools` · 评论复制及媒体工具<br>`CommentToolsHook` | `COMMENT_TOOLS`<br>`comment_tools_enabled` | 评论菜单行长按、工具选择 | 关闭/宿主已有长按/模型缺失/无适用动作/剪贴板缺失 | menu → action → clipboard/native_save | 复制/保存提交返回均未读取最终文件或剪贴板=UNCONFIRMED |
| `immersive` · 状态栏及导航栏沉浸<br>`ImmersiveModeController` | `IMMERSIVE`<br>`immersive_enabled` | Activity恢复/焦点、配置变更 | 开关/Window缺失/恢复原flags | capture → apply/reapply → flags_readback → restore | 仅Window flags回读；ROM最终系统栏渲染未知 |
| `version` · 版本显示伪装<br>`VersionInfoHook` | `VERSION`<br>`version_spoof_enabled` | 目标PackageManager PackageInfo查询 | 关闭/非目标/空配置/非法code/复制失败 | copy → override → returned PackageInfo差异 | 返回对象版本字段变更可证本地显示；安装包元数据未修改 |
| `top_tabs` · 顶部标签隐藏<br>`TopTabFilterHook` | `TOP_TABS`<br>`top_tab_filter_enabled` | 首页标签提供器 | 空列表/关闭/类型不符/无匹配/全部移除保护 | classify → filtered_list_returned | 返回列表数量及保持最后标签保护；实际绘制另待宿主 |
| `refresh_confirm` · 刷新确认<br>`RefreshConfirmHook` | `REFRESH`<br>`refresh_confirm_enabled` | 重复点击当前首页tab | 关闭/非当前/Activity缺失/已有确认/取消/关闭 | await_dialog → await_confirmation → forward_native_refresh | 用户取消=CANCELLED；原生刷新返回而无新响应=UNCONFIRMED |
| `diagnostics` · 诊断及日志管理<br>`DebugLogChannel/DebugLogDialog` | `DIAGNOSTICS`<br>`always` | 宿主日志注册、查看/导出/清空 | 详细开关/缓冲满/限流/轮转/通道失败/进程中断 | structured event → queue → store/framework → integrity_manifest | 会话、序号、截断、损失、开放操作和旧会话恢复；外部传输完整性unknown |
| `local_analytics` · 本地浏览及行为分析<br>`LocalAnalyticsHook/LocalAnalytics` | `null`<br>`local_analytics_enabled` | 真实可见卡片选择、前后台及750ms观测 | 记录关闭/可见性/相邻预载/代次/重复/睡眠间隔 | visible_visit → 累计可见间隔 → finishVisit | Visit保存稳定operation；最终存储调用返回不冒充持久化完成 |
| `recall_background` · 撤回消息背景<br>`RecallHighlightHook` | `MESSAGE_RECALL`<br>`prevent_message_recall` | 已记录撤回消息的单元格绑定 | 开关/非撤回/无View/账号/复用恢复 | restore previous → recall record → set background | 仅设置背景返回；不再次读取宿主消息正文，不推断实际绘制 |
| `home_navigation` · 首页导航组件隐藏<br>`HomeNavigationHideController` | `FEED_UI`<br>`feed_ui_active` | 首页导航monitor、布局及配置 | 非首页/未配置/临时显示/目标非唯一/恢复 | unique target → apply → changed property readback | 只在属性变化时记录；当前View可见性/透明度回读 |
| `short_progress` · 短视频进度条<br>`ShortVideoProgressHook` | `FEED_UI`<br>`feed_ui_active` | 短视频进度隐藏谓词 | 未配置/关闭/广告/类型/缺时长/时长范围 | 原谓词 → duration policy → returned_hidden | 只记录谓词决策；不证明进度条已绘制 |
| `hash_long_press_voice` · 井号长按语音<br>`HashLongPressVoiceHook` | `null`<br>`hash_long_press_voice` | #前缀草稿长按 | 未绑定/关闭/无#前缀/空语音/编辑器/下游忙/点击拒绝 | validate_draft → message/comment handoff | 同trace调用下游语音span；手势被消费或click接受仍UNCONFIRMED |
| `remove_splash_ads` · remove_splash_ads<br>`HostBehaviorHooks` | `HOST_BEHAVIOR`<br>`remove_splash_ads` | 对应宿主门函数/手势/插入方法 | 关闭/能力失配/独立回滚 | decision → host_call_suppressed | SUCCESS仅证本次宿主方法被抑制；不宣称服务端或永久效果 |
| `remove_update_dialog` · remove_update_dialog<br>`HostBehaviorHooks` | `HOST_BEHAVIOR`<br>`remove_update_dialog` | Dialog/Fragment show/showNow | 关闭/非目标Dialog/底部弹窗/事务重载 | decision → dialog_show_suppressed | 记录目标弹窗调用被跳过；不是全应用所有弹窗都被移除 |
| `remove_teen_dialog` · remove_teen_dialog<br>`HostBehaviorHooks` | `HOST_BEHAVIOR`<br>`remove_teen_dialog` | 对应宿主门函数/手势/插入方法 | 关闭/能力失配/独立回滚 | decision → host_call_suppressed | SUCCESS仅证本次宿主方法被抑制；不宣称服务端或永久效果 |
| `hide_video_black_edges` · hide_video_black_edges<br>`HostBehaviorHooks` | `HOST_BEHAVIOR`<br>`hide_video_black_edges` | 黑边容器/发布按钮挂载及状态变化 | 关闭/目标缺失/宿主复写/恢复 | remember → apply → visibility readback | 仅属性变化记录；设置目标visibility后回读，关闭后恢复 |
| `hide_camera_plus` · hide_camera_plus<br>`HostBehaviorHooks` | `HOST_BEHAVIOR`<br>`hide_camera_plus` | 黑边容器/发布按钮挂载及状态变化 | 关闭/目标缺失/宿主复写/恢复 | remember → apply → visibility readback | 仅属性变化记录；设置目标visibility后回读，关闭后恢复 |
| `disable_double_tap_like` · disable_double_tap_like<br>`HostBehaviorHooks` | `HOST_BEHAVIOR`<br>`disable_double_tap_like` | 对应宿主门函数/手势/插入方法 | 关闭/能力失配/独立回滚 | decision → host_call_suppressed | SUCCESS仅证本次宿主方法被抑制；不宣称服务端或永久效果 |
| `prevent_skip_hidden` · prevent_skip_hidden<br>`HostBehaviorHooks` | `HOST_BEHAVIOR`<br>`prevent_skip_hidden` | 当前/后台隐藏视频安全任务及删除方法 | 关闭/分支鉴别/部分安装失效/任务profile不匹配 | route decision → safety_task_suppressed | 当前与后台hit/blocked分开；首次及2的幂次汇总，不输出作品标识 |
| `prevent_insert_video` · prevent_insert_video<br>`HostBehaviorHooks` | `HOST_BEHAVIOR`<br>`prevent_insert_video` | 对应宿主门函数/手势/插入方法 | 关闭/能力失配/独立回滚 | decision → host_call_suppressed | SUCCESS仅证本次宿主方法被抑制；不宣称服务端或永久效果 |
| `block_long_press_speed` · block_long_press_speed<br>`HostBehaviorHooks` | `HOST_BEHAVIOR`<br>`block_long_press_speed` | 对应宿主门函数/手势/插入方法 | 关闭/能力失配/独立回滚 | decision → host_call_suppressed | SUCCESS仅证本次宿主方法被抑制；不宣称服务端或永久效果 |
| `ai_chat` · 手动AI会话及图片语音<br>`AiConversationDialog/ChatCompletionClient` | `null`<br>`always` | 手动聊天发送与重试 | 模型/空输入/附件/取消/过期回调/网络错误 | send_or_retry → model request → response/UI | 对话结果与服务器调用、流式token和渲染证据分开 |
| `ai_contacts` · 消息页AI联系人<br>`AiMessageEntryHook` | `null`<br>`always` | 消息页联系人刷新、联系人点击 | 关闭/无启用联系人/列表非唯一/模型移除/重复dialog | refresh → insertion readback → dialog open | 只证明插入或dialog显示，AI回复另属ai_chat |
| `module_startup` · 模块进程及启动条件<br>`DouAiModule` | `null`<br>`always` | onModuleLoaded；功能安装准备入口 | 目标主进程/其他进程/安装依赖未齐 | process_gate → feature_install_gate | 进程、API及等待原因；模块被加载不表示全部功能可用 |
| `host_scan` · 宿主扫描及安装准备<br>`DouAiModule/HostScanner` | `null`<br>`always` | onPackageLoaded及后台scanHost | 包资格/重复调度/元数据缺失/profile不符/缓存/扫描失败 | scan_gate → await_context → scan progress → install_features → report | scan_report_published只证报告已发布；规则数量不是功能数量，安装及业务结果分别读取 |
| `model_settings` · AI模型及提示词配置<br>`AiModelEditor/PromptEditorDialog` | `null`<br>`always` | 模型和提示词保存 | 有效性/激活模型/协议/保存异常 | save_model_configuration → persist | 保存返回未持久化回读=UNCONFIRMED；凭据/提示词正文不输出 |
| `speech_settings` · 语音配置及试听<br>`SpeechSettingsDialog` | `null`<br>`always` | 用户试听及关闭/替换试听 | 配置/TTS失败/超时/页面代次/迟到准备/播放错误/取消 | speech_preview → synthesis_requested → audio_ready → playing → player callback | 合成文件、播放器开始及播放完成分开；未等到完成不能当试听成功，文本/路径不输出 |
| `third_party_settings` · 第三方解析配置及测试<br>`ThirdPartyDownloadEditor` | `null`<br>`always` | 用户测试GET、查看解析、保存响应样本 | 链接无效/网络失败/解析失败/页面失效/取消/样本保存失败 | third_party_get_test → request → parse → preview；third_party_response_sample → settings write | 解析/预览只证测试结果；保存样本未回读则UNCONFIRMED，不能等同实际媒体下载成功 |
| `configuration` · 配置读取热更新及迁移<br>`DouAiModule/ConfigurationSnapshot` | `null`<br>`always` | 初始读取、偏好变更、导入事务发布 | 事务未提交/无变化/读失败保留旧设置/分功能热更 | snapshot → config revision → per-key refs → feature_state | 输出键/布尔/引用及consumer；跨功能是否启用、安装可用与运行结果分离 |
| `config_transfer` · 配置导入导出及恢复<br>`ConfigTransferDialog/ConfigTransfer` | `null`<br>`always` | 配置文件选择/序列化/预览/导入确认 | 大小/类型/哈希/版本/逻辑备份/原子事务/失败恢复 | config_export/import_preview/import；settings_replace | 实际序列化/提交和回读证据；文件通道由media_picker串接 |
| `portrait` · 本地画像及AI报告<br>`LocalAnalyticsDialog/AnalyticsAiAnalysis` | `null`<br>`always` | 本地分析快照、AI画像、描述保存 | 关闭/空数据/请求配置/取消/响应无效/失效请求 | local_analytics_report / analytics_ai_analysis / portrait_context | 统计快照、AI回复和持久化阶段各自单独结果；原始浏览和用户描述不落日志 |
| `report_export` · 报告图片文件分享<br>`ReportImageExport/ReportImageShare` | `null`<br>`always` | 图片/文件报告导出及分享 | 无报告/尺寸/写失败/取消 | save_report → generated file → user destination | 生成文件和外部保存分开验证；分享Intent发出不能证明接收方保存 |
| `history` · 自动化历史及未知提交恢复<br>`AutomationHistoryDialog/SubmissionRecoveryDialog` | `null`<br>`always` | 自动化历史查看、未知提交人工修复 | 未知记录/已确定/用户取消/账本写失败 | manual_reconcile → ledger update | 人工标记是人工证据，不能当服务器自动确认 |
| `media_picker` · 图片语音及目录选择<br>`HostImagePicker/HostAudioPicker/HostDownloadDirectoryPicker` | `null`<br>`always` | 图片/音频/配置/保存目录选择 | 权限/无picker/用户取消/URI/读取失败/目录授权 | picker launch → result → read/validate | result URI不是读取成功；不输出URI和文件名正文 |
| `settings_entry` · 宿主设置入口及主题<br>`HostSettingsEntry/HostUiController/ModuleTheme` | `null`<br>`always` | 宿主设置页面注入、点击、主题刷新 | host设置页面识别/重复入口/Activity不可用 | entry click → settings visible | 界面显示证据与功能安装分开；主题切换仍需要实际设备观察 |
| `restart` · 宿主重启<br>`HostAppRestarter` | `null`<br>`always` | 适配确认后的宿主重启 | 目标包/Activity/启动Intent/计划任务失败 | restart schedule → launch request | 进程退出后无法证明重启完成，跨会话应为UNCONFIRMED |
| `launcher` · 模块桌面图标<br>`LauncherVisibility` | `null`<br>`always` | 设置桌面别名可见性 | 组件状态/PackageManager错误 | toggle_alias → enabled-state readback | 别名enabled状态回读；桌面缓存显示不是可验证业务结果 |

## 配置键与共享依赖

`FeatureLogCatalog.Entry.configurationKeys()` 直接读取 `ConfigurationCatalog.settings()` 的消费者集合，不复制另一份手工配置定义。每次已提交配置变更在详细模式输出键、类型、布尔值或会话内引用及消费者；简单模式只保留简要配置操作结果和修订标识。不输出提示词、关键词、API凭据、URL、用户标识、目录URI或任意配置字符串。被移除的键在详细模式另有 `config_removed`，不把旧值当当前值。

`COMMENT_REPLY` 继承 `REPLY`；`AUTOMATION_DISPATCH` 联合回复、评论和评论回复消费者。以 `tts_`、`third_party_`、`long_press_` 等前缀定义的未来键也沿相同热更新依赖计算。没有消费者的旧键仍能在配置日志看到，但不能从其存在推断有对应运行功能。

配置revision使用完整、带类型的确定性摘要并加入session盐；只在已提交快照变化时计算。Operation固定接纳时的 `config_revision`，事件同时携带 `process_config_revision`；原业务后续实际选用/重读配置以 `configuration_selected.effective_config_revision` 及终态 `configuration_selections` 说明，日志不会改变读取时机。

就绪状态按实际运行证据发布。`feature.<id>.dependency.<name>=false` 表示missing；只有一个或几个检查为true仍是partial，不能推出所有依赖满足。只有功能提供明确 `feature.<id>.dependency_state=ready` 才能断言依赖检查完成；安装成功但账号、权限、模型或远端服务未知的能力保持partial。配置尚未完成则pending，明确安装失败则unavailable。UI无安装键不等于supported；它仍需要真实依赖/入口证据。

以下依赖表达式摘自源码 `ConfigurationCatalog.depend`；值永远不进入本文或日志。`FeatureSettings`、`ExtendedSettings`、`LocalOptions` 和 `ComponentHideSettings` 继续掌握默认值与旧格式兼容。

| 配置消费者 | 键和前缀依赖（源码表达式） |
| --- | --- |
| `FEED_FILTER` | `"feed_filter_enabled hide_ads hide_lives hide_image_posts hide_recommendation_cards hide_recommendation_merchants keyword_filter_enabled keyword minimum_duration_enabled minimum_duration_seconds minimum_likes_enabled minimum_like_count filter_interrupt hide_mini_games hide_long_videos hide_product_cards hide_hotspot_rank_cards maximum_duration_seconds", ""` |
| `DOWNLOAD` | `"download_enabled remove_watermark download_custom_name download_name_template remove_download_limits download_method download_directory_uri", "third_party_"` |
| `REPLY` | `"automation_enabled native_auto_reply reply_global reply_targets reply_mode reply_prompt reply_delay_seconds auto_message_text reply_daily_limit reply_user_cooldown_minutes reply_recognize_images reply_recognize_video reply_keywords reply_scope_mode reply_scope_relations reply_scope_fields reply_scope_keywords reply_scope_users reply_filter_mode reply_filter_keywords reply_filter_fields reply_private_chats reply_group_chats reply_group_scope_mode reply_group_targets reply_group_mode reply_group_members reply_random ai_model_settings_v2 streak_enabled streak_targets streak_content streak_content_api streak_start streak_end streak_tip streak_reset_token streak_retry_unknown_token reply_voice", "tts_"` |
| `COMMENT_AUTOMATION` | `"automation_enabled native_auto_comment comment_mode comment_prompt comment_delay_seconds auto_comment_text comment_daily_limit comment_user_cooldown_minutes comment_recognize_images comment_recognize_video comment_scope_mode comment_scope_relations comment_scope_fields comment_scope_keywords comment_scope_users comment_filter_mode comment_filter_keywords comment_filter_fields ai_model_settings_v2 comment_voice", "tts_"` |
| `COMMENT_REPLY` | `"native_comment_reply", ""` |
| `FEED_UI` | `"hide_interface video_right_alpha component_hide_enabled component_hide_uniform_enabled component_hide_uniform_alpha component_hide_on_switch component_hide_open_comments_on_show component_hide_show_on_pause component_hide_custom_views", "component_alpha_"` |
| `PLAYBACK` | `"auto_speed playback_speed", ""` |
| `AUTO_PLAY` | `"auto_play smart_swipe pause_auto_on_comments", ""` |
| `GESTURE` | `"playback_speed toolbar_enabled long_press_action double_tap_action intercept_native_long_press remove_watermark download_custom_name download_name_template remove_download_limits download_method download_directory_uri", "long_press_ double_tap_ toolbar_ third_party_"` |
| `MESSAGE_RECALL` | `"prevent_message_recall recall_self recall_other recall_background", ""` |
| `SPOOF` | `"spoof_version fake_version_name fake_version_code spoof_profile profile_like_count profile_following_count profile_follower_count profile_mutual_count", ""` |
| `FOLLOW_LISTS` | `"reply_scope_mode comment_scope_mode reply_private_chats reply_global reply_scope_relations reply_targets reply_group_targets reply_group_members comment_scope_relations comment_scope_users relation_lists_refresh_token", ""` |
| `AUTOMATION_DISPATCH` | `"reply_tip comment_tip reply_notice_seconds comment_notice_seconds streak_tip reply_save_history comment_save_history", ""` |
| `HOST_BEHAVIOR` | `"remove_splash_ads remove_update_dialog remove_teen_dialog hide_video_black_edges hide_camera_plus disable_double_tap_like prevent_skip_hidden prevent_insert_video block_long_press_speed", ""` |
| `DIRECT_MESSAGE_SPEECH` | `LocalOptions.HASH_LONG_PRESS_VOICE, "dm_ tts_"` |
| `MEDIA_MODEL` | `"remove_download_limits", ""` |
| `COMMENT_ENHANCEMENTS` | `"comment_append_suffix comment_suffix colored_comment colored_comment_type", ""` |
| `COMMENT_VOICE` | `"comment_voice_replace comment_voice_path", ""` |
| `NATIVE_COMMENT_SPEECH` | `"comment_tts " + LocalOptions.HASH_LONG_PRESS_VOICE, "tts_"` |
| `AUTO_LIKE` | `"automation_enabled auto_like_enabled", ""` |
| `AUTO_FOLLOW` | `"automation_enabled auto_follow_enabled", ""` |
| `AUTO_MESSAGE` | `"automation_enabled auto_message_enabled native_auto_reply auto_message_text hide_interface", ""` |
| `COMMENT_TOOLS` | `"copy_comment_text comment_media_tools copy_source_data", ""` |
| `IMMERSIVE` | `"remove_black_edges hide_navigation_bar", ""` |
| `VERSION` | `"spoof_version fake_version_name fake_version_code", ""` |
| `TOP_TABS` | `"hide_top_tabs hidden_top_tabs", ""` |
| `REFRESH` | `"confirm_refresh", ""` |
| `DIAGNOSTICS` | `LocalOptions.DEBUG_LOGGING, ""` |


## 主功能与底层子链路ID

一个业务族可能包含多个底层 `feature_id`。按相同 `trace_id` 联合读取，不能只按主功能ID过滤后把子链路结果漏掉。以下是 `FeatureLogCatalog.registerAliases()` 当前注册的清单归并：事件本身保留原始feature_id，manifest主项保留source_feature_id，最后结果保留result_scope和root_operation；子链路结束不自动升级主功能为成功。

| 台账主功能 | 运行中的子链路ID |
| --- | --- |
| `native_auto_reply` | `auto_reply` |
| `comment_reply` | `auto_comment_reply`、`comment_reply_inbox`、`comment_reply_thread_lookup` |
| `comment_voice` | `comment_voice_replace` |
| `dm_speech` | `dm_voice_replace`、`dm_tts` |
| `follow_lists` | `relationship_lists` |
| `portrait` | `analytics_ai_analysis`、`portrait_context` |
| `config_transfer` | `config_export`、`config_import_preview`、`config_import` |
| `configuration` | `settings`、`settings_replace` |
| `download` | `media_download`、`download_menu`、`official_download_selection`、`official_download_save_attempt`、`third_party_selection`、`third_party_download` |
| `hash_long_press_voice` | `hash_long_press_voice_comment` |
| `local_analytics` | `local_analytics_collection`、`local_analytics_report`、`local_analytics_write`、`local_analytics_clear`、`analytics_backup_export`、`analytics_backup_preview`、`analytics_backup_restore` |
| `speech_settings` | `speech_preview` |
| `third_party_settings` | `third_party_get_test`、`third_party_response_sample` |

`speech_synthesis`、`local_speech_synthesis`、`speech_preparation`、`native_audio_message`、`native_comment_publish` 等共享底层能力仍按实际trace读取，不为多个主功能同时伪造最后成功结果。别名只整理清单归属，不改变业务trace或结果证据范围。

## 高频、隐私及故障上下文

`DebugHookTrace` 关闭详细模式时不检查参数、DTO内容或结果正文，也不使用详细模式时钟；内部首次/状态变化/幂次计数仍维护，但常规Hook观察不输出到任何sink。打开详细日志后才输出这些观察与结构化hook_detail，保留args/result的类型、布尔、长度及duration_us，不再经legacy压成引用；包装层5秒窗口普通全局200/每Hook8条，额度耗尽后异常全局20/每Hook2条，核心相同摘要另做30秒抑制。返回不是业务终态。关闭debug仍保留简要异常，省略栈、cause及参数/结果/详细耗时。播放进度同阶段/attempt在1秒窗口汇总，布局按首次或属性变化输出；简单模式省略普通阶段，不进行逐帧树全文落地。隐藏视频安全任务按路由分别汇总计数。

异常内部关联类名、静态安全原因、原因链/裁剪栈、`error_id` 和异常消息引用；同一Throwable在有界弱身份表内复用error_id。详细模式首次带裁剪上下文，简单模式仅保留类名、安全短说明及ID。终态通过error_ids链接（内部本操作及根操作各最多8项；简单输出最多3项并说明省略数量）。调用方标注feature/stage；未知异常正文不明文落地。未受信任的旧文本进入 `legacy` 适配器，只有静态标签、长度和引用；普通legacy诊断随详细模式输出，WARN/ERROR保留简单记录，旧打印不会被计为结构化业务覆盖。

JSON序列化由后台证据线程完成，结构化单队列出队后直写Store，旧文本保留独立有界队列。缓冲/开放操作/去重窗口均有界；核心、启动与存储分别通过LogLossLedger记录reason/session/feature/event/trace及序号包络，各最多64组，超出部分显式聚合。序号min/max不表示中间全丢失，核心队列全关键记录时仍可能丢失。丢失和抑制须核对全部清单，不能只看上游lost_count。框架分片包含事件ID、trace、序号、片号/总数和校验值；只有完整片集才可重建同一事件。文件轮转、进程被杀、磁盘写失败或外部LSPosed传输都不能用一个业务成功事件掩盖。

## 证据与验证路径

| 源码证据 | 可确认的结论 | 可复核的路径 |
| --- | --- | --- |
| `FeatureLogCatalog` 与 `ConfigurationCatalog` | 所有配置消费者都有能力归属，独立支持功能被显式登记 | `FeatureLogCatalogTest` 全枚举覆盖/共享键/Hook归一 |
| `DebugHookTrace`、`LogModePolicy` | 简单模式省略常规Hook明细，详细模式保留触发及聚合观察；异常仍有简要记录，调用返回不伪造成业务成功 | `DebugHookTraceTest`、`LogModePolicyTest`：零详细时钟、返回/异常保留、白名单投影与大小约束 |
| `FeedAdFilterHook` | 列表过滤、回滚与回读证据可在同一trace复核 | `FeedAdFilterHookTest` 真实fixture列表、removed和回读结果 |
| `FeedUiHideHook`、`HomeNavigationHideController` | 只证明当前View属性，布局变更不会让旧证据永久有效 | 现有View/透明度/复用/宿主复写回归测试 |
| `RefreshConfirmHook`、`FeedGestureToolsHook` | UI异步、取消和三次速度回读保持关联 | 原有手势/播放器fixture与完整日志样例；真机回调仍待验 |
| `DouAiLog` | 六种终态、重复/迟到回调、有限缓冲与进程中断有统一语义 | `DouAiLogTest`；最终命令和通过数量见本次验收记录 |

完整链路样例及统一事件字段由同目录的日志事件/验收文档提供。这里只登记接入点，不把文档中的示意轨迹冒充真实设备输出。

具体注册表达式和源码位置见[Hook注册索引](logging-hook-index.md)；78处静态表达式不代表功能数量或实际安装数量。
