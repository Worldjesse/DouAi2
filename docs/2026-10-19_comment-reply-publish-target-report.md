# 评论回复的原生投递目标

日期：2026-10-19，Asia/Shanghai；普通 APK 功能诊断，`flavor=null`。本地范围记录为 `work/comment-reply-20261019-114203/scope.md`，过程记录为同目录 `timeline.md`；这些材料不随公共仓库分发。前一轮[评论素材修复](2026-10-19_comment-reply-comment-media-report.md)继续保留。

## 结论与边界

0.1.95 已越过识别和生成阶段，两次 AI 均返回 HTTP 200，分别生成 36、38 字符，原生发布请求均被接受。两次回调都是 `submitted; code=0`，均未通过准确回复目标的 `sent` 校验；用户确认内容实际落在作品评论区。本轮确认模块只传了目标 Comment，却遗漏宿主普通发布分支使用的两个继承定位字段，导致有效回复定位为空。

本轮交付 0.1.96 / versionCode 97：同时设置根评论、具体被回复评论和原生目标对象，并使用宿主实际 helper 在提交前核对最终定位。保留准确回执、账号有效性、防重与 ledger，不重发旧的未知结果。244 项相关检查及最终归档 APK 的 3 项合同检查全部通过，均零失败、零错误、零跳过；全部 227 条原宿主规则唯一匹配。Release / Lint 与签名、归档核对通过；仍不能据此宣称手机投递位置已经恢复。

## 运行时证据

输入 `F:/qq/MobileFile/LSPosed_20261019_114203.zip`，SHA-256：

```text
1D6EC6B20CD1BC14B0B25F32CDC700FF699354A59937CCB2139A84F387FC7A2C
```

只统计 module 日志中 11:17:15.920 明确加载 0.1.95 后的主进程 PID 23922，排除前序 PID 23120 和重复的 verbose 日志。两次完整序列如下；本轮不把反复扫描得到的匹配数相加为唯一回复数。

| 序列 | AI 请求 / 返回 | 生成 | 原生接受 / 回调 | 可确认事实 |
| --- | --- | --- | --- | --- |
| 第一次 | 11:17:33.557 / 11:17:36.836，HTTP 200 | 11:17:36.838，36 字符 | 11:17:44.888 / 11:17:45.356，submitted、code 0 | 生成和提交已发生；准确回复目标未确认 |
| 第二次 | 11:40:35.589 / 11:40:40.089，HTTP 200 | 11:40:40.096，38 字符 | 11:40:48.141 / 11:40:48.678，submitted、code 0 | 同上 |

安全汇总保存在本地 `work/comment-reply-20261019-114203/runtime-safe-stats.json`。原始日志包含用户数据，仅保留本地，不在报告复制身份、正文或请求体。此次无需继续排查 AI、图片或视频读取；问题发生在生成后的原生投递定位。

## 原生合同与根因

原始 APK `classes30.dex` 的准确类型为 `LX/0yFs;`，继承 `LX/0yH0;`。解包文件存在大小写碰撞，`0yFs.1.smali` 才对应本类型，不能读取 `0yFS.smali` 推断合同。新增自动化直接读取原始 DEX 的准确描述符。

| 原生输入 | 含义 | 证据 |
| --- | --- | --- |
| `0yFs.p` | 实际被回复的完整 Comment | `0yFq.LIZ()` 从 builder 的目标对象字段复制 |
| 继承的 `0yH0.b` | 所属根评论 CID，发到 `reply_id` | 原生 UI 读取目标的 `replyId`，经 `0yH8.LIZJ` 复制 |
| 继承的 `0yH0.e` | 具体被回复的 incoming CID，发到 `reply_to_reply_id` | 原生 UI 读取目标的 `cid`，经 `0yH8.LJ` 复制 |

`0y6L.LJ(Comment,0yFs)` 和 `LJFF(Comment,0yFs)` 含多条实验与目标类型分支。普通分支在 `00Wd.LIZ()` 与 `0xyq.LIZIZ(target)` 均为 false 时，直接返回模型的 `b/e`。仅看到它们也有从 `p` 读取 `replyId/cid` 的分支，不能推断所有运行时配置都会自动补全定位。原生模型构造器不会初始化 `b/e`。

`CommentPublishRequest.LJII(Object)` 将上述 helper 的结果直接传到普通 `/aweme/v1/comment/publish/` 的 `reply_id`、`reply_to_reply_id`；另一个 evaluate 发布入口也保留同样的数据流。详细原生位置与参数证据保存在本地 `work/comment-reply-20261019-114203/native-publish-target-contract.md`。本轮没有将 evaluate 接口误标为语音接口。

