# Publishing

The IDE plugin goes to **JetBrains Marketplace**, the Gradle plugin to the **Gradle Plugin Portal**. After the
one-time setup below, a release is: bump the version, update the changelog, push a tag.

## One-time setup

### 1. Repository and identity

Done: the repository is <https://github.com/KhubaibKhan4/kmp-dependency-hub> (Apache-2.0), and `gradle.properties`
sets the owner and vendor. That fixes the IDs, which can't change after the first publish:

- Marketplace plugin: `io.github.khubaibkhan4.kmpdependencyhub`
- Gradle plugin: `io.github.khubaibkhan4` (the Plugin Portal accepts it when you sign in with the KhubaibKhan4 GitHub account)

### 2. JetBrains Marketplace

1. Sign in at <https://plugins.jetbrains.com> with your JetBrains Account and accept the developer agreement.
2. **Upload the first version by hand**: *Upload plugin* → pick `idea-plugin/build/distributions/kmp-dependency-hub-<version>.zip`
   (`./gradlew :idea-plugin:buildPlugin`). Marketplace only accepts automated uploads for plugins that already exist.
   JetBrains reviews new plugins before they go public (usually a few working days).
3. Create a permanent token: profile → *My Tokens* → *Generate Token*. Save it as the `PUBLISH_TOKEN` secret.
4. Create a signing certificate (keep these files out of the repository):

   ```bash
   openssl genpkey -aes-256-cbc -algorithm RSA -out private_encrypted.pem -pkeyopt rsa_keygen_bits:4096
   openssl rsa -in private_encrypted.pem -out private.pem
   openssl req -key private.pem -new -x509 -days 3650 -out chain.crt
   ```

   Secrets: `CERTIFICATE_CHAIN` = contents of `chain.crt`, `PRIVATE_KEY` = contents of `private_encrypted.pem`,
   `PRIVATE_KEY_PASSWORD` = the password you chose.

### 3. Gradle Plugin Portal

1. Sign in at <https://plugins.gradle.org> with the GitHub account that owns `io.github.<githubOwner>`.
2. Profile → *API Keys* → copy the key and secret into the `GRADLE_PUBLISH_KEY` and `GRADLE_PUBLISH_SECRET` secrets.
3. Optional dry run from your machine: `./gradlew :gradle-plugin:publishPlugins --validate-only -Pgradle.publish.key=… -Pgradle.publish.secret=…`.
   New plugins are also reviewed before they're public.

### 4. GitHub secrets

Repository → *Settings → Secrets and variables → Actions* → add:

| Secret | From |
|---|---|
| `PUBLISH_TOKEN` | Marketplace permanent token |
| `CERTIFICATE_CHAIN` | `chain.crt` |
| `PRIVATE_KEY` | `private_encrypted.pem` |
| `PRIVATE_KEY_PASSWORD` | the key's password |
| `GRADLE_PUBLISH_KEY` / `GRADLE_PUBLISH_SECRET` | Plugin Portal API keys |

## Every release

1. Bump `kmphub.version` in `gradle.properties`.
2. Add a `## <version>` section at the top of `CHANGELOG.md`; it becomes the Marketplace "What's New".
3. Commit, then tag and push:

   ```bash
   git tag v0.3.0 && git push origin v0.3.0
   ```

The **Release** workflow checks the tag matches the version, builds and tests, runs the Plugin Verifier against the
IDE versions JetBrains recommends, signs and publishes to Marketplace, publishes the Gradle plugin, and attaches
the signed zip to a GitHub release. The **Build** workflow runs the same checks on every push and pull request.

## Checks you can run locally

```bash
./gradlew build                                  # all tests, including live klibs.io / Maven ones
./gradlew :idea-plugin:verifyPlugin              # Plugin Verifier against your local IDE
./gradlew :idea-plugin:verifyPlugin -Pkmphub.verifyRecommended=true   # + recommended IDEs (downloads several GB)
./gradlew :idea-plugin:buildPlugin               # zip in idea-plugin/build/distributions
./gradlew :gradle-plugin:validatePlugins         # strict Gradle plugin validation
```

Local builds use the IDE in `local.properties` (`kmphub.ideaLocalPath=…`, git-ignored); CI downloads IntelliJ IDEA
`kmphub.ciPlatformVersion` instead.
