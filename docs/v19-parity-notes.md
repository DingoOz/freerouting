# v1.9 Parity Work — Notes

Branch: `feature/engine-perf-1.9-parity` on `https://github.com/DingoOz/freerouting`
(fork of `freerouting/freerouting`, based on upstream `master` at `aa909a34`).

Goal: make the current engine match or beat Freerouting 1.9.x routing completion.

## Where the gap is

From upstream's nightly results (`scripts/benchmark/results/benchmarks.json`, 1157 fixtures):

| Version   | Fully routed | Clean (0 DRC) | Total time |
|-----------|-------------:|--------------:|-----------:|
| 1.9.0     | 49.1%        | 35.9%         | 2574 s     |
| 2.4.1     | 51.9%        | 30.9%         | 3251 s     |
| 2.5.0-RC8 | 54.6%        | 47.3%         | 2106 s     |

- Overall, RC8 already beats 1.9.0.
- The remaining gap is **Tier B** (routine 2–4 layer boards): 1.9.0 fully routes 33.4% vs RC8 26.4%.
- On 177 Tier B boards 1.9.0 wins, almost always by completing the **last 1–2 connections** that the
  current engine leaves unrouted.
- Example, `PCBench/ABOVISP_ABOVISP`: 1.9 routes everything in pass 1. RC8 sticks at 1 unrouted
  (`+5V L1-1 -> P3-9`), repeats the same score for 10 passes, then stops. Current `master` routes it
  fully, so upstream #931 (Tier B completion) may already close part of the gap.

## Candidate causes to investigate

- **Stop rules:** 1.9 stops after 20 passes without improvement
  (`src_v19/main/java/app/freerouting/autoroute/BatchAutorouter.java`). Current code
  (`src/main/java/app/freerouting/autoroute/pipeline/AutorouteBatchLoop.java`) stops after
  `STAGNATION_PASS_LIMIT = 10`, also restores earlier boards (`BoardHistory`), and has a
  board-rank limit. Constants are in `pipeline/BatchAutorouter.java`.
- **Routing behaviour, not just stopping:** ABOVISP shows the difference can appear in pass 1, so
  compare fanout results and connection ordering / rip-up costs as well.

## Benchmark tool: `scripts/benchmark/gap_bench.py`

A Linux runner, used instead of the Windows-oriented PowerShell harness.

- `select`: picks fixtures where 1.9.0 beat the newest release in the nightly data (103 gaps with
  the default `--max-seconds 60`) plus 30 random control fixtures where the newest release wins,
  so regressions show up.
- `run`: routes each fixture with each `--jar label=path`, DRCs every `.ses` with one reference
  jar (default: `build/libs/freerouting-current-executable.jar`), and appends rows to
  `scripts/benchmark/results/comparison-v19-gap/runs.jsonl` (git-ignored). It resumes by skipping
  pairs already done.
- `report [--base v19 --head wip]`: summary table plus a per-fixture better/worse list.
- Headless: current builds run with `-Djava.awt.headless=true`. 1.9 is GUI-only, so it runs on a
  private Xvfb display with `-dct 0` (skips its 20 s auto-start countdown dialog). Without Xvfb
  the script exits instead of opening windows on the desktop.
- Wall times are indicative only when running in parallel; completion and DRC counts are reliable.

## Setup on a new machine

1. `git clone -b feature/engine-perf-1.9-parity https://github.com/DingoOz/freerouting.git && cd freerouting && git remote add upstream https://github.com/freerouting/freerouting.git`
2. `sudo apt-get install -y openjdk-25-jdk xvfb && ./gradlew executableJar`
3. `python3 scripts/benchmark/gap_bench.py select > /tmp/fx.txt && python3 scripts/benchmark/gap_bench.py run /tmp/fx.txt --jar v19=scripts/benchmark/binaries/freerouting-1.9.0.jar --jar wip=build/libs/freerouting-current-executable.jar -j 4 && python3 scripts/benchmark/gap_bench.py report`

Step 3 is 266 runs, about 30–40 minutes on 12 cores.

## Findings (2026-09-28)

### Fixed: padstack names with decimals broke via rules

Commit `4be323b6` ("Deduplicate package/padstack names in imports") made the DSN reader strip every
`\.\d+` from padstack names, so `Via[0-1]_635:304.8_um` was stored as `Via[0-1]_635:304_um`. Net
class `(use_via ...)` names were not stripped, so they no longer matched and **every via rule was
empty: the router could not place a single via**. The stripping also made distinct padstacks
collide (e.g. `Round[A]Pad_1600.000000_um` / `Round[A]Pad_1600.200000_um`); the second definition
was dropped as "exists already" and its pins got the first one's shape.

Scale in the benchmark fixtures: 189 of 1177 DSNs have dotted `use_via` names, 12 have collisions.

