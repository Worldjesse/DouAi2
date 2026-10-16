# 0.1.65：官方下载手势先选择再保存

本地构建：`0.1.65 / 66`，基于此前 `0.1.64 / 65`。本次未发布到 GitHub 或 LSPosed 仓库。

## 使用

在“下载相关 → 下载方式”选择“官方下载”，触发分配为“保存媒体”的长按或双击手势，或从手势快捷菜单选择“保存媒体”。先选择要保存的内容，之后才创建下载任务。

| 当前内容 | 选项 |
| --- | --- |
| 视频 | 保存视频；保存音频（作品模型中的原声/配乐来源） |
| 图集 | 保存当前图片；选择图片保存；保存全部图片 |
| 单张实况 | 保存图片；保存动态视频；保存实况 |
| 混合图集中的当前实况 | 保留图集选项，另提供保存当前动态视频、保存当前实况 |

选项根据当前作品已提供的媒体地址显示。音频使用作品 `music.play_url`，不是在本地从视频中提取音轨。选图列表默认不勾选，勾选后点击“保存”；空选提示继续选择，取消不下载。

沿用“保存位置”、自定义命名和去水印来源选择；图片文件名的序号保留原始图集位置。选择的图片有无效来源时，在排队前报告失败，避免静默保存不完整的选择。第三方下载维持已选择方式的独占执行。

## 实现

- [FeedGestureToolsHook](../app/src/main/java/com/douai/module/feature/FeedGestureToolsHook.java) 将官方下载手势交给 [OfficialDownloadDialog](../app/src/main/java/com/douai/module/feature/OfficialDownloadDialog.java)，确认前没有下载任务或媒体请求。
- 切换作品、暂停页面、修改设置或关闭 Hook 时，选择会关闭或失效。确认时复核当前 holder、根视图和作品 ID，阻止旧选择下载其他作品。
- 弹窗复用模块的系统深浅色适配；原生长按下载按钮的既有行为保留。
- [OfficialLivePhotoDownload](../app/src/main/java/com/douai/module/feature/OfficialLivePhotoDownload.java) 先把图片、动态视频下载到独立缓存，再调用抖音 `IExternalService.albumService().Xi(imagePath, videoPath, 0L)`。要求成功码为 0、未直接写宿主相册、返回一个非空文件，且宿主 `isLivePhoto(path)` 确认为实况。
- [MediaStoreDownloader](../app/src/main/java/com/douai/module/feature/MediaStoreDownloader.java) 按原字节复制真正编码后的实况容器到相同保存目录，不把图片和视频两个原始文件冒充实况。失败不降级为其他格式，自建缓存进入清理流程。

## 本地验证与产物

- 全量回归：130 个测试套件、1100 项测试全部通过，0 失败、0 错误、0 跳过；包括原宿主 DEX 检查、最终 Release APK 契约和实况接口 ABI 检查。
- 针对性回归：99 项通过，0 失败、0 错误、0 跳过。
- 原生弹窗渲染：3 项通过，覆盖 296 dp 小屏视频、系统深色下图集选图、640×360 横屏实况；检查按钮可见、列表有界且取消不增加任务。
- Release Lint：0 错误、48 警告；R8 Release 构建通过。
- APK：`artifacts/DouAi-0.1.65-release.apk`，4,140,365 字节。
- SHA-256：`0ADBB5705251EA262CBFDF6092F8A0D41474396BAAC4F486C466FCFF5C2145DC`。
- v3 签名验证通过，证书 SHA-256 为 `FAE67C134F4C4FA73B8E34487C47A8C7F3E7C34B840BC245AFAC51585B7DFD47`，与本地 0.1.64 APK 证书直接比较一致。

截图、APK、宿主 APK、测试日志和签名结果均为本地验证产物，不随源码仓库分发。截图目录为 `artifacts/download-0.1.65-screenshots/`；日志前缀为 `artifacts/download-065-`，汇总记录为 `artifacts/download-065-validation.json`。归档 APK 与最终测试所用构建的 SHA-256 相同。

复现命令：

```powershell
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$env:DOUAI_TEST_APK = (Get-ChildItem -File -Filter '抖音-40.6.0*.apk' | Select-Object -First 1).FullName
.\gradlew.bat :app:lintRelease :app:assembleRelease --console=plain
$env:DOUAI_VERIFY_RELEASE_APK = (Resolve-Path 'app/build/outputs/apk/release/app-release.apk').Path
.\gradlew.bat :app:testDebugUnitTest --console=plain
```

## 验证边界

未进行真机安装、实际下载或相册识别验收。本地实况测试使用可替换编码器验证调度、结果校验、保存和取消；另核对原始抖音 40.6.0 APK DEX 中真实接口的 ABI，这不等于已运行宿主 native 编码器。

Vivo/iQOO 的已识别宿主分支直接写系统相册，当前在调用编码前拒绝，提示设备不支持独立文件保存。宿主同步编码没有取消接口：取消后需等该调用返回，再阻止发布并清理本模块自建缓存。宿主在自建缓存之外返回的文件不擅自删除。
