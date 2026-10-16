# Publishing

The IDE plugin goes to **JetBrains Marketplace**, the Gradle plugin to the **Gradle Plugin Portal**. After the
one-time setup below, a release is: bump the version, update the changelog, push a tag.

## One-time setup

### 1. Repository and identity

Done: the repository is <https://github.com/KhubaibKhan4/kmp-dependency-hub> (Apache-2.0), and `gradle.properties`
sets the owner and vendor. That fixes the IDs, which can't change after the first publish:

- Marketplace plugin: `io.github.kmphub.idea` (listing 35177: https://plugins.jetbrains.com/plugin/35177)
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

1. Sign in at <https://plugins.gradle.org> with the KhubaibKhan4 GitHub account (it owns `io.github.khubaibkhan4`).
2. Profile → *API Keys* → generate a key and secret.
3. Publish whenever you like, independent of Marketplace: *Actions → Publish Gradle plugin → Run workflow*, or
   `gh workflow run publish-gradle-plugin.yml`. A new plugin is reviewed by Gradle before it's public.

### 4. GitHub secrets

Store each secret with `gh secret set`; it prompts for the value, so it never lands in your shell history,
a file or a chat:

```bash
R=KhubaibKhan4/kmp-dependency-hub
gh secret set GRADLE_PUBLISH_KEY --repo $R
gh secret set GRADLE_PUBLISH_SECRET --repo $R
gh secret set PUBLISH_TOKEN --repo $R
gh secret set PRIVATE_KEY_PASSWORD --repo $R
gh secret set CERTIFICATE_CHAIN --repo $R < chain.crt
gh secret set PRIVATE_KEY --repo $R < private_encrypted.pem
```

| Secret | From |
|---|---|
| `GRADLE_PUBLISH_KEY` / `GRADLE_PUBLISH_SECRET` | Plugin Portal API keys |
| `PUBLISH_TOKEN` | Marketplace permanent token |
| `CERTIFICATE_CHAIN` | `chain.crt` |
| `PRIVATE_KEY` | `private_encrypted.pem` |
| `PRIVATE_KEY_PASSWORD` | the key's password |

If a key or token is ever exposed (pasted somewhere, committed), revoke it and generate a new one.

## Every release

1. Bump `kmphub.version` in `gradle.properties`.
2. Add a `## <version>` section at the top of `CHANGELOG.md`; it becomes the Marketplace "What's New".
3. Commit, then tag and push:

   ```bash
   git tag v0.3.0 && git push origin v0.3.0
   ```

The **Release** workflow checks the tag matches the version, builds and tests, and runs the Plugin Verifier against
the IDE versions JetBrains recommends. Then, as independent jobs, it signs and publishes to Marketplace, publishes
the Gradle plugin, and attaches the plugin zip to a GitHub release. A store whose secrets aren't set is skipped
with a notice instead of failing the release. The **Build** workflow runs the same checks on every push and pull request.

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
