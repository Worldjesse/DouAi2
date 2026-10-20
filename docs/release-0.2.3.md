# DouAi 0.2.3 备份 Android 正则兼容修正

版本 **0.2.3 / 104**。0.2.2 的现场细分诊断已定位到配置合并时的端点占位符处理：一处在 OpenJDK 可编译的正则不符合 Android ICU 规则。本轮已对两处字面右花括号补齐转义，保留原配置校验与凭据隔离语义；原生 ICU、备份专项及最终完整严格本地验收均通过。尚未远端提交或发布，**用户手机安装新包后的导入恢复尚未验证**。

## 新现场与调用链

用户在 0.2.2 看到 `CONFIG/MERGED_SETTINGS; field=tts_provider; failureKind=ARGUMENT`，文件指纹为 `bytes=24849`、SHA-256 前 12 位 `0942D57A90C6`，与此前提供的配置专用文件一致。这次错误已经进入配置合并处理，不能仅凭字段名判定语音来源值无效。

`ConfigTransfer.inspect` 在每个字符串设置上检查凭据所属端点。处理 `tts_provider` 时，`ConfigSecrets.endpointKey` 将其关联到 `tts_get_template`；当前快照和备份缺少该模板时，会读取默认 GET 模板，再经 `redactText → redactUrl → placeholder` 判断占位符。含密钥导入和合并/覆盖都会经过该检查，因此同一缺陷可影响完整备份和配置专用文件的两种模式。

## 已定位的兼容缺陷

