# 评论自动回复扫描失败诊断与修复

后续更新：用户在 0.1.87 仍复现失败；新版日志明确指向基线分页保护，后续修正与 0.1.88 交付见 [2026-10-19 后续报告](2026-10-19_comment-reply-pagination-report.md)。下文保留上一轮证据及本地验证边界。

分析日期：2026-10-18。范围为用户提供的 LSPosed 日志、本地宿主 smali、模块源码及 JVM/Robolectric 回归，采用普通诊断结构（`flavor=null`）。[范围与授权](../work/comment-reply-20261018-232000/scope.md)、[时间线](../work/comment-reply-20261018-232000/timeline.md)留存在已忽略的本地证据目录。

## 结论与边界

日志确认评论回复扫描持续抛出 `IllegalStateException`，共 102 次，跨 DouAi 0.1.82–0.1.86，没有评论回复进入发送阶段的记录。旧日志只记异常类名，不能确定具体失败分支，也不能将某个分支认定为本次设备问题的唯一根因。

本地确认并通过两项 RED 回归复现了一处分页兼容缺陷：模块额外要求 `max_time >= min_time`、翻页时 `min_time` 不得增大，而所见宿主调用链原样保存并传回游标对，没有这两项关系约束。本轮移除额外假设，保留游标取值、空页、重复游标对及 50 页上限保护，并增加安全的细分诊断。修复后四个专项套件共 95 项全部通过。

零 `max`、翻页时 `min` 增大是构造的回归输入，本次未捕获真实服务端返回以证明它们在用户账号上出现。本地集成到 `FakeSender.send` 的通过结果，不等于服务器接收或真机自动回复已恢复。最终本地交付为 `0.1.87 / versionCode 88`；169 个套件的 1629 项完整回归、Lint 和 Release 构建已完成，最终归档 APK 另有 3 项合同检查通过。测试均为零失败、零错误、零跳过，Lint 为 0 错误、63 警告。当前无可连接设备，未安装或触发真机验证，未远端发布。

## 日志事实

原包为用户提供的 `LSPosed_20261018_232000.zip`，SHA-256：

```text
778E6A6AB6B74D7E1CFC6D01B9F1B54957C0A143F5CF56C8D9C3568B7907976F
```

原包保持不变。原 ZIP、日志、宿主反编译文件和 `work/` 不随公共仓库分发；本地证据链接需要相应工作区。本报告仅保留脱敏事件、类名和统计。

| 模块日志 | 扫描失败 | 首次—最后（2026-10-18） |
| --- | ---: | --- |
| `log.old/modules_2026-10-18T19:27:44.176934.log` | 82 | 19:36:15.433—22:05:29.301 |
| `log/modules_2026-10-18T22:26:06.182177.log` | 20 | 22:40:26.479—23:19:21.978 |

按进程载入版本关联，0.1.82 为 5 次、0.1.83 为 11 次、0.1.84 为 50 次、0.1.85 为 23 次、0.1.86 为 13 次。版本进程可有时间重叠，不代表互斥安装区间。

两份模块日志的全部 `Comment reply` 行均为扫描失败，没有 `native request accepted`、`generation failed`、`notice skipped` 或 `push observation failed`，也没有该异常的消息或堆栈。当前文件第 4493 行载入 0.1.86，第 4538、5166、5177、5180 行继续失败。`full.log` 另有 3 条同类事件，不重复计入模块日志总数。设备属性为 Android 16/API 36、OS3.0，模块使用 API 102；所选日志未确认宿主精确版本。`DiggViewModel action contract changed` 属于自动点赞 Hook，不能作为评论回复根因。

## 契约、修正与本地验证

静态对象为本地抖音 40.6.0，其身份与接口依据见[既有报告](2026-10-17_reverse-douyin-comment-reply-report.md)，不能据此推定设备宿主版本。`smali_classes17/X/0dHG.smali:315–351` 检查列表并原样保存 `NoticeResponse.maxTime/minTime`；`0dHH.smali:318/322` 读取它们，再于 `536/540` 原样传给 `0dHa.LIZ`。这支持移除模块额外关系假设，不证明服务器必然返回某组游标。

