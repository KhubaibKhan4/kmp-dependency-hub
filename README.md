<div align="center">

<img src="idea-plugin/src/main/resources/META-INF/pluginIcon.svg" width="96" alt="KMP Dependency Hub icon">

# KMP Dependency Hub

**Every Kotlin Multiplatform dependency, one click away.**

Browse 4,800+ KMP, Compose Multiplatform and Android libraries inside Android Studio,<br>
select several at once, and add them to the right source sets — engines, KSP and Gradle plugins included.

[![JetBrains Marketplace](https://img.shields.io/jetbrains/plugin/v/35177?label=JetBrains%20Marketplace&color=7f52ff)](https://plugins.jetbrains.com/plugin/35177)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/35177?color=1e88e5)](https://plugins.jetbrains.com/plugin/35177)
[![Rating](https://img.shields.io/jetbrains/plugin/r/rating/35177?color=3ddc84)](https://plugins.jetbrains.com/plugin/35177/reviews)
[![Build](https://github.com/KhubaibKhan4/kmp-dependency-hub/actions/workflows/build.yml/badge.svg)](https://github.com/KhubaibKhan4/kmp-dependency-hub/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Support on Ko-fi](https://img.shields.io/badge/Ko--fi-Support-FF5E5B?logo=ko-fi&logoColor=white)](https://ko-fi.com/Y8Y2YABZ7)
[![Buy Me a Coffee](https://img.shields.io/badge/Buy%20Me%20a%20Coffee-Support-FFDD00?logo=buymeacoffee&logoColor=black)](https://www.buymeacoffee.com/khubaibkhan)

[**Install from JetBrains Marketplace →**](https://plugins.jetbrains.com/plugin/35177) &nbsp;·&nbsp; [**▶ Watch the 90-second tour**](https://youtu.be/iO_Yv1gEgvw)

<img src="docs/screenshot.png" alt="KMP Dependency Hub tool window in Android Studio" width="900">

</div>

## The problem

Adding one library to a Kotlin Multiplatform project means answering a lot of questions. Which version is the
latest stable? Does the core go in `commonMain`? Which engine goes in `androidMain`, which in `iosMain`, which for
desktop? Does it need a Gradle plugin in the root build file? KSP for every target? And if one Android-only library
lands in `commonMain`, your iOS build breaks.

KMP Dependency Hub answers all of them for you, and shows you the diff before anything changes.

## See it in action

<a href="https://youtu.be/iO_Yv1gEgvw"><img src="https://img.youtube.com/vi/iO_Yv1gEgvw/maxresdefault.jpg" alt="Watch: KMP Dependency Hub in 90 seconds" width="720"></a>

The problem, then the fix: browsing, multi-select, every artifact in the right source set, platform safety and the
Gradle CLI. [Watch on YouTube](https://youtu.be/iO_Yv1gEgvw).

## Features

- **Browse the best KMP libraries.** The top projects from [klibs.io](https://klibs.io), ranked by GitHub stars,
  50 at a time with more as you scroll, and 22 categories: Networking, Database, Dependency injection, Navigation,
  Images, Serialization, Testing, Charts, AI and more.
- **Search everything.** klibs.io, Maven Central and Google Maven. New libraries appear as soon as they're published.
- **Multi-select.** Tick as many libraries as you need, pick a module, add them all in one go.
- **Recipes that know the details.** Ktor, Koin, Room, SQLDelight, Coil, kotlinx.serialization, coroutines,
  datetime and more install their per-platform engines, BOMs, KSP processors for every target, Gradle plugins
  and config blocks.
- **The right source set, every time.** `commonMain`, `androidMain`, `iosMain`, desktop and web — plus entries in
  `gradle/libs.versions.toml` that reuse the aliases and versions you already have.
- **Platform-aware.** A library that doesn't publish for one of your targets is kept out of `commonMain`.
- **Always current.** The latest stable versions from Maven Central and Google Maven, refreshed automatically.
- **Review first, undo once.** A diff of every file; all changes apply as a single undoable action.
- **Doctor.** *Tools → KMP Hub: Run Doctor* finds incompatible `commonMain` dependencies, duplicates and updates.
- **Private by design.** No telemetry, no account; see [PRIVACY.md](PRIVACY.md).

## Install

**From the IDE:** *Settings → Plugins → Marketplace*, search **KMP Dependency Hub**, click **Install**.
Or install it from the [Marketplace page](https://plugins.jetbrains.com/plugin/35177).

Works in **Android Studio** and **IntelliJ IDEA 2025.1 or newer**, with Gradle Kotlin DSL (`build.gradle.kts`)
projects.

## How to use it

1. Open the **KMP Hub** tool window on the right.
2. Browse **Top libraries**, pick a category, or search.
3. Tick the libraries you want. Libraries marked **Recipe** bring their whole setup; for other projects, choose
   artifacts in the details pane.
4. Pick the **Module** (and, if you like, the source set for common artifacts), press **Add**, review the diff,
   **Apply**.
5. Sync Gradle.

## Gradle plugin

The same engine works from the command line and in CI. Until it's on the Gradle Plugin Portal, use it as an
included build:

```kotlin
// settings.gradle.kts
pluginManagement {
    includeBuild("../kmp-dependency-hub")
}
```

```kotlin
// build.gradle.kts (root)
plugins {
    id("io.github.khubaibkhan4")
}
```

```bash
./gradlew kmpHubList --category=Networking
./gradlew kmpHubSearch --query=okio
./gradlew kmpHubModules
./gradlew kmpHubAdd --lib=ktor-client,koin,room --module=:composeApp --preview
./gradlew kmpHubAdd --lib=ktor-client,koin,room --module=:composeApp
./gradlew kmpHubAdd --lib=retrofit --module=:composeApp --source-set=androidMain
./gradlew kmpHubAdd --lib=com.squareup.okio:okio      # any group:name from kmpHubSearch
./gradlew kmpHubDoctor --strict                       # fails the build on errors, for CI
```

`--preview` prints the diff without writing. `--force` installs even when a library doesn't support every target.
`--refresh` skips the 24-hour version cache, and Gradle's `--offline` is respected.

## How it works

| Module | What it is |
|---|---|
| [`core`](core) | Dependency-free engine: recipes, version catalog and `build.gradle.kts` editors, installer, Doctor, live versions, klibs.io client. |
| [`idea-plugin`](idea-plugin) | The KMP Hub tool window for Android Studio / IntelliJ IDEA. |
| [`gradle-plugin`](gradle-plugin) | `io.github.khubaibkhan4`: `kmpHubList`, `kmpHubSearch`, `kmpHubModules`, `kmpHubAdd`, `kmpHubDoctor`. |
| [`sample-kmp-app`](sample-kmp-app) | A KMP app (Android, iOS, desktop) to try the plugins on. |
| [`video`](video) | The animated tutorial, reproducible from code (Three.js, headless Chrome, ffmpeg). |

Curated recipes live in [`core/src/main/resources/kmphub/catalog.json`](core/src/main/resources/kmphub/catalog.json).
Their versions update live; [`tools/refresh_catalog_versions.py`](tools/refresh_catalog_versions.py) refreshes the
bundled fallback.

## Contributing

Bug reports and recipe requests are welcome in [Issues](https://github.com/KhubaibKhan4/kmp-dependency-hub/issues).

```bash
# Point the build at your IDE (git-ignored):
echo 'kmphub.ideaLocalPath=/Applications/Android Studio.app' > local.properties
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

./gradlew build                                  # all tests, including a real KMP build via Gradle TestKit
./gradlew :idea-plugin:runIde -Pkmphub.openProject="$PWD/sample-kmp-app"   # sandbox IDE with the plugin
./gradlew :idea-plugin:verifyPlugin              # JetBrains Plugin Verifier
```

Releases are tag-driven; see [PUBLISHING.md](PUBLISHING.md).

## Support

KMP Dependency Hub is free. If it saves you time, you can support its development on [Ko-fi](https://ko-fi.com/Y8Y2YABZ7) or
[Buy Me a Coffee](https://www.buymeacoffee.com/khubaibkhan). A rating or review on the [Marketplace page](https://plugins.jetbrains.com/plugin/35177/reviews)
helps a lot too.

## Roadmap

- Gradle Plugin Portal release.
- Standalone CLI jar, so the engine can fix builds that Gradle can't configure.
- Upgrade solver: a compatible set of Kotlin, KSP, Compose Multiplatform and libraries, applied as one change.
- Groovy `build.gradle` and convention-plugin (`build-logic`) projects.
- Remote recipe feed, so new recipes arrive without a plugin update.
- "Feature" recipes that also generate glue code (an `HttpClient` setup, Koin modules).

## License

[Apache License 2.0](LICENSE) © 2026 Khubaib Khan
