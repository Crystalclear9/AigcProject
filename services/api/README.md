# 随手办 API

FastAPI 后端负责截图文本结构化、vivo OCR 图片识别、蓝心大模型/规则抽取兜底、行动卡管理和演示评测。

```bash
cd services/api
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
copy .env.example .env
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

访问 `http://127.0.0.1:8000/docs` 查看接口文档。Android 模拟器访问本机后端时使用 `http://10.0.2.2:8000/`。

## 配置

```env
LANXIN_API_KEY=
LANXIN_BASE_URL=https://api-ai.vivo.com.cn/v1
LANXIN_MODEL=Doubao-Seed-2.0-mini
VIVO_OCR_APP_ID=
VIVO_OCR_APP_KEY=
VIVO_OCR_URL=https://api-ai.vivo.com.cn/ocr/general_recognition
VIVO_OCR_BUSINESS_PROFILE=rotatable
VIVO_OCR_TIMEOUT_SECONDS=5
VIVO_IMAGE_GENERATION_API_KEY=
VIVO_IMAGE_GENERATION_URL=https://api-ai.vivo.com.cn/api/v1/image_generation
VIVO_IMAGE_GENERATION_MODEL=Doubao-Seedream-4.5
MAX_UPLOAD_IMAGE_BYTES=5242880
DATABASE_PATH=./suishouban.db
CORS_ORIGINS=*
```

vivo AIGC 鉴权使用 `Authorization: Bearer AppKey`。图片接口按文档提交 `requestId`、`businessid=aigc+appid`、`pos=2`，返回相对坐标用于清洗截图噪声。

## 关键接口

```http
POST /api/analyze/screenshot-text
POST /api/analyze/screenshot-image
GET  /api/demo/evaluate
GET  /api/metrics/summary
```

分析接口会返回 `engine`、`trace_id`、`fallback_reason`、`warnings`，用于展示 `vivo-ocr+lanxin`、`vivo-ocr+rules`、`rules` 等真实执行链路。

## 验证

```powershell
cd services/api
python -m compileall app
python -m unittest discover -s tests -v
```

## HTTPS gateway deployment

Use this service as the only BlueLM/Lanxin proxy. Android must never receive
`LANXIN_API_KEY`, `FAST_MODEL_API_KEY`, or `EXPERT_MODEL_API_KEY`.

```powershell
docker build -t suishouban-workflow-gateway .
docker run --rm -p 8000:8000 `
  -e LANXIN_API_KEY="<server-side-key>" `
  -e FAST_MODEL_API_KEY="<server-side-key>" `
  -e EXPERT_MODEL_API_KEY="<server-side-key>" `
  suishouban-workflow-gateway
```

Expose the container through a public HTTPS host before entering the Workflow
API URL on the phone.

Phone settings must use this service's public HTTPS Workflow gateway URL, not
the raw vivo provider endpoints. The chat, OCR, and image-generation provider
URLs stay server-side and are read only from environment variables.