Fix: names are stored verbatim again (as in v1.9). All reference lookups (pins, `use_via`, via
padstacks, wiring, SES) go through `Padstacks.getByReference()`: exact match first, and only then a
match that ignores dotted numeric suffixes. Test: `PadstackNameResolutionTest`.

Benchmark on the 133-fixture gap set (103 gaps + 30 controls, same flags for all):

| Version | gap: fully routed | gap: sum unrouted | control: fully routed | clearance (gap/control) |
|---------|------------------:|------------------:|----------------------:|------------------------:|
| 1.9.0   | 60 / 103          | 114               | 18 / 30               | 1 / 0                   |
| master  | 75 / 103          | 82                | 24 / 30               | 4 / 5                   |
| fix     | 85 / 103          | 48                | 25 / 30               | 4 / 5                   |

fix vs master: 12 better, 0 worse. fix vs 1.9: 45 better, 13 worse.

### How the bug was found (reusable recipe)

1. Trace both versions on one board for 1–2 passes and diff `compare_trace_route_item`, then the
   `RAW_SECTION assign` stream (normalise `roomRipped`/`expansionValue`/`sortingValue` to the v1.9
   snake_case names and mask the values).
2. The shipped `binaries/freerouting-1.9.0.jar` ignores `--logging.file.level`; give it a log4j2
   config with a TRACE root via `-Dlog4j2.configurationFile=<file>`.
3. Match copper-to-edge clearance: v1.9 gives the board outline the default clearance class, the
   current default is 250 um. Pass `--router.copper_to_edge_clearance_um=<default clearance>`
   (e.g. 228.6) or the first room next to the board edge already differs.
4. In the Atmel-ICE trace the first remaining mismatch was the A* sorting value, lower by exactly
   `via_costs * (via radius - trace half width)`: the via radius was missing because the via rule
   was empty.

### Still open

- 13 boards where 1.9 still wins, e.g. `bt-tnc_tnc` (1.9 clean, fix 4 unrouted),
  `OLD-Stepper-motor-board-design-project` (clean vs 3), `android_debug_cable` (clean vs 1). Apply
  the recipe above to the first one.
- `ESP07-Breakout`: current build finishes with 3 clearance violations, 1.9 with none. Not related
  to the padstack bug (no dotted names on that board).
