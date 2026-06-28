# 随手办

随手办是一个“截图到行动”的移动产品：用户截图后，App 先在手机端完成 OCR、噪声过滤和行动证据判断。只有识别到明确的待办、截止、会议、报名或承诺信号时，才给出低打扰提示；用户确认后才创建行动卡、注册提醒或同步日历。

产品目标是让手机在真实使用场景中独立运行。默认不依赖开发主机；云端 AI 只作为可选增强，通过公网 HTTPS Workflow 网关接入。

## 用户路径

```text
截图 / 相册 / 分享 / 粘贴文字
  -> 端侧 ML Kit OCR 与噪声清洗
  -> 行动证据判定
  -> 无明确行动：静默忽略
  -> 有明确行动：低打扰“可能有待办”通知
  -> 用户点击生成：顶部小窗展示候选卡
  -> 本地规则先出草稿，云端 Workflow 可异步增强
  -> 用户选择、编辑、确认
  -> 保存 Room 行动卡，注册 WorkManager 截止提醒，可选写入系统日历
```

确认前不会写入最终卡片、不会注册提醒、不会写日历。云端增强只补字段、追加建议或更新证据，不覆盖用户锁定字段。

## 产品原则

- **端侧优先**：OCR、截图 gate、本地规则、多任务拆卡、Room 卡片和提醒均可在手机侧完成。
- **少打扰**：通知采用静默紧凑样式；同一截图被忽略后短时间内不重复提示。
- **证据驱动**：候选卡展示标题、时间、地点/平台、材料/提交方式、证据摘要和置信度。
- **用户确认优先**：保存、提醒、日历写入都必须由用户确认触发。
- **云端可插拔**：vivo/蓝心 provider 只由后端代理调用，Android 只保存 Workflow HTTPS 网关 URL。
- **可降级**：无网、模型失败、OCR 失败时保留本地规则和手动补全入口。

## 架构

```text
apps/android/                         Android Compose 客户端
  app/src/main/java/com/suishouban/app/
    data/                              Room、Repository、远程 DTO/API
    domain/                            本地规则抽取、OCR 清洗、截图 gate
    ocr/                               ML Kit OCR
    reminder/                          截图监听、通知、WorkManager 提醒
    ui/                                Compose 页面与组件

services/api/                          FastAPI + LangGraph Workflow 网关
docs/                                  架构、接口资料、测试指南和报告
scripts/                               构建、部署、云真机验收脚本
.github/workflows/                     CI
```

## Android 运行

用 Android Studio 打开 `apps/android`，安装 Android SDK 35，运行 `app` 模块。

默认设置：

```text
Workflow API URL = 空
启用云端增强 = 关闭
```

此时真机不需要连接开发主机，也不会访问 `127.0.0.1`、`10.0.2.2`、局域网 IP 或 `api-ai.vivo.com.cn` 原始 provider endpoint。截图识别、提示、预览、本地保存和提醒均走端侧闭环。

如需启用云端增强，在设置页填写手机可访问的 HTTPS Workflow 网关，例如：

```text
https://api.your-domain.com/
```

`adb reverse tcp:8000 tcp:8000` 只适合本地开发调试，不是产品运行前提。

## vivo API 与后端网关

Android 不直连 vivo/蓝心 provider，也不把 API key 写入 APK。文本模型、OCR、图片生成都由随手办 Workflow 网关代理。

后端环境变量示例：

```env
LANXIN_API_KEY=
LANXIN_BASE_URL=https://api-ai.vivo.com.cn/v1
LANXIN_MODEL=Doubao-Seed-2.0-mini

FAST_MODEL_API_KEY=
FAST_MODEL_BASE_URL=https://api-ai.vivo.com.cn/v1
FAST_MODEL_NAME=Doubao-Seed-2.0-mini

EXPERT_MODEL_API_KEY=
EXPERT_MODEL_BASE_URL=https://api-ai.vivo.com.cn/v1
EXPERT_MODEL_NAME=Doubao-Seed-2.0-pro

VIVO_OCR_APP_ID=
VIVO_OCR_APP_KEY=
VIVO_OCR_URL=http://api-ai.vivo.com.cn/ocr/general_recognition
VIVO_OCR_BUSINESS_PROFILE=rotatable

VIVO_IMAGE_GENERATION_API_KEY=
VIVO_IMAGE_GENERATION_URL=https://api-ai.vivo.com.cn/api/v1/image_generation
VIVO_IMAGE_GENERATION_MODEL=Doubao-Seedream-4.5
ENABLE_PROVIDER_PROBE=false
```

