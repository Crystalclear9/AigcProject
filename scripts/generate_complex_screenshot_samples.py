from __future__ import annotations

import argparse
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


WIDTH = 1260
HEIGHT = 2800


def font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont:
    candidates = [
        Path("C:/Windows/Fonts/msyhbd.ttc" if bold else "C:/Windows/Fonts/msyh.ttc"),
        Path("C:/Windows/Fonts/simhei.ttf"),
        Path("C:/Windows/Fonts/arial.ttf"),
    ]
    for candidate in candidates:
        if candidate.exists():
            return ImageFont.truetype(str(candidate), size=size)
    return ImageFont.load_default()


def wrap_text(text: str, max_chars: int) -> str:
    lines: list[str] = []
    for raw_line in text.splitlines():
        line = raw_line.strip()
        while len(line) > max_chars:
            lines.append(line[:max_chars])
            line = line[max_chars:]
        lines.append(line)
    return "\n".join(lines)


def draw_text(
    draw: ImageDraw.ImageDraw,
    xy: tuple[int, int],
    text: str,
    size: int = 38,
    fill: str = "#111827",
    bold: bool = False,
    max_chars: int = 28,
    spacing: int = 12,
) -> None:
    draw.multiline_text(
        xy,
        wrap_text(text, max_chars=max_chars),
        font=font(size, bold=bold),
        fill=fill,
        spacing=spacing,
    )


def round_rect(draw: ImageDraw.ImageDraw, xy: tuple[int, int, int, int], radius: int, fill: str) -> None:
    draw.rounded_rectangle(xy, radius=radius, fill=fill)


def canvas(background: str) -> tuple[Image.Image, ImageDraw.ImageDraw]:
    image = Image.new("RGB", (WIDTH, HEIGHT), background)
    return image, ImageDraw.Draw(image)


def status_bar(draw: ImageDraw.ImageDraw) -> None:
    draw_text(draw, (70, 48), "15:14        5G  WiFi  电量 62%", 32, "#111827", True, 34)


def bottom_tabs(draw: ImageDraw.ImageDraw) -> None:
    draw.rectangle((0, 2470, WIDTH, HEIGHT), fill="#FFFFFF")
    draw_text(draw, (120, 2580), "首页        消息        卡片        日历        我的", 36, "#6B7280", False, 40)


def save(image: Image.Image, output_dir: Path, name: str) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    path = output_dir / name
    image.save(path)
    print(f"generated {path}")


def course_notice(output_dir: Path) -> None:
    image, draw = canvas("#EAF2FF")
    status_bar(draw)
    round_rect(draw, (70, 210, 1190, 2510), 46, "#FFFFFF")
    round_rect(draw, (120, 310, 1140, 570), 44, "#3B82F6")
    draw_text(draw, (190, 365), "✨ 课程通知 ✨", 64, "#FFFFFF", True, 18)
    round_rect(draw, (140, 710, 1120, 1470), 36, "#F8FAFC")
    draw_text(draw, (190, 790), "请各位同学注意：", 52, "#1F2937", True, 18)
    draw_text(draw, (190, 910), "6 月 20 日 22：00 前", 72, "#EF4444", True, 18)
    draw_text(draw, (190, 1060), "提交《实验报告》", 66, "#111827", True, 18)
    draw_text(draw, (190, 1210), "提交至学习通，文件命名为：学号 + 姓名。", 44, "#374151", False, 22)
    draw_text(draw, (160, 1650), "老师提醒：逾期无法补交，请提前准备附件。", 42, "#475569", False, 24)
    bottom_tabs(draw)
    save(image, output_dir, "complex_course_notice.png")


def chat_promise(output_dir: Path) -> None:
    image, draw = canvas("#EEF2FF")
    status_bar(draw)
    draw_text(draw, (90, 160), "群聊 · 项目小组", 46, "#111827", True, 20)
    round_rect(draw, (80, 330, 980, 550), 36, "#FFFFFF")
    draw_text(draw, (130, 385), "A：明天下午三点前能把报名材料发给我吗？", 42, "#111827", False, 22)
    round_rect(draw, (300, 620, 1180, 870), 36, "#DCFCE7")
    draw_text(draw, (350, 680), "我来整理，明天晚上 8 点前发给你，记得提醒我。", 42, "#14532D", False, 22)
    round_rect(draw, (80, 940, 900, 1160), 36, "#FFFFFF")
    draw_text(draw, (130, 1000), "B：还需要团队信息表和作品说明书。", 42, "#111827", False, 22)
    bottom_tabs(draw)
    save(image, output_dir, "complex_chat_promise.png")


def competition_poster(output_dir: Path) -> None:
    image, draw = canvas("#160B3F")
    status_bar(draw)
    round_rect(draw, (80, 220, 1180, 2480), 64, "#26145F")
    draw_text(draw, (160, 390), "AIGC 创新赛", 88, "#FDE68A", True, 18)
    draw_text(draw, (160, 560), "报 名 通 道 已 开 启", 60, "#FFFFFF", True, 20)
    round_rect(draw, (150, 780, 1110, 1140), 48, "#FFFFFF")
    draw_text(draw, (210, 850), "D D L：2026.06.18 23:59", 56, "#DC2626", True, 24)
    draw_text(draw, (210, 960), "上传作品说明书、团队信息表", 42, "#111827", False, 24)
    draw_text(draw, (210, 1040), "点击官网链接提交，逾期系统关闭。", 42, "#111827", False, 24)
    draw_text(draw, (160, 1320), "主办方：学院创新中心", 38, "#C4B5FD", False, 24)
    bottom_tabs(draw)
    save(image, output_dir, "complex_competition_poster.png")