修复前的 `NativeCommentPublisher.send` 只设置 `p`，所以普通分支得到空 root/direct，符合用户看到的作品顶层评论。修复前真实发布入口回归已出现同型 RED：预期 inherited root 为 `root`，实际为 `null`；不是 AI 返回空内容，也不是匹配到了别人的其他评论。

## 修复行为

- 对已严格验证的 incoming，设置 `p=实际 Comment`、`b=event.rootId`、`e=event.incomingId`。若目标是根评论则 `e="0"`；本轮用户场景要求对嵌套 incoming 的准确回复。
- 新增 `comment_reply_publish_root`、`comment_reply_publish_direct` 两条精确宿主规则，绑定 `0y6L.LJ/LJFF(Comment,0yFs):String`。规则总数 227，指纹为 `cfc4af969972d52cc5cd0332c135dd8789e6473a306d9881f30373f2c80a376d`。
- 评论回复路径按需解析两个 helper，检查它们对当前完整模型算出的有效 root/direct 与事件一致。完成发送预留后再检查一次；不一致就在实际 native submit 之前拒绝，避免后续钩子或状态变化把回复降成作品评论。
- 普通自动评论不依赖新增回复 helper。保留捕获作品校验、账号有效性、准确回执、防重和 schema4；不清除旧的 submitted/unknown，不将 code 0 或 native accepted 当作准确回复成功。
- AI 输入继续只使用双方评论文字和对方评论附图，不恢复原作品媒体或描述读取。此轮修复的是投递目标，作品对象仍仅供原生准确投递。

## 验证记录

| 验证 | 当前证据 | 结论边界 |
| --- | --- | --- |
| 修复前 RED | 1 项执行、1 失败；期望 inherited root 为 root，实际 null；XML 已归档 | 复现生产遗漏字段导致的有效目标缺失 |
| 原 APK 数据流合同 | 新增 4 项，保留原 18 项，共 22 项全部通过 | 直接读取准确 DEX 描述符，包含在下行 244 项内 |
| 行为专项与全规则扫描 | 244 项、0 失败、0 错误、0 跳过；227/227 规则唯一匹配 | focused-tests.log、focused-junit/、focused-totals.json |
| Release、Lint、签名 | 构建通过，Lint 0 错误、63 警告；v3 验证通过且证书与前版一致 | release-build.log、lint-results-release.xml、signature.txt |
| 最终归档 APK 合同 | 3 项通过，0 失败、0 错误、0 跳过 | final-apk-contract.log、final-apk-junit.xml；指定最终归档包 |
| 修复后真机正确位置 | 尚无设备证据 | 必须用新收到的回复确认，不能用旧包的 code 0 代替 |

四项新增原始 DEX 测试分别验证：builder 对 `b/e/p` 的独立复制；原生 UI 的同一 Comment 根/CID 到同一 builder；普通 helper 的真实分支控制流；helper 结果到两个发布入口准确 wire 参数的寄存器数据流及其未被覆盖。回归必须测试真实 native submit 入口，不能只检查模型持有 `p`。

九套件分别为 AutoCommentHook 81、Dispatcher 24、CommentReplyHook 55、Ledger 32、Publisher 17、HostContract 22、HostAdaptation 3、HostScanner 9、原 APK 全规则扫描 1。Publisher 的模拟响应改为根据真正进入请求的 b/e 生成，覆盖嵌套／根评论／语音回复、普通作品评论、宿主解析不一致和预留后字段变动。原先的测试只证明 p 已设置，手工构造的正确回执未能暴露空定位。本轮未重跑全量测试，ADB 无连接设备，未远端发布。

### 最终安装包

| 项目 | 结果 |
| --- | --- |
| 文件 | `artifacts/DouAi-0.1.96-release.apk` |
| 版本 / 包名 | `0.1.96 / 97`，`io.github.yudigaga.douai` |
| 大小 | 4,518,048 字节 |
| SHA-256 | `8189C9C7A433D4C5F36679CC19D9AE4F77F5950D8657A47776E0F3F3028D1CAF` |
| 证书 SHA-256 | `FAE67C134F4C4FA73B8E34487C47A8C7F3E7C34B840BC245AFAC51585B7DFD47` |
| 归档 | 与构建输出哈希相同；同版本 JSON、SHA256SUMS，published=false |

旧未知请求不会自动重发，因此安装后应等待一条新收到的回复。此次已落错位置的两条内容不会由修复包删除。修复后的验收要求实际挂在回复者 incoming 之下，且日志或回执能够确认对应根与 incoming；提交成功的悬浮窗或 HTTP 200 都不足以单独判定。

## Evidence → Finding → Path

case 根为 `work/comment-reply-20261019-114203/`。联合源码与测试证据哈希记 n/a；固定文件使用 SHA-256。

