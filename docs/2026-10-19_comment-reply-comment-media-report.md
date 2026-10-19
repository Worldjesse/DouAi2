# 评论回复仅使用评论本身的素材

日期：2026-10-19，Asia/Shanghai；普通功能诊断，`flavor=null`。范围见 [scope.md](../work/comment-reply-20261019-104738/scope.md)，过程见 [timeline.md](../work/comment-reply-20261019-104738/timeline.md)。

## 结论与边界

0.1.94 的新日志已定位到具体失败：10:46:47.099 在评论回复的媒体准备阶段抛出 `video_source_unavailable`，当时 `ai_sent=0; generated=0; submitted=0; chars=0`。因此该次失败发生在 AI 请求写出和回复生成之前，悬浮窗直接失败不表示此前已发送抖音回复。日志可以确认原作品视频地址读取失败，不能进一步区分字段缺失与地址不受支持。

用户明确要求只分析评论本身。当前代码在对方评论没有附图时，会回退读取原作品图片、封面和视频，还将作品描述加入提示词；这一额外依赖既不符合本次要求，也正好位于现场失败链路。本轮修正目标是 0.1.95 / versionCode 96：删除评论回复的原作品媒体与描述输入，仅保留本人原评论、对方回复及其附图。原生发布所需作品及评论身份绑定继续保留，不增加作品详情回源 API，也不关闭共享的图片／视频识别设置。

0.1.95 / versionCode 96 已完成本地交付：249 项相关测试与最终归档 APK 的 3 项合同检查全部通过，均零失败、零错误、零跳过。Release / Lint、v3 签名及归档哈希核对通过；Lint 0 错误、63 警告。ADB 无连接设备，实际 AI 请求、生成、原生提交与准确成功回执仍需真机验收，未远端发布。

## 新日志的实际进展

输入为 `F:/qq/MobileFile/LSPosed_20261019_104738.zip`，SHA-256：

```text
3F73B785326CA0D833C6269014BEA38880A904BE1B72BF288C59F3D2CD85E0AF
```

统计仅使用有明确 0.1.94 加载记录的主进程 PID 30307、5449，排除沙箱与初始适配进程。六轮均完成原评与真实子回复查询及 inbox 汇总：前四轮各匹配 8 条，后两轮各匹配 9 条。它们包含重复观察，不能相加成 50 条新回复。每轮仍有 4 个未解决提示，水位保护保持。

| 观察 | 计数 | 含义 |
| --- | --- | --- |
| 评论回复线程匹配及 inbox 完成 | 6 轮 | 已越过早前的宿主 Task 等待问题 |
| 评论回复媒体处理失败 | 1 次 | 本轮明确抛错发生于 `AutomationCommentMedia.videoUrl` |
| 评论回复 AI 写出 / 生成 / 原生 accepted | 0 / 0 / 0 | 没有证据表明该次回复已经发出模型请求或抖音请求 |
| 普通自动评论 native accepted / sent 回调 | 3 / 2 | 属于独立功能；第三次 accepted 位于日志末尾，未见其后回执 |

脱敏阶段证据为：

```text
10:46:47.099 Comment reply generation failed: IllegalArgumentException;
reason=video_source_unavailable;
site=com.douai.module.feature.AutomationCommentMedia.videoUrl:26;
stage=media; ai_sent=0; generated=0; submitted=0; chars=0
```

`site` 行号来自 Release 堆栈位置，不将其当作当前源码的稳定行号。安全计数与每轮数值已保存在 [runtime-safe-stats.json](../work/comment-reply-20261019-104738/runtime-safe-stats.json)。

## 修正的行为范围

评论回复素材选择只读取对方评论的 `image_list`。开启图片识别且评论附图存在时，继续按已适配的原图、下载图等明确 URL 字段读取，并保留媒体取消、时限、域名与数量限制。没有附图时直接使用文字上下文，不再为原作品准备图片或视频。关闭评论图片识别时不提交这些附图。共享视频开关在其他回复场景仍有原有用途，不能因此推断本轮会读取评论没有提供的视频。

