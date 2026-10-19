# 从本人评论线程补全通知的回复关系

本文记录 0.1.92 的历史实现；后续设备日志暴露宿主 Task 方法名适配遗漏，修正见[0.1.93 等待适配报告](2026-10-19_comment-reply-task-await-report.md)。

分析日期：2026-10-19，Asia/Shanghai；普通 APK 诊断结构 `flavor=null`。范围与授权见 [scope.md](../work/comment-reply-20261019-083513/scope.md)，过程见 [timeline.md](../work/comment-reply-20261019-083513/timeline.md)。[0.1.91 定向兼容报告](2026-10-19_comment-reply-directed-report.md)保留为历史记录。

## 结论与当前边界

0.1.91 已载入并持续扫描，但没有生成可回复事件，故本次失败仍在收件提取阶段，尚未进入评论回复的 AI 生成和原生发送。新增的严格缺父引用分支要求明确的本人接收 UID 与本人原评上下文；这次实际计数没有满足这些条件。通知缺少这些字段不能证明它是顶层评论，也不能证明它回复本人。用户已确认消息页能看到测试的新记录，因此主入口继续以前台定时扫描为准，通知横幅不是触发前提。

本轮采用新的原生取证路径：从“自己的评论”取得本人根评论及作品，再查询该根下的真实回复；仅当通知摘要与真实回复的 CID、发送者、作品一致，且真实直接父评论确为本人原评时，才交给既有发布器。此路径绕开摘要中缺失的父关系，保留用户指定范围。最终本地交付为 0.1.92 / versionCode 93，198 项专项与原始 APK 全部 220 条规则的实际扫描已通过，共 199 项；最终归档 APK 的 3 项合同也通过，均零失败、零错误、零跳过。Release/Lint、v3 签名与归档哈希核对通过，Lint 0 错误、63 警告。原生调用存在和本地通过不等于线上回复已经恢复，真机结果仍未验证。

## 0.1.91 新日志

输入为 `F:/qq/MobileFile/LSPosed_20261019_083513.zip`，SHA-256：

```text
DB5161B9DCF840510D2609634D3650201F19EB7380590EA5813B31A54187F839
```

仅统计明确载入 0.1.91 的进程区间：9 次载入，19 次完成扫描，完成时间为 07:20:34.790 至 08:34:13.383。每轮返回 21 项，共 **399 次重复扫描观测**，不是 399 个唯一通知。去标识化明细见 [log-timeline-notes.md](../work/comment-reply-20261019-083513/log-timeline-notes.md)与 `log-timeline-sanitized.json`。

| 观测 | 每轮 | 19 轮合计 |
| --- | --- | --- |
| 模型 | group44；detail20、no-target1 | 399 次观测 |
| 父引用与资格 | parentMissing20、recipientOwn0、level1Missing20 | 全轮未形成可回复事件 |
| 缺父引用和已验证本人上下文而跳过 | 15 | 285 |
| 缺少可用目标或作品而跳过 | 4 | 76 |
| 显式指向其他接收账号而跳过 | 1 | 19 |
| 缺少支持的 payload/target | 1 | 19 |

`level1Missing20` 合并了“对象缺失”与“从原始 user 字段无法取得作者”等情况，不能写成 Q.e 全部为 null。宿主有效用户 getter `Comment.LLLLZLL()` 优先 `commentUser`、随后才是 `user`；本轮改用该 getter，并增加回源阶段、通知时间与未解析水位计数，原始层级计数仍不区分上述两种缺失。

前 17 轮的通知集合相同，第 18 轮新增一个身份并移除一个，第 19 轮保持；联合集合为 22 个。新增项同样因缺少父引用与已验证本人上下文被跳过。日志没有建立它与用户指定测试记录的逐条对应关系。处理版本 3 的 baseline 重扫已经发生一次，不能将本次零事件归因于未执行旧水位迁移。普通自动评论另有原生请求接受日志，不能用来证明评论回复链路已经发送。

## 原始 APK 合同与新回源路径

静态样本仍为本地抖音 40.6.0 原 APK。工具为 apktool 既有 smali、jadx 1.5.5 单类反编译、dexlib2 3.0.9 有界类读取和 Build Tools 36 aapt。仅检查 Java/DEX、Retrofit、账号与评论模型依赖，不涉及新的 so/PE 导入分析；没有代用户请求抖音线上接口。

