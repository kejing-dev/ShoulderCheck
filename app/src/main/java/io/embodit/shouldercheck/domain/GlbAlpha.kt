package io.embodit.shouldercheck.domain

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Load-time OPAQUE guard for the road-user GLBs (factory rule: "导入的 GLB 在加载时检查 alphaMode，强制 OPAQUE").
 * Reads the glTF JSON chunk of a binary glTF, reports every material's `alphaMode` (glTF default = OPAQUE) and, when
 * any material is BLEND or MASK, rewrites the JSON chunk with `alphaMode: "OPAQUE"` (and no `alphaCutoff`) before the
 * bytes go to the SDK loader. Pure Kotlin (JVM-tested).
 */
object GlbAlpha {
    private const val MAGIC = 0x46546C67 // "glTF"
    private const val CHUNK_JSON = 0x4E4F534A // "JSON"

    data class Report(val modes: List<String>, val forced: Int) {
        val allOpaque: Boolean
            get() = modes.all { it == "OPAQUE" }
    }

    private class Parsed(val json: Map<*, *>, val jsonStart: Int, val jsonLen: Int)

    private fun parse(glb: ByteArray): Parsed {
        val b = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)
        require(glb.size >= 20 && b.getInt(0) == MAGIC) { "not a binary glTF" }
        val len = b.getInt(12)
        require(b.getInt(16) == CHUNK_JSON) { "first chunk is not JSON" }
        val text = String(glb, 20, len, Charsets.UTF_8).trimEnd(' ', '\u0000')
        val json = MiniJson.parse(text) as? Map<*, *> ?: error("JSON chunk is not an object")
        return Parsed(json, 20, len)
    }

    /** alphaMode of every material, in order ("OPAQUE" when absent). */
    fun alphaModes(glb: ByteArray): List<String> =
        ((parse(glb).json["materials"] as? List<*>) ?: emptyList<Any?>()).map { m -> ((m as? Map<*, *>)?.get("alphaMode") as? String) ?: "OPAQUE" }

    /**
     * The GLB with every material forced to OPAQUE, plus a report (modes as found, number of materials changed).
     * Unchanged bytes are returned as is when everything already is OPAQUE.
     */
    fun forceOpaque(glb: ByteArray): Pair<ByteArray, Report> {
        val p = parse(glb)
        val materials = (p.json["materials"] as? List<*>) ?: emptyList<Any?>()
        val modes = materials.map { m -> ((m as? Map<*, *>)?.get("alphaMode") as? String) ?: "OPAQUE" }
        val forced = modes.count { it != "OPAQUE" }
        if (forced == 0) return glb to Report(modes, 0)
        val newMaterials =
            materials.map { m ->
                val src = m as? Map<*, *> ?: return@map m
                val out = LinkedHashMap<String, Any?>()
                for ((k, v) in src) if (k != "alphaCutoff") out[k.toString()] = v
                out["alphaMode"] = "OPAQUE"
                out
            }
        val root = LinkedHashMap<String, Any?>()
        for ((k, v) in p.json) root[k.toString()] = v
        root["materials"] = newMaterials
        var jsonBytes = MiniJson.write(root).toByteArray(Charsets.UTF_8)
        val pad = (4 - jsonBytes.size % 4) % 4
        if (pad > 0) jsonBytes += ByteArray(pad) { ' '.code.toByte() }
        val rest = glb.copyOfRange(p.jsonStart + p.jsonLen, glb.size)
        val total = 12 + 8 + jsonBytes.size + rest.size
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(MAGIC).putInt(2).putInt(total)
        out.putInt(jsonBytes.size).putInt(CHUNK_JSON).put(jsonBytes).put(rest)
        return out.array() to Report(modes, forced)
    }
}
