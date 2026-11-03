"""shoulder-check asset pipeline, step 1b: finish the decimated GLBs and measure the D25 calibration.

Input : <raw>/<role>.raw.glb from tools/decimate_models.mjs (geometry decimated, 1k textures).
Output: app/src/main/assets/models/<role>.glb   (textures <= 512 px JPEG, every material OPAQUE, no extensions)
        app/src/main/assets/models/calibration.json
        <factory>/apps/shoulder-check/build/models-calibrated.png (front / side / top contact sheet, calibrated)

Run:  python -X utf8 tools/process_models.py <rawDir>      (needs numpy + Pillow)

Geometry is NOT changed here (no calibration baked in). The app applies, on a parent "calibration" node,
TRS = translate(t) * rotateY(yawDeg) * scale(s)   i.e.  p' = t + Ry(yaw) (s p)
so the vehicle's bottom-centre lands at the origin, its front points to -Z and its length matches the design.
Ry(+yaw) is the right-handed rotation about +Y (counter-clockwise seen from above): +X -> -Z for yaw = +90.
"""
import io
import json
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from glbio import read_glb  # noqa: E402
from softrender import render  # noqa: E402

PROJECT = os.path.dirname(HERE)
OUT_DIR = os.path.join(PROJECT, "app", "src", "main", "assets", "models")
FACTORY_APP = r"C:\Users\Admin\Downloads\spatial-app-factory\apps\shoulder-check"
SHEET = os.path.join(FACTORY_APP, "build", "models-calibrated.png")
TEX_MAX = 512

SOURCES = {"sedan": "sc_car_silver.glb", "suv": "sc_suv_red.glb", "ebike": "sc_ebike_delivery.glb",
           "bike": "sc_bike_navy.glb", "moto": "sc_moto_blue.glb"}
# design sizes (m): entities3d / gateBDecisions.models
DESIGN = {"sedan": {"L": 4.6, "W": 1.8, "H": 1.45}, "suv": {"L": 4.7, "W": 1.9, "H": 1.7},
          "ebike": {"L": 1.7, "H": 1.7}, "bike": {"L": 1.75, "H": 1.75}, "moto": {"L": 2.0, "H": 1.6}}
# Forward axis in model space, determined by rendering top / side / front views of each raw model and looking
# (2026-10-30): sedan grille + headlights face +Z; SUV headlights at -X (tail lights seen from +X);
# e-bike and bicycle riders' faces / handlebars toward +X; motorcycle rider's back + tail light seen from +X.
FORWARD = {"sedan": "+Z", "suv": "-X", "ebike": "+X", "bike": "+X", "moto": "-X"}
YAW_FOR = {"+Z": 180.0, "-Z": 0.0, "+X": 90.0, "-X": -90.0}  # yaw that turns that axis to -Z

# Texture blanking (D25: 车头标和车牌框贴图涂掉). Boxes in raw model units, measured on 1600 px/m renders of the
# raw front / rear with a grid: lateral = |offset from bbox centre| on the horizontal axis normal to forward,
# y = height, end = "front"/"rear" face, depth = how far behind that face the surface may be.
BLANK = {
    "sedan": [
        {"what": "front logo", "end": "front", "lat": 0.026, "y": (0.146, 0.182), "depth": 0.08},
        {"what": "front plate", "end": "front", "lat": 0.058, "y": (0.097, 0.132), "depth": 0.08},
        {"what": "rear logo", "end": "rear", "lat": 0.019, "y": (0.224, 0.249), "depth": 0.10},
        {"what": "rear plate", "end": "rear", "lat": 0.058, "y": (0.177, 0.214), "depth": 0.10},
    ],
    "suv": [
        {"what": "front logo", "end": "front", "lat": 0.019, "y": (0.155, 0.189), "depth": 0.08},
        {"what": "rear badge", "end": "rear", "lat": 0.016, "y": (0.219, 0.231), "depth": 0.10},
        {"what": "rear plate", "end": "rear", "lat": 0.060, "y": (0.184, 0.216), "depth": 0.10},
    ],
}

AXIS = {"X": 0, "Y": 1, "Z": 2}


