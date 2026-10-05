"""
Dev-time: turn the Arabic word "آية" into Android vector-drawable path data.

A vector drawable cannot reference a font, so the glyphs must become outlines. Arabic also needs
real shaping — letters take different forms by position — so HarfBuzz does the layout and
fontTools extracts the outlines of the glyphs it actually chose. Renders a PNG alongside so the
result can be eyeballed before shipping: wrong contextual forms are the failure mode.
"""
import os

import uharfbuzz as hb
from fontTools.pens.basePen import BasePen
from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont

WORD = "آية"  # ayah
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONT = os.path.join(ROOT, "app", "src", "main", "res", "font", "scheherazade_new.ttf")

CANVAS = 108.0
# Fit by HEIGHT: "آية" is taller than it is wide once the madda and the teh marbuta's dots are
# counted, so scaling to a target width pushed those past the 66dp safe circle and a round
# launcher mask would clip them. 50dp leaves margin on the diagonal too.
TARGET_H = 50.0

with open(FONT, "rb") as fh:
    data = fh.read()
face = hb.Face(data)
hbfont = hb.Font(face)
buf = hb.Buffer()
buf.add_str(WORD)
buf.guess_segment_properties()
hb.shape(hbfont, buf)

tt = TTFont(FONT)
glyph_order = tt.getGlyphOrder()
glyph_set = tt.getGlyphSet()

print(f"word: {WORD}   direction: {buf.direction}   script: {buf.script}")
placed = []          # (glyph name, pen x, pen y)
x = y = 0
for info, pos in zip(buf.glyph_infos, buf.glyph_positions):
    name = glyph_order[info.codepoint]
    print(f"  {name:28s} adv={pos.x_advance:5d} off=({pos.x_offset},{pos.y_offset})")
    placed.append((name, x + pos.x_offset, y + pos.y_offset))
    x += pos.x_advance
    y += pos.y_advance
run_width = x

# Inked bounds of the whole run, for true optical centring.
xmin = ymin = 1e9
xmax = ymax = -1e9
for name, gx, gy in placed:
    bp = BoundsPen(glyph_set)
    glyph_set[name].draw(bp)
    if not bp.bounds:
        continue
    x0, y0, x1, y1 = bp.bounds
    xmin, ymin = min(xmin, x0 + gx), min(ymin, y0 + gy)
    xmax, ymax = max(xmax, x1 + gx), max(ymax, y1 + gy)

scale = TARGET_H / (ymax - ymin)
ox = CANVAS / 2 - (xmax + xmin) / 2 * scale
oy = CANVAS / 2 + (ymax + ymin) / 2 * scale   # +: canvas Y grows downward
print(f"\nrun={run_width}  ink={xmax - xmin:.0f}x{ymax - ymin:.0f}  scale={scale:.5f}")
print(f"rendered size: {(xmax - xmin) * scale:.1f} x {(ymax - ymin) * scale:.1f} dp")


def draw_transformed(name, gx, gy, pen):
    """Font Y grows up, canvas Y grows down — hence the negated yy."""
    glyph_set[name].draw(
        TransformPen(pen, (scale, 0, 0, -scale, ox + gx * scale, oy - gy * scale))
    )


svg_pen = SVGPathPen(glyph_set, ntos=lambda v: f"{v:.2f}")
for name, gx, gy in placed:
    draw_transformed(name, gx, gy, svg_pen)
path_data = svg_pen.getCommands()

with open("icon_path.txt", "w", encoding="utf-8") as fh:
    fh.write(path_data)
print(f"path data: {len(path_data)} chars")


# --- rasterise for a visual check ---------------------------------------
class MplPen(BasePen):
    """Collects the outline straight into matplotlib verts/codes — no SVG re-parsing."""

    def __init__(self, glyph_set):
        super().__init__(glyph_set)
        self.verts, self.codes = [], []

    def _moveTo(self, p):
        self.verts.append(p); self.codes.append(Path.MOVETO)

    def _lineTo(self, p):
        self.verts.append(p); self.codes.append(Path.LINETO)

    def _curveToOne(self, p1, p2, p3):
        self.verts += [p1, p2, p3]
        self.codes += [Path.CURVE4] * 3

    def _qCurveToOne(self, p1, p2):
        self.verts += [p1, p2]
        self.codes += [Path.CURVE3] * 2

    def _closePath(self):
        self.verts.append((0.0, 0.0)); self.codes.append(Path.CLOSEPOLY)


import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import PathPatch
from matplotlib.path import Path

mpl_pen = MplPen(glyph_set)
for name, gx, gy in placed:
    draw_transformed(name, gx, gy, mpl_pen)

fig, ax = plt.subplots(figsize=(4, 4), dpi=64)
ax.add_patch(plt.Rectangle((0, 0), CANVAS, CANVAS, color="#0A1710"))
ax.add_patch(
    PathPatch(Path(mpl_pen.verts, mpl_pen.codes), facecolor="#45D483", edgecolor="none")
)
# The circle launchers are guaranteed to keep; anything outside may be masked away.
ax.add_patch(plt.Circle((54, 54), 33, fill=False, edgecolor="#ffffff55", linestyle="--"))
ax.set_xlim(0, CANVAS); ax.set_ylim(CANVAS, 0); ax.set_aspect("equal"); ax.axis("off")
fig.savefig("icon_preview.png", bbox_inches="tight", pad_inches=0)
print("wrote icon_path.txt + icon_preview.png")
