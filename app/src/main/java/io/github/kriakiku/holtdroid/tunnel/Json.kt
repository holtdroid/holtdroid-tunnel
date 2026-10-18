package io.github.kriakiku.holtdroid.tunnel

object Json {
    class Raw(val json: String)

    fun obj(vararg fields: Pair<String, Any?>): String =
        fields.joinToString(",", "{", "}") { (k, v) -> "${quote(k)}:${value(v)}" }

    fun value(v: Any?): String = when (v) {
        null -> "null"
        is Raw -> v.json
        is Boolean, is Int, is Long -> v.toString()
        is Enum<*> -> quote(v.name.lowercase())
        is Collection<*> -> v.joinToString(",", "[", "]") { value(it) }
        else -> quote(v.toString())
    }

    fun quote(s: String): String = buildString(s.length + 2) {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }
}
