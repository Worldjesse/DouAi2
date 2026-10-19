# 评论回复回源的宿主 Task 等待适配

日期：2026-10-19，Asia/Shanghai；普通 APK 诊断，`flavor=null`。范围见 [scope.md](../work/comment-reply-20261019-094432/scope.md)，过程见 [timeline.md](../work/comment-reply-20261019-094432/timeline.md)。[0.1.92 回源报告](2026-10-19_comment-reply-thread-backfill-report.md)保留为历史记录。

## 结论与边界

0.1.92 已成功读取本人原评论，但四次进入真实子回复查询时都遇到 `NoSuchMethodException`，没有到达回复匹配汇总、评论回复 AI 或原生发送。原因是宿主混淆了 Bolts Task 的限时等待与错误 getter，模块仍按公共库的英文名称反射。上版测试替身也暴露英文 API，因此未覆盖这个差异。

本轮最终交付为 0.1.93 / versionCode 94，将五个 Task 方法纳入宿主精确适配规则，并改用真实混淆形状的测试替身。211 项专项与原始 APK 全部 225 条规则扫描已实际通过，合计 212 项；最终归档 APK 另 3 项合同通过，均零失败、零错误、零跳过。Release、v3 签名与归档哈希核对通过，Lint 0 错误、63 警告。修复针对可复现的方法名错误；本地通过不能据此认定真机自动回复恢复。目标始终是别人回复本人在他人作品下的评论。

## 现场证据

输入为 `F:/qq/MobileFile/LSPosed_20261019_094432.zip`，SHA-256：

```text
D4FBFBDFB47C8C7A660E622AA4E941F4B3ABCCDF6D74E6657357A806322D0D64
```

按明确的 0.1.92 加载会话隔离旧 0.1.91 日志：三个主进程、六轮扫描开始；其中四轮各读取本人评论五页，缓存 95 或 96 个原评，选出两个候选根，随后均报 `thread_lookup; cause=NoSuchMethodException`。另两轮只有开始记录，不能计为成功或失败。候选根读取成功不代表找到新回复。

四次失败时间为 09:40:33.335、09:41:33.287、09:42:33.123、09:43:33.870；零 `reply_queries` 汇总、零 inbox scan complete、零评论回复 native request accepted。六轮 126 次跳过对应 23 个唯一通知，不能当成 126 条新回复。日志未保留缺失方法签名；具体方法通过原 APK 与本地 RED 交叉定位，见 [安全统计](../work/comment-reply-20261019-094432/independent-runtime-audit.md)。

## 宿主合同与修复

原 APK `classes33.dex` 的 `Lbolts/Task;` 保留无参 `waitForCompletion()`，但不存在限时英文重载与 `getError()`。原生评论加载器 `0yWx.LIZJ`、回复列表请求及其 continuation 均使用下表宿主方法，详见 [task-contract.md](../work/comment-reply-20261019-094432/task-contract.md)。

| 操作 | 实际宿主方法 | 本轮处理 |
| --- | --- | --- |
| 限时等待 | `LJIJJ(long, TimeUnit):boolean` | 精确规则绑定、检查实例/参数/返回类型 |
| 错误读取 | `LJIIL():Exception` | 替换不存在的 `getError()` |
| 结果与状态 | `getResult()`、`isFaulted()`、`isCancelled()` | 名称保留，仍通过规则预绑定 |

五条规则在发请求前统一解析；规则总数由 220 增至 225，指纹为 `41ca1fe60d1df9ef3fa8ded07f2876ee4f6ebd6e83ea0afad8cc279e33a8638c`。原生 `LJIJJ` 只进行一次 `Object.wait(unit.toMillis(duration))`，因此未完成时继续按剩余预算轮询；每次传入正毫秒 1–500，避免亚毫秒转为 `wait(0)` 而无限等待。保留总计 60 秒预算、账号有效性、取消、中断及错误传播。

资格检查、本人评论与子回复查询预算、准确回执、防重和 ledger schema4 均保持。0.1.92 失败未推进扫描水位，不增加 schema5，也不清除已处理记录。本轮不扩大回复范围，不补造父关系。

