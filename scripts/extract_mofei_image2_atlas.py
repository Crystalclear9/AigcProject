#!/usr/bin/env python3
"""Extract native Mofei frames from the single gpt-image-2 sprite atlas."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

from PIL import Image


ROOT = Path(__file__).resolve().parent.parent
ASSET_DIR = ROOT / "output" / "imagegen" / "mofei-runtime"
ATLAS = ASSET_DIR / "mofei-image2-sprite-atlas-v4.png"
FRAME_COORDINATES = {
    "idle": ((0, 0), (1, 0), (2, 0)),
    "focus": ((3, 0), (4, 0), (5, 0)),
    "confirm": ((0, 1), (1, 1), (2, 1)),
    "reminder": ((3, 1), (4, 1), (5, 1)),
    "due_soon": ((0, 2), (1, 2), (2, 2)),
    "urgent": ((3, 2), (4, 2), (5, 2)),
    "complete": ((0, 3), (1, 3), (2, 3)),
    "rest": ((3, 3), (4, 3), (5, 3)),
}


def extract_cells(atlas: Image.Image, columns: int, rows: int) -> list[Image.Image]:
    """Crop a strict grid without resizing or repainting generated pixels."""
    if atlas.width % columns or atlas.height % rows:
        raise ValueError(f"Atlas {atlas.size} cannot be divided into {columns}x{rows} cells")
    cell_width = atlas.width // columns
    cell_height = atlas.height // rows
    if cell_width != cell_height:
        raise ValueError(f"Atlas cells must be square, got {cell_width}x{cell_height}")
    return [
        atlas.crop((column * cell_width, row * cell_height, (column + 1) * cell_width, (row + 1) * cell_height))
        for row in range(rows)
        for column in range(columns)
    ]


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def save_preview(frames: list[Image.Image], target: Path) -> None:
    frames[0].save(target, format="GIF", save_all=True, append_images=frames[1:], duration=250, loop=0, disposal=2)


def main() -> None:
    with Image.open(ATLAS) as source:
        cells = extract_cells(source.convert("RGBA"), columns=6, rows=4)

    manifest = {"version": 5, "source_atlas": ATLAS.name, "source_sha256": sha256(ATLAS), "states": {}}
    for state, coordinates in FRAME_COORDINATES.items():
        frames = [cells[row * 6 + column] for column, row in coordinates]
        filenames: list[str] = []
        for index, frame in enumerate(frames, start=1):
            target = ASSET_DIR / f"mofei_{state}_f{index:02d}.png"
            frame.save(target, format="PNG")
            filenames.append(target.name)
        base = ASSET_DIR / f"mofei_{state}_base.png"
        frames[0].save(base, format="PNG")
        preview = ASSET_DIR / f"mofei_{state}_preview.gif"
        save_preview(frames, preview)
        manifest["states"][state] = {"base": base.name, "motion_frames": filenames, "preview": preview.name}

    files = sorted(ASSET_DIR.glob("mofei_*_base.png")) + sorted(ASSET_DIR.glob("mofei_*_f*.png"))
    manifest["assets"] = [{"file": file.name, "sha256": sha256(file), "size_bytes": file.stat().st_size} for file in files]
    (ASSET_DIR / "manifest-image2-v4.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
