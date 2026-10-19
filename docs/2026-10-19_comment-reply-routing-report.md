# 评论自动回复收件分组修正

后续更新：0.1.89 日志确认 group44 已返回通知，但旧模型字段为空，仍未进入回复；完整模型适配、保留发送记录的一次性重扫及 0.1.90 交付见 [模型报告](2026-10-19_comment-reply-model-report.md)。下文保留本轮分组修正的历史证据。

分析日期：2026-10-19（Asia/Shanghai）。本文采用普通诊断结构（`flavor=null`）；[范围](../work/comment-reply-20261019-001859/scope.md)、[时间线](../work/comment-reply-20261019-001859/timeline.md)及原始证据保存在本地。前两轮[扫描报告](2026-10-18_comment-reply-scan-report.md)、[分页报告](2026-10-19_comment-reply-pagination-report.md)保留为历史记录。

## 结论与验证边界

用户明确确认：没有效果的是重启后新收到的评论回复。0.1.88 新日志只有一次 `baseline ready: pages=15; notices=0; existing_comments=0`，没有评论回复发送记录，不能再将现象解释为仅测试了历史评论。

本次反编译找到实质性的请求分组差异：UI 的一级类别 `3=comment` 不能直接当作原生接口的 `notice_group`。宿主将动态解析的评论子组传给该参数，`0dFd.LJIIIIZZ()` 调用 `0dED.LIZ("2")`，本地 APK 的默认映射为 44。旧实现直接传 UI 类别 3，与该合同不符；错误分组返回空列表的本地集成也成功复现了“完成基线但没有回复”的行为。

当前实现每次建立基线或增量扫描时读取宿主动态分组，并在该轮全部分页中固定使用；没有硬编码 44。解析失败或非正数时不发请求、不推进基线/水位。七个专项套件共 121 项通过，但没有捕获现场返回分组 44，也没有真机恢复证据，不能宣称用户问题已彻底解决。

最终本地交付为 `0.1.89 / versionCode 90`。Release/Lint 构建、121 项专项及最终归档 APK 的 3 项合同检查完成，测试均为 0 失败、0 错误、0 跳过；Lint 为 0 错误、63 警告。此次未重跑全量测试，当前无连接设备，未真机验收或远端发布。

## 日志事实与反编译依据

原始 `LSPosed_20261019_001859.zip` SHA-256：

```text
134B77D872C36669559CB1ABA0A8765653CD1F00EC8C6C8469742D4AEA12672C
```

0.1.88 的基线事件发生于 00:14:51.844、PID 24876，当前模块日志第 258 行；该进程之前已成功完成宿主规则扫描。新版本没有评论回复扫描失败、非空扫描完成或发送接受记录。后续宿主前台窗口覆盖了至少一次 60 秒轮询机会，但空扫描未记录日志，无法反推执行次数。用户随后打开互动通知页和详情页；页面生命周期不能证明模块读取到了对应通知。旧版本错误不混计为 0.1.88 错误。

反编译以本地抖音 40.6.0 原 APK 的 `classes17.dex` 为依据，样本身份见[既有接口报告](2026-10-17_reverse-douyin-comment-reply-report.md)。Jadx 从该 DEX 生成的 `work/comment-reply-20261019-001859/0dFd.java:65–67` 显示：

```java
public static final int LJIIIIZZ() {
    C0dED.LIZ.getClass();
    return C0dED.LIZ("2");
}
```

| 合同 | 原始 DEX / smali 依据 | 结论 |
| --- | --- | --- |
| 评论分组解析 | `X/0dFd.1.smali:1277–1303`；默认映射工厂 `Y/AObjectS331S0000000_23.smali:3765/3772` | 键 `"2"` 通过运行时映射解析；默认值 44，不保证每台设备当前值相同 |
| UI 子组到原生参数 | `0dH3` 读取 `0dHB.LIZIZ`，经 `0dHH/0dHa` 传递；合同测试验证寄存器链 | 网络参数取子组，不取 UI 一级类别；raw 6 对应 notifyme/@，也不是回复专组 |
| 两条 API 路径 | 互动页经 `0dPP`；模块继续经 `0dPQ`，同 endpoint、同响应，前 23 个 Query 槽位匹配；`0dPP` 另有 4 个 UI/筛选参数 | 只能确认共用部分的参数合同，不能证明服务端完全等价或已完整复刻原生请求 |

