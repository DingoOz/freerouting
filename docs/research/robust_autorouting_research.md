# Robust Autorouting — Literature Review and Proposed Architecture

> **Status:** research and design proposal. Nothing here is implemented yet, except the grid fallback
> stage it builds on (`app.freerouting.autoroute.grid`, PR #2 on the DingoOz fork).
> **Date:** 2026-10-02

---

## TL;DR

Freerouting's core search, the gridless expansion-room maze router, is sound. What it lacks is
**memory of where routing is contested**:

- Rip-up cost today rises with the pass number for the whole board (`startRipupCosts × passNo`,
  divided by detour, randomised after pass 4).
- That is only the "present congestion" half of negotiated-congestion routing. It has no
  per-region history, so the router cannot learn that a net *not* currently in conflict has to
  move to make room.

Every robust router in the literature (PathFinder/VPR, NTHU-Route, FGR, BoxRouter, CUGR,
TritonRoute) has such a history term. Almost every production router also splits the work into a
cheap **global** stage, which plans corridors and resolves congestion where overlap is allowed,
and a **detailed** stage, which stays geometrically legal.

The proposal ("NCR-PCB") keeps the existing detailed router and adds five pieces around it:

1. A **pre-route routability analysis**: a congestion map, airline crossings and an escape-capacity
   check, used for net ordering and placement feedback.
2. A **global router on a coarse 3-D tile graph** with negotiated congestion, producing a corridor
   and layer plan per connection.
3. **Corridor-guided detailed routing**: the existing maze router, with a soft corridor penalty
   and a shared tile history cost.
4. **Negotiated detailed repair** in place of pass-wide rip-up: per-tile history,
   aggressor/victim queues and incremental reroute.
5. A **last-gasp stage**, extending today's grid fallback with trial-based ordering, repair windows
   and cut-capacity diagnosis.

Each piece ships behind its own setting and must pass a benchmark gate before it becomes the default.

---

## 1. Where Freerouting stands

**Evidence**

| Source | What it shows |
|---|---|
| Gap benchmark (`scripts/benchmark/gap_bench.py`, this fork) | Failures are almost always the **last 1–9 connections** on routine 2–4 layer boards. With the grid fallback, 11 of 33 failing boards improve and none get worse (`docs/v19-parity-notes.md`). |
| PCBWorld (Song et al., arXiv 2607.05915, 2026) | Freerouting 2.1.0 was the strongest rule-based baseline. Clean-pass rate (full connectivity and zero KiCad DRC errors) was **0.80** on 99 small real boards and **0.78** on 10 medium boards. On the medium set routing completion was **1.00** with a mean of **10.6 DRC violations**, so DRC, not completion, fails those boards. |
| FanoutNet (Li et al., AAAI 2023) | On the ASP-DAC 2021 PCB benchmarks (our `Issue508-DAC2020_bm*` fixtures), Freerouting v1.4.5.1 completed 93–99% of connections on the hard boards (bm3, bm4, bm11) and took more than 24 h. A commercial router (ELECTRA) took 1–11 s. |
| Liu et al., DAC 2023 / TCAD 2025 | Escape routing paper; states that "FreeRouting and Allegro cannot complete escape routing" on their industrial BGA cases. |

**Mechanics** (verified in code)

- **Pass-wide rip-up.** `MazeRipupResolver` computes the rip-up cost as `ripupCosts × width / detour`,
  where `ripupCosts = startRipupCosts × passNo`. The cost is the same everywhere on the board. Rip-up
  history is per item (`RoutingFailureLog`), not per region.
- **Fixed net order, global stop rules.** Within a pass the order is fixed (`reorderSingleThreadItems`
  only reshuffles between passes). Stagnation is detected globally: 10 passes without improvement,
  plus `BoardHistory` restores.
- **One label per door section.** The maze search allows one occupant per door section
  (`MazeSearchElement.isOccupied`). With path-dependent costs (bends, rip-ups) this can discard a
  cheaper continuation that arrives later. Contour (Dion & Monier 1995) keeps several non-dominated
  paths per tile for exactly this reason.
- **Last gasp.** The grid fallback (`GridFallbackRouter`) already acts as a strict mode: transactional
  rip-up with per-net history and a no-new-violation gate.

---

## 2. What the literature says

### 2.1 Negotiated congestion (the core idea)

**PathFinder** (McMurchie & Ebeling, FPGA 1995) routes every net every iteration, allows resources
to be over-used, and prices them:

```
cost(n) = (b_n + h_n) · p_n
  b_n  base cost (length / delay)
  p_n  present-congestion factor; 1 in iteration 1, grows with current sharing
  h_n  history; grows slightly in every iteration where n was over-used
```

`p` resolves first-order conflicts. `h` resolves **second-order** ones: a net that touches no
congested resource still has to move so another net can pass. Classic sequential rip-up cannot
detect that this net needs rerouting. If the increase is too abrupt, net ordering starts to matter
again (the paper's own warning).

**VPR**, the production implementation (verified in `vpr/src/route/route_common.cpp`):

```
cost = base · acc · pres
pres = 1 + pres_fac · (occ + 1 − cap)       when occ ≥ cap
acc += (occ − cap) · acc_fac                 after each iteration
```

Defaults: `initial_pres_fac` 0.5, `pres_fac_mult` 1.3, `max_pres_fac` 1000, `acc_fac` 1,
`bb_factor` 3, `astar_fac` 1.2, at most 50 iterations, and a precomputed map lookahead.

**ASIC refinements that matter for us**

| Router | What it adds |
|---|---|
| **FGR** (Roy & Markov, TCAD 2008) | Keeps base cost separate, `c = b + h·p` with `p = exp(k(ω−1))` for ω > 1, and adds a **"last gasp" mode**: once overflow is tiny, route only through free capacity at base cost. More than 75% of its iterations were spent at under 0.01% overflow before this fix. |
| **BoxRouter 2.0** (Cho et al., ICCAD 2007) | Scales the present term by `α = max h / p` so history cannot outgrow present congestion. Without that, overflow "spins out of control". |
| **NTHU-Route 2.0, FastRoute, CUGR** | History with **decay** (×0.9 in FastRoute, ×0.01 in CUGR, decayed counters in TritonRoute), sigmoid congestion costs that sharpen over iterations, search windows that grow when a route hits the boundary, and rerouting congested regions in bounding-box order. CUGR also lowers via cost over iterations. |
| **AIR** (Murray et al., ASP-DAC 2020) | Incremental rerouting: keep a net's legal sub-trees and reroute only the illegal parts. Dynamic bounding box. Keeps the best result after the first legal solution. 7× faster than VPR 7. |
| **TritonRoute-WXL** (Kahng et al., TCAD 2021) | Detailed repair is driven by DRC markers. Earlier-routed nets in a violation (aggressors) are queued for reroute; the last-routed net (victim) only for a re-check. A per-net reroute cap bounds the work, and repair windows are shifted between iterations. Runtime fell 33.5% and every testcase converged. |

### 2.2 Negotiated congestion on PCBs

- **NCER** (Ma, Yan & Wong, ISQED 2010): negotiated congestion for PCB escape routing. It and Allegro
  each completed 7 of 14 industrial cases, and together 11. The two methods are complementary.
- **Layered NC** (McDaniel et al., TCAD 2017): negotiate one layer at a time, then push failed nets
  to the next layer.
- **Lin et al., DAC 2021**: complete PCB flow of simultaneous escape (which fixes layer and escape
  order), then refinement, then gridless area routing. Completed 7 commercial boards that a
  commercial tool could not.
- **Bus planning** (Kong, Yan & Wong, DAC 2009): PCB "global routing" as layer assignment plus a
  planar route per layer.

No paper applies history costs to an expansion-room (Specctra-style) router. The closest working
precedent is gEDA/pcb-rnd's `rt_hace` rectangle-expansion router. It routes *through* other nets'
copper with a conflict penalty that rises each pass, then rips up the overlapped nets — the same
family of router as Freerouting's.

### 2.3 Ordering, last connections and routability checks

- **B-Escape** (Luo et al., ISPD 2010): **dynamic trial ordering**. Tentatively route every remaining
  net, score it by the pins it *traps* (makes unroutable) and *blocks*, commit the least damaging
  one, and backtrack on traps. 14/14 industrial benchmarks routed against 7/14 for Allegro.
- **gEDA toporouter**: orders nets by pairwise trial-route conflicts. Its ROAR repair rips up
  conflicting routes and **rolls back the whole step** if more than 2 or 5 reroutes fail.
- **Maley's theorem / Yu & Dai** (UCSC-CRL-97-07, 1997): a topological routing is realisable if and
  only if every critical *cut* (a segment between two obstacles) has `Σ(width + clearance)` of the
  crossing wires ≤ its free length. The check is gridless and incremental, and identifies exactly
  which wires overfill a gap.
- **Contour** (Dion & Monier, DEC WRL 95/3): bidirectional A* over corner-stitched tiles. If one
  frontier dies early, that terminal is boxed in, which tells the router where to rip up.

### 2.4 Two-stage handover

- **FastRoute → TritonRoute** (OpenROAD): the global stage outputs route *guides* (boxes of tiles per
  layer). The detailed router follows them strictly only in its first iterations and treats them
  as a cost bias afterwards.
- **Dr.CU**: guides become an out-of-guide edge penalty, never a hard wall.
- **Altium Situs** and **TopoR**: topological global planning, then geometric push-and-shove or
  cleanup passes. Both still rely on a geometric detailed stage.

### 2.5 What not to build

- **A full topological (rubber-band) rewrite.** Every open-source attempt is self-described as
  experimental or incomplete: gEDA `toporouter.c`, pcb-rnd `rt_topo`, Salewski's router. The
  commercial ones still end with geometric passes.
- **ML/RL routing.** FanoutNet's gains come from *fanout point selection*, not routing.
  Liao et al. (ESWA 2026) use RL only to choose Freerouting's *net order*. The commercial RL routers
  (DeepPCB, Quilter) publish no reproducible benchmarks. An ordering policy could be learned later;
  the router should not be.
- **Reusing KiCad's PNS.** It is a local walkaround/shove engine in C++, useful as a finisher behind a
  global planner, not as a router, and it cannot be called from Java.

---

## 3. Proposed architecture: NCR-PCB

```
DSN ─▶ [0] Routability analysis ─▶ [1] Global NCR on tile graph ─▶ [2] Corridor-guided detailed routing
                                              ▲                                 │
                                              └───── shared tile history h ◀────┤
                                                                                ▼
        [5] Optimizer + DRC gate ◀── [4] Last gasp (grid fallback++) ◀── [3] Negotiated detailed repair
```

### Stage 0 — Routability analysis (cheap, always on)

Inputs are the board after loading and fanout. Outputs:

- **Tile graph.** One coarse 3-D grid per signal layer, with a pitch of about 4–8 trace pitches
  (`trace width + clearance`) clamped to roughly 0.5–2 mm.
  - **Edge capacity** = (free length of the shared tile border on that layer, after subtracting fixed
    obstacles: pins, keepouts, outline, fixed traces) / (width + clearance) of the net class.
  - **Via-site capacity** per tile = via sites that fit, after subtracting fixed obstacles.
  - Built with `ShapeSearchTree` queries, the same queries `GridPathFinder` already uses.
- **Demand estimate (RUDY).** Smear each airline's (width + clearance) over its bounding box. Plane
  nets (`Net.containsPlane()`) are excluded; they connect by vias to the plane.
- **Airline crossings per layer pair**, a lower bound on the vias or detours that will be needed.
- **Escape check.** For each fine-pitch or BGA component, compare the pins that must escape with
  the escape-channel capacity (Maley cuts between adjacent pads). Pins that cannot escape on any
  layer are reported as **dead** and skipped by later stages.

These outputs drive **net ordering**: dead connections excluded, then hardest first by demand ÷
capacity along the bounding box, then the shortest. They also drive a **user-facing report**:
over-subscribed regions and probably-unroutable connections, which serves as placement feedback
(see §6).

### Stage 1 — Global negotiated-congestion routing

- **Unit:** two-pin connections from the existing airline decomposition. Multi-pin nets keep their
  partial tree as a multi-source seed (PathFinder/Prim).
- **Search:** A* over the tile graph, with wire edges per layer and via edges between layers.
  - The heuristic is via-aware: octile distance plus the minimum number of layer changes needed to
    reach the target's layers, times the via base cost.
  - The search box is the airline's bounding box × 3, grown whenever the route touches its edge.
- **Edge cost** (FGR form with BoxRouter scaling):

  ```
  c(e) = b(e) + α · h(e) · p(e)
  b(e) = length (or via cost for via edges)
  p(e) = 1 + pres_fac · max(0, use(e) + w − cap(e)) / cap(e)    pres_fac: 0.5 × 1.3 per iteration, capped
  h(e) ← 0.9·h(e) + acc_fac · overflow(e)                       after each iteration (decayed history)
  α    = keeps α·h·p of a currently over-full edge above the history of a clean edge
  ```

- **Iterations:** reroute only connections that touch over-used edges (AIR/FGR), at most 20–50
  iterations, and keep the best-overflow solution (FastRoute).
- **Last gasp:** when overflow drops below about 1% of edges, switch to free-capacity-only edges at
  base cost (FGR).
- **Output per connection:** a **corridor** (a set of tiles per layer) and preferred via tiles.
- **Cost:** at tens of tiles per side, this stage is milliseconds to seconds even on 1000-net boards.

### Stage 2 — Corridor-guided detailed routing

The existing `AutorouteEngine` and expansion-room maze search are kept, with two additions to the
expansion cost of a room or door:

- **Out-of-corridor penalty**, soft: strong in the first pass, fading afterwards (TritonRoute
  follows guides only in its first iterations).
- **Tile history**, the same `h` from Stage 1, read at the tile the door lies in.

Net order comes from Stage 0. Rip-up keeps today's mechanism, but its cost is multiplied by the
history of the tile where the rip-up happens, instead of rising with `passNo` across the whole
board.

### Stage 3 — Negotiated detailed repair (replaces pass-wide rip-up)

- **Feedback to the global stage.** When a connection fails or rips up a foreign connection, add
  overflow to the tiles involved, which feeds `h` and therefore Stage 1. Every K repair rounds, or
  on stagnation, re-run Stage 1 incrementally so corridors move away from contested tiles.
- **Aggressor/victim queue** (TritonRoute). A rip-up queues the ripped connection (the aggressor)
  for reroute. Each connection has a reroute cap (for example 8); at the cap it is parked for
  Stage 4.
- **Incremental reroute** (AIR). Rip up only the conflicting connection segment and keep the rest of
  the net's tree.
- **Determinism.** Each candidate gets a seeded RNG with stable tie-breaks, so a run is reproducible
  given `(board, settings, seed)`.

### Stage 4 — Last gasp (extend the existing grid fallback)

- **Trial-based ordering** (B-Escape, toporouter). For the remaining N connections (N small),
  tentatively route each one, count the other remaining connections it traps or blocks, commit
  the least damaging, and backtrack on traps.
- **Repair windows** (TritonRoute, ROAR). Take the airline's bounding box plus k clearances, rip up
  every unfixed item in it, and reroute the contents under several orderings. Grow and shift the
  window when stuck. Keep the existing acceptance rule: strictly fewer incomplete nets or
  connections and no new clearance violations.
- **Cut-capacity diagnosis** (Maley). For a failing connection, compute the cuts along its corridor.
  Rip up exactly the foreign wires on over-full cuts. If a cut is over-full on every layer, the
  connection is reported as placement-limited instead of being retried.
- **Bidirectional search** (Contour), to detect a boxed-in terminal early.

### Stage 5 — Optimizer and DRC gate

Unchanged, except that the benchmarks' acceptance judge becomes **KiCad DRC**. PCBWorld shows that
DRC-only failures (copper-to-edge, rules not carried in the DSN) cost as much clean-pass rate as
incomplete routing.

### Cross-cutting

- **Time budgets** derived from the job's remaining time and enforced at every inner call. The grid
  fallback taught this the hard way (see `ERRORS.md`: pull-tight ignores its limit).
- **Portfolio.** Run up to N seeds or parameter sets in parallel workers, give more time to the
  leaders, and keep the best board. PCBWorld's best-of-5 and tscircuit's supervisor do this.
- **Best-board tracking** (already in `BoardHistory`), plus a convergence predictor: stop when the
  trend in unrouted connections cannot reach zero within the budget (VPR's failure predictor).

---

## 4. Implementation plan

Each phase sits behind its own `router.*` setting, defaults to off until it passes its gate, and is
benchmarked with `gap_bench.py` flag-off vs flag-on in the same run.

| Phase | Deliverable | Gate | Effort |
|---|---|---|---|
| **P0** | Benchmarks: add PCBWorld D3-A/B and the full ASP-DAC bm1–bm11 set; report clean-pass, completion, KiCad DRC and wall time. Fix the bm numbering note (fixture `bm04` = paper bm3, …). | Baseline numbers recorded | S |
| **P1** | Stage 0 tile graph, RUDY demand, crossings, escape check. Analysis report only; net ordering by difficulty. | No regressions; at least 1 connection gained on the gap set | M |
| **P2** | Tile-history cost in the maze search (Stage 2 without corridors). History accumulates where rip-ups and failures happen; it replaces the global `× passNo` ramp. | More fully routed boards on the gap set, 0 new violations | M |
| **P3** | Stage 4 extensions to the grid fallback: trial ordering, repair windows, cut diagnosis. | More completions than today's grid fallback on its 33 boards | M |
| **P4** | Stage 1 global NCR with corridor guidance (Stage 2 complete). | Completion and time better on bm1–bm11 and PCBWorld D3 | L |
| **P5** | Stage 3 aggressor/victim queue, incremental reroute, convergence predictor. | Fewer passes and less time at equal or better completion | M |
| **P6** | Seeded portfolio with best-board selection. | Clean-pass improves at a fixed wall-time budget | M |
| *opt.* | BGA escape pre-pass (escape order and layer per net), via network flow or NCER. | bm boards with BGAs complete | L |

P2 is the cheapest step towards history costs and tests the core hypothesis before the larger
global stage (P4) is built. P3 extends code that already exists and is measured.

**Safety rules** (from `AGENTS.md`):

- Every stage must preserve the "no new clearance violations" invariant. Gate on the full
  `getAllClearanceViolations()` count, not a per-new-item check (logged in `ERRORS.md`).
- Run the fixture tests and the gap benchmark before any default changes.

---

## 5. Open questions

1. **Tile size versus 45° traces.** Diagonal routes cross tile borders at √2 the length. The literature
   does not settle diagonal capacity (Yan & Wong 2009 do for escape grids). Start with border
   capacity, measure, then refine.
2. **Plane nets.** Exclude them from demand and route them as via drops to the plane, as the current
   plane-routing mode does. Confirm this with the Issue 093 plane-routing clearance bug in mind.
3. **Multi-threading.** Stage 1 is cheap and can stay single-threaded. Stages 2 and 3 already have
   per-pass worker boards, and the shared history map needs a merge step after each pass.
4. **Corridor rigidity.** How quickly to fade the corridor penalty is empirical; TritonRoute's "first
   3 iterations" is the only published guide.

---

## 6. Placement feedback

Freerouting receives placement fixed from KiCad and cannot move parts. Stage 0 can still say
**why** a board fails before routing starts:

- over-subscribed regions (RUDY demand ÷ capacity);
- airline crossings per layer pair;
- components whose pins cannot escape.

This is how the analytic and routability-driven placement literature (RUDY, crossing count, pin
density) judges placements. Reporting it to the user, or to the KiCad plugin, turns "1 unrouted
connection" into an actionable placement hint.

---

## Sources

The research agents read these in full text or source code unless marked (abstract) or
(not opened).

**Negotiated congestion and two-stage routing**
- McMurchie & Ebeling, "PathFinder: A Negotiation-Based Performance-Driven Router for FPGAs", FPGA 1995. https://www.esa.informatik.tu-darmstadt.de/archive/twiki/pub/Lectures/AlgorithmenImChipEntwurfDe/router-pathfinder.pdf
- VTR/VPR source: https://github.com/verilog-to-routing/vtr-verilog-to-routing (`vpr/src/route/route_common.cpp`, `vpr/src/base/read_options.cpp`)
- Murray, Zhong & Betz, "AIR: A Fast but Lazy Timing-Driven FPGA Router", ASP-DAC 2020. https://web.archive.org/web/2023/https://www.eecg.utoronto.ca/~kmurray/air/aspdac2020_air.pdf
- Gao, Wu & Wang, "NTHU-Route", ASP-DAC 2008 (slides). https://www.aspdac.com/aspdac2008/Archive_Folder/3A_Slides/3A-2.pdf
- Chang, Lee & Wang, "NTHU-Route 2.0", ICCAD 2008 (code: https://github.com/luckyrantanplan/nthu-route)
- Cho, Lu, Yuan & Pan, "BoxRouter 2.0", ICCAD 2007. https://www.cerc.utexas.edu/utda/publications/iccad_07_boxrouter_2.0.pdf
- Roy & Markov, "High-performance Routing at the Nanometer Scale" (FGR), IEEE TCAD 2008. https://web.eecs.umich.edu/~imarkov/pubs/jour/tcad08-fgr.pdf
- Liu, Pui, Wang & Young, "CUGR", DAC 2020 (code: https://github.com/cuhk-eda/cu-gr)
- Kahng, Wang & Xu, "TritonRoute-WXL", IEEE TCAD 2021. https://vlsicad.ucsd.edu/Publications/Journals/j136.pdf
- Chen et al., "Dr. CU", ASP-DAC 2019 (slides). https://www.aspdac.com/aspdac2019/archive/pdf/10C-1.pdf
- OpenROAD FastRoute and TritonRoute source: https://github.com/The-OpenROAD-Project/OpenROAD (`src/grt`, `src/drt`)

**PCB routing**
- Ma, Yan & Wong, "A Negotiated Congestion based Router for Simultaneous Escape Routing", ISQED 2010 (abstract).
- McDaniel et al., "PCB Escape Routing and Layer Minimization for DMFBs", IEEE TCAD 2017.
- Ozdal & Wong, "Algorithms for simultaneous escape routing and layer assignment of dense PCBs", IEEE TCAD 2006 (abstract).
- Yan & Wong, "A correct network flow model for escape routing", DAC 2009; TCAD 2012 (abstract).
- Luo, Yan, Ma, Wong & Shibuya, "B-Escape", ISPD 2010 (slides). https://ispd.cc/ispd2026/slides/2010/2_05.pdf
- Liu et al., "A matching based escape routing algorithm…", DAC 2023 / TCAD 2025 (abstract).
- Lin, Wang, Kuo, Chen & Li, "A complete PCB routing methodology with concurrent hierarchical routing", DAC 2021 (abstract).
- Lin, Merrill, Wu, Holtz & Cheng, "A unified PCB routing algorithm with complicated constraints and differential pairs", ASP-DAC 2021 (not opened).
- Kong, Yan & Wong, "Automatic bus planner for dense PCBs", DAC 2009 (abstract).
- Dion & Monier, "Contour: a tile-based gridless router", DEC WRL 95/3, 1995. https://ftp.mirrorservice.org/sites/www.bitsavers.org/pdf/dec/tech_reports/WRL-95-3.pdf
- Dayan & Dai, "Layer assignment for rubber band routing", UCSC-CRL-93-04. https://tr.soe.ucsc.edu/sites/default/files/technical-reports/UCSC-CRL-93-04.pdf
- Yu & Dai, "Fast and incremental routability check of a topological routing using a cut-based encoding", UCSC-CRL-97-07. https://tr.soe.ucsc.edu/sites/default/files/technical-reports/UCSC-CRL-97-07.pdf
- Li, Zhang, Xu & Liu, "FanoutNet", AAAI 2023. https://ojs.aaai.org/index.php/AAAI/article/view/26030
- Song et al., "PCBWorld", arXiv 2607.05915, 2026. https://arxiv.org/abs/2607.05915
- Liao, Pan & Chiang, "Automation of PCB autorouting via world-model RL and Freerouting integration", ESWA 2026 (abstract).
- ASP-DAC 2021 PCB benchmarks (our `DAC2020_bm*` fixtures): https://github.com/aspdac-submission-pcb-layout/PCBBenchmarks

**Router implementations**
- KiCad push-and-shove router: https://gitlab.com/kicad/code/kicad/-/tree/master/pcbnew/router
- gEDA toporouter: https://raw.githubusercontent.com/bert/pcb/master/src/toporouter.c
- pcb-rnd route-rnd (`rt_hace`, `rt_topo`): http://www.repo.hu/projects/route-rnd/state.html
- Altium Situs: https://www.altium.com/documentation/altium-circuitmaker/automated-board-layout-situs-topological-autorouter?version=5
- OrthoRoute: https://github.com/bbenchoff/OrthoRoute
- tscircuit autorouter: https://blog.autorouting.com/p/hypergraph-autorouting
