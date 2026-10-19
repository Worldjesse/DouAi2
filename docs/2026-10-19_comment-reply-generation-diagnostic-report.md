# 评论回复生成阶段诊断

日期：2026-10-19，Asia/Shanghai；普通功能诊断，`flavor=null`。范围见 [scope.md](../work/comment-reply-20261019-101042/scope.md)，过程见 [timeline.md](../work/comment-reply-20261019-101042/timeline.md)。前轮宿主 Task 修复见 [0.1.93 报告](2026-10-19_comment-reply-task-await-report.md)。

## 结论与边界

0.1.93 的设备日志已经证明子回复查询和身份匹配可以完成：匹配数由 6 增至 7。随后出现 13 次 `IllegalArgumentException`，分别为 6 次和 7 次；这不是 13 条不同的新回复。当前错误日志没有阶段、原因或异常位置，尚不能确定是媒体准备、模型配置校验、请求构造还是后续生成处理失败。评论回复原生发送接受记录为零。

本轮交付为 0.1.94 / versionCode 95 的诊断增强：增加阶段、固定原因、模块调用位置及 `ai_sent/generated/submitted/chars` 等数值状态，并接入共享 AI 客户端的安全传输日志。**这是一版定位问题的改动，不宣称已修复现场异常。**149 项相关测试及最终归档 APK 的 3 项合同检查通过，均零失败、零错误、零跳过；Release、Lint 和签名检查已完成。

目标保持为别人回复本人在其他作者作品下的评论。用户确认图片和视频识别均开启，因此素材准备属于本轮需区分的阶段；开关开启不代表素材读取成功。

## 现场证据

输入为 `F:/qq/MobileFile/LSPosed_20261019_101042.zip`，SHA-256：

```text
51B60ABA311098A9A4B6CD12BDBC0123C6158D4E4A1229C3313EB27526D65AA9
```

按明确加载 0.1.93 的主进程 PID 22782 隔离旧会话，统计见 [runtime-safe-stats.json](../work/comment-reply-20261019-101042/runtime-safe-stats.json)。

| 时间 | 设备日志事实 | 可以确定的边界 |
| --- | --- | --- |
| 10:06:33.894 | 主进程加载 0.1.93 | 下列记录属于该版本会话 |
| 10:06:47.580 | 本人原评五页、三个根、子回复三页，10 个提示匹配 6 条 | 回源及严格匹配已完成 |
| 10:06:49.605—10:06:49.712 | 6 次生成处理 `IllegalArgumentException` | 固定错误标题不区分内部子阶段 |
| 10:08:45.863 | 同样查询预算，11 个提示匹配 7 条 | 已有可处理事件，不再停在 Task 方法缺失 |
| 10:08:47.886—10:08:48.007 | 7 次同类异常 | 不能仅凭耗时判定准确抛出点 |
| 截至 ZIP 内最新记录 | 评论回复 native request accepted 为 0；自动评论 accepted 为 1 | 两个功能必须分别统计 |

自动评论另有语音合成 HTTP 402、随后文本回退与原生发送接受记录。这属于自动评论链路，不足以解释评论回复异常，也不能当成评论回复已发送。

用户纠正先前描述：回复正文没有生成。历史截图显示原内容已保存、状态失败、模型和生成内容为空，图片请求未记录；截图时间为 **10:14:28**，晚于该 ZIP 约 **10:10** 的终点，因此无法把它与上述某一条异常逐一对应。截图本身没有 AI 请求写出或抖音发送接受的证据。

用户进一步说明悬浮窗没有展示处理过程，出现即失败。`CommentReplyHook.fail` 会直接调用 `AutomationDispatcher.reportFinalFailure`，后者独立保存失败历史并显示失败提示，因此这一界面现象不构成生成完成或请求已发送的证据。

## 源码核查与诊断处理