| 复核项 | 原始证据 | 结论 |
| --- | --- | --- |
| 收件分组 | `0dFd.LJIIIIZZ → 0dED.LIZ("2")`；默认 44；原生 HeaderTitleVM 绑定资源 `0x7f116b6c` | 原 APK 文字是“收到的评论”；520 对应“弹幕/收到的弹幕”，不补扫 520 |
| PQ 与 PP | 同一个 notice endpoint，前 23 个槽一致；PP 多出四个 frame/选择状态字段 | 未证明接口切换能补父关系；两者原生调用均可关闭类型过滤，不据此认定 PQ 只返回顶层评论 |
| 通知详情 | `getNoticeDetail(id_list, notice_group)`；原生 `id_list` 为 `List<0dFs>` 序列化，每项含 notice_id 与 type | 不能传普通 ID 字符串列表；本轮未采用此路径，也未证实它能补全原父评论 |
| 本人评论列表 | `0dHa.b:0dPP`；`getMineComment(long start_time, int count)`；GET `/aweme/v1/comment/self/list/` | 原始 `MineCommentResponse.items` 是 `List<CommentNotice>`，包含 comment 与 aweme；依 minTime/hasMore 翻页 |
| 根下回复列表 | `0y6o → 0y6l → 0yTH.LJIJJ → CommentRealApi.LJIJJLI` | 根 CID 进入 comment_id，作品由宿主 context 解析为 item_id；cursor/count/merge_cursor 原生分页，无须复制网页签名 |
| 回复响应与目标 | `CommentItemList.items:List<Comment>`，wire 为 comments；另有 cursor、hasMore、mergeCursor | 使用真实 incoming Comment 作为原生输入目标；回源成功不需 clone 推造其父关系 |

具体方法、字段、参数槽与 smali 路径见 [分组复核](../work/comment-reply-20261019-083513/new-route-audit.md)、[原生线程入口](../work/comment-reply-20261019-083513/reply-list-seam.md)。真实 APK 合同新增 5 项，合计 16 项，均已实际执行通过。新增 8 条精确规则后共 220 条；完整原始 APK 扫描全部唯一，指纹为 `a9f5e209aabc3f42afe845c41a70e9b1fe3e39a77b61d57ed62b56ba5e7d516d`。

### 匹配和执行上限

1. 通知仅提供候选线索；保留其 incoming CID、发送者、作品和时间，不能直接授权发送。
2. 本人原评必须有真实有效用户且 UID 为当前账号，CID、正文、作品可用；两个父字段均为空或 0，只纳入本人顶层原评。`getItems()` 本地设置的 isMineComment 标志不能替代作者证明。
3. 回源回复必须匹配摘要 incoming CID、发送者与作品。真实直接父 CID 取非零 replyToReplyId，否则 replyId，并要求等于本人原评 CID；同根下别人互相回复仍拒绝。显式其他接收 UID、作品冲突、缺少父证据均不发送。
4. 保留真实回复对象交给既有 Publisher，继续核验目标身份和准确回执；不将“请求接受”当作发布成功。
5. 每轮最多读取本人评论 5 页、每页 20 项，缓存最多 100 个根；轮转查询最多 8 个根，每根最多 3 页，合计时间预算 60 秒。账号变化、关闭或取消时停止，不提交本轮扫描水位。
6. 安全计数不记录账号、评论正文或原始响应；未解析的新通知不得被成功扫描水位越过。Hook 使用原生 source 的完成上界限制最终写入水位，后面的已处理事件不能把上界重新抬高。回源失败时，已有 canonical 证据的事件仍可处理并保留待解析范围；无可用事件则报告 THREAD_LOOKUP 失败。取消、中断和账号变化仍整体停止。
7. 处理 schema 升为 4，旧账号仅回到原始 baseline 一次，保留全部 incoming claims，避免漏项同时保持防重。本人原评可以早于 baseline，资格时间仍用对应新通知的到达时间；不把根的创建时间用作新回复过滤条件。

以上是限定的补证路径，不把“该根由本人发出”推成“根下全部互动都回复本人”。原评超出本轮有界查询范围或返回关系仍不完整时，继续保留待解析范围，不授权发送。服务器返回字段、最小 builder 参数可接受性以及真实发送效果仍依赖安装后的日志。

## 本地验证记录

| 阶段 | 实际结果 | 归档 |
| --- | --- | --- |
| 通知回源、有效用户与 schema4 RED | 5 项，5 失败 | `thread-backfill-red.log`、`red-junit/` |
| 水位上界与 canonical 事件保留 RED | 2 项，2 失败 | `watermark-red.log`、`watermark-red-junit/`；测试初次编译问题修正后执行的行为 RED |
| 最终专项 | **198 项，0 失败、0 错误、0 跳过** | `focused-tests.log`、`focused-junit/`、`focused-totals.json` |
| 原始 APK 全部规则扫描 | **220/220 唯一**；1 项、0 失败、0 错误、0 跳过 | 同一轮 `Douyin406AnchorTest.verifiedFeedAnchorsAreUniqueAcrossTheLocalHostDexSet`，合计 199 项、9 套件 |
| Release / Lint | 构建成功；0 错误、63 警告 | `release-build.log`、`lint-results-release.xml` |
| 最终归档 APK 合同 | **3 项，0 失败、0 错误、0 跳过** | `final-apk-contract.log`、`final-apk-junit.xml`；指定下面的最终归档 APK |

