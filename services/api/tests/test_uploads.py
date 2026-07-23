from __future__ import annotations

import asyncio
from io import BytesIO

import pytest
from fastapi import HTTPException, UploadFile


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
