# Error Log

### Gradle 9 build fails in foojay toolchain plugin — 2026-09-28

- **Severity:** Medium
- **Category:** Configuration
- **File(s):** `settings.gradle`
- **Pattern:** Gradle settings plugin pinned to a version compiled against an older Gradle API (here `foojay-resolver-convention` 0.8.0 referencing the removed `JvmVendorSpec.IBM_SEMERU`) while the wrapper was bumped to a new Gradle major.
- **Root cause:** The wrapper moved to Gradle 9.7.1 but the foojay resolver plugin stayed at 0.8.0, which fails on a clean machine with `Class org.gradle.jvm.toolchain.JvmVendorSpec does not have member field ... IBM_SEMERU`.
- **Fix applied:** Bumped `org.gradle.toolchains.foojay-resolver-convention` to 1.0.0.
- **Prevention rule:** When bumping the Gradle wrapper major version, also bump every settings/build plugin and verify with a clean `./gradlew executableJar` using an empty `~/.gradle/caches`.
