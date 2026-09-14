#!/usr/bin/env python3
"""渲染 TextNote 图标的各密度 PNG 与预览板（与矢量自适应图标同一设计）。

设计：深青绿底 + 三行白色文本 + 一个薄荷色插入符。

坐标与 `app/src/main/res/drawable/ic_launcher_foreground.xml` 是同一套（108 视口），
**改一处必须同步改另一处**，否则 PNG 与矢量图标会长得不一样。

用法：
    /Users/blz/.workbuddy/binaries/python/envs/default/bin/python tools/render_icon.py
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app" / "src" / "main" / "res"
ART = ROOT / ".workbuddy" / "artifacts"

BG = (18, 101, 90, 255)         # #12655A
WHITE = (255, 255, 255, 255)
MINT = (127, 233, 212, 255)     # #7FE9D4

# 108 视口里的坐标，与 ic_launcher_foreground.xml 一一对应
LINES = [((26, 40), (72, 40)), ((26, 54), (56, 54)), ((26, 68), (64, 68))]
CARET = ((78, 62), (78, 76))
STROKE = 8.0


def draw_glyph(draw: ImageDraw.ImageDraw, scale: float,
               line_color=WHITE, caret_color=MINT) -> None:
    w = STROKE * scale
    r = w / 2

    def stroke(points, color):
        pts = [(x * scale, y * scale) for x, y in points]
        draw.line(pts, fill=color, width=int(round(w)))
        for x, y in pts:            # 圆头，对应 vector 的 strokeLineCap="round"
            draw.ellipse([x - r, y - r, x + r, y + r], fill=color)

    for line in LINES:
        stroke(line, line_color)
    stroke(CARET, caret_color)


def render_icon(size: int) -> Image.Image:
    ss = 4                          # 超采样抗锯齿
    img = Image.new("RGBA", (size * ss, size * ss), BG)
    draw_glyph(ImageDraw.Draw(img), size * ss / 108)
    return img.resize((size, size), Image.LANCZOS)


def rounded(img: Image.Image, radius_ratio: float) -> Image.Image:
    """套圆角（或圆形）蒙版，radius_ratio=0.5 即圆形。"""
    size = img.width
    ss = 4
    mask = Image.new("L", (size * ss, size * ss), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, size * ss, size * ss], radius=size * ss * radius_ratio, fill=255)
    mask = mask.resize((size, size), Image.LANCZOS)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    return out


def main() -> None:
    for dpi, size in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96),
                      ("xxhdpi", 144), ("xxxhdpi", 192)]:
        out = RES / f"mipmap-{dpi}" / "ic_launcher.png"
        render_icon(size).save(out)
        print("written", out.relative_to(ROOT))

    # 预览板：启动器上三种遮罩下的样子
    ART.mkdir(parents=True, exist_ok=True)
    width, height, size = 1200, 520, 300
    board = Image.new("RGBA", (width, height), (245, 246, 250, 255))
    draw = ImageDraw.Draw(board)

    squircle = rounded(render_icon(size), 0.22)
    circle = rounded(render_icon(size), 0.5)
    mono = Image.new("RGBA", (size * 4, size * 4), (197, 202, 233, 255))
    draw_glyph(ImageDraw.Draw(mono), size * 4 / 108,
               line_color=(38, 50, 56, 255), caret_color=(38, 50, 56, 255))
    themed = rounded(mono.resize((size, size), Image.LANCZOS), 0.5)

    for x, icon in zip([120, 450, 780], [squircle, circle, themed]):
        board.alpha_composite(icon, (x, 80))

    try:
        font = ImageFont.truetype("/System/Library/Fonts/Helvetica.ttc", 30)
        title = ImageFont.truetype("/System/Library/Fonts/Helvetica.ttc", 46)
    except OSError:
        font = title = ImageFont.load_default()

    draw.text((width // 2, 40), "TextNote", font=title, fill=(30, 33, 45, 255), anchor="mm")
    for x, label in zip([120, 450, 780],
                        ["Adaptive (squircle)", "Round", "Themed (monochrome)"]):
        draw.text((x + size // 2, 410), label, font=font, fill=(90, 95, 110, 255), anchor="mm")
    draw.text((width // 2, 470), "#12655A  ·  three lines + caret", font=font,
              fill=(120, 125, 140, 255), anchor="mm")

    out = ART / "icon_preview.png"
    board.convert("RGB").save(out)
    print("written", out.relative_to(ROOT))


if __name__ == "__main__":
    main()
