package io.github.kmphub.core

/**
 * A brace-aware view of a `build.gradle.kts` file. It understands strings, templates and comments well
 * enough to find blocks like `kotlin { sourceSets { commonMain.dependencies { ... } } }` and to insert
 * lines into them. Edits return a new script; offsets are never reused across edits.
 */
class KtsBuildScript private constructor(val text: String, val root: Block) {

    class Block(
        /** Text before `{` with whitespace collapsed, e.g. `commonMain.dependencies` or `val androidMain by getting`. */
        val header: String,
        /** Offset of `{`; -1 for the file itself. */
        val open: Int,
        val parent: Block?,
    ) {
        /** Offset of the matching `}`; the text length for the file itself. */
        var close: Int = -1
            internal set
        val children = mutableListOf<Block>()

        val compactHeader: String get() = header.replace(" ", "")

        fun descendants(): Sequence<Block> = children.asSequence().flatMap { sequenceOf(it) + it.descendants() }

        fun child(header: String): Block? = children.firstOrNull { it.compactHeader == header.replace(" ", "") }
    }

    fun body(block: Block): String = text.substring(block.open + 1, block.close)

    fun topLevel(header: String): Block? = root.child(header)

    /** The block that holds dependencies for [sourceSet], in any of the common declaration styles. */
    fun sourceSetDependencies(sourceSet: String): Block? = allSourceSetDependencies(sourceSet).firstOrNull()

    /** Every dependencies block for [sourceSet], across all top-level `kotlin { }` blocks. */
    fun allSourceSetDependencies(sourceSet: String): List<Block> {
        val direct = setOf(
            "$sourceSet.dependencies",
            "sourceSets.$sourceSet.dependencies",
            "getByName(\"$sourceSet\").dependencies",
            "sourceSets.getByName(\"$sourceSet\").dependencies",
        )
        return root.children.filter { it.compactHeader == "kotlin" }.flatMap { kotlin ->
            kotlin.descendants().filter { it.compactHeader in direct }.toList() +
                kotlin.descendants().filter { sourceSetHeader(it.header, sourceSet) }.mapNotNull { it.child("dependencies") }
        }
    }

    /** Inserts [line] as the last statement of [block], indented one level deeper than the block. */
    fun insertLine(block: Block, line: String): KtsBuildScript = insertLines(block, listOf(line))

    /** Creates `header { }` as the last child of [parent] (or of the file when [parent] is null). */
    fun insertBlock(parent: Block?, header: String): KtsBuildScript {
        val target = parent ?: root
        if (target === root) {
            val sep = if (text.isEmpty() || text.endsWith("\n\n")) "" else if (text.endsWith("\n")) "\n" else "\n\n"
            return parse("$text$sep$header {\n}\n")
        }
        return insertLines(target, listOf("$header {", "}"))
    }

    /** Creates `header { }` at the very top of the file, used for a missing `plugins` block. */
    fun prependBlock(header: String): KtsBuildScript = parse("$header {\n}\n\n$text")

    private fun insertLines(block: Block, newLines: List<String>): KtsBuildScript {
        require(block !== root) { "Use insertBlock for top-level additions" }
        val baseIndent = indentOf(lineStart(block.open))
        val childIndent = existingChildIndent(block) ?: (baseIndent + INDENT)
        val payload = newLines.joinToString("") { childIndent + it + "\n" }

        val closeLineStart = lineStart(block.close)
        val beforeClose = text.substring(closeLineStart, block.close)
        val updated = if (beforeClose.isBlank() && closeLineStart > block.open) {
            text.substring(0, closeLineStart) + payload + text.substring(closeLineStart)
        } else {
            // `{ }` or `{ foo() }` on one line: break it open.
            val inner = text.substring(block.open + 1, block.close).trim()
            val existing = if (inner.isEmpty()) "" else childIndent + inner + "\n"
            text.substring(0, block.open + 1) + "\n" + existing + payload + baseIndent + text.substring(block.close)
        }
        return parse(updated)
    }

    private fun existingChildIndent(block: Block): String? {
        val firstLine = body(block).split("\n").drop(1).firstOrNull { it.isNotBlank() && it.trim() != "}" } ?: return null
        val indent = firstLine.takeWhile { it == ' ' || it == '\t' }
        return indent.takeIf { it.length > indentOf(lineStart(block.open)).length }
    }

    private fun lineStart(offset: Int): Int = if (offset <= 0) 0 else text.lastIndexOf('\n', offset - 1) + 1

    private fun indentOf(lineStart: Int): String =
        text.substring(lineStart).takeWhile { it == ' ' || it == '\t' }

