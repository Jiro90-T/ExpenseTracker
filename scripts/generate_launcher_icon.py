"""
Generate the Expense Tracker launcher icon set.

Outputs:
  app/src/main/res/mipmap-mdpi/ic_launcher.png    (48x48)
  app/src/main/res/mipmap-mdpi/ic_launcher_round.png
  ... and the same for hdpi (72), xhdpi (96), xxhdpi (144), xxxhdpi (192)

Design:
  Solid vibrant-purple background (#6750A4 — the brand primary).
  Centered white "$" symbol in Arial Bold. For round variants, the
  image is the same (the launcher applies its own circular mask
  on Android 7.x via ic_launcher_round; on Android 8+ the adaptive
  icon layer handles it).
"""

import os
from PIL import Image, ImageDraw, ImageFont

BRAND_PURPLE = (0x67, 0x50, 0xA4, 0xFF)
WHITE = (0xFF, 0xFF, 0xFF, 0xFF)

DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

FONT_PATH = "C:/Windows/Fonts/arialbd.ttf"
RES_BASE = "F:/AndroidApp/ExpenseTracker/app/src/main/res"


def render_icon(size: int) -> Image.Image:
    """Render the icon (background + foreground) at the given pixel size."""
    img = Image.new("RGBA", (size, size), BRAND_PURPLE)
    draw = ImageDraw.Draw(img)

    # The "$" should be roughly 60% of the canvas height, vertically centered.
    # PIL's truetype uses font size in pixels.
    glyph_size = int(size * 0.62)
    font = ImageFont.truetype(FONT_PATH, glyph_size)

    text = "$"
    # Measure to center
    bbox = draw.textbbox((0, 0), text, font=font, anchor="lt")
    text_w = bbox[2] - bbox[0]
    text_h = bbox[3] - bbox[1]
    # Center horizontally, vertically
    x = (size - text_w) // 2 - bbox[0]
    y = (size - text_h) // 2 - bbox[1]

    draw.text((x, y), text, fill=WHITE, font=font)
    return img


def render_round(size: int) -> Image.Image:
    """Render the round variant — the same artwork on a fully-rounded alpha
    background so the system can mask it cleanly. (Android's launcher
    also applies its own circular crop; this just gives it a safe
    starting point.)"""
    square = render_icon(size)
    # Apply a circular alpha mask
    mask = Image.new("L", (size, size), 0)
    md = ImageDraw.Draw(mask)
    md.ellipse((0, 0, size - 1, size - 1), fill=255)
    result = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    result.paste(square, (0, 0), mask)
    return result


def main():
    for density, size in DENSITIES.items():
        out_dir = os.path.join(RES_BASE, f"mipmap-{density}")
        os.makedirs(out_dir, exist_ok=True)
        render_icon(size).save(os.path.join(out_dir, "ic_launcher.png"))
        render_round(size).save(os.path.join(out_dir, "ic_launcher_round.png"))
        print(f"  mipmap-{density}/: {size}x{size} ic_launcher.png + ic_launcher_round.png")
    print("done")


if __name__ == "__main__":
    main()
