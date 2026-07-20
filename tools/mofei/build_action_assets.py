#!/usr/bin/env python3
"""Build transparent, size-bounded Mofei action-center Android assets.

The large capability-ring sources are generated artwork. Small semantic glyphs are
drawn here so their silhouettes and names stay deterministic across rebuilds.
"""

from __future__ import annotations

import argparse
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter


CYAN = (73, 232, 255, 255)
PALE = (216, 250, 255, 255)
NAVY = (8, 36, 86, 245)
GLASS = (118, 210, 255, 105)
SILVER = (174, 213, 236, 230)


def _fit(source: Path, destination: Path, size: int) -> None:
    """Downsample without discarding the generated transparent outer glow."""
    image = Image.open(source).convert("RGBA")
    image.thumbnail((size, size), Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", (size, size))
    canvas.alpha_composite(image, ((size - image.width) // 2, (size - image.height) // 2))
    destination.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(destination, optimize=True)


def _base_glyph(size: int = 192) -> tuple[Image.Image, ImageDraw.ImageDraw]:
    image = Image.new("RGBA", (size, size))
    glow = Image.new("RGBA", image.size)
    glow_draw = ImageDraw.Draw(glow)
    glow_draw.ellipse((27, 27, size - 27, size - 27), outline=(42, 224, 255, 170), width=12)
    image.alpha_composite(glow.filter(ImageFilter.GaussianBlur(11)))
    draw = ImageDraw.Draw(image)
    draw.ellipse((32, 32, size - 32, size - 32), fill=(38, 93, 147, 90), outline=PALE, width=5)
    draw.ellipse((40, 40, size - 40, size - 40), fill=NAVY, outline=CYAN, width=6)
    return image, draw


def _spark(draw: ImageDraw.ImageDraw, x: int, y: int, radius: int) -> None:
    points = [(x, y - radius), (x + 4, y - 4), (x + radius, y), (x + 4, y + 4),
              (x, y + radius), (x - 4, y + 4), (x - radius, y), (x - 4, y - 4)]
    draw.polygon(points, fill=PALE)


def _draw_glyph(kind: str) -> Image.Image:
    image, draw = _base_glyph()
    if kind == "capture_current_screen":
        draw.rounded_rectangle((58, 58, 134, 134), radius=15, outline=CYAN, width=7)
        for x, y, dx, dy in ((48, 48, 20, 0), (144, 48, -20, 0), (48, 144, 20, 0), (144, 144, -20, 0)):
            draw.line((x, y, x + dx, y), fill=PALE, width=6)
            draw.line((x, y, x, y + (20 if y < 96 else -20)), fill=PALE, width=6)
        draw.ellipse((82, 82, 110, 110), fill=GLASS, outline=PALE, width=4)
    elif kind == "latest_screenshot":
        draw.rounded_rectangle((55, 62, 128, 132), radius=10, fill=GLASS, outline=CYAN, width=6)
        draw.polygon(((63, 119), (82, 96), (94, 108), (108, 90), (124, 119)), fill=SILVER)
        _spark(draw, 135, 55, 20)
    elif kind == "pick_image":
        draw.rounded_rectangle((50, 57, 142, 136), radius=14, fill=GLASS, outline=PALE, width=6)
        draw.ellipse((69, 72, 86, 89), fill=CYAN)
        draw.polygon(((58, 125), (84, 94), (103, 112), (119, 91), (136, 125)), fill=CYAN)
    elif kind == "take_photo":
        draw.rounded_rectangle((48, 67, 144, 133), radius=15, fill=GLASS, outline=PALE, width=6)
        draw.rounded_rectangle((70, 54, 108, 72), radius=6, fill=SILVER)
        draw.ellipse((72, 76, 120, 124), fill=NAVY, outline=CYAN, width=7)
        draw.ellipse((88, 92, 104, 108), fill=PALE)
    elif kind == "notification_drafts":
        draw.arc((60, 54, 132, 131), 195, 345, fill=CYAN, width=8)
        draw.polygon(((64, 112), (128, 112), (117, 91), (112, 71), (80, 71), (75, 91)), fill=GLASS, outline=PALE)
        draw.ellipse((89, 116, 103, 130), fill=CYAN)
        _spark(draw, 139, 65, 13)
    elif kind == "open_current_card":
        draw.rounded_rectangle((58, 55, 126, 137), radius=10, fill=GLASS, outline=CYAN, width=6)
        draw.line((73, 78, 112, 78), fill=PALE, width=5)
        draw.line((73, 95, 112, 95), fill=SILVER, width=5)
        draw.line((73, 112, 101, 112), fill=SILVER, width=5)
        draw.arc((43, 44, 149, 148), 210, 55, fill=PALE, width=4)
    elif kind == "open_settings":
        draw.ellipse((67, 67, 125, 125), fill=GLASS, outline=CYAN, width=7)
        draw.ellipse((86, 86, 106, 106), fill=NAVY, outline=PALE, width=4)
        for angle in range(0, 360, 45):
            import math
            x = 96 + int(math.cos(math.radians(angle)) * 43)
            y = 96 + int(math.sin(math.radians(angle)) * 43)
            draw.ellipse((x - 7, y - 7, x + 7, y + 7), fill=SILVER, outline=CYAN, width=2)
    elif kind == "seal":
        draw.polygon(((96, 48), (137, 64), (132, 113), (96, 143), (60, 113), (55, 64)), fill=GLASS, outline=PALE)
        draw.rounded_rectangle((75, 88, 117, 121), radius=8, fill=NAVY, outline=CYAN, width=5)
        draw.arc((80, 65, 112, 101), 180, 360, fill=CYAN, width=6)
        draw.ellipse((91, 99, 101, 109), fill=PALE)
    else:
        raise ValueError(f"Unknown glyph kind: {kind}")
    return image


def _validate(directory: Path) -> None:
    """Fail early when packaging accidentally loses alpha or exceeds the UI budget."""
    for path in directory.glob("mofei_action_*.png"):
        image = Image.open(path)
        if image.mode != "RGBA" or image.getchannel("A").getextrema()[0] != 0:
            raise ValueError(f"{path.name} must contain transparent pixels")
        if path.stat().st_size > 1_500_000:
            raise ValueError(f"{path.name} exceeds 1.5 MB")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-dir", type=Path, required=True)
    parser.add_argument("--out-dir", type=Path, required=True)
    args = parser.parse_args()
    args.out_dir.mkdir(parents=True, exist_ok=True)

    _fit(args.source_dir / "mofei_action_ring_full_alpha.png", args.out_dir / "mofei_action_ring_full.png", 1024)
    _fit(args.source_dir / "mofei_action_ring_compact_alpha.png", args.out_dir / "mofei_action_ring_compact.png", 768)
    for kind in (
        "capture_current_screen", "latest_screenshot", "pick_image", "take_photo",
        "notification_drafts", "open_current_card", "open_settings", "seal",
    ):
        _draw_glyph(kind).save(args.out_dir / f"mofei_action_{kind}.png", optimize=True)
    _validate(args.out_dir)


if __name__ == "__main__":
    main()
