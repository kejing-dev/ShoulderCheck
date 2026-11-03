"""Shoulder Check icon (design `appIcon`, taste D2). One set of shapes drawn two ways so they always match: the
adaptive-icon foreground vector and the raster PNGs (legacy launcher + 512 export for the factory gallery).

    python -X utf8 tools/make_icon.py [export-512.png]
    (default export: C:/Users/Admin/Downloads/spatial-app-factory/apps/shoulder-check/icon.png; needs Pillow)

Road-shoulder olive tile #5E7A1C; a white side-mirror outline (rounded trapezoid, leaning a little to the right, the
inner edge shorter than the outer one like a door mirror); inside it an orange #FF8A3D cyclist in profile; outside it, upper left, a white
arrow curving backward (look over your shoulder). No text, no gradient. Coordinates are the vector's 108-unit
viewport (safe zone: circle of radius 33 around 54,54).
Writes: res/drawable/ic_launcher_foreground.xml, res/values/ic_launcher_background.xml,
        res/mipmap-anydpi/ic_launcher.xml + ic_launcher_round.xml, res/mipmap-xxxhdpi/ic_launcher(_round).png
"""
import math
import os
import sys

from PIL import Image, ImageDraw

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
RES = os.path.join(ROOT, "app", "src", "main", "res")
EXPORT = r"C:\Users\Admin\Downloads\spatial-app-factory\apps\shoulder-check\icon.png"
BG = "#5E7A1C"
WHITE = "#FFFFFF"
ORANGE = "#FF8A3D"


def rgba(hexs):
    return tuple(int(hexs[i:i + 2], 16) for i in (1, 3, 5)) + (255,)


# ------------------------------------------------------------------ geometry (108 viewport)
MIRROR_C = (61.0, 59.0)
MIRROR_TILT = 7.0  # degrees, clockwise on screen = top leans right


def rot(pts, c, deg):
    a = math.radians(deg)
    ca, sa = math.cos(a), math.sin(a)
    return [(c[0] + (x - c[0]) * ca - (y - c[1]) * sa, c[1] + (x - c[0]) * sa + (y - c[1]) * ca) for x, y in pts]


def rounded_poly(corners, r, n=10):
    """Closed polygon with each corner replaced by a smooth curve (quadratic, tangent to both edges)."""
    out = []
    k = len(corners)
    for i in range(k):
        p0, p1, p2 = corners[i - 1], corners[i], corners[(i + 1) % k]
        v1 = ((p0[0] - p1[0]), (p0[1] - p1[1]))
        v2 = ((p2[0] - p1[0]), (p2[1] - p1[1]))
        l1, l2 = math.hypot(*v1), math.hypot(*v2)
        u1, u2 = (v1[0] / l1, v1[1] / l1), (v2[0] / l2, v2[1] / l2)
        dd = min(r, l1 * 0.45, l2 * 0.45)
        a = (p1[0] + u1[0] * dd, p1[1] + u1[1] * dd)
        b = (p1[0] + u2[0] * dd, p1[1] + u2[1] * dd)
        for j in range(n + 1):
            t = j / n
            x = (1 - t) ** 2 * a[0] + 2 * (1 - t) * t * p1[0] + t * t * b[0]
            y = (1 - t) ** 2 * a[1] + 2 * (1 - t) * t * p1[1] + t * t * b[1]
            out.append((x, y))
    return out


def mirror_outline():
    """Car door-mirror glass: inner (left, toward the car) edge shorter than the outer edge, top sloping up outward."""
    cx, cy = MIRROR_C
    w = 46.0
    hl, hr = 25.0, 35.0  # left / right edge heights
    bottom = cy + 14.0
    corners = [(cx - w / 2, bottom - hl), (cx + w / 2, bottom - hr), (cx + w / 2, bottom), (cx - w / 2 + 3, bottom)]
    return rot(rounded_poly(corners, 10.0), MIRROR_C, MIRROR_TILT)


# cyclist in profile, riding to the left, local units; placed upright in the mirror glass
CYC_C = (64.5, 60.0)
CYC_S = 0.82


def cyc(pts):
    return [(CYC_C[0] + x * CYC_S, CYC_C[1] + y * CYC_S) for x, y in pts]


