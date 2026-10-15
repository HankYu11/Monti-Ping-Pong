#!/usr/bin/env python3
"""Draw the app icons (a marked ball on table blue) -> app/icon-*.png. Run once; PNGs are committed."""
import os
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
BLUE, WHITE, BLACK, ORANGE = (27, 76, 134), (244, 244, 241), (18, 18, 18), (226, 112, 31)


def icon(size, ball_frac):
    S = 4 * size                                          # draw large, shrink for smooth edges
    im = Image.new("RGB", (S, S), BLUE)
    d = ImageDraw.Draw(im)
    r = S * ball_frac / 2
    c = S / 2
    box = [c - r, c - r, c + r, c + r]
    # spin arc behind the ball
    a = r * 1.22
    d.arc([c - a, c - a, c + a, c + a], start=200, end=320, fill=ORANGE, width=int(S * 0.035))
    d.ellipse(box, fill=WHITE)
    d.pieslice(box, start=180, end=360, fill=BLACK)       # top half black
    lw = r * 0.16
    d.rectangle([c - lw / 2, c, c + lw / 2, c + r * 0.98], fill=BLACK)   # the line over the white half
    dr = r * 0.14
    dx, dy = c - r * 0.45, c + r * 0.45
    d.ellipse([dx - dr, dy - dr, dx + dr, dy + dr], fill=BLACK)          # the dot
    return im.resize((size, size), Image.LANCZOS)


for name, size, frac in [("icon-192.png", 192, 0.66), ("icon-512.png", 512, 0.66),
                         ("icon-maskable-512.png", 512, 0.5), ("apple-touch-icon.png", 180, 0.62)]:
    icon(size, frac).save(os.path.join(HERE, name), optimize=True)
    print(name)
