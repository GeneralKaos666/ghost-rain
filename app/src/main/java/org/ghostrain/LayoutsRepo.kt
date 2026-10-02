// SPDX-License-Identifier: GPL-3.0-or-later
package org.ghostrain

/**
 * A named HUD config snapshot for one screen (HOME or LOCK): the layout's
 * [name], the per-screen boolean flags ([bools], e.g. `hud`, `el_ram`,
 * `redactIp`), the per-screen integer geometry ([ints], e.g. `hudX`,
 * `hudPos`, `hudScale`), and the [title] text.
 */
data class LayoutSnapshot(
        val name: String,
        val bools: Map<String, Boolean>,
        val ints: Map<String, Int>,
        val title: String
)

/**
 * Single source of named-layout persistence, shared by the Settings screen and
 * (Tasks 4-5) the Compose UI. The wire format is a JSON array of objects with
 * string/boolean/number fields — the same shape `SettingsActivity` has always
 * written via `org.json.JSONArray` — so snapshots stored by older builds load
 * unchanged.
 *
 * Pure Kotlin with no Android dependency so it runs on the JVM unit-test
 * source set. (Notably `org.json` ships as a throwing stub on the unit-test
 * classpath, so this file parses/emits the small JSON subset it needs by
 * hand instead of depending on it.)
 */
object LayoutsRepo {

    /**
     * Parse the `layouts` pref string. Corrupt input (not JSON, not an array,
     * wrong-typed elements) loads as an empty list rather than throwing, so a
     * damaged pref can never break Settings startup.
     */
    fun load(raw: String?): List<LayoutSnapshot> {
        if (raw.isNullOrBlank()) return emptyList()
        val parsed = try {
            Json.read(raw.trim())
        } catch (_: Exception) {
            return emptyList()
        }
        if (parsed !is List<*>) return emptyList()
        return parsed.mapNotNull { element ->
            if (element !is Map<*, *>) return@mapNotNull null
            val name = element["name"] as? String ?: ""
            val title = element["title"] as? String ?: ""
            val bools = LinkedHashMap<String, Boolean>()
            val ints = LinkedHashMap<String, Int>()
            for ((k, v) in element) {
                if (k !is String || k == "name" || k == "title") continue
                when (v) {
                    is Boolean -> bools[k] = v
                    is Number -> ints[k] = v.toInt()
                }
            }
            LayoutSnapshot(name, bools, ints, title)
        }
    }

    /** Serialize snapshots back to the `layouts` pref string. */
    fun serialize(list: List<LayoutSnapshot>): String {
        val sb = StringBuilder("[")
        list.forEachIndexed { index, s ->
            if (index > 0) sb.append(',')
            sb.append('{')
            var first = true
            fun rawField(key: String, valueJson: String) {
                if (!first) sb.append(',')
                first = false
                Json.writeStringInto(key, sb)
                sb.append(':')
                sb.append(valueJson)
            }
            fun strField(key: String, value: String) =
                    rawField(key, buildString { Json.writeStringInto(value, this) })
            // Field order mirrors the legacy save order: name, flags, geometry, title.
            strField("name", s.name)
            for ((k, v) in s.bools) rawField(k, if (v) "true" else "false")
            for ((k, v) in s.ints) rawField(k, v.toString())
            strField("title", s.title)
            sb.append('}')
        }
        return sb.append(']').toString()
    }

    /**
     * Minimal JSON reader/writer for the layouts subset: arrays, objects with
     * string keys, and string/boolean/number/null values. Anything outside that
     * parses generically but [load] only keeps the shapes it understands.
     */
    private object Json {

        fun read(s: String): Any? {
            val p = Parser(s)
            p.spaces()
            val v = p.value()
            p.spaces()
            if (!p.end()) throw IllegalArgumentException("Trailing characters")
            return v
        }

        fun writeStringInto(raw: String, sb: StringBuilder) {
            sb.append('"')
            for (c in raw) {
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    '\b' -> sb.append("\\b")
                    '\u000C' -> sb.append("\\f")
                    else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
                }
            }
            sb.append('"')
        }

        private class Parser(val s: String) {
            var i = 0

            fun end(): Boolean = i >= s.length

            fun spaces() {
                while (i < s.length && s[i].isWhitespace()) i++
            }

            fun value(): Any? {
                if (i >= s.length) throw IllegalArgumentException("Unexpected end of JSON")
                return when (s[i]) {
                    '{' -> obj()
                    '[' -> arr()
                    '"' -> str()
                    't' -> lit("true", true)
                    'f' -> lit("false", false)
                    'n' -> lit("null", null)
                    else -> num()
                }
            }

            fun obj(): Map<String, Any?> {
                i++ // {
                val m = LinkedHashMap<String, Any?>()
                spaces()
                if (i < s.length && s[i] == '}') {
                    i++
                    return m
                }
                while (true) {
                    spaces()
                    if (i >= s.length || s[i] != '"') throw IllegalArgumentException("Expected string key")
                    val k = str()
                    spaces()
                    if (i >= s.length || s[i] != ':') throw IllegalArgumentException("Expected ':'")
                    i++
                    spaces()
                    m[k] = value()
                    spaces()
                    if (i >= s.length) throw IllegalArgumentException("Unterminated object")
                    if (s[i] == '}') {
                        i++
                        return m
                    }
                    if (s[i] != ',') throw IllegalArgumentException("Expected ',' or '}'")
                    i++
                }
            }

            fun arr(): List<Any?> {
                i++ // [
                val l = mutableListOf<Any?>()
                spaces()
                if (i < s.length && s[i] == ']') {
                    i++
                    return l
                }
                while (true) {
                    spaces()
                    l.add(value())
                    spaces()
                    if (i >= s.length) throw IllegalArgumentException("Unterminated array")
                    if (s[i] == ']') {
                        i++
                        return l
                    }
                    if (s[i] != ',') throw IllegalArgumentException("Expected ',' or ']'")
                    i++
                }
            }

            fun str(): String {
                i++ // opening quote
                val sb = StringBuilder()
                while (true) {
                    if (i >= s.length) throw IllegalArgumentException("Unterminated string")
                    val c = s[i++]
                    when (c) {
                        '"' -> return sb.toString()
                        '\\' -> {
                            if (i >= s.length) throw IllegalArgumentException("Unterminated escape")
                            when (val e = s[i++]) {
                                '"', '\\', '/' -> sb.append(e)
                                'b' -> sb.append('\b')
                                'f' -> sb.append('\u000C')
                                'n' -> sb.append('\n')
                                'r' -> sb.append('\r')
                                't' -> sb.append('\t')
                                'u' -> {
                                    if (i + 4 > s.length) throw IllegalArgumentException("Bad \\u escape")
                                    sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                    i += 4
                                }
                                else -> throw IllegalArgumentException("Bad escape: $e")
                            }
                        }
                        else -> sb.append(c)
                    }
                }
            }

            fun lit(word: String, v: Any?): Any? {
                if (!s.startsWith(word, i)) throw IllegalArgumentException("Expected $word")
                i += word.length
                return v
            }

            fun num(): Number {
                val start = i
                if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
                while (i < s.length && (s[i].isDigit() || s[i] == '.' ||
                                s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
                val tok = s.substring(start, i)
                return tok.toLongOrNull() ?: tok.toDoubleOrNull()
                        ?: throw IllegalArgumentException("Bad number: $tok")
            }
        }
    }
}
