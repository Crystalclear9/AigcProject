# 随手办

随手办是一个面向大学生和轻办公场景的“截图到行动”移动端项目。用户把课程通知、比赛海报、社团活动、会议安排或聊天承诺截图导入后，系统会完成 OCR、语义抽取、行动卡生成、动作预览、提醒和日历管理。

## 核心链路

```text
截图/图片
  -> Android 相册/分享/截图入口
  -> 优先 POST /api/analyze/screenshot-image
  -> vivo OCR；失败时回退 Android ML Kit
  -> 蓝心大模型结构化抽取；失败时规则兜底
  -> 动作预览与字段确认
  -> 保存行动卡、注册提醒、进入卡片/日历视图
```

文本粘贴路径会直接调用 `POST /api/analyze/screenshot-text`，同样优先走蓝心大模型，失败时自动回退规则抽取。

## 仓库结构

```text
.
├── apps/
│   └── android/                 # Android Compose 客户端
├── services/
│   └── api/                     # FastAPI 后端服务
├── docs/
│   ├── api/vivo-aigc/           # vivo AIGC 接口资料归档
│   ├── architecture/            # 架构说明
│   ├── guides/                  # 演示脚本与使用指南
│   ├── product/                 # 产品策划案交付物
│   └── reports/                 # 接口实验与验收报告
├── scripts/                     # 文档抓取等辅助脚本
├── .gitignore
└── README.md
```

## 功能概览

- 截图监听通知、系统分享图片、相册导入、文本粘贴。
- 图片路径优先使用 vivo 通用 OCR，失败时自动回退 ML Kit 中文 OCR。
- 后端蓝心大模型抽取，未配置 key、接口超时或调用失败时自动使用规则兜底。
- 分析结果返回 `trace_id`、`fallback_reason`、`warnings`，便于演示和排查真实接口链路。
- 行动卡类型覆盖任务、事件、承诺、资料。
- 动作预览支持创建前编辑标题、时间、地点、提交方式、提醒和待确认字段。
- 本地 Room 存储、WorkManager 通知提醒、日历视图、卡片筛选。
- 内置五类演示场景评测：课程通知、比赛报名、社团活动、聊天承诺、会议准备。

## 后端快速启动

```powershell
cd services/api
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
copy .env.example .env
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

接口文档地址：

```text
http://127.0.0.1:8000/docs
```

健康检查：

```powershell
Invoke-RestMethod http://127.0.0.1:8000/health
```

## Android 快速启动

用 Android Studio 打开 `apps/android`，安装 Android SDK 35，运行 `app` 模块。

默认后端地址是：

```text
http://10.0.2.2:8000/
```

这个地址适用于 Android 模拟器。真机运行时，在 App 设置页把 API 地址改为电脑的局域网地址，例如 `http://192.168.x.x:8000/`。

## 环境变量

后端配置文件位于 `services/api/.env`，可从 `.env.example` 复制：

```env
LANXIN_API_KEY=
LANXIN_BASE_URL=https://api-ai.vivo.com.cn/v1
LANXIN_MODEL=Doubao-Seed-2.0-mini
REQUEST_TIMEOUT_SECONDS=20
VIVO_OCR_APP_ID=
VIVO_OCR_APP_KEY=
VIVO_OCR_BUSINESS_PROFILE=rotatable
VIVO_OCR_TIMEOUT_SECONDS=5
MAX_UPLOAD_IMAGE_BYTES=5242880
DATABASE_PATH=./suishouban.db
CORS_ORIGINS=*
```

`LANXIN_API_KEY` 为空时，后端不会调用蓝心大模型，会直接使用规则抽取。`VIVO_OCR_APP_KEY` 为空时，后端图片接口会返回明确错误，Android 会回退到 ML Kit 本地 OCR。

## API 概览

```http
POST /api/analyze/screenshot-text
POST /api/analyze/screenshot-image
GET  /api/cards
POST /api/cards
PATCH /api/cards/{id}
POST /api/cards/{id}/complete
GET  /api/demo/scenarios
GET  /api/demo/evaluate
GET  /api/metrics/summary
```

分析接口响应保留原有字段：

```json
{
  "ocr_text": "识别或输入的文本",
  "cards": [],
  "preview_actions": [],
  "engine": "vivo-ocr+lanxin"
}
```

同时新增演示和排查字段：

```json
{
  "trace_id": "per-request uuid",
  "fallback_reason": "RuntimeError: ...",
  "warnings": ["蓝心大模型不可用，已自动切换到本地规则抽取"]
}
```

文本分析示例：

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri http://127.0.0.1:8000/api/analyze/screenshot-text `
  -ContentType application/json `
  -Body '{"text":"请同学们在本周五晚上 22:00 前提交实验报告，提交至学习通。"}'
```

## 验证

后端静态编译和单元测试：

```powershell
cd services/api
python -m compileall app
python -m unittest discover -s tests -v
```

接口验收：

```powershell
Invoke-RestMethod http://127.0.0.1:8000/api/demo/evaluate
Invoke-RestMethod http://127.0.0.1:8000/api/metrics/summary
```

Android 单元测试需要本机配置 Android SDK：

```powershell
cd apps/android
.\gradlew.bat testDebugUnitTest --no-daemon
```

如果当前环境未配置 `ANDROID_HOME` 或 `local.properties` 的 `sdk.dir`，Android 只能做源码级检查；APK 构建和单测需要在 Android Studio 环境完成。

## 文档索引

- [架构说明](docs/architecture/ARCHITECTURE.md)
- [全国赛演示脚本](docs/guides/COMPETITION_DEMO.md)
- [接口实验与比赛验收报告](docs/reports/API_EXPERIMENT_REPORT.md)
- [产品策划案 PDF](docs/product/随手办-产品策划案.pdf)
- [产品策划案 DOCX](docs/product/随手办-产品策划案.docx)
- [vivo AIGC 接口资料](docs/api/vivo-aigc/README.md)
