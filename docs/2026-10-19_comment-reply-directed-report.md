# 定向回复的缺失父评论兼容

本文记录 0.1.91 的历史实现；后续设备日志确认该兼容未命中，回源核对方案见[0.1.92 线程补全报告](2026-10-19_comment-reply-thread-backfill-report.md)。

分析日期：2026-10-19（Asia/Shanghai），普通诊断结构 `flavor=null`。本次[范围](../work/comment-reply-20261019-014527/scope.md)与[时间线](../work/comment-reply-20261019-014527/timeline.md)保存在本地；[上一轮模型报告](2026-10-19_comment-reply-model-report.md)保留为历史记录。

## 结论与验证边界

用户明确需要回复“别人回复自己在他人作品下发出的自动评论”，不扩大为自动回复自己作品下的顶层评论。0.1.90 日志已证明 Q 模型能够读取，但每轮 21 项中有 18 项因目标评论缺少 `replyId/replyToReplyId` 而跳过。缺少父引用并不能证明这些对象是顶层评论，也不能证明它们都回复当前账号；现场尚未记录接收 UID、原评作者和原评内容。

本轮仅为 Q 模型增加严格兼容：同时确认明确接收 UID 为当前账号、同一结构中存在真实的本人顶层原评、评论及作品身份没有冲突，才调用宿主 clone 并在副本上恢复根评论。`parent_id`、`is_reply` 不提供授权，不能单独触发兼容。此归一化是模块依据已验证字段作出的兼容推导，原生代码没有自动执行 `level1_comment → current_comment.replyId` 的同类修复。

最终本地交付为 **0.1.91 / versionCode 92**。160 项专项（含 11 项原始宿主 APK 合同）与 3 项最终归档 APK 合同全部通过，零失败、零错误、零跳过；Release 构建、v3 签名和归档哈希核对通过，Lint 为 0 错误、63 警告。未重跑全量测试，没有可用 ADB 设备，未真机验收或远端发布；本地通过不能证明现场 18 项都具备新分支所需证据。

## 新日志实际说明了什么

原始 `LSPosed_20261019_014527.zip` SHA-256 已复核：

```text
73D7DF4C16624E58017FEFE288243F5B0FC784393809CFD3CC20518CF74C8C10
```

已隔离载入 0.1.90 的三个进程。四次完成扫描分别为 01:40:11.589、01:40:20.468、01:42:20.037、01:44:41.494；其他版本及未完成扫描不混计。

| 项目 | 每次完成扫描 | 四次观测合计 |
| --- | --- | --- |
| 收件与模型 | group44；21 项，`detail=20 / no_target=1` | 84 项扫描观测，不是 84 个唯一通知 |
| 缺少目标父引用而跳过 | 18 项 | 72 次 |
| 缺少可用作品而跳过 | 2 项 | 8 次 |
| 缺少统一 target | 1 项 | 4 次 |
| 输出回复事件 | 0 项 | 0 项 |

`runtime-counts.json` 保存了去标识化计数。每轮 `before_start=0` 也不能证明全部通知都在起点之后：旧实现先提取、后按时间过滤，被上述条件跳过的对象没有进入该时间计数。现有日志不足以确定通知新旧时间、18 项中实际定向给本人的数量，或新兼容分支能命中多少项。

原 ZIP、原始日志、身份 ID、账号、评论、token 和响应正文不进入公开报告。测试中的构造数据也不视为现场响应。

## 原始 DEX 证据与兼容设计

静态对象仍为本地抖音 40.6.0 原 APK，样本身份见[原始接口报告](2026-10-17_reverse-douyin-comment-reply-report.md)。使用 dexlib2 3.0.9 从 `classes17.dex`、`classes30.dex` 选择读取指定类，避免 Windows 文件名大小写碰撞和保留全量 DEX 对象。此处是 Java/DEX 方法与字段引用分析，不涉及新的 native/PE 导入表；关键依赖为账号服务、Comment 克隆及宿主发布 helper。

