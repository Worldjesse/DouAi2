# DouAi 0.1.55

版本 `0.1.55` / versionCode `56`，基于 `0.1.54`。沿用正式包名、长期发布签名、R8 配置和抖音 40.6.0 适配规则。本版只在本地构建，尚未上传 GitHub 或 LSPosed。

## 通用第三方请求参数

第三方下载不再固定显示 App ID 和 App Secret。GET 地址的查询参数会自动解析为参数列表，名称与接口地址一致；保存 GET 后自动更新，参数列表本身也可以增删和编辑名称、值，不需要另外刷新。编辑后的参数会写回同一个 GET 地址。

作品链接使用 `{url}`，可放在任意查询参数或地址路径。例如 `https://example.com/parse?ID=123&link={url}` 会传递 `ID` 与 `link`，不附加 `app_id`。没有 `{url}` 的完整 GET 地址按原样请求，支持零参数接口，不强制提供作品链接。新配置中的空参数值保持为空。

签名独立选择“无签名”或“PHP MD5”。无签名时隐藏密钥和签名请求头设置，已保存的密钥不会触发签名。PHP MD5 使用原参数排序、编码、拼接密钥与 MD5 规则，签名请求头名称可自定义，默认 `Sign`；缺少密钥会提示配置错误。

旧版的 `{app_id}` 和 App ID 值会转换为普通请求参数；旧版末尾 `url=` 链接前缀保持原链接绑定行为。已配置密钥的旧版接口保留 MD5 签名，新的 GET 配置保存后使用显式签名选择。

请求头、JSON/TXT 响应样本、下载字段及命名字段继续独立设置。返回字段来自实际响应，不由 GET 查询参数推断。手势“保存媒体”和第三方 GET 测试使用同一请求配置，自定义保存目录与第三方失败不回退官方的规则保留。

## 构建验证

完整测试 840 项通过，0 失败、0 错误、0 跳过，包含本地抖音 40.6.0 全 DEX 规则扫描、宿主目录选择合约检查，以及最终 R8 Release APK 的 3 项契约检查。新增回归覆盖任意参数名、参数增删、固定值与作品链接切换、零参数 GET、路径链接、编码与重复参数、签名独立选择、旧配置迁移、手势下载及 Miuix 参数编辑。

Release Lint 为 0 错误、45 警告，R8 Release 构建通过。APK 包名为 `io.github.yudigaga.douai`，版本 `0.1.55 / 56`，调试关闭。APK v3 签名校验通过，证书与既有正式版一致。归档与构建输出的 SHA-256 完全一致。

产物：`artifacts/DouAi-0.1.55-release.apk`，3,910,103 字节。

APK SHA-256：`835517702D383AA31F83435DFCB32807F7A4695E6D2FC0A080A289E294A0C01B`。

签名证书 SHA-256：`FAE67C134F4C4FA73B8E34487C47A8C7F3E7C34B840BC245AFAC51585B7DFD47`。

本轮未进行真机安装或真实第三方接口联调。请求与保存行为使用本地模拟 HTTP 服务和存储提供程序验证。

## 复验命令

在项目根目录的 PowerShell 执行：

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:DOUAI_TEST_APK = (Get-ChildItem -LiteralPath . -Filter '抖音-40.6.0*.apk' | Select-Object -First 1).FullName
.\gradlew.bat :app:assembleRelease :app:lintRelease --console=plain
$env:DOUAI_VERIFY_RELEASE_APK = (Resolve-Path 'app/build/outputs/apk/release/app-release.apk').Path
.\gradlew.bat :app:testDebugUnitTest --console=plain
& "$env:ANDROID_HOME\build-tools\36.0.0\apksigner.bat" verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
```