WHEEL_R = 5.8
REAR = (8.8, 7.0)
FRONT = (-8.8, 7.0)


def cyclist():
    """Returns (strokes [(pts, width)], filled circles [(c, r)], rings [(c, r, width)]) in viewport units."""
    s = CYC_S
    rings = [(cyc([REAR])[0], WHEEL_R * s, 1.9 * s), (cyc([FRONT])[0], WHEEL_R * s, 1.9 * s)]
    crank = (0.8, 6.8)
    seat = (3.6, -1.2)
    bar = (-5.6, -2.4)
    strokes = [
        (cyc([REAR, crank, seat, REAR]), 1.7 * s),        # chain stay + seat tube + seat stay
        (cyc([crank, bar]), 1.7 * s),                     # down tube
        (cyc([seat, (-4.6, -1.6)]), 1.5 * s),             # top tube
        (cyc([bar, FRONT]), 1.7 * s),                     # fork
        (cyc([(4.4, -2.6), (-1.6, -10.4)]), 4.4 * s),     # torso (hip -> shoulder), leaning forward
        (cyc([(-1.6, -10.0), (-4.6, -5.4), (-6.2, -3.2)]), 2.2 * s),  # arm to the bars
        (cyc([(4.4, -2.2), (-1.0, 0.6), (0.4, 7.0)]), 2.6 * s),       # leg: hip -> knee -> pedal
    ]
    circles = [(cyc([(-4.2, -13.6)])[0], 2.7 * s)]       # head
    return strokes, circles, rings


def arrow():
    """White arrow curving backward (over the shoulder), upper left: arc + arrowhead. Returns (arc pts, head tri)."""
    c, r = (32.5, 40.0), 9.0
    a0, a1 = -10.0, -205.0  # screen degrees (y down): from right, over the top, round to lower left
    n = 28
    pts = [(c[0] + r * math.cos(math.radians(a0 + (a1 - a0) * i / n)), c[1] + r * math.sin(math.radians(a0 + (a1 - a0) * i / n)))
           for i in range(n + 1)]
    end = pts[-1]
    # tangent at the end (direction of travel)
    tx, ty = pts[-1][0] - pts[-2][0], pts[-1][1] - pts[-2][1]
    tl = math.hypot(tx, ty)
    tx, ty = tx / tl, ty / tl
    nx, ny = -ty, tx
    L, W = 7.0, 5.4
    tip = (end[0] + tx * L * 0.75, end[1] + ty * L * 0.75)
    base = (end[0] - tx * L * 0.25, end[1] - ty * L * 0.25)
    head = [tip, (base[0] + nx * W, base[1] + ny * W), (base[0] - nx * W, base[1] - ny * W)]
    return pts[:-1], head


MIRROR_W = 4.2
ARROW_W = 4.0


# ------------------------------------------------------------------ vector
def fmt(v):
    return f"{v:.2f}".rstrip("0").rstrip(".")


def poly_d(pts, close=False):
    return "M" + "L".join(f"{fmt(x)},{fmt(y)}" for x, y in pts) + ("Z" if close else "")


def circle_d(c, r):
    cx, cy = c
    return f"M{fmt(cx - r)},{fmt(cy)}a{fmt(r)},{fmt(r)} 0,1 1,{fmt(2 * r)},0a{fmt(r)},{fmt(r)} 0,1 1,{fmt(-2 * r)},0z"


def stroke_path(d, color, w):
    return (f'    <path\n        android:strokeColor="#FF{color[1:]}"\n        android:strokeWidth="{fmt(w)}"\n'
            f'        android:strokeLineCap="round"\n        android:strokeLineJoin="round"\n        android:pathData="{d}" />\n')


def fill_path(d, color):
    return f'    <path\n        android:fillColor="#FF{color[1:]}"\n        android:pathData="{d}" />\n'


