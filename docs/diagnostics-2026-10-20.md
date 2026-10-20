# 2026-10-20 备份、MiniMax 与语音评论诊断

本轮证据能确认语音已生成后在宿主链路失败，也能确认旧回执日志漏掉了可读取的数值错误码；它们尚不能确认失败由时长、大小、格式、作品权限或风控中的哪一项造成。**宿主默认录音 30 秒，不等于已证实的上传或发布上限。** 0.2.1 因此补足阶段诊断、简化刷新入口和减少未配置的默认预览等待，没有据此新增 30 秒限制、截断或自动加速策略。

## 范围与材料

这是用户授权的本项目本地故障分析，范围包括用户提供的日志、备份、合法取得的抖音 40.6.0 APK，以及 MiniMax 公开文档。没有在本轮连接手机、调用真实账户合成、发送真实评论或修改用户备份中的凭据。本报告使用普通 APK 调用链与证据记录结构，不作漏洞或恶意软件定性。

日志时间按原始记录显示，没有重新校准设备时钟。`work/` 中的日志、宿主文件和真实备份是本地材料，不随源码发布；第三方复核须自行准备合法取得的对应输入，报告不附账户、UID、正文、Key 或签名媒体地址。

| 证据 | 来源与范围 | 复核方式 |
| --- | --- | --- |
| E-01 | `work/diagnostics-20261020-195230/comment-audio-evidence-summary.json`：按版本归类的生成、提交与回执 | 读取 `statistics` 与 `submissions`；重复 verbose/modules 记录已去重，32,264 条解析事件合为 16,131 条事件 |
| E-02 | 同目录 `comment-audio-current-timeline.json`：0.1.100 的 5 次完整文本回退流程 | 按 `startFile/startLine` 对照脱敏日志，核对生成、首次提交、回退与结束时间 |
| E-03 | 同目录 `comment-reply-scan-timeline.json`：评论回复扫描时间线 | 按 `startFile/startLine/endLine` 对照回源与扫描日志 |
| E-04 | `work/comment-voice-verify-20261020/findings.json` 与 `original/`：22 类原 DEX 对照 | 使用同目录 `verify-direct-dex.ps1` 与原始 APK 重建证据；该脚本还依赖本机 Java、apktool.jar 与既有 `VerifyVoiceDex.java`，不下载或修改 APK |
| E-05 | 用户提供的 22,559 字节备份及本地证据测试 | `work/backup-import-evidence/BackupImportEvidenceTest.java`、`BackupDialogEvidenceTest.java`；SDK 35 / Robolectric 4.14.1，使用本地私有输入，不将文件内容写入报告 |
| E-06 | MiniMax 国际公开[速率限制](https://platform.minimax.io/docs/guides/rate-limits)与[错误码表](https://platform.minimax.io/docs/api-reference/errorcode)，本次已查阅 | 公共页面读取，不使用账户认证；不是对中国区或代理服务的实测 |
| E-07 | `work/comment-voice-verify-20261020/response-review/`：独立 6 类回执/发布模型复核 | `Comment.smali` 与 `X/0y7U`、`0yFq`、`0yFs`、`0yGo`、`0yH0`；各文件摘要见同目录 `class-hashes.json` |

原 APK SHA-256：`3983779738e1804a83e783ca447069e13f7686a30213eebd32196915f601a0e8`。

E-07 是独立补充范围，不替代 E-04 的 22 类原 DEX 核验。`findings.json` 本轮仅修订 `format_and_wave.finding` 的成功回执证据边界，原 22 类核验基础保留；更新后的文件摘要见文末。

`CommentAudioStruct` 已包含在 E-04 的原 22 类中：`original/com/ss/android/ugc/aweme/comment/model/CommentAudioStruct.smali:23/41` 分别声明音频标识 `a:String` 与时长 `e:Long`，`:611/842` 对应时长与标识访问方法。本轮利用该既有契约补充诊断，不再将它另计为新增核验类。

## 先按版本和阶段区分失败

| 日志版本 | 语音提交 | 明确失败 | 旧模块判为 `sent` | 无回执 | 文本回退成功 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 0.1.99 | 22 | 20 | 1 | 1 | 6 |
| 0.1.100 | 15 | 15 | 0 | 0 | 5 |

0.1.100 的 15 次失败对应 5 份已准备音频，每份 3 次提交；不能当成 15 次独立合成。0.1.99 的一条无回执记录只能归为未知，不能补算为失败或成功。0.1.97 和 0.1.98 中存在生成阶段 HTTP 402，属于更早版本的独立证据；本轮 0.1.100 已进入宿主提交的样本不能用旧 402 解释。（E-01）

0.1.99 唯一被旧模块判为 `sent` 的语音提交使用 **28.260 秒、453,876 字节**音频；最短的明确失败样本为 **31.320 秒、502,836 字节**。旧 ACK 检查只覆盖状态码、评论 CID 与作品 AID 条件，没有检查 `response.comment.commentAudio`，没有证明该评论实际携带音频或可播放。固定码率使时长与大小近乎同步变化，且不同样本的作品语音权限、服务端状态等没有保持一致。这组数据不能单独验证 30 秒、512 KiB 或另一文件大小阈值，也不能据该条 `sent` 排除 MP3、空 `wave` 等因素；它们与失败的因果关系仍未知。（E-01、E-04、E-07）

## 录音、上传和发布的证据链

以下均为限定到所列原始 Java/DEX 支路的静态结论，严重性为 `n/a_re`。

| 发现 | 状态 / 置信度 | 位置与证据 | 结论及剩余边界 |
| --- | --- | --- | --- |
| F-01 录音默认值 | 已验证 / 高 | E-04，`original/X/0yaR.smali:114`、`X/0yaE.smali:1434`、`X/0yJf.smali:561` | 默认最短 1 秒、最长 30 秒和阈值 25 秒用于录音器；不能据此推导上传上限 |
| F-02 时长单位 | 已验证 / 高 | E-04，`X/0yaE.smali:1702`、`X/1USW.smali:115`、`X/0yHx.smali:51`、`CommentPublishRequest.smali:3588`、`CommentRealApi.smali:627` | 录音回调使用毫秒差值或媒体时长 Long，模型保留该数值，`audio_duration` 接收同一 Long；模块使用毫秒与此路径一致 |
| F-03 发布检查范围 | 有界路径已验证 / 高 | E-04，`CommentPublishRequest.smali:7234/7494/7925`、`X/0yG1.smali:236` | 跟踪方法检查普通评论权限，并在本地音频路径存在时进入上传；此 Java 段未找到 30 秒硬比较，不代表其它层不存在检查 |
| F-04 作品语音入口权限 | UI 条件已验证 / 高；服务端因果未证实 | E-04，`X/0yAP.smali:3049`、`X/0yJf.smali:561` | 正常语音 UI 会根据 `audioCommentPermission` / `blockAudio` 禁用；模块旧自动评论日志未提供相应预检，仍是成功/失败差异中的未排除因素 |
| F-05 格式与波形差异 | 差异已验证 / 高；拒绝因果未知 | E-04，`X/0yaE.smali:2155`、`CommentPublishRequest.smali:3617/3661`；E-07 | 宿主录音输出 AAC-LC、44.1 kHz、单声道、96 kbps、M4A，并拼接波形；模块使用原 TTS 文件与空波形。唯一旧 `sent` 未核对音频响应结构，不能据此排除 MP3 或空 `wave` 对失败的影响，也未证明它们就是原因 |
| F-06 上传分片 | Java 配置已验证 / 高 | E-04，`ASLambdaS28S1301000_35.smali:3021/3117/3194` | 512 KiB 是上传分片配置，不能当作整文件上限；该段未找到扩展名、总大小或 30 秒硬限制，native SDK 与服务端尚未验证 |
| F-07 错误码遗漏 | 模块诊断问题已确认 / 高 | E-04，`ApiServerException.smali:2`、`X/1KNh.smali:7/184` | `ApiServerException` 继承数值字段 `errorCode` 与混淆方法 `LIZ()I`，旧诊断未读该字段。0.2.1 增补受控数值读取；实际失败码须新版手机日志验证 |
| F-08 失败回调共用 | 已验证 / 高 | E-04，`Y/AObjectS313S0200000_35.smali:2368/2388`、`Y/AObjectS814S0100000_35.smali:7095`、`X/0yHR.smali:43` | 上传/鉴权失败与发布失败可进入同一失败回调，旧 `ApiServerException` 类名不足以定位阶段 |
| F-09 语音成功证据不足 | ACK 检查范围已验证 / 高；音频存在与播放未知 | E-01、E-07，`response-review/com/ss/android/ugc/aweme/comment/model/Comment.smali:119/4242`，模块 `NativeCommentPublisher.validResponse/responseStatus` | 旧 `sent` 依据状态码为 0、非空 CID 及 AID 缺省或匹配等条件；未读 `response.comment.commentAudio`。新增音频结构诊断与原有 ACK 分开记录，不将结构存在、上传阶段或普通评论回执等同于音频可播放 |

P-01（`path_type=callflow`）：录音设置与回调（E-04 / F-01、F-02）→ 携带本地路径的评论请求检查（E-04 / F-03）→ 上传鉴权及 native uploader（E-04 / F-06）→ 上传成功先写入音频结果标识，再启动发布（E-04 / F-08）→ `audio_duration` 和 `audio_wave` 进入发布请求（E-04 / F-02、F-05）→ 共用回执（E-01、E-04 / F-07、F-08）。标识仅用于判断阶段，不写入新日志。

本轮没有证明 native SDK 或服务端的允许时长、大小、编码及风控策略。已撤回把录音默认值提升为发布硬限制的方案；自动评论与评论回复保留完整正文、完整音频，不据此裁剪、自动加速或额外合成。录音文件替换入口现有 30 秒保护属于该模块入口的单独政策，私聊/群聊的 120 秒限制也不外推为评论上传规则。

## “慢”由哪些阶段组成

0.1.100 的 5 条完整流程如下；秒数来自 E-02，是这些样本的观察值，不是性能基准或 P50/P95。

| 开始时间 | 生成与准备 | 准备完成至首提 | 语音重试及回执 | 文本回退 | 总耗时 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 19:38:01.281 | 12.152 | 8.296 | 13.246 | 3.838 | 37.532 |
| 19:42:46.502 | 11.356 | 9.152 | 14.632 | 4.379 | 39.519 |
| 19:43:29.147 | 14.935 | 8.453 | 11.411 | 3.853 | 38.652 |
| 19:44:11.005 | 17.260 | 8.320 | 13.945 | 3.800 | 43.325 |
| 19:44:57.380 | 8.451 | 8.248 | 11.361 | 3.900 | 31.960 |

“生成与准备”不是单独的 MiniMax API 耗时，准备完成至首提也包含调度开销。真实备份没有保存 `comment_notice_seconds` / `reply_notice_seconds`，此前默认 8 秒与这些样本的等待相符；0.2.1 将缺省值改为 3 秒，已有保存值不变，AI 的 `reasoningEffort=max` 等用户选项不自动降低。不能把省去的默认等待当成对生成、上传或网络回执耗时的保证。

E-03 中 0.1.100 的 6 次评论回复扫描各耗时约 **8.807–13.079 秒**，不能全归因于 AI。新版对已完整读取成功的“我的评论”窗口做最长 60 秒的有限复用；命中仍读取一页新鲜头页和新的线程，头页不延长 TTL。账号/任务有效性隔离、失败或重复游标不晋升、反证撤销优先等条件保留。新增 `own_ms`、`thread_ms`、`lookup_ms` 和 `cache=refresh|fresh_head`，可进一步分清回源与查找；这些指标可能覆盖嵌套阶段，不应直接相加。手机上的速度改善仍待新日志验证。

## 备份为何不能直接判定“已修复”

用户提供文件为 **22,559 字节**；本地 JVM 预览在 **SDK 35 / Robolectric 4.14.1** 环境下解析出 **97 项配置、2 条分析数据**，`MERGE` 和 `REPLACE` 均通过（E-05）。这支持“该输入能被当前本地解析器与预览流程接受”，不支持“Android 16 真机上的 URI、文件读取、运行环境或写入报错已复现并修复”。没有为此降低导入校验、跳过密钥保护或覆盖原文件。

新版仍先预览，再由用户确认应用；失败提示及 `ConfigTransfer failed: stage=...; error=...` 仅提供受控阶段和异常类型，不写 JSON 内容或异常原文。成功读取仅记录 `bytes` 和 SHA-256，可确认手机实际读到的内容是否与本地输入一致，不记录文件名或 URI。用户文件摘要为 `02D2F41E35B5698A6E6EE54E034FBB6B18572F3E40950BCA6A53872BC2FF3FD3`。遇到问题按阶段排查：`read_file` 核对系统文件选择器及本地可读性，`decode_file` 核对原始 UTF-8 文件，`validate_data` 核对完整备份结构/版本/配置，`apply_data` 核对应用配置阶段。实际设备还须用新的阶段日志确认原因。

这些备份诊断由 `ConfigTransferDialog` 使用 `android.util.Log`、标签 `DouAi` 写入 Logcat：读取成功为 `bytes` / `sha256`，失败为 `stage` / `error`（异常类型）。LSPosed 导出的日志是否包含这些记录，取决于采集范围，不能保证模块日志导出一定包含 Logcat。用户可先反馈新版界面显示的分阶段错误提示原文，无需为排查上传密钥或完整 JSON。

## MiniMax 速率与错误应怎样判断

本次查阅的国际[速率限制页](https://platform.minimax.io/docs/guides/rate-limits)在 T2A 表中列出 speech-2.8、2.6、02 的 turbo/hd 为 **60 RPM**，且说明账户限制依模型、接口而异。此表不能当作中国区所有账户、代理端点或自定义服务的固定额度；也不等于一段音频的长度或字数上限。未用真实账户测量本次配额。（E-06）

按国际[错误码表](https://platform.minimax.io/docs/api-reference/errorcode)，1002 对应请求频率限制，1039 对应 Token 限制，1008 对应余额不足，1004/2049 对应认证，2013/20132 对应参数或音色等输入问题，2042 对应音色访问权限。应以本次请求的业务码或 HTTP 状态定位，不能把旧日志中的 402、一次成功后的宿主失败、或所有语音问题都归为服务限流。（E-01、E-06）

上述表只用于 MiniMax 语音准备阶段的 `providerCode`。抖音上传或发布回执中的 `code` 属于宿主错误码体系，数值相同也不能套用 MiniMax 的解释；`httpCode` 则是 HTTP 状态码。语音准备的 `stage`、宿主的 `audio_phase` 与备份 `ConfigTransfer` 的 `stage` 也分别描述各自流程，须先识别日志入口再解释字段。

本次 0.1.100 样本中已完成音频准备并提交宿主，明确失败发生在之后的宿主链路；缺少的新数值码与阶段使鉴权、上传、发布之间尚不能细分。MiniMax 目录刷新与音色列表请求也不同于实际合成：新版仅把刷新放到对应选择器中，打开缓存不自动调用任何服务。

### 0.2.2 补充核对：中国区与国际站

2026-10-20 再核对当前官方页面，补充中国区分档；上面的 E-06 仍保留其国际站来源与原核对范围，不把国际值改写为所有地区通用额度。对照记录见 `work/minimax-rate-review-20261020.md`。

| 官方来源 | 语音接口与模型范围 | 页面公开值 | 使用边界 |
| --- | --- | --- | --- |
| [中国区速率限制](https://platform.minimax.cn/docs/guides/rate-limits) | T2A v2，speech-2.8 / 2.6 / 02 的 hd、turbo | 免费用户 10 RPM；充值用户 20 RPM | 随模型、接口与账户类型实施，主账号和子账号共享；未给 T2A 的具体 TPM/CONN 数值 |
| [国际站速率限制](https://platform.minimax.io/docs/guides/rate-limits) | T2A，同系列 hd、turbo | 60 RPM | 该表未拆免费/充值档；T2A 的 CONN、TPM 为破折号，表示未列数值，不能解释为无限并发或无限 Token |

两处公开表不能合并为一个通用额度，也不能据备份或本地日志推断用户的私人账户档位、代理额度或自定义服务限制。以下为 MiniMax [中国区错误码表](https://platform.minimax.cn/docs/api-reference/errorcode)与[国际站错误码表](https://platform.minimax.io/docs/api-reference/errorcode)中的对应类别：

| `providerCode` | 官方类别 |
| --- | --- |
| 1002 | 请求频率超限 / rate limit |
| 1039 | Token 限制；不能由此推导 T2A 的具体 Token 配额 |
| 1041 | 连接数限制 / conn limit |
| 2045 | 请求频率增长超限 / rate growth limit |

这些限额页和错误码表没有给出上述业务码与 HTTP 429 的一一映射；应分别记录 `providerCode` 与 `httpCode`，不能仅凭一个值补造另一个。本次 0.2.1 的三份音频已经生成，随后九次抖音提交均为宿主 `code=5; audio_phase=publish`，不能用 MiniMax 的限流错误表解释原生 5，也不能据公开限额承诺语音成功率。这不等于其它独立的 TTS 请求不会触发供应商限流。

## 输入卡顿调查边界

用户反馈抖音内多个模块输入框打字卡顿。已追踪的普通设置编辑路径保留弹窗草稿，取消不保存，点击保存后才写入宿主本地配置并合并通知；未发现逐键同步磁盘提交、逐键远程 Binder 或全量重装 Hook 的调用链。`ConfigSecrets` 参与备份导入/导出，不在普通键入链路中。

共同 UI 路径是 Miuix 可见输入与隐藏 `EditText` 的桥接。原 `FormInput.applyValue` 在每次文本变化时调用 `Editable.replace(0, 全长, 新全文)`，同时还有状态与光标回填。已通过真实可见输入路径锁定并修复 3 条行为，证据归档为 `work/evidence/input-bridge-red-0.2.1/` 与 `work/evidence/input-bridge-green-0.2.1/`：

| 场景 | 修改前的实测红结果 | 局部编辑后的回归要求 |
| --- | --- | --- |
| 4,097 个 UTF-16 单元中间插入 1 字 | watcher 变化范围为 `(start=0, before=4097, count=4098)` | `(2048, 0, 1)`，删除该字也只报告局部删除；这是替换范围变化，不是耗时测量 |
| “前后”、长度上限 6、中间插入 `abcdef` | 得到“前abcde”，错误裁掉未编辑的后缀“后” | 保留“前abcd后”，光标停在 5；文本已满上限时拒绝插入，不用裁尾给新字符腾位 |
| 选中“前😀后”中的 emoji 换成“😁” | 整串替换范围为 `(0, 4, 4)` | 完整代理对一次替换 `(1, 2, 2)`，不拆 UTF-16 代理对 |

共用 `applyNativeTextEdit` 优先尊重原选择区，必要时进行代理对安全的差异编辑；继续交给原生过滤器处理，再按实际结果映射光标与 `TextFieldValue.composition`。普通表单、提示词与聊天输入均复用该桥，保留 watcher 反馈保护，不增加逐键配置写入，不改变取消和未保存语义。

提示词和聊天输入另有 3 条实际红测闭环，红测归档在 `work/evidence/editor-input-red-0.2.1/`，最终绿测在 `work/evidence/editor-input-final-green-0.2.1/`：

| 场景 | 修改前的实测红结果 | 修复后的实测结果 |
| --- | --- | --- |
| 提示词、聊天各在 2,002 个 UTF-16 单元的第 2 位插入“中间” | 两者均全文替换 `(0, 2002, 2004)` | 均只替换 `(2, 0, 2)`，原生光标停在 4，未编辑区域的 span 保留；提示词输入和取消不保存，聊天输入不发送 |
| 已有 4 行聊天记录时编辑草稿 | 每次编辑触发历史适配器 `getView` 4 次 | 同一测试中为 0 次；可见编辑期间仅更新输入与发送/附件控件状态，其他刷新仍保留完整历史读取 |

独立复核还补充了 2 条 native watcher 边界红测：watcher 用 `setText` 更换 `Editable` 后，旧桥继续访问旧缓冲区会越界；watcher 改写未编辑区域后，旧桥会覆盖它选择的光标位置。修复后重读最新缓冲区，发现 watcher 接管文本时尊重其原生选区并清除旧组合范围。这两项直接 helper 回归不代表现有模块 watcher 已在用户设备触发同样行为，也不作为现场卡顿原因。红测归档在 `work/evidence/input-watcher-red-0.2.1/`。

最终输入专项 `MiuixEditorsInteractionTest` **16 项**、`MiuixFormControlsTest` **10 项**，合计 **26 项通过、0 失败、0 错误、0 跳过**；XML 保存在 `work/evidence/editor-input-final-green-0.2.1/testDebugUnitTest/`。这覆盖上述 2 条 watcher 红测闭环、既有发送、重试、附件、取消与布局交互，也验证了局部编辑及不重复物化历史的操作次数，未测量逐键耗时。

组合输入当前仅完成 helper 的 `TextFieldValue` 范围一致性检查，包括预编辑文本更新、结束组合及过滤后范围映射；原平台 `InputConnection` 探针在测试环境没有 Android 焦点，已撤下，不能当成生产 IME 故障或真实键盘通过的证据。现有源码及实际 Miuix 0.9.4 依赖检查未证明逐键 `restartInput` 或窗口重设大小。

用户日志包里的旧 ANR 主线程摘要见 `work/diagnostics-20261020-195230/anr-sanitized-main-summary.json`；相关记录早于本次输入反馈，主线程位于消息队列等待，不能用于证明当前按键卡顿。仍未取得实际手机的逐键耗时或帧耗时，不能把局部替换范围从全文缩为单字称为已测得物理提速，也不能宣布现场卡顿已解决。

## 下一次日志可以核对什么

`audio_receipt` 的观测点是宿主 `chain.proceed()` 之后投递到主线程时的回调对象，未捕获原始 HTTP 响应，也不保证对象在宿主处理期间未被修改。因此该字段只描述观察时的元数据；即使为 `present`，也不能证明服务端媒体校验通过或音频可播放。

本地语音诊断专项 **36 项通过、0 失败、0 错误、0 跳过**：`NativeCommentPublisherResponseTest` 6 项、`NativeCommentReplyPublisherTest` 19 项、`SpeechPreparationTest` 11 项，XML 归档在 `work/evidence/audio-diagnostics-green-0.2.1/testDebugUnitTest/`。这是模拟回执和语音准备路径的本地回归，未调用真实 MiniMax 账户或在手机发送评论，不能据此确定现场失败原因。

| 入口 | 新受控字段 | 能回答的问题与边界 |
| --- | --- | --- |
| 语音准备失败 | `stage=synthesis\|encoding`、`reason`、`sourceMillis`、`encodedMillis`、异常类名；存在时追加数字 `providerCode` / `httpCode` | 区分 MiniMax 合成/API 与本地编码失败；`providerCode` 属于 MiniMax，`httpCode` 属于 HTTP；不输出 URL、Key、正文或服务端原文 |
| 宿主语音评论回执 | `response=<类名>`、存在时的数值 `code`、`audio_phase`、`audio_receipt` | `code` 属于抖音宿主。`upload_or_auth` 表示尚无已验证的上传结果标识，`publish` 表示已取得标识后进入发布支路，阶段 `unknown` 表示信息不足。`audio_receipt=present\|missing\|unknown` 观察宿主回调对象的 `response.comment.commentAudio`：`present` 表示契约可读、标识非空且时长为正；`missing` 表示可读但结构缺失、标识为空或时长无效；`unknown` 表示结构契约不足。它不改变原 ACK 语义；`publish` 不证明发送成功，文字回执不追加音频诊断 |
| 评论回复回源/线程 | `own_ms`、`thread_ms`、`lookup_ms`、`cache` | 区分读取与查找，并确认有限缓存是否命中；不能仅凭查询次数减少声称真机性能已提升 |
| 备份操作 | `ConfigTransfer` 前缀；成功读取的 `bytes` / `sha256`；失败的 `stage`、异常类名 | 属于备份流程，与语音准备的 `stage` 分开解释；对照实际读取内容，定位读文件、解码、校验或应用阶段；本地可解析不替代手机复现 |

用户只需保留对应失败前后的新版本受控日志及任务状态，不必提供 Key、完整请求、正文或媒体地址。缺少回执继续按未知处理，先核对实际评论区，不能自动重复发送来“验证成功”。

0.2.1 / 102 已完成最终严格本地验收：**177 个套件、1877 次测试执行**（1868 个源码测试方法及 9 次额外 SDK 变体执行），0 失败、0 错误、0 跳过；验收工具 34 项通过。正式 Release 构建、原长期证书 v3 签名及证据完整性复核通过，Lint 为 0 错误、56 警告，增量预算通过。清单为 `artifacts/release-verification/20261020T134910Z-06e62a14/manifest.json`，状态 `verified-local`、`published=false`；完整 JUnit、Lint 与静态锚点证据保留在同目录。

最终统一归档 `artifacts/DouAi-0.2.1-release.apk` 为 **4,583,583 字节**，SHA-256 为 `D0744E3DE1D898BDC4D2B03A674726BF50C9FD40B94006FB3712C51D2387C5E3`，与严格验收 APK 一致。最终包的 `zipalign -c -P 16 4` 通过，日志独立保存在 `artifacts/DouAi-0.2.1-zipalign.txt`，未纳入上述封存清单；产物 JSON、摘要及签名证书见 [0.2.1 本地记录](release-0.2.1.md)。

本轮未进行真机或真实账户调用；语音真正拒绝原因仍待新版设备日志，备份设备故障未复现，输入物理帧耗时与真实 IME 未验。完整本地通过不改变这些证据边界，也不代表已经远端发布。

## 本地证据完整性

| 文件 | SHA-256 |
| --- | --- |
| E-04 `findings.json` | `AA61597E5B458F7871E554EC5B54E3E9CA5E49A03C5C063A0C3DBC2CEFED7836` |
| E-07 `response-review/class-hashes.json` | `0A08B2D924B7DB177E78A8453C0367C0076172D4B42E614482C59969E5B1944F` |
| E-01 `comment-audio-evidence-summary.json` | `79B5722B9DB55A3FCF82883E2C37FD1EF6371BBC5F89F14018867C7C7E73F3C2` |
| E-02 `comment-audio-current-timeline.json` | `71E4872D4513C2BCFD390D0FECCF1D84988620FE4E7398F48B0C935AED6E34D5` |
| E-03 `comment-reply-scan-timeline.json` | `CAE57B02253CA117BEB49A19D6D3D9117658F7C304BA7F6F36258317D2D0AA2F` |

在持有对应本地材料的仓库根目录，可只读核对：

```powershell
Get-Content 'work/comment-voice-verify-20261020/findings.json' -Raw | ConvertFrom-Json
Get-FileHash 'work/comment-voice-verify-20261020/findings.json',
  'work/comment-voice-verify-20261020/response-review/class-hashes.json',
  'work/diagnostics-20261020-195230/comment-audio-evidence-summary.json',
  'work/diagnostics-20261020-195230/comment-audio-current-timeline.json',
  'work/diagnostics-20261020-195230/comment-reply-scan-timeline.json' -Algorithm SHA256
```

这些摘要固定的是本次本地证据，不代表最终 APK、运行设备状态或外部文档未来保持不变。

## 0.2.2：设备复验与安全诊断

以下是 0.2.1 本地产物交付后的新增用户反馈与 0.2.2 / 103 工作记录，不改写前文 0.2.1 的验收事实。用户确认：原完整备份在手机合并/覆盖均失败，配置专用派生文件也仍显示校验失败，新导出后一分钟内立即导入仍失败。本地至今没有复现相同设备故障，不能把只在合并时出现的残留配置冲突或分析时间戳边界当成已查实的共同原因。

原文件 **22,559 字节**，SHA-256 为 `02D2F41E35B5698A6E6EE54E034FBB6B18572F3E40950BCA6A53872BC2FF3FD3`，保持未修改。忽略目录中的配置专用文件为 **24,849 字节**，SHA-256 为 `0942D57A90C658B490184373930A8F414A670D358C8B33B76525D8DB2F40BDFD`；递归比较确认它与原 `config` 的字段、数组和值完全相等，只剥离外层分析封装，大小差异来自排版。两份私有输入均在本地两模式通过预览，配置专用文件的默认 ContentResolver 读取、生产选择回调、原生及 HostComposeContext/ModuleTheme 预览还核对了 97 项配置、分析恢复选项隐藏、没有写入配置；这些通过不能代替用户手机结果。

`work/backup-runtime-review-20261020/findings.md` 保存本轮 AOSP、0.2.1 发布 DEX 与独立 probe 复核。已下载的 Android 15/16 `JSONObject`、`JSONArray`、`JsonReader` 三组对应源文件摘要一致；官方 Android 16 入口为 [JSONObject](https://android.googlesource.com/platform/libcore/+/refs/heads/android16-release/json/src/main/java/org/json/JSONObject.java)、[JSONArray](https://android.googlesource.com/platform/libcore/+/refs/heads/android16-release/json/src/main/java/org/json/JSONArray.java)、[JsonReader](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/util/JsonReader.java)，Android 15 对照链接及下载摘要见该记录。DEX 所检分支仍有 `Long.parseLong`、`Long.valueOf` 和对应版本/类型检查，独立 JVM probe 的原完整文件两模式、配置重序列化回读及多时区分析检查通过。未发现能解释现场错误的数字装箱不对称；这不是设备执行原 DEX 的证据，也没有排除全部环境差异。捕获时间领先当前时钟约五分钟会被拒绝的边界已验证，但不含分析数据的配置专用文件也在设备失败，不能据此推定共同根因。

0.2.2 的本轮备份改动仅增加安全分段诊断，未放宽原 JSON、类型、结构、密钥、MiniMax 组合或分析校验，也未改变确认写入和取消边界。界面增加 `BUNDLE_JSON` / `BUNDLE_HEADER` / `CONFIG` / `ANALYTICS` 区段、固定原因码、白名单字段和固定 `FailureKind`；未知键不回显，不保留原 cause、消息、stack 或配置值。实际读取的字节数与 SHA-256 前 12 位直接显示在界面，完整摘要和受控诊断写入 `DouAi` 标签 Logcat；LSPosed 导出是否采集它们取决于采集范围。用户可先反馈界面提示与短摘要，无需提供密钥或完整 JSON。

诊断先有 **9 条实际红测**，归档 `work/evidence/backup-validation-diagnostics-red-0.2.1/`；随后专项 **10 个套件、99 项通过，0 失败、0 错误、0 跳过**，其中 89 项永久回归、10 项私有输入证据测试，归档 `work/evidence/backup-validation-diagnostics-green-0.2.2/testDebugUnitTest/`。范围包括完整/配置专用输入、当前 HostSettingsStore/SettingsRepository 快照、配置与分析各校验层、预览/确认/取消及隐私断言；私有输入测试不进入最终源码完整回归。

本版最终完整回归、签名 APK、Lint 与对齐已完成本地严格验收，结果见本文末段和 [0.2.2 本地记录](release-0.2.2.md)。本轮代理未连接手机或调用真实账户；手机失败来自用户反馈，根因和本版设备恢复尚未验证。语音真正拒绝原因、输入真实 IME 与物理帧耗时等原有边界保持，不宣称已解决所有故障。

### 新增日志：版本加载、语音失败与备份采集边界

新增 ZIP SHA-256 为 `BC0EA4A796F6A7C541E18591BC5840BE96AC501AA523283926E7497CCC3E8D87`。`log/modules_2026-10-20T22:05:19.564841.log:2563` 确认 **22:16:43.747 已在抖音主进程加载 0.2.1 / API 102**，之后另有 3 次同版本加载。该文件早段仍是 0.1.100，不能仅按文件名或导出时间归属版本。新旧 modules/verbose 镜像先去重，再按加载记录划分；受控结果见 `work/voice-log-review-20261020-225123/controlled-summary.json` 与 `review.md`。

| 本次 0.2.1 样本 | 已确认结果 |
| --- | --- |
| 独立语音与原生提交 | 3 份独立生成、9 次提交，全部失败；9 次均为宿主 `code=5; audio_phase=publish` |
| 文本回退 | 3 次 `sent`；任务终态不能算作语音发送成功 |
| 新版语音成功与响应结构 | 没有语音 `sent`，也未触发成功音频回调的 `audio_receipt`；字段缺席不等于 `missing` |
| 约 21 分钟空窗 | 第三个任务第三次语音失败至决定文本回退为 1278.388 秒；原因未知，不归入 AI、TTS 或上传耗时 |
| 备份受控记录 | 这份 ZIP 的普通日志、压缩日志及 `full.log` 均无 `ConfigTransfer` 读取/失败行，不能比较设备读取 bytes/SHA 或内部错误 |

失败行号为 3309/3315/3321、3931/3937/3948、4816/4822/4829；文本 `sent` 为 3350、3954、4885。新版诊断已证明九次失败处于模型已取得上传结果标识的 `publish` 支路，但没有证明标识对应的媒体可播放或服务端已接受格式、波形、权限及其它参数。关联的 `full.log` 提交窗口没有找到服务器错误原文；code 5 的业务含义仍未知，不能套用 MiniMax 的 `providerCode` 解释。这批样本没有显示语音成功率改善。

没有 `ConfigTransfer` 不等于没有执行导入；这些诊断使用 `android.util.Log`，这份 LSPosed 导出的采集范围没有提供对应记录。手机备份故障仍按用户反馈保留，等待 0.2.2 界面的区段、原因、`FailureKind` 和短摘要进一步定位。

0.2.2 为后续复验增加受控语音线索，仅对模块持有的语音失败回调输出：

| 字段 | 取值与边界 |
| --- | --- |
| `audio_hint` | 从严格核验的宿主 `ApiServerException` 提示 getter 读取最多 2048 字符，优先使用非空 prompt，排除 HTTP(S) URL、Bearer/Basic 字段后分类。只输出固定类别 `duration`、`size`、`format`、`permission`、`frequency`、`parameter`、`content`，多项用 `+`；契约不足、空文本、未命中分别为 `unknown`、`empty`、`other`，不输出原文或 Throwable 消息 |
| `audio_permission` | 发送前已核验作品的 `audioCommentPermission` 整数快照，字段缺失或类型不符为 `unknown`；不把任意非零值擅自解释为允许 |
| `audio_block` | 同一作品的 `blockAudio` 布尔快照，不足为 `unknown` |

这些字段不用于改变发送 gate、ACK、重试或回退，也不是对宿主 code 5 的解码。提示类别最多说明宿主文字涉及某一主题，不证明时长、体积、格式或权限就是实际拒绝原因；没有成功率改善承诺。3 项针对性回归先有 2 项实际失败，归档 `work/evidence/voice-hint-permission-red-0.2.2/`；随后 5 个相邻套件、179 项通过且零失败/错误/跳过，归档 `work/evidence/voice-hint-permission-green-0.2.2/`。该专项不替代本版最终完整验收或设备复验，最终本地结果见本文末段。

### 报告内结果与图片的本地验证

报告底部已直接展示完整可复制的 AI 结果，图片分享在本地记录全部统计区块、图表、边界说明和已有分析；没有结果时明确注明尚无 AI 分析，不再次发起请求。图片只绘制报告容器，不额外复制“记录说明与自述”配置卡中的原始字段；AI 文字可能引用所填自述。Android 10 及以上保存到 `Pictures/DouAi` 后打开系统分享，Android 9 通过 SAF 选择位置，多页选择文件夹；打开分享面板后的图片保留供接收方读取。

两条需求红测见 `work/evidence/report-image-red-0.2.2/`。报告/选择器/备份等相邻 81 项通过；之后图片专属 13 项已逐项实际通过，其中 12 项图片 helper、1 项长 AI 实际 PNG。`work/evidence/report-image-final-preflight-0.2.2/` 的 12 通过、1 失败与 `work/evidence/report-image-tail-fixture-0.2.2/` 的单项通过共同构成该记录，不能说成同一次 13 项全绿。失败 fixture 原文本换行后超出固定高度，修为 24 条短行并增加 `lineBottom` 装得下的前置断言；生产报告使用 wrap-content，不能把该 fixture 失败当作业务缺陷红测。更早的 qualifier 环境参数错误同样不能计作业务红测。

PNG 与各套 `*-index.json` 在 `artifacts/diagnostics-0.2.2/report-images/`：正式 `final-native-report-{light,dark}` 各 6 页，1080 × 不超过 4096；`final-portrait-report-{light,dark}` 各 9 页，720 × 不超过 2048；`final-forced-page` 为 5 页。索引所列 PNG 均已解码，并选取正式首尾与深色末页目视复核，AI 尾部正文、标记与采集边界保留，无仅余装饰的空末页。旧无 `final-` 前缀的同目录图片不作为最终样例。

这些是 SDK 35 / Robolectric Native Graphics 的本地 fixture 渲染；API 28 单文件/目录测试使用模拟 provider。MediaStore、SAF 清理、只读多 URI ClipData、交接后保留、取消与失败、后台删除及无主线程 MIME 查询均有本地合同覆盖，不能由此宣称真实手机、接收应用读图或设备峰值内存/耗时已验。逐页单张 ARGB 上限约 16.9 MiB，不包括 Picture、布局与编码等其它开销。

预检 Lint 0 错误、57 警告，唯一新增为成功导出提示中的实际页数拼接。对应 `LocalAnalyticsDialog` 的 `SetTextI18n` 预算从 5 增至 6，原因限定该条中文完成状态，未添加整类压制，验收工具 34 项再次通过。最终仍以全部源码冻结后的严格验收清单为准。

### 历史失败与最终严格验收

尝试 `artifacts/release-verification/20261020T145053Z-33b47f8f/` 的 Gradle 测试和 Lint 任务已完成，Lint 0 错误、56 警告；但增量预算因 `ConfigTransferDialog` 新增 1 条 `SetTextI18n` 失败。同期少了 1 条 `NewerVersionAvailable`，不抵消新问题键；失败状态保留在 `failure.json` 与 `reports/lint-budget.json`。该次整体未通过，没有成功验收清单。随后仅对状态文案追加当前操作文件摘要的这一处组合给出专属预算批准，沿用宿主上下文既有中文文案、本地化仍待处理，不压制整类警告。此后在全部源码冻结后重新验收。

最终目录为 `artifacts/release-verification/20261020T154702Z-66c0c2f3/`，严格入口退出码为 0，`manifest.json` 标记 **`verified-local`、`published=false`**。完整源码回归为 **180 个套件、1921 次执行，0 失败、0 错误、0 跳过**；验收工具 34 项通过。正式 R8 构建、原长期证书 v3 签名及 `zipalign -c -P 16 4` 通过；最终 Lint 为 **0 错误、58 警告**，预算全部匹配，无新增/减少项。此前预检为 57 警告，差异是旧 `MockWebServer` 升级提示在最终运行重现，不作为新的业务缺陷。

最终 0.2.2 / 103 APK 为 **4,616,355 字节**，SHA-256 为 `03FFA3B781A5FAF5721CC0A7AA688E451BAB05200A0FCE160D143D578860E182`；证书 SHA-256 为 `FAE67C134F4C4FA73B8E34487C47A8C7F3E7C34B840BC245AFAC51585B7DFD47`。清单绑定 479 项证据，包括 `reports/report-images/` 的本轮 **35 张 PNG、5 份索引和 `sha256.json`（共 41 项）**、8 个 DEX 锚点的 `artifact-inspection.json`、`zipalign-result.json` 及原始对齐日志。图片封存的是此次完整回归生成的本地 fixture，静态锚点不等于真实设备执行；旧临时产物不作为交付依据。

统一归档 `artifacts/DouAi-0.2.2-release.apk` 及其 JSON、SHA256SUMS、对齐记录已保存，大小与哈希和清单一致，完整性 CLI 复核通过。验证日志中的 SDK 35 线程异常前缀已用主动抛出 `OutputStream IOException` 的 PNG 负例独立复现：1 项通过、进程退出码 0，重试 PNG 解码有效，见 `work/evidence/png-stream-exception-prefix-0.2.2/summary.json`。对应 [AOSP 流适配器](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/libs/hwui/jni/CreateJavaOutputStreamAdaptor.cpp) 在 JNI 写流异常后描述并清除异常、返回失败，以及 [OpenJDK 21](https://github.com/openjdk/jdk21u/blob/master/src/hotspot/share/prims/jni.cpp#L534-L558) 将原生前缀与 Java 栈分流输出；这是该测试负例的诊断，不是手机崩溃证据。

本版没有远端提交或发布，真机与真实账户调用未执行。报告接收应用、手机相册及设备内存/耗时仍未验；备份与语音的现场根因、输入真实 IME/FPS 等边界没有被本地通过结果消除。

## 2026-10-21：0.2.2 现场细分与 0.2.3 兼容修正

本节是 0.2.2 交付后的新增定位，不改写前文各版当时的未知状态或本地验收事实。用户手机新提示为 `CONFIG/MERGED_SETTINGS; field=tts_provider; failureKind=ARGUMENT`，读取 `24849` 字节，短摘要 `0942D57A90C6`，与原配置专用文件一致。字段指向合并过程正在处理的设置，不等于其语音来源值本身无效。

只读追踪确认：`ConfigTransfer.inspect` 的字符串端点检查调用 `sameEndpoint`，`tts_provider` 对应 `tts_get_template`；该键缺失时，经默认 GET 模板执行 `redactText → redactUrl → placeholder`。含密钥、合并或覆盖都仍会经过该调用。`ConfigSecrets.redactEndpoint` 和 `placeholder` 的同一模式 `\{[a-zA-Z_][a-zA-Z0-9_]*}` 未转义最后的右花括号，触发了 Android 与 JDK 的正则语法差异。

[Android 官方 Pattern 文档](https://developer.android.com/reference/java/util/regex/Pattern)明确说明 Android 使用 ICU4C，字面右花括号必须转义。本机 ICU4C 72.1.0.4 编译从源码提取的旧模式返回 `U_REGEX_RULE_SYNTAX`（66305），补齐 `\}` 的候选通过编译；记录在 `work/backup-icu-review-20261021/findings.md` 与 `icu-source-regex-results.json`。此前 JDK/Robolectric 可接受该模式的结果不能覆盖 Android ICU 行为。

该机制与现场的合并阶段、字段及异常类别吻合。修正覆盖两处：占位符判断的异常会直接沿默认模板路径失败，端点规范化中的异常虽被保护捕获，也会使正常端点被误判为省略内容。**0.2.3 / 104** 已只补齐这两处字面右花括号转义，保持原占位符字符集合、配置校验及凭据服务隔离，不改变用户文件或值；手机使用同一文件的恢复仍待新包复验，不能把本机 ICU 复现当作真机修复验收。

原生产源码的原生 ICU 检查退出码 1，两处模式均返回 66305；修后源码退出码 0，两处编译和 16 个合成匹配合同通过，报告为 `work/backup-import-evidence/native-icu-source-{red,green}.json`，含各自源码摘要和 ICU 版本。备份专项 **10 套件、99 项通过，0 失败、0 错误、0 跳过**（89 项永久回归、10 项真实私有输入证据），归档 `work/evidence/backup-icu-fix-0.2.3/`；原完整/配置专用输入的两模式、默认读取到预览通过，预览不确认写入，原文件未改。

检查已固化为 Windows 严格发布入口的 assemble 前门禁 `tools/verify_config_secrets_icu.py`：原生编译或合同失败退出 1，ICU 不可用或源码抽取失败退出 2，均停止且无 JDK 回退，成功报告 `android-icu-placeholder.json` 与日志进入验收封存；普通 Linux CI 不新增 Windows ICU 依赖。验收工具 34 项通过，`clean` 后本轮 `artifacts/release-verification/20261020T162817Z-f39c66a0/` 已实际先通过原生门禁，再进入 assemble/R8，最终严格验收通过，见 [0.2.3 修复记录](release-0.2.3.md)。0.2.2 的本地通过数、签名与未知状态保留其历史事实。

本轮封存 `android-icu-apk-comparison.json`：直接读取 0.2.2 签名 APK 的 DEX，确认旧模式存在、修正模式缺席，该旧模式在本机 ICU 编译返回 66305；0.2.3 最终签名 APK 则确认修正模式存在、旧模式缺席，并在本机 ICU 编译返回 0。正式门禁的源码摘要与 `inputs.json` 相符，旧源码实际红报告存为 `android-icu-placeholder-before.json`，相关门禁、对齐及比较报告均已绑定清单。这是独立的产物字面量与引擎验证，不是设备执行 DEX，也没有证明手机修后恢复。

0.2.3 / 104 最终清单为 `verified-local`、`published=false`，严格入口退出码 0；**180 个套件、1921 次执行，0 失败、0 错误、0 跳过**，验收工具 34 项通过。Release、原长期证书 v3 签名、16 KiB ZIP 对齐与完整性 CLI 复核通过；最终 Lint **0 错误、57 警告**，预算批准 58，无新增，减少的 1 条是 `AndroidGradlePluginVersion` 的 Gradle 9.8.2 升级提示。

统一归档 `artifacts/DouAi-0.2.3-release.apk` 为 **4,616,345 字节**，SHA-256 为 `57DFB2BF23B5EAA58889D4828FC15970FA44285BAA2298DD2D350C6C70FFF415`；证书 SHA-256 为 `FAE67C134F4C4FA73B8E34487C47A8C7F3E7C34B840BC245AFAC51585B7DFD47`。APK 与同目录 JSON、SHA256SUMS、对齐 JSON 均与清单匹配。本轮绑定 **441 项证据**，包含原生 ICU 修前/修后、APK 比较和对齐记录；报告 PNG 没有重复复制进本轮清单，0.2.2 的 479 项封存仍按原范围保留。手机导入恢复、真实账户及设备性能仍未验，没有远端发布。