    /** True when the script declares `val <sourceSet> by getting` (with or without a block), making a local accessor. */
    fun declaresSourceSetVal(sourceSet: String): Boolean =
        Regex("""\bval\s+${Regex.escape(sourceSet)}\s*(:\s*\w+\s*)?by\s+(getting|creating)\b""").containsMatchIn(text)

    companion object {
        private const val INDENT = "    "

        private val ACCESSOR_PREFIXES = setOf(
            "common", "native", "apple", "ios", "macos", "tvos", "watchos", "linux", "mingw", "androidNative",
            "android", "jvm", "js", "wasmJs", "wasmWasi", "web",
            "iosArm64", "iosX64", "iosSimulatorArm64", "macosArm64", "macosX64", "linuxX64", "linuxArm64", "mingwX64",
            "tvosArm64", "tvosX64", "tvosSimulatorArm64", "watchosArm32", "watchosArm64", "watchosX64",
            "watchosSimulatorArm64", "watchosDeviceArm64",
        )

        /**
         * KGP generates `commonMain`-style accessors for the default hierarchy and preset target names only.
         * A custom target like `jvm("desktop")` gets no `desktopMain` accessor.
         */
        fun hasGeneratedAccessor(sourceSet: String): Boolean {
            val base = sourceSet.removeSuffix("Main").removeSuffix("Test")
            return base != sourceSet && base in ACCESSOR_PREFIXES
        }

        fun sourceSetHeader(header: String, sourceSet: String): Boolean {
            val s = Regex.escape(sourceSet)
            return Regex(
                """(sourceSets\.)?$s|val$s(:\w+)?by(getting|creating)|(sourceSets\.)?(getByName|named|maybeCreate)\("$s"\)""",
            ).matches(header.replace(" ", ""))
        }

        fun parse(text: String): KtsBuildScript {
            val root = Block("", -1, null)
            val stack = ArrayDeque<Block>().apply { addLast(root) }
            var i = 0
            val n = text.length
            while (i < n) {
                val c = text[i]
                when {
                    c == '/' && text.startsWith("//", i) -> i = text.indexOf('\n', i).let { if (it == -1) n else it }
                    c == '/' && text.startsWith("/*", i) -> i = text.indexOf("*/", i + 2).let { if (it == -1) n else it + 2 }
                    c == '"' && text.startsWith("\"\"\"", i) -> i = skipString(text, i + 3, raw = true)
                    c == '"' -> i = skipString(text, i + 1, raw = false)
                    c == '\'' -> i = skipChar(text, i + 1)
                    c == '{' -> {
                        val block = Block(headerBefore(text, i), i, stack.last())
                        stack.last().children += block
                        stack.addLast(block)
                        i++
                    }
                    c == '}' -> {
                        if (stack.size > 1) stack.removeLast().close = i
                        i++
                    }
                    else -> i++
                }
            }
            // Unbalanced input: close whatever is left at EOF so callers still get a usable tree.
            while (stack.size > 1) stack.removeLast().close = n
            root.close = n
            return KtsBuildScript(text, root)
        }

        private fun headerBefore(text: String, brace: Int): String {
            var j = brace - 1
            while (j >= 0 && text[j] !in "\n{};") j--
            return text.substring(j + 1, brace).trim().replace(Regex("\\s+"), " ")
        }

        /** Returns the offset just after the closing quote(s). */
        private fun skipString(text: String, from: Int, raw: Boolean): Int {
            var i = from
            while (i < text.length) {
                when {
                    !raw && text[i] == '\\' -> i += 2
                    text.startsWith("\${", i) -> i = skipTemplate(text, i + 2)
                    raw && text.startsWith("\"\"\"", i) -> {
                        var end = i + 3
                        while (end < text.length && text[end] == '"') end++ // """foo"""" ends at the last quote
                        return end
                    }
                    !raw && text[i] == '"' -> return i + 1
                    !raw && text[i] == '\n' -> return i // unterminated; recover at line end
                    else -> i++
                }
            }
            return i
        }

        private fun skipTemplate(text: String, from: Int): Int {
            var depth = 1
            var i = from
            while (i < text.length && depth > 0) {
                when (text[i]) {
                    '{' -> depth++
                    '}' -> depth--
                    '"' -> { i = skipString(text, i + 1, raw = false); continue }
                }
                i++
            }
            return i
        }

        private fun skipChar(text: String, from: Int): Int {
            var i = from
            while (i < text.length && text[i] != '\'' && text[i] != '\n') i += if (text[i] == '\\') 2 else 1
            return i + 1
        }
    }
}
