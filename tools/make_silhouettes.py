"""shoulder-check asset pipeline, step 2: pre-rendered vehicle images for the mirror canvases
(model.mirrors.render / gateBDecisions.mirrorSilhouettes).

Reads app/src/main/assets/models/<role>.glb + calibration.json, applies the calibration (front -> -Z, +Y up,
bottom-centre at the origin, metres) and renders orthographic, textured, lambert-shaded RGBA PNGs, 256 px tall,
transparent background. Writes app/src/main/assets/silhouettes/<role>_<view>.png + silhouettes.json.

Views (camera position relative to the vehicle; the vehicle always drives toward -Z):
  rear  camera straight behind (+Z), looking forward        - as the design package asked (背面)
  lr    camera behind-left  (rear + LEFT flank visible)
  rr    camera behind-right (rear + RIGHT flank visible)
  front camera straight ahead (-Z), looking back at it      - what a rear-view mirror really shows of a
  fl    camera ahead-left  (front + LEFT flank visible)        vehicle FOLLOWING you (it drives toward you)
  fr    camera ahead-right (front + RIGHT flank visible)
Images are plain camera views, NOT mirror-flipped; flip horizontally in the app if the mirror projection needs it.
Run:  python -X utf8 tools/make_silhouettes.py
"""
import io
import json
import os
import sys

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from glbio import read_glb  # noqa: E402
from softrender import look_at, render  # noqa: E402
from process_models import apply_cal  # noqa: E402

PROJECT = os.path.dirname(HERE)
MODELS = os.path.join(PROJECT, "app", "src", "main", "assets", "models")
OUT = os.path.join(PROJECT, "app", "src", "main", "assets", "silhouettes")
H_PX = 256
MARGIN = 4
AZ = 30.0    # 3/4 views: degrees off the vehicle's long axis
ELEV = 4.0   # camera a little above (driver eye ~1.2 m looking at a vehicle 10-20 m back)

# view -> azimuth of the camera measured from +Z (behind) toward +X (vehicle's right), degrees
VIEWS = {"rear": 0.0, "lr": -AZ, "rr": AZ, "front": 180.0, "fl": 180.0 + AZ, "fr": 180.0 - AZ}


def main():
    os.makedirs(OUT, exist_ok=True)
    cal = json.load(open(os.path.join(MODELS, "calibration.json"), encoding="utf8"))
    meta = {"_doc": ("Orthographic renders of the calibrated models (front -> -Z). bboxPx = [x0, y0, x1, y1) of "
                     "non-transparent pixels; widthM/heightM = real size that bbox represents; pxPerM = scale; "
                     "groundPx = image position of the vehicle's bottom-centre (calibrated origin). Camera azimuth "
                     "from behind (+Z) toward the vehicle's right (+X); elevation looks slightly down. Not mirror-"
                     "flipped. For a mirror use front/fl/fr (followers face you): a follower in the lane to your LEFT "
                     "shows its front + right flank = 'fr'; one in the lane / bike lane to your RIGHT = 'fl'. "
                     "rear/lr/rr = as the design package asked (背面/左后/右后)."),
            "heightPx": H_PX, "azimuthDeg3q": AZ, "elevationDeg": ELEV, "images": {}}
    for role in ["sedan", "suv", "ebike", "bike", "moto"]:
        g = read_glb(os.path.join(MODELS, f"{role}.glb"))
        V, F, UV, _ = g.geometry()
        Vc = apply_cal(V, cal[role])
        mat = g.json["materials"][0]
        ti = mat["pbrMetallicRoughness"]["baseColorTexture"]["index"]
        tex = np.asarray(Image.open(io.BytesIO(g.image_bytes(g.json["textures"][ti]["source"]))).convert("RGB"))
        ctr = np.array([0.0, Vc[:, 1].max() / 2, 0.0])
        for view, az in VIEWS.items():
            a, e = np.radians(az), np.radians(ELEV)
            d = np.array([np.sin(a) * np.cos(e), np.sin(e), np.cos(a) * np.cos(e)])
            eye = ctr + d * 30.0
            # fit: projected height -> H_PX - 2*margin
            R, _ = look_at(eye, ctr)
            C = (Vc - eye) @ R.T
            ph = C[:, 1].max() - C[:, 1].min()
            ppm = (H_PX - 2 * MARGIN) / ph
            img, info = render(Vc, F, UV, tex, eye=eye, target=ctr, px_per_m=ppm, margin=MARGIN, ss=3)
            # crop/pad height exactly to H_PX
            im = Image.fromarray(img)
            if im.height != H_PX:
                canvas = Image.new("RGBA", (im.width, H_PX), (0, 0, 0, 0))
                canvas.alpha_composite(im, (0, (H_PX - im.height) // 2) if im.height < H_PX else (0, 0))
                off_y = (H_PX - im.height) // 2
                im = canvas
            else:
                off_y = 0
            alpha = np.asarray(im)[..., 3]
            ys, xs = np.nonzero(alpha > 8)
            bbox = [int(xs.min()), int(ys.min()), int(xs.max()) + 1, int(ys.max()) + 1]
            g0 = (np.zeros(3) - eye) @ R.T
            gx = (g0[0] - info["cx"]) * ppm + info["W"] / 2
            gy = info["H"] / 2 - (g0[1] - info["cy"]) * ppm + off_y
            name = f"{role}_{view}.png"
            im.quantize(256, method=Image.Quantize.FASTOCTREE).save(os.path.join(OUT, name), optimize=True)  # palette+alpha PNG
            meta["images"][name] = {
                "role": role, "view": view, "azimuthDeg": az, "sizePx": [im.width, im.height],
                "bboxPx": bbox, "pxPerM": round(ppm, 3),
                "widthM": round((bbox[2] - bbox[0]) / ppm, 3), "heightM": round((bbox[3] - bbox[1]) / ppm, 3),
                "groundPx": [round(gx, 1), round(gy, 1)]}
            print(f"{name:16s} {im.width}x{im.height}  {meta['images'][name]['widthM']:.2f} x "
                  f"{meta['images'][name]['heightM']:.2f} m")
    with open(os.path.join(OUT, "silhouettes.json"), "w", encoding="utf8") as fh:
        json.dump(meta, fh, ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
