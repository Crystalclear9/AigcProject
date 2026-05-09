# 随手办

截图触发的 AI 行动助手。项目根据《随手办-产品策划案》实现 Android 原生移动端与 FastAPI 后端，覆盖截图导入、OCR、AI/规则结构化抽取、行动卡生成、动作预览、提醒、卡片管理、日历视图、隐私与同步偏好。

## 工程结构

```text
.
├── apps/android                 # Kotlin + Jetpack Compose Android App
│   └── app/src/main/java/com/suishouban/app
│       ├── data                 # Room、Retrofit、Repository、Settings
│       ├── domain               # 本地规则抽取与提醒策略
│       ├── ocr                  # ML Kit 中文 OCR
│       ├── reminder             # WorkManager 提醒与 Calendar 同步
│       └── ui                   # Compose 主题、组件、页面
├── services/api                 # FastAPI 后端
│   └── app
│       ├── api                  # /api/v1 路由
│       ├── db                   # SQLite 持久化
│       ├── schemas              # Pydantic 数据结构
│       └── services             # 蓝心大模型兼容封装与规则兜底
├── 随手办-产品策划案.pdf
└── 随手办-产品策划案.docx
```

## 已实现能力

- Android 页面：今日主页、截图导入、动作预览、卡片中心、日历视图、设置中心。
- Android 本地能力：系统分享图片入口、相册导入、截图监听通知入口、ML Kit 中文 OCR、Room 本地卡片库、WorkManager 通知提醒、Calendar 写入兜底。
- Android 网络能力：Retrofit 调用 FastAPI；服务端不可用或未配置模型时自动使用本地规则抽取。
- 后端能力：截图文本分析、行动卡 CRUD、完成状态、SQLite 存储、蓝心大模型 OpenAI-compatible 调用、规则抽取兜底。
- 支持卡片：任务卡、事件卡、承诺卡、资料卡。
- 支持场景：课程通知、比赛报名、社团活动、聊天承诺、会议准备。

## 后端运行

```powershell
cd C:\Users\70454\Desktop\AIGC\services\api
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
copy .env.example .env
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

接口文档：`http://127.0.0.1:8000/docs`

Android 模拟器访问本机后端使用默认地址：`http://10.0.2.2:8000/`。真机调试时，把 App 设置页中的 `API Base URL` 改成电脑局域网 IP，例如 `http://192.168.1.10:8000/`。

## Android 运行

1. 用 Android Studio 打开 `C:\Users\70454\Desktop\AIGC\apps\android`。
2. 确认 Android Studio 已安装 Android SDK 35，并等待 Gradle Sync 完成。
3. 启动后端。
4. 运行 `app` 到 Android 模拟器或真机。
5. 在 App 中进入“导入”，选择图片或使用内置场景文本生成行动卡。

项目包名：`com.suishouban.app`

关键依赖：

- Jetpack Compose Material 3
- Room
- Retrofit + Gson
- WorkManager
- ML Kit Chinese Text Recognition

## API

```http
POST /api/v1/analyze/screenshot-text
GET /api/v1/cards
POST /api/v1/cards
PATCH /api/v1/cards/{id}
POST /api/v1/cards/{id}/complete
GET /api/v1/demo/scenarios
GET /api/v1/demo/evaluate
GET /api/v1/metrics/summary
```

示例请求：

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri http://127.0.0.1:8000/api/v1/analyze/screenshot-text `
  -ContentType application/json `
  -Body '{"text":"请同学们在本周五晚上 22:00 前提交实验报告，提交至学习通，文件命名为学号+姓名。"}'
```

现场评测接口：

```powershell
Invoke-RestMethod http://127.0.0.1:8000/api/v1/demo/evaluate
Invoke-RestMethod http://127.0.0.1:8000/api/v1/metrics/summary
```

## 蓝心大模型配置

复制 `services/api/.env.example` 为 `.env` 后配置：

```env
LANXIN_API_KEY=你的 key
LANXIN_BASE_URL=https://你的蓝心兼容接口/v1
LANXIN_MODEL=你的模型名
```

未配置时，后端会自动使用规则抽取，比赛 demo 不依赖外部 key。

## 演示流程

1. 在“导入”页选择截图或点选高频场景。
2. OCR 文本进入分析接口。
3. “动作预览”页展示待创建任务、提醒、日历事件和待确认字段。
4. 编辑标题、时间、地点、提交方式、提醒策略。
5. 确认后写入 Room，并注册 WorkManager 通知提醒。
6. 在“卡片中心”按类型、状态、关键词筛选。
7. 在“日历视图”查看时间线和冲突提示。
8. 在“设置中心”管理后端地址、隐私脱敏、截图保留、模型偏好和日历同步。

## 竞赛答辩亮点

- 不是通用聊天助手，而是围绕“截图之后怎么办”的行动闭环。
- 有端侧 OCR、本地兜底、云端大模型三层能力，现场无网或无 key 也能演示。
- AI 输出不直接执行，先进入动作预览，用户可编辑并确认，解决可信度问题。
- 待确认字段、时间冲突、隐私脱敏、原始截图保留策略均有产品化表达。
- 后端内置五类场景评测接口，可现场证明课程通知、比赛报名、社团活动、聊天承诺、会议准备都能识别。

## 说明

Android 当前支持相册导入、系统分享图片和前台截图监听通知入口。悬浮窗属于需要 `SYSTEM_ALERT_WINDOW` 特殊授权的增强入口，本工程先用通知入口完成轻量触发；核心截图到行动闭环已经通过截图监听、图片导入、系统分享、OCR、AI 抽取、预览确认和提醒完成。