def ry(deg):
    a = np.radians(deg)
    c, s = np.cos(a), np.sin(a)
    return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])


def calibrate(V, role):
    fwd = FORWARD[role]
    fi = AXIS[fwd[1]]
    li = 2 if fi == 0 else 0
    vmin, vmax = V.min(0), V.max(0)
    ext = vmax - vmin
    L, Wd, H = ext[fi], ext[li], ext[1]
    d = DESIGN[role]
    s = d["L"] / L
    yaw = YAW_FOR[fwd]
    bc = np.array([(vmin[0] + vmax[0]) / 2, vmin[1], (vmin[2] + vmax[2]) / 2])
    t = -(ry(yaw) @ (s * bc))
    sizes = {"L": s * L, "W": s * Wd, "H": s * H}
    err = {k: (sizes[k] - d[k]) / d[k] for k in d}
    extra = {}
    if "W" in d:
        # body width without the door mirrors: lateral extent of everything below 55 % of the height
        low = V[V[:, 1] < vmin[1] + 0.55 * H]
        wb = low[:, li].max() - low[:, li].min()
        sizes["Wbody"] = s * wb
        err["Wbody"] = (s * wb - d["W"]) / d["W"]
        extra["note"] = "W includes the door mirrors; Wbody = widest point below 55 % height (no mirrors)"
    # optional alternative: uniform scale that balances the log-errors of L, (Wbody), H
    keys = [("L", L), ("H", H)] + ([("W", sizes["Wbody"] / s)] if "W" in d else [])
    sb = float(np.exp(np.mean([np.log(d[k] / v) for k, v in keys])))
    extra["scaleBalanced"] = sb
    extra["sizeErrorIfBalanced"] = {k: (sb * v - d[k]) / d[k] for k, v in keys}
    return {"forwardAxis": fwd, "yawDeg": yaw, "scale": s, "translation": t.tolist(),
            "bboxMin": vmin.tolist(), "bboxMax": vmax.tolist(),
            "modelSize": {"L": L, "W": Wd, "H": H}, "sizeAfterM": sizes, "designM": d,
            "sizeError": err, **extra}


def apply_cal(V, cal):
    return (ry(cal["yawDeg"]) @ (cal["scale"] * V.T)).T + np.array(cal["translation"])


def decode(g, tex_index):
    return Image.open(io.BytesIO(g.image_bytes(g.json["textures"][tex_index]["source"])))


