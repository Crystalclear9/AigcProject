"""Background-removal (抠图) pipeline for Mofei sprite frames.

Every packaged frame was an opaque PNG with a near-white/light-blue backdrop, so the
mascot showed a solid rectangle over the app's tinted panels. This regenerates all frames
from the clean output/mofei/<mood>/ source sets using the rembg isnet-general-use matting
model, trims to the subject, pads square, and writes transparent PNGs at a uniform size.

It also fixes the "old three-frame" mismatch: the previously bundled f01-f03 were a
different (256px) render than the f04-f08 copied later, so the 8-frame loop jumped between
two styles. Here all eight frames come from one consistent source set per mood.
"""
import sys
import numpy as np
from PIL import Image
from rembg import remove, new_session

DST = "apps/android/app/src/main/res/drawable-nodpi"
SESSION = new_session("isnet-general-use")
OUT_SIZE = 512  # uniform packaged size; sprites render into <=128dp so this is ample

# mood -> (source dir under output/mofei, resource prefix)
MOODS = {
    "focus": ("focus", "mofei_focus"),
    "confirm": ("confirm", "mofei_confirm"),
    "reminder": ("reminder", "mofei_reminder"),
    "due_soon": ("due", "mofei_due_soon"),
    "urgent": ("urgent", "mofei_urgent"),
    "complete": ("complete", "mofei_complete"),
    "rest": ("rest", "mofei_rest"),
}


def matte(path):
    im = Image.open(path).convert("RGBA")
    return remove(im, session=SESSION)


def trim_and_square(im, pad_frac=0.06):
    a = np.asarray(im)
    alpha = a[:, :, 3]
    ys, xs = np.where(alpha > 12)
    if len(xs) == 0:
        return im.resize((OUT_SIZE, OUT_SIZE), Image.LANCZOS)
    x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
    cropped = im.crop((x0, y0, x1 + 1, y1 + 1))
    side = int(max(cropped.width, cropped.height) * (1 + pad_frac * 2))
    canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    canvas.paste(cropped, ((side - cropped.width) // 2, (side - cropped.height) // 2))
    return canvas.resize((OUT_SIZE, OUT_SIZE), Image.LANCZOS)


def build_frame(src_path, dst_name):
    out = trim_and_square(matte(src_path))
    out.save("%s/%s.png" % (DST, dst_name))
    return dst_name


def main():
    written = 0
    for mood, (srcdir, prefix) in MOODS.items():
        for n in range(1, 9):
            src = "output/mofei/%s/mofei_%s_f%02d.png" % (srcdir, mood, n)
            build_frame(src, "%s_f%02d" % (prefix, n))
            written += 1
        print("done", mood)
    # In-app idle is the source of truth for IDLE: matte the already-bundled clean 8 frames
    # in place. In-app focus/confirm reuse the matted mood frames so the in-app pet and the
    # overlay never diverge in style.
    for n in range(1, 9):
        idle = "%s/mofei_in_app_idle_f%02d.png" % (DST, n)
        build_frame(idle, "mofei_in_app_idle_f%02d" % n)
        build_frame("output/mofei/focus/mofei_focus_f%02d.png" % n,
                    "mofei_in_app_focus_f%02d" % n)
        build_frame("output/mofei/confirm/mofei_confirm_f%02d.png" % n,
                    "mofei_in_app_confirm_f%02d" % n)
        written += 3
    print("in-app idle/focus/confirm done")
    print("TOTAL frames written:", written)


if __name__ == "__main__":
    main()
