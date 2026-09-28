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

## Next steps

1. Run the comparison and read `report`: which boards does `master` still lose to 1.9?
2. For those boards, diff the 1.9 and `master` logs (`results/comparison-v19-gap/logs/`) to find
   where they split: fanout, connection selection, rip-up budget or stop rules.
3. Fix the smallest cause first; re-run the benchmark and confirm the gap narrows with no new
   clearance violations (see the exit criteria in `AGENTS.md`).

## Build note

`settings.gradle` bumps `foojay-resolver-convention` 0.8.0 → 1.0.0; 0.8.0 fails on Gradle 9.7.1
(`JvmVendorSpec.IBM_SEMERU` removed). Logged in `ERRORS.md`.