当前 `NativeCommentReplyInbox.ensureProgress` 在继续翻页时要求非空列表、`min > 0`、`max >= 0`、游标对不重复；基线和增量扫描仍有 50 页上限。账号复验、基线秒内收集、通知识别与持久去重继续执行，扫描失败不能作为成功来推进水位。

新增 `FailureReason`、`ScanFailure` 和安全诊断，区分 API 初始化、请求/Future/等待、响应模型/状态/分页、基线及账号等原因。`CommentReplyHook` 记录 `stage`、异常类型、固定 `reason`，适用时附根因类型与数值 `status`；不拼接宿主异常原文、请求体或响应正文。

| 归档结果 | tests / failures / errors / skipped |
| --- | --- |
| 修复前新增两项 Inbox 游标回归 | 2 / 2 / 0 / 0 |
| 修复后 `CommentReplyHookTest` | 43 / 0 / 0 / 0 |
| 修复后 `CommentReplyLedgerTest` | 21 / 0 / 0 / 0 |
| 修复后 `NativeCommentReplyInboxTest` | 22 / 0 / 0 / 0 |
| 修复后 `NativeCommentReplyPublisherTest` | 9 / 0 / 0 / 0 |
| 最终源码完整回归，169 个套件 | 1629 / 0 / 0 / 0 |
| 最终归档 APK 的 `ApkArtifactContractTest` | 3 / 0 / 0 / 0 |

结果位于 `work/comment-reply-20261018-232000/`：`red-tests.log`、`red-inbox-junit.xml`、`focused-tests.log`、`focused-junit/`。集成测试 `rawNativeNoticeCursorsEstablishBaselineAndDeliverTheNextReply` 使用真实 Inbox 建立基线、收全同秒记录，再输入新回复并校验生成、`FakeSender.send` 与历史记录；宿主 API、账号和发送器为测试替身。

### 最终安装包

| 项目 | 结果 |
| --- | --- |
| 归档 | `artifacts/DouAi-0.1.87-release.apk` |
| 版本 / 包名 | `0.1.87 / 88`，`io.github.yudigaga.douai` |
| 大小 | 4,484,871 字节 |
| SHA-256 | `E0714BC9F7B851EA39D39C8C7DA327937BBFF193B0CD86AB30F1D5CB03F0C73E` |
| 签名 | apksigner 验证通过，v3；证书 SHA-256 为 `FAE67C134F4C4FA73B8E34487C47A8C7F3E7C34B840BC245AFAC51585B7DFD47` |
| 归档一致性 | 与 `app/build/outputs/apk/release/app-release.apk` SHA-256 相同 |

完整回归记录为 `full-junit/`、`release-validation.log`；最终 APK 合同为 `final-apk-junit.xml`、`final-apk-contract.log`。安装后重启抖音，在前台收到新的评论回复后验证。首次成功建立起点会跳过此前已有评论；之后按原设置延迟处理并轮询。若仍不回复，新版日志的 `stage` 和固定 `reason` 可用于确认具体失败分支。

## Evidence → Finding → Path

观察日期均为 2026-10-18。以下复核命令均从仓库根目录运行；多文件联合证据的 `content_hash=n/a`。

| Evidence | source_ref / artifact_path | 复核方式 | content_hash |
| --- | --- | --- | --- |
| E-001 | 原 ZIP、上述两份模块日志 | 下节日志统计；`Get-FileHash` | ZIP 见上文；当前日志 `D4C27807C7288091900889917DB89A8FD3BF6D93F102383C4BEF2B2693CA1C18`；旧日志 `6AEB8EC75FD20B80F84481E6C3D9CAA10B0D169B57D0EF3CAEA9D584DBB41C14` |
| E-002 | `NativeCommentReplyInbox.java`、`CommentReplyHook.java` | 下节源码检索 | n/a |
| E-003 | `0dHG.smali`、`0dHH.smali`、既有宿主报告 | 上节准确位置及下节检索 | n/a；样本验证记录见既有报告 |
| E-004 | `red-inbox-junit.xml`、`red-tests.log` | 下节 XML 计数 | RED XML `44492F72EAFD22BF47D27D36C7E701B7A56AE2A37439F7E03F9B8037DE0F5B29` |
| E-005 | `focused-junit/` 四份 XML、专项日志与测试类 | 下节 XML 计数、专项重放 | n/a |
| E-006 | `full-junit/`、`release-validation.log`、`final-apk-junit.xml`、`final-apk-contract.log`、最终 APK | 完整回归、Release/Lint；另以 `DOUAI_VERIFY_RELEASE_APK` 指向最终归档运行 `ApkArtifactContractTest`，aapt/apksigner/Get-FileHash 核对元数据、签名和哈希 | APK 哈希见安装包表 |

