# 墨斐行动中心验证记录

日期：2026-07-20  
分支：`codex/mofei-action-center`

## 已实现范围

- 应用内七项能力环：当前屏幕、最近截图、相册、相机、通知草稿、当前事项、设置。
- 跨 App 五项紧凑能力环；左右停靠时对应展开和镜像。
- 单次 MediaProjection 授权、单帧私有缓存、预览和完整资源释放。
- 系统截图监听与主动捕获共用像素指纹；同一画面在两种来源之间按 10 分钟窗口去重。
- 通知特殊访问、来源 App 白名单、本地候选、敏感内容过滤、24 小时过期。
- 消息萤火只提供“打开分析”和“忽略”，没有直接创建行动卡路径。
- 系统图片分享入口移除。

## 隐私与权限检查

| 能力 | 权限/数据边界 | 失败行为 |
|---|---|---|
| 应用内墨斐 | 不需要悬浮窗权限 | 仅隐藏应用内能力环 |
| 跨 App 墨斐 | SYSTEM_ALERT_WINDOW，用户设置页开启 | 悬浮能力不可用，不影响应用内入口 |
| 当前屏幕 | 每次 MediaProjection 系统确认 | 取消不重试；黑屏/受保护内容不生成预览 |
| 最近截图 | Android 图片读取权限 | 没有权限或候选时返回空，不读取普通最近照片 |
| 通知草稿 | 通知使用权 + 功能开关 + App 白名单 | 撤销后封印通知能力，不循环打开设置 |
| 通知候选 | 原文与分析均仅本地，Room 保存 24 小时 | 忽略，或其关联草稿保存成功后删除 |

## 视觉资产

- `mofei_action_ring_full.png`：1024×1024，透明背景，约 689 KB。
- `mofei_action_ring_compact.png`：768×768，透明背景，约 339 KB。
- 两个能力环为 image2 生成原画，经色键透明化和本地尺寸校验后打包。
- 小图标与权限印记由 `tools/mofei/build_action_assets.py` 确定性生成；原因是图像服务的图谱请求三次在远端回传阶段失败。图标仍复用冰蓝玻璃、深蓝内芯和青色发光语言。
- 目视检查未发现白色矩形背景、生成文字或透明角残留；完整环七槽、紧凑环五槽与运行时动作数一致。

## 自动验证

最终命令：

```powershell
cd apps\android
.\gradlew.bat clean testDebugUnitTest assembleDebug assembleDebugAndroidTest --no-daemon --console=plain
```

结果：退出码 0。JUnit 报告共 24 个测试套件、107 个测试，失败 0、错误 0。新增回归覆盖通知草稿关联、通知动态过期、跨来源截图指纹及两个来源并发争抢同一画面的原子去重；Compose 能力环、通知萤火和 Room 迁移测试已编译进测试 APK。因没有设备，本轮未执行 instrumentation。

- App APK：`apps/android/app/build/outputs/apk/debug/app-debug.apk`
- APK 大小：85,719,431 bytes
- SHA-256：`0860F57D9C6AA22ECD67F4DB4F35C8B9A5F9DB75AF593194F8FAF040E06C830F`
- AndroidTest APK：`apps/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
- AndroidTest APK 大小：994,925 bytes

静态边界检查：

- 合并清单必须包含 `FOREGROUND_SERVICE_MEDIA_PROJECTION` 和 `foregroundServiceType="mediaProjection"`。
- 合并清单不得包含 `android.intent.action.SEND`。
- 所有 `mofei_action_*.png` 必须包含透明像素且单文件不超过 1.5 MB。

检查结果：MediaProjection 权限存在、服务类型存在、SEND intent 不存在；资产构建脚本退出码 0；`git diff --check` 退出码 0。

## 设备验证状态

`adb devices -l` 在本次执行时没有返回设备，因此没有运行 `connectedDebugAndroidTest`，也没有把十项真机交互检查标记为通过。待连接设备后执行：

```powershell
cd apps\android
.\gradlew.bat connectedDebugAndroidTest --no-daemon --console=plain
adb shell dumpsys activity services com.suishouban.app
adb shell dumpsys notification
```

真机需要逐项验证：Photo Picker、相机、每次投影授权、受保护内容、系统截图监听、跨来源去重、通知白名单、敏感通知过滤、确认后创建、权限撤销以及减弱动效。