0.2.2 的 `ConfigSecrets.redactEndpoint` 与 `placeholder` 使用正则 `\{[a-zA-Z_][a-zA-Z0-9_]*}`，仅转义左花括号。Android 的[官方 Pattern 文档](https://developer.android.com/reference/java/util/regex/Pattern)明确说明其正则后端为 ICU4C，字面右花括号也须转义；这与 OpenJDK 的容忍行为不同。0.2.3 已将两处改为 `\{[a-zA-Z_][a-zA-Z0-9_]*\}`，标识符允许的字符范围保持不变。

本机 Windows ICU4C 72.1.0.4 对从源码实际提取的旧模式返回 `U_REGEX_RULE_SYNTAX`（66305）；补齐右花括号转义的候选可编译。受控结果、源码摘要和无需读取备份的复算脚本在 `work/backup-icu-review-20261021/`。这是实际 ICU 引擎验证与 Android 官方规则的交叉证据，不是用户手机修后执行的证明。

两处均须修正：`placeholder` 的编译错误会沿上述默认端点路径进入 `MERGED_SETTINGS/ARGUMENT`；`redactEndpoint` 的同类错误则会被原有保护捕获，将正常端点当作已省略内容，影响同服务识别及凭据恢复判断。修正转义旨在恢复原先的字面占位符含义，不放宽 JSON、类型、结构、密钥或 MiniMax 组合验证，不修改用户备份内容。

## 已完成的专项与永久门禁

- 原生产源码的原生 ICU 检查实际失败，退出码 1，两处模式均为 66305；修后源码退出码 0，两处编译及 16 个合成匹配合同通过。红绿报告分别为 `work/backup-import-evidence/native-icu-source-red.json` 和 `native-icu-source-green.json`，各自绑定源码摘要及 ICU 版本，不使用 JDK 正则替代。
- 备份专项 **10 个套件、99 项通过，0 失败、0 错误、0 跳过**，其中 89 项永久回归、10 项私有输入证据测试，归档 `work/evidence/backup-icu-fix-0.2.3/`。范围包含原完整文件与配置专用文件、真实 HostSettingsStore/SettingsRepository 的两模式预览、默认文件读取与界面，以及校验拒绝、凭据隔离、提交和取消；真实输入的预览不执行确认导入，原文件未改。
- 原生检查已固化为 `tools/verify_config_secrets_icu.py`，Windows 严格发布入口在 `assembleRelease` 前强制运行；原生编译或合同失败退出 1，ICU 不可用或源码模式抽取失败退出 2，均停止，不回退到 JDK。成功的源码哈希、ICU 版本、编译与合同报告封存为验收目录中的 `android-icu-placeholder.json`，日志一并保存。普通 Linux CI 不增加 Windows ICU 硬依赖；该门禁只覆盖这两处模式及合成合同，不代表所有 Android 运行行为已验。
- 验收工具 34 项回归通过。本轮 `clean` 后完整严格验收已完成，最终结果见下表。

本轮目录为 `artifacts/release-verification/20261020T162817Z-f39c66a0/`，已在 assemble/R8 前实际通过原生门禁，两处编译及 16 个合成匹配合同均通过；其绑定的修后 `ConfigSecrets` 源码摘要为 `85f8100acd0503f4202e68e45144311e2060eaa9c5625f1a7e4e013debea6cbf`。最终 `manifest.json` 标记为 `verified-local`、`published=false`，严格入口退出码为 0。

### 签名 APK 的独立证据

同目录的 `android-icu-apk-comparison.json` 直接检查两个签名 APK 的 DEX 字符串，并用本机 ICU4C 编译相应模式：

| 产物 | 静态 DEX 字面量 | 本机 ICU 结果 |
| --- | --- | --- |
| 0.2.2 已交付 APK | 旧模式存在，修正模式缺席 | 编译失败，66305 |
| 本轮 0.2.3 最终 APK | 修正模式存在，旧模式缺席 | 编译通过，0 |

当前原生门禁的源码摘要也与 `inputs.json` 对应源码项一致；实际旧源码红报告 `android-icu-placeholder-before.json`、正式门禁报告/日志、APK 比较及对齐记录均已绑定最终清单。这补足了产物层证据，但仅是 **静态 DEX 字符串存在性 + 本机原生 ICU 验证**，不是在 Android 设备执行原 APK 或验证手机恢复。

## 验证范围与待验项

| 项目 | 当前状态 |
| --- | --- |
| 故障机制 | 现场分段与文件指纹、源调用链、原生 ICU 编译及 Android 官方语法约束吻合 |
| 最小源码修正与专项 | 两处转义已完成；原生 ICU 红绿与 99 项备份专项通过 |
| 完整源码回归 | 180 个套件、1921 次执行，0 失败、0 错误、0 跳过；不含私有输入证据测试 |
| Release 与 Lint | 正式 R8 构建通过；Lint 0 错误、57 警告，批准预算 58，无新增，减少 1 条 Gradle 升级提示 |
| 签名、对齐与完整性 | 原长期证书 v3、`zipalign -c -P 16 4` 及完整性 CLI 复核通过 |
| 最终 APK | `artifacts/DouAi-0.2.3-release.apk`，4,616,345 字节 |
| APK SHA-256 | `57DFB2BF23B5EAA58889D4828FC15970FA44285BAA2298DD2D350C6C70FFF415` |
| 手机使用同一文件预览及导入恢复 | 尚未验证 |
| 远端提交与发布 | 未执行 |

签名证书 SHA-256 为 `FAE67C134F4C4FA73B8E34487C47A8C7F3E7C34B840BC245AFAC51585B7DFD47`。统一归档的 APK、`DouAi-0.2.3-release.json`、`DouAi-0.2.3-SHA256SUMS.txt` 和 `DouAi-0.2.3-zipalign.json` 已保存，大小与摘要和清单一致。

清单封存 **441 项证据**，包含旧源码实际红、正式原生 ICU 门禁 JSON/日志、APK 正则字面量比较、对齐 JSON/原始日志及完整 JUnit、Lint、R8 mapping。报告 PNG 未在本轮重复封存；0.2.2 的图片封存和 479 项清单仍保留其历史范围。本轮减少的 Lint 项为 `AndroidGradlePluginVersion`，消息是 Gradle 9.3.1 可升级至 9.8.2；不沿用上一版或预检中的其它升级提示差异。

[0.2.2](release-0.2.2.md) 的本地完整回归、签名和归档结果保持为该版历史事实；当时没有查实手机失败原因，且 JDK/Robolectric 验证未覆盖这项 Android ICU 语法差异。它们不作为 0.2.3 的验收结果。报告图片分享、语音诊断等既有功能与待验边界也不因本次定位而自动得到真机验证。
