# DouAi 0.1.54

版本 `0.1.54` / versionCode `55`，基于 `0.1.53`。沿用正式包名 `io.github.yudigaga.douai`、长期发布签名、R8 配置和抖音 40.6.0 适配规则。本版只在本地构建，尚未上传 GitHub 或 LSPosed。

## 下载方式与保存位置

- 下载方式为官方或第三方二选一，页面只显示当前方式对应的设置，切换时保留两种配置。
- 第三方 GET 地址可编辑。`{url}` 替换为编码后的作品链接，未使用占位符时在地址末尾追加编码后的链接；`{app_id}` 使用所填 App ID。支持 JSON 请求头。填写 App Secret 后，按参数名升序、PHP URL 编码、拼接密钥、MD5 的顺序生成 `Sign` 请求头。
- 从分享文本提取作品链接，解析 JSON 或 TXT 响应。嵌套字段可用于下载选择和文件命名，命名字段支持排序；作品 ID 保留完整数字。其余信息整理为 TXT，与所选媒体一起保存到作品文件夹。
- 可粘贴响应样本解析预览，也可测试 GET 并主动保存返回内容作为样本。使用其他接口时按实际响应重新选择字段；默认下载 `data.video`、`data.images`，默认按 `data.author.nickname`、`data.id` 命名。
- 官方与第三方共用“保存位置”，通过系统文件夹选择器获取持久目录授权，可恢复默认位置。自定义目录失效时提示错误，不切换到默认目录。
- 目录选择通过宿主已有的 `StartActivityForResult` 合约发送系统目录选择 Intent，不依赖抖音未打包的 `OpenDocumentTree` 类；本地宿主 DEX 检查覆盖所需合约及结果读取方法。
- 手势快捷菜单的“保存媒体”和原生下载扩展入口使用同一方式和目录设置。选择第三方后只请求第三方，失败不回退官方。

## 安装与使用

使用既有正式签名，可以覆盖安装正式版。安装后完全退出并重新打开抖音，在 DouAi 的“下载相关”设置下载方式、保存位置及所需字段。第三方接口需自行填写账户凭据，解析接口请求头不会发送到媒体下载地址。

普通 GET 下载支持媒体文件，不处理 M3U8/MPD 播放列表。官方自定义目录使用当前作品可读取的媒体来源。单次第三方结果最多保存 64 个媒体文件，单文件最多 512 MiB；接口响应最多 2 MiB。保存失败会清理本次创建的文件，清理结果受存储提供程序权限和可用性影响。

## 构建验证

完整测试 816 项通过，0 失败、0 错误、0 跳过。包含本地抖音 40.6.0 全 DEX 规则扫描、宿主目录选择合约检查，以及最终 R8 Release APK 的 3 项契约检查。新增目录选择回归覆盖宿主缺少 `OpenDocumentTree`、缺失通用合约、取消且携带数据、空或异常结果、缺失授权标志等情况。

Release Lint 为 0 错误、45 警告，R8 Release 构建通过。APK 包名为 `io.github.yudigaga.douai`，版本 `0.1.54 / 55`，调试关闭。APK v3 签名校验通过，证书与既有正式版一致。归档与构建输出的 SHA-256 完全一致。

产物：`artifacts/DouAi-0.1.54-release.apk`，3,893,717 字节。

APK SHA-256：`A09E6AD0769C4FADC0E220EAF5C07CB41AE4343519AF29E5F7BC1D5A02252F50`。

签名证书 SHA-256：`FAE67C134F4C4FA73B8E34487C47A8C7F3E7C34B840BC245AFAC51585B7DFD47`。

本轮未进行真机安装、真实第三方接口联调或真实账号操作。接口和文件写入行为通过本地模拟 HTTP 服务、MediaStore 和文档提供程序验证。

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
