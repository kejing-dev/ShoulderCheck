// shoulder-check offline asset pipeline, step 1a: decimate the Tripo GLBs.
//
// Usage (node >= 18):
//   GLTF_TOOLS=<dir that contains node_modules with @gltf-transform/core,functions + meshoptimizer> \
//   node tools/decimate_models.mjs <srcGlbDir> <outDir> [targetTris=11500]
//
// Tooling lives outside the project (npm install @gltf-transform/cli@4 in a scratch dir).
// Output: <outDir>/<role>.raw.glb — geometry only decimated, textures untouched; tools/process_models.py
// then resizes textures, forces OPAQUE, measures the calibration and writes the final <role>.glb.
//
// Why not `gltf-transform simplify`: Tripo splits ~15 % of vertices on NORMAL only (same position + UV);
// those splits act as seams and the stock simplify stalls at ~230k triangles. We weld on POSITION+UV,
// simplify with meshoptimizer honouring the remaining UV seams (no 'Permissive' flag: crossing UV seams
// smeared the texture in a first try), then recompute smooth normals for the low-poly surface.
import { pathToFileURL } from 'node:url';
import path from 'node:path';
import fs from 'node:fs';

const NM = path.join(process.env.GLTF_TOOLS || '.', 'node_modules');
const imp = (p) => import(pathToFileURL(path.join(NM, p)).href);
const { NodeIO } = await imp('@gltf-transform/core/dist/index.js');
const { prune, dedup } = await imp('@gltf-transform/functions/dist/index.js');
const { MeshoptSimplifier } = await imp('meshoptimizer/index.js');
await MeshoptSimplifier.ready;

export const MODELS = {
  sedan: 'sc_car_silver.glb',
  suv: 'sc_suv_red.glb',
  ebike: 'sc_ebike_delivery.glb',
  bike: 'sc_bike_navy.glb',
  moto: 'sc_moto_blue.glb',
};

const [srcDir, outDir, tgtArg] = process.argv.slice(2);
const TARGET_TRIS = Number(tgtArg || 11500);
fs.mkdirSync(outDir, { recursive: true });
const io = new NodeIO();
const report = {};

