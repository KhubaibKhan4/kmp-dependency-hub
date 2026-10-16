package io.github.kmphub.core

import io.github.kmphub.core.Fixtures.apply
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveCatalogTest {
    private val central = MavenIndex.CENTRAL
    private val google = MavenIndex.GOOGLE

    private fun metadata(vararg versions: String) =
        "<metadata><versioning>${versions.joinToString("") { "<version>$it</version>" }}</versioning></metadata>"

    private class FakeHttp(val responses: Map<String, String>) : Http {
        val requested = mutableListOf<String>()
        override fun get(url: String): String? = synchronized(this) { requested += url; responses[url] }
    }

    @Test
    fun `latest stable ignores pre-releases and compat builds`() {
        val http = FakeHttp(mapOf(
            "$central/io/ktor/ktor-client-core/maven-metadata.xml" to metadata("3.2.0", "3.10.0", "3.11.0-beta1", "3.9.9"),
            "$central/org/jetbrains/kotlinx/kotlinx-datetime/maven-metadata.xml" to metadata("0.7.1", "0.8.0-0.6.x-compat", "0.8.0-RC"),
        ))
        val index = MavenIndex(http)
        assertEquals("3.10.0", index.latestStable("io.ktor:ktor-client-core"))
        assertEquals("0.7.1", index.latestStable("org.jetbrains.kotlinx:kotlinx-datetime"))
        assertNull(index.latestStable("com.example:missing"))
    }

    @Test
    fun `androidx groups are looked up on Google Maven first`() {
        val http = FakeHttp(mapOf("$google/androidx/room/room-runtime/maven-metadata.xml" to metadata("2.8.5")))
        assertEquals("2.8.5", MavenIndex(http).latestStable("androidx.room:room-runtime"))
        assertTrue(http.requested.first().startsWith(google))
    }

    @Test
    fun `platforms come from module metadata, or the POM packaging`() {
        val kmp = """{"variants":[
            {"attributes":{"org.gradle.category":"library","org.jetbrains.kotlin.platform.type":"common"}},
            {"attributes":{"org.gradle.category":"library","org.jetbrains.kotlin.platform.type":"jvm","org.gradle.jvm.environment":"android"}},
            {"attributes":{"org.gradle.category":"library","org.jetbrains.kotlin.platform.type":"jvm","org.gradle.jvm.environment":"standard-jvm"}},
            {"attributes":{"org.gradle.category":"library","org.jetbrains.kotlin.platform.type":"native","org.jetbrains.kotlin.native.target":"ios_arm64"}},
            {"attributes":{"org.gradle.category":"library","org.jetbrains.kotlin.platform.type":"native","org.jetbrains.kotlin.native.target":"android_arm64"}},
            {"attributes":{"org.gradle.category":"library","org.jetbrains.kotlin.platform.type":"wasm","org.jetbrains.kotlin.wasm.target":"js"}},
            {"attributes":{"org.gradle.category":"documentation","org.gradle.usage":"sources"}}
        ]}"""
        assertEquals(setOf(Platform.ANDROID, Platform.JVM, Platform.IOS, Platform.WASM), MavenIndex.platformsFromModuleMetadata(kmp))

        val aar = """{"variants":[{"attributes":{"org.gradle.category":"library","org.gradle.libraryelements":"aar","org.gradle.usage":"java-api"}}]}"""
        assertEquals(setOf(Platform.ANDROID), MavenIndex.platformsFromModuleMetadata(aar))

        val http = FakeHttp(mapOf(
            "$central/com/google/dagger/hilt-android/2.60.1/hilt-android-2.60.1.pom" to "<project><packaging>aar</packaging></project>",
            "$central/com/example/plain/1.0/plain-1.0.pom" to "<project></project>",
        ))
        val index = MavenIndex(http)
        assertEquals(setOf(Platform.ANDROID), index.platforms("com.google.dagger:hilt-android", "2.60.1"))
        assertEquals(setOf(Platform.JVM), index.platforms("com.example:plain", "1.0"))
    }

    @Test
    fun `search merges Google and Central and drops per-platform artifacts`() {
        val solr = """{"response":{"numFound":4,"docs":[
            {"g":"io.ktor","a":"ktor-client-core","versionCount":120},
            {"g":"io.ktor","a":"ktor-client-core-jvm","versionCount":120},
            {"g":"io.ktor","a":"ktor-client-core-iosarm64","versionCount":80},
            {"g":"com.example","a":"room-helper","versionCount":2}
        ]}}"""
        val http = FakeHttp(mapOf(
            "https://central.sonatype.com/solrsearch/select?q=room&rows=80&wt=json" to solr,
            "$google/master-index.xml" to "<metadata><androidx.room/><androidx.core/></metadata>",
            "$google/androidx/room/group-index.xml" to "<androidx.room><room-runtime versions=\"2.8.5\"/><room-compiler versions=\"2.8.5\"/></androidx.room>",
        ))
        val search = MavenIndex(http).search("room")
        assertEquals(emptyList(), search.unavailable)
        val results = search.artifacts.map { it.module }
        assertEquals(listOf("androidx.room:room-runtime", "androidx.room:room-compiler"), results.take(2))
        assertTrue("io.ktor:ktor-client-core" in results)
        assertTrue(results.none { it.endsWith("-jvm") || it.endsWith("-iosarm64") })
    }

    @Test
    fun `falls back to search maven org and ranks canonical groups first`() {
        val solr = """{"response":{"docs":[
            {"g":"org.wso2.orbit.com.squareup.okio","a":"okio"},
            {"g":"com.squareup.okio","a":"benchmarks"},
            {"g":"com.squareup.okio","a":"okio"}
        ]}}"""
        val http = FakeHttp(mapOf("https://search.maven.org/solrsearch/select?q=okio&rows=80&wt=json" to solr))
        val result = MavenIndex(http).search("okio")
        assertEquals(listOf("com.squareup.okio:okio", "org.wso2.orbit.com.squareup.okio:okio", "com.squareup.okio:benchmarks"), result.artifacts.map { it.module })
        assertTrue(http.requested.indexOfFirst { "central.sonatype.com" in it } < http.requested.indexOfFirst { "search.maven.org" in it })
        assertEquals(listOf("Google Maven"), result.unavailable)
    }

    @Test
    fun `search reports sources that did not answer`() {
        val result = MavenIndex { null }.search("ktor")
        assertEquals(emptyList(), result.artifacts)
        assertEquals(listOf("Maven Central", "Google Maven"), result.unavailable)
    }

    @Test
    fun `live catalog refreshes versions, caches them and falls back offline`() {
        val bundled = LibraryCatalog.bundled()
        val dir = Files.createTempDirectory("kmphub").toFile()
        val cache = File(dir, "versions.json")
        val http = FakeHttp(mapOf("$central/io/ktor/ktor-client-core/maven-metadata.xml" to metadata("99.1.0")))
        var now = 1_000_000L
        val live = LiveCatalog(bundled, MavenIndex(http), cache, maxAgeMillis = 1000, clock = { now })

        val first = live.load()
        assertEquals("99.1.0", first.catalog["ktor-client"]!!.versions["ktor"])
        assertEquals(bundled["koin"]!!.versions, first.catalog["koin"]!!.versions, "unresolved refs keep bundled versions")
        assertEquals(now, first.fetchedAt)
        assertContains(cache.readText(), "\"io.ktor:ktor-client-core\": \"99.1.0\"")

        val calls = http.requested.size
        now += 500
        assertEquals("99.1.0", live.load().catalog["ktor-client"]!!.versions["ktor"])
        assertEquals(calls, http.requested.size, "fresh cache means no network")

        // Network gone and cache stale: keep cached versions rather than reverting to bundled.
        now += 5000
        val offline = LiveCatalog(bundled, MavenIndex { null }, cache, maxAgeMillis = 1000, clock = { now }).load()
        assertEquals("99.1.0", offline.catalog["ktor-client"]!!.versions["ktor"])
        assertEquals(1_000_000L, offline.fetchedAt)
    }

    @Test
    fun `any searched artifact installs with the platform check applied`() {
        val http = FakeHttp(mapOf(
            "$central/com/squareup/okio/okio/maven-metadata.xml" to metadata("3.16.0"),
            "$central/com/squareup/okio/okio/3.16.0/okio-3.16.0.module" to """{"variants":[
                {"attributes":{"org.jetbrains.kotlin.platform.type":"common"}},
                {"attributes":{"org.jetbrains.kotlin.platform.type":"jvm"}},
                {"attributes":{"org.jetbrains.kotlin.platform.type":"native","org.jetbrains.kotlin.native.target":"ios_arm64"}}]}""",
            "$central/com/example/jvmonly/maven-metadata.xml" to metadata("1.0"),
            "$central/com/example/jvmonly/1.0/jvmonly-1.0.pom" to "<project/>",
        ))
        val index = MavenIndex(http)
        val okio = RemoteRecipes.resolve(index, "com.squareup.okio:okio")!!
        assertEquals("okio", okio.artifacts.single().alias)
        val jvmOnly = RemoteRecipes.resolve(index, "com.example:jvmonly")!!
        assertEquals("example-jvmonly", jvmOnly.artifacts.single().alias)

        val module = ModuleInfo(":shared", "shared/build.gradle.kts", ModuleKind.KMP,
            listOf(Target("jvm", Platform.JVM), Target("iosArm64", Platform.IOS)))
        val files = Fixtures.files(
            "shared/build.gradle.kts" to "kotlin {\n    jvm()\n    iosArm64()\n    sourceSets {\n        commonMain.dependencies {\n        }\n    }\n}\n",
            "gradle/libs.versions.toml" to Fixtures.catalog,
        )
        val installer = Installer(LibraryCatalog(listOf(okio, jvmOnly)))
        val ok = installer.install(InstallRequest(listOf(okio.id), module), files::get)
        files.apply(ok)
        assertContains(files.getValue("shared/build.gradle.kts"), "implementation(libs.okio)")
        assertContains(files.getValue("gradle/libs.versions.toml"), "okio = \"3.16.0\"")

        val refused = installer.install(InstallRequest(listOf(jvmOnly.id), module), files::get)
        assertTrue(refused.hasErrors)
        assertTrue(refused.messages.any { it.text.contains("doesn't publish for ios") })
    }
}
