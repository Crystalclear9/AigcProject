# 随手办

随手办是一个“截图到行动”的移动产品原型：用户截图后，App 先在端侧完成 OCR、噪声过滤和行动证据判断。只有发现明确的待办、截止、会议、报名或承诺信号时，才给出低打扰提示；用户确认后才创建行动卡、注册提醒或同步日历。

产品目标是让手机在真实使用场景中独立运行：默认不依赖开发主机，云端 AI 只作为可选增强，通过公网 HTTPS Workflow 网关接入。

## 核心体验

```text
用户截图
  -> Android 端侧 ML Kit OCR
  -> 状态栏、底栏、广告、按钮、App 自身页面等噪声过滤
  -> ScreenshotActionGate 证据评分
  -> 无明确行动信号：静默忽略
  -> 命中强行动信号：低打扰“可能有待办”通知
  -> 用户点击生成：顶部小窗展示候选卡
  -> 本地规则先出草稿，云端 Workflow 可异步增强
  -> 用户选择、编辑并确认
  -> 保存 Room 行动卡，注册 WorkManager 截止提醒，可选写入日历
```

手动入口仍然保留：相册导入、系统分享图片和文字粘贴可以直接进入预览。

## 产品原则

- **端侧优先**：默认不依赖开发主机或后端。端侧 OCR、行动判定、本地规则抽取、Room 卡片和 WorkManager 提醒均可独立运行。
- **云端增强可选**：设置页可配置 Workflow API URL 并启用云端增强；不配置时 App 不会访问 `127.0.0.1`、`10.0.2.2`、局域网 IP 或开发主机。
- **Provider 解耦**：Android 不持有 vivo/蓝心 key。文本模型、OCR 和图片生成都由随手办 HTTPS Workflow 网关代理；手机侧只保存网关 URL。
- **证据驱动**：工作流保留 provisional 草稿、SSE 事件、字段来源、置信度、ActionGraph、审核和确认门控；模型不可用时仍可走端侧与规则降级。
- **低打扰交互**：截图建议采用静默通知和顶部小窗，避免全屏弹窗打断当前 App。
- **确认前无外部动作**：用户确认前不写入最终卡片、不注册提醒、不写日历。云端增强属于数据处理行为，首次启用时应明确说明会把脱敏 OCR 文本发送到用户配置的 Workflow 网关。

## 仓库结构

```text
.
├── apps/android/                         # Android Compose 客户端
│   └── app/src/main/java/com/suishouban/app/
│       ├── data/                         # Room、Repository、远程 DTO/API
│       ├── domain/                       # 本地规则抽取、OCR 清洗、截图 gate
│       ├── ocr/                          # ML Kit OCR
│       ├── reminder/                     # 截图监听、通知、WorkManager 提醒
│       └── ui/                           # Compose 页面与组件
├── services/api/                         # FastAPI + LangGraph Workflow 网关
├── docs/                                 # 架构、接口资料、测试指南和报告
├── scripts/                              # 构建、部署、远端验收脚本
└── .github/workflows/                    # CI
```

## Android 运行

用 Android Studio 打开 `apps/android`，安装 Android SDK 35，运行 `app` 模块。

默认是本机模式：

```text
Workflow API URL = 空
启用云端增强 = 关闭
```

这意味着真机不需要连接开发主机即可完成截图识别、提示、预览、本地保存和提醒。若要接入云端增强，请在 App 设置页填写手机可访问的 HTTPS Workflow 网关，例如：

```text
https://api.your-domain.com/
```

本地开发可以使用 `adb reverse tcp:8000 tcp:8000`，但这只是开发便利，不是产品运行前提。

## vivo API 与网关

Android 不直连 vivo/蓝心 provider，也不把 API key 写入 APK。需要验证 vivo/蓝心增强时，先部署公网 HTTPS Workflow 网关，key 只放在后端或密钥系统中；debug APK 只允许注入非敏感的默认 Workflow URL。

```powershell
$env:DEFAULT_API_BASE_URL="https://your-gateway.example.com/"
cd apps\android
.\gradlew.bat assembleDebug --no-daemon
```

不要把真实 key 写入 `build.gradle.kts`、脚本、README 或任何 Android 构建环境变量。没有网关 URL、无网或 provider 失败时，App 会降级到 ML Kit OCR + 本地规则工作流。

后端主要环境变量：

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

`/health` 只返回 provider 是否配置、URL 是否合规和运行时是否就绪，不回显任何密钥。Chat 与图片生成 provider 必须使用 HTTPS、预期 vivo 域名和预期路径；OCR 按 vivo 官方文档允许精确的 `http://api-ai.vivo.com.cn/ocr/general_recognition`，但仍拒绝任意 HTTP、私网和非预期路径配置。生产环境建议通过受控 TLS 网关转发 OCR，避免服务端 AppKey 与图片内容经过不可信明文链路。错误配置会让 `/ready` 失败。

## 后端启动

```powershell
cd services/api
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
copy .env.example .env
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

健康检查：

```powershell
Invoke-RestMethod http://127.0.0.1:8000/health
```

接口文档：

```text
http://127.0.0.1:8000/docs
```

生产或云真机验收推荐部署为公网 HTTPS 网关，再由手机设置页填写该 HTTPS Workflow API URL。

## API 概览

旧同步接口保留兼容：

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

Provider 与指标接口：

```http
GET  /api/providers/status
POST /api/providers/probe
GET  /api/metrics/summary
GET  /api/metrics/performance
```

`/api/providers/probe` 默认关闭，只应在受控验收环境中通过 `ENABLE_PROVIDER_PROBE=true` 启用。

## 质量验证

本地回归用于构建和单元测试：

```powershell
cd services/api
python -m pytest -q

cd ..\..\apps\android
.\gradlew.bat testDebugUnitTest assembleDebug --no-daemon
```

复杂截图链路需要在云真机验证，默认设备为：

```text
val-vclinner-rt-contest.vivo.com.cn:38053
```

```powershell
.\scripts\validate_remote_complex_screenshots.ps1
.\scripts\validate_remote_complex_screenshots.ps1 -WorkflowUrl "https://your-gateway.example.com/"
```

远端验收覆盖：无行动截图不提示、行动截图出现低打扰“可能有待办”小窗、点击生成后展示候选卡、多任务截图拆出多张卡、选择后保存 Room、注册 WorkManager 截止提醒、logcat 无崩溃/DTO/Room/WorkManager/本机地址连接错误。

未传 `-WorkflowUrl` 时脚本只验证端侧 ML Kit + 本地规则闭环；传入公网 HTTPS Workflow 网关后，才会验证 vivo API/蓝心增强。

更多部署、远端验收和故障排查见 [本地与云真机测试指南](docs/guides/LOCAL_AND_REMOTE_TESTING.md)。