for (const [role, file] of Object.entries(MODELS)) {
  const doc = await io.read(path.join(srcDir, file));
  const root = doc.getRoot();
  let trisBefore = 0, trisAfter = 0;
  const prims = root.listMeshes().flatMap((m) => m.listPrimitives());
  const totalBefore = prims.reduce((s, p) => s + (p.getIndices() ? p.getIndices().getCount() : p.getAttribute('POSITION').getCount()) / 3, 0);
  for (const prim of prims) {
    const posA = prim.getAttribute('POSITION');
    const uvA = prim.getAttribute('TEXCOORD_0');
    let vcount = posA.getCount();
    let indices = prim.getIndices() ? Uint32Array.from(prim.getIndices().getArray()) : Uint32Array.from({ length: vcount }, (_, i) => i);
    const tris = indices.length / 3;
    trisBefore += tris;
    const share = tris / totalBefore;
    const target = Math.max(12, Math.floor(TARGET_TRIS * share) * 3);
    // 1) weld by POSITION+UV (Tripo splits ~15 % of vertices on normals only; those splits block the simplifier)
    const P0 = posA.getArray(), U0 = uvA ? uvA.getArray() : null;
    const key2new = new Map();
    const weldMap = new Uint32Array(vcount);
    const keep = [];
    const f32 = new Float32Array(5), u32 = new Uint32Array(f32.buffer);
    for (let v = 0; v < vcount; v++) {
      f32[0] = P0[v * 3]; f32[1] = P0[v * 3 + 1]; f32[2] = P0[v * 3 + 2];
      f32[3] = U0 ? U0[v * 2] : 0; f32[4] = U0 ? U0[v * 2 + 1] : 0;
      const k = `${u32[0]},${u32[1]},${u32[2]},${u32[3]},${u32[4]}`;
      let id = key2new.get(k);
      if (id === undefined) { id = keep.length; key2new.set(k, id); keep.push(v); }
      weldMap[v] = id;
    }
    for (let i = 0; i < indices.length; i++) indices[i] = weldMap[indices[i]];
    for (const sem of prim.listSemantics()) {
      const a = prim.getAttribute(sem); const k = a.getElementSize(); const src = a.getArray();
      const dst = new src.constructor(keep.length * k);
      for (let j = 0; j < keep.length; j++) for (let c = 0; c < k; c++) dst[j * k + c] = src[keep[j] * k + c];
      prim.setAttribute(sem, doc.createAccessor().setType(a.getType()).setArray(dst).setNormalized(a.getNormalized()).setBuffer(a.getBuffer()));
    }
    vcount = keep.length;
    const uvA2 = prim.getAttribute('TEXCOORD_0');
    const pos = Float32Array.from(prim.getAttribute('POSITION').getArray());
    // 2) simplify honouring UV seams (no 'Permissive': crossing a UV seam smears the texture)
    const attrs = uvA2 ? Float32Array.from(uvA2.getArray()) : null;
    let [out, err] = attrs
      ? MeshoptSimplifier.simplifyWithAttributes(indices, pos, 3, attrs, 2, [1.0, 1.0], null, target, 1.0, [])
      : MeshoptSimplifier.simplify(indices, pos, 3, target, 1.0, []);
    console.log(`${role}: welded ${posA.getCount()} -> ${vcount} verts; pass1 ${out.length / 3} tris`);
    // compact: keep only referenced vertices
    const remap = new Int32Array(vcount).fill(-1);
    let n = 0;
    for (let i = 0; i < out.length; i++) if (remap[out[i]] < 0) remap[out[i]] = n++;
    const newIdx = new Uint32Array(out.length);
    for (let i = 0; i < out.length; i++) newIdx[i] = remap[out[i]];
    for (const sem of prim.listSemantics()) {
      const a = prim.getAttribute(sem);
      const k = a.getElementSize();
      const src = a.getArray();
      const dst = new src.constructor(n * k);
      for (let v = 0; v < vcount; v++) {
        const r = remap[v];
        if (r >= 0) for (let c = 0; c < k; c++) dst[r * k + c] = src[v * k + c];
      }
      const na = doc.createAccessor().setType(a.getType()).setArray(dst).setNormalized(a.getNormalized()).setBuffer(a.getBuffer());
      prim.setAttribute(sem, na);
    }
    const ia = doc.createAccessor().setType('SCALAR').setArray(n < 65536 ? Uint16Array.from(newIdx) : newIdx).setBuffer(prim.getIndices() ? prim.getIndices().getBuffer() : posA.getBuffer());
    prim.setIndices(ia);
    // 3) recompute NORMAL for the low-poly surface: area-weighted, shared across UV-seam twins (same position)
    if (prim.getAttribute('NORMAL')) {
      const P = prim.getAttribute('POSITION').getArray();
      const pkey = new Map(); const grp = new Uint32Array(n); let g = 0;
      for (let v = 0; v < n; v++) {
        const k = `${P[v * 3]},${P[v * 3 + 1]},${P[v * 3 + 2]}`;
        let id = pkey.get(k); if (id === undefined) { id = g++; pkey.set(k, id); } grp[v] = id;
      }
      const acc = new Float64Array(g * 3);
      for (let t = 0; t < newIdx.length; t += 3) {
        const a = newIdx[t] * 3, b = newIdx[t + 1] * 3, c = newIdx[t + 2] * 3;
        const ux = P[b] - P[a], uy = P[b + 1] - P[a + 1], uz = P[b + 2] - P[a + 2];
        const vx = P[c] - P[a], vy = P[c + 1] - P[a + 1], vz = P[c + 2] - P[a + 2];
        const nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
        for (const vi of [newIdx[t], newIdx[t + 1], newIdx[t + 2]]) { const o = grp[vi] * 3; acc[o] += nx; acc[o + 1] += ny; acc[o + 2] += nz; }
      }
      const N = new Float32Array(n * 3);
      const na = prim.getAttribute('NORMAL');
      const old = na.getArray();
      let fallback = 0;
      for (let v = 0; v < n; v++) {
        const o = grp[v] * 3; const l = Math.hypot(acc[o], acc[o + 1], acc[o + 2]);
        if (l > 1e-12) {
          N[v * 3] = acc[o] / l; N[v * 3 + 1] = acc[o + 1] / l; N[v * 3 + 2] = acc[o + 2] / l;
        } else { // faces cancel out (folded sliver): keep the source normal (or +Y)
          const ol = Math.hypot(old[v * 3], old[v * 3 + 1], old[v * 3 + 2]);
          if (ol > 1e-6) { N[v * 3] = old[v * 3] / ol; N[v * 3 + 1] = old[v * 3 + 1] / ol; N[v * 3 + 2] = old[v * 3 + 2] / ol; }
          else N[v * 3 + 1] = 1;
          fallback++;
        }
      }
      if (fallback) console.log(`${role}: ${fallback} normals fell back to the source normal`);
      prim.setAttribute('NORMAL', doc.createAccessor().setType('VEC3').setArray(N).setBuffer(na.getBuffer()));
    }
    trisAfter += newIdx.length / 3;
    console.log(`${role}: prim ${tris} -> ${newIdx.length / 3} tris, ${n} verts, err ${err.toFixed(4)}`);
  }
  // no compression extensions — make sure nothing is declared
  for (const ext of root.listExtensionsUsed()) ext.dispose();
  await doc.transform(prune(), dedup());
  const outPath = path.join(outDir, `${role}.raw.glb`);
  await io.write(outPath, doc);
  report[role] = { source: file, trisBefore, trisAfter };
}
fs.writeFileSync(path.join(outDir, 'decimate-report.json'), JSON.stringify(report, null, 1));
console.log(JSON.stringify(report));
