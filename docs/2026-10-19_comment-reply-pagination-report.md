# 评论自动回复基线分页的后续修正

后续更新：0.1.88 日志确认初始化已完成，但 15 页通知均为空；原生收件分组的后续定位与 0.1.89 修正见 [收件分组报告](2026-10-19_comment-reply-routing-report.md)。下文保留分页修正的历史证据及验证边界。

分析日期：2026-10-19（Asia/Shanghai）；日志事件发生于 2026-10-18。本文采用普通诊断结构（`flavor=null`），[范围](../work/comment-reply-20261018-235512/scope.md)与[时间线](../work/comment-reply-20261018-235512/timeline.md)保存在本地证据目录。[上一轮报告](2026-10-18_comment-reply-scan-report.md)作为历史记录保留。

## 结论与边界

**0.1.87 没有解决用户问题。** 新日志有 7 条 `stage=baseline; type=ScanFailure; reason=pagination_no_progress`，未见评论回复发送记录。它们把故障范围缩小到基线分页保护，但未记录页列表、游标或重复情况，仍不能确认现场究竟是空页、零游标还是游标重复。

这次依据更直接的宿主调用链，修正了“单页为空、零游标或游标重复就立即失败”的假设：以新增原始游标对或新增通知身份判断进展，连续 3 页没有进展才失败，并保留总计 50 页上限。修复后四套专项共 100 项通过，包含真实收件箱到模拟发送的集成；尚不能宣称设备问题已彻底解决。

最终本地交付为 `0.1.88 / versionCode 89`。100 项专项、Release 构建、Lint 及最终归档 APK 的 3 项合同检查完成，测试均为 0 失败、0 错误、0 跳过；Lint 为 0 错误、63 警告。本次没有重跑全量测试，不能将 0.1.87 的 1629 项结果当作新版全量验证。当前无连接设备，未做本轮真机验收或远端发布。

## 新日志与静态证据

用户提供的 `LSPosed_20261018_235512.zip` SHA-256：

```text
49AECC5DE59FEA1896389B4AD47C0543E4212166E1C7298EED7FF50166FFE412
```

当前模块日志新增 7 条错误，时间为 23:50:17.760–23:54:58.421，行号为 6080、6127、6466、7226、8024、8181、8558。对应模块载入版本均为 0.1.87；旧错误属于同包包含的历史记录，不能全部算作新版错误。没有 `native request accepted`、生成失败或通知跳过事件。

`full.log` 的 PID 31739/12062 共 8080 行，仅另存最后 1 条扫描失败；没有可归属扫描的请求响应、`NoticeResponse`、`min_time/max_time`、`has_more` 或列表字段。因此，当前只能确认保护分支，不能恢复该分支的输入值。原 ZIP、日志、宿主 smali 和 `work/` 不随公共仓库分发，本文只摘录脱敏元数据。

更直接的静态证据是本地抖音 40.6.0 的 `smali_classes17/X/0dU3.smali:838–916`：它经 `0dU1` 调用 `0dPQ` 路径，依据 `hasMore`、非 null 列表及数量/次数条件决定是否继续，并复用原始游标。该路径允许空的非 null 列表、零游标和有限重复；上一轮参考的 `0dHG` 属于旧 UI 路径，不能把它的非空列表门槛套到当前接口包装器。静态行为不等于本次账号已经返回过这些输入。

## 实现与专项验证

`NativeCommentReplyInbox.PaginationProgress` 分别记录原始游标对及通知身份（`nid/cid`）。出现新游标对或新增通知身份就重置停滞计数；否则累计，连续 3 页无进展抛出失败，总页数仍限制为 50。读取完成仍须 `more=false` 或有证据证明已越过目标时间界；不强行使用当前时间、不接受部分基线，也不因触发保护而推进成功水位。

失败日志新增 `page/items/max_sign/min_sign/new_cursor/new_notices/stalled`，只记录页号、数量、游标符号和进展统计，不打印游标原值、通知身份、账号、评论、请求或响应正文。

| 验证阶段 | tests / failures / errors / skipped | 本地归档 |
| --- | --- | --- |
| 修复前 Inbox RED | 3 / 3 / 0 / 0 | `red-tests.log`、`red-inbox-junit.xml` |
| 修复前集成 RED | 2 / 2 / 0 / 0 | `red-integration.log`、`red-integration-junit.xml` |
| 修复后 Hook / Inbox / Ledger / Publisher | 45 + 25 + 21 + 9 = **100 / 0 / 0 / 0** | `focused-tests.log`、`focused-junit/` |
| 最终归档 APK 合同 | 3 / 0 / 0 / 0 | `final-apk-contract.log`、`final-apk-junit.xml` |

归档目录为 `work/comment-reply-20261018-235512/`。集成使用真实 Inbox，校验同一秒的新 ID 各发送一次、旧 ID 不发送；也校验连续 3 页空 `0:0` 失败后，下次扫描能够恢复。接口模型与 `FakeSender` 为测试替身，验证止于模拟发送，不代表真实宿主、服务器或设备发送成功。

### 安装包