| Finding | 结论 | severity / category | evidence_ids | confidence / status | location |
| --- | --- | --- | --- | --- | --- |
| F-001 | 持续扫描失败；异常粒度不足以辨别分支 | n/a_re / other | E-001,E-002 | high / validated（日志与源码） | `CommentReplyHook.requestScan` |
| F-002 | 额外游标关系导致构造分页输入被误拒绝 | n/a_re / design | E-002,E-003,E-004 | high / validated（本地缺陷） | `ensureProgress` |
| F-003 | 修正通过 95 项专项，含 Inbox 到模拟发送集成 | n/a_re / other | E-002,E-005 | high / validated（JVM/Robolectric） | 四个测试套件 |
| F-004 | 设备 102 次错误由 F-002 单独导致 | n/a_re / other | E-001,E-003,E-004 | low / candidate（未确证） | 实际收件响应与扫描分支 |

P-001，`path_type=callflow`：前台触发扫描/建立基线（E-001/E-002，F-001）→ 原样复用游标并执行分页保护（E-002/E-003/E-004，F-002）→ 识别、去重、生成、模拟发送（E-005，F-003）。目标是处理新回复；残余风险为设备真实失败分支、服务端返回、Hook 与实际发送未验证。新增诊断供后续设备日志区分这些情况，不能把 F-004 升为已确证。

## 复核命令

PowerShell；依赖本地证据和仓库既有 SDK/JDK/Gradle 环境。报告编写只读取结果，未再次运行 Gradle。

```powershell
Get-FileHash -Algorithm SHA256 -LiteralPath 'F:/qq/MobileFile/LSPosed_20261018_232000.zip'
$caseRoot = 'work/comment-reply-20261018-232000'
Get-ChildItem -LiteralPath $caseRoot -Filter '*modules*.log' | ForEach-Object {
    [pscustomobject]@{ File = $_.Name; ScanFailures = @(Get-Content -LiteralPath $_.FullName | Select-String -SimpleMatch 'Comment reply scan failed: IllegalStateException').Count }
}
$xmlFiles = @((Join-Path $caseRoot 'red-inbox-junit.xml')) + @(Get-ChildItem (Join-Path $caseRoot 'focused-junit') -Filter '*.xml' | Select-Object -ExpandProperty FullName)
foreach ($xmlFile in $xmlFiles) {
    [xml]$junit = Get-Content -LiteralPath $xmlFile
    [pscustomobject]@{ Suite = $junit.testsuite.name; Tests = $junit.testsuite.tests; Failures = $junit.testsuite.failures; Errors = $junit.testsuite.errors; Skipped = $junit.testsuite.skipped }
}
rg -n 'ensureProgress|FailureReason|failureDiagnostic|stage=' app/src/main/java/com/douai/module/feature/NativeCommentReplyInbox.java app/src/main/java/com/douai/module/feature/CommentReplyHook.java
rg -n 'NoticeResponse;->(maxTime|minTime)|0dH3;->LJII(IZ|J):J|0dHa;->LIZ' douyin-40.6.0/apktool-no-res/smali_classes17/X/0dHG.smali douyin-40.6.0/apktool-no-res/smali_classes17/X/0dHH.smali
```

重放专项回归：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'com.douai.module.feature.CommentReplyHookTest' --tests 'com.douai.module.feature.CommentReplyLedgerTest' --tests 'com.douai.module.feature.NativeCommentReplyInboxTest' --tests 'com.douai.module.feature.NativeCommentReplyPublisherTest' --console=plain
```

当前源码运行应通过。RED 两项为 `zeroMaxCursorDoesNotPreventBaselineOrDropNewReplies`、`changingCursorPairCanIncludeNewerNoticesDuringPagination`；其修复前失败由归档证明，直接运行修复后源码不会重现旧失败。
