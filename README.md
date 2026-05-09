# 随手办

随手办是一个“截图到行动”的移动端项目。Android 端负责截图入口、OCR、动作预览和提醒落地；FastAPI 后端负责文本理解、行动卡生成、卡片存储和演示评测。

## 架构

```text
截图/图片
  -> Android OCR
  -> POST /api/analyze/screenshot-text
  -> LLM 抽取；失败时规则兜底
  -> 动作预览
  -> 确认后保存卡片、注册提醒、进入日历视图
```

后端内部按职责拆分：

```text
services/api/app
├── api/           # 路由、依赖注入、模块化 endpoints
├── core/          # 配置
├── db/            # 数据库连接和 schema 初始化
├── repositories/  # SQLite 持久化
├── schemas/       # Pydantic 请求和响应模型
└── services/      # 分析、LLM、规则抽取、提醒策略、指标
```

Android 端主要模块：

```text
apps/android/app/src/main/java/com/suishouban/app
├── data/          # Room、Retrofit、Repository、Settings
├── domain/        # 本地抽取规则、提醒策略
├── ocr/           # ML Kit 中文 OCR
├── reminder/      # WorkManager、日历同步、截图监听通知
└── ui/            # Compose 页面、组件、主题
```

## 功能

- 截图监听通知、系统分享图片、相册导入、文本粘贴。
- ML Kit 中文 OCR。
- 后端 LLM 抽取，未配置模型时自动使用规则兜底。
- 行动卡类型：任务、事件、承诺、资料。
- 动作预览：创建前可编辑标题、时间、地点、提交方式、提醒和待确认字段。
- 本地 Room 存储、WorkManager 通知提醒、日历视图、卡片筛选。
- 内置五类演示场景评测：课程通知、比赛报名、社团活动、聊天承诺、会议准备。

## 运行后端

```powershell
cd services/api
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
copy .env.example .env
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

接口文档：`http://127.0.0.1:8000/docs`

## 运行 Android

用 Android Studio 打开 `apps/android`，安装 Android SDK 35，运行 `app` 模块。

默认后端地址是 `http://10.0.2.2:8000/`，适用于 Android 模拟器。真机运行时，在 App 设置页把 API 地址改为电脑的局域网地址。

## 配置

`services/api/.env`：

```env
LANXIN_API_KEY=
LANXIN_BASE_URL=
LANXIN_MODEL=lanxin-pro
DATABASE_PATH=./suishouban.db
CORS_ORIGINS=*
```

`LANXIN_API_KEY` 或 `LANXIN_BASE_URL` 为空时，后端不会调用外部模型，会直接使用规则抽取。

## API

```http
POST /api/analyze/screenshot-text
GET  /api/cards
POST /api/cards
PATCH /api/cards/{id}
POST /api/cards/{id}/complete
GET  /api/demo/scenarios
GET  /api/demo/evaluate
GET  /api/metrics/summary
```

示例：

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri http://127.0.0.1:8000/api/analyze/screenshot-text `
  -ContentType application/json `
  -Body '{"text":"请同学们在本周五晚上 22:00 前提交实验报告，提交至学习通。"}'
```

## 验证

```powershell
cd services/api
python -m compileall app
```

```powershell
Invoke-RestMethod http://127.0.0.1:8000/api/demo/evaluate
Invoke-RestMethod http://127.0.0.1:8000/api/metrics/summary
```

当前环境未配置 Android SDK 时，Android 只能做源码和 XML 检查；APK 构建需要在 Android Studio 中完成。

## 文档

- [全国赛演示脚本](docs/COMPETITION_DEMO.md)
