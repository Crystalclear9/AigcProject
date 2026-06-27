# 本地与云真机测试

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

模型和 vivo OCR 密钥属于可选能力，未配置时规则和 Android ML Kit 仍可工作。

## Android

```powershell
.\scripts\build_android_debug.ps1
.\scripts\deploy_remote_android.ps1
```

默认云真机为 `val-vclinner-rt-contest.vivo.com.cn:38197`。vivo 安装器可能要求勾选风险提示并确认安装，部署脚本会尝试自动处理该页面。

部分云真机虽然接受 `adb reverse`，但不会把流量转发到开发机。在线端到端测试应使用公网 HTTPS 后端网关，再在 App 设置页写入该地址并使用“测试服务连接”。临时隧道只能作为阻塞备选；临时 URL、截图、日志和隧道输出均不得提交。

离线回归应关闭网关或网络，确认文本分析仍走本地规则、图片仍可走 ML Kit，并且同步失败会显示明确错误。

## 蓝心后端代理调试

Android 不直连蓝心，也不把 API key 写入 APK。蓝心 key 只放在后端或 HTTPS 网关环境变量中；debug APK 最多注入一个非敏感的默认网关 URL：

```powershell
$env:DEFAULT_API_BASE_URL="https://your-temp-gateway.example.com/"
cd apps\android
.\gradlew.bat assembleDebug --no-daemon
```

默认 Workflow API URL 留空时，App 不访问 `127.0.0.1`、`10.0.2.2` 或开发主机，端侧 ML Kit + 本地规则仍可完成截图判定、小窗确认、保存和提醒。需要验证蓝心增强时，先启动公网 HTTPS 后端代理，再把该 HTTPS 地址通过 App 设置页、`DEFAULT_API_BASE_URL` 或远端脚本的 `-WorkflowUrl` 写入设置。

## 复杂截图远端验收

```powershell
.\scripts\validate_remote_complex_screenshots.ps1
.\scripts\validate_remote_complex_screenshots.ps1 -WorkflowUrl "https://your-temp-gateway.example.com/"
```

脚本会连接 `38197`，清装 APK，授权通知/图片权限，推送复杂样例图，验证广告、系统页和自身页面不提示，课程截图出现“可能有待办”小窗，多任务截图至少拆出两张候选卡，并检查 WorkManager 截止提醒与 logcat。未传 `-WorkflowUrl` 时只算端侧闭环；传入公网 HTTPS Workflow 网关后才算 vivo API 增强验收。

只有 `adb devices` 显示 `device` 且 APK 安装成功后，才算进入云真机验收；`unauthorized` 或 `offline` 只能算阻塞，不能算通过。
