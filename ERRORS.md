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

### Post-autorouter stage skipped because the batch loop requests an autorouter stop — 2026-10-01

- **Severity:** High
- **Category:** Logic
- **File(s):** `src/main/java/app/freerouting/autoroute/pipeline/RoutingPipeline.java`
- **Pattern:** Guarding a stage that runs after `BatchAutorouter.runBatchLoop()` with `thread.isStopAutoRouterRequested()`. `AutorouteBatchLoop` calls `requestStopAutoRouter()` on every normal exit (max passes, stagnation, board-history limits), so the flag is almost always set afterwards.
- **Root cause:** `StoppableThread` uses one state for "user stopped the autorouter" and "the autorouter finished"; the grid fallback guard treated both as a stop and never ran in the real pipeline (only in a harness that called the stage directly).
- **Fix applied:** The guard now checks `isStopRequested()` (full stop) and `job.state != TIMED_OUT` instead.
- **Prevention rule:** After `runBatchLoop()`, only `isStopRequested()` and the job state mean "stop"; never use `isStopAutoRouterRequested()` there. Verify any new pipeline stage end-to-end through the executable JAR and look for its log line, not only through a direct-call harness.

### New-item clearance check misses violations reported only by the older item — 2026-10-01

- **Severity:** High
- **Category:** Logic
- **File(s):** `src/main/java/app/freerouting/autoroute/grid/GridFallbackRouter.java`
- **Pattern:** Deciding "this change added no clearance violations" by calling `Item.clearanceViolations()` only on items with `id > maxIdBefore`. `isObstacle(Item)` is not symmetric (for example `Via.isObstacle(ComponentObstacleArea)` is false while the reverse pair is reported by the full DRC), so a pair can be invisible from the new item's side.
- **Root cause:** The grid fallback accepted transactions on the new-items check alone; on the 30-board probe the full `DesignRulesChecker.getAllClearanceViolations()` count rose by 9 across 5 boards.
- **Fix applied:** The per-step check now also asks every older neighbour of a new item (`overlappingItemsWithClearance`) for violations that involve the new item; each transaction is additionally gated on the full `getAllClearanceViolations().size()` not growing, and the stage compares the full count at start and end.
- **Prevention rule:** When checking "no violation added", look at both sides of every pair that involves a new item (new item and its older neighbours), and gate the final decision on the full `getAllClearanceViolations()` count before vs after.

### "Unset" setting treated as a cap because the default is a sentinel — 2026-10-02

- **Severity:** Medium
- **Category:** Configuration
- **File(s):** `src/main/java/app/freerouting/autoroute/pipeline/RoutingPipeline.java`
- **Pattern:** Testing a limit setting with `value == null || value <= 0` to mean "no limit" when `DefaultSettings` fills the field with a sentinel such as `Integer.MAX_VALUE`.
- **Root cause:** `router.autorouter.max_items` defaults to `Integer.MAX_VALUE`, so the "skip the grid fallback when max_items caps the work" guard skipped it on every default run.
- **Fix applied:** The guard also treats `Integer.MAX_VALUE` as "no cap".
- **Prevention rule:** Before testing a setting for "unset", read its value in `DefaultSettings.getSettings()`; verify a new default-on stage by grepping its log line in a default CLI run.

### Stage between autorouter and optimizer breaks the phase-metrics handover — 2026-10-02

- **Severity:** Medium
- **Category:** Logic
- **File(s):** `src/main/java/app/freerouting/autoroute/pipeline/RoutingPipeline.java`, `src/test/java/app/freerouting/fixtures/Issue872SingleLayerRoutingTest.java`
- **Pattern:** Changing the board after `AutorouteBatchLoop` has written `job.resultPhaseMetrics.autorouter.after` but before `BatchOptimizer` writes `optimizer.before`.
- **Root cause:** The grid fallback completed a connection after the autorouter snapshot, so `optimizerStartsFromAutorouterBestBoard` saw 1 incomplete connection vs 0.
- **Fix applied:** After the fallback keeps a change, the pipeline refreshes `autorouter.after` from the board and adds the stage time to the autorouter phase duration.
- **Prevention rule:** Any stage that edits the board between two phase snapshots must refresh the earlier phase's `after` snapshot (or record its own phase).
