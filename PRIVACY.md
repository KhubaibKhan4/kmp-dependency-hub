# Privacy Policy

_Last updated: October 16, 2026_

KMP Dependency Hub (the IntelliJ / Android Studio plugin and the Gradle plugin) does **not** collect, store or send
any personal data, and has **no telemetry or analytics**.

## What the plugin sends over the network

Only to find libraries and their latest versions, and only these public services:

| Service | What is sent | Why |
|---|---|---|
| `api.klibs.io` | The text you type in the search box, the selected category, a page number | Browse and search Kotlin Multiplatform libraries |
| `central.sonatype.com`, `search.maven.org` | The text you type in the search box | Search Maven Central |
| `repo1.maven.org`, `dl.google.com`, `plugins.gradle.org` | Library coordinates (for example `io.ktor:ktor-client-core`) | Read latest versions and supported platforms |

Requests go through your IDE's own network settings (proxy, certificates) and carry the IDE's standard User-Agent.
They contain no account, project, file or machine identifiers.

## What stays on your machine

- Your project's build files are read and edited locally. Nothing from your project is uploaded.
- Latest-version lookups are cached locally for 24 hours (in the IDE's system folder, or `~/.gradle/kmphub/` for
  the Gradle plugin). Delete those files at any time.

## Contact

Questions: open an issue at <https://github.com/KhubaibKhan4/kmp-dependency-hub/issues>.