| Evidence | source_ref / artifact_path | repro_command | content_hash |
| --- | --- | --- | --- |
| E-001 | 原 ZIP、module 日志、安全统计与用户位置反馈 | 下节哈希及按 PID 隔离查询 | ZIP 见上文；module 日志 `36B6C7BCEE8B75CC446FD5D46AE7A5C40F9A6D4B35E88C4489778D4A5D504413` |
| E-002 | 原始 classes30、`native-publish-target-contract.md`、原 APK 合同测试 | 下节配置原 APK 并执行 HostContract | 合同笔记 `55DBEA9AA7C58244010B69733E8AA8A379AB31610897232FF784DE3D7DED28D8` |
| E-003 | `reply-target-red.log`、`red-junit/TEST-com.douai.module.feature.NativeCommentReplyPublisherTest.xml` | 下节读取 XML；修复后的源码不再重现旧失败 | XML `9FC8DEEB89ABC060CD065125E16BB22980DD920C490C09818F507FFACFA02F90` |
| E-004 | NativeCommentPublisher、宿主规则、HostAdaptation、新增回归 | 下节离线专项；最终执行结果另计 | n/a，联合源码证据 |
| E-005 | focused-junit/、focused-totals.json、构建／Lint／签名日志、最终归档 APK、final-apk-junit.xml | 下节专项与最终 APK 合同 | APK 哈希见上表；其余联合证据 n/a |

| Finding | 结论 | severity / category | evidence_ids | confidence / status | location / 限制 |
| --- | --- | --- | --- | --- | --- |
| F-001 | .95 两次生成和提交均发生，但未获得准确回复成功，用户观察落到作品 | n/a_re / other | E-001,E-002 | high / validated | .95 native callback；日志不包含完整回执定位，位置由用户确认 |
| F-002 | 原生普通分支读取 b/e；模块只设置 p，遗漏有效定位 | n/a_re / reverse_algo | E-002,E-003 | high / validated | NativeCommentPublisher.send 与宿主 0y6L |
| F-003 | 补齐定位字段并校验宿主实际 helper 的有效目标，本地回归及产物检查通过 | n/a_re / design | E-002,E-003,E-004,E-005 | high / local_validated，待手机验收 | 不承诺修复后真机已正确投递 |

P-001，`path_type=callflow`：严格匹配 incoming（E-001）→ 生成 36/38 字符（E-001/F-001）→ 原生目标 Comment 和继承 b/e 构造（E-002/F-002）→ 两次 helper 有效目标预检（E-004/F-003）→ 原生发布 wire 参数（E-002）→ 保留原有准确回执校验（E-004）；修复后的手机实际位置为剩余验收。

## 离线复核

以下命令在仓库根 PowerShell 执行。原日志查询只输出固定诊断，专项使用本地原 APK 与替身。

```powershell
Get-FileHash -Algorithm SHA256 -LiteralPath 'F:/qq/MobileFile/LSPosed_20261019_114203.zip'
$caseModuleLog = 'work/comment-reply-20261019-114203/log__modules_2026-10-19T10-40-58.993189.log'
Get-Content -LiteralPath $caseModuleLog | Where-Object { $_ -match '\[ 2026-10-19T11:(?:1[7-9]|[2-5][0-9]):[0-9:.]+\s+10343:\s+23922:' } | Where-Object { $_ -match 'Comment reply (AI request sent|AI protocol=.*stage=response|generated|native request accepted)|Automatic comment native callback:' }
Get-Content -LiteralPath 'work/comment-reply-20261019-114203/red-junit/TEST-com.douai.module.feature.NativeCommentReplyPublisherTest.xml' -TotalCount 8
$env:DOUAI_TEST_APK = (Resolve-Path -LiteralPath '抖音-40.6.0_40609900_20260922 (400601).apk').Path
$env:ANDROID_HOME = 'C:\Users\95300\AppData\Local\Android\Sdk'
.\gradlew.bat :app:testDebugUnitTest --tests 'com.douai.module.feature.NativeCommentReplyPublisherTest' --tests 'com.douai.module.scan.CommentReplyHostContractTest' --tests 'com.douai.module.scan.HostAdaptationTest' --tests 'com.douai.module.scan.HostScannerTest' --tests 'com.douai.module.scan.Douyin406AnchorTest.verifiedFeedAnchorsAreUniqueAcrossTheLocalHostDexSet' --console=plain
$env:DOUAI_VERIFY_RELEASE_APK = (Get-Item -LiteralPath 'artifacts/DouAi-0.1.96-release.apk').FullName
.\gradlew.bat :app:testDebugUnitTest --tests 'com.douai.module.scan.ApkArtifactContractTest' --console=plain
```
