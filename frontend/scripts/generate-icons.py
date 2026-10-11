#!/usr/bin/env python3
"""Regenerates the PWA / Android icons from the geometry of public/favicon.svg.

The favicon is two shapes (a pill and a ringed circle), so they are redrawn here
with Pillow instead of depending on an SVG rasteriser. Output is committed; run
this only when the favicon changes:   python3 scripts/generate-icons.py

  icon-192 / icon-512     glyph at 62% of the canvas on the brand teal
  icon-maskable-512       glyph at 52%, inside the Android adaptive-icon safe zone
  apple-touch-icon        180px for iOS home screens (iOS ignores manifest icons)
"""
from pathlib import Path
from PIL import Image, ImageDraw

# Pill is lighter than the favicon's so it stays visible on the teal tile.
TEAL, PILL, RING = "#0b6f62", "#7fe0cf", "#10202b"
OUT = Path(__file__).resolve().parent.parent / "public" / "icons"
SS = 4  # supersampling factor for smooth edges


def glyph(px: int) -> Image.Image:
    s = px * SS / 32  # favicon viewBox is 32 units
    img = Image.new("RGBA", (px * SS, px * SS), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 12 * s, 32 * s, 20 * s], radius=4 * s, fill=PILL)
    d.ellipse([4 * s, 4 * s, 28 * s, 28 * s], fill=RING)  # outer edge of the 5-unit stroke
    d.ellipse([7.5 * s, 7.5 * s, 24.5 * s, 24.5 * s], fill="#ffffff")
    return img.resize((px, px), Image.LANCZOS)


def render(size: int, pct: int, name: str) -> None:
    canvas = Image.new("RGBA", (size, size), TEAL)
    g = glyph(size * pct // 100)
    off = (size - g.width) // 2
    canvas.alpha_composite(g, (off, off))
    canvas.convert("RGB").save(OUT / name, optimize=True)


OUT.mkdir(parents=True, exist_ok=True)
render(192, 62, "icon-192.png")
render(512, 62, "icon-512.png")
render(512, 52, "icon-maskable-512.png")
render(180, 62, "apple-touch-icon.png")

# ---- Android launcher icons and splash screens (only if ../android exists) ----
RES = OUT.parent.parent.parent / "android" / "app" / "src" / "main" / "res"
if RES.is_dir():
    DENSITY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for d, legacy in DENSITY.items():
        fg = legacy * 108 // 48  # adaptive layers are 108dp, the legacy icon 48dp
        folder = RES / f"mipmap-{d}"
        # Adaptive foreground: transparent, glyph inside the 66% safe zone.
        layer = Image.new("RGBA", (fg, fg), (0, 0, 0, 0))
        g = glyph(fg * 50 // 100)
        layer.alpha_composite(g, ((fg - g.width) // 2, (fg - g.height) // 2))
        layer.save(folder / "ic_launcher_foreground.png", optimize=True)
        # Legacy square and round icons (pre-Android 8).
        sq = Image.new("RGBA", (legacy, legacy), TEAL)
        g = glyph(legacy * 62 // 100)
        sq.alpha_composite(g, ((legacy - g.width) // 2, (legacy - g.height) // 2))
        sq.convert("RGB").save(folder / "ic_launcher.png", optimize=True)
        mask = Image.new("L", (legacy * SS, legacy * SS), 0)
        ImageDraw.Draw(mask).ellipse([0, 0, legacy * SS - 1, legacy * SS - 1], fill=255)
        rd = sq.copy()
        rd.putalpha(mask.resize((legacy, legacy), Image.LANCZOS))
        rd.save(folder / "ic_launcher_round.png", optimize=True)
    (RES / "values" / "ic_launcher_background.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
        f'    <color name="ic_launcher_background">{TEAL}</color>\n</resources>\n'
    )
    for splash in RES.glob("drawable*/splash.png"):
        w, h = Image.open(splash).size
        rgba = Image.new("RGBA", (w, h), TEAL)
        g = glyph(min(w, h) * 22 // 100)
        rgba.alpha_composite(g, ((w - g.width) // 2, (h - g.height) // 2))
        rgba.convert("RGB").save(splash, optimize=True)
