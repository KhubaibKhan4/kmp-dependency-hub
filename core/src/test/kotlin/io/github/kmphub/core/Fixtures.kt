package io.github.kmphub.core

/** Files shaped like the output of the JetBrains KMP wizard (Compose Multiplatform, Android + iOS + desktop). */
object Fixtures {
    val catalog = """
        [versions]
        agp = "8.12.0"
        composeMultiplatform = "1.9.0"
        kotlin = "2.2.20"

        [libraries]
        kotlin-test = { module = "org.jetbrains.kotlin:kotlin-test", version.ref = "kotlin" }

        [plugins]
        androidApplication = { id = "com.android.application", version.ref = "agp" }
        composeMultiplatform = { id = "org.jetbrains.compose", version.ref = "composeMultiplatform" }
        composeCompiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
        kotlinMultiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
    """.trimIndent() + "\n"

    val rootBuild = """
        plugins {
            // this is necessary to avoid the plugins to be loaded multiple times
            // in each subproject's classloader
            alias(libs.plugins.androidApplication) apply false
            alias(libs.plugins.composeMultiplatform) apply false
            alias(libs.plugins.composeCompiler) apply false
            alias(libs.plugins.kotlinMultiplatform) apply false
        }
    """.trimIndent() + "\n"

    val settings = """
        rootProject.name = "Sample"
        include(":composeApp")
        include(":shared", ":server")
    """.trimIndent() + "\n"

    val composeApp = """
        import org.jetbrains.compose.desktop.application.dsl.TargetFormat
        import org.jetbrains.kotlin.gradle.dsl.JvmTarget

        plugins {
            alias(libs.plugins.kotlinMultiplatform)
            alias(libs.plugins.androidApplication)
            alias(libs.plugins.composeMultiplatform)
            alias(libs.plugins.composeCompiler)
        }

        kotlin {
            androidTarget {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_11)
                }
            }

            listOf(
                iosArm64(),
                iosSimulatorArm64()
            ).forEach { iosTarget ->
                iosTarget.binaries.framework {
                    baseName = "ComposeApp"
                    isStatic = true
                }
            }

            jvm("desktop")

            sourceSets {
                val desktopMain by getting

                androidMain.dependencies {
                    implementation(compose.preview)
                }
                commonMain.dependencies {
                    implementation(compose.runtime)
                    implementation(compose.material3)
                }
                commonTest.dependencies {
                    implementation(libs.kotlin.test)
                }
                desktopMain.dependencies {
                    implementation(compose.desktop.currentOs)
                }
            }
        }

        android {
            namespace = "com.example.sample"
            defaultConfig {
                applicationId = "com.example.sample" // "{ not a block }"
                versionName = "${'$'}{project.version}"
            }
        }
    """.trimIndent() + "\n"

    val composeAppModule = ModuleInfo(
        path = ":composeApp",
        buildFile = "composeApp/build.gradle.kts",
        kind = ModuleKind.KMP,
        targets = listOf(
            Target("android", Platform.ANDROID),
            Target("iosArm64", Platform.IOS),
            Target("iosSimulatorArm64", Platform.IOS),
            Target("desktop", Platform.JVM),
        ),
    )

    val androidApp = """
        plugins {
            id("com.android.application")
            kotlin("android")
        }

        android {
            namespace = "com.example.app"
        }

        dependencies {
            implementation("androidx.core:core-ktx:1.17.0")
        }
    """.trimIndent() + "\n"

    val androidModule = ModuleInfo(":app", "app/build.gradle.kts", ModuleKind.ANDROID)

    fun files(vararg entries: Pair<String, String>): MutableMap<String, String> = mutableMapOf(*entries)

    fun wizardProject() = files(
        "settings.gradle.kts" to settings,
        "build.gradle.kts" to rootBuild,
        "gradle/libs.versions.toml" to catalog,
        "composeApp/build.gradle.kts" to composeApp,
    )

    fun MutableMap<String, String>.apply(result: InstallResult) = result.changes.forEach { put(it.path, it.after) }
}
