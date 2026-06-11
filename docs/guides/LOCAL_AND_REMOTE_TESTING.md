# 本地与云真机测试

## 后端

```powershell
.\scripts\setup_backend.ps1
.\scripts\test_backend.ps1
.\scripts\start_backend.ps1
```

`GET /health` 用于可观测性，`GET /ready` 用于部署就绪检查。正常环境必须满足：

- LangGraph 版本为 `1.2.1`
- `AsyncSqliteSaver` 可导入
- `workflow.db` 可读写
- 检查点写入独立的 `workflow_checkpoint.db`

模型和 vivo OCR 密钥属于可选能力，未配置时规则和 Android ML Kit 仍可工作。

## Android

```powershell
.\scripts\build_android_debug.ps1
.\scripts\deploy_remote_android.ps1
```

默认云真机为 `val-vclinner-rt-contest.vivo.com.cn:35185`。vivo 安装器会要求勾选风险提示并确认安装，部署脚本会自动处理该页面。

部分云真机虽然接受 `adb reverse`，但不会把流量转发到开发机。在线端到端测试应建立临时 HTTPS 隧道，再在 App 设置页写入该地址并使用“测试服务连接”。临时 URL、截图、日志和隧道输出均不得提交。

离线回归应关闭隧道，确认文本分析仍走本地规则、图片仍可走 ML Kit，并且同步失败会显示明确错误。
