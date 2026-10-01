# Error Log

### Gradle 9 build fails in foojay toolchain plugin — 2026-09-28

- **Severity:** Medium
- **Category:** Configuration
- **File(s):** `settings.gradle`
- **Pattern:** Gradle settings plugin pinned to a version compiled against an older Gradle API (here `foojay-resolver-convention` 0.8.0 referencing the removed `JvmVendorSpec.IBM_SEMERU`) while the wrapper was bumped to a new Gradle major.
- **Root cause:** The wrapper moved to Gradle 9.7.1 but the foojay resolver plugin stayed at 0.8.0, which fails on a clean machine with `Class org.gradle.jvm.toolchain.JvmVendorSpec does not have member field ... IBM_SEMERU`.
- **Fix applied:** Bumped `org.gradle.toolchains.foojay-resolver-convention` to 1.0.0.
- **Prevention rule:** When bumping the Gradle wrapper major version, also bump every settings/build plugin and verify with a clean `./gradlew executableJar` using an empty `~/.gradle/caches`.

### Unattended run left idle for 5.5 hours without anyone checking — 2026-09-29

- **Severity:** High (lost ~5.5 of 8 hours of an unattended overnight session)
- **Category:** Process (supervision of long-running work)
- **File(s):** agent scratch scripts (`queue5.sh`), not in the repository
- **Pattern:** The agent started a background job and then waited passively for its completion notification, with no independent check that work was actually progressing. When the job hung, no notification ever arrived and nothing prompted a look, so the stall went unnoticed until the user asked for a status report.
- **Root cause:** The primary failure was the absence of any progress check between 01:43 and 07:11 during a session the agent had been asked to supervise alone. The trigger was a minor script bug: the validation queue waited with `while pgrep -f "queue4.sh"`, and the shell that launched it had `queue4.sh` in its own command line, so the loop matched itself forever after the real job finished. Any single check of the benchmark logs in those 5.5 hours would have shown no output since 01:43.
- **Fix applied:** Killed the stuck loop at 07:11, started the validation runs directly, and added a heartbeat monitor that reports if the benchmark logs stop growing for 10 minutes.
- **Prevention rule:** For any unattended or long-running work, set up an active heartbeat that confirms progress (log growth, row counts) at a fixed interval and alerts on stalls. Never rely only on a completion notification, which a hung job never sends. Secondary: chain dependent jobs in one script or wait on a PID or done-file, never with `pgrep -f` on a name that can appear in a launcher's arguments.
