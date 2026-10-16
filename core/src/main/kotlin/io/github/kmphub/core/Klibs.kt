package io.github.kmphub.core

/** A Kotlin Multiplatform project listed on klibs.io (one GitHub repo, usually several artifacts). */
data class KlibsProject(
    val id: Long,
    val name: String,
    val description: String,
    val scmLink: String,
    val stars: Int,
    val owner: String,
    val latestRelease: String?,
    val platforms: Set<Platform>,
    val tags: List<String>,
) {
    /** `ktorio/ktor`; matches [Library.klibs] on curated recipes. */
    val key: String get() = "${owner.lowercase()}/${name.lowercase()}"
}

/** One published artifact of a klibs.io project. */
data class KlibsPackage(
    val group: String,
    val artifact: String,
    val description: String,
    val version: String?,
    val platforms: Set<Platform>,
) {
    val module: String get() = "$group:$artifact"
}

/** A browse category, backed by a klibs.io tag; [tag] null means "everything, by stars". */
data class Category(val name: String, val tag: String?)

object Categories {
    val TOP = Category("Top libraries", null)

    /** Each tag has 40+ projects on klibs.io, so every category fills its first page of 20. */
    val all: List<Category> = listOf(
        TOP,
        Category("Networking", "network"),
        Category("UI components", "compose-ui"),
        Category("Design systems", "design-system"),
        Category("Navigation", "navigation"),
        Category("State & architecture", "state-management"),
        Category("Dependency injection", "dependency-injection"),
        Category("Database", "database"),
        Category("Storage & preferences", "storage"),
        Category("Serialization", "serialization"),
        Category("Coroutines & Flow", "kotlin-coroutines"),
        Category("Images", "image"),
        Category("Animation", "animation"),
        Category("Logging", "logging"),
        Category("Testing", "test"),
        Category("Authentication", "authentication"),
        Category("Cryptography", "cryptography"),
        Category("Date & time", "date-time"),
        Category("Localization", "localization"),
        Category("Charts", "charts"),
        Category("Audio & media", "audio"),
        Category("Analytics", "analytics"),
        Category("AI & LLM", "ai"),
    )
}

/**
 * Client for the public klibs.io API (the JetBrains-run index of KMP libraries): ranked, tag-filtered,
 * paginated project search, and the artifacts that belong to each project.
 */
class KlibsClient(private val http: Http = JdkHttp) {

    /** One page of projects, most-starred first when there's no query; null when klibs.io didn't answer. */
    fun projects(page: Int, limit: Int, query: String? = null, tag: String? = null): List<KlibsProject>? {
        val body = buildString {
            append("{\"sortBy\":\"relevance\",\"tags\":[")
            if (tag != null) append(quote(tag))
            append("],\"markers\":[],\"targetGroupFilters\":[]")
            if (!query.isNullOrBlank()) append(",\"query\":").append(quote(query.trim()))
            append('}')
        }
        val json = http.postJson("$API/search/projects?page=$page&limit=$limit", body) ?: return null
        val list = runCatching { Json.parse(json) }.getOrNull() as? List<*> ?: return null
        return list.mapNotNull { (it as? Map<*, *>)?.toProject() }
    }

    /** Project count per tag, plus `""` for all projects; null when klibs.io didn't answer. */
    fun tagCounts(): Map<String, Int>? {
        val root = http.get("$API/tags/stats?limit=500")?.let { runCatching { Json.parse(it) }.getOrNull() } as? Map<*, *> ?: return null
        val counts = (root["tags"] as? List<*>).orEmpty().mapNotNull { t ->
            val m = t as? Map<*, *> ?: return@mapNotNull null
            (m["tag"] as? String ?: return@mapNotNull null) to ((m["projectsCount"] as? Number)?.toInt() ?: 0)
        }.toMap()
        return counts + ("" to ((root["totalProjectsCount"] as? Number)?.toInt() ?: 0))
    }

    /** Artifacts published from [project]'s repository, without per-platform variants; null on failure. */
    fun packages(project: KlibsProject): List<KlibsPackage>? {
        val result = LinkedHashMap<String, KlibsPackage>()
        for (page in 1..3) {
            val body = "{\"sortBy\":\"relevance\",\"tags\":[],\"markers\":[],\"targetGroupFilters\":[]," +
                "\"query\":${quote(project.name)},\"owner\":${quote(project.owner)}}"
            val json = http.postJson("$API/search/packages?page=$page&limit=100", body) ?: return if (page == 1) null else result.values.toList()
            val list = runCatching { Json.parse(json) }.getOrNull() as? List<*> ?: break
            // A package's scmLink is whatever its POM says (often a made-up per-artifact URL), so match on the
            // owner plus the project's name tokens in the coordinates instead.
            val tokens = nameTokens(project.name)
            list.mapNotNull { it as? Map<*, *> }
                .filter { (it["ownerLogin"] as? String)?.equals(project.owner, ignoreCase = true) ?: true }
                .mapNotNull { it.toPackage() }
                .filter { pkg -> tokens.isEmpty() || tokens.any { it in pkg.module.lowercase() } }
                .filterNot { MavenIndex.isPlatformArtifact(it.artifact) || it.artifact.endsWith("-kotlinMultiplatform") }
                .forEach { result.putIfAbsent(it.module, it) }
            if (list.size < 100) break
        }
        return result.values.sortedWith(compareByDescending<KlibsPackage> { primaryScore(project, it) }.thenBy { it.artifact })
    }

