# Android 平台烟测与性能基线

这套烟测在指定 Android 设备或模拟器上检查模块自身的平台合同。执行成功只说明这些隔离样例通过，不能证明 LSPosed Hook、抖音宿主界面、自动评论或私信发送正常。测试使用合成配置和报告，不读取账号，不调用网络接口。

## 执行一次烟测

需要已授权的设备序列号、Android SDK 和本地构建环境。每个 ADB 命令都带 `-s`，脚本不会自动选择其他设备。推荐使用专用模拟器；`-Install` 会向指定设备安装或更新当前项目的 Debug APK 和测试 APK，遇到签名不兼容会失败，不会自动卸载、清除应用数据或调整权限。

```powershell
adb devices
.\tools\run-platform-smoke.ps1 -Serial emulator-5554 -Build -Install
```

已经构建并安装同一份代码时，可以省略 `-Build -Install`。只构建不会安装；只安装需要现成的两个 Debug APK。

```powershell
.\tools\run-platform-smoke.ps1 -Serial emulator-5554
```

SDK 不在默认位置时传入 `-SdkRoot`，或直接用 `-Adb` 指定 ADB。结果保存到 `artifacts/platform-smoke/smoke-<run>/`，也可通过 `-OutputRoot` 调整父目录。测试 runner 为 `androidx.test.runner.AndroidJUnitRunner`，类为 `com.douai.module.ui.PlatformSmokeTest`。

## 检查的合同

| 合同 | 实际 Android 路径 | 清理与限制 |
| --- | --- | --- |
| 默认脱敏备份与导入 | 独立 SharedPreferences、真实 `ConfigTransfer`、Android 正则实现；验证占位符保留、凭据隐藏、缺失密钥清空 | 每次生成随机偏好文件名，`finally` 删除；不使用正式配置文件 |
| PNG 保存与读取 | 通过模块自身 ContentResolver 创建 MediaStore 图片，验证 pending 发布、PNG 头、独立 Bitmap 解码和报告尾部 | 调用生产清理逻辑并确认精确 URI 消失；清理失败时同步删除该 URI 并让测试失败 |
| PDF 保存与解析 | MediaStore Downloads、真实文件描述符、Android PdfRenderer 页数与末页绘制 | 同上；不会启动分享面板或向其他应用发送内容 |

PNG/PDF 用合成的两页以上报告。报告构建与 Picture 捕获在主线程执行，编码和 provider I/O 在仪器化测试线程执行，遵守生产代码的线程边界。

API 28 的正式存储路径需要用户通过 SAF 选择文档位置。无交互烟测会明确跳过两项 provider 写入，结果为 `partial`，不会申请全盘存储权限。API 28 的文件选择、写入、分享和用户取消仍需人工验证。进程或设备被强制终止时，`finally` 无法保证运行；应在专用测试设备检查是否留下报告样例。

## 读取结果和比较性能

`summary.json` 保存明确的序列号、SDK、系统指纹、ABI、模拟器标记、总耗时和未覆盖场景。`installedApks` 是设备上实际安装的应用和测试包 SHA-256，`localApks` 是本地可用构建产物的 SHA-256；两者分开记录，避免把旧安装包的通过结果归给新代码。`instrumentation.log` 保留 runner 原始结果，`metrics.jsonl` 保存每个合同的状态、耗时纳秒和 Java 堆使用量。没有三个完整结果，或 runner/合同失败时，脚本会报错；API 28 的存储跳过会保留为未覆盖。

指标是整个合同的一次操作样本，包含文件写入、解码或解析及清理。Java 堆数值是前后快照，受 GC 影响，不代表分配总量、峰值或 native/GPU 内存。脚本不会根据耗时或堆变化宣称帧率达标。

比较修改前后时，使用同一设备、系统、构建类型和数据，重复运行，分别记录首次与后续结果，比较各合同耗时的中位数以及波动范围。不要把单次模拟器结果当作真机性能结论。

宿主冷启动、滑动帧时间、Hook 热路径、长报告排版峰值、网络取消和真实账号交互，需要另外采集设备日志或性能跟踪。这些场景始终列在 `summary.json` 的未覆盖字段中；这套烟测本身不补足这些证据。

本文描述可执行合同，不声称已经在任何设备上通过。设备结论以每次生成的原始日志和 `summary.json` 为准。
