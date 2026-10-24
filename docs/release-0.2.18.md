# DouAi 0.2.18 正式版：播放过渡与手势快捷设置

版本从 **0.2.16 / 117** 递增为 **0.2.18 / 119**，正式包名保持 `io.github.yudigaga.douai`。本版沿用长期签名，完成严格本地发布验收；GitHub 与 LSPosed 使用同一份 APK。真机、LSPosed 回调和真实服务未在本轮执行。

正式下载：[GitHub v0.2.18](https://github.com/yudigaga/DouAi/releases/tag/v0.2.18) · [LSPosed 119-0.2.18](https://github.com/Xposed-Modules-Repo/io.github.yudigaga.douai/releases/tag/119-0.2.18)。安装后完全退出并重新打开抖音。需要 Android 9+ 与支持 libxposed API 102 的 LSPosed，40.8 仍需内部构建 40809900，40.6 继续保留。

## 纳入的改动

- 自动播放在切换视频时捕获可见 SurfaceView 画面，在短暂解绑或解码期间显示内存覆盖层；检测到新作品开始播放后释放覆盖层，失败或超时会清理资源。
- 自动播放和手动上下翻页使用请求式切换，避免空白 SurfaceView 的平滑滚动过渡；评论打开、目标变化和 Activity 结束时取消迟到状态。
- 信息流手势新增“功能快捷”“切换模型”“切换音色”和“打开模块设置”，快捷修改继续写入既有配置存储，不复制另一套配置。
- 系统离线语音支持保存用户选择的同语言离线 Voice；网络语音和原有第三方语音配置保持独立。

## APK 与产物核对

[下载 DouAi-0.2.18-release.apk](https://github.com/yudigaga/DouAi/releases/download/v0.2.18/DouAi-0.2.18-release.apk)。

| 项目 | 本次结果 |
| --- | --- |
| versionName / versionCode | `0.2.18 / 119` |
| 正式包名 | `io.github.yudigaga.douai` |
| 文件大小 | 5,066,032 字节 |
| APK SHA-256 | `4EF4597DA4EF2BFF00DBCB23D2F7B7AD9AE113E14AFB43E1444093C748B7F610` |
| 签名 | APK v3 签名校验通过，使用既有长期证书 |
| 证书 SHA-256 | `FAE67C134F4C4FA73B8E34487C47A8C7F3E7C34B840BC245AFAC51585B7DFD47` |
| ZIP 对齐 | `zipalign -c -P 16 -v 4` 通过 |
| Release 元数据 | `aapt dump badging` 核对包名与版本，未出现 `application-debuggable` |

本地记录目录为 `artifacts/release-verification/20261024T090210Z-df562738/`，归档完整性复核通过。目录保存 ICU 预检、构建、签名、宿主 badging、JUnit、Lint、R8 mapping 和封存清单；不随公共源码分发。正式标签为 GitHub `v0.2.18` 与 LSPosed `119-0.2.18`。

## 验证范围

严格入口完成 2,313 项测试，225 个 suite，零失败、零错误、零跳过。Release Lint 0 错误，57 条既有告警，预算门禁通过；4 条联网依赖/工具链版本提示作为 advisory，不阻断构建。宿主静态合同使用抖音 40.8.0 / 400801 APK；未执行真机安装、真实 LSPosed Hook 回调、账号网络请求、评论/私信投递或服务端最终持久化验证。

此前 `0.2.12` 与 `0.2.13` 的 CI 失败来自旧版 Lint 预算对联网依赖版本提示的误判；当前主分支门禁已将这类提示单独列为 advisory，本版本地门禁已通过。远端 CI 结论以推送后对应 commit 的运行结果为准。

APK 关于页、GitHub 源码仓库和 LSPosed 模块仓库均保留[免责协议](../DISCLAIMER.md)：本项目仅用于学习与技术研究，用户的应用、配置、二次开发及使用本项目生成、下载、发送或发布内容的行为和结果由用户自行决定并承担责任，与本项目及作者无关。