参数比对中，`top=0`、`sub_category=-1`、空 `scene`、`is_new_notice=1` 一致。模块保留 `is_mark_read=0`，与宿主该路径使用的 1 有意不同，以避免主动扫描改变已读状态。本轮仅修正已确认的动态收件分组缺陷；是否仍受 API 差异影响，需要新版实际计数验证，不追加 raw 6 请求。

原 ZIP、日志、DEX、Jadx 和 smali 输出及 `work/` 不随公共仓库分发；本文不公开账号、评论、token、请求或响应正文。

## 实现与专项验证

新增扫描规则 `comment_reply_received_group`，精确目标为 `LX/0dFd;->LJIIIIZZ()I`，并接入功能目录。规则总数为 207，指纹为 `a206123856e5de0787279ef5bcfebe774f1bc86535156233edf165963d8214cf`，使旧缓存不能遗漏新合同。Inbox 每轮先验证账号并解析一次分组；分页期间保持该值，下一轮重新解析。新增安全路由日志以及包含 0 条结果的扫描计数，便于区分未触发与触发后空列表。

| 验证 | tests / failures / errors / skipped | 归档 |
| --- | --- | --- |
| 修复前路由 RED | 4 / 4 / 0 / 0 | `red-route.log`、`red-route-junit.xml` |
| 修复前真实 Inbox 集成 RED | 1 / 1 / 0 / 0 | `red-route-integration.log`、`red-route-integration-junit.xml` |
| 修复后七套专项 | **121 / 0 / 0 / 0** | `focused-tests.log`、`focused-junit/` |
| 最终归档 APK 合同 | 3 / 0 / 0 / 0 | `final-apk-contract.log`、`final-apk-junit.xml` |

七套分别为 Hook 46、Inbox 29、Ledger 21、Publisher 9、HostContract 4、HostAdaptation 3、HostScanner 9。四项原 APK 合同测试验证动态方法及键 `"2"`、默认映射 44、原生子组寄存器链和 23 个 Query 槽位，全部实际执行、零跳过。

集成测试使用真实 Inbox，并让模拟宿主在错误分组下返回空页；修正后完成基线、读取新回复并到达 `FakeSender`，同一新回复只发送一次。它证明该逻辑缺陷可本地复现及修正，不证明用户现场采用了分组 44，也不证明真实服务器或设备发送成功。

### 最终安装包

| 项目 | 结果 |
| --- | --- |
| 文件 | `artifacts/DouAi-0.1.89-release.apk` |
| 版本 / 包名 | `0.1.89 / 90`，`io.github.yudigaga.douai` |
| 大小 | 4,484,903 字节 |
| SHA-256 | `EDD1E6806BC5026B00875259EA46596847087FB97DEB1257D19C55EE0FC472F3` |
| 签名 | apksigner 验证通过，v3；证书 SHA-256 为 `FAE67C134F4C4FA73B8E34487C47A8C7F3E7C34B840BC245AFAC51585B7DFD47` |
| 一致性 | 与 `app/build/outputs/apk/release/app-release.apk` SHA-256 相同 |

安装后重启抖音，等待新规则扫描及模块加载完成，再用新收到的评论回复验证。新版日志会记录实际动态分组和空/非空扫描计数。旧基线与去重记录保留，旧基线之前的历史回复不自动补发。

## Evidence → Finding → Path

观察日期均为 2026-10-19；多文件联合证据 `content_hash=n/a`。

