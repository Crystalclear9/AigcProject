# 随手办 Workflow API

这是随手办的后端 Workflow 网关，负责：

- 文本/截图 workflow 编排、SSE 事件、ReAct 受控完善。
- vivo/蓝心 chat、vivo OCR、图片生成 provider 的服务端代理。
- provider telemetry、health/readiness、指标和脱敏 probe。
- 旧版分析接口兼容和行动卡管理。

Android 不直接持有 provider key，也不直连 `api-ai.vivo.com.cn`。真实手机只配置这个服务的公网 HTTPS 地址；不配置时，Android 端侧 ML Kit + 本地规则仍能独立完成截图判定、候选卡、保存和提醒。

## 本地开发

```powershell
cd services/api
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
copy .env.example .env
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

本地接口文档：

```text
http://127.0.0.1:8000/docs
```

这只是开发入口。真实手机或云真机验收应使用公网 HTTPS 网关，再在 App 设置页填写该 HTTPS 服务地址。

## Provider 配置

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
VIVO_OCR_TIMEOUT_SECONDS=5

VIVO_IMAGE_GENERATION_API_KEY=
VIVO_IMAGE_GENERATION_URL=https://api-ai.vivo.com.cn/api/v1/image_generation
VIVO_IMAGE_GENERATION_MODEL=Doubao-Seedream-4.5

ENABLE_PROVIDER_PROBE=false
MAX_UPLOAD_IMAGE_BYTES=5242880
DATABASE_PATH=./suishouban.db
CORS_ORIGINS=*
```

安全边界：

- 所有真实 key 只来自环境变量或密钥系统，不写入 Android、README、脚本、APK 或日志。
- Chat 与图片生成必须使用 HTTPS、预期 vivo 域名和预期路径。
- OCR 按 vivo 官方文档允许精确的 `http://api-ai.vivo.com.cn/ocr/general_recognition`，但会拒绝任意 HTTP、私网和非预期路径。
- 生产建议通过受控 TLS 网关转发 OCR，避免 AppKey 与图片内容经过不可信明文链路。

## 核心接口

```http
GET  /health
GET  /ready

POST /api/analyze/screenshot-text
POST /api/analyze/screenshot-image

POST /api/workflows/screenshot-text
POST /api/workflows/screenshot-image
GET  /api/workflows/{run_id}
GET  /api/workflows/{run_id}/events
POST /api/workflows/{run_id}/react
POST /api/workflows/{run_id}/confirm

GET  /api/providers/status
POST /api/providers/probe
GET  /api/metrics/summary
GET  /api/metrics/performance
```

`/api/providers/probe` 默认关闭，只在受控验收环境设置 `ENABLE_PROVIDER_PROBE=true` 后用于证明 provider 实际可调用。响应只返回脱敏状态和计数，不回显 key、完整 prompt 或完整 OCR 文本。

## 验证

```powershell
cd services/api
.venv\Scripts\python.exe -m pytest -q
```

Android 真机/云真机验收请使用仓库根目录下的 `scripts/validate_remote_complex_screenshots.ps1`，并传入公网 HTTPS Workflow URL 才能验证 vivo API 增强链路。

## 部署

```powershell
docker build -t suishouban-workflow-gateway .
docker run --rm -p 8000:8000 `
  -e FAST_MODEL_API_KEY="<server-side-key>" `
  -e EXPERT_MODEL_API_KEY="<server-side-key>" `
  -e VIVO_OCR_APP_ID="<server-side-app-id>" `
  -e VIVO_OCR_APP_KEY="<server-side-key>" `
  suishouban-workflow-gateway
```

容器应放在 HTTPS 入口之后，再把公网 HTTPS 地址配置到 App 设置页。
