# DouAi 0.1.98：MiniMax 文本转语音

在“抖音设置 → DouAi → 扩展功能 → 文本转语音设置 → 语音来源”选择 MiniMax，填写平台 API Key 后刷新音色并试听。默认 API 地址为 `https://api.minimax.cn`、模型为 `speech-2.8-hd`、音色为 `male-qn-qingse`。支持修改地址和模型、查询系统/克隆/文生音色、手填音色 ID，以及独立的语速、音量和音调。详见[使用帮助](help.md#配置-minimax)。

试听、手动 `#` 转语音、自动回复和自动语音评论使用统一来源选择。原有系统引擎与第三方 GET 设置继续独立保存。MiniMax 合成失败不会切换其他语音来源；自动评论已有的明确失败后转文字规则保持原样。

实现使用官方非流式同步接口，请求 URL 格式的 MP3 并立即下载；检查 HTTP 状态及 `base_resp.status_code`，兼容有界的小 Hex 响应。JSON 响应限制为 2 MiB，音频限制为 20 MiB，合成总超时为 60 秒。API Key 仅发送至配置的 API，下载音频时不携带，API 和音频请求均拒绝重定向。配置备份默认隐藏 Key，切换 API 地址的脱敏导入不会继承旧地址的 Key。

官方接口依据：[同步语音合成](https://platform.minimax.cn/docs/api-reference/speech-t2a-http)、[查询音色](https://platform.minimax.cn/docs/api-reference/voice-management-get)、[错误码](https://platform.minimax.cn/docs/api-reference/errorcode)。

## 本地验证

2026-10-20 执行：

```powershell
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:testDebugUnitTest :app:lintRelease :app:assembleRelease --console=plain
$env:DOUAI_VERIFY_RELEASE_APK=(Resolve-Path artifacts\DouAi-0.1.98-release.apk).Path
.\gradlew.bat :app:testDebugUnitTest --tests 'com.douai.module.scan.ApkArtifactContractTest' --console=plain
```

- 单元/Robolectric 回归：172 个套件、1739 项，其中 1728 项通过，零失败、零错误。11 项需要额外 APK 输入的可选检查首次跳过；最终归档后补跑的 3 项 APK 合同全部通过。其余 8 项宿主 APK 检查未运行，本次未修改宿主适配规则。
- 新增 25 项测试全部通过，覆盖独立设置与范围校验、备份密钥处理、设置页、音色目录、POST JSON、中文及转义、凭证隔离、响应边界、错误脱敏、取消与文件清理。
- Release 构建通过，Lint 为 0 错误、63 警告。
- APK 包名 `io.github.yudigaga.douai`，版本 `0.1.98` / `99`，v3 签名通过，证书与现有正式版本一致。R8 输出包含 `MiniMaxSpeechClient`。
- 未配置真实 MiniMax Key，未验证实际服务生成、真机播放或抖音发送。本轮为本地构建。

## 归档

- 文件：`artifacts/DouAi-0.1.98-release.apk`
- 大小：4,518,052 字节
- SHA-256：`452BB2AAC926403D0394DFE112B680171F3547DF25779F1A9FB4329EDA0EDFD1`
- 签名证书 SHA-256：`FAE67C134F4C4FA73B8E34487C47A8C7F3E7C34B840BC245AFAC51585B7DFD47`
- 元数据：`artifacts/DouAi-0.1.98-release.json`；摘要：`artifacts/DouAi-0.1.98-SHA256SUMS.txt`。
