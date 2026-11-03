"""Minimal GLB reader / writer for the shoulder-check asset pipeline (numpy only).

read_glb(path) -> Glb with .json, .bin, helpers to get accessors in scene (world) space.
Glb.write(path) rebuilds the single binary buffer (replaced image bytes are re-packed, 4-byte aligned).
"""
import json
import struct

import numpy as np

_CT = {5120: np.int8, 5121: np.uint8, 5122: np.int16, 5123: np.uint16, 5125: np.uint32, 5126: np.float32}
_NC = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}


def _quat_mat(q):
    x, y, z, w = q
    return np.array([
        [1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
        [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
        [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])


def node_matrix(n):
    if "matrix" in n:
        return np.array(n["matrix"], dtype=np.float64).reshape(4, 4).T
    m = np.eye(4)
    s = np.array(n.get("scale", [1, 1, 1]), dtype=np.float64)
    r = _quat_mat(n.get("rotation", [0, 0, 0, 1]))
    m[:3, :3] = r * s[None, :]
    m[:3, 3] = n.get("translation", [0, 0, 0])
    return m


class Glb:
    def __init__(self, js, binary):
        self.json = js
        self.bin = bytearray(binary)
        self.replaced = {}  # bufferView index -> new bytes

    def accessor(self, i):
        a = self.json["accessors"][i]
        bv = self.json["bufferViews"][a["bufferView"]]
        dt = np.dtype(_CT[a["componentType"]])
        nc = _NC[a["type"]]
        off = bv.get("byteOffset", 0) + a.get("byteOffset", 0)
        stride = bv.get("byteStride", 0) or dt.itemsize * nc
        cnt = a["count"]
        if stride == dt.itemsize * nc:
            arr = np.frombuffer(bytes(self.bin[off:off + cnt * stride]), dtype=dt).reshape(cnt, nc)
        else:
            raw = np.frombuffer(bytes(self.bin[off:off + (cnt - 1) * stride + dt.itemsize * nc]), dtype=np.uint8)
            arr = np.stack([np.frombuffer(raw[k * stride:k * stride + dt.itemsize * nc].tobytes(), dtype=dt) for k in range(cnt)])
        if a.get("normalized"):
            arr = arr.astype(np.float32) / np.iinfo(dt).max
        return arr.copy()

    def image_bytes(self, img_index):
        im = self.json["images"][img_index]
        bvi = im["bufferView"]
        if bvi in self.replaced:
            return self.replaced[bvi]
        bv = self.json["bufferViews"][bvi]
        o = bv.get("byteOffset", 0)
        return bytes(self.bin[o:o + bv["byteLength"]])

    def replace_image(self, img_index, data, mime):
        im = self.json["images"][img_index]
        self.replaced[im["bufferView"]] = bytes(data)
        im["mimeType"] = mime

    def world_meshes(self):
        """Yield (meshIndex, primitive dict, 4x4 world matrix) for every drawn primitive of the default scene."""
        js = self.json
        scene = js["scenes"][js.get("scene", 0)]
        out = []

        def walk(ni, parent):
            n = js["nodes"][ni]
            m = parent @ node_matrix(n)
            if "mesh" in n:
                for p in js["meshes"][n["mesh"]]["primitives"]:
                    out.append((n["mesh"], p, m))
            for c in n.get("children", []):
                walk(c, m)

        for r in scene["nodes"]:
            walk(r, np.eye(4))
        return out

    def geometry(self):
        """Concatenated world-space geometry: V (n,3), F (m,3), UV (n,2) or None, material index per face."""
        Vs, Fs, UVs, Ms = [], [], [], []
        base = 0
        for _, p, m in self.world_meshes():
            v = self.accessor(p["attributes"]["POSITION"]).astype(np.float64)
            v = v @ m[:3, :3].T + m[:3, 3]
            f = self.accessor(p["indices"]).reshape(-1, 3).astype(np.int64) if "indices" in p else np.arange(len(v)).reshape(-1, 3)
            uv = self.accessor(p["attributes"]["TEXCOORD_0"]).astype(np.float64) if "TEXCOORD_0" in p["attributes"] else np.zeros((len(v), 2))
            Vs.append(v); Fs.append(f + base); UVs.append(uv); Ms.append(np.full(len(f), p.get("material", -1)))
            base += len(v)
        return np.concatenate(Vs), np.concatenate(Fs), np.concatenate(UVs), np.concatenate(Ms)

    def write(self, path):
        js = self.json
        new = bytearray()
        for i, bv in enumerate(js["bufferViews"]):
            if i in self.replaced:
                data = self.replaced[i]
            else:
                o = bv.get("byteOffset", 0)
                data = bytes(self.bin[o:o + bv["byteLength"]])
            while len(new) % 4:
                new.append(0)
            bv["byteOffset"] = len(new)
            bv["byteLength"] = len(data)
            bv["buffer"] = 0
            new.extend(data)
        while len(new) % 4:
            new.append(0)
        js["buffers"] = [{"byteLength": len(new)}]
        jb = json.dumps(js, separators=(",", ":")).encode("utf8")
        while len(jb) % 4:
            jb += b" "
        total = 12 + 8 + len(jb) + 8 + len(new)
        with open(path, "wb") as fh:
            fh.write(struct.pack("<III", 0x46546C67, 2, total))
            fh.write(struct.pack("<II", len(jb), 0x4E4F534A)); fh.write(jb)
            fh.write(struct.pack("<II", len(new), 0x004E4942)); fh.write(new)


def read_glb(path):
    with open(path, "rb") as fh:
        data = fh.read()
    magic, ver, total = struct.unpack_from("<III", data, 0)
    assert magic == 0x46546C67, "not a GLB"
    off = 12
    js, binary = None, b""
    while off < total:
        ln, typ = struct.unpack_from("<II", data, off)
        chunk = data[off + 8:off + 8 + ln]
        if typ == 0x4E4F534A:
            js = json.loads(chunk.decode("utf8"))
        elif typ == 0x004E4942:
            binary = chunk
        off += 8 + ln
    return Glb(js, binary)
