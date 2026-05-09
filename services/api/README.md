# 随手办 API

FastAPI 后端负责截图文本结构化、行动卡管理和 AI/规则抽取兜底。

```bash
cd services/api
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
copy .env.example .env
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

访问 `http://127.0.0.1:8000/docs` 查看接口文档。Android 模拟器访问本机后端时使用 `http://10.0.2.2:8000/`。
