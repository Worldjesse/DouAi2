# 评论收件模型适配与一次性重扫

本文记录 0.1.90 的历史验证；后续收到的新日志与父关系兼容修正见[定向回复报告](2026-10-19_comment-reply-directed-report.md)。

分析日期：2026-10-19（Asia/Shanghai）。本文采用普通诊断结构（`flavor=null`），[范围](../work/comment-reply-20261019-005812/scope.md)与[时间线](../work/comment-reply-20261019-005812/timeline.md)留存在本地。既有[路由报告](2026-10-19_comment-reply-routing-report.md)等文档保留为历史记录。

## 结论与边界

0.1.89 已实际使用宿主解析出的 group44，9 轮扫描均收到 21 条通知，却全部得到 0 条可处理回复。189 次跳过均发生于旧 `commentNotice` 字段为空处，因此问题已从空收件分组收敛到通知模型读取。不能据此断言现场 189 条都是某一种新模型，或都属于起点之后的新回复。

原始宿主 DEX 显示，原生评论回复还读取 `interactive_notice.comment`，并优先使用 `interactive_notice.additional_info.comment.current_comment`。本轮改用宿主统一解析入口，补齐外层作品及原评论上下文；同时将模型版本升为 2，旧账本只回退一次到原始 baseline，保留全部去重/发送记录，避免旧解析期间推进的水位继续遮住漏项。

模型、迁移及发送联动的 148 项专项检查已全部通过，零失败、零错误、零跳过，其中包括 8 项原始宿主 APK 合同。最终本地交付为 `0.1.90 / versionCode 91`；Release/Lint 和最终归档 APK 的 3 项合同检查已完成，Lint 为 0 错误、63 警告。此次未重跑全量测试，当前无连接设备，未真机验收或远端发布。

## 日志事实

原始 `LSPosed_20261019_005812.zip` SHA-256：

```text
47C7C4A30BC1634382E0E40B27DA9C6E0D2837D53F94D6E6C5589DF5E97E2326
```

已隔离 0.1.89 的 PID4663、20623、17377，载入分别为 00:49:56.292、00:50:40.355、00:52:41.846。9 轮 group44 扫描发生于 00:50:27–00:57:47，每轮单页 21 条，全部输出 `replies=0`；189 条 skip 原因为 `unsupported or missing comment notice payload`。没有本轮评论回复发送接受或生成失败；其他自动评论日志不混计。

前 6 轮的 21 个通知身份和顺序相同；00:55:47.308 首次观察到换入 1 个、换出 1 个，保留 20 个，最后 3 轮集合相同。总计 22 个唯一身份，本文不输出 ID。首次观察时间不是通知创建时间，现有日志没有来源时间或模型字段响应，不能证明这些对象是否聚合/占位。

旧实现先 `extract()`，再按 `event.createdAt >= sinceSeconds` 过滤，故 skip 在时间过滤前发生。21 条不能全部算作水位之后；但一次成功扫描可能根据通知时间推进水位，即使每条解析都失败。修复仅保留原水位减 5 分钟的回看不足以覆盖更早漏项，因此需要一次性原 baseline 重扫。

原 ZIP、日志、DEX、反编译输出、源码快照及 `work/` 不随公共仓库分发；报告不包含账号、评论、token 或请求响应正文。

## 原始 DEX 合同与修正范围

静态对象仍为本地抖音 40.6.0 原 APK，样本身份见[既有接口报告](2026-10-17_reverse-douyin-comment-reply-report.md)。`CommentReplyHostContractTest` 已由 4 项扩至 8 项，直接读取原始 `classes17.dex` 验证以下合同，而非仅依赖反编译变量名：

| 原生入口/字段 | 已确认的行为 | 实施约束 |
| --- | --- | --- |
| `0dKM.LIZLLL` | 优先取 `interactive_notice.comment`（General 的 F），再回退 legacy 字段 | 两种 payload 均需适配 |
| `0dKM.LIZJ` | 优先取 Q → `additional_info.comment.current_comment`，再经 F/legacy 回退 | 基线、时间、进展与事件提取使用统一 incoming |
| `0dN5` / `0dN2` | 回复目标使用统一 target；作品取 `General.getAweme()`，可位于外层 | 不能只依赖 payload 内的作品辅助方法 |
| `0dKN.e` / `f` | e 是 `level1_comment`；f 是独立的 `parent_id` 元数据 | 原评论与当前账号作者用于方向证明，不凭 f 猜父评论 CID |