def blank_regions(V, F, UV, role, base, normal):
    """Paint the logo / plate regions in base (and flatten them in the normal map). Returns notes."""
    fi = AXIS[FORWARD[role][1]]
    sign = 1 if FORWARD[role][0] == "+" else -1
    li = 2 if fi == 0 else 0
    f_coord = V[:, fi] * sign  # larger = more to the front
    fmax, fmin = f_coord.max(), f_coord.min()
    lat_c = (V[:, li].min() + V[:, li].max()) / 2
    B = np.asarray(base, dtype=np.float64)
    Nm = np.asarray(normal, dtype=np.float64) if normal is not None else None
    th, tw = B.shape[:2]
    a, b, c = V[F[:, 0]], V[F[:, 1]], V[F[:, 2]]
    fn = np.cross(b - a, c - a)
    fn /= np.maximum(np.linalg.norm(fn, axis=1, keepdims=True), 1e-12)
    notes = []
    for box in BLANK.get(role, []):
        front = box["end"] == "front"
        face_dir = 1 if front else -1
        lat_lim, (y0, y1), depth = box["lat"], box["y"], box["depth"]

        def inside(P, grow=0.0):
            lat = np.abs(P[..., li] - lat_c)
            fc = P[..., fi] * sign
            okf = (fc > fmax - depth) if front else (fc < fmin + depth)
            return (lat <= lat_lim + grow) & (P[..., 1] >= y0 - grow) & (P[..., 1] <= y1 + grow) & okf

        # candidate faces: facing that end, any vertex or centroid near the box
        facing = fn[:, fi] * sign * face_dir > 0.35
        near = inside(a, 0.03) | inside(b, 0.03) | inside(c, 0.03) | inside((a + b + c) / 3, 0.03)
        cand = np.where(facing & near)[0]
        inner_px, ring_px = [], []
        for t in cand:
            uv = UV[F[t]] * [tw, th]
            xa, xb = int(max(np.floor(uv[:, 0].min()) - 1, 0)), int(min(np.ceil(uv[:, 0].max()) + 1, tw - 1))
            ya, yb = int(max(np.floor(uv[:, 1].min()) - 1, 0)), int(min(np.ceil(uv[:, 1].max()) + 1, th - 1))
            if xb < xa or yb < ya:
                continue
            gx, gy = np.meshgrid(np.arange(xa, xb + 1) + 0.5, np.arange(ya, yb + 1) + 0.5)
            (x0_, y0_), (x1_, y1_), (x2_, y2_) = uv
            den = (y1_ - y2_) * (x0_ - x2_) + (x2_ - x1_) * (y0_ - y2_)
            if abs(den) < 1e-9:
                continue
            w0 = ((y1_ - y2_) * (gx - x2_) + (x2_ - x1_) * (gy - y2_)) / den
            w1 = ((y2_ - y0_) * (gx - x2_) + (x0_ - x2_) * (gy - y2_)) / den
            w2 = 1 - w0 - w1
            m = (w0 >= -0.02) & (w1 >= -0.02) & (w2 >= -0.02)
            P = w0[..., None] * a[t] + w1[..., None] * b[t] + w2[..., None] * c[t]
            ins = m & inside(P)
            ring = m & inside(P, 0.012) & ~ins
            iy, ix = np.nonzero(ins)
            inner_px.append(np.stack([iy + ya, ix + xa], 1))
            ry_, rx_ = np.nonzero(ring)
            ring_px.append(np.stack([ry_ + ya, rx_ + xa], 1))
        inner = np.unique(np.concatenate(inner_px), axis=0) if inner_px else np.zeros((0, 2), int)
        ringp = np.unique(np.concatenate(ring_px), axis=0) if ring_px else np.zeros((0, 2), int)
        if len(inner) == 0 or len(ringp) < 10:
            notes.append(f"{role} {box['what']}: no texels found, skipped")
            continue
        fill = np.median(B[ringp[:, 0], ringp[:, 1]], axis=0)
        B[inner[:, 0], inner[:, 1]] = fill
        if Nm is not None:
            Nm[inner[:, 0], inner[:, 1]] = [128, 128, 255]
        notes.append(f"{role} {box['what']}: {len(inner)} texels -> rgb{tuple(int(v) for v in fill)}")
    out_n = Image.fromarray(np.clip(Nm, 0, 255).astype(np.uint8)) if Nm is not None else None
    return Image.fromarray(np.clip(B, 0, 255).astype(np.uint8)), out_n, notes


