# 墨斐行动中心验证记录

日期：2026-07-20  
分支：`codex/mofei-action-center`

## 已实现范围

- 应用内七项能力环：当前屏幕、最近截图、相册、相机、通知草稿、当前事项、设置。
- 跨 App 五项紧凑能力环；左右停靠时对应展开和镜像，5 秒无交互后自动收起。
- 收起状态仅露出半个墨斐本体，不显示额外边框；墨斐本体支持横向、纵向拖动和侧边吸附。
- 展开状态中墨斐本体仍可作为拖动把手；系统手势排除区仅覆盖墨斐，避免 vivo 侧边栏抢占拖动。
- 环上功能第一次点击只展示名称，第二次点击才执行；名称使用环内深色胶囊，并始终置于墨斐和其他功能球上方。
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

- 能力环改为贴近墨斐的细线半环，环体仅占 132dp 宽，操作球 38dp，墨斐 64dp；透明交互预留区不参与视觉占用。
- 应用内与跨 App 共用 `MofeiActionRing`，保证首次点击说明、顶层标签和收起时序一致。
- 用户提供的图标原图重命名保存为 `apps/android/branding/mofei_app_icon_source.png`。
- Android 使用的透明圆角版本保存为 `apps/android/branding/mofei_app_icon.png`，并生成 mdpi、hdpi、xhdpi、xxhdpi、xxxhdpi 五档资源；只去除原图黑色角区，没有重绘主体。
- `AndroidManifest.xml` 的 `android:icon` 与 `android:roundIcon` 均指向 `@mipmap/mofei_app_icon`。

## 自动验证

最终命令：

```powershell
cd apps\android
.\gradlew.bat clean testDebugUnitTest assembleDebug assembleDebugAndroidTest --no-daemon --console=plain
```

结果：退出码 0。JUnit 报告共 25 个测试套件、111 个测试，失败 0、错误 0。新增回归覆盖能力环几何、收起状态根手势捕获和墨斐拖动边界。

- App APK：`apps/android/app/build/outputs/apk/debug/app-debug.apk`
- APK 大小：85,854,125 bytes
- SHA-256：`C2C49611DEB33D678F66A1FA22D1C0ECE73818C8AF66F0C8C25349E51743C264`
- AndroidTest APK：`apps/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
- AndroidTest APK 大小：998,221 bytes

静态边界检查：

- 合并清单必须包含 `FOREGROUND_SERVICE_MEDIA_PROJECTION` 和 `foregroundServiceType="mediaProjection"`。
- 合并清单不得包含 `android.intent.action.SEND`。
- 所有 `mofei_action_*.png` 必须包含透明像素且单文件不超过 1.5 MB。

检查结果：MediaProjection 权限存在、服务类型存在、SEND intent 不存在；APK 资源表包含五档 `mofei_app_icon`。

## 设备验证状态

设备：vivo V2509A，序列号 `10AFA30A7Z002Q5`。最终 App APK 已安装，`pm path com.suishouban.app` 返回有效安装路径。

- instrumentation：6 个测试，`OK (6 tests)`；覆盖 Room 迁移、能力环首次点击说明、通知萤火和 Overlay ViewTree owners。
- 手工拖动：墨斐从左侧拖到右侧，WindowManager 坐标最终为 `(990,1200)`，尺寸 `180x180`；未触发 vivo 侧边栏。
- 跨 App 首次点击：功能名称“识别当前屏”以环内顶层胶囊显示，没有使用底部 Toast；第二次点击仍进入原动作。
- 收起状态：仅半个墨斐停靠屏幕边缘，无外框。

证据截图：

- `docs/reports/assets/mofei-inline-label-topmost-fast.png`
- `docs/reports/assets/mofei-horizontal-body-drag.png`
- `docs/reports/assets/mofei-half-peek.png`
- `docs/reports/assets/mofei-snug-arc-overlay.png`
