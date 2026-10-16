package io.github.kmphub.core

/**
 * Minimal JSON reader for the bundled catalog. The engine stays dependency-free so it can be loaded
 * inside Gradle and inside the IDE without fighting over kotlinx-serialization versions.
 */
internal object Json {
    fun parse(text: String): Any? = Parser(text).run {
        val value = readValue()
        skipWhitespace()
        require(pos == text.length) { "Trailing content at $pos" }
        value
    }

    private class Parser(val text: String) {
        var pos = 0

        fun readValue(): Any? {
            skipWhitespace()
            return when (val c = text.getOrNull(pos) ?: fail("Unexpected end")) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) readNumber() else fail("Unexpected '$c'")
            }
        }

        private fun readObject(): Map<String, Any?> {
            val result = LinkedHashMap<String, Any?>()
            pos++
            skipWhitespace()
            if (peek() == '}') return result.also { pos++ }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                result[key] = readValue()
                skipWhitespace()
                when (text[pos++]) {
                    ',' -> continue
                    '}' -> return result
                    else -> fail("Expected ',' or '}'")
                }
            }
        }

        private fun readArray(): List<Any?> {
            val result = ArrayList<Any?>()
            pos++
            skipWhitespace()
            if (peek() == ']') return result.also { pos++ }
            while (true) {
                result += readValue()
                skipWhitespace()
                when (text[pos++]) {
                    ',' -> continue
                    ']' -> return result
                    else -> fail("Expected ',' or ']'")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                when (val c = text[pos++]) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = text[pos++]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> sb.append(text.substring(pos, pos + 4).toInt(16).toChar()).also { pos += 4 }
                        else -> sb.append(e)
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun readNumber(): Number {
            val start = pos
            while (pos < text.length && (text[pos].isDigit() || text[pos] in "-+.eE")) pos++
            val raw = text.substring(start, pos)
            return raw.toLongOrNull() ?: raw.toDouble()
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!text.startsWith(word, pos)) fail("Expected $word")
            pos += word.length
            return value
        }

        fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        private fun peek() = text.getOrNull(pos)

        private fun expect(c: Char) {
            if (text.getOrNull(pos) != c) fail("Expected '$c'")
            pos++
        }

        private fun fail(message: String): Nothing = throw IllegalArgumentException("$message at offset $pos")
    }
}