专项套件为 Hook 51、Inbox 50、ThreadLookup 22、Ledger 32、Publisher 15、HostContract 16、HostAdaptation 3、HostScanner 9。两个完整回源用例分别贯通真实 Lookup/Inbox 到 Hook 调度和 Publisher，网络与发布回调仍为本地替身：保留服务端目标对象、本人旧原评与新通知时间，重复扫描不重发，准确回执才记 sent。

第一轮 192 项曾有 1 个合同测试失败，原因是将 dexlib2 的 MethodParameter 对象直接与 String 比较，已改取参数类型文本；随后整链测试的延迟重查队列和水位预期按实际调度时序修正。其余行为断言保留，最终结果如上。没有重跑全量测试。

### 最终安装包

| 项目 | 结果 |
| --- | --- |
| 文件 | `artifacts/DouAi-0.1.92-release.apk` |
| 版本 / 包名 | `0.1.92 / 93`，`io.github.yudigaga.douai` |
| 大小 | 4,501,540 字节 |
| SHA-256 | `50E8A59E2854B59D8C31D67AB31F5F1A5BF684F8002AFCCF5209FEACA48B8599` |
| 签名 | apksigner 验证通过，v3；证书 SHA-256 `FAE67C134F4C4FA73B8E34487C47A8C7F3E7C34B840BC245AFAC51585B7DFD47`，与前版一致 |
| 一致性 | 与 `app/build/outputs/apk/release/app-release.apk` 哈希一致；同版本 JSON 与 SHA256SUMS 已归档 |

安装后重启抖音并等待适配扫描完成，如提示再次重启则按提示完成；保留“开启自动回复”和“接收评论回复”开启。APP 横幅并非必要，前台定时扫描仍是主入口。升级会从原 baseline 重查漏项，保留所有已发送、已提交和结果未知的记录；不补发原始起点之前的历史来评。

## Evidence → Finding → Path

所有观察日期为 2026-10-19；case 根为 `work/comment-reply-20261019-083513/`。原 ZIP 位于用户路径不复制公开；原日志、DEX、身份值和响应正文留在本地 work。联合源码证据记 `content_hash=n/a`，固定文件哈希如下。

| Evidence | source_ref / artifact_path | repro_command | content_hash |
| --- | --- | --- | --- |
| E-001 | 原 ZIP 与本地 module/full 日志；ZIP case 路径 n/a | 下节 ZIP 哈希及 0.1.91 完成扫描检索 | ZIP 哈希见上文 |
| E-002 | case `log-timeline-sanitized.json`、`log-timeline-notes.md` | 下节读取统计并结合 E-001 复核 | JSON `66BAAF113999FB887849A6D4A7F31F459A3E92331AB109419DC36BAD6B076F02` |
| E-003 | case `notice-group-resource-evidence.txt`、`new-route-audit.md` | 下节读取资源证据及原方法引用；资源摘录来自原 APK aapt | 资源摘录 `4339810DABBC8870111F5F2C21B62C4BAEEE201B88E5BE7D7C7747A4F94547A4` |
| E-004 | case `reply-list-seam.md`、原 classes17/classes30、单类 jadx 文件与 HostContractTest | 下节原 APK 合同及接口检索；仅离线 | 入口报告 `15B0BCFDD3062E852A5CB4BF9E679307DA06A7BC1EF43A6505A917D8F8E2C5FC` |
| E-005 | 用户确认测试记录出现在消息页；Inbox/ThreadLookup/Ledger/Hook 源码及回归 | 本会话人工确认；下节源码检索与测试命令 | n/a，人工与开发中联合证据 |
| E-006 | `red-junit/`、`watermark-red-junit/`、`focused-junit/` 九份最终 XML 与统计 | 下节专项/全规则扫描、读取 XML 计数 | n/a，联合测试证据；199 项实际执行零跳过 |
| E-007 | 最终归档 APK、构建/Lint/签名日志、`final-apk-junit.xml` | 下节构建、哈希、签名及最终 APK 合同命令 | 最终 APK 哈希见安装包表；其余联合证据 n/a |