    companion object {
        const val API = "https://api.klibs.io"

        /** How likely an artifact is the one people mean by the project name (ktor -> ktor-client-core, coil -> coil-compose). */
        fun primaryScore(project: KlibsProject, pkg: KlibsPackage): Int {
            val name = project.name.lowercase().removePrefix("kotlinx.").removePrefix("kotlinx-").replace('.', '-')
            val a = pkg.artifact.lowercase()
            var score = when {
                a == name || a == "kotlinx-$name" -> 100
                a == "$name-core" || a == "$name-compose" || a == "$name-client-core" -> 90
                a.startsWith(name) && a.endsWith("-core") -> 80
                a.startsWith(name) -> 60 - (a.length - name.length).coerceAtMost(40)
                name in a -> 30
                else -> 0
            }
            if (Regex("-(test|tests|testing|bom|sample|samples|benchmark|benchmarks|android|jvm|lint|debug|gradle-plugin|plugin)$").containsMatchIn(a)) score -= 70
            if (pkg.platforms.size >= 3) score += 5
            return score
        }

        private fun Map<*, *>.toProject(): KlibsProject? {
            return KlibsProject(
                id = (this["id"] as? Number)?.toLong() ?: return null,
                name = this["name"] as? String ?: return null,
                description = this["description"] as? String ?: "",
                scmLink = this["scmLink"] as? String ?: "",
                stars = (this["scmStars"] as? Number)?.toInt() ?: 0,
                owner = this["ownerLogin"] as? String ?: "",
                latestRelease = this["latestReleaseVersion"] as? String,
                platforms = platforms(this),
                tags = (this["tags"] as? List<*>).orEmpty().map { it.toString() },
            )
        }

        private fun Map<*, *>.toPackage(): KlibsPackage? {
            val group = this["groupId"] as? String ?: return null
            val artifact = this["artifactId"] as? String ?: return null
            return KlibsPackage(group, artifact, this["description"] as? String ?: "", this["latestVersion"] as? String, platforms(this))
        }

        /** `platforms: ["androidJvm","native",...]` plus `targetGroups: {"IOS": [...]}` -> our platforms. */
        private fun platforms(item: Map<*, *>): Set<Platform> {
            val names = (item["platforms"] as? List<*>).orEmpty().map { it.toString() }
            val groups = (item["targetGroups"] as? Map<*, *>).orEmpty().keys.map { it.toString().lowercase() }
            return buildSet {
                if ("androidJvm" in names || "androidjvm" in groups) add(Platform.ANDROID)
                if ("jvm" in names) add(Platform.JVM)
                if ("js" in names || "javascript" in groups) add(Platform.JS)
                if ("wasm" in names) add(Platform.WASM)
                if ("ios" in groups) add(Platform.IOS)
                if ("native" in names && groups.any { it in setOf("macos", "linux", "windows", "mingw", "tvos", "watchos", "androidnative") }) add(Platform.NATIVE)
            }
        }

        /** `kotlinx.coroutines` -> [coroutines]; `compose-multiplatform` -> [compose]; `Kermit` -> [kermit]. */
        fun nameTokens(name: String): List<String> {
            val all = name.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }
            val generic = setOf("kotlin", "kotlinx", "kmp", "multiplatform", "android", "compose", "library", "sdk", "lib")
            return all.filterNot { it in generic }.ifEmpty { all }
        }

        private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}

/** Loads klibs.io projects page by page for one query/category. Not thread-safe; drive it from one thread. */
class ProjectPager(
    private val client: KlibsClient,
    val query: String?,
    val category: Category,
    val pageSize: Int,
) {
    private var nextPage = 1
    var exhausted = false
        private set
    var failed = false
        private set

    fun loadNext(): List<KlibsProject> {
        if (exhausted) return emptyList()
        val page = client.projects(nextPage, pageSize, query, category.tag)
        if (page == null) {
            failed = true
            return emptyList()
        }
        failed = false
        nextPage++
        if (page.size < pageSize) exhausted = true
        return page
    }
}

/** Recipe for one klibs.io artifact; the version is re-checked on Maven at install time. */
fun KlibsPackage.toLibrary(index: MavenIndex? = null): Library? {
    val version = index?.latestStable(module) ?: version ?: return null
    val platforms = platforms.ifEmpty { index?.platforms(module, version).orEmpty() }.ifEmpty { setOf(Platform.JVM) }
    return RemoteRecipes.library(RemoteArtifact(group, artifact, "klibs.io"), version, platforms)
        .copy(description = description.ifBlank { "Kotlin Multiplatform library from klibs.io." })
}