当前实现使用统一 helper，保留实际 target 的 `replyId/replyToReplyId`、原评论作者为本人及作品/评论身份一致性的检查。新模型的作品可由原生外层绑定，`nativeWorkBound` 将该证明传到发布器；仅此路径允许目标评论省略重复作品 ID，非空冲突一律拒绝，发送预留前后继续复核 CID、root 和捕获作品。精确回执校验未放宽。`is_reply` 在原生轻互动链仅影响 `scene_type`，不作为允许发送的硬门槛；不能用它或 `parent_id` 替代真实父 CID/作者证明。

新增 4 条模型规则，合计 211 条，指纹为 `3e9028188ac4644f0253c56bc2cfacbc8d5ee657d220dd50acff7acbee4b9fae`。新增安全模型计数区分 legacy、nested、detail、no_target，配合 `before_start` 计数，不记录用户内容。

## 一次性重扫与验证记录

`CommentReplyLedger.ensureInboxModelVersion` 在账号已初始化且持久状态有效时，用同一次 commit 将旧版本 watermark 回退到原 baseline，并写入 `inbox_model.<account>=2`；保留 baseline 和全部 incoming claims。已为 2 或更高版本则不再回退；新账号初始化同一事务写版本 2。schema 类型错误、非法边界或 commit 失败均停止授权扫描。Hook 在 `source.scan` 前检查迁移成功，不重建或清空账本，也不扩大到原 baseline 之前。

sent、submitted、unknown、ignored、deferred 等记录全部保留，原有“已提交或结果未知不自动重发”约束继续生效。两个新增 Hook 集成测试已通过，覆盖 F/Q 两路各一次、重扫原 baseline 后找回超过 5 分钟的漏项，以及已发送/已提交/未知记录不重发。

| 阶段 | 实际结果 | 归档/边界 |
| --- | --- | --- |
| 模型与 Ledger RED | 37 项、15 失败、0 error、0 skip：Inbox 7/7 失败，Ledger 30/8 失败 | `red-model-and-ledger.log`、`red-junit/`；22 项既有/当前 schema 行为通过 |
| 作品绑定新增 RED | 1 项、1 失败、0 error、0 skip | `red-work-binding.log`、`red-work-binding-junit.xml` |
| 发布器联动 RED | 13 项、2 失败、0 error、0 skip | `red-publisher.log`、`red-publisher-junit.xml`；原有及负例通过 |
| UI 标签约束 RED | 1 项、1 失败、0 error、0 skip | `red-ui-label.log`、`red-ui-label-junit.xml` |
| 修复后专项 | **148 项、0 失败、0 error、0 skip** | `focused-tests.log`、`focused-junit/` |
| 最终归档 APK 合同 | 3 项、0 失败、0 error、0 skip | `final-apk-contract.log`、`final-apk-junit.xml` |

七个套件分别为 Hook 48、Inbox 37、Ledger 30、Publisher 13、HostContract 8、HostAdaptation 3、HostScanner 9。它们验证本地收件、恢复、去重和模拟原生提交，不证明设备恢复，也不证明现场全部通知都具备 F/Q 内容。

### 最终安装包

| 项目 | 结果 |
| --- | --- |
| 文件 | `artifacts/DouAi-0.1.90-release.apk` |
| 版本 / 包名 | `0.1.90 / 91`，`io.github.yudigaga.douai` |
| 大小 | 4,484,982 字节 |
| SHA-256 | `1D2747941DB520A6547DF1FCA841F1E33100CDCD35154407F46345558D58A0B0` |
| 签名 | apksigner 验证通过，v3；证书 SHA-256 为 `FAE67C134F4C4FA73B8E34487C47A8C7F3E7C34B840BC245AFAC51585B7DFD47` |
| 一致性 | 与 `app/build/outputs/apk/release/app-release.apk` SHA-256 相同 |

安装后重启抖音并等待模块新规则加载完成。升级账号会从原始起点重查漏项，同时保留已经发送、已提交和结果未知的防重记录；原始起点之前的历史不处理。随后用新收到的评论回复验证实际收件、生成与发送，新版安全计数可确认解析走哪种结构。

## Evidence → Finding → Path

观察日期均为 2026-10-19，多文件联合证据 `content_hash=n/a`。