def to_jpeg(img, q):
    img = img.convert("RGB")
    if max(img.size) > TEX_MAX:
        img = img.resize((TEX_MAX, TEX_MAX * img.size[1] // img.size[0]), Image.LANCZOS)
    bio = io.BytesIO()
    img.save(bio, "JPEG", quality=q, optimize=True)
    return bio.getvalue(), img.size


def font(sz):
    for f in ("C:/Windows/Fonts/segoeui.ttf", "C:/Windows/Fonts/arial.ttf"):
        if os.path.exists(f):
            return ImageFont.truetype(f, sz)
    return ImageFont.load_default()


def main(raw_dir):
    os.makedirs(OUT_DIR, exist_ok=True)
    os.makedirs(os.path.dirname(SHEET), exist_ok=True)
    dec = json.load(open(os.path.join(raw_dir, "decimate-report.json"), encoding="utf8"))
    calib = {"_doc": ("Per role (use scale; scaleBalanced is only an alternative that spreads the size error over L/W/H): apply on a parent calibration node TRS = translate(translation) * rotateY(yawDeg) * "
                      "uniform scale(scale), i.e. p' = t + Ry(yaw)(s p). Ry(+yaw) is right-handed about +Y "
                      "(seen from above counter-clockwise; +X -> -Z at +90). Result: bottom-centre at the origin, "
                      "front / handlebars toward -Z, +Y up, length = design length. bbox in model units "
                      "(scene space of the GLB, node transforms included). sizeError = (calibrated - design)/design."),
             "units": "model units for bbox, metres after calibration", "generatedBy": "tools/process_models.py"}
    sheet_rows = []
    all_notes = []
    for role, src in SOURCES.items():
        g = read_glb(os.path.join(raw_dir, f"{role}.raw.glb"))
        V, F, UV, _ = g.geometry()
        cal = calibrate(V, role)
        js = g.json
        # --- textures
        mat = js["materials"][0]
        pbr = mat.setdefault("pbrMetallicRoughness", {})
        base_ti = pbr["baseColorTexture"]["index"]
        norm_ti = mat.get("normalTexture", {}).get("index")
        base = decode(g, base_ti).convert("RGB")
        normal = decode(g, norm_ti).convert("RGB") if norm_ti is not None else None
        if role in BLANK:
            base, normal, notes = blank_regions(V, F, UV, role, base, normal)
            all_notes += notes
        done = set()
        for ti, img, q in [(base_ti, base, 85), (norm_ti, normal, 90)]:
            if ti is None:
                continue
            data, size = to_jpeg(img, q)
            g.replace_image(js["textures"][ti]["source"], data, "image/jpeg")
            done.add(js["textures"][ti]["source"])
        for ii, im in enumerate(js.get("images", [])):
            if ii not in done:
                data, size = to_jpeg(Image.open(io.BytesIO(g.image_bytes(ii))), 85)
                g.replace_image(ii, data, "image/jpeg")
        # --- materials: OPAQUE, no extensions
        for m in js["materials"]:
            m["alphaMode"] = "OPAQUE"
            m.pop("alphaCutoff", None)
            m.pop("extensions", None)
            bcf = m.get("pbrMetallicRoughness", {}).get("baseColorFactor")
            if bcf:
                bcf[3] = 1.0
        for k in ("extensionsUsed", "extensionsRequired"):
            js.pop(k, None)
        for t in js.get("textures", []):
            t.pop("extensions", None)
        out = os.path.join(OUT_DIR, f"{role}.glb")
        g.write(out)
        mb = os.path.getsize(out) / 1e6
        tex_sizes = sorted({Image.open(io.BytesIO(g.image_bytes(i))).size for i in range(len(js.get("images", [])))})
        cal.update({"source": src, "file": f"{role}.glb", "fileMB": round(mb, 3),
                    "trianglesBefore": dec[role]["trisBefore"], "trianglesAfter": int(len(F)),
                    "textureSizes": [list(s) for s in tex_sizes]})
        calib[role] = cal
        sheet_rows.append((role, apply_cal(V, cal), F, UV, np.asarray(base), cal))
        e = cal["sizeError"]
        print(f"{role:6s} fwd {cal['forwardAxis']} yaw {cal['yawDeg']:+6.1f} s {cal['scale']:.4f} "
              f"tris {cal['trianglesBefore']}->{cal['trianglesAfter']} {mb:.2f} MB  err " +
              " ".join(f"{k}{v * 100:+.1f}%" for k, v in e.items()))
    calib["blanking"] = all_notes
    with open(os.path.join(OUT_DIR, "calibration.json"), "w", encoding="utf8") as fh:
        json.dump(calib, fh, ensure_ascii=False, indent=1)
    for n in all_notes:
        print("  " + n)
    contact_sheet(sheet_rows)


def contact_sheet(rows, ppm=70):
    f_big, f_small = font(22), font(15)
    tiles = []
    for role, Vc, F, UV, tex, cal in rows:
        d = cal["designM"]
        hmax = max(Vc[:, 1].max(), 2.0)
        views = []
        # front: camera at -Z looking +Z (sees the front)
        im, info = render(Vc, F, UV, tex, eye=(0, 0.9, -20), target=(0, 0.9, 0), px_per_m=ppm, size=(int(2.4 * ppm), int(2.3 * ppm)),
                          center=(0, 1.05, 0))
        views.append(("front (from -Z)", im))
        # side: camera at +X looking -X  -> image right = -Z (front)
        im, info = render(Vc, F, UV, tex, eye=(20, 0.9, 0), target=(0, 0.9, 0), px_per_m=ppm, size=(int(5.4 * ppm), int(2.3 * ppm)),
                          center=(0, 1.05, 0))
        views.append(("side (from +X, front ->)", im))
        # top: camera above, image up = -Z (front up)
        im, info = render(Vc, F, UV, tex, eye=(0, 20, 0), target=(0, 0, 0), up=(0, 0, -1), px_per_m=ppm,
                          size=(int(2.4 * ppm), int(5.4 * ppm)), center=(0, 0, 0))
        views.append(("top (front up = -Z)", im))
        tiles.append((role, views, cal))
    rowh = int(5.4 * ppm) + 70
    W = int((2.4 + 5.4 + 2.4) * ppm) + 4 * 20 + 160
    sheet = Image.new("RGBA", (W, rowh * len(tiles) + 50), (246, 246, 242, 255))
    dr = ImageDraw.Draw(sheet)
    dr.text((10, 10), "shoulder-check - calibrated models, 70 px/m. red = origin / ground, blue = design size box",
            fill=(20, 20, 20, 255), font=f_small)
    for r, (role, views, cal) in enumerate(tiles):
        y0 = 50 + r * rowh
        e = cal["sizeError"]
        dr.text((10, y0 + 8), role, fill=(0, 0, 0, 255), font=f_big)
        dr.text((10, y0 + 36), f"yaw {cal['yawDeg']:+.0f}  s {cal['scale']:.3f}", fill=(60, 60, 60, 255), font=f_small)
        for k, (kk, v) in enumerate(e.items()):
            dr.text((10, y0 + 56 + 18 * k), f"{kk} {v * 100:+.1f}%", fill=(60, 60, 60, 255), font=f_small)
        dr.text((10, y0 + 140), f"{cal['trianglesAfter']} tris", fill=(60, 60, 60, 255), font=f_small)
        x = 160
        d = cal["designM"]
        for name, im in views:
            img = Image.fromarray(im)
            ox, oy = x, y0 + 26
            sheet.alpha_composite(img, (ox, oy))
            w, h = img.size
            dr.text((ox, y0 + 4), name, fill=(40, 40, 40, 255), font=f_small)
            dr.rectangle([ox, oy, ox + w - 1, oy + h - 1], outline=(200, 200, 200, 255))
            ppm = 70
            if name.startswith("top"):
                cx, cy = ox + w / 2, oy + h / 2
                dr.line([(cx - 8, cy), (cx + 8, cy)], fill=(220, 0, 0, 255), width=2)
                dr.line([(cx, cy - 8), (cx, cy + 8)], fill=(220, 0, 0, 255), width=2)
                dw = d.get("W", 0.7) / 2 * ppm
                dl = d["L"] / 2 * ppm
                dr.rectangle([cx - dw, cy - dl, cx + dw, cy + dl], outline=(40, 90, 230, 255), width=1)
                dr.polygon([(ox + w - 20, oy + 10), (ox + w - 28, oy + 26), (ox + w - 12, oy + 26)], fill=(220, 0, 0, 255))
                dr.text((ox + w - 34, oy + 28), "-Z", fill=(220, 0, 0, 255), font=f_small)
            else:
                gy = oy + h / 2 + 1.05 * ppm  # ground (y=0) given center y=1.05
                cx = ox + w / 2
                dr.line([(ox, gy), (ox + w, gy)], fill=(220, 0, 0, 255), width=1)
                dr.line([(cx, gy - 6), (cx, gy + 6)], fill=(220, 0, 0, 255), width=2)
                half = (d["L"] if name.startswith("side") else d.get("W", 0.7)) / 2 * ppm
                dr.rectangle([cx - half, gy - d["H"] * ppm, cx + half, gy], outline=(40, 90, 230, 255), width=1)
            x += w + 20
    sheet.save(SHEET)
    print("contact sheet ->", SHEET)


if __name__ == "__main__":
    main(sys.argv[1])
