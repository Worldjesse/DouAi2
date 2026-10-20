# DouAi 正式版构建

源码仓库：https://github.com/yudigaga/DouAi

下载入口：https://github.com/yudigaga/DouAi/releases

LSPosed 模块仓库：https://github.com/Xposed-Modules-Repo/io.github.yudigaga.douai

正式包名：`io.github.yudigaga.douai`。Java namespace 保持 `com.douai.module`，Xposed 入口使用完整 Java 类名。

当前正式版本为 `0.2.4 / 105`，包含 MiniMax 语音、持久化发送防重与会话调度、局部编辑、备份 ICU 修复和成功通知，以及完整长 PNG / A4 PDF 报告导出。版本、最终包与验证范围见 [更新与发布记录](release-0.2.4.md)。沿用原正式签名、包名和既有黑边回退结果。新交付按下述进位规则递增 versionName，并独立递增 versionCode；同一批修复的重复验证不重复递增。

本版严格本地验收为 182 个套件、1933 次执行通过，零失败、零错误、零跳过，包含本地宿主 DEX、227 条规则唯一性、22 项评论回复宿主合同和最终签名 APK 合同；另 34 项工具测试、2 个原生 ICU 编译及 16 项匹配检查通过。Lint 0 错误、57 条既有警告、0 新增，预算通过。完整本地验证采用 Gradle 768 MiB、单测试工作进程并每 20 个测试类回收 JVM。未执行本版新增功能的真机或真实服务验收。

AI 消息联系人于 0.1.76 发布，入口和原验证范围见 [历史记录](release-0.1.76.md)。0.1.77、0.1.78 的聊天界面与模型头像、0.1.79 的附件和后台回复功能继续保留，详细行为见 [0.1.79](release-0.1.79.md)。

**0.2.4 / 105** 的原证书 v3 签名、最终包摘要、16 KiB ZIP 对齐及证据完整性通过。封存清单 `artifacts/release-verification/20261020T175556Z-01d40a32/manifest.json` 保留本地验收时的 `verified-local`、`published=false`，绑定本轮实际生成的完整长图、13 页 A4 PDF 及解析/渲染/目视报告；其状态不改写为远端发布证据。正式标签为 GitHub `v0.2.4`、LSPosed `105-0.2.4`，两边使用同一份已验收 APK。用户已确认旧版手机备份导入恢复；新 Toast、保存及分享接收仍需设备复验。

历史候选 **0.2.3 / 104** 修复备份导入过程中 Android ICU 对未转义右花括号的拒绝，并在本地严格入口加入原生 ICU 编译与匹配门禁。严格验收 **180 个套件、1921 次执行**全部通过，0 失败、0 错误、0 跳过；备份专项 99 项、原生 2 个编译及 16 项匹配检查、工具 34 项通过。Release、原长期证书 v3 签名、最终 APK 哈希、16 KiB ZIP 对齐及证据完整性通过；Lint 0 错误、57 条已审警告，无新增，预算通过。清单 `artifacts/release-verification/20261020T162817Z-f39c66a0/manifest.json` 标记 `verified-local`、`published=false`，绑定原生 ICU 修前与修后报告、最终 APK 字面量对照及对齐证据。完整产物与当时手机复验边界见 [0.2.3 本地记录](release-0.2.3.md)；用户现已确认 0.2.3 可以在手机导入备份，此反馈不代替本轮 Toast 与报告导出的设备验收。

历史候选 **0.2.2 / 103** 完成报告内 AI 结果、完整报告图片分享和受控诊断，其 180 套件、1921 次执行、58 条 Lint 警告及报告 PNG 样例封存记录保留在 [0.2.2 本地记录](release-0.2.2.md)。0.2.3 清单绑定的是当次原生 ICU 与产物验证，没有重复封存这些报告图片。

历史候选 **0.2.1 / 102** 的 177 个套件、1877 次执行及原签名产物保留在 [0.2.1 本地记录](release-0.2.1.md)，不作为本版的验收结果。其独立对齐日志未纳入当时的封存清单。