def meeting_poster(output_dir: Path) -> None:
    image, draw = canvas("#FFF7ED")
    status_bar(draw)
    round_rect(draw, (90, 250, 1170, 2310), 54, "#FFFFFF")
    draw_text(draw, (160, 390), "团队周会安排", 76, "#7C2D12", True, 18)
    draw_text(draw, (160, 560), "周五 14:30  腾讯会议", 58, "#EA580C", True, 22)
    draw_text(draw, (160, 720), "请参加会议并准备本周进展汇报 PPT。", 46, "#111827", False, 23)
    draw_text(draw, (160, 860), "会议号：886 210 552", 42, "#475569", False, 24)
    draw_text(draw, (160, 980), "需要提前 10 分钟签到。", 42, "#475569", False, 24)
    bottom_tabs(draw)
    save(image, output_dir, "complex_meeting_poster.png")


def multi_tasks_notice(output_dir: Path) -> None:
    image, draw = canvas("#ECFEFF")
    status_bar(draw)
    round_rect(draw, (80, 210, 1180, 2440), 54, "#FFFFFF")
    draw_text(draw, (150, 330), "课程群公告", 72, "#0F172A", True, 16)
    round_rect(draw, (140, 520, 1120, 980), 42, "#EFF6FF")
    draw_text(draw, (190, 580), "① 请在 6 月 20 日 22:00 前", 48, "#1D4ED8", True, 22)
    draw_text(draw, (190, 680), "提交《实验报告》到学习通，文件名：学号+姓名。", 42, "#111827", False, 23)
    round_rect(draw, (140, 1080, 1120, 1540), 42, "#FFF7ED")
    draw_text(draw, (190, 1140), "② 周五 14:30 参加腾讯会议", 48, "#C2410C", True, 22)
    draw_text(draw, (190, 1240), "并准备本周进展汇报 PPT，会议号 886 210 552。", 42, "#111827", False, 23)
    round_rect(draw, (140, 1640, 1120, 2040), 42, "#F0FDF4")
    draw_text(draw, (190, 1700), "③ 报名表下周一前发到指定邮箱，逾期不补。", 44, "#166534", True, 22)
    draw_text(draw, (190, 1840), "广告：618 文具满减与本通知无关。", 38, "#6B7280", False, 24)
    bottom_tabs(draw)
    save(image, output_dir, "complex_multi_tasks.png")


def shopping_noise(output_dir: Path) -> None:
    image, draw = canvas("#FFF1F2")
    status_bar(draw)
    draw_text(draw, (120, 300), "618 限时秒杀", 86, "#BE123C", True, 18)
    draw_text(draw, (120, 480), "明晚 20:00 截止！优惠券满 300 减 80", 54, "#E11D48", True, 22)
    draw_text(draw, (120, 660), "加入购物车，下单抽奖，直播间还有红包。", 42, "#7F1D1D", False, 24)
    round_rect(draw, (140, 960, 1120, 1480), 44, "#FFFFFF")
    round_rect(draw, (320, 1120, 930, 1300), 34, "#E11D48")
    draw_text(draw, (445, 1170), "立即抢购", 68, "#FFFFFF", True, 12)
    bottom_tabs(draw)
    save(image, output_dir, "noise_shopping_promo.png")


def status_only(output_dir: Path) -> None:
    image, draw = canvas("#F3F4F6")
    status_bar(draw)
    draw_text(draw, (110, 2500), "今日        导入        卡片        日历        设置", 44, "#6B7280", False, 40)
    save(image, output_dir, "noise_status_only.png")


def own_app(output_dir: Path) -> None:
    image, draw = canvas("#F1F5FF")
    status_bar(draw)
    draw_text(draw, (120, 220), "随手办", 82, "#111827", True, 16)
    draw_text(draw, (120, 420), "设置中心", 54, "#111827", True, 18)
    round_rect(draw, (80, 580, 1180, 1580), 50, "#FFFFFF")
    draw_text(draw, (150, 680), "云端增强（可选）", 54, "#2563EB", True, 18)
    draw_text(draw, (150, 820), "Workflow API URL，可留空", 42, "#6B7280", False, 24)
    draw_text(draw, (150, 960), "提醒策略：高优先级 3 天 / 1 天 / 3 小时 / 30 分钟", 42, "#111827", False, 25)
    draw_text(draw, (150, 1100), "已创建提醒 0", 42, "#111827", False, 24)
    bottom_tabs(draw)
    save(image, output_dir, "noise_own_app_settings.png")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", required=True)
    args = parser.parse_args()
    output_dir = Path(args.output_dir)

    course_notice(output_dir)
    chat_promise(output_dir)
    competition_poster(output_dir)
    meeting_poster(output_dir)
    multi_tasks_notice(output_dir)
    shopping_noise(output_dir)
    status_only(output_dir)
    own_app(output_dir)


if __name__ == "__main__":
    main()