提示词和回复历史中的原输入仅由“我的原评论”“对方的回复”及实际附带的评论图片标签组成；不再调用 `work.getDesc()`。共享生成器的生成和确认后记忆共用此输入。AID/rootId 会话隔离、防重、资格过滤、ledger schema4、账号有效性和准确回执不属于本次改动。

`NativeCommentPublisher.sendReply()` 仍使用经验证的作品对象建立原生请求上下文，并检查作品 AID、目标评论及回执。该作品对象用于将回复发往正确评论，不作为 AI 输入。普通自动评论继续遵循自己的作品素材设置。

## 本地验证与安装包

| 检查 | 实际结果 | 证据与边界 |
| --- | --- | --- |
| 素材改动前 RED | 15 项执行，6 失败 | comment-media-red.log、red-junit/AutomationReplyMediaTest；包含同型视频地址异常与原作品读取陷阱 |
| 输入改动前 RED | 4 项执行，4 失败 | comment-input-red.log、red-junit/Hook 与 SharedSettings；当时素材修正已应用，原作品描述仍被读取 |
| 最终相关回归 | 249 项通过，0 失败、0 错误、0 跳过 | focused-tests.log、focused-junit/、focused-totals.json |
| Release / Lint / 签名 | 通过；Lint 0 错误、63 警告；v3 与前版同证书 | release-build.log、lint-results-release.xml、signature.txt |
| 最终 APK 合同 | 3 项通过，0 失败、0 错误、0 跳过 | final-apk-contract.log、final-apk-junit.xml；指定最终归档包 |

九个套件分别为 AutoCommentHook 81、AutomationCommentMedia 11、Dispatcher 24、ReplyMedia 15、CommentReplyHook 55、Ledger 32、SharedSettings 12、Publisher 15、HelpDialog 4。覆盖真实默认素材选择、真实共享客户端的本机请求、稀疏通知回源后的完整发送目标、附图开关／数量／校验／取消、只传评论文本，以及普通自动评论和帮助入口回归。测试使用本机服务与替身，不向在线模型或抖音发出请求。未重跑全量测试或原宿主全部规则扫描，宿主规则与 API 本轮未改动。

| 安装包项目 | 结果 |
| --- | --- |
| 文件 | `artifacts/DouAi-0.1.95-release.apk` |
| 版本 / 包名 | `0.1.95 / 96`，`io.github.yudigaga.douai` |
| 大小 | 4,501,621 字节 |
| SHA-256 | `552DC3AF529ED166EDC154442347767D9488E69ECB8F5E3C656DBB838A03E99B` |
| 证书 SHA-256 | `FAE67C134F4C4FA73B8E34487C47A8C7F3E7C34B840BC245AFAC51585B7DFD47` |
| 归档 | APK 与构建输出哈希相同；同版本 JSON、SHA256SUMS，published=false |

覆盖安装后重启抖音，保持原有开关，用一条新收到的评论回复验收。原先达到三次失败上限的记录不会因本次升级重置，已发送／已提交／结果未知记录也继续保留。若仍失败，依据已保留的分阶段诊断分析新日志。最终修改源码和 Release mapping 已存于 case，不能用本地替身成功代替设备成功。

## Evidence → Finding → Path

case 根为 `work/comment-reply-20261019-104738/`。报告不复制用户账号、评论标识、正文或媒体地址。

| Evidence | source_ref / artifact_path | repro_command | content_hash |
| --- | --- | --- | --- |
| E-001 | 原 ZIP、`log__modules_2026-10-19T10-40-58.993189.log`、`runtime-safe-stats.json` | 下节哈希及筛选命令；原始日志仅本地可用 | ZIP 见上文；module 日志 `0A068EBCF466862BCE2EB41C519FCF880C402C6EBB558AAF2910B6D91B2F478F`；统计 `777FF37086310428F0883818FBD8AA7593D139EA00AC939F97902FD3F6DC95AA` |
| E-002 | 当前对话中用户确认视频作品、只需评论素材及图片／视频均开启 | 当前对话人工复核；无独立离线导出 | n/a，用户范围澄清 |
| E-003 | .94 `AutomationReplyMedia.comment/commentSourceLabel`、`CommentReplyHook.input` 与 `NativeCommentPublisher.sendReply`；case `before-fix/` | 下节源码查询；旧逻辑以 `before-fix/` 保存内容为准 | n/a，联合源码证据 |
| E-004 | .95 修改源码、before-fix、red-junit、focused-junit、最终归档 APK 与 final-apk-junit.xml | 下节相关回归与 APK 合同；case 构建／签名／元数据 | APK 哈希见上表；其余联合证据 n/a |

