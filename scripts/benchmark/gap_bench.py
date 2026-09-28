#!/usr/bin/env python3
"""Linux-friendly v1.9-gap benchmark: route a fixture list with several jars, DRC every
result with one reference jar, and compare completion / clearance / time.

  gap_bench.py select [--max-seconds 60] [--controls 30] > fixtures.txt
  gap_bench.py run --jar v19=binaries/freerouting-1.9.0.jar --jar wip=../../build/libs/freerouting-current-executable.jar fixtures.txt
  gap_bench.py report [--base v19 --head wip]

Selection reads the nightly results/benchmarks.json: fixtures where 1.9.0 beat the newest
release (fewer unrouted, then fewer violations), plus a random sample of fixtures where the
newest release wins, so regressions show up too. Results go to results/comparison-v19-gap/
(git-ignored) as runs.jsonl; `run` resumes by skipping (label, fixture) pairs already there.
"""
import argparse, json, os, random, shutil, subprocess, sys, time
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

HERE = Path(__file__).resolve().parent
OUT = HERE / "results" / "comparison-v19-gap"
RUNS = OUT / "runs.jsonl"
DEFAULT_DRC_JAR = HERE.parent.parent / "build" / "libs" / "freerouting-current-executable.jar"


def quality_key(q):
    big = 10**9
    return tuple(big if q[k] is None else q[k] for k in ("unrouted_connections", "clearance_violations"))


def select(a):
    runs = json.load(open(HERE / "results" / "benchmarks.json"))["runs"]
    by = defaultdict(dict)
    for r in runs:
        by[r["fixture"]["relative_path"]][r["binary"]["version_label"]] = r
    newest = max({r["binary"]["version_label"] for r in runs} - {"1.9.0"})
    gaps, wins = [], []
    for f, v in sorted(by.items()):
        old, new = v.get("1.9.0"), v.get(newest)
        if not old or not new or (new["quality"]["wall_clock_seconds"] or 1e9) > a.max_seconds:
            continue
        ko, kn = quality_key(old["quality"]), quality_key(new["quality"])
        if a.clean_only and ko != (0, 0):
            continue
        (gaps if ko < kn else wins if ko > kn else []).append(f)
    random.Random(1).shuffle(wins)
    print(f"# v1.9 gap set vs {newest}: {len(gaps)} gaps + {min(a.controls, len(wins))} controls", file=sys.stderr)
    for f in gaps:
        print(f"gap\t{f}")
    for f in wins[: a.controls]:
        print(f"control\t{f}")


def drc(jar, dsn, ses, report, timeout):
    if not ses.exists():
        return None, None
    subprocess.run(["java", "-Djava.awt.headless=true", "-jar", str(jar), "-de", f"{dsn}+{ses}", "-drc", str(report), "--gui.enabled=false"],
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=timeout)
    rep = json.load(open(report))
    unconnected = [u for u in rep.get("unconnectedItems") or rep.get("unconnected_items") or []
                   if u.get("type") in ("unconnectedItems", "unconnected_items")]
    clearance = [v for v in rep.get("violations") or [] if "clearance" in str(v.get("type"))]
    return len(unconnected), len(clearance)


def run_one(a, label, jar, kind, rel):
    dsn = HERE / "fixtures" / rel
    stem = f"{label}--{rel.replace('/', '--').replace(' ', '_').replace('+', '_')}"
    ses, log = OUT / "outputs" / f"{stem}.ses", OUT / "logs" / f"{stem}.log"
    ses.unlink(missing_ok=True)
    v19 = "1.9" in jar.name
    cmd = ["java", f"-Xmx{a.heap}", "-Dsun.stdout.buffered=false"] + ([] if v19 else ["-Djava.awt.headless=true"])
    cmd += ["-jar", str(jar), "-de", str(dsn), "-do", str(ses),
            f"--router.max_threads={a.threads}", f"--router.job_timeout={a.timeout}",
            "--router.optimizer.enabled=true", "--router.fanout.enabled=true",
            f"--router.autorouter.max_passes={a.max_passes}", "--router.autorouter.enabled=true"]
    if v19:
        cmd += ["-dct", "0"]  # v1.9 has no headless mode; skip its 20 s auto-start countdown dialog
    else:
        cmd += ["--api_server.enabled=false", "--gui.enabled=false"]
    h, m, s = map(int, a.timeout.split(":"))
    t0, state = time.time(), "COMPLETED"
    with open(log, "w") as lf:
        try:
            env = dict(os.environ, DISPLAY=a.display) if v19 else None  # v1.9 GUI goes to the virtual display
            rc = subprocess.run(cmd, env=env, stdout=lf, stderr=subprocess.STDOUT, timeout=h * 3600 + m * 60 + s + 60).returncode
            state = "COMPLETED" if rc == 0 else f"EXIT_{rc}"
        except subprocess.TimeoutExpired:
            state = "KILLED"
    wall = round(time.time() - t0, 2)
    try:
        unrouted, clearance = drc(a.drc_jar, dsn, ses, ses.with_suffix(".drc.json"), 600)
    except Exception as e:  # DRC failures are recorded, not fatal
        unrouted, clearance, state = None, None, f"{state}+DRC_FAIL:{type(e).__name__}"
    return dict(label=label, jar=jar.name, kind=kind, fixture=rel, state=state, wall_s=wall,
                unrouted=unrouted, clearance=clearance)


