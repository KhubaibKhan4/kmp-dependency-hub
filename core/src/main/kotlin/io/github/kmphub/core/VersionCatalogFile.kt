package io.github.kmphub.core

/**
 * Line-based reader/editor for `libs.versions.toml`. It only appends entries, so the user's
 * formatting, ordering and comments stay exactly as they were.
 */
class VersionCatalogFile private constructor(private val lines: MutableList<String>) {

    data class LibraryEntry(val alias: String, val module: String?, val versionRef: String?, val version: String?)
    data class PluginEntry(val alias: String, val id: String?, val versionRef: String?, val version: String?)

    fun text(): String = lines.joinToString("\n")

    fun versions(): Map<String, String> =
        entries("versions").mapNotNull { (key, value) -> firstQuoted(value)?.let { key to it } }.toMap()

    fun libraries(): List<LibraryEntry> = entries("libraries").map { (alias, value) ->
        val trimmed = value.trim()
        if (trimmed.startsWith("\"")) {
            val parts = firstQuoted(trimmed).orEmpty().split(':')
            LibraryEntry(alias, parts.take(2).joinToString(":"), null, parts.getOrNull(2))
        } else {
            val module = field(trimmed, "module")
                ?: field(trimmed, "group")?.let { g -> field(trimmed, "name")?.let { "$g:$it" } }
            LibraryEntry(alias, module, field(trimmed, "version.ref"), field(trimmed, "version"))
        }
    }

    fun plugins(): List<PluginEntry> = entries("plugins").map { (alias, value) ->
        val trimmed = value.trim()
        if (trimmed.startsWith("\"")) {
            val parts = firstQuoted(trimmed).orEmpty().split(':')
            PluginEntry(alias, parts[0], null, parts.getOrNull(1))
        } else {
            PluginEntry(alias, field(trimmed, "id"), field(trimmed, "version.ref"), field(trimmed, "version"))
        }
    }

    fun resolvedVersion(entry: LibraryEntry): String? = entry.version ?: entry.versionRef?.let { versions()[it] }

    fun libraryByModule(module: String): LibraryEntry? = libraries().firstOrNull { it.module == module }

    fun pluginById(id: String): PluginEntry? = plugins().firstOrNull { it.id == id }

    fun hasKey(section: String, key: String): Boolean =
        entries(section).any { normalize(it.first) == normalize(key) }

    fun addVersion(key: String, version: String) = add("versions", "$key = \"$version\"")

    fun addLibrary(alias: String, module: String, versionRef: String?) = add(
        "libraries",
        if (versionRef == null) "$alias = { module = \"$module\" }"
        else "$alias = { module = \"$module\", version.ref = \"$versionRef\" }",
    )

    fun addPlugin(alias: String, id: String, versionRef: String) =
        add("plugins", "$alias = { id = \"$id\", version.ref = \"$versionRef\" }")

    private fun add(section: String, line: String) {
        val header = lines.indexOfFirst { sectionName(it) == section }
        if (header == -1) {
            createSection(section, line)
            return
        }
        // Insert after the last entry of the section, before trailing blank lines and the next header.
        var insertAt = header + 1
        var i = header + 1
        while (i < lines.size && sectionName(lines[i]) == null) {
            if (lines[i].isNotBlank() && !lines[i].trimStart().startsWith("#")) insertAt = i + 1
            i++
        }
        lines.add(insertAt, line)
    }

    private fun createSection(section: String, line: String) {
        val order = listOf("versions", "libraries", "bundles", "plugins")
        val later = order.drop(order.indexOf(section) + 1)
        val nextHeader = lines.indexOfFirst { sectionName(it) in later }
        if (nextHeader == -1) {
            while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
            if (lines.isNotEmpty()) lines += ""
            lines += listOf("[$section]", line, "")
        } else {
            lines.addAll(nextHeader, listOf("[$section]", line, ""))
        }
    }

    /** (key, raw value) pairs of a section. */
    private fun entries(section: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        var current: String? = null
        for (line in lines) {
            sectionName(line)?.let { current = it; continue }
            if (current != section) continue
            val match = ENTRY.matchEntire(line.substringBefore(" #")) ?: continue
            result += match.groupValues[1] to match.groupValues[2]
        }
        return result
    }

    companion object {
        const val DEFAULT_PATH = "gradle/libs.versions.toml"

        private val ENTRY = Regex("""\s*([A-Za-z0-9_.\-]+)\s*=\s*(.+?)\s*""")
        private val SECTION = Regex("""\s*\[([A-Za-z]+)]\s*(#.*)?""")

        fun parse(text: String) = VersionCatalogFile(text.split("\n").toMutableList())

        fun empty() = VersionCatalogFile(mutableListOf())

        /** Gradle treats `-`, `_` and `.` in aliases as the same separator. */
        fun normalize(alias: String) = alias.replace('-', '.').replace('_', '.')

        /** `ktor-client-core` -> `libs.ktor.client.core`; plugins live under `libs.plugins`. */
        fun accessor(alias: String, plugin: Boolean = false) =
            (if (plugin) "libs.plugins." else "libs.") + normalize(alias)

        private fun sectionName(line: String) = SECTION.matchEntire(line)?.groupValues?.get(1)

        private fun firstQuoted(value: String) = Regex("\"([^\"]*)\"").find(value)?.groupValues?.get(1)

        private fun field(table: String, name: String): String? =
            Regex("""(?:^|[{,\s])${Regex.escape(name)}\s*=\s*"([^"]*)"""").find(table)?.groupValues?.get(1)
    }
}
