# DouAi 0.2.2 报告图片分享与校验诊断

版本 **0.2.2 / 103**，承接 [0.2.1](release-0.2.1.md) 已交付的本地产物。报告内直接显示完整 AI 结果，并可将当前范围的统计、图表与已有分析生成为图片分享，不必再次请求 AI；同时增强备份及语音失败的安全诊断。最终严格本地验收已通过，尚未远端提交或发布。**手机导入失败与语音拒绝的真正原因仍未查实，不宣称已修复现场故障或改善语音成功率。**

## 报告内 AI 结果与图片分享

在“扩展功能 → 本地数据分析”查看报告。主动请求的 AI 结果完成后，底部直接显示可选择复制的完整侧写和分图解读，仍可展开各区块查看对应图表分析。结果属于当次范围和请求；刷新、切换范围、清空或关闭后，旧结果不能作为新报告的分析。

“分享分析文字”保留原文字分享；“分享报告图片”在本地生成当前完整报告，包含全部统计区块、图表、采集边界说明及已有 AI 结果，不受当前折叠状态影响。没有 AI 结果也可生成图片，并明确注明尚无分析；生成图片本身不发送新的 AI 请求。

长报告按文本行和图表边界分为带连续页码的 PNG，最多 32 张；无法完整分页或超出预算时明确失败，不静默裁掉后文。Android 10 及以上将图片保存到相册 `Pictures/DouAi` 后打开系统分享面板；Android 9 先选择图片保存位置，多页时选择文件夹。分享前请核对图片中的作者、话题、最近记录与分析文字；AI 文字可能引用所填自述。图片不会附加数据库、接口配置或凭据。打开分享面板后图片会保留，取消选择接收方也不等于删除已保存图片；不需要时可自行删除。