| 合同 | 原始证据 | 能证明与不能证明的内容 |
| --- | --- | --- |
| 定向接收账号 | `Comment.replyToUserId` 的 Gson 名称为 `reply_to_userid`；getter `LLLLL()`；`0yXF.LIZ` 与当前登录 UID 比较 | 可以独立证明该评论指向的接收账号；不能单独恢复具体父 CID |
| 原评上下文 | Q 的 `0dKN.e` 是 `level1_comment`；统一入口为 `0dKM.LJII` | 模块另外核验其真实 CID、作者、正文与作品；原生已查消费者只检查状态或设置 `reply_to_reply` 标签，没有把 e 的 CID 写回 current |
| 宿主克隆 | `Comment.clone()` 桥接到 `LJLLLLLL()`，复制 CID、作品 ID、文本、时间、接收 UID、有效用户及图片列表等 | 允许修改独立副本；有效用户 getter 可能返回另一 User 实例，应比较 UID，不能要求用户对象同引用 |
| 发布目标 | `CommentPublishRequest` 将模型 p 交给 `0y6L.LJ/LJFF`，原 helper 存在返回明确 replyId 与 incoming CID 的分支 | 验证已有明确根 ID 时的发布合同；不证明原生会从 Q.e 推造缺失父引用 |

`replyUser`、`replyToReplyCommentText` 没有对应 Gson 注解；后者同名的 snake_case 出现在发布请求字段中，不能据此当作已证实的收件字段。原生 clone 也没有复制这两个本地字段。本轮只使用真实本人原评的正文作上下文，不依赖它们补足缺失证据。

仅当以下条件全部成立时启用新分支：

1. target 确实来自 Q，且其两个 canonical 父字段均为空或 `0`；发送者、incoming CID、作品及时间等既有校验仍通过。
2. `reply_to_userid` 明确等于当前账号；缺失、`0` 或其他账号均不能授权此推导。
3. 同一 Q detail 的 e 有真实作者且作者为本人，CID 有效并与 incoming 不同，正文非空；其自身两个父字段也为空或 `0`，只覆盖本人顶层原评。
4. e 与 target 的非空作品 ID 均不得和捕获作品冲突。
5. 宿主 clone 返回另一个合法 Comment，身份、正文、作品 ID、时间、接收 UID、发送者 UID 与媒体保持一致。有效用户可为不同对象，但 UID 必须相同；随后仅给副本赋 `replyId=e.cid`、`replyToReplyId="0"`，原对象不变。

已有 canonical 父引用继续采用真实父 CID 与本人原评作者证明；若目标显式指向其他接收账号，即使父 CID 恰好匹配本人，也拒绝。`f/parent_id` 和 `d/is_reply` 都不能替代上述证据。新规则加入精确 `Comment.clone()Ljava/lang/Object;` 锚点，运行时另验实例方法、无参数及可接受返回类型。

当前规则合计 212 条，指纹为 `350b44517ddf827c00c911f16077e521668b64c071c6dd4a9fbbb258d53a9787`。安全计数只记录缺父字段通知的接收者/原评归属数量；`parent_context_ready` 表示父上下文满足，仍需通过可见性、目标身份、内容、时间与克隆校验，不能视为实际可发送数。

发送仍使用捕获作品与目标副本；预留前后复验作品 ID、CID、root，只有已验证原生作品绑定才允许目标省略重复作品 ID，非空冲突仍拒绝。准确回执才确认 `sent`，错误目标回执维持 `submitted`，不改变既有回执终结语义。

## 恢复旧水位与已观察验证

0.1.90 已按模型版本 2 扫描并可能越过这些跳过项。本轮将处理版本升为 3，同一次持久提交把旧版本 watermark 回退到原始 baseline，并写入版本 3；原 baseline 与全部 incoming claims 保留，新账号初始化也原子写版本 3。已发送、已提交、结果未知及已忽略记录继续防重，重扫不扩大到最初起点之前，提交失败时停止扫描。

| 阶段 | 已归档实际结果 | 证据与限制 |
| --- | --- | --- |
| 定向兼容 RED | 2 项，2 失败，0 错误、0 跳过 | `strict-parent-red.log`、`strict-parent-red-junit.xml`；覆盖无父引用的定向回复与 canonical 显式其他接收账号 |
| 第一轮专项 | 157 项，1 失败，0 错误、0 跳过 | `first-focused-tests.log`、`first-focused-junit/`；Hook 旧断言 `expected 2 / actual 3`，已改用当前版本常量 |
| 原始 APK 合同子集 | 11 项，0 失败、0 错误、0 跳过 | 包含于上述第一轮专项；静态合同，不是现场网络或动态 Hook 证据 |
| 最终专项 | **160 项，0 失败、0 错误、0 跳过** | `focused-tests.log`、`focused-junit/`、`focused-totals.json`；包含新的 UID、严格根层级、数字诊断、Inbox→Hook→Sender 与 Inbox→Publisher 联动 |
| 最终归档 APK 合同 | **3 项，0 失败、0 错误、0 跳过** | 同一次执行指定最终归档 APK；`final-apk-junit.xml`，总计 163 项、8 套件 |
| Release / Lint | 构建通过；0 错误、63 警告 | `release-build.log`、`lint-results-release.xml`；未重跑全量测试 |

