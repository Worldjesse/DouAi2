# 0.1.74：重新打包黑边回退结果

版本：`0.1.74 / 75`。按用户要求，保留已完成的黑边回退，重新构建用于更新安装的正式 Release。R8 构建、最终全量回归、签名与归档核对均已完成，本轮仅本地交付，尚未发布远端 Release。

此前 `0.1.73 / 74` 已本地交付，因此本次递增版本号重新打包，供已安装该本地包的设备更新。当前源码没有恢复 0.1.73 的 holder 黑边处理。

## 本包行为

- 黑边逻辑恢复为 0.1.64 对应的原有实现：仅在 `adv_ui_black_spaces` 定位的 `BaseListFragmentPanel.LLLFF()` 执行后，读取 `i/j` 并按“隐藏上下黑边”开关管理，注册目标为 1 个。
- 撤回 0.1.69 增加的 `onViewCreated(View, Bundle)` 黑边绑定、创建诊断与专用测试，以及 0.1.73 的 `black_space_recovery` holder 回调和恢复诊断。原有黑边开关与可恢复视图管理继续使用。
- 保留 0.1.65 至 0.1.68 的官方下载分类、图片/动态视频多选、实况识别和编码保存，以及第三方分类选择、来源字段标记和独立默认行为。
- 保留 0.1.70 至 0.1.72 的评论回复接入、父级配置合并、素材识别、独立回复悬浮提示及其他已有功能。自动回复的正式功能说明继续见 [0.1.72](release-0.1.72.md)。

本版用于交付用户指定的回退结果。此前日志和录屏没有锁定该机型底栏残留的唯一根因，回退后的真实表现仍待用户设备实测，不将本次重新打包视为黑边修复验收。

## Evidence：源码与版本依据

| 编号 | 来源 | 已确认的范围 |
| --- | --- | --- |
| E-001 | [HostBehaviorHooks](../app/src/main/java/com/douai/module/feature/HostBehaviorHooks.java) 与提交 `9b60a36` 的文件比对 | 当前该文件与黑边修改前的基线一致，保留原单一更新回调，未恢复创建或 holder 恢复增量。 |
| E-002 | [版本配置](../app/build.gradle.kts) | 本次 `versionName=0.1.74`、`versionCode=75`，高于此前本地 `0.1.73 / 74`。 |
| E-003 | [0.1.69 历史记录](release-0.1.69.md)、[0.1.72 正式记录](release-0.1.72.md) 与本地 `artifacts/DouAi-0.1.73-release.validation.json` | 已发布的 0.1.69、0.1.72 及此前 0.1.73 本地包的验证和产物记录保留；其统计、签名和哈希只对应各自当时的 APK，不作为本版结果。 |

## Finding：回退范围

F-001：当前源码仅撤回两轮黑边增量，黑边回到原有单一回调；下载选择、评论回复与素材功能继续保留。依据 E-001 及历史功能记录，版本由 E-002 单独递增，不通过降回旧包版本号交付。

## Path：本地交付流程

1. 保持黑边回退后的生产源码，版本升至 `0.1.74 / 75`。E-001、E-002 / F-001。
2. 按现有正式签名与 R8 配置构建，执行 Release Lint、最终全量回归及原宿主/成品 APK 契约检查。
3. 核对最终归档的版本、签名、大小与 SHA-256，再交付更新安装。安装后完全退出并重新打开抖音，使回退后的 hook 加载。

步骤 2、3 已完成：143 个套件、1325 项全量测试全部通过，0 失败、0 错误、0 跳过；其中原有视图行为 5 项、原宿主 DEX 3 项及最终归档 APK 契约 3 项均通过。Release Lint 为 0 错误、44 警告。本版尚无真机安装或底栏行为验收结论。

APK 的 v3 签名通过，与此前 0.1.73 归档使用相同证书；包名为 `io.github.yudigaga.douai`，实际版本 `0.1.74 / 75`，并已确认不是 debuggable 包。成品 DEX 中不再含两轮新增的黑边创建诊断、holder 恢复诊断和 `black_space_recovery` 标记。

## 正式构建与验证入口

本轮沿用以下正式构建与全量验证入口：

```powershell
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$env:DOUAI_TEST_APK = (Get-ChildItem -File -Filter '抖音-40.6.0*.apk' | Select-Object -First 1).FullName
.\gradlew.bat :app:lintRelease :app:assembleRelease --console=plain
$env:DOUAI_VERIFY_RELEASE_APK = (Resolve-Path 'app/build/outputs/apk/release/app-release.apk').Path
.\gradlew.bat :app:testDebugUnitTest --console=plain
```

## 构建产物

- APK：`artifacts/DouAi-0.1.74-release.apk`，4,206,279 字节。
- SHA-256：`B15387909306B3D7474960B45520ED8B534D26D1F5E32B36D8DC07E58ECBBDC5`。
- 签名证书 SHA-256：`FAE67C134F4C4FA73B8E34487C47A8C7F3E7C34B840BC245AFAC51585B7DFD47`。
- 验证记录：`artifacts/DouAi-0.1.74-release.validation.json`。
- 构建日志：`artifacts/black-edge-rollback-074-build.log`；全量日志：`artifacts/black-edge-rollback-074-full-tests.log`。

最终归档、Release 输出和全量测试使用的 APK 哈希一致。安装新包后需完全退出并重新打开抖音。