| Finding | 结论 | severity / category | evidence_ids | confidence / status | location / 限制 |
| --- | --- | --- | --- | --- | --- |
| F-001 | .94 已完成真实子回复匹配，汇总由 8 增至 9 条 | n/a_re / other | E-001 | high / validated | 六轮重复观察，不是每轮全为新事件 |
| F-002 | 本次失败在媒体准备阶段，模型写出、生成及抖音提交均未发生 | n/a_re / other | E-001 | high / validated | `videoUrl`；地址缺失与不受支持未被日志区分 |
| F-003 | 原作品媒体与描述依赖超出用户本轮需要，应从评论回复 AI 输入移除 | n/a_re / design | E-002,E-003 | high / validated，范围与旧路径已核实 | 修正实现、本地检查及设备结果需分别验收 |
| F-004 | .95 移除原作品读取，评论及附图路径通过本地回归与产物校验 | n/a_re / design | E-004 | high / local_validated | 真机生成和成功回执仍待验收 |

P-001，`path_type=callflow`：原通知提示 → 原生本人原评与子回复精确匹配（E-001/F-001）→ .94 在无评论附图时读取原作品视频 → 媒体校验失败，尚未调用模型（E-001,E-003/F-002）→ 按用户范围将素材限定为对方评论附图、上下文限定为双方评论文本（E-002,E-003/F-003）→ 保留原生身份校验，本地回归和归档校验通过（E-004/F-004）。未解决的提示、真实模型及抖音发送结果仍是独立验收边界。

## 离线复核

以下 PowerShell 命令在仓库根执行，仅查询本地证据，不向抖音或模型服务发送请求。筛选输出为固定阶段与数值，不输出评论跳过日志中的身份字段。

```powershell
Get-FileHash -Algorithm SHA256 -LiteralPath 'F:/qq/MobileFile/LSPosed_20261019_104738.zip'
Get-FileHash -Algorithm SHA256 -LiteralPath 'work/comment-reply-20261019-104738/log__modules_2026-10-19T10-40-58.993189.log'
Get-Content -LiteralPath 'work/comment-reply-20261019-104738/runtime-safe-stats.json'
$logPath = 'work/comment-reply-20261019-104738/log__modules_2026-10-19T10-40-58.993189.log'
$sessions = @(Get-Content -LiteralPath $logPath | Where-Object { $_ -match '^\[ [^\]]+\s+10343:\s+(30307|5449):' })
$sessions | Where-Object { $_ -match 'Comment reply (thread lookup:|generation failed:|AI request sent:|generated:|native request accepted)' }
rg -n 'event\.work|event\.targetComment|commentSourceLabel|static String input' app/src/main/java/com/douai/module/feature/AutomationReplyMedia.java app/src/main/java/com/douai/module/feature/CommentReplyHook.java
```

核心行为与最终产物可用以下命令复核；完整九套件结果保存在 focused-junit 中。

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'com.douai.module.feature.AutomationReplyMediaTest' --tests 'com.douai.module.feature.CommentReplyHookTest' --tests 'com.douai.module.feature.CommentReplySharedSettingsTest' --console=plain
$env:DOUAI_VERIFY_RELEASE_APK = (Get-Item -LiteralPath 'artifacts/DouAi-0.1.95-release.apk').FullName
.\gradlew.bat :app:testDebugUnitTest --tests 'com.douai.module.scan.ApkArtifactContractTest' --console=plain
```
