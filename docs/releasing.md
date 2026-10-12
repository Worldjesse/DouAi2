# DouAi 正式版构建

源码仓库：https://github.com/yudigaga/DouAi

下载入口：https://github.com/yudigaga/DouAi/releases

LSPosed 模块仓库：https://github.com/Xposed-Modules-Repo/io.github.yudigaga.douai

正式包名：`io.github.yudigaga.douai`。Java namespace 保持 `com.douai.module`，Xposed 入口使用完整 Java 类名。

当前版本 `0.1.47 / 48`，本次递增版本号；[更新与验证记录](release-0.1.47.md) 包含最终 APK 大小、校验值和签名指纹。

## 环境

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

官方模块仓库按官方要求仅上传模块介绍和 APK，源码托管在个人 GitHub 仓库。正式 APK 两处使用同一产物，官方 Release 标签必须为 `versionCode-versionName`，本版为 `48-0.1.47`。

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
