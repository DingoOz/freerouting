package app.freerouting.autoroute.grid;

import app.freerouting.autoroute.maze.AutorouteControl;
import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.Item;
import app.freerouting.board.model.items.Via;
import app.freerouting.board.model.structure.AngleRestriction;
import app.freerouting.board.model.structure.FixedState;
import app.freerouting.core.library.Padstack;
import app.freerouting.drc.AirLine;
import app.freerouting.geometry.planar.ConvexShape;
import app.freerouting.geometry.planar.IntPoint;
import app.freerouting.geometry.planar.Point;
import app.freerouting.geometry.planar.Polyline;
import app.freerouting.geometry.planar.TileShape;
import app.freerouting.geometry.planar.Vector;
import app.freerouting.rules.ViaInfo;
import app.freerouting.settings.RouterSettings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.ToDoubleFunction;

/**
 * A* search for one airline on a uniform grid with 45-degree (or 90-degree) moves and vias. The
 * grid is anchored on the airline's start point and every edge is checked against the real board
 * obstacles with clearance, so a found path is an insertion candidate, not an approximation. The
 * caller still verifies the inserted result with the clearance checker.
 */
public final class GridPathFinder {

  /** How items of other nets are treated by the search. */
  public enum Mode {
    /** Everything currently on the board blocks. */
    ALL_OBSTACLES,
    /** Unfixed traces and vias of other nets can be crossed at a cost and are reported. */
    RIPUP,
    /** Unfixed traces and vias of other nets are ignored (diagnostic: is this net boxed in?). */
    FIXED_ONLY
  }

  /** One path corner. Consecutive steps on different layers at the same point are a via. */
  public record Step(IntPoint point, int layer) {}

  /** Search outcome; {@code path} is null when nothing was found. */
  public record Result(List<Step> path, Set<Item> ripped, int expansions, String reason) {
    /** Returns true when a path was found. */
    public boolean found() {
      return path != null;
    }
  }

  private record Open(double f, double g, long key) {}