安全边界：

- 不要把真实 key 写入 Android、Gradle、README、脚本、APK 或日志。
- Chat 与图片生成 provider 必须使用 HTTPS、预期 vivo 域名和预期路径。
- OCR 按 vivo 官方文档允许精确的 `http://api-ai.vivo.com.cn/ocr/general_recognition`，但仍拒绝任意 HTTP、私网和非预期路径配置。
- 生产环境建议通过受控 TLS 网关转发 OCR，避免服务端 AppKey 与图片内容经过不可信明文链路。
- `/health`、`/ready`、`/api/providers/status` 只返回脱敏状态，不回显密钥。
- `/api/providers/probe` 默认关闭，只应在受控验收环境中通过 `ENABLE_PROVIDER_PROBE=true` 启用。

## 后端启动

```powershell
cd services/api
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
copy .env.example .env
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

健康检查与接口文档：

```powershell
Invoke-RestMethod http://127.0.0.1:8000/health
```

```text
http://127.0.0.1:8000/docs
```

云真机和真实手机验收应部署公网 HTTPS 网关，再在 App 设置页填写该 HTTPS Workflow API URL。

## API 概览

兼容旧接口：

```http
POST /api/analyze/screenshot-text
POST /api/analyze/screenshot-image
```

工作流接口：

```http
POST /api/workflows/screenshot-text
POST /api/workflows/screenshot-image
GET  /api/workflows/{run_id}
GET  /api/workflows/{run_id}/events
POST /api/workflows/{run_id}/ocr-candidates
PATCH /api/workflows/{run_id}/draft
POST /api/workflows/{run_id}/react
POST /api/workflows/{run_id}/confirm
POST /api/workflows/{run_id}/resume
```

Provider 与指标：

```http
GET  /api/providers/status
POST /api/providers/probe
GET  /api/metrics/summary
GET  /api/metrics/performance
```

## 构建与测试

本地回归：

```powershell
cd services/api
.venv\Scripts\python.exe -m pytest -q

cd ..\..\apps\android
.\gradlew.bat testDebugUnitTest assembleDebug --no-daemon
```

云真机验收默认设备：

```text
val-vclinner-rt-contest.vivo.com.cn:37065
```

```powershell
.\scripts\validate_remote_complex_screenshots.ps1
.\scripts\validate_remote_complex_screenshots.ps1 -WorkflowUrl "https://your-gateway.example.com/"
```

远端验收覆盖：

- 广告、系统页、自身页面不提示。
- 行动截图出现“可能有待办”，忽略后不保存。
- 生成后展示候选卡，确认后保存 Room 并注册 WorkManager 截止提醒。
- 多任务截图拆出多张卡，支持全部创建和选择性创建。
- ReAct 只完善选中卡；空选择提示先选择。
- logcat 无崩溃、DTO、Room/SQLite、WorkManager、主线程网络、本机地址连接错误。

未传 `-WorkflowUrl` 时脚本只验证端侧 ML Kit + 本地规则闭环；传入公网 HTTPS Workflow 网关后，才会验证 vivo API/蓝心增强和 provider telemetry。

## 部署建议

- Android 发布包不要包含任何 provider key。
- Workflow 网关使用 HTTPS、服务端密钥管理和访问控制。
- SQLite 适合当前单机部署；扩大并发或多实例后再引入 Redis、消息队列或独立调度系统。
- provider probe、调试日志和临时隧道只用于验收环境。

## 已知限制

- 没有公网 Workflow 网关时，无法在云真机上声明 vivo API 增强通过。
- 云真机 ADB 可能出现 `unauthorized/offline`，未进入 `device` 前不算安装验收。
- OCR 官方示例为 HTTP endpoint；生产建议前置 TLS 网关。
- 日历写入依赖用户授权和设备上可写日历；失败会在 App 内提示。

更多远端验收和故障排查见 [本地与云真机测试指南](docs/guides/LOCAL_AND_REMOTE_TESTING.md)。
