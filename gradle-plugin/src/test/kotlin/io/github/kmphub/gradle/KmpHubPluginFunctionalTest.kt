package io.github.kmphub.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** Runs the plugin in a real KMP build, then compiles code that uses what it added. */
class KmpHubPluginFunctionalTest {
    @TempDir
    lateinit var dir: File

    private val kotlinVersion = System.getProperty("kmphub.kotlinVersion")
    private val pluginId = System.getProperty("kmphub.pluginId")

    private fun write(path: String, text: String) = File(dir, path).apply { parentFile.mkdirs() }.writeText(text.trimIndent() + "\n")

    private fun gradle(vararg args: String) = GradleRunner.create()
        .withProjectDir(dir)
        .withPluginClasspath()
        .withArguments(*args, "--configuration-cache", "--stacktrace")
        .forwardOutput()

    private fun setUpProject() {
        write("settings.gradle.kts", """
            pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
            dependencyResolutionManagement { repositories { mavenCentral() } }
            rootProject.name = "sample"
            include(":shared")
        """)
        write("gradle/libs.versions.toml", """
            [versions]
            kotlin = "$kotlinVersion"

            [plugins]
            kotlinMultiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
        """)
        write("build.gradle.kts", """
            plugins {
                id("$pluginId")
                alias(libs.plugins.kotlinMultiplatform) apply false
            }
        """)
        write("shared/build.gradle.kts", """
            plugins {
                alias(libs.plugins.kotlinMultiplatform)
            }

            kotlin {
                jvm()
            }
        """)
    }

    @Test
    fun `adds libraries that then compile`() {
        setUpProject()

        val list = gradle("kmpHubList", "--category=Database").build()
        assertContains(list.output, "room")
        assertContains(list.output, "sqldelight")

        val modules = gradle("kmpHubModules").build()
        assertContains(modules.output, ":shared")
        assertContains(modules.output, "jvm (jvm)")

        val preview = gradle("kmpHubAdd", "--lib=kotlinx-serialization,kotlinx-datetime", "--preview").build()
        assertContains(preview.output, "+            implementation(libs.kotlinx.datetime)")
        assertEquals(false, File(dir, "shared/build.gradle.kts").readText().contains("datetime"))

        val add = gradle("kmpHubAdd", "--lib=kotlinx-serialization,kotlinx-datetime", "--module=:shared").build()
        assertEquals(TaskOutcome.SUCCESS, add.task(":kmpHubAdd")?.outcome)
        assertContains(File(dir, "build.gradle.kts").readText(), "alias(libs.plugins.kotlinx.serialization) apply false")

        write("shared/src/commonMain/kotlin/Event.kt", """
            import kotlinx.datetime.LocalDate
            import kotlinx.serialization.Serializable
            import kotlinx.serialization.json.Json

            @Serializable
            data class Event(val name: String, val day: String)

            fun encode(): String = Json.encodeToString(Event.serializer(), Event("launch", LocalDate(2026, 10, 16).toString()))
        """)
        val compile = gradle(":shared:compileKotlinJvm").build()
        assertEquals(TaskOutcome.SUCCESS, compile.task(":shared:compileKotlinJvm")?.outcome)

        val again = gradle("kmpHubAdd", "--lib=kotlinx-datetime", "--module=:shared").build()
        assertContains(again.output, "Nothing to change.")
    }

    @Test
    fun `offline mode uses bundled versions and refuses remote lookups`() {
        setUpProject()
        val list = gradle("kmpHubList", "--offline").build()
        assertContains(list.output, "Versions: ")
        val remote = gradle("kmpHubAdd", "--lib=com.squareup.okio:okio", "--offline").buildAndFail()
        assertContains(remote.output, "while Gradle is offline")
    }

    @Test
    fun `refuses incompatible libraries and doctor reports them`() {
        setUpProject()
        val jvmLib = gradle("kmpHubAdd", "--lib=retrofit", "--module=:shared", "--source-set=jvmMain").build()
        assertContains(jvmLib.output, "Added Retrofit to :shared.")

        val failed = gradle("kmpHubAdd", "--lib=hilt", "--module=:shared").buildAndFail()
        assertContains(failed.output, "Hilt doesn't publish for jvm")
        assertEquals(false, File(dir, "shared/build.gradle.kts").readText().contains("hilt"))

        File(dir, "shared/build.gradle.kts").appendText(
            "\nkotlin { sourceSets { commonMain.dependencies { implementation(\"com.google.dagger:hilt-android:2.57.2\") } } }\n",
        )
        val doctor = gradle("kmpHubDoctor").build()
        assertContains(doctor.output, "com.google.dagger:hilt-android in commonMain doesn't support jvm")
        gradle("kmpHubDoctor", "--strict").buildAndFail()
    }
}