def vector():
    out = ['<?xml version="1.0" encoding="utf-8"?>\n',
           "<!-- Shoulder Check: white side-mirror outline, orange cyclist inside, white backward arrow "
           "(design appIcon); generated by tools/make_icon.py -->\n",
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
           '    android:width="108dp"\n    android:height="108dp"\n'
           '    android:viewportWidth="108"\n    android:viewportHeight="108">\n']
    out.append(stroke_path(poly_d(mirror_outline(), close=True), WHITE, MIRROR_W))
    strokes, circles, rings = cyclist()
    for c, r, w in rings:
        out.append(stroke_path(circle_d(c, r), ORANGE, w))
    for pts, w in strokes:
        out.append(stroke_path(poly_d(pts), ORANGE, w))
    for c, r in circles:
        out.append(fill_path(circle_d(c, r), ORANGE))
    arc, head = arrow()
    out.append(stroke_path(poly_d(arc), WHITE, ARROW_W))
    out.append(fill_path(poly_d(head, close=True), WHITE))
    out.append("</vector>\n")
    return "".join(out)


# ------------------------------------------------------------------ raster
def raster(size, shape):
    k = 8
    S = size * k
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    if shape == "rounded":
        d.rounded_rectangle([0, 0, S - 1, S - 1], radius=int(S * 0.22), fill=rgba(BG))
    elif shape == "round":
        d.ellipse([0, 0, S - 1, S - 1], fill=rgba(BG))
    else:
        d.rectangle([0, 0, S, S], fill=rgba(BG))
    u = S / 108.0

    def line(pts, w, col, close=False):
        P = [(x * u, y * u) for x, y in pts]
        if close:
            P = P + [P[0]]
        rr = w * u / 2
        for a, b in zip(P, P[1:]):
            d.line([a, b], fill=col, width=max(1, int(round(w * u))))
        for x, y in P:
            d.ellipse([x - rr, y - rr, x + rr, y + rr], fill=col)

    white, orange = rgba(WHITE), rgba(ORANGE)
    line(mirror_outline(), MIRROR_W, white, close=True)
    strokes, circles, rings = cyclist()
    for (cx, cy), r, w in rings:
        ro, ri = (r + w / 2) * u, (r - w / 2) * u
        ring = Image.new("L", (S, S), 0)
        rd = ImageDraw.Draw(ring)
        rd.ellipse([cx * u - ro, cy * u - ro, cx * u + ro, cy * u + ro], fill=255)
        rd.ellipse([cx * u - ri, cy * u - ri, cx * u + ri, cy * u + ri], fill=0)
        img.paste(orange, (0, 0), ring)
    d = ImageDraw.Draw(img)
    for pts, w in strokes:
        line(pts, w, orange)
    for (cx, cy), r in circles:
        d.ellipse([(cx - r) * u, (cy - r) * u, (cx + r) * u, (cy + r) * u], fill=orange)
    arc, head = arrow()
    line(arc, ARROW_W, white)
    d.polygon([(x * u, y * u) for x, y in head], fill=white)
    return img.resize((size, size), Image.LANCZOS)


ADAPTIVE = ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <background android:drawable="@color/ic_launcher_background" />\n'
            '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
            '    <monochrome android:drawable="@drawable/ic_launcher_foreground" />\n'
            '</adaptive-icon>\n')


def main():
    export = sys.argv[1] if len(sys.argv) > 1 else EXPORT
    for sub in ("drawable", "values", "mipmap-anydpi", "mipmap-xxxhdpi"):
        os.makedirs(os.path.join(RES, sub), exist_ok=True)
    with open(os.path.join(RES, "drawable", "ic_launcher_foreground.xml"), "w", encoding="utf-8", newline="\n") as f:
        f.write(vector())
    with open(os.path.join(RES, "values", "ic_launcher_background.xml"), "w", encoding="utf-8", newline="\n") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
                f'    <color name="ic_launcher_background">{BG}</color>\n</resources>\n')
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        with open(os.path.join(RES, "mipmap-anydpi", name), "w", encoding="utf-8", newline="\n") as f:
            f.write(ADAPTIVE)
    raster(192, "square").save(os.path.join(RES, "mipmap-xxxhdpi", "ic_launcher.png"))
    raster(192, "round").save(os.path.join(RES, "mipmap-xxxhdpi", "ic_launcher_round.png"))
    os.makedirs(os.path.dirname(export), exist_ok=True)
    raster(512, "rounded").save(export)
    print("wrote", export)


if __name__ == "__main__":
    main()