| Evidence | source_ref / artifact_path | repro_command | content_hash |
| --- | --- | --- | --- |
| E-001 | 原 ZIP、当前模块日志、去标识化身份比较 | 下节哈希与统计；原始 ID 只在本地作集合相等性比较 | ZIP 见上文 |
| E-002 | 原始 classes17.dex、8 项 `CommentReplyHostContractTest` | 下节专项重放；需要原始 APK | n/a |
| E-003 | Inbox、Ledger、Hook、规则及 RED 归档 | 下节源码检索与 XML 计数 | n/a |
| E-004 | `focused-tests.log`、`focused-junit/` 七份 XML、work-binding/publisher/UI-label RED、两个 Hook 集成测试 | 下节日志/XML 复核与专项重放 | n/a；148 项实际执行，零跳过 |
| E-005 | 最终归档 APK、`release-build.log`、`final-apk-contract.log`、`final-apk-junit.xml` | Release/Lint；对最终归档执行 `ApkArtifactContractTest`；aapt/apksigner/Get-FileHash | APK 哈希见安装包表 |

| Finding | 结论 | severity / category | evidence_ids | confidence / status | location |
| --- | --- | --- | --- | --- | --- |
| F-001 | 分组生效，但 legacy payload 为空导致事件未提取 | n/a_re / other | E-001,E-003 | high / validated（日志与源码） | 旧 `extract` |
| F-002 | 只读 legacy 模型遗漏宿主两条统一目标路径及外层作品 | n/a_re / design | E-002,E-003 | high / validated（原 DEX 与 RED） | `0dKM/0dN5/0dN2`、Inbox |
| F-003 | 旧水位可能掩盖漏项，需原 baseline 一次性重扫且保留 claims | n/a_re / design | E-001,E-003 | high / validated（机制与本地回归） | Ledger、Hook |
| F-004 | 现场通知具体结构及新版真机恢复情况 | n/a_re / other | E-001,E-004 | low / candidate（尚未确证） | 真实响应与发送 |

P-001，`path_type=callflow`：group44 收件（E-001，F-001）→ 宿主 helper 解析 F/Q/legacy 与外层作品（E-002/E-003，F-002）→ 旧账号一次性回原 baseline、保留去重与未知提交记录（E-003，F-003）→ 发送前后复验目标与作品、准确回执才确认成功（E-004）。该路径已通过本地专项，现场形态与真机行为仍未确认，不将 F-004 升为已确证。

## 复核命令

仓库根目录 PowerShell；专项依赖既有 SDK/JDK/Gradle 环境，`DOUAI_TEST_APK` 必须指向原始宿主 APK。报告编写未运行 Gradle；执行当前源码会验证最新实现，不会重现归档中的旧 RED 状态。

```powershell
Get-FileHash -Algorithm SHA256 -LiteralPath 'F:/qq/MobileFile/LSPosed_20261019_005812.zip'
$caseRoot = 'work/comment-reply-20261019-005812'
$moduleLog = Join-Path $caseRoot 'log__modules_2026-10-19T00-14-44.557747.log'
foreach ($event in @('Comment reply inbox route: source=host_received_comments; group=44', 'Comment reply inbox scan complete: pages=1; notices=21; replies=0', 'unsupported or missing comment notice payload')) {
    [pscustomobject]@{ Event=$event; Count=@(Get-Content $moduleLog | Select-String -SimpleMatch $event).Count }
}
Get-ChildItem -LiteralPath $caseRoot -Recurse -Filter '*.xml' | ForEach-Object {
    [xml]$j = Get-Content -LiteralPath $_.FullName
    [pscustomobject]@{ Suite=$j.testsuite.name; Tests=$j.testsuite.tests; Failures=$j.testsuite.failures; Errors=$j.testsuite.errors; Skipped=$j.testsuite.skipped }
}
rg -n 'comment_reply_notice_(comment_model|incoming|reply_detail)|comment_reply_reply_detail_model' app/src/main/assets/douyin-host-rules.tsv
rg -n 'ensureInboxModelVersion|INBOX_MODEL_VERSION|model_migration' app/src/main/java/com/douai/module/feature/CommentReplyLedger.java app/src/main/java/com/douai/module/feature/CommentReplyHook.java
.\gradlew.bat :app:testDebugUnitTest --tests 'com.douai.module.feature.CommentReplyHookTest' --tests 'com.douai.module.feature.NativeCommentReplyInboxTest' --tests 'com.douai.module.feature.CommentReplyLedgerTest' --tests 'com.douai.module.feature.NativeCommentReplyPublisherTest' --tests 'com.douai.module.scan.CommentReplyHostContractTest' --tests 'com.douai.module.scan.HostAdaptationTest' --tests 'com.douai.module.scan.HostScannerTest' --console=plain
```
