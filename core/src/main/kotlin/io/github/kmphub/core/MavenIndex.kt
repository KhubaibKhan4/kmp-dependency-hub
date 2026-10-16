package io.github.kmphub.core

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/** GET a URL as text; null for 404 or any failure. The IDE passes its proxy-aware client, Gradle the JDK one. */
fun interface Http {
    fun get(url: String): String?

    /** POST a JSON body; null for non-200 or any failure. */
    fun postJson(url: String, body: String): String? = null
}

object JdkHttp : Http {
    /** `-Dkmphub.debugHttp=true` prints every request and failure to stderr. */
    private val debugEnabled = System.getProperty("kmphub.debugHttp") == "true"

    private inline fun debug(message: () -> String) {
        if (debugEnabled) System.err.println("[kmphub] " + message())
    }

    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    override fun get(url: String): String? = send(url, HttpRequest.newBuilder(URI(url)).GET())

    override fun postJson(url: String, body: String): String? = send(
        url,
        HttpRequest.newBuilder(URI(url)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)),
    )

    private fun send(url: String, builder: HttpRequest.Builder): String? {
        val request = builder
            .timeout(Duration.ofSeconds(30))
            .header("User-Agent", "kmp-dependency-hub")
            .build()
        // One retry for timeouts and 5xx; a 404 is a real answer.
        repeat(2) {
            val response = try {
                client.send(request, HttpResponse.BodyHandlers.ofString())
            } catch (e: java.io.IOException) {
                debug { "GET $url failed: $e" }
                null
            }
            debug { "GET $url -> ${response?.statusCode()}" }
            when {
                response == null || response.statusCode() >= 500 -> Unit
                response.statusCode() == 200 -> return response.body()
                else -> return null
            }
        }
        return null
    }
}

/** Search hits, plus the sources that didn't answer (so "nothing found" isn't confused with "couldn't ask"). */
data class SearchResult(val artifacts: List<RemoteArtifact>, val unavailable: List<String>)

/** An artifact found by live search. */
data class RemoteArtifact(val group: String, val name: String, val repository: String) {
    val module: String get() = "$group:$name"
}

/**
 * Live lookups against Maven Central, Google Maven and the Gradle Plugin Portal: latest stable versions,
 * full-text search, and which platforms an artifact publishes for (read from its Gradle module metadata).
 */
class MavenIndex(private val http: Http = JdkHttp) {

    private val googleGroups by lazy {
        http.get("$GOOGLE/master-index.xml")?.let { xml -> Regex("<([\\w.\\-]+)/>").findAll(xml).map { it.groupValues[1] }.toList() }.orEmpty()
    }
    private val platformCache = ConcurrentHashMap<String, Set<Platform>>()

    /** Newest stable version, or null when the artifact isn't found. Plugin ids resolve through their marker artifact. */
    fun latestStable(module: String): String? {
        val (group, name) = module.split(':').let { it[0] to it[1] }
        val path = "${group.replace('.', '/')}/$name/maven-metadata.xml"
        for (repo in reposFor(group)) {
            val xml = http.get("$repo/$path") ?: continue
            val stable = Regex("<version>([^<]+)</version>").findAll(xml).map { it.groupValues[1] }.filter(Versions::isStable).toList()
            if (stable.isNotEmpty()) return stable.maxWith(Comparator(Versions::compare))
        }
        return null
    }

    fun latestPluginVersion(id: String): String? = latestStable("$id:$id.gradle.plugin")

    /** Platforms the artifact publishes for, or null when its metadata can't be read. */
    fun platforms(module: String, version: String): Set<Platform>? {
        platformCache["$module:$version"]?.let { return it }
        val (group, name) = module.split(':').let { it[0] to it[1] }
        val base = "${group.replace('.', '/')}/$name/$version/$name-$version"
        for (repo in reposFor(group)) {
            val metadata = http.get("$repo/$base.module")
            val result = if (metadata != null) {
                platformsFromModuleMetadata(metadata)
            } else {
                val pom = http.get("$repo/$base.pom") ?: continue
                if (Regex("<packaging>\\s*aar\\s*</packaging>").containsMatchIn(pom)) setOf(Platform.ANDROID) else setOf(Platform.JVM)
            }
            return result.also { platformCache["$module:$version"] = it }
        }
        return null
    }

    /** Full-text search across Maven Central and Google Maven, without per-platform KMP artifacts. */
    fun search(query: String, limit: Int = 25): SearchResult {
        val q = query.trim().lowercase()
        if (q.length < 2) return SearchResult(emptyList(), emptyList())
        val unavailable = mutableListOf<String>()
        val results = LinkedHashMap<String, RemoteArtifact>()
        searchGoogle(q).forEach { results.putIfAbsent(it.module, it) }
        val central = searchCentral(q)
        if (central == null) unavailable += "Maven Central" else central.forEach { results.putIfAbsent(it.module, it) }
        if (googleGroups.isEmpty()) unavailable += "Google Maven"
        return SearchResult(results.values.filterNot { isPlatformArtifact(it.name) }.take(limit), unavailable)
    }

