package io.github.kmphub.core

/** The curated set of libraries and their install recipes. */
class LibraryCatalog(val libraries: List<Library>) {

    private val byId = libraries.associateBy { it.id }
    private val byModule = libraries.flatMap { lib -> lib.artifacts.map { it.module to lib } }.toMap()

    val categories: List<String> get() = libraries.map { it.category }.distinct().sorted()

    operator fun get(id: String): Library? = byId[id]

    fun libraryForModule(module: String): Library? = byModule[module]

    private val byKlibs = libraries.filter { it.klibs != null }.associateBy { it.klibs!! }

    /** The curated recipe for a klibs.io project, if there is one. */
    fun forKlibs(project: KlibsProject): Library? = byKlibs[project.key]

    fun search(query: String = "", category: String? = null): List<Library> {
        val q = query.trim().lowercase()
        return libraries.filter { lib ->
            (category == null || lib.category == category) &&
                (q.isEmpty() || listOf(lib.id, lib.name, lib.description, lib.category).any { q in it.lowercase() } ||
                    lib.artifacts.any { q in it.module })
        }
    }

    companion object {
        private const val RESOURCE = "/kmphub/catalog.json"

        /** The catalog shipped inside the engine jar. */
        fun bundled(): LibraryCatalog {
            val stream = LibraryCatalog::class.java.getResourceAsStream(RESOURCE)
                ?: error("Bundled catalog $RESOURCE is missing")
            return parse(stream.bufferedReader().use { it.readText() })
        }

        fun parse(json: String): LibraryCatalog {
            val root = Json.parse(json).obj()
            return LibraryCatalog(root.list("libraries").map { it.obj().toLibrary() })
        }

        private fun Map<String, Any?>.toLibrary(): Library {
            val versions = (this["versions"] as? Map<*, *>).orEmpty()
                .entries.associate { (k, v) -> k.toString() to v.toString() }
            val library = Library(
                id = str("id"),
                name = str("name"),
                category = str("category"),
                description = str("description"),
                website = this["website"] as? String,
                platforms = list("platforms").map { Platform.valueOf(it.toString()) }.toSet(),
                versions = versions,
                artifacts = list("artifacts").map { a ->
                    a.obj().run {
                        Artifact(
                            alias = str("alias"),
                            module = str("module"),
                            versionRef = this["versionRef"] as? String,
                            placement = Placement.of(this["on"] as? String ?: "common"),
                            configuration = this["configuration"] as? String ?: "implementation",
                            bom = this["bom"] as? Boolean ?: false,
                        )
                    }
                },
                plugins = (this["plugins"] as? List<*>).orEmpty().map { p ->
                    p.obj().run { PluginSpec(str("alias"), str("id"), str("versionRef")) }
                },
                blocks = (this["blocks"] as? List<*>).orEmpty().map { b ->
                    b.obj().run { ConfigBlock(str("header"), list("lines").map { it.toString() }) }
                },
                notes = (this["notes"] as? List<*>).orEmpty().map { it.toString() },
                klibs = (this["klibs"] as? String)?.lowercase(),
            )
            val refs = library.artifacts.mapNotNull { it.versionRef } + library.plugins.map { it.versionRef }
            val missing = refs.filter { it !in versions }
            require(missing.isEmpty()) { "Library '${library.id}' references undefined versions $missing" }
            return library
        }

        @Suppress("UNCHECKED_CAST")
        private fun Any?.obj(): Map<String, Any?> = this as? Map<String, Any?> ?: error("Expected an object, got $this")

        private fun Map<String, Any?>.str(key: String): String = this[key] as? String ?: error("Missing '$key' in $this")

        private fun Map<String, Any?>.list(key: String): List<Any?> = this[key] as? List<Any?> ?: error("Missing '$key' in $this")
    }
}
