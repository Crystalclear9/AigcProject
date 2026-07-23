from __future__ import annotations

import asyncio
from io import BytesIO

import pytest
from fastapi import HTTPException, UploadFile
from fastapi.testclient import TestClient

from app.core.config import settings
from app.main import create_app


def _read(upload: UploadFile, limit: int) -> bytes:
    from app.api.uploads import read_limited_upload

    return asyncio.run(read_limited_upload(upload, max_bytes=limit))


def test_upload_at_limit_is_returned_unchanged() -> None:
    payload = b"image-bytes"
    upload = UploadFile(BytesIO(payload), size=len(payload), filename="image.png")

    assert _read(upload, len(payload)) == payload


def test_known_oversize_upload_is_rejected_before_reading() -> None:
    payload = b"x" * 100
    source = BytesIO(payload)
    upload = UploadFile(source, size=len(payload), filename="large.png")

    with pytest.raises(HTTPException) as raised:
        _read(upload, 10)

    assert raised.value.status_code == 413
    assert source.tell() == 0


def test_unknown_oversize_upload_stops_after_first_chunk() -> None:
    payload = b"x" * (256 * 1024)
    source = BytesIO(payload)
    upload = UploadFile(source, size=None, filename="streamed.png")

    with pytest.raises(HTTPException) as raised:
        _read(upload, 10)

    assert raised.value.status_code == 413
    assert 0 < source.tell() < len(payload)


def test_empty_upload_is_rejected() -> None:
    upload = UploadFile(BytesIO(), size=0, filename="empty.png")

    with pytest.raises(HTTPException) as raised:
        _read(upload, 10)

    assert raised.value.status_code == 400


def test_request_body_limit_rejects_before_multipart_parsing() -> None:
    original_limit = settings.max_upload_image_bytes
    object.__setattr__(settings, "max_upload_image_bytes", 32)
    try:
        client = TestClient(create_app())
        response = client.post(
            "/api/workflows/screenshot-image",
            content=b"x" * (128 * 1024),
            headers={"Content-Type": "application/octet-stream"},
        )
    finally:
        object.__setattr__(settings, "max_upload_image_bytes", original_limit)

    assert response.status_code == 413
    assert response.json()["detail"] == "请求体超过上传大小限制"


def test_request_body_limit_stops_chunked_body_without_content_length() -> None:
    from app.api.body_limit import RequestBodyLimitMiddleware

    async def exercise() -> tuple[list[dict[str, object]], bool]:
        messages = iter(
            [
                {"type": "http.request", "body": b"123456", "more_body": True},
                {"type": "http.request", "body": b"789012", "more_body": False},
            ]
        )
        sent: list[dict[str, object]] = []
        completed = False

        async def receive() -> dict[str, object]:
            return next(messages)

        async def send(message: dict[str, object]) -> None:
            sent.append(message)

        async def downstream(scope, receive, send) -> None:
            nonlocal completed
            while True:
                message = await receive()
                if not message.get("more_body", False):
                    completed = True
                    return

        middleware = RequestBodyLimitMiddleware(
            downstream,
            max_bytes=10,
            limited_paths={"/api/workflows/screenshot-image"},
        )
        scope = {
            "type": "http",
            "method": "POST",
            "path": "/api/workflows/screenshot-image",
            "headers": [],
        }
        await middleware(scope, receive, send)
        return sent, completed

    sent, completed = asyncio.run(exercise())

    assert sent[0]["type"] == "http.response.start"
    assert sent[0]["status"] == 413
    assert not completed
