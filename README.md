# 随手办

Android 原生端 + FastAPI 后端。核心链路是：截图/图片文本 -> OCR -> 行动抽取 -> 动作预览 -> 卡片与提醒。

## Structure

```text
apps/android   Android App, Kotlin, Compose, Room, WorkManager, ML Kit
services/api   FastAPI service, SQLite, LLM adapter, rule fallback
docs           demo script
```

## Run API

```powershell
cd services/api
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
copy .env.example .env
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

Docs: `http://127.0.0.1:8000/docs`

## Run Android

Open `apps/android` in Android Studio, install Android SDK 35, then run the `app` module.

Default API base URL is `http://10.0.2.2:8000/` for the Android emulator. For a real device, change the API URL in the app settings.

## API

```http
POST /api/analyze/screenshot-text
GET  /api/cards
POST /api/cards
PATCH /api/cards/{id}
POST /api/cards/{id}/complete
GET  /api/demo/evaluate
GET  /api/metrics/summary
```

Smoke test:

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri http://127.0.0.1:8000/api/analyze/screenshot-text `
  -ContentType application/json `
  -Body '{"text":"请同学们在本周五晚上 22:00 前提交实验报告，提交至学习通。"}'
```

## LLM Config

`services/api/.env`:

```env
LANXIN_API_KEY=
LANXIN_BASE_URL=
LANXIN_MODEL=lanxin-pro
DATABASE_PATH=./suishouban.db
```

If no LLM config is provided, the API uses the built-in rule extractor.
