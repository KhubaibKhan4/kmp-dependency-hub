package io.github.kmphub.idea

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowEP
import com.intellij.testFramework.HeavyPlatformTestCase
import io.github.kmphub.core.InstallRequest
import io.github.kmphub.core.Installer
import io.github.kmphub.core.LibraryCatalog
import io.github.kmphub.core.ModuleKind
import io.github.kmphub.core.ProjectScanner
import java.io.File

/** Runs inside a real (headless) IDE with the plugin loaded, on a project that exists on disk. */
class HubPluginTest : HeavyPlatformTestCase() {

    private lateinit var root: File

    override fun setUp() {
        super.setUp()
        root = File(project.basePath!!)
        write("settings.gradle.kts", "rootProject.name = \"Sample\"\ninclude(\":composeApp\")\n")
        write("gradle/libs.versions.toml", """
            [versions]
            kotlin = "2.2.20"

            [plugins]
            kotlinMultiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
        """.trimIndent() + "\n")
        write("build.gradle.kts", "plugins {\n    alias(libs.plugins.kotlinMultiplatform) apply false\n}\n")
        write("composeApp/build.gradle.kts", """
            plugins {
                alias(libs.plugins.kotlinMultiplatform)
            }

            kotlin {
                androidTarget()
                iosArm64()
                iosSimulatorArm64()
                jvm("desktop")

                sourceSets {
                    commonMain.dependencies {
                    }
                }
            }
        """.trimIndent() + "\n")
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(root)!!.refresh(false, true)
    }

    private fun write(path: String, text: String) = File(root, path).apply { parentFile.mkdirs() }.writeText(text)

    fun `test plugin registers tool window and doctor action`() {
        assertNotNull(ActionManager.getInstance().getAction("KmpHub.Doctor"))
        assertTrue(ToolWindowEP.EP_NAME.extensionList.any { it.id == "KMP Hub" && it.factoryClass == HubToolWindowFactory::class.java.name })
    }

    fun `test scan sees the KMP module and its targets`() {
        val modules = readAction { ProjectScanner.scan(IdeProjectFiles(project)) }
        val app = modules.single { it.path == ":composeApp" }
        assertEquals(ModuleKind.KMP, app.kind)
        assertEquals(listOf("android", "iosArm64", "iosSimulatorArm64", "desktop"), app.targets.map { it.name })
    }

    fun `test install writes catalog, build files and per-platform engines`() {
        val files = IdeProjectFiles(project)
        val module = readAction { ProjectScanner.scan(files) }.single { it.path == ":composeApp" }
        val result = readAction { Installer(LibraryCatalog.bundled()).install(InstallRequest(listOf("ktor-client", "koin"), module), files) }
        assertFalse(result.messages.toString(), result.hasErrors)
        assertEquals(3, result.changes.size)

        ChangeWriter.apply(project, result.changes)

        val build = File(root, "composeApp/build.gradle.kts").readText()
        assertTrue(build, build.contains("implementation(libs.ktor.client.core)"))
        assertTrue(build, build.contains("iosMain.dependencies {\n            implementation(libs.ktor.client.darwin)"))
        assertTrue(build, build.contains("androidMain.dependencies {\n            implementation(libs.ktor.client.okhttp)"))
        assertTrue(build, build.contains("implementation(project.dependencies.platform(libs.koin.bom))"))
        assertTrue(File(root, "build.gradle.kts").readText().contains("alias(libs.plugins.kotlinx.serialization) apply false"))
        assertTrue(File(root, "gradle/libs.versions.toml").readText().contains("ktor-client-core = { module = \"io.ktor:ktor-client-core\""))
    }