历史本地候选 **0.1.100 / 101** 曾通过严格验收：177 个套件、1843 次测试执行及当时的 32 项工具回归全部通过，签名、对齐、归档哈希及证据完整性已核对。其版本、产物和结果保留在 [0.1.100 本地记录](release-0.1.100.md)，不作为当前版本的验收结果。

## 版本进位规则

versionName 使用三段非负整数。末段达到 100 时向中段进位，中段达到 10 时向首段进位；规范化后中段为 `0–9`，末段为 `0–99`。例如 `0.1.99` 的下一版为 `0.2.0`，`0.9.99` 的下一版为 `1.0.0`。此前草稿号 `0.1.101` 据此规范化为 `0.2.1 / 102`，后续本地候选依次为 `0.2.2 / 103`、`0.2.3 / 104`、`0.2.4 / 105`；versionCode 独立递增，同一未发布批次的重建保留批次版本。

Gradle 配置与严格验收入口均检查版本段范围。既有归档的版号、文件名、标签和摘要保留其历史事实，已归档的 `0.1.100 / 101` 不追溯改名或重新标记。

## 环境

当前工作区保持 0.1.69 与 0.1.73 两轮黑边增量的回退结果。视频显示仍为一屏一条、保留完整画面，接受比例所需的必要留白；未新增裁剪、页距或切换黑带修复。最终归档、构建输出与测试所用 APK 应核对哈希一致，历史归档仍代表各自当时源码。

0.1.75 的本地构建与验证见 [当前记录](release-0.1.75.md)，本版尚未进行真机验收。0.1.74 的回退范围与本地验证见 [此前记录](release-0.1.74.md)。已发布 0.1.72 的包体信息、验证及真机待验范围见 [更新记录](release-0.1.72.md)。评论回复并入父设置的 `0.1.71 / 72` 与 1274 项回归见 [此前记录](release-0.1.71.md)；`0.1.70 / 71` 首版与 1242 项回归见 [历史记录](release-0.1.70.md)。这两轮评论回复变更均包含于 0.1.72，本轮继续保留这些功能。

需要 JDK 21、Android SDK Platform 37。Gradle Wrapper 为 9.3.1。

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:testDebugUnitTest :app:lintRelease --console=plain
```

宿主 DEX 集成测试需要本地抖音 40.6.0 APK，通过环境变量 `DOUAI_TEST_APK` 指定绝对路径。第三方 APK 不由本仓库分发；没有配置该路径时，相关集成测试会跳过。

## 签名

在用户级 `%USERPROFILE%\.gradle\gradle.properties` 中配置以下四个属性：

| Gradle 属性 | 对应环境变量 | 用途 |
| --- | --- | --- |
| `douaiReleaseStoreFile` | `DOUAI_RELEASE_STORE_FILE` | 长期发布密钥库绝对路径 |
| `douaiReleaseStorePassword` | `DOUAI_RELEASE_STORE_PASSWORD` | 密钥库密码 |
| `douaiReleaseKeyAlias` | `DOUAI_RELEASE_KEY_ALIAS` | 签名密钥别名 |
| `douaiReleaseKeyPassword` | `DOUAI_RELEASE_KEY_PASSWORD` | 密钥密码 |

Windows 路径在 properties 中使用正斜杠。私钥和密码仅在本机保存，更新发布必须使用同一密钥。未配置签名时 Gradle 仅生成未签名 APK，不能作为已签名正式版发布。

```powershell
.\gradlew.bat :app:assembleRelease --console=plain
```

签名 APK 位于 `app/build/outputs/apk/release/app-release.apk`。Release 关闭调试，启用 R8 代码压缩与依赖优化，资源裁剪关闭。`app/proguard-release.pro` 保留模块类和成员、Xposed 入口，以及媒体选择器反射宿主 AndroidX ActivityResult 所需的类名和成员；其余依赖允许优化和改名。

用户于 2026-10-12 反馈 R8 试验包已测试且没有问题，随后将同一保留配置用于正式 Release。具体设备和覆盖场景未提供，真实发送与性能测量结果仍单独记录。`r8Experiment` 保留为兼容构建名，继承正式 Release 的压缩配置；正式 mapping 位于 `app/build/outputs/mapping/release/`。

构建后执行正式 APK 契约检查：

```powershell
$env:DOUAI_VERIFY_RELEASE_APK = (Resolve-Path 'app/build/outputs/apk/release/app-release.apk').Path
.\gradlew.bat :app:testDebugUnitTest --tests com.douai.module.scan.ApkArtifactContractTest --console=plain
```

CI 在构建未签名 Release 后提供该路径并运行测试。未配置路径时本地测试可跳过；配置了不存在或不可读的路径应失败。检查覆盖模块元数据、源码规则资产、入口签名、宿主反射名称及 compileOnly API 不被打包。

此前 Debug 版本使用 `com.douai.module` 和另一把签名密钥。切换到正式版时卸载旧模块，再安装并在 LSPosed 启用新的模块，避免两份模块同时作用于抖音。抖音私有目录中保存的宿主功能配置不属于模块应用数据。

## LSPosed 发布

收录申请：https://github.com/Xposed-Modules-Repo/submission/issues/2133

官方模块仓库按官方要求仅上传模块介绍、免责协议和 APK，源码托管在个人 GitHub 仓库。正式 APK 两处使用同一产物，官方 Release 标签必须为 `versionCode-versionName`。本版使用 GitHub 标签 `v0.1.97` 和官方模块仓库标签 `98-0.1.97`。上传后核对两个仓库的 asset SHA-256 与本地归档一致；旧 Release 和本地旧 APK 保留。

## 严格本地发布验收

在源码稳定后，从仓库根目录执行下面一条命令。它只构建和归档本地产物，不创建标签或上传 Release。

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA/Android/Sdk"
.\tools\verify-release.ps1 -HostApk (Resolve-Path '抖音-40.6.0_40609900_20260922 (400601).apk').Path
```