## 验证记录

| 阶段 | 当前证据 | 结论边界 |
| --- | --- | --- |
| 修复前行为 RED | 1 项实际执行，1 失败；`NoSuchMethodException` | 与现场同型，XML 明确缺少 `waitForCompletion(long, TimeUnit)` |
| 测试替身 | Lookup 共 33 项，全部通过，替身不再提供猜测的英文方法 | 包括异步完成、提前唤醒、有效性失效、取消、close、中断、故障及构造前签名拒绝 |
| 原 APK 合同 | 保留原 16 项，新增 Task 两项，共 18 项，全部通过 | 覆盖混淆方法、原生调用与英文方法缺失 |
| 最终专项与全规则 | **212 项、0 失败、0 错误、0 跳过**；225/225 规则唯一 | `focused-tests.log`、`focused-junit/`、`focused-totals.json`；其中专项211、完整原APK扫描1 |
| Release / Lint | 构建成功；0 错误、63 警告 | `release-build.log`、`lint-results-release.xml` |
| 最终归档 APK 合同 | **3 项，0 失败、0 错误、0 跳过** | `final-apk-contract.log`、`final-apk-junit.xml`，指定最终归档包 |

ADB 没有连接设备，未进行真机验证；没有远端发布。安装后的关键验收是根回复查询成功、严格匹配具体新回复、AI 生成、原生发送及准确成功回执，不能只凭 `own comments ready` 判断恢复。

专项套件分别为 Hook 51、Inbox 50、ThreadLookup 33、Ledger 32、Publisher 15、HostContract 18、HostAdaptation 3、HostScanner 9；另有 1 项原始 APK 全规则扫描。未重跑全量测试。

### 最终安装包

| 项目 | 结果 |
| --- | --- |
| 文件 | `artifacts/DouAi-0.1.93-release.apk` |
| 版本 / 包名 | `0.1.93 / 94`，`io.github.yudigaga.douai` |
| 大小 | 4,501,634 字节 |
| SHA-256 | `044844D8DA1F1E7E881CE293DCA58409130A523C8C0DF7EC464CC3F987D1C32C` |
| 签名 | apksigner 验证通过，v3；证书 SHA-256 `FAE67C134F4C4FA73B8E34487C47A8C7F3E7C34B840BC245AFAC51585B7DFD47`，与前版相同 |
| 一致性 | 与构建输出 `app-release.apk` 哈希一致，同版本 JSON 与 SHA256SUMS 已归档 |

安装后重启抖音并等待新规则适配完成，如提示再次重启则完成该步骤；保持抖音前台及自动回复父开关、接收评论回复子项开启。此轮保持 schema4，先前失败未推进的记录会继续检查，不清除已有发送与未知结果记录。

## Evidence → Finding → Path

case 根为 `work/comment-reply-20261019-094432/`；原始日志仅留本地，报告不记录账号、评论身份或正文。下列联合源码证据哈希记 n/a；固定文件哈希可用 `Get-FileHash` 复核。

| Evidence | source_ref / artifact_path | repro_command | content_hash |
| --- | --- | --- | --- |
| E-001 | 原 ZIP、module 日志、安全统计 JSON | 下节哈希及安全统计脚本 | ZIP 见上文；JSON `D9D6CAC69D32882B63930763BC0BD2F5B6B03EFB2EE4343128956841D2EB3D82` |
| E-002 | `task-contract.md`、原 classes33/30、HostContractTest | 下节原 APK 合同 | 合同笔记 `CF18A1DBF4344803E596474D9A755EC63A1051DBEEFD441E8D28699395FF4CBD` |
| E-003 | `task-await-red.log`、`red-junit/TEST-com.douai.module.feature.NativeCommentThreadLookupTest.xml` | 下节读取 RED XML；修复后的源码不再重现旧失败 | XML `C9793C51D8E3222B1C87DF5328738BEB6C1BD1AE319A8DF5F25D25EB7C6972BE` |
| E-004 | NativeCommentThreadLookup、宿主规则及新替身 | 下节专项；仅离线，最终结果另计 | n/a，开发中联合证据 |
| E-005 | `focused-junit/` 九份 XML、`focused-tests.log`、`focused-totals.json` | 下节设置原 APK 并执行专项与全规则扫描 | n/a，212 项最终联合证据 |
| E-006 | 最终归档 APK、构建/Lint/签名/元数据、`final-apk-junit.xml` | 下节构建、签名和最终 APK 合同 | APK 哈希见安装包表；其余联合证据 n/a |