| Finding | 结论 | severity / category | evidence_ids | confidence / status | location / 限制 |
| --- | --- | --- | --- | --- | --- |
| F-001 | 0.1.91 完成扫描但提取为零，失败发生在回复 AI/send 之前 | n/a_re / other | E-001,E-002 | high / validated，仅现场阶段定位 | NativeCommentReplyInbox；不确定每条通知真实方向 |
| F-002 | group44 的收到评论语义正确；520 是弹幕 | n/a_re / reverse_algo | E-003,E-004 | high / validated，仅原 APK 静态合同 | 0dFd/0dED、HeaderTitleVM、原资源 |
| F-003 | 原生 self/list 与根回复 wrapper 提供可补证的数据路线，本地集成保持严格归属、去重和水位边界 | n/a_re / reverse_algo | E-004,E-005,E-006 | high / validated，仅静态合同与本地构造链路 | 0dPP、0y6o/0y6l/0yTH、CommentItemList；线上字段与发送仍未知 |
| F-004 | 限定回源及 schema4 重扫能恢复本次真实自动回复 | n/a_re / design | E-001,E-004,E-005 | low / candidate | 尚无新版本真实响应、发送和准确回执；不得据静态合同升级为已恢复 |
| F-005 | 最终 0.1.92 安装包满足本地构建、签名、元数据和合同检查 | n/a_re / other | E-006,E-007 | high / validated，仅本地产物 | 不等于真机接口和发送成功 |

P-001，`path_type=callflow`：通知产生候选（E-001/E-002，F-001）→ 本人 self/list 验证原评与作品（E-004，F-003）→ 宿主 builder 查询根下真实回复（E-004，F-003）→ 按 incoming CID、发送者、作品及直接父 CID 严格匹配（E-005）→ 真实目标交给原生 Publisher 并等待准确回执（E-005）→ 未解析项不越水位、schema4 原 baseline 重扫且保留 claims（E-005）。终点真实恢复仍为 F-004 候选结论。

## 复核方式

在仓库根目录 PowerShell 执行。日志统计复核不触发网络；测试使用本地原 APK 和构造的请求替身，不向抖音发请求。原 APK 及本机 Android/JDK 环境须已存在；当前源码重放不等于重现旧版本失败，RED 以最终归档 XML/日志为准。

```powershell
Get-FileHash -Algorithm SHA256 -LiteralPath 'F:/qq/MobileFile/LSPosed_20261019_083513.zip'
$caseRoot = 'work/comment-reply-20261019-083513'
Get-Content -LiteralPath (Join-Path $caseRoot 'log-timeline-sanitized.json')
Get-Content -LiteralPath (Join-Path $caseRoot 'notice-group-resource-evidence.txt')
rg -n 'DouAi 0\.1\.91 /|Comment reply inbox scan complete:' $caseRoot -g '*modules*.log'
rg -n 'OWN_PAGES|THREAD_PAGES|ROOTS_PER_ROUND|MAX_ROOTS|ROUND_TIMEOUT|validIncoming' app/src/main/java/com/douai/module/feature/NativeCommentThreadLookup.java
$env:DOUAI_TEST_APK = (Resolve-Path -LiteralPath '抖音-40.6.0_40609900_20260922 (400601).apk').Path
$env:ANDROID_HOME = 'C:\Users\95300\AppData\Local\Android\Sdk'
.\gradlew.bat :app:testDebugUnitTest --tests 'com.douai.module.scan.CommentReplyHostContractTest' --tests 'com.douai.module.feature.NativeCommentReplyInboxTest' --tests 'com.douai.module.feature.NativeCommentThreadLookupTest' --tests 'com.douai.module.feature.CommentReplyLedgerTest' --tests 'com.douai.module.feature.CommentReplyHookTest' --tests 'com.douai.module.feature.NativeCommentReplyPublisherTest' --tests 'com.douai.module.scan.HostAdaptationTest' --tests 'com.douai.module.scan.HostScannerTest' --tests 'com.douai.module.scan.Douyin406AnchorTest.verifiedFeedAnchorsAreUniqueAcrossTheLocalHostDexSet' --console=plain
.\gradlew.bat :app:assembleRelease :app:lintRelease --console=plain
$env:DOUAI_VERIFY_RELEASE_APK = (Resolve-Path -LiteralPath 'artifacts/DouAi-0.1.92-release.apk').Path
.\gradlew.bat :app:testDebugUnitTest --tests 'com.douai.module.scan.ApkArtifactContractTest' --console=plain
Get-FileHash -Algorithm SHA256 -LiteralPath $env:DOUAI_VERIFY_RELEASE_APK
& 'C:\Users\95300\AppData\Local\Android\Sdk\build-tools\36.0.0\apksigner.bat' verify --verbose --print-certs $env:DOUAI_VERIFY_RELEASE_APK
```

当前 ADB 无连接设备，未真机验收。应分别观察候选数、self/list 页数、根查询页数、严格匹配数、未解析水位、AI 生成、原生请求和准确回执；仅出现“扫描 21 项”或本地测试通过均不足以判定恢复。没有远端发布。