七个专项套件分别为 Hook 49、Inbox 43、Ledger 31、Publisher 14、HostContract 11、HostAdaptation 3、HostScanner 9。发布器联动直接使用真实 Inbox 生成的副本，验证对方 CID 保持不变、根为本人原评、原通知未被修改，准确回执为 sent，其他目标回执仅为 submitted。这里的“真实 Inbox”指生产实现，输入模型和发布框架仍为本地测试构造。

### 最终安装包

| 项目 | 结果 |
| --- | --- |
| 文件 | `artifacts/DouAi-0.1.91-release.apk` |
| 版本 / 包名 | `0.1.91 / 92`，`io.github.yudigaga.douai` |
| 大小 | 4,485,005 字节 |
| SHA-256 | `00BF8F615C24F5CF23506A2D69B23779D819A05CBEA939CA445A3234A4CFCAFB` |
| 签名 | apksigner 验证通过，v3；证书 SHA-256 为 `FAE67C134F4C4FA73B8E34487C47A8C7F3E7C34B840BC245AFAC51585B7DFD47` |
| 一致性 | 与 `app/build/outputs/apk/release/app-release.apk` 哈希一致；元数据和校验和位于 artifacts 同版本文件 |

安装后重启抖音，保持“开启自动回复”和“接收评论回复”开启。旧账号升级会在原始起点之后重查漏项，同时保留所有已有防重记录；随后通过别人新回复本人在其他作品下的评论，验证实际触发、生成与发布。

现场没有捕获 `recipient_is_me`、本人 level1 的身份与内容等字段，不能把本地构造模型成功推广为四轮现场对象都符合新分支。也未找到统一 target 为空时自动按 nid 补详情再发送的原生链，本轮不据 legacy 字段缺失推定需要 hydrate。后续真机验收应分别确认严格分支命中、生成、原生请求接受及准确回执，不以“收到 21 项”当成已经恢复回复。

## Evidence → Finding → Path

观察日期均为 2026-10-19。联合源码/测试证据使用 `content_hash=n/a`；两个 case 内固定文件的 SHA-256 另列如下：

| Evidence | source_ref / artifact_path | repro_command | content_hash |
| --- | --- | --- | --- |
| E-001 | 原 ZIP、两份包含 0.1.90 的模块日志；原 ZIP 保留于 case 外的用户路径 | 下节原 ZIP 哈希与完成扫描检索 | ZIP 哈希见上文；case 内原 ZIP `artifact_path=n/a` |
| E-002 | `work/comment-reply-20261019-014527/runtime-counts.json`，case 相对路径 `runtime-counts.json` | 下节读取计数；可结合 E-001 复核 | `404FCDF16BBA2E7135D2305AC3B1420A3A323E0DCC199CF00EC33A26C80E49E6` |
| E-003 | 原始 APK 的指定 classes17/classes30 类、`CommentReplyHostContractTest` | 下节设置原 APK 并执行合同；受本地原 APK 可用性限制 | n/a，联合静态证据 |
| E-004 | Inbox、Event、Publisher、Ledger、Hook、规则，以及 `before-091/` 源码快照 | 下节源码检索；与本地快照比较 | n/a，联合源码证据 |
| E-005 | `strict-parent-red-junit.xml` 及日志；case 相对路径 `strict-parent-red-junit.xml` | 下节 XML 复核 | `38C248BE07266904B886EBA739B0FA0AC19A2BA373677864920F7055B13E0DF7` |
| E-006 | `first-focused-junit/` 七份 XML、`first-focused-tests.log` | 下节 XML 复核 | n/a，157 项归档联合证据 |
| E-007 | `focused-junit/` 八份 XML、`focused-tests.log`、`focused-totals.json` | 下节设置两个 APK 环境变量并执行；XML 计数 | n/a，163 项最终归档联合证据 |
| E-008 | 最终归档 APK、`release-build.log`、`signature.txt`、`apk-metadata.txt`、`lint-results-release.xml` | 下节构建、哈希与签名命令 | APK 哈希见安装包表；其余联合证据 n/a |