| Finding | 结论 | severity / category | evidence_ids | confidence / status | location / 限制 |
| --- | --- | --- | --- | --- | --- |
| F-001 | .92 在本人原评读取后、子回复等待阶段失败 | n/a_re / other | E-001,E-003 | high / validated | ThreadLookup；仅定位失败阶段 |
| F-002 | 英文反射与真实宿主 Task 合同不符 | n/a_re / reverse_algo | E-002,E-003 | high / validated | Task 限时等待及错误 getter |
| F-003 | 规则预绑定与正毫秒轮询修正上述合同差异 | n/a_re / design | E-002,E-004,E-005 | high / validated，仅本地合同与集成回归 | 设备真实响应和发送效果仍待确认 |
| F-004 | 最终包满足本地构建、签名、元数据与合同检查 | n/a_re / other | E-005,E-006 | high / validated，仅本地产物 | 不等于设备已回复成功 |

P-001，`path_type=callflow`：收到评论产生候选（E-001/F-001）→ 读取本人原评成功（E-001）→ 查询根下回复并以宿主 Task 等待（E-002/F-002）→ 使用已绑定方法、有界轮询取得结果（E-004/F-003）→ 继续原有身份匹配与准确回执链路；设备恢复仍未证实。

## 离线复核

以下命令在仓库根 PowerShell 执行，使用本地原 APK 和请求替身，不向抖音发送请求：

```powershell
Get-FileHash -Algorithm SHA256 -LiteralPath 'F:/qq/MobileFile/LSPosed_20261019_094432.zip'
& 'work/comment-reply-20261019-094432/audit-runtime-safe.ps1'
Get-Content -LiteralPath 'work/comment-reply-20261019-094432/red-junit/TEST-com.douai.module.feature.NativeCommentThreadLookupTest.xml' -TotalCount 14
$env:DOUAI_TEST_APK = (Resolve-Path -LiteralPath '抖音-40.6.0_40609900_20260922 (400601).apk').Path
$env:ANDROID_HOME = 'C:\Users\95300\AppData\Local\Android\Sdk'
.\gradlew.bat :app:testDebugUnitTest --tests 'com.douai.module.feature.NativeCommentThreadLookupTest' --tests 'com.douai.module.feature.NativeCommentReplyInboxTest' --tests 'com.douai.module.feature.CommentReplyHookTest' --tests 'com.douai.module.feature.CommentReplyLedgerTest' --tests 'com.douai.module.feature.NativeCommentReplyPublisherTest' --tests 'com.douai.module.scan.CommentReplyHostContractTest' --tests 'com.douai.module.scan.HostAdaptationTest' --tests 'com.douai.module.scan.HostScannerTest' --tests 'com.douai.module.scan.Douyin406AnchorTest.verifiedFeedAnchorsAreUniqueAcrossTheLocalHostDexSet' --console=plain
.\gradlew.bat :app:assembleRelease :app:lintRelease --console=plain
$env:DOUAI_VERIFY_RELEASE_APK = (Resolve-Path -LiteralPath 'artifacts/DouAi-0.1.93-release.apk').Path
.\gradlew.bat :app:testDebugUnitTest --tests 'com.douai.module.scan.ApkArtifactContractTest' --console=plain
Get-FileHash -Algorithm SHA256 -LiteralPath $env:DOUAI_VERIFY_RELEASE_APK
& 'C:\Users\95300\AppData\Local\Android\Sdk\build-tools\36.0.0\apksigner.bat' verify --verbose --print-certs $env:DOUAI_VERIFY_RELEASE_APK
```
