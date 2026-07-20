# 测试与真实设备验收

## 后端

```powershell
.\scripts\setup_backend.ps1
.\scripts\test_backend.ps1
.\scripts\start_backend.ps1
```

`GET /health` 用于可观测性，`GET /ready` 用于部署就绪检查。正常环境必须满足：

- LangGraph 版本为 `1.2.1`。
- `AsyncSqliteSaver` 可导入。
- `workflow.db` 可读写。
- 检查点写入独立的 `workflow_checkpoint.db`。

模型和 vivo OCR 密钥属于可选增强。未配置时，Android ML Kit、截图 gate、本地规则、多任务拆卡、Room 保存和 WorkManager 提醒仍应可用。

## Android 构建

```powershell
.\scripts\build_android_debug.ps1
.\scripts\deploy_remote_android.ps1
```

### 墨斐行动中心权限与验收

构建与安装：

```powershell
cd apps\android
.\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest --no-daemon --console=plain
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

权限按能力分离，不能用一个总开关代替：

1. 应用内墨斐不需要悬浮窗权限。轻点后可直接使用 Photo Picker 和相机。
2. 跨 App 墨斐需要在系统“显示在其他应用上层”页面单独授权。
3. “识别当前屏幕”每次由 Android MediaProjection 系统页确认。授权只对本次捕获有效，App 不保存授权结果。
4. 通知草稿需要在设置中同时开启功能、授予“通知使用权”、选择来源 App 白名单。撤销通知使用权后只有该能力被锁定。
5. Android 13+ 的系统截图监听和“最近截图”需要图片读取权限；Photo Picker 本身不依赖全量相册权限。

通知隐私验收：

- 未在白名单内的 App 不产生候选。
- 验证码、支付内容、常驻通知、分组摘要和空通知不产生候选。
- 候选只保存在本地 Room 表中，24 小时到期；收到通知时不调用分析后端、不创建行动卡。
- 点“消息萤火”后仅用端侧规则生成预览草稿，不把通知原文送入 Workflow；忽略会删除候选，只有该候选关联的草稿保存成功后才删除并创建卡片。
- 不记录验收通知正文到文档或日志。

主动截屏验收：

- 从跨 App 能力环点击“识别当前屏”，确认悬浮墨斐在截屏前隐藏，预览关闭后恢复。
- 取消授权、受保护黑屏和 6 秒超时均应停止前台服务且不打开伪预览。
- 读取成功后检查 `adb shell dumpsys activity services com.suishouban.app`，不应残留 MofeiScreenCaptureService。
- 私有截屏文件位于 FileProvider 对应的 App 缓存路径，预览销毁后删除；系统截图和相机照片不受该清理影响。

当前产品不接受系统图片分享。清单中不应出现 `android.intent.action.SEND`；相册导入统一走系统 Photo Picker。

真实手机验收的目标不是绑定开发主机，而是确认 App 在手机上能独立完成截图判定、候选预览、用户确认、保存和提醒。默认不填写 Workflow URL 时，App 不访问本机地址、局域网地址或 vivo 原始 provider endpoint。

开发阶段可使用实体机、云真机或自动化脚本。当前脚本默认设备为 `val-vclinner-rt-contest.vivo.com.cn:37065`，但它只是测试环境；产品运行不依赖 ADB、`adb reverse` 或开发主机。vivo 安装器可能要求勾选风险提示并确认安装，部署脚本会尝试自动处理该页面。

部分测试设备虽然接受 `adb reverse`，但不会把流量转发到开发机。在线端到端测试应使用公网 HTTPS 后端网关，再在 App 设置页写入该地址并使用“测试服务连接”。临时隧道只能作为阻塞备选；临时 URL、截图、日志和隧道输出均不得提交。

离线回归应关闭网关或网络，确认文本分析仍走本地规则、图片仍可走 ML Kit，并且同步失败会显示明确错误。

## vivo 后端代理调试

Android 不直连 vivo/蓝心，也不把 API key 写入 APK。真实密钥只放在后端或 HTTPS 网关环境变量中；debug APK 最多注入一个非敏感的默认网关 URL：

```powershell
$env:DEFAULT_API_BASE_URL="https://your-temp-gateway.example.com/"
cd apps\android
.\gradlew.bat assembleDebug --no-daemon
```

默认服务地址留空时，App 不访问 `127.0.0.1`、`10.0.2.2` 或开发主机，端侧 ML Kit + 本地规则仍可完成截图判定、小窗确认、保存和提醒。需要验证 vivo API 增强时，先启动公网 HTTPS 后端代理，再把该 HTTPS 地址通过 App 设置页、`DEFAULT_API_BASE_URL` 或远端脚本的 `-WorkflowUrl` 写入设置。

## 复杂截图真实设备验收

```powershell
.\scripts\validate_remote_complex_screenshots.ps1
.\scripts\validate_remote_complex_screenshots.ps1 -WorkflowUrl "https://your-temp-gateway.example.com/"
```

脚本会连接测试设备，清装 APK，授权通知/图片权限，推送复杂样例图，验证广告、系统页和自身页面不提示，课程截图出现“可能有待办”小窗，多任务截图至少拆出两张候选卡，并检查 WorkManager 截止提醒与 logcat。未传 `-WorkflowUrl` 时只算端侧闭环；传入公网 HTTPS Workflow 网关后才算 vivo API 增强验收。

只有 `adb devices` 显示 `device` 且 APK 安装成功后，才算进入设备验收；`unauthorized` 或 `offline` 只能算开发环境阻塞，不能算通过。
