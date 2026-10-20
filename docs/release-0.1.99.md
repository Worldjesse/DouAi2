# DouAi 0.1.99：MiniMax 模型列表与高级设置

进入“抖音设置 → DouAi → 扩展功能 → 文本转语音设置”，选择 MiniMax。新版增加两个在线刷新入口，模型仍可从列表选择或手动填写。

- **刷新模型**：使用配置的 API 地址和 Key 请求 `/v1/models`，筛选 `speech-*` 标识。只返回文本模型、空结果或请求失败时，保留当前模型与原列表，并提示刷新官方列表或手填。
- **刷新官方模型列表**：无需 Key，读取 MiniMax 公开的同步语音 OpenAPI 目录。2026-10-20 已验证该地址直接返回 HTTP 200，目录含 8 个 Speech 模型。
- 模型选择框区分内置官方列表、接口列表、官方文档列表；当前手填模型不在列表中时单独标注。列表不代表账号权限或余额已经验证。

高级参数在“MiniMax 高级设置”配置，供试听、手动 `#` 转语音、自动回复和自动评论共用：

| 设置 | 可选内容与行为 |
| --- | --- |
| 情绪 | 自动、高兴、悲伤、愤怒、恐惧、厌恶、惊讶、平静；流利/低语当前仅在 2.6 模型开放 |
| 语言增强 | 不指定、自动或官方语言枚举，包含中文与粤语 |
| 文本归一化 | 不指定、基础、高质量；高质量仅允许 2.8/2.6 模型 |
| 音频格式 | MP3、WAV、FLAC |
| 音质 | 8/16/22.05/24/32/44.1 kHz，单/双声道；MP3 额外支持 32/64/128/256 kbps |
| 声音效果器 | 音高、音强、音色各 -100 至 100；空旷回声、礼堂回声、电话音质或机器人音效单选 |
| 发音字典 | 每行一条“原文/替换内容”，例如 `处理/(chu3)(li3)` |
| LaTeX 朗读 | 默认/开/关；开启时只使用中文，不静默覆盖已指定的其他语言 |
| AIGC 水印 | 默认/开/关 |

保存和配置导入均验证组合：不兼容时提示并保留原值。WAV/FLAC 不发送 MP3 码率；可选项未指定时省略。模型与音色缓存分开保存，列表来源也不进入配置备份。Key/地址变更取消旧请求，清除旧接口模型缓存，公开文档目录不携带 API Key。原有系统和第三方 GET 设置保持独立。

接口依据：[模型 API](https://platform.minimax.cn/docs/api-reference/models/openai/list-models)、[同步语音合成](https://platform.minimax.cn/docs/api-reference/speech-t2a-http)、[官方 T2A 模型目录](https://platform.minimax.cn/docs/api-reference/speech/t2a/api/openapi.json)。完整操作见[使用帮助](help.md#minimax-模型列表)。

## 本地验证

```powershell
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
$env:DOUAI_CHAT_SCREENSHOTS=(Join-Path (Get-Location) 'artifacts\speech-0.1.99-screenshots')
.\gradlew.bat :app:testDebugUnitTest --tests '*Speech*' --tests '*VoiceCatalogTest' --tests '*ConfigTransfer*' --tests '*HelpDialogTest' :app:lintRelease :app:assembleRelease --console=plain
$env:DOUAI_VERIFY_RELEASE_APK=(Resolve-Path artifacts\DouAi-0.1.99-release.apk).Path
.\gradlew.bat :app:testDebugUnitTest --tests 'com.douai.module.scan.ApkArtifactContractTest' --console=plain
```

- 23 个相关套件、183 项测试全部通过，零失败、零错误、零跳过；包含模型发现、精准目录解析、高级参数真实 POST、冲突参数不发送请求、备份与 UI。
- Miuix 窄屏与横屏下主页面和高级设置的滚动、控件显示检查通过；已查看本地渲染，截图位于 `artifacts/speech-0.1.99-screenshots/`。
- 最终归档 APK 的 3 项合同检查通过，零失败、零错误、零跳过。
- Release 构建通过；Lint 0 错误、64 警告。新增警告为高级设置中文说明的 `SetTextI18n`，没有新增 Lint 错误。
- 包名、版本与原证书 v3 签名已核对；归档与构建输出 SHA-256 一致。
- 官方公开目录已实时核实。未配置真实账户 Key，未验证账户模型接口、实际合成、真机播放或抖音发送。本轮为本地构建。

## 归档

- APK：`artifacts/DouAi-0.1.99-release.apk`
- 版本：`0.1.99` / `100`
- 大小：4,534,427 字节
- SHA-256：`D7E139D6C48B8F63B5154683DEB4E18EA1E30020D4AB3D36C2E8F6051518D66C`
- 签名证书 SHA-256：`FAE67C134F4C4FA73B8E34487C47A8C7F3E7C34B840BC245AFAC51585B7DFD47`
- 元数据和摘要：`artifacts/DouAi-0.1.99-release.json`、`artifacts/DouAi-0.1.99-SHA256SUMS.txt`。