| Finding | 结论 | severity / category | evidence_ids | confidence / status | location |
| --- | --- | --- | --- | --- | --- |
| F-001 | Q 已读取，缺 canonical 父字段造成每轮 18 项跳过；通知实际方向和新旧时间尚不确定 | n/a_re / other | E-001,E-002,E-004 | high / validated（仅日志与分支观察） | 0.1.90 Inbox 提取逻辑 |
| F-002 | 接收 UID、宿主 clone 与明确根 ID 的发布 helper 可分别提供静态合同 | n/a_re / reverse_algo | E-003,E-006 | high / validated（仅 DEX 合同） | Comment、0yXF、0y6L；残余风险为现场字段未捕获 |
| F-003 | 严格 Q 定向归一化与版本 3 重扫不扩大用户范围，保留全部防重记录 | n/a_re / design | E-004,E-005,E-007 | high / validated（本地模型、迁移与发布联动）；现场字段仍未知 | Inbox、Ledger、Hook、Publisher |
| F-004 | 新版实际恢复回复 | n/a_re / other | E-001,E-007 | low / candidate | 真机响应、生成、提交与回执；尚无设备验证 |
| F-005 | 最终安装包满足本地构建、签名、元数据与合同检查 | n/a_re / other | E-007,E-008 | high / validated（本地产物） | 0.1.91 归档 APK；不等于真机验收 |

P-001，`path_type=callflow`：Q 收件与缺父引用观察（E-001/E-002，F-001）→ 明确接收人为本人并核验本人顶层原评（E-003/E-004，F-002/F-003）→ 克隆目标并仅归一化副本根字段（E-003/E-004，F-003）→ 模型版本 3 回原 baseline、保留 claims（E-004，F-003）→ 发布前后复核、准确回执才成功（E-004/E-007）。终点的现场恢复仍为 F-004 候选结论，不由静态合同或本地构造数据升级为已验证。

## 复核命令

在仓库根目录 PowerShell 执行。当前源码重放验证最新实现，不会重现旧源码 RED；RED 结论以归档 XML 为准。专项依赖已配置的本地 Android/JDK 环境与原始宿主 APK。

```powershell
Get-FileHash -Algorithm SHA256 -LiteralPath 'F:/qq/MobileFile/LSPosed_20261019_014527.zip'
$caseRoot = 'work/comment-reply-20261019-014527'
Get-Content -LiteralPath (Join-Path $caseRoot 'runtime-counts.json')
rg -n 'DouAi 0\.1\.90 /|Comment reply inbox scan complete:.*detail=' $caseRoot -g '*modules*.log'
Get-ChildItem -LiteralPath (Join-Path $caseRoot 'focused-junit') -Filter '*.xml' | ForEach-Object {
    [xml]$j = Get-Content -LiteralPath $_.FullName
    [pscustomobject]@{ Suite=$j.testsuite.name; Tests=$j.testsuite.tests; Failures=$j.testsuite.failures; Errors=$j.testsuite.errors; Skipped=$j.testsuite.skipped }
}
rg -n 'parentlessOriginal|copyWithParent|replyToUserId|comment_reply_comment_clone|INBOX_MODEL_VERSION' app/src/main/java/com/douai/module/feature/NativeCommentReplyInbox.java app/src/main/java/com/douai/module/feature/CommentReplyLedger.java app/src/main/assets/douyin-host-rules.tsv
$env:DOUAI_TEST_APK = (Resolve-Path -LiteralPath '抖音-40.6.0_40609900_20260922 (400601).apk').Path
$env:ANDROID_HOME = 'C:\Users\95300\AppData\Local\Android\Sdk'
$env:DOUAI_VERIFY_RELEASE_APK = (Resolve-Path -LiteralPath 'artifacts/DouAi-0.1.91-release.apk').Path
.\gradlew.bat :app:testDebugUnitTest --tests 'com.douai.module.scan.CommentReplyHostContractTest' --tests 'com.douai.module.feature.NativeCommentReplyInboxTest' --tests 'com.douai.module.feature.NativeCommentReplyPublisherTest' --tests 'com.douai.module.feature.CommentReplyLedgerTest' --tests 'com.douai.module.feature.CommentReplyHookTest' --tests 'com.douai.module.scan.HostAdaptationTest' --tests 'com.douai.module.scan.HostScannerTest' --tests 'com.douai.module.scan.ApkArtifactContractTest' --console=plain
.\gradlew.bat :app:assembleRelease :app:lintRelease --console=plain
Get-FileHash -Algorithm SHA256 -LiteralPath 'artifacts/DouAi-0.1.91-release.apk'
& 'C:\Users\95300\AppData\Local\Android\Sdk\build-tools\36.0.0\apksigner.bat' verify --verbose --print-certs 'artifacts/DouAi-0.1.91-release.apk'
```