| Evidence | source_ref / artifact_path | repro_command | content_hash |
| --- | --- | --- | --- |
| E-001 | 原 ZIP、当前模块日志、用户确认 | 下节哈希/基线统计；用户确认来自本次对话 | ZIP 见上文 |
| E-002 | 原始 `classes17.dex`、`0dFd.java`、宿主合同测试 | 下节反编译结果读取与专项重放 | n/a |
| E-003 | Inbox、规则 TSV、catalog、HostAdaptation、两组 RED 归档 | 下节源码检索与 XML 计数 | n/a |
| E-004 | 七份 `focused-junit/` XML、专项日志和测试类 | 下节专项重放与 XML 计数 | n/a |
| E-005 | `release-build.log`、最终归档 APK、`final-apk-contract.log`、`final-apk-junit.xml` | Release/Lint；对最终归档运行 `ApkArtifactContractTest`；aapt/apksigner/Get-FileHash | 最终 APK 哈希见上表 |

| Finding | 结论 | severity / category | evidence_ids | confidence / status | location |
| --- | --- | --- | --- | --- | --- |
| F-001 | 0.1.88 没有解决重启后新回复的问题；模块读取到空基线 | n/a_re / other | E-001,E-003 | high / validated（反馈与日志） | 收件基线 |
| F-002 | UI 一级类别被误用作原生分组；动态子组才符合宿主合同 | n/a_re / design | E-002,E-003 | high / validated（原 DEX 与 RED） | Inbox 请求分组 |
| F-003 | 动态解析、逐轮固定及模拟发送通过 121 项专项 | n/a_re / other | E-003,E-004 | high / validated（本地） | Inbox、规则及集成 |
| F-004 | 现场真实分组及新版设备恢复情况 | n/a_re / other | E-001,E-004 | low / candidate（未确证） | 账号映射与真实发送 |

P-001，`path_type=callflow`：宿主动态解析收件子组（E-002，F-002）→ 每轮固定分组，原生只读分页（E-003，F-002）→ 基线、去重、生成、`FakeSender` 一次发送（E-004，F-003）。目标是读取并回复新评论；残余限制为现场映射、实际响应及真机发送未验证，F-004 不提升为已确证。

## 复核命令

从仓库根目录 PowerShell 执行；专项依赖既有 SDK/JDK/Gradle 环境，`DOUAI_TEST_APK` 必须指向原始宿主 APK，否则原 APK 合同测试会跳过。本轮归档零跳过。报告编写未运行 Gradle；当前源码重放应通过，修复前失败由 RED 归档证明。

```powershell
Get-FileHash -Algorithm SHA256 -LiteralPath 'F:/qq/MobileFile/LSPosed_20261019_001859.zip'
$caseRoot = 'work/comment-reply-20261019-001859'
(Get-Content (Join-Path $caseRoot 'log__modules_2026-10-19T00-14-44.557747.log') | Select-String -SimpleMatch 'Comment reply inbox baseline ready').Count
Get-Content (Join-Path $caseRoot '0dFd.java') | Select-Object -Skip 64 -First 4
Get-ChildItem -LiteralPath $caseRoot -Recurse -Filter '*.xml' | ForEach-Object {
    [xml]$j = Get-Content -LiteralPath $_.FullName
    [pscustomobject]@{ Suite=$j.testsuite.name; Tests=$j.testsuite.tests; Failures=$j.testsuite.failures; Errors=$j.testsuite.errors; Skipped=$j.testsuite.skipped }
}
rg -n 'comment_reply_received_group|resolveReceivedGroup|inbox route|inbox scan complete' app/src/main/java/com/douai/module/feature/NativeCommentReplyInbox.java app/src/main/assets/douyin-host-rules.tsv app/src/main/java/com/douai/module/scan/HostFeatureCatalog.java
.\gradlew.bat :app:testDebugUnitTest --tests 'com.douai.module.feature.CommentReplyHookTest' --tests 'com.douai.module.feature.NativeCommentReplyInboxTest' --tests 'com.douai.module.feature.CommentReplyLedgerTest' --tests 'com.douai.module.feature.NativeCommentReplyPublisherTest' --tests 'com.douai.module.scan.CommentReplyHostContractTest' --tests 'com.douai.module.scan.HostAdaptationTest' --tests 'com.douai.module.scan.HostScannerTest' --console=plain
```
