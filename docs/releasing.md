# DouAi 正式版构建

源码仓库：https://github.com/yudigaga/DouAi

下载入口：https://github.com/yudigaga/DouAi/releases

LSPosed 模块仓库：https://github.com/Xposed-Modules-Repo/io.github.yudigaga.douai

正式包名：`io.github.yudigaga.douai`。Java namespace 保持 `com.douai.module`，Xposed 入口使用完整 Java 类名。

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

签名 APK 位于 `app/build/outputs/apk/release/app-release.apk`。Release 关闭调试，保留反射与 Xposed 入口所需类名，不开启代码混淆。

此前 Debug 版本使用 `com.douai.module` 和另一把签名密钥。切换到正式版时卸载旧模块，再安装并在 LSPosed 启用新的模块，避免两份模块同时作用于抖音。抖音私有目录中保存的宿主功能配置不属于模块应用数据。

## LSPosed 发布

收录申请：https://github.com/Xposed-Modules-Repo/submission/issues/2133

官方模块仓库按官方要求仅上传模块介绍和 APK，源码托管在个人 GitHub 仓库。正式 APK 两处使用同一产物，官方 Release 标签必须为 `versionCode-versionName`，本版为 `47-0.1.46`。