需要 JDK 21、Python 3.10+、Android SDK Platform 37 和 build-tools，以及前述四项本机签名配置。`-HostApk` 可由 `DOUAI_TEST_APK` 提供；脚本检查宿主的包名及版本与 `HostAdaptation` 一致。完整回归还保留一项旧 smali 对照，需本地 `douyin-40.6.0/apktool-no-res/` 的对应文件；缺材料会在构建前失败。宿主 APK、smali 与密钥均不上传。

每次验收创建独立的 `artifacts/release-verification/<UTC时间>-<随机ID>/`，保留原有归档。流程先执行原生 ICU 预检，再构建正式 R8 APK，检查当前 Gradle 输出元数据，复制最终签名 APK，再核对包名、版本、原长期证书、v3 签名及两份 APK 的哈希。全量回归只检查这份最终归档，不在结尾用三项 APK 合同覆盖完整 JUnit 结果。

`-PreleaseVerification=true` 固化测试堆 `1g`、单测试工作进程和每 20 个测试类回收 JVM；入口同时指定 Gradle 堆 768 MiB 和单构建 worker。实际参数写入 `test-profile.json`。全部源码 JUnit suite 和测试方法必须有结果，包括所有宿主合同与最终 APK 合同，任何缺失、失败、错误或跳过均失败；Robolectric 的多个 SDK 变体分别计数。源码、宿主 APK、签名证据和归档在验收期间发生变化也会失败。

从 0.2.3 起，Windows 严格入口在 `assembleRelease` 前强制运行 `tools/verify_config_secrets_icu.py`。它直接提取 `ConfigSecrets` 的两处占位符模式，用本机原生 ICU4C 编译并执行固定合成匹配合同；原生编译或合同失败退出 1，源码模式无法提取或 ICU 不可用退出 2，均停止，没有 JDK 回退。报告记录源码 SHA-256、ICU 版本及各项结果，保存为本次目录的 `android-icu-placeholder.json` 和 `android-icu-placeholder.log` 并纳入封存。该门禁用于覆盖 JDK/Robolectric 与 Android ICU 的已知差异，不读取用户备份；只验证这两处模式，不能替代 Android 设备验收。普通 Linux CI 不增加 Windows ICU 硬依赖。