  private static final double BLOCKED = -1;
  private static final int OFFSET = 1 << 23;
  private static final int[][] DIRS_45 = {
    {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
  };
  private static final int[][] DIRS_90 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

  private final RoutingBoard board;
  private final AirLine airline;
  private final Mode mode;
  private final ToDoubleFunction<Item> ripCost;
  private final AutorouteControl ctrl;
  private final int net;
  private final int[] netNumbers;
  private final int pitch;
  private final IntPoint origin;
  private final IntPoint target;
  private final ViaInfo via;
  private final Via viaPrototype;
  private final boolean ninetyDegree;
  private final Map<Long, Double> viaCache = new HashMap<>();

  /**
   * Sets up a search for one airline.
   *
   * @param mode how foreign traces and vias are treated
   * @param pitchFactor grid pitch as a multiple of (trace width + trace clearance)
   * @param ripCost cost, in grid steps, of crossing a foreign unfixed item in {@link Mode#RIPUP};
   *     may be null in the other modes
   */
  public GridPathFinder(
      RoutingBoard board,
      RouterSettings settings,
      AirLine airline,
      Mode mode,
      double pitchFactor,
      ToDoubleFunction<Item> ripCost) {
    this.board = board;
    this.airline = airline;
    this.mode = mode;
    this.ripCost = ripCost;
    this.net = airline.net.netNumber;
    this.netNumbers = new int[] {net};
    this.ctrl = new AutorouteControl(board, net, settings);
    int maxHalfWidth = 0;
    int maxClearance = 0;
    for (int l = 0; l < board.getLayerCount(); l++) {
      maxHalfWidth = Math.max(maxHalfWidth, ctrl.traceHalfWidth[l]);
      maxClearance =
          Math.max(
              maxClearance,
              board.clearanceValue(
                  ctrl.traceClearanceClassIndex, ctrl.traceClearanceClassIndex, l));
    }
    this.pitch = Math.max(1, (int) Math.round(pitchFactor * (2 * maxHalfWidth + maxClearance)));
    this.origin = airline.fromCorner.round();
    this.target = airline.toCorner.round();
    this.via = ctrl.viasAllowed && ctrl.viaRule.viaCount() > 0 ? ctrl.viaRule.getVia(0) : null;
    this.viaPrototype =
        via == null
            ? null
            : new Via(
                via.getPadstack(),
                origin,
                netNumbers,
                via.getClearanceClassIndex(),
                Integer.MAX_VALUE, // never inserted; avoids consuming a real item id
                0,
                FixedState.UNFIXED,
                via.attachSmdAllowed(),
                board);
    this.ninetyDegree = board.rules.getTraceAngleRestriction() == AngleRestriction.NINETY_DEGREE;
  }

  /** Returns the grid pitch in board units. */
  public int pitch() {
    return pitch;
  }

  /** Runs the search with an expansion and wall-clock budget. */
  public Result search(int maxExpansions, long maxMillis) {
    long deadline = System.currentTimeMillis() + maxMillis;
    PriorityQueue<Open> open = new PriorityQueue<>((a, b) -> Double.compare(a.f(), b.f()));
    Map<Long, Double> best = new HashMap<>();
    Map<Long, Long> parent = new HashMap<>();
    for (int l = 0; l < board.getLayerCount(); l++) {
      if (ctrl.layerActive[l] && airline.fromItem.isOnLayer(l)) {
        long key = key(0, 0, l);
        best.put(key, 0.0);
        open.add(new Open(heuristic(origin), 0.0, key));
      }
    }
    double viaCost = 8.0 * pitch;
    int[][] dirs = ninetyDegree ? DIRS_90 : DIRS_45;
    int expansions = 0;
    while (!open.isEmpty()) {
      Open entry = open.poll();
      long key = entry.key();
      double g = entry.g();
      if (g > best.get(key)) {
        continue;
      }
      if (++expansions > maxExpansions) {
        return new Result(null, Set.of(), expansions, "budget");
      }
      if ((expansions & 255) == 0 && System.currentTimeMillis() > deadline) {
        return new Result(null, Set.of(), expansions, "timeout");
      }
      int gx = gx(key);
      int gy = gy(key);
      int layer = layer(key);
      IntPoint p = point(gx, gy);

      List<Step> finish = tryFinish(p, layer);
      if (finish != null) {
        List<Step> path = new ArrayList<>();
        for (Long k = key; k != null; k = parent.get(k)) {
          path.addFirst(new Step(point(gx(k), gy(k)), layer(k)));
        }
        path.addAll(finish);
        return new Result(path, collectRipped(path), expansions, "found");
      }

      for (int[] d : dirs) {
        IntPoint q = point(gx + d[0], gy + d[1]);
        if (!board.boundingBox.contains(q)) {
          continue;
        }
        double cost = segmentCost(p, q, layer, null);
        if (cost != BLOCKED) {
          double length = p.toFloat().distance(q.toFloat());
          relax(open, best, parent, key, key(gx + d[0], gy + d[1], layer), g + length + cost, q);
        }
      }
      if (via != null) {
        Padstack ps = via.getPadstack();
        if (layer >= ps.fromLayer() && layer <= ps.toLayer()) {
          double cost = viaCost(gx, gy);
          if (cost != BLOCKED) {
            for (int l = ps.fromLayer(); l <= ps.toLayer(); l++) {
              if (l != layer && ctrl.layerActive[l]) {
                relax(open, best, parent, key, key(gx, gy, l), g + viaCost + cost, p);
              }
            }
          }
        }
      }
    }
    return new Result(null, Set.of(), expansions, "exhausted");
  }

  private void relax(
      PriorityQueue<Open> open,
      Map<Long, Double> best,
      Map<Long, Long> parent,
      long from,
      long to,
      double g,
      IntPoint q) {
    Double old = best.get(to);
    if (old == null || g < old) {
      best.put(to, g);
      parent.put(to, from);
      open.add(new Open(g + heuristic(q), g, to));
    }
  }

  /** Tries a 45-degree dogleg (or an L in 90-degree mode) from a grid node onto the target. */
  private List<Step> tryFinish(IntPoint p, int layer) {
    if (!airline.toItem.isOnLayer(layer)) {
      return null;
    }
    long dx = (long) target.x - p.x;
    long dy = (long) target.y - p.y;
    if (Math.max(Math.abs(dx), Math.abs(dy)) > 3L * pitch) {
      return null;
    }
    IntPoint mid;
    if (ninetyDegree) {
      mid = new IntPoint(target.x, p.y);
    } else {
      long d = Math.min(Math.abs(dx), Math.abs(dy));
      mid = new IntPoint((int) (p.x + Long.signum(dx) * d), (int) (p.y + Long.signum(dy) * d));
    }
    if (segmentCost(p, mid, layer, null) == BLOCKED
        || segmentCost(mid, target, layer, null) == BLOCKED) {
      return null;
    }
    List<Step> tail = new ArrayList<>();
    if (!mid.equals(p)) {
      tail.add(new Step(mid, layer));
    }
    if (!target.equals(mid)) {
      tail.add(new Step(target, layer));
    }
    return tail;
  }

  private Set<Item> collectRipped(List<Step> path) {
    Set<Item> ripped = new LinkedHashSet<>();
    if (mode != Mode.RIPUP) {
      return ripped;
    }
    for (int i = 1; i < path.size(); i++) {
      Step a = path.get(i - 1);
      Step b = path.get(i);
      if (a.layer() == b.layer()) {
        segmentCost(a.point(), b.point(), a.layer(), ripped);
      } else {
        viaCost(a.point(), ripped);
      }
    }
    return ripped;
  }

  private double segmentCost(IntPoint a, IntPoint b, int layer, Set<Item> ripped) {
    if (a.equals(b)) {
      return 0;
    }
    TileShape shape = new Polyline(a, b).offsetShape(ctrl.traceHalfWidth[layer], 0);
    return shapeCost(shape, layer, ctrl.traceClearanceClassIndex, false, ripped);
  }

  private double viaCost(int gx, int gy) {
    return viaCache.computeIfAbsent(key(gx, gy, 0), k -> viaCost(point(gx, gy), null));
  }

  private double viaCost(IntPoint p, Set<Item> ripped) {
    Padstack ps = via.getPadstack();
    Vector v = p.differenceBy(Point.ZERO);
    double total = 0;
    for (int l = ps.fromLayer(); l <= ps.toLayer(); l++) {
      ConvexShape s = ps.getShape(l);
      if (s == null) {
        continue;
      }
      TileShape oct = s.translateBy(v).boundingOctagon();
      double cost = shapeCost(oct, l, via.getClearanceClassIndex(), true, ripped);
      if (cost == BLOCKED) {
        return BLOCKED;
      }
      total += cost;
    }
    return total;
  }

  /** Returns the crossing cost of a shape, or {@link #BLOCKED}; adds crossed items to ripped. */
  private double shapeCost(
      TileShape shape, int layer, int clearanceClass, boolean isVia, Set<Item> ripped) {
    if (!shape.isContainedIn(board.boundingBox)) {
      return BLOCKED;
    }
    double cost = 0;
    for (Item item :
        board.overlappingItemsWithClearance(shape, layer, netNumbers, clearanceClass)) {
      boolean obstacle = isVia ? item.isObstacle(viaPrototype) : item.isTraceObstacle(net);
      if (!obstacle) {
        continue;
      }
      if (item.isRoutable() && mode != Mode.ALL_OBSTACLES) {
        if (mode == Mode.RIPUP) {
          double rip = ripCost.applyAsDouble(item);
          if (Double.isInfinite(rip)) {
            return BLOCKED;
          }
          cost += rip * pitch;
          if (ripped != null) {
            ripped.add(item);
          }
        }
        continue;
      }
      return BLOCKED;
    }
    return cost;
  }

  /** Inserts a found path as unfixed traces and vias. */
  public void insert(List<Step> path) {
    List<Point> run = new ArrayList<>();
    int runLayer = path.getFirst().layer();
    for (Step s : path) {
      if (s.layer() != runLayer) {
        insertRun(run, runLayer);
        board.insertVia(
            via.getPadstack(),
            s.point(),
            netNumbers,
            via.getClearanceClassIndex(),
            FixedState.UNFIXED,
            via.attachSmdAllowed());
        run.clear();
        runLayer = s.layer();
      }
      if (run.isEmpty() || !run.getLast().equals(s.point())) {
        run.add(s.point());
      }
    }
    insertRun(run, runLayer);
  }

  private void insertRun(List<Point> run, int layer) {
    if (run.size() >= 2) {
      board.insertTrace(
          run.toArray(new Point[0]),
          layer,
          ctrl.traceHalfWidth[layer],
          netNumbers,
          ctrl.traceClearanceClassIndex,
          FixedState.UNFIXED);
    }
  }

  private double heuristic(IntPoint p) {
    double dx = Math.abs((double) target.x - p.x);
    double dy = Math.abs((double) target.y - p.y);
    return ninetyDegree ? dx + dy : Math.max(dx, dy) + (Math.sqrt(2) - 1) * Math.min(dx, dy);
  }

  private IntPoint point(int gx, int gy) {
    return new IntPoint(origin.x + gx * pitch, origin.y + gy * pitch);
  }

  private static long key(int gx, int gy, int layer) {
    return ((long) (gx + OFFSET) << 32) | ((long) (gy + OFFSET) << 8) | layer;
  }

  private static int gx(long key) {
    return (int) (key >>> 32) - OFFSET;
  }

  private static int gy(long key) {
    return (int) ((key >>> 8) & 0xFFFFFF) - OFFSET;
  }

  private static int layer(long key) {
    return (int) (key & 0xFF);
  }
}
