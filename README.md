# 随手办

随手办是一个“截图到行动”的 Android + FastAPI 项目。它的产品目标不是让用户每次手动上传截图，而是在用户截图后先在端侧完成 OCR 和行动判定，只在发现明确待办、截止、会议、报名、承诺等信号时给出轻量提示；用户确认后才生成行动卡、注册提醒或同步日历。

## 产品工作流

```text
用户截图
  -> Android 端侧 ML Kit OCR
  -> OCR 噪声清洗：状态栏、底栏、按钮、广告和 App 自身界面过滤
  -> ScreenshotActionGate 证据评分
  -> 无明确行动信号：静默忽略，只写调试日志
  -> 命中强行动信号：发送低打扰通知“可能有待办”
  -> 用户点击“生成”：进入预览页生成草稿
  -> 用户编辑并确认：保存 Room 行动卡，注册 WorkManager 截止提醒，可选写入日历
```

手动入口仍然保留：相册导入、系统分享图片和文字粘贴可以直接进入预览。最终创建行动卡、提醒、日历写入都必须由用户确认触发。

## 架构原则

- **端侧优先**：默认不依赖任何主机或后端。端侧 OCR、行动判定、本地规则抽取、Room 卡片和 WorkManager 提醒均可独立运行。
- **云端增强可选**：设置页可配置 Workflow API URL 并启用云端增强；不配置时 App 不会假设 `127.0.0.1:8000`、局域网 IP 或某台开发主机存在。
- **AI Provider 解耦**：后端预留快模型、强模型、OCR、联网检索等独立 provider 环境变量，不同能力可以接入不同 OpenAI 兼容或厂商 API。
- **证据驱动**：工作流保留 provisional 草稿、SSE 事件、字段来源、置信度、审核和确认门控；模型不可用时仍可使用端侧与规则流程。
- **低打扰交互**：截图建议通知采用短文案、默认静默、低打扰样式，避免大弹窗打断当前 App 使用。

## 仓库结构

```text
.
├── apps/
│   └── android/                         # Android Compose 客户端
│       └── app/src/main/java/com/suishouban/app/
│           ├── data/                    # Room、Repository、远程 DTO/API
│           ├── domain/                  # 本地规则抽取
│           │   └── screenshot/          # OCR 清洗与截图行动判定
│           ├── ocr/                     # ML Kit OCR
│           ├── reminder/                # 截图监听、通知、WorkManager 提醒
│           └── ui/                      # Compose 页面与组件
├── services/
│   └── api/                             # FastAPI + LangGraph 工作流服务
├── docs/
│   ├── api/vivo-aigc/                   # vivo AIGC 接口资料归档
│   ├── architecture/                    # 架构说明
│   ├── guides/                          # 演示与调试指南
│   ├── product/                         # 产品策划案交付物
│   └── reports/                         # 接口实验与验收报告
├── scripts/                             # 构建、测试、部署辅助脚本
├── .github/workflows/                   # CI
└── README.md
```

## Android 运行方式

用 Android Studio 打开 `apps/android`，安装 Android SDK 35，运行 `app` 模块。

默认配置是本机模式：

```text
Workflow API URL = 空
启用云端增强 = 关闭
```

这意味着真机不需要连接开发主机即可完成截图识别、提示、预览、本地保存和提醒。若要接入后端或 AI 工作流，在 App 设置页配置一个手机可访问的 HTTPS 网关，例如：

```text
https://api.your-domain.com/
```

本地开发调试仍可使用 `adb reverse tcp:8000 tcp:8000`，但这只是开发便利，不是产品运行前提。

### Android 后端代理调试

Android 不再直连蓝心，也不把 API key 写入 APK。需要验证蓝心增强时，先启动公网 HTTPS 后端代理，key 只放在后端/网关环境变量中；debug APK 只允许注入非敏感的默认 API URL：

```powershell
$env:DEFAULT_API_BASE_URL="https://your-temp-gateway.example.com/"
cd apps\android
.\gradlew.bat assembleDebug --no-daemon
```

不要把真实 key 写入 `build.gradle.kts`、脚本、文档或任何 Android 构建环境变量。没有网关 URL、无网或蓝心代理失败时，App 会降级到 ML Kit OCR + 本地规则工作流，不访问 `127.0.0.1`、`10.0.2.2` 或开发主机。