| 项目 | 结果 |
| --- | --- |
| 文件 | `artifacts/DouAi-0.1.88-release.apk` |
| 版本 / 包名 | `0.1.88 / 89`，`io.github.yudigaga.douai` |
| 大小 | 4,484,858 字节 |
| SHA-256 | `C11D03C7C29B6715D65AAF3882F8692E0072EC290E3FC38A5C220249527CA4F7` |
| 签名 | apksigner 验证通过，v3；证书 SHA-256 为 `FAE67C134F4C4FA73B8E34487C47A8C7F3E7C34B840BC245AFAC51585B7DFD47` |
| 一致性 | 与 `app/build/outputs/apk/release/app-release.apk` 哈希一致 |

安装后重启抖音，保持前台并用新收到的评论回复测试。起点完整建立前不处理历史；若依旧失败，新日志会显示具体页状态，可区分持续空页、游标不变与重复通知。不能把安装包的本地通过结果当作现场已经恢复。

## Evidence → Finding → Path

以下证据观察日期为 2026-10-19；多文件联合证据使用 `content_hash=n/a`。

| Evidence | source_ref / artifact_path | repro_command | content_hash |
| --- | --- | --- | --- |
| E-001 | 原 ZIP、当前模块日志、`full.log` | 下节原包哈希及日志统计 | ZIP 见上文 |
| E-002 | 本地 `0dU3.smali:838–916`、`0dU1/0dPQ` | 下节源码检索；宿主身份见[既有报告](2026-10-17_reverse-douyin-comment-reply-report.md) | n/a |
| E-003 | 当前 Inbox、修复前源码归档及两组 RED XML/日志 | 下节 XML 计数与源码检索 | n/a |
| E-004 | 四个专项测试类、`focused-junit/` 四份 XML及日志 | 下节专项重放与 XML 计数 | n/a |
| E-005 | 最终 APK、`release-build.log`、`final-apk-contract.log`、`final-apk-junit.xml` | Release/Lint；对最终归档运行 `ApkArtifactContractTest`；aapt/apksigner/Get-FileHash | 最终 APK 哈希见安装包表 |

| Finding | 结论 | severity / category | evidence_ids | confidence / status | location |
| --- | --- | --- | --- | --- | --- |
| F-001 | 0.1.87 仍在基线分页保护处失败 | n/a_re / other | E-001,E-003 | high / validated（日志与捕获实现） | `baseline`、分页保护 |
| F-002 | 立即拒绝空页/零游标/重复游标不符合当前宿主包装器；本地可复现 | n/a_re / design | E-002,E-003 | high / validated（静态与 RED） | `PaginationProgress` |
| F-003 | 新进展规则及恢复路径通过 100 项专项 | n/a_re / other | E-003,E-004 | high / validated（JVM/Robolectric） | Inbox 到 `FakeSender` |
| F-004 | 现场具体输入形态与新版真机修复效果 | n/a_re / other | E-001,E-004 | low / candidate（未确证） | 真实分页响应与发送 |

P-001，`path_type=callflow`：建立基线失败（E-001，F-001）→ 按宿主路径处理原始游标、列表和新增身份（E-002/E-003，F-002）→ 完整基线、去重、生成及模拟发送/失败后恢复（E-004，F-003）。目标为处理新回复；残余限制是现场输入和新版设备行为仍需实际日志验证，F-004 不提升为已确证。

## 复核命令

在仓库根目录 PowerShell 执行；需要本地输入及仓库既有 SDK/JDK/Gradle 环境。文档编写没有运行 Gradle。修复前 RED 由归档证明，当前源码重放应通过。

```powershell
Get-FileHash -Algorithm SHA256 -LiteralPath 'F:/qq/MobileFile/LSPosed_20261018_235512.zip'
$caseRoot = 'work/comment-reply-20261018-235512'
(Get-Content (Join-Path $caseRoot 'log__modules_2026-10-18T22-26-06.182177.log') | Select-String -SimpleMatch 'stage=baseline; type=ScanFailure; reason=pagination_no_progress').Count
Get-ChildItem -LiteralPath $caseRoot -Recurse -Filter '*.xml' | ForEach-Object {
    [xml]$j = Get-Content -LiteralPath $_.FullName
    [pscustomobject]@{ Suite=$j.testsuite.name; Tests=$j.testsuite.tests; Failures=$j.testsuite.failures; Errors=$j.testsuite.errors; Skipped=$j.testsuite.skipped }
}
rg -n 'PaginationProgress|MAX_STALLED_PAGES|new_cursor|new_notices|stalled' app/src/main/java/com/douai/module/feature/NativeCommentReplyInbox.java
rg -n '0dU1;->LIZ|NoticeResponse;->(hasMore|items|maxTime|minTime)' douyin-40.6.0/apktool-no-res/smali_classes17/X/0dU3.smali
.\gradlew.bat :app:testDebugUnitTest --tests 'com.douai.module.feature.CommentReplyHookTest' --tests 'com.douai.module.feature.NativeCommentReplyInboxTest' --tests 'com.douai.module.feature.CommentReplyLedgerTest' --tests 'com.douai.module.feature.NativeCommentReplyPublisherTest' --console=plain
```