范围改变、重新分析或关闭页面会取消尚未交接的图片任务，生成/保存失败不继续分享并尝试清理本次产物。图片功能已纳入本版完整本地回归；真实设备的相册、文件提供方及外部接收应用还未实测。操作说明见 [本地数据分析](local-analytics.md#请求-ai-分析与分享)。

## 设备反馈与本地证据

用户安装 0.2.1 后，原完整备份在合并和覆盖两种模式均显示校验失败；从原文件仅剥离分析数据的配置专用文件也在手机失败。用户还确认新导出的备份在一分钟内立即导入仍失败。因此，不能继续只用合并残留项、分析时间戳或备份过旧解释共同故障。

| 私有输入 | 大小与 SHA-256 | 已确认的范围 |
| --- | --- | --- |
| 原完整备份 | 22,559 字节；`02D2F41E35B5698A6E6EE54E034FBB6B18572F3E40950BCA6A53872BC2FF3FD3` | 97 项配置、2 条分析数据；本地两模式及默认文件读取到预览通过，用户手机两模式均失败 |
| 配置专用派生文件 | 24,849 字节；`0942D57A90C658B490184373930A8F414A670D358C8B33B76525D8DB2F40BDFD` | 原 `config` 的字段、数组及值递归完全相等，仅移除外层分析封装；本地真实 HostSettingsStore 两模式和默认读取到预览通过，手机仍失败 |

派生文件因排版不同而更大，不是内容增加；原文件未修改。真实文件只用于本地忽略目录中的证据测试，不进入源码 fixture 或报告正文。已有配置矩阵验证了正常宿主快照、运行时元数据，以及仅在合并时出现的残留结构/类型或 MiniMax 组合冲突；这些候选分支不能解释用户两模式共同失败。

## 新日志确认的 0.2.1 结果

新增日志 ZIP SHA-256 为 `BC0EA4A796F6A7C541E18591BC5840BE96AC501AA523283926E7497CCC3E8D87`。按实际进程加载记录隔离版本、去重后，确认 **22:16:43.747 已在抖音主进程加载 0.2.1 / API 102**；不能把同一日志文件早段的 0.1.100 回执计作新版结果。

0.2.1 的 **3 份独立语音、9 次提交全部失败**，回调均为宿主 `code=5; audio_phase=publish`；随后 3 次文本回退为 `sent`。本批没有新版语音 `sent`，也没有成功语音回调才会输出的 `audio_receipt`；字段未出现不能解释为 `audio_receipt=missing`，更不能声称语音成功率改善。`publish` 只证明 owned 原生模型已有上传结果标识，不能证明媒体可播放或发布参数被接受。code 5 的实际业务原因仍未知，不能套用 MiniMax 错误码表。

第三个任务第三次语音失败到决定文本回退之间有 **1278.388 秒，约 21 分钟**的空窗，现有日志未解释原因，不能归入 AI、TTS 或上传耗时。受控时间线与行号见 `work/voice-log-review-20261020-225123/controlled-summary.json`、`review.md`。包括 `full.log` 在内的这份 ZIP 没有 `ConfigTransfer` 读取或失败行，因此不能核对手机实际读取的文件大小、摘要和内部校验原因；这不代表用户没有导入。仍需新版界面提示与短摘要。

## 新增诊断

- 校验失败显示固定区段 `BUNDLE_JSON`、`BUNDLE_HEADER`、`CONFIG` 或 `ANALYTICS`，再给出固定原因码，区分格式版本、注册类型、未知键、模型/请求头/组件结构、密钥策略、当前存储读取、合并结果、MiniMax 组合及分析字段。
- 只显示白名单中的字段名。未知输入键不回显；`FailureKind` 只使用 `EXPLICIT`、`JSON`、`ARGUMENT`、`TYPE`、`SECURITY`、`UNSUPPORTED`、`LINKAGE`、`OTHER` 等固定类别，不保留或输出原异常消息、cause、stack 或输入值。既有明确大小限制及更具体的受控异常继续透传。
- 读完文件后，界面显示实际 `bytes` 和 SHA-256 前 12 位，便于确认手机读取的是否为同一文件；Logcat 的 `DouAi` 标签保留完整摘要和受控诊断。重新选择、切换模式、取消与晚回调按对应操作隔离，未增加自动写入。
- 仅为模块持有的语音失败回调增加 `audio_hint`、`audio_permission`、`audio_block`。提示仅从已核验的宿主异常 getter 读取最多 2048 个字符，先排除 HTTP(S) URL 和 Bearer/Basic 字段，再输出固定类别 `duration`、`size`、`format`、`permission`、`frequency`、`parameter`、`content`，多类以 `+` 连接；无法读取、空提示和未命中分别为 `unknown`、`empty`、`other`。不读取或输出 Throwable 原消息、提示原文、标识或凭据。
- 权限状态来自发送前已核验作品的快照，记录有严格字段类型的 `audioCommentPermission` 整数与 `blockAudio` 布尔值，不足则为 `unknown`；它们仅作诊断，不新增发送门禁，也不改变 ACK、重试或文本回退。这些提示类别是宿主文本线索，不是对 code 5 的业务解码，不证明对应限制或拒绝原因已经查实，没有语音成功率承诺。

用户可先反馈新版界面显示的完整分阶段提示和短摘要，无需上传密钥或完整 JSON。LSPosed 导出是否包含这些 Logcat 记录取决于采集范围，不保证模块日志导出一定含有它们。

备份仍执行原校验规则与大小限制，保留预览确认、合并/覆盖和取消语义；没有跳过分析数据或放宽配置验证。

## 已完成检查与剩余边界

新增诊断先有 **9 条实际红测**，证据在 `work/evidence/backup-validation-diagnostics-red-0.2.1/`。随后专项 **10 个套件、99 项通过，0 失败、0 错误、0 跳过**，其中 89 项为永久回归，10 项为使用私有输入的本地证据测试；完整 XML 在 `work/evidence/backup-validation-diagnostics-green-0.2.2/testDebugUnitTest/`。范围包含配置与分析校验、预览/确认/取消、Miuix 交互、受控原因与隐私断言、真实完整/配置专用输入及当前存储矩阵。私有证据测试不纳入最终源码完整回归。

语音提示与权限诊断先有 3 项针对性回归，其中 2 项实际失败，证据在 `work/evidence/voice-hint-permission-red-0.2.2/`；随后 5 个相邻套件、179 项通过，0 失败、0 错误、0 跳过，证据在 `work/evidence/voice-hint-permission-green-0.2.2/`。覆盖 owned 失败归属、普通文字与外部回调隔离、提示分类及隐私、权限快照与不新增门禁；这不是手机发送成功的验证，也不替代最后完整验收。

### 报告图片专项与实际 PNG

报告内完整 AI 结果、无 AI 也可导图且不再次请求等两项需求先有 **2 条实际红测**，归档 `work/evidence/report-image-red-0.2.2/`。报告、选择器、备份等相邻回归有 **81 项通过**；同期图片 helper 的早期环境参数顺序错误发生在 setup 前，不能记作业务缺陷红测。

最终图片专项的 **13 项均已实际通过**：12 项图片 helper 与 1 项长 AI 报告渲染。它们不是单次全绿记录：`work/evidence/report-image-final-preflight-0.2.2/` 为 12 通过、1 失败；失败 fixture 把会换行的长文本放进固定高度，先修为 24 条短行并断言末行确实装得下，再单项通过，归档 `work/evidence/report-image-tail-fixture-0.2.2/`。生产报告使用 wrap-content；这次 fixture 修正不能称为用户故障红绿闭环。专项覆盖末页正文、超限拒绝、流失败与取消、部分发布清理、多 URI 只读授权、分享交接后保留，以及清理和 MIME 查询不阻塞主线程的合同。

实际 PNG 位于 `artifacts/diagnostics-0.2.2/report-images/`，只以 `final-` 前缀及各套索引为准，早期同目录样例保留但不作为最终 QA：

| 本地 fixture 图片集 | 索引页数 | 输出边界 |
| --- | ---: | --- |
| `final-native-report-light` / `final-native-report-dark` | 各 6 页 | 1080 像素宽，每页不超过 4096 像素高 |
| `final-portrait-report-light` / `final-portrait-report-dark` | 各 9 页 | 720 像素宽，每页不超过 2048 像素高 |
| `final-forced-page` | 5 页 | 360 像素宽，每页不超过 900 像素高 |

每套的 `*-index.json` 列出完整文件列表，所列 PNG 均可解码；选取正式尺寸首尾及深色末页目视确认 AI 尾部标记、采集边界和正文保留，未再出现仅余装饰的空尾页。这是 **SDK 35 / Robolectric Native Graphics 本地渲染**；SDK 28 的 SAF 单文件/目录与清理验证使用模拟 provider，实际手机 MediaStore、设备峰值内存/耗时及外部接收应用读取尚未验收。逐页单张 ARGB 缓冲上限约 16.9 MiB，Picture、布局和编码另有开销，不能据此声称整个过程只使用这点内存。

预检 Release Lint 为 **0 错误、57 警告**。新增仅为 `LocalAnalyticsDialog` 在成功生成图片并打开分享后显示实际页数的 1 条 `SetTextI18n`；该键预算从 5 增为 6，保留专属批准理由，未压制整类警告。验收工具 34 项再次通过。这些预检结果不替代下面的新一轮最终严格验收。

平台与发布 DEX 复核记录在 `work/backup-runtime-review-20261020/findings.md`：已下载的 AOSP Android 15/16 `JSONObject`、`JSONArray`、`JsonReader` 对应源码逐字一致；0.2.1 原签名 APK 的相关 DEX 分支仍按预期装箱与检查 `Long`。独立 JVM probe 使用 Android 16 读取器对原输入完成完整封装两模式及配置重序列化回读，均通过，多时区分析检查也通过。官方源码入口为 [JSONObject](https://android.googlesource.com/platform/libcore/+/refs/heads/android16-release/json/src/main/java/org/json/JSONObject.java)、[JSONArray](https://android.googlesource.com/platform/libcore/+/refs/heads/android16-release/json/src/main/java/org/json/JSONArray.java)、[JsonReader](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/util/JsonReader.java)；对应 Android 15 链接与源码摘要保存在本地复核记录。

这些结果在所检查的源码、JVM 与 DEX 分支中没有发现能解释现场失败的数字类型不对称，不等于在设备执行了原 DEX，也不证明所有宿主环境差异已排除。分析捕获时间最多允许领先当前时钟约五分钟的边界仍保留；用户配置专用文件不含分析数据仍失败，不能据此把时钟当成共同根因。还须取得新版受控区段/原因、异常类别和读取摘要才能继续定位。

| 本版最终验收项目 | 状态 |
| --- | --- |
| 完整源码、界面及宿主合同回归 | 180 个套件、1921 次执行通过，0 失败、0 错误、0 跳过；私有输入证据测试不计入该源码完整回归 |
| 已失败的验收尝试 | `20261020T145053Z-33b47f8f` 的 Gradle 测试与 Lint 任务完成，但 Lint 增量预算失败，整体未通过；见下文 |
| Release、Lint、签名与对齐 | 正式 R8 构建、原长期证书 v3 签名及 `zipalign -c -P 16 4` 通过；最终 Lint 0 错误、58 警告，预算通过，无新增或减少键 |
| 最终 APK、大小、SHA-256 | `DouAi-0.2.2-release.apk`，4,616,355 字节；`03FFA3B781A5FAF5721CC0A7AA688E451BAB05200A0FCE160D143D578860E182` |
| 最终机器清单 | `artifacts/release-verification/20261020T154702Z-66c0c2f3/manifest.json`，`verified-local`、`published=false` |
| 验收工具回归 | 34 项通过 |
| 设备故障根因与新版导入恢复 | 尚未查实或验收 |
| 本版真机、真实账户调用、远端提交与发布 | 未执行 |

上述失败尝试的 Lint 为 0 错误、56 警告，但新增了 `ConfigTransferDialog` 的 1 条 `SetTextI18n`，同时移除了 1 条 `NewerVersionAvailable`。预算按问题键检查，警告总数相同不抵消新增项。该轮保留 `failure.json`，没有成功验收清单。随后对这一处“状态文案 + 当前操作文件摘要”组合单独复核并记录预算批准：宿主上下文沿用既有中文状态文案，本地化仍待后续处理；没有压制整类警告或放宽新增键门禁。此后已重新运行完整严格验收。

全部源码冻结后的最终目录为 `artifacts/release-verification/20261020T154702Z-66c0c2f3/`，严格入口退出码为 0。最终 Lint 比预检的 57 条多 1 条，是原 `MockWebServer` 升级提示重新出现；58 条均与已批准预算匹配，没有新的问题键或减少项，不能沿用预检数字。

清单绑定 **479 项证据**。`reports/report-images/` 中有本轮完整回归生成的 **35 张 PNG、5 份索引及 `sha256.json`，共 41 项**；另外封存 `zipalign-result.json` 与原始对齐日志、`artifact-inspection.json` 的 8 个 DEX 锚点，以及完整 JUnit、Lint、签名、R8 mapping 和执行日志。锚点涵盖图片导出/分享类、文本编辑桥和受控诊断标记，仅证明最终 APK 静态存在这些入口，不代替设备执行。签名证书 SHA-256 为 `FAE67C134F4C4FA73B8E34487C47A8C7F3E7C34B840BC245AFAC51585B7DFD47`，与原长期证书一致；本次结果不引用旧临时 APK。

统一归档位于 `artifacts/DouAi-0.2.2-release.apk`，同目录保存 `DouAi-0.2.2-release.json`、`DouAi-0.2.2-SHA256SUMS.txt` 与 `DouAi-0.2.2-zipalign.json`。统一归档的大小、哈希与清单一致，`release_verification.py integrity` 完整性复核通过。

测试日志中的 SDK 35 线程异常前缀已独立定位到故意抛出 `OutputStream IOException` 的 PNG 失败流负例：该单项通过、进程退出码为 0，重试 PNG 可解码，证据见 `work/evidence/png-stream-exception-prefix-0.2.2/summary.json`。它对应 [AOSP 流适配器](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/libs/hwui/jni/CreateJavaOutputStreamAdaptor.cpp) 的 `ExceptionDescribe` / `ExceptionClear` 失败返回与 [OpenJDK 21](https://github.com/openjdk/jdk21u/blob/master/src/hotspot/share/prims/jni.cpp#L534-L558) 的原生前缀、Java 栈分流输出，属于该测试负例的 JNI 诊断，不是手机崩溃或未处理线程退出。

0.2.1 的 177 套件、1877 次执行和签名 APK 仍是该版的历史事实，不能作为本版最终产物的验收结果。输入真实 IME/物理帧耗时、语音真正拒绝原因等此前待验边界也保持不变，详见 [诊断记录](diagnostics-2026-10-20.md#022设备复验与安全诊断)。
