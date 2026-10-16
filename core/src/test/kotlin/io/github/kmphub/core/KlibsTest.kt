package io.github.kmphub.core

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KlibsTest {
    /** Answers POSTs by URL; records request bodies. */
    private class FakeKlibs(val posts: Map<String, String>) : Http {
        val bodies = mutableListOf<Pair<String, String>>()
        override fun get(url: String): String? = null
        override fun postJson(url: String, body: String): String? { bodies += url to body; return posts[url] }
    }

    private val ktorProject = """{"id":1,"name":"ktor","description":"Framework for async servers and clients","scmLink":"https://github.com/ktorio/ktor",
        "scmStars":14546,"ownerLogin":"ktorio","latestReleaseVersion":"3.6.0","platforms":["androidJvm","common","js","jvm","native","wasm"],
        "targetGroups":{"AndroidJvm":["1.8"],"JVM":["1.8"],"IOS":["ios_arm64"],"MacOS":["macos_arm64"],"JavaScript":[],"Wasm":[]},"tags":["network","http"]}"""

    @Test
    fun `projects page parses stars, owner and platforms and sends the tag`() {
        val coil = """{"id":2,"name":"coil","description":"Image loading","scmLink":"https://github.com/coil-kt/coil","scmStars":11934,
            "ownerLogin":"coil-kt","platforms":["androidJvm","common","jvm","native"],"targetGroups":{"IOS":["ios_arm64"]},"tags":["image"]}"""
        val http = FakeKlibs(mapOf("${KlibsClient.API}/search/projects?page=1&limit=20" to "[$ktorProject,$coil]"))
        val projects = KlibsClient(http).projects(1, 20, tag = "network")!!
        assertEquals(listOf("ktor", "coil"), projects.map { it.name })
        assertEquals("ktorio/ktor", projects[0].key)
        assertEquals(14546, projects[0].stars)
        assertEquals(setOf(Platform.ANDROID, Platform.JVM, Platform.JS, Platform.WASM, Platform.IOS, Platform.NATIVE), projects[0].platforms)
        assertEquals(setOf(Platform.ANDROID, Platform.JVM, Platform.IOS), projects[1].platforms)
        assertContains(http.bodies.single().second, "\"tags\":[\"network\"]")
        assertContains(http.bodies.single().second, "\"targetGroupFilters\":[]")
    }

    @Test
    fun `packages are limited to the project's repository with the primary artifact first`() {
        val project = KlibsClient(FakeKlibs(mapOf("${KlibsClient.API}/search/projects?page=1&limit=1" to "[$ktorProject]"))).projects(1, 1)!!.single()
        fun pkg(g: String, a: String, owner: String = "ktorio") =
            """{"groupId":"$g","artifactId":"$a","ownerLogin":"$owner","scmLink":"https://github.com/$owner/$a","latestVersion":"3.6.0","platforms":["common","jvm","native"],"targetGroups":{"IOS":[]}}"""
        val page = listOf(
            pkg("io.ktor", "ktor-server-netty"), pkg("io.ktor", "ktor-client-core"), pkg("io.ktor", "ktor-client-core-jvm"),
            pkg("io.ktor", "ktor-kotlinMultiplatform"), pkg("io.ktor", "ktor-client-tests"),
            pkg("com.fork", "ktor-client-core", owner = "someone"),
        ).joinToString(",", "[", "]")
        val http = FakeKlibs(mapOf("${KlibsClient.API}/search/packages?page=1&limit=100" to page))
        val packages = KlibsClient(http).packages(project)!!
        assertEquals("io.ktor:ktor-client-core", packages.first().module)
        assertEquals(setOf("io.ktor:ktor-client-core", "io.ktor:ktor-server-netty", "io.ktor:ktor-client-tests"), packages.map { it.module }.toSet())
        assertTrue(packages.last().artifact.endsWith("-tests"), "test artifacts sink to the bottom")
        assertContains(http.bodies.single().second, "\"owner\":\"ktorio\"")
    }

    @Test
    fun `pager advances pages, stops when a page is short and reports failures`() {
        val two = "[$ktorProject,$ktorProject]"
        val http = FakeKlibs(mapOf(
            "${KlibsClient.API}/search/projects?page=1&limit=2" to two,
            "${KlibsClient.API}/search/projects?page=2&limit=2" to "[$ktorProject]",
        ))
        val pager = ProjectPager(KlibsClient(http), null, Categories.TOP, 2)
        assertEquals(2, pager.loadNext().size)
        assertEquals(1, pager.loadNext().size)
        assertTrue(pager.exhausted)
        assertEquals(0, pager.loadNext().size)
        assertEquals(2, http.bodies.size, "no request once exhausted")

        val down = ProjectPager(KlibsClient(FakeKlibs(emptyMap())), null, Categories.TOP, 50)
        assertEquals(0, down.loadNext().size)
        assertTrue(down.failed)
    }

    @Test
    fun `curated recipes are found by klibs key and every category has a tag`() {
        val catalog = LibraryCatalog.bundled()
        val project = KlibsProject(1, "ktor", "", "https://github.com/ktorio/ktor", 1, "ktorio", null, emptySet(), emptyList())
        assertEquals("ktor-client", catalog.forKlibs(project)?.id)
        assertNull(catalog.forKlibs(project.copy(owner = "someone")))
        assertTrue(Categories.all.drop(1).all { it.tag != null })
        assertEquals(Categories.all.size, Categories.all.map { it.name }.toSet().size)
    }

    @Test
    fun `name tokens drop generic words`() {
        assertEquals(listOf("coroutines"), KlibsClient.nameTokens("kotlinx.coroutines"))
        assertEquals(listOf("compose", "multiplatform"), KlibsClient.nameTokens("compose-multiplatform"))
        assertEquals(listOf("kermit"), KlibsClient.nameTokens("Kermit"))
    }

    @Test
    fun `primary score prefers the canonical artifact`() {
        val p = KlibsProject(1, "coil", "", "", 0, "coil-kt", null, emptySet(), emptyList())
        fun score(a: String) = KlibsClient.primaryScore(p, KlibsPackage("io.coil-kt.coil3", a, "", "1", setOf(Platform.JVM)))
        assertTrue(score("coil") > score("coil-compose"))
        assertTrue(score("coil-compose") > score("coil-network-ktor3"))
        assertTrue(score("coil-network-ktor3") > score("coil-test"))
    }
}