完整偏好快照经 ConfigurationSnapshot → LocalOptions → CommentReplyHook；`ai_model_settings_v2` 包含在安装和配置变更路径中，未发现配置字段被裁剪。SharedReplyGenerator 与自动评论使用同一 AiModelSettings 和 ChatCompletionClient；不同任务提示词本身不构成错误证据。独立核查见 [independent-audit.md](../work/comment-reply-20261019-101042/independent-audit.md)。

历史中的模型和正文在 generator 返回后才赋值，因此模型为空不能直接推出“没有配置模型”。AI 请求标志由 HTTP 请求体写出后的回调设置；“图片请求未记录”也不能被解读为请求已被服务接受。素材校验、模型配置校验及请求构造均可能产生 `IllegalArgumentException`，当前证据没有唯一定位。

诊断改动将分别标记媒体、输入、模型请求、输出、语音和调度准备阶段，保留真实请求写出、生成完成及提交状态。日志只输出固定枚举、数值及受限模块位置，不输出原始异常消息、评论正文、提示词、URL、请求体、密钥或请求头。发送目标校验、准确回执、防重、资格检查和 ledger schema4 不变；不清空未知提交或已处理记录。

## 验证记录

| 阶段 | 当前状态 | 结论边界 |
| --- | --- | --- |
| 设备日志审计 | 已完成，匹配 6→7、异常 6+7、评论回复接受 0 | 证明识别推进，不证明生成或发送成功 |
| 快照及共享生成器源码核查 | 已完成，未发现模型设置丢失 | 不能据此排除运行时输入或媒体校验异常 |
| 修复前诊断 RED | 3 项实际执行，3 失败 | 均因现有错误日志缺少阶段；保留 red-junit 和 diagnostics-red.log |
| 新诊断专项回归 | 149 项、零失败、零错误、零跳过 | 六套件；含真实共享客户端的本机 HTTP 请求、真实素材校验分支、隐私和阶段标记回归 |
| Release / Lint / 签名 | 构建成功，Lint 0 错误、63 警告；v3 验证通过且证书与前版一致 | release-build.log、lint-results-release.xml、signature.txt |
| 最终 APK 合同 / SHA-256 | 3 项通过，零失败、零错误、零跳过；归档与构建输出哈希相同 | 包含新增诊断方法、日志标记及模块名称的 R8 保留检查 |
| 真机验收 / 远端发布 | 本轮未完成真机验收，未远端发布 | 新日志仍需确认具体异常位置与后续效果 |

相关六套件分别为 Hook 55、SharedSettings 12、ReplyMedia 11、Ledger 32、Publisher 15、Dispatcher 24。测试使用本机替身，未向在线模型或抖音提交；未重跑全量测试及原宿主全部规则扫描，宿主规则本轮未变。APK 检查首次因新断言错误地要求包内方法具备非零可见性标志而失败，调整测试助手的零约束语义后通过；保留 apk-contract-assertion-check 日志和 XML，未为此修改生产包。

### 最终诊断包

| 项目 | 结果 |
| --- | --- |
| 文件 | `artifacts/DouAi-0.1.94-release.apk` |
| 版本 / 包名 | `0.1.94 / 95`，`io.github.yudigaga.douai` |
| 大小 | 4,518,007 字节 |
| SHA-256 | `3E1E15FA22713E286149D4DE1757707767F618249BE1DF43DAFB47B3B65ACE38` |
| 证书 SHA-256 | `FAE67C134F4C4FA73B8E34487C47A8C7F3E7C34B840BC245AFAC51585B7DFD47` |
| 归档 | 同版本 JSON、SHA256SUMS；published=false |

安装后重启抖音，保持目前开关与图片/视频识别设置，在前台复现后导出新日志。先前失败仍按原账本规则重试，不清空记录。诊断中的行号可能是 R8 重映射值或 -1；对应 release-mapping.txt 与 final-source 已在 case 内保存。ADB 无连接设备，本轮未做真机验收。

## Evidence → Finding → Path

case 根为 `work/comment-reply-20261019-101042/`。原始日志仅本地留存；联合源码证据哈希记 n/a，最终 APK 哈希见上表。