- Pass 2+ reordering and the "rip every trace of a net after 2 failures" rule in
  `AutoroutePassRunner` (both from #931) differ from 1.9. Switching them off did not fix Atmel-ICE,
  but they are worth re-testing on the remaining boards.
- `gap_bench.py` runs the optimizer only on fully routed boards, and 1.9 spends most of its time
  there, so wall times are not comparable between versions.

## Next steps

1. Pick the first remaining "worse" board from `report --base v19 --head fix` and trace it as above.
2. Fix the smallest cause; re-run the benchmark and confirm the gap narrows with no new clearance
   violations (see the exit criteria in `AGENTS.md`).

## Plan: settings sweep on the routing server

### Why

Most boards where 1.9 still wins fail on the last 1–4 connections. The router is deterministic, so
a different setting (via cost, rip-up cost, order) is often what gets it past a dead end. A server
can try many settings per board at once, keep the best result, and show which settings win most.
This needs no engine changes: it runs the jar several times per board and compares DRC results.

### Hardware and what it is good for

Server: 2x Intel Xeon E5-2680 (~54 threads), 160 GB RAM.

- **Single-board speed will not improve.** Each routing pass is single-threaded, so per-core speed
  matters, and these cores are older and slower than a current desktop.
- **Memory is not the bottleneck.** Typical boards peak at a few hundred MB of heap; 2–3 GB per job
  is plenty. A large heap for one job does not make it faster.
- **Throughput is the win.** Budget:

  | Use | Parallel jobs | Memory |
  |-----|--------------:|-------:|
  | Benchmarks with trustworthy timings | ~26 (one per physical core) | ~80 GB |
  | Maximum throughput (completion/DRC only) | ~48 (hyperthreads) | ~145 GB |

- The full 266-run v1.9 comparison took ~3 h at 6 jobs on a 12-core desktop; on the server it
  should take well under an hour.
- GPU/CUDA was considered and rejected: the maze search is a sequential A* over irregular,
  on-the-fly geometry, which does not suit GPUs.

### Server setup

- Java 25 (use `gap_bench.py run --java <path>` if the system Java is older) and `xvfb` for 1.9.
- One thread per job: `--router.autorouter.max_threads=1 --router.optimizer.max_threads=1`
  (`gap_bench.py` already passes these). Without them, every job starts an optimizer thread per
  core and the machine is oversubscribed.
- `-Xmx3g` per job (`gap_bench.py run --heap 3g`).
- Wall times are only comparable when jobs do not exceed physical cores.

### Settings to vary (starting set)

The cost keys live in `RoutingCostSettings` under `router.scoring` (the flat layout in the
`docs/settings.md` JSON example is out of date). Confirm on one board that each flag takes effect
(the log prints the effective values) before sweeping.

| Setting | Default | Sweep values |
|---------|--------:|--------------|
| `--router.scoring.via_costs` | 50 | 25, 50, 100 |
| `--router.scoring.start_ripup_costs` | 100 | 50, 100, 200 |
| `--router.scoring.default_undesired_direction_trace_cost` | 1.0 (+ board-aspect adjustment) | 1.0, 2.0, 4.0 |
| `--router.fanout.enabled` | true | true, false |
| `--router.fanout.pin_sorting_order` | `outer_first` | `outer_first`, `surroundings_density`, `distance_to_closest_on_net` |
| `--router.scoring.plane_via_costs` (boards with pours) | 5 | 2, 5, 20 |

Start with one-at-a-time changes from the defaults (~12 variants per board) rather than the full
cross product, then combine the settings that win.

### Picking the best result

Every variant's `.ses` is checked with the same DRC jar. Ranking, following `AGENTS.md`:

1. clearance violations = 0 (a result with violations never beats one without)
2. fewest unrouted connections
3. tie-breaks: fewer vias, then shorter total trace length

### Steps

1. **Rerun the baseline on the server:** the full 133-board set (`gap_bench.py select`) with
   `v19`, `master` and the padstack fix, using the corrected thread flags and one DRC jar.
2. **Add a sweep mode to `gap_bench.py`:** a variants file (label + extra flags), all variants of a
   board run in parallel, and a report with the best variant per board and how often each
   variant wins.
3. **Check it locally on 2–3 boards** first, e.g. `bt-tnc_tnc` (1.9 clean, fix 4 unrouted).
4. **Run the sweep on the server** over the 133 boards.
5. **Act on the results:**
   - A setting that wins across many boards: propose it as the new default (helps every user),
     then confirm with the benchmark and full DRC.
   - Boards no variant can route: these need an engine fix. Trace them with the recipe above.

### Later (bigger decision)

If sweeps prove valuable, a "try several settings" mode could be built into Freerouting itself;
the API server's job scheduler could run the variants. Decide after step 5.

## Harness notes

- Current builds need Java 25; if the system `java` is older, pass `gap_bench.py run --java <path>`
  (Gradle provisions one under `~/.gradle/jdks/`).
- The flat `--router.max_threads` flag is ignored by current builds, so the script also passes
  `--router.autorouter.max_threads` / `--router.optimizer.max_threads`. Runs before this change
  used 11 optimizer threads per job.
- DRC every version's output with the same jar (`--drc-jar`); a jar with the padstack bug computes
  wrong pad shapes on the 12 colliding boards.

## Build note

`settings.gradle` bumps `foojay-resolver-convention` 0.8.0 → 1.0.0; 0.8.0 fails on Gradle 9.7.1
(`JvmVendorSpec.IBM_SEMERU` removed). Logged in `ERRORS.md`.

## Grid fallback stage (`router.grid_fallback`, on by default)

Branch `feature/grid-fallback-router`. After the batch autorouter, `autoroute.grid.GridFallbackRouter`
tries the connections that are still open: grid A* (`GridPathFinder`) from both ends, then grid
A* with negotiated rip-up of other nets' unfixed traces/vias, rerouting the ripped nets with the
maze router or the grid. Each attempt is an undo-stack transaction kept only if incomplete nets
(then connections) strictly drop and the full clearance count does not grow. Budget: at most 120 s
and at most half of the remaining job time; skipped when `router.autorouter.max_items` is set.

Result on the 33 PCBench boards where a current build leaves connections open (same jar, flag off vs
on, `gap_bench.py run ... --jar-args "grid2=--router.grid_fallback=true"`, 6 jobs in parallel):

| | flag off | flag on |
|---|--:|--:|
| boards better / worse / equal | | 24 / 0 / 9 |
| fully routed | 0 | 7 |
| sum of unrouted nets | 109 | 65 |
| sum of clearance violations | 1 | 1 |
| total wall time | 6031 s | 7537 s |

Re-run after merging the parity branch (strict_drc on, padstack and net-teardown fixes), same 33
boards, flag off vs on: 11 better / 0 worse, fully routed 14 -> 20, unrouted nets 49 -> 33,
clearance violations 0 -> 0, wall time 2566 s -> 3037 s.

Development harness: `GridProbeTest` runs only the stage on routed DSN + SES pairs
(`GRID_PROBE_LIST=<file of "dsn<TAB>ses"> ./gradlew test --rerun --tests '*GridProbeTest' -PincludeSlowTests=true`).
Note: the main router is not deterministic under CPU load, so compare flag off vs on from the same run.
