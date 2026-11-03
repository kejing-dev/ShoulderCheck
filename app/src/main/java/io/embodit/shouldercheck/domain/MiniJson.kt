package io.embodit.shouldercheck.domain

/**
 * Minimal JSON reader / writer (the domain has no Android and no org.json). Values: Map<String, Any?> (insertion
 * ordered), List<Any?>, String, Double, Boolean, null. Numbers always read as Double.
 */
object MiniJson {
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value()
        p.ws()
        require(p.i == text.length) { "trailing characters at ${p.i}" }
        return v
    }

    fun write(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> str(sb, v)
            is Boolean -> sb.append(v)
            is Int, is Long -> sb.append(v.toString())
            is Number -> {
                val d = v.toDouble()
                require(d.isFinite()) { "non-finite number" }
                if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) sb.append(d.toLong()) else sb.append(d.toString())
            }
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    str(sb, k.toString())
                    sb.append(':')
                    write(sb, x)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (x in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, x)
                }
                sb.append(']')
            }
            is Enum<*> -> str(sb, v.name)
            else -> str(sb, v.toString())
        }
    }

    private fun str(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            ws()
            require(i < s.length) { "unexpected end" }
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> string()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) number() else throw IllegalArgumentException("bad char '$c' at $i")
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            require(s.startsWith(word, i)) { "bad literal at $i" }
            i += word.length
            return v
        }

        private fun number(): Double {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            return s.substring(start, i).toDouble()
        }

        private fun string(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                require(i < s.length) { "unterminated string" }
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        val e = s[i++]
                        when (e) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> throw IllegalArgumentException("bad escape at $i")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun obj(): Map<String, Any?> {
            i++
            val m = LinkedHashMap<String, Any?>()
            ws()
            if (s[i] == '}') {
                i++
                return m
            }
            while (true) {
                ws()
                val k = string()
                ws()
                require(s[i] == ':') { "expected ':' at $i" }
                i++
                m[k] = value()
                ws()
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return m
                    else -> throw IllegalArgumentException("expected ',' or '}' at ${i - 1}")
                }
            }
        }

        private fun arr(): List<Any?> {
            i++
            val l = ArrayList<Any?>()
            ws()
            if (s[i] == ']') {
                i++
                return l
            }
            while (true) {
                l += value()
                ws()
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return l
                    else -> throw IllegalArgumentException("expected ',' or ']' at ${i - 1}")
                }
            }
        }
    }

    // ---- typed access helpers
    fun Map<*, *>.num(key: String): Double? = (this[key] as? Number)?.toDouble()

    fun Map<*, *>.str(key: String): String? = this[key] as? String

    fun Map<*, *>.bool(key: String): Boolean? = this[key] as? Boolean

    fun Map<*, *>.obj(key: String): Map<*, *>? = this[key] as? Map<*, *>

    fun Map<*, *>.list(key: String): List<*>? = this[key] as? List<*>

    fun Map<*, *>.vec(key: String): Vec3? {
        val l = list(key) ?: return null
        if (l.size < 3) return null
        val n = l.map { (it as? Number)?.toDouble() ?: return null }
        return Vec3(n[0], n[1], n[2])
    }
}
