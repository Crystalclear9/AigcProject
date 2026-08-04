from __future__ import annotations

from fastapi import HTTPException, UploadFile

UPLOAD_READ_CHUNK_BYTES = 64 * 1024


async def read_limited_upload(upload: UploadFile, *, max_bytes: int) -> bytes:
    """Read an upload without ever accumulating more than the configured limit."""
    if max_bytes < 1:
        raise ValueError("max_bytes must be positive")
    if upload.size is not None:
        if upload.size <= 0:
            raise HTTPException(status_code=400, detail="图片为空")
        if upload.size > max_bytes:
            raise HTTPException(status_code=413, detail="图片超过上传大小限制")

    content = bytearray()
    while True:
        remaining = max_bytes - len(content)
        chunk = await upload.read(min(UPLOAD_READ_CHUNK_BYTES, remaining + 1))
        if not chunk:
            break
        if len(chunk) > remaining:
            raise HTTPException(status_code=413, detail="图片超过上传大小限制")
        content.extend(chunk)

    if not content:
        raise HTTPException(status_code=400, detail="图片为空")
    return bytes(content)