    fun `test unsaved editor text is what gets edited`() {
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(root, "composeApp/build.gradle.kts"))!!
        val document = FileDocumentManager.getInstance().getDocument(vf)!!
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            document.setText(document.text.replace("commonMain.dependencies {\n", "commonMain.dependencies {\n            implementation(\"unsaved:edit:1.0\")\n"))
        }
        assertTrue(IdeProjectFiles(project).read("composeApp/build.gradle.kts")!!.contains("unsaved:edit"))
        assertFalse(File(root, "composeApp/build.gradle.kts").readText().contains("unsaved:edit"))
    }

    /** Needs the network: proves the IDE's HTTP stack reaches Maven Central and Google Maven. */
    fun `test live lookups work through the IDE http client`() {
        val index = io.github.kmphub.core.MavenIndex(IdeHttp)
        val ktor = index.latestStable("io.ktor:ktor-client-core")
        assertNotNull("Maven Central unreachable", ktor)
        assertNotNull("Google Maven unreachable", index.latestStable("androidx.room:room-runtime"))
        val platforms = index.platforms("io.ktor:ktor-client-core", ktor!!)!!
        assertTrue(platforms.toString(), platforms.containsAll(listOf(io.github.kmphub.core.Platform.IOS, io.github.kmphub.core.Platform.JVM)))
    }

    fun `test central search through the IDE http client`() {
        val result = io.github.kmphub.core.MavenIndex(IdeHttp).search("okio", 5)
        println("IDE search unavailable=${result.unavailable} artifacts=${result.artifacts.map { it.module }}")
        assertTrue(result.artifacts.any { it.module == "com.squareup.okio:okio" })
    }

    private fun waitFor(timeoutMs: Long = 60_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(100)
        }
        com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        return condition()
    }

    /** Needs the network: the panel's default list, paging, categories, search and selection against live klibs.io. */
    fun `test panel loads top 50, pages on scroll, 20 per category and searches`() {
        val panel = HubPanel(project, testRootDisposable, goOnline = true)

        assertTrue("top page: ${panel.projectCount}", waitFor { panel.projectCount >= 50 && !panel.isLoading })
        assertEquals(50, panel.projectCount)
        panel.requestMore()
        assertTrue("second page: ${panel.projectCount}", waitFor { panel.projectCount >= 100 && !panel.isLoading })
        println("PANEL top pages ok: ${panel.projectCount}")

        panel.selectCategory("Networking")
        assertTrue("category: ${panel.projectCount}", waitFor { panel.projectCount >= 20 && !panel.isLoading })
        assertEquals(20, panel.projectCount)

        // A project without a recipe: ticking it selects its most likely artifact.
        val keys = panel.entryKeys
        val index = keys.indexOfFirst { it.startsWith("klibs:") && it != keys.first() }
        panel.toggleAt(index)
        assertTrue("cart: ${panel.cartIds}", waitFor { panel.cartIds.isNotEmpty() })
        println("PANEL networking cart: ${panel.cartIds}")

        panel.selectCategory("Top libraries")
        panel.searchField.text = "okio"
        assertTrue(waitFor { panel.projectCount > 0 && !panel.isLoading })
        println("PANEL search okio: ${panel.projectCount} projects, ${panel.entryKeys.take(3)}")

        // Let background lookups finish so no network threads outlive the test.
        waitFor(20_000) { false }
    }

    /** Renders the live panel to build/kmphub-panel-*.png for a visual check. */
    fun `test render panel snapshot`() {
        val panel = HubPanel(project, testRootDisposable, goOnline = true)
        assertTrue(waitFor { panel.projectCount >= 50 && !panel.isLoading })
        panel.toggleAt(0)
        panel.toggleAt(2)
        waitFor(5_000) { panel.cartIds.size >= 2 }
        fun snap(name: String) {
            panel.setSize(460, 980)
            com.intellij.util.ui.UIUtil.uiTraverser(panel).forEach { it.doLayout() }
            panel.validate()
            val image = java.awt.image.BufferedImage(460, 980, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics()
            panel.printAll(g)
            g.dispose()
            javax.imageio.ImageIO.write(image, "png", java.io.File(System.getProperty("user.dir"), "build/kmphub-panel-$name.png"))
        }
        panel.selectListIndex(1)
        waitFor(8_000) { false }
        snap("top")
        panel.selectCategory("Networking")
        assertTrue(waitFor { panel.projectCount >= 20 && !panel.isLoading })
        panel.selectListIndex(3)
        waitFor(8_000) { false }
        snap("networking")
    }

    fun `test tool window panel builds`() {
        val panel = HubPanel(project, testRootDisposable, goOnline = false)
        assertTrue(panel.componentCount > 0)
    }
}