成功目录含 `manifest.json`、`SHA256SUMS.txt`、签名及 Manifest 证据、完整 JUnit/XML/HTML、Lint、R8 mapping 和命令日志；失败保留 `failure.json` 及已产生的报告。机器清单分别记录源码 commit、未提交状态、输入文件哈希、测试范围与实际参数，并明确标为本地已验证、未发布、真机与真实服务未执行。归档后的完整性复核命令如下；这是对已存证据哈希的核对，不会重跑 Android 验收。

```powershell
python -B tools/release_verification.py integrity --run-dir artifacts/release-verification/<本次目录>
```

普通 CI 仍允许没有宿主 APK，但会按全部宿主合同 suite 单独显示通过、跳过和缺失。设置了宿主 APK 却跳过相应检查、缺报告或缺必要方法会失败。每次 CI 都保存 JUnit、Lint 和 R8 报告；普通 CI 绿色不代表严格发布验收或真机成功。

Lint 警告使用 `tools/lint-baseline.json` 的可审查预算，以问题 ID、相对文件和归一化消息为键，不依赖行号；同键数量减少允许，新增键或数量增加失败。修复后的报告通过检查即可，无须为了减少警告更新预算。确需保留新增警告时，说明理由并显式更新清单，检查差异后再提交：

```powershell
python -B tools/lint_budget.py check --report app/build/reports/lint-results-release.xml --output app/build/reports/lint-budget.json
python -B tools/lint_budget.py update --report app/build/reports/lint-results-release.xml --reason '说明新增警告的原因与取舍'
python -B -m unittest discover -s tools/tests -v
```

## R8 正式配置验证记录

2026-10-13：沿用用户测试通过的 R8 试验配置，正式 Release 启用代码压缩，资源裁剪关闭。版本仍为 0.1.46 / 47，本次为本地构建，未重新发布。

- 659 项测试通过，0 失败、0 错误、0 跳过，包含本地抖音 40.6.0 全 DEX 扫描及正式 Release APK 契约检查。
- Release Lint：0 错误、44 警告；Release 构建通过。
- APK 为 `app/build/outputs/apk/release/app-release.apk`，3,811,746 字节，比同轮未压缩基线 25,455,763 字节小 85.03%。
- APK SHA-256：`1224002D8C094C967701EAAB0D02864F73F4ECF306425937A18B6C05380CD8D8`。
- APK v3 签名验证通过，证书 SHA-256 为 `FAE67C134F4C4FA73B8E34487C47A8C7F3E7C34B840BC245AFAC51585B7DFD47`，与既有发布证书一致。
- 用户反馈试验包测试无问题；代理完成构建和静态契约验证。具体设备测试场景、真实发送与性能测量未补充记录。

产物测试允许 R8 合法扩大 Activity 生命周期方法访问权限、改名 AndroidX 依赖，仍验证 Activity 继承链以及 Xposed 回调的公开权限、非静态属性与完整签名。

## 0.1.46 原发布验证结果

- 591 项测试通过，0 失败、0 错误、0 跳过，包括抖音 40.6.0 全 DEX 规则扫描、正式包名的权限/诊断、重启组件、启动器和宿主资源检查。
- Release Lint：0 错误、41 警告。
- 正式包未开启调试；APK v3 签名校验通过，适用于本项目最低版本 Android 9。
- APK 内含 GitHub、LSPosed 和 Miuix 地址以及 libxposed 模块元数据。
- 未进行真机安装、真实账号网络调用或实际评论/回复发送验证。

正式 APK：`DouAi-0.1.46-release.apk`，25,390,227 字节。

APK SHA-256：`95C71D4F4397634BBBC0BC1802C148208E55FFCF21FF01EEBD78C62AD482343F`

签名证书 SHA-256：`FAE67C134F4C4FA73B8E34487C47A8C7F3E7C34B840BC245AFAC51585B7DFD47`
