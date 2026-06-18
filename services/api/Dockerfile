FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

RUN useradd --create-home --shell /usr/sbin/nologin appuser

COPY requirements.txt .
RUN pip install --upgrade pip && pip install -r requirements.txt

COPY app ./app

RUN mkdir -p /data/workflow_inputs && chown -R appuser:appuser /app /data

USER appuser

ENV DATABASE_PATH=/data/suishouban.db \
    WORKFLOW_DATABASE_PATH=/data/workflow.db \
    WORKFLOW_CHECKPOINT_DATABASE_PATH=/data/workflow_checkpoint.db \
    WORKFLOW_INPUT_DIRECTORY=/data/workflow_inputs

EXPOSE 8000

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