### 云真机强制验收

复杂截图链路的功能验收以云真机为准，默认设备为：

```text
val-vclinner-rt-contest.vivo.com.cn:35165
```

执行：

```powershell
.\scripts\validate_remote_complex_screenshots.ps1
.\scripts\validate_remote_complex_screenshots.ps1 -WorkflowUrl "https://your-temp-gateway.example.com/"
```

验收覆盖：无行动截图不提示、行动截图出现低打扰“可能有待办”小窗、点击生成后展示候选卡、多任务截图拆出多张卡、选择后保存 Room、注册 WorkManager 截止提醒、logcat 无崩溃/DTO/Room/WorkManager/本机地址连接错误。

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

生产或云真机验收推荐部署为公网 HTTPS 网关，蓝心 key 只放在网关环境变量中：

```powershell
cd services/api
docker build -t suishouban-workflow-gateway .
docker run --rm -p 8000:8000 `
  -e LANXIN_API_KEY="<server-side-key>" `
  -e FAST_MODEL_API_KEY="<server-side-key>" `
  -e EXPERT_MODEL_API_KEY="<server-side-key>" `
  suishouban-workflow-gateway
```

再由反向代理或托管平台提供 HTTPS 域名，手机设置页填写该 HTTPS Workflow API URL。

## AI 与外部能力配置

后端配置文件位于 `services/api/.env`，可从 `.env.example` 复制。不同模块可以接入不同服务：

```env
# 兼容旧蓝心配置
LANXIN_API_KEY=
LANXIN_BASE_URL=https://api-ai.vivo.com.cn/v1
LANXIN_MODEL=Doubao-Seed-2.0-mini

# 快模型：规划、普通抽取、低延迟增强
FAST_MODEL_API_KEY=
FAST_MODEL_BASE_URL=https://api-ai.vivo.com.cn/v1
FAST_MODEL_NAME=Doubao-Seed-2.0-mini

# 强模型：复杂冲突、最终验证
EXPERT_MODEL_API_KEY=
EXPERT_MODEL_BASE_URL=https://api-ai.vivo.com.cn/v1
EXPERT_MODEL_NAME=Doubao-Seed-2.0

# 云 OCR，可选；Android 端侧 OCR 始终可用
VIVO_OCR_APP_ID=
VIVO_OCR_APP_KEY=
VIVO_OCR_BUSINESS_PROFILE=rotatable

REQUEST_TIMEOUT_SECONDS=20
MAX_UPLOAD_IMAGE_BYTES=5242880
DATABASE_PATH=./suishouban.db
WORKFLOW_DATABASE_PATH=./workflow.db
WORKFLOW_CHECKPOINT_DATABASE_PATH=./workflow_checkpoint.db
CORS_ORIGINS=*
```

没有模型密钥或 OCR 密钥时，后端仍会保留规则降级路径；Android 端默认不依赖这些密钥。

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
POST /api/workflows/{run_id}/confirm
POST /api/workflows/{run_id}/resume
```

卡片与指标：

```http
GET  /api/cards
POST /api/cards
PATCH /api/cards/{id}
POST /api/cards/{id}/complete
GET  /api/metrics/summary
GET  /api/metrics/performance
```

## 验证

后端：

```powershell
cd services/api
python -m pytest -q
```

Android：

```powershell
cd apps/android
.\gradlew.bat testDebugUnitTest assembleDebug --no-daemon
```

云真机或真机回归建议覆盖：

- 普通界面截图：不出现行动提示。
- 花哨课程通知或海报截图：出现低打扰“可能有待办”通知。
- 点击“忽略”：不生成卡片。
- 点击“生成”：进入预览页，用户确认后保存卡片。
- 有截止时间的任务：WorkManager 注册临近截止提醒。
- 未配置 Workflow API URL：端侧流程仍完整可用。
- 配置 HTTPS Workflow API URL：SSE、审核和云端增强正常工作。

## 文档索引

- [架构说明](docs/architecture/ARCHITECTURE.md)
- [云真机与 ADB 调试](docs/ADB_DEBUGGING.md)
- [全国赛演示脚本](docs/guides/COMPETITION_DEMO.md)
- [接口实验与比赛验收报告](docs/reports/API_EXPERIMENT_REPORT.md)
- [vivo AIGC 接口资料](docs/api/vivo-aigc/README.md)