    /**
     * Null when Maven Central's search didn't answer. The Central Portal host answers in seconds; the legacy
     * search.maven.org host often takes 20s+ or times out for uncached queries, so it's only a fallback.
     */
    private fun searchCentral(q: String): List<RemoteArtifact>? {
        val encoded = URLEncoder.encode(q, "UTF-8")
        val docs = CENTRAL_SEARCH_HOSTS.firstNotNullOfOrNull { host ->
            val response = http.get("$host/solrsearch/select?q=$encoded&rows=80&wt=json")
                ?.let { runCatching { Json.parse(it) }.getOrNull() } as? Map<*, *>
            (response?.get("response") as? Map<*, *>)?.get("docs") as? List<*>
        } ?: return null
        val artifacts = docs.mapNotNull { doc ->
            val d = doc as? Map<*, *> ?: return@mapNotNull null
            val g = d["g"] as? String ?: return@mapNotNull null
            val a = d["a"] as? String ?: return@mapNotNull null
            RemoteArtifact(g, a, "Maven Central")
        }
        // The official publisher usually has the most matching artifacts (app.cash.sqldelight vs. forks).
        val groupSize = artifacts.filterNot { isPlatformArtifact(it.name) }.groupingBy { it.group }.eachCount()
        return artifacts.withIndex().sortedWith(
            compareByDescending<IndexedValue<RemoteArtifact>> { relevance(q, it.value.group, it.value.name) }
                .thenByDescending { groupSize[it.value.group] ?: 0 }
                .thenBy { it.value.group.count { c -> c == '.' } } // canonical groups beat re-publishes like org.wso2.orbit.com.squareup.okio
                .thenBy { it.index },
        ).map { it.value }
    }

    private fun searchGoogle(q: String): List<RemoteArtifact> {
        val tokens = q.split(Regex("[\\s:.\\-]+")).filter { it.length >= 2 }
        val groups = googleGroups.filter { g -> tokens.any { t -> g.split('.').any { it == t } } }
            .sortedBy { it.length }
            .take(4)
        return groups.flatMap { group ->
            val xml = http.get("$GOOGLE/${group.replace('.', '/')}/group-index.xml") ?: return@flatMap emptyList()
            Regex("<([\\w.\\-]+) versions=\"[^\"]*\"/>").findAll(xml).map { RemoteArtifact(group, it.groupValues[1], "Google Maven") }.toList()
        }.sortedByDescending { relevance(q, it.group, it.name) }
    }

    private fun relevance(q: String, group: String, name: String): Int = when {
        name == q -> 100
        name.startsWith(q) -> 60
        "$group:$name".contains(q) -> 30
        else -> 0
    } + (if (group.split('.').any { it == q }) 20 else 0) + if (group.startsWith("org.jetbrains") || group.startsWith("androidx") || group.startsWith("io.ktor")) 5 else 0

    private fun reposFor(group: String): List<String> =
        if (GOOGLE_PREFIXES.any { group.startsWith(it) }) listOf(GOOGLE, CENTRAL, PLUGIN_PORTAL) else listOf(CENTRAL, GOOGLE, PLUGIN_PORTAL)

    companion object {
        const val CENTRAL = "https://repo1.maven.org/maven2"
        const val GOOGLE = "https://dl.google.com/dl/android/maven2"
        const val PLUGIN_PORTAL = "https://plugins.gradle.org/m2"
        val CENTRAL_SEARCH_HOSTS = listOf("https://central.sonatype.com", "https://search.maven.org")
        private val GOOGLE_PREFIXES = listOf("androidx.", "com.android.", "com.google.android.", "com.google.firebase", "com.google.gms")

        private val PLATFORM_SUFFIX = Regex(
            "-(jvm|android|androidnative\\w*|ios\\w*|macos\\w*|tvos\\w*|watchos\\w*|linux\\w*|mingw\\w*|js|wasm-js|wasm-wasi|wasm|desktop|metadata|native)$",
        )

        fun isPlatformArtifact(name: String) = PLATFORM_SUFFIX.containsMatchIn(name)

        /** Reads the `variants` of a Gradle module metadata file into platforms. */
        fun platformsFromModuleMetadata(json: String): Set<Platform> {
            val root = runCatching { Json.parse(json) }.getOrNull() as? Map<*, *> ?: return emptySet()
            val variants = (root["variants"] as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.get("attributes") as? Map<*, *> }
            return variants.filter { it["org.gradle.category"] == null || it["org.gradle.category"] == "library" }.mapNotNull { attrs ->
                when (attrs["org.jetbrains.kotlin.platform.type"]) {
                    "common" -> null
                    "androidJvm" -> Platform.ANDROID
                    "jvm" -> if (attrs["org.gradle.jvm.environment"] == "android") Platform.ANDROID else Platform.JVM
                    "js" -> Platform.JS
                    "wasm" -> if (attrs["org.jetbrains.kotlin.wasm.target"] == "wasi") null else Platform.WASM
                    "native" -> {
                        val target = attrs["org.jetbrains.kotlin.native.target"] as? String ?: ""
                        when {
                            target.startsWith("ios_") -> Platform.IOS
                            target.startsWith("android_") -> null // Android native, not the Android JVM target
                            else -> Platform.NATIVE
                        }
                    }
                    null -> when (attrs["org.gradle.libraryelements"]) {
                        "aar" -> Platform.ANDROID
                        "jar", "classes" -> Platform.JVM
                        else -> if (attrs["org.gradle.jvm.environment"] == "android") Platform.ANDROID else null
                    }
                    else -> null
                }
            }.toSet()
        }
    }
}