def run(a):
    (OUT / "outputs").mkdir(parents=True, exist_ok=True)
    (OUT / "logs").mkdir(parents=True, exist_ok=True)
    jars = [(l, Path(p).resolve()) for l, p in (j.split("=", 1) for j in a.jar)]
    fixtures = [ln.rstrip("\n").split("\t", 1) for ln in open(a.fixtures) if ln.strip() and not ln.startswith("#")]
    done = {(r["label"], r["fixture"]) for r in load()}
    todo = [(l, j, k, f) for k, f in fixtures for l, j in jars if (l, f) not in done]
    xvfb = None
    if any("1.9" in j.name for _, j in jars):
        # v1.9 cannot run without a GUI window, so give it an invisible X server instead of the desktop.
        if not shutil.which("Xvfb"):
            sys.exit("v1.9 needs a virtual display so its windows stay off your desktop: sudo apt-get install -y xvfb")
        a.display = ":%d" % (90 + os.getpid() % 100)
        xvfb = subprocess.Popen(["Xvfb", a.display, "-screen", "0", "1920x1080x24", "-nolisten", "tcp"],
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        time.sleep(1)
        if xvfb.poll() is not None:
            sys.exit(f"Xvfb failed to start on {a.display}")
    try:
        _run(a, todo, done)
    finally:
        if xvfb:
            xvfb.terminate()


def _run(a, todo, done):
    print(f"{len(todo)} runs to do ({len(done)} already done), {a.jobs} in parallel", file=sys.stderr)
    with ThreadPoolExecutor(a.jobs) as pool, open(RUNS, "a") as out:
        for i, r in enumerate(pool.map(lambda t: run_one(a, *t), todo), 1):
            out.write(json.dumps(r) + "\n")
            out.flush()
            print(f"[{i}/{len(todo)}] {r['label']:>6} {r['state']:<10} unrouted={r['unrouted']} "
                  f"clearance={r['clearance']} {r['wall_s']}s  {r['fixture']}", file=sys.stderr)


def load():
    return [json.loads(l) for l in open(RUNS)] if RUNS.exists() else []


def report(a):
    rows = load()
    by = defaultdict(dict)
    for r in rows:
        by[r["fixture"]][r["label"]] = r
    labels = sorted({r["label"] for r in rows})
    print(f"| label | kind | runs | fully routed | clean (0 unrouted, 0 clearance) | sum unrouted | sum clearance | total s |")
    print("|---|---|--:|--:|--:|--:|--:|--:|")
    for kind in ("gap", "control"):
        for l in labels:
            rs = [v[l] for v in by.values() if l in v and v[l]["kind"] == kind]
            if not rs:
                continue
            ok = [r for r in rs if r["unrouted"] is not None]
            print(f"| {l} | {kind} | {len(rs)} | {sum(r['unrouted'] == 0 for r in ok)} | "
                  f"{sum(r['unrouted'] == 0 and r['clearance'] == 0 for r in ok)} | {sum(r['unrouted'] for r in ok)} | "
                  f"{sum(r['clearance'] for r in ok)} | {sum(r['wall_s'] for r in rs):.0f} |")
    if a.base in labels and a.head in labels:
        key = lambda r: (10**9, 10**9) if r["unrouted"] is None else (r["unrouted"], r["clearance"])
        pairs = [(f, v[a.base], v[a.head]) for f, v in sorted(by.items()) if a.base in v and a.head in v]
        worse = [p for p in pairs if key(p[2]) > key(p[1])]
        better = [p for p in pairs if key(p[2]) < key(p[1])]
        print(f"\n{a.head} vs {a.base}: {len(better)} better, {len(worse)} worse, {len(pairs) - len(better) - len(worse)} equal "
              f"(key = unrouted, then clearance)\n")
        for tag, ps in (("worse", worse), ("better", better)):
            for f, b, h in ps:
                print(f"{tag:6} {b['kind']:7} {a.base}=({b['unrouted']},{b['clearance']}) {b['wall_s']:>7}s  "
                      f"{a.head}=({h['unrouted']},{h['clearance']}) {h['wall_s']:>7}s  {f}")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sp = p.add_subparsers(dest="cmd", required=True)
    s = sp.add_parser("select")
    s.add_argument("--max-seconds", type=float, default=60, help="skip fixtures slower than this on the newest release")
    s.add_argument("--controls", type=int, default=30)
    s.add_argument("--clean-only", action="store_true", help="only gaps where 1.9.0 was fully clean")
    r = sp.add_parser("run")
    r.add_argument("fixtures")
    r.add_argument("--jar", action="append", required=True, help="label=path/to.jar (repeatable)")
    r.add_argument("--drc-jar", type=Path, default=DEFAULT_DRC_JAR, help="jar used to DRC every .ses")
    r.add_argument("-j", "--jobs", type=int, default=max(1, (os.cpu_count() or 2) // 3))
    r.add_argument("--heap", default="2g")
    r.add_argument("--threads", type=int, default=1)
    r.add_argument("--max-passes", type=int, default=500)
    r.add_argument("--timeout", default="00:10:00")
    q = sp.add_parser("report")
    q.add_argument("--base", default="v19")
    q.add_argument("--head", default="wip")
    a = p.parse_args()
    {"select": select, "run": run, "report": report}[a.cmd](a)


if __name__ == "__main__":
    main()
