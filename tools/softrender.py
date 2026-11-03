"""Tiny numpy software rasteriser (orthographic, z-buffer, textured, lambert) for offline previews and
mirror silhouettes. Not fast, but ~12k triangles render in about a second.

Conventions: world is glTF (Y up, right-handed). glTF UV origin is the top-left of the image, so a texel is
tex[v*H, u*W] with no flip.
"""
import numpy as np


def look_at(eye, target, up=(0, 1, 0)):
    eye = np.asarray(eye, float); target = np.asarray(target, float); up = np.asarray(up, float)
    f = target - eye; f /= np.linalg.norm(f)
    r = np.cross(f, up); r /= np.linalg.norm(r)
    u = np.cross(r, f)
    return np.stack([r, u, -f]), eye  # rows: right, up, back (camera looks down -back)


def render(V, F, UV=None, tex=None, eye=(0, 0, 5), target=(0, 0, 0), up=(0, 1, 0), px_per_m=100.0,
           size=None, center=None, light=None, ambient=0.42, margin=4, ss=2, base_rgb=(170, 170, 175)):
    """Return (rgba uint8 HxWx4, info dict). If size is None the canvas is fitted to the projected bbox."""
    R, e = look_at(eye, target, up)
    C = (V - e) @ R.T  # camera space
    s = px_per_m * ss
    x, y = C[:, 0], C[:, 1]
    depth = -C[:, 2]
    if size is None:
        x0, x1, y0, y1 = x.min(), x.max(), y.min(), y.max()
        W = int(np.ceil((x1 - x0) * px_per_m)) + 2 * margin
        H = int(np.ceil((y1 - y0) * px_per_m)) + 2 * margin
        cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    else:
        W, H = size
        if center is None:
            cx, cy = (x.min() + x.max()) / 2, (y.min() + y.max()) / 2
        else:
            cc = (np.asarray(center, float) - e) @ R.T
            cx, cy = cc[0], cc[1]
    Ws, Hs = W * ss, H * ss
    sx = (x - cx) * s + Ws / 2
    sy = Hs / 2 - (y - cy) * s
    zbuf = np.full((Hs, Ws), np.inf)
    col = np.zeros((Hs, Ws, 3))
    alpha = np.zeros((Hs, Ws))
    if light is None:
        light = np.array([0.35, 0.8, 0.5])  # camera space: from upper right, toward camera
    light = np.asarray(light, float); light /= np.linalg.norm(light)
    # face normals in camera space
    a, b, c = C[F[:, 0]], C[F[:, 1]], C[F[:, 2]]
    n = np.cross(b - a, c - a)
    nl = np.linalg.norm(n, axis=1); nl[nl == 0] = 1
    n /= nl[:, None]
    n[n[:, 2] < 0] *= -1  # double-sided: face the camera
    shade = ambient + (1 - ambient) * np.clip(n @ light, 0, 1)
    if tex is not None:
        th, tw = tex.shape[:2]
        texf = tex[..., :3].astype(np.float64)
    base = np.asarray(base_rgb, float)
    for t in range(len(F)):
        i0, i1, i2 = F[t]
        px = np.array([sx[i0], sx[i1], sx[i2]]); py = np.array([sy[i0], sy[i1], sy[i2]])
        xa, xb = int(max(np.floor(px.min()), 0)), int(min(np.ceil(px.max()), Ws - 1))
        ya, yb = int(max(np.floor(py.min()), 0)), int(min(np.ceil(py.max()), Hs - 1))
        if xa > xb or ya > yb:
            continue
        den = (py[1] - py[2]) * (px[0] - px[2]) + (px[2] - px[1]) * (py[0] - py[2])
        if abs(den) < 1e-12:
            continue
        gx, gy = np.meshgrid(np.arange(xa, xb + 1) + 0.5, np.arange(ya, yb + 1) + 0.5)
        w0 = ((py[1] - py[2]) * (gx - px[2]) + (px[2] - px[1]) * (gy - py[2])) / den
        w1 = ((py[2] - py[0]) * (gx - px[2]) + (px[0] - px[2]) * (gy - py[2])) / den
        w2 = 1 - w0 - w1
        m = (w0 >= -1e-6) & (w1 >= -1e-6) & (w2 >= -1e-6)
        if not m.any():
            continue
        z = w0 * depth[i0] + w1 * depth[i1] + w2 * depth[i2]
        sub = zbuf[ya:yb + 1, xa:xb + 1]
        m &= z < sub
        if not m.any():
            continue
        sub[m] = z[m]
        if tex is not None and UV is not None:
            u = w0 * UV[i0, 0] + w1 * UV[i1, 0] + w2 * UV[i2, 0]
            v = w0 * UV[i0, 1] + w1 * UV[i1, 1] + w2 * UV[i2, 1]
            tu = np.clip((u[m] % 1.0) * tw, 0, tw - 1).astype(int)
            tv = np.clip((v[m] % 1.0) * th, 0, th - 1).astype(int)
            rgb = texf[tv, tu]
        else:
            rgb = base[None, :]
        col[ya:yb + 1, xa:xb + 1][m] = rgb * shade[t]
        alpha[ya:yb + 1, xa:xb + 1][m] = 1.0
    # downsample (box filter), premultiplied
    col = col.reshape(H, ss, W, ss, 3).mean(axis=(1, 3))
    alpha = alpha.reshape(H, ss, W, ss).mean(axis=(1, 3))
    rgb = np.where(alpha[..., None] > 0, col / np.maximum(alpha[..., None], 1e-6), 0)
    img = np.dstack([np.clip(rgb, 0, 255), alpha * 255]).astype(np.uint8)
    info = {"px_per_m": px_per_m, "W": W, "H": H, "cx": cx, "cy": cy}
    return img, info