| Evidence | source_ref / artifact_path | repro_command | content_hash |
| --- | --- | --- | --- |
| E-001 | 原 ZIP、当前模块日志、runtime-safe-stats.json | 下节哈希及安全筛选；按 0.1.93 / PID 22782 隔离 | ZIP 哈希见上文；统计联合证据 n/a |
| E-002 | 用户更正及历史截图；图片/视频开关反馈 | 人工核对时间、空生成内容及请求状态，不复制身份与正文 | n/a，截图不与 ZIP 单条记录强行对应 |
| E-003 | independent-audit.md；配置快照、CommentReplyHook、共享 AI 客户端 | 本地源码核查与现有共享生成器测试入口 | n/a，联合源码证据 |
| E-004 | 0.1.94 阶段、固定原因、模块位置和请求状态诊断；red-junit、focused-junit、focused-totals.json、最终 APK 与 final-apk-junit.xml | 下节专项与 APK 检查；构建、签名日志及归档元数据 | APK 哈希见上表；其余联合证据 n/a |

| Finding | 结论 | severity / category | evidence_ids | confidence / status | location / 限制 |
| --- | --- | --- | --- | --- | --- |
| F-001 | .93 已匹配真实子回复，随后生成处理失败 | n/a / other | E-001 | high / validated | 设备日志；具体抛出点未知 |
| F-002 | 现有标题和历史字段不足以确定 AI 写出、生成或抖音提交 | n/a / design | E-001,E-002,E-003 | high / validated | 截图较晚，不强行对应某次异常 |
| F-003 | 模型设置快照未发现裁剪，异常原因仍未唯一定位 | n/a / other | E-003 | medium / unresolved | 静态核查不替代运行时阶段证据 |
| F-004 | 新版具备安全阶段诊断，本地回归与归档检查通过 | n/a / design | E-004 | high / local_validated | 诊断增强不等于设备问题已修复 |

P-001，`path_type=callflow`：新回复提示 → 本人原评和子回复回源 → 严格匹配成功（E-001/F-001）→ 素材/输入/模型/输出/语音/调度处理中的未知异常（E-001,E-003/F-002,F-003）→ 新诊断记录阶段和数值状态（E-004/F-004）→ 依据后续设备日志定位。当前没有证据将本路径推进到评论回复原生发送接受。

## 离线复核

在仓库根 PowerShell 执行；以下命令只读取本地文件，不操作线上抖音账号：

```powershell
Get-FileHash -Algorithm SHA256 -LiteralPath 'F:/qq/MobileFile/LSPosed_20261019_101042.zip'
Get-Content -LiteralPath 'work/comment-reply-20261019-101042/runtime-safe-stats.json'
rg -n 'Comment reply thread lookup:|Comment reply generation failed:|Comment reply native request accepted|Automatic comment native request accepted' 'work/comment-reply-20261019-101042/log__modules_2026-10-19T09-42-03.896329.log'
```

筛选结果含旧会话时，必须先按版本加载与 PID 隔离；不得把整份轮转日志的计数直接当作 .93 当前会话统计。六个相关套件的结果在 focused-junit / focused-totals.json 中保存；复核命令如下：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'com.douai.module.feature.CommentReplyHookTest' --tests 'com.douai.module.feature.CommentReplySharedSettingsTest' --tests 'com.douai.module.feature.AutomationReplyMediaTest' --tests 'com.douai.module.feature.NativeCommentReplyPublisherTest' --tests 'com.douai.module.feature.CommentReplyLedgerTest' --tests 'com.douai.module.feature.AutomationDispatcherTest' --console=plain
$env:DOUAI_VERIFY_RELEASE_APK = (Get-Item -LiteralPath 'artifacts/DouAi-0.1.94-release.apk').FullName
.\gradlew.bat :app:testDebugUnitTest --tests 'com.douai.module.scan.ApkArtifactContractTest' --console=plain
```
