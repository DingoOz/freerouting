package app.freerouting.autoroute.grid;

import app.freerouting.autoroute.AutorouteAttemptState;
import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.Item;
import app.freerouting.datastructures.Stoppable;
import app.freerouting.datastructures.TimeLimit;
import app.freerouting.drc.AirLine;
import app.freerouting.drc.DesignRulesChecker;
import app.freerouting.logger.FRLogger;
import app.freerouting.settings.RouterSettings;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Fallback stage for the connections the batch autorouter leaves unrouted. Each remaining airline
 * is tried as one transaction on the board's undo stack:
 *
 * <ol>
 *   <li>grid A* with every item as an obstacle, from both ends of the airline; if that fails,
 *   <li>grid A* that may cross unfixed traces and vias of other nets (rip-up). The crossed
 *       connections are removed, the connection is routed into the freed space (gridless maze
 *       router first, the grid path otherwise), and every broken connection of the ripped nets is
 *       rerouted: maze router, then grid, then grid with rip-up down to a small depth.
 * </ol>
 *
 * <p>A transaction is kept only if no item it created takes part in a clearance violation, the full
 * clearance-violation count did not grow, and the board has strictly fewer incomplete nets (or, at
 * equal nets, fewer incomplete connections); otherwise it is undone. Rip-up is negotiated: a net
 * whose reroute failed is penalised and the search repeated, and nets that were ripped before stay
 * more expensive for the rest of the stage.
 */
public final class GridFallbackRouter {

  // ponytail: fixed budgets; make them settings if boards need tuning
  private static final long STAGE_BUDGET_MS = 120_000;
  private static final int MAX_EXPANSIONS = 200_000;
  private static final long SEARCH_BUDGET_MS = 10_000;
  private static final int VICTIM_ROUTE_MS = 2_000;
  private static final double[] PITCH_FACTORS = {1.0, 0.5};
  private static final int MAX_RIPUP_DEPTH = 2;
  private static final int NEGOTIATION_ROUNDS = 4;
  private static final double NEGOTIATION_PENALTY = 50.0;

  /** Summary of one stage run. */
  public record Outcome(
      int netsBefore,
      int netsAfter,
      int connectionsBefore,
      int connectionsAfter,
      int accepted,
      int attempts,
      long millis) {

    /** One-line summary for the job log. */
    public String summary() {
      return "Grid fallback: incomplete nets "
          + netsBefore
          + " -> "
          + netsAfter
          + ", connections "
          + connectionsBefore
          + " -> "
          + connectionsAfter
          + " ("
          + accepted
          + " of "
          + attempts
          + " attempts kept, "
          + millis
          + " ms).";
    }
  }

  private record Metric(int nets, int connections) {
    boolean betterThan(Metric other) {
      return nets < other.nets || (nets == other.nets && connections < other.connections);
    }
  }

  private final RoutingBoard board;
  private final RouterSettings settings;
  private final Stoppable stop;
  private final DesignRulesChecker drc;
  private final Map<Integer, Integer> ripHistory = new HashMap<>();
  private final long deadline;

  /** Full clearance-violation count of the board after the last kept transaction. */
  private int violations;

  /** Incomplete-connection count of each net before the current transaction ripped it. */
  private final Map<Integer, Integer> txnPreCounts = new HashMap<>();

  /** Ripped nets the last rejected transaction could not restore. */
  private Set<Integer> failedVictims = Set.of();

  private GridFallbackRouter(
      RoutingBoard board, RouterSettings settings, Stoppable stop, Instant jobTimeout) {
    this.board = board;
    this.settings = settings;
    this.stop = stop;
    this.drc = new DesignRulesChecker(board, null);
    long now = System.currentTimeMillis();
    long budget = STAGE_BUDGET_MS;
    if (jobTimeout != null) {
      // Leave at least half of the remaining job time to the optimizer.
      budget = Math.min(budget, Math.max(0, (jobTimeout.toEpochMilli() - now) / 2));
    }
    this.deadline = now + budget;
  }

  /**
   * Tries to route the remaining airlines of a routed board. Never adds clearance violations.
   *
   * @param jobTimeout when the routing job times out, or null; the stage uses at most half of the
   *     time left
   */
  public static Outcome run(
      RoutingBoard board, RouterSettings settings, Stoppable stop, Instant jobTimeout) {
    return new GridFallbackRouter(board, settings, stop, jobTimeout).run();
  }

  private Outcome run() {
    final long start = System.currentTimeMillis();
    drc.calculateAllIncompletes();
    final Metric initial = metric();
    violations = countViolations();
    int initialViolations = violations;
    int accepted = 0;
    int attempts = 0;
    Set<String> failed = new HashSet<>();
    try {
      while (!outOfTime()) {
        AirLine next = null;
        for (AirLine airline : sortedAirlines()) {
          if (!failed.contains(key(airline))) {
            next = airline;
            break;
          }
        }
        if (next == null) {
          break;
        }
        attempts++;
        if (tryConnect(next)) {
          accepted++;
          failed.clear();
        } else {
          failed.add(key(next));
        }
      }
    } finally {
      board.clearTransientAutorouteState();
    }
    if (countViolations() > initialViolations) {
      // Should not happen (every kept transaction was checked); fall back to the input board.
      FRLogger.warn("Grid fallback: clearance violations increased, discarding its changes.");
      for (int i = 0; i < accepted; i++) {
        board.undo(null);
      }
      board.clearTransientAutorouteState();
      accepted = 0;
    }
    drc.calculateAllIncompletes();
    Metric fin = metric();
    return new Outcome(
        initial.nets(),
        fin.nets(),
        initial.connections(),
        fin.connections(),
        accepted,
        attempts,
        System.currentTimeMillis() - start);
  }

  private static void trace(String operation, String message, int net) {
    FRLogger.trace("GridFallbackRouter", operation, message, "Net #" + net, null);
  }

  private boolean tryConnect(AirLine airline) {
    trace("attempt", airline.fromItem + " -> " + airline.toItem, airline.net.netNumber);
    // Searching from both ends gives two grid alignments and two pin escapes.
    List<AirLine> directions =
        List.of(
            airline,
            new AirLine(
                airline.net,
                airline.toItem,
                airline.toCorner,
                airline.fromItem,
                airline.fromCorner));
    for (AirLine direction : directions) {
      for (double pitchFactor : PITCH_FACTORS) {
        if (outOfTime()) {
          return false;
        }
        GridPathFinder finder =
            new GridPathFinder(
                board, settings, direction, GridPathFinder.Mode.ALL_OBSTACLES, pitchFactor, null);
        GridPathFinder.Result result = finder.search(MAX_EXPANSIONS, searchBudget());
        trace(
            "plain_search",
            "pitch=" + pitchFactor + " " + result.reason() + " expansions=" + result.expansions(),
            direction.net.netNumber);
        if (result.found() && transaction(direction, finder, result)) {
          return true;
        }
      }
    }
    for (AirLine direction : directions) {
      for (double pitchFactor : PITCH_FACTORS) {
        if (tryRipup(direction, pitchFactor)) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Rip-up attempts with negotiation: when a ripped net cannot be rerouted, crossing that net gets
   * more expensive and the search is repeated, so the next path avoids it if it can.
   */
  private boolean tryRipup(AirLine airline, double pitchFactor) {
    Map<Integer, Double> penalty = new HashMap<>();
    Set<Set<Integer>> seenRipSets = new HashSet<>();
    for (int round = 0; round < NEGOTIATION_ROUNDS && !outOfTime(); round++) {
      GridPathFinder finder =
          new GridPathFinder(
              board,
              settings,
              airline,
              GridPathFinder.Mode.RIPUP,
              pitchFactor,
              item -> ripCost(item, Set.of()) + penalty.getOrDefault(item.getNetNumber(0), 0.0));
      GridPathFinder.Result result = finder.search(MAX_EXPANSIONS, searchBudget());
      Set<Integer> ripNets = new TreeSet<>();
      result.ripped().forEach(item -> ripNets.add(item.getNetNumber(0)));
      trace(
          "ripup_search",
          "pitch="
              + pitchFactor
              + " round="
              + round
              + " "
              + result.reason()
              + " expansions="
              + result.expansions()
              + " ripped_nets="
              + ripNets,
          airline.net.netNumber);
      if (!result.found() || !seenRipSets.add(ripNets)) {
        return false;
      }
      if (transaction(airline, finder, result)) {
        return true;
      }
      // Make every net this path disturbed, and every net that stayed broken, more expensive.
      for (int victim : ripNets) {
        penalty.merge(victim, NEGOTIATION_PENALTY, Double::sum);
      }
      for (int victim : failedVictims) {
        penalty.merge(victim, NEGOTIATION_PENALTY, Double::sum);
      }
    }
    return false;
  }

  /**
   * Crossing an item costs ten grid steps, more for nets that were already ripped; nets in
   * protectedNets cannot be crossed.
   */
  private double ripCost(Item item, Set<Integer> protectedNets) {
    int itemNet = item.getNetNumber(0);
    if (protectedNets.contains(itemNet)) {
      return Double.POSITIVE_INFINITY;
    }
    return 10.0 * (1 + 2 * ripHistory.getOrDefault(itemNet, 0));
  }

  private boolean transaction(AirLine airline, GridPathFinder finder, GridPathFinder.Result r) {
    final Metric before = metric();
    int netNo = airline.net.netNumber;
    final int idBefore = board.communication.idGenerator.maxGeneratedId();
    Set<Integer> touchedNets = new TreeSet<>();
    touchedNets.add(netNo);
    board.generateSnapshot();
    txnPreCounts.clear();

    Set<Integer> victims = rip(r.ripped());
    touchedNets.addAll(victims);
    // With the blockers gone the gridless maze router usually finds a cleaner path than the grid.
    boolean clean =
        (!victims.isEmpty() && mazeRoute(airline.fromItem, idBefore))
            || insert(finder, r.path(), idBefore);
    if (clean) {
      Set<Integer> protectedNets = new HashSet<>(Set.of(netNo));
      for (int victim : victims) {
        rerouteNet(victim, 1, protectedNets, touchedNets);
      }
    }
    touchedNets.forEach(drc::recalculateNetIncompletes);
    Metric after = metric();
    int violationsAfter = clean && after.betterThan(before) ? countViolations() : Integer.MAX_VALUE;
    boolean keep = violationsAfter <= violations;
    trace(
        keep ? "transaction_kept" : "transaction_undone",
        "clean=" + clean + " " + before + " -> " + after + " nets=" + touchedNets,
        netNo);
    if (!keep) {
      failedVictims = new HashSet<>();
      for (var pre : txnPreCounts.entrySet()) {
        if (pre.getKey() != netNo && drc.getIncompleteCount(pre.getKey()) > pre.getValue()) {
          failedVictims.add(pre.getKey());
        }
      }
      board.undo(null);
      board.clearTransientAutorouteState();
      touchedNets.forEach(drc::recalculateNetIncompletes);
      return false;
    }
    violations = violationsAfter;
    for (int victim : touchedNets) {
      if (victim != netNo) {
        ripHistory.merge(victim, 1, Integer::sum);
      }
    }
    return true;
  }

  /** Removes the connections of the ripped items and returns their nets. */
  private Set<Integer> rip(Set<Item> ripped) {
    Set<Integer> nets = new TreeSet<>();
    if (ripped.isEmpty()) {
      return nets;
    }
    Set<Item> connections = new TreeSet<>();
    for (Item item : ripped) {
      connections.addAll(item.getConnectionItems(Item.StopConnectionOption.NONE));
      int net = item.getNetNumber(0);
      nets.add(net);
      txnPreCounts.computeIfAbsent(net, drc::getIncompleteCount);
    }
    board.removeItems(connections);
    for (int net : nets) {
      board.removeTraceTails(net, Item.StopConnectionOption.NONE);
    }
    return nets;
  }

  /**
   * Inserts a grid path and reports whether it added no violations. The path is not pulled tight
   * here: the pull-tight does not honour its time limit on degenerate geometry, and the optimizer
   * smooths the result afterwards.
   */
  private boolean insert(GridPathFinder finder, List<GridPathFinder.Step> path, int idBefore) {
    finder.insert(path);
    return !hasNewViolations(idBefore);
  }

  private int countViolations() {
    return drc.getAllClearanceViolations().size();
  }

  /**
   * Reroutes every open connection of a ripped net: gridless maze router first, then the grid, then
   * (below the depth limit) the grid with rip-up of nets that are not protected. A step that adds
   * violations is removed again.
   */
  private void rerouteNet(int netNo, int depth, Set<Integer> protectedNets, Set<Integer> touched) {
    Set<String> tried = new HashSet<>();
    while (!outOfTime()) {
      drc.recalculateNetIncompletes(netNo);
      AirLine open = null;
      for (AirLine airline : airlines()) {
        if (airline.net.netNumber == netNo && tried.add(key(airline))) {
          open = airline;
          break;
        }
      }
      if (open == null) {
        return;
      }
      int idBefore = board.communication.idGenerator.maxGeneratedId();
      if (mazeRoute(open.fromItem, idBefore)) {
        continue;
      }
      if (gridReroute(open, GridPathFinder.Mode.ALL_OBSTACLES, null, idBefore) != null) {
        continue;
      }
      if (depth >= MAX_RIPUP_DEPTH) {
        continue;
      }
      Set<Integer> deeperProtected = new HashSet<>(protectedNets);
      deeperProtected.add(netNo);
      Set<Integer> victims =
          gridReroute(open, GridPathFinder.Mode.RIPUP, deeperProtected, idBefore);
      if (victims != null) {
        touched.addAll(victims);
        for (int victim : victims) {
          rerouteNet(victim, depth + 1, deeperProtected, touched);
        }
      }
    }
  }

  /** Routes an item with the gridless maze router (no rip-up); undoes it if it adds violations. */
  private boolean mazeRoute(Item item, int idBefore) {
    var result =
        board.autoroute(
            item,
            settings,
            settings.getViaCosts(),
            stop,
            new TimeLimit((int) Math.min(VICTIM_ROUTE_MS, remainingMillis())));
    board.clearTransientAutorouteState();
    if (result.state == AutorouteAttemptState.ROUTED && !hasNewViolations(idBefore)) {
      return true;
    }
    removeNewItems(idBefore);
    return false;
  }

  /** Grid-routes one airline; returns the ripped nets on success, null on failure. */
  private Set<Integer> gridReroute(
      AirLine open, GridPathFinder.Mode mode, Set<Integer> protectedNets, int idBefore) {
    for (double pitchFactor : PITCH_FACTORS) {
      if (outOfTime()) {
        return null;
      }
      GridPathFinder finder =
          new GridPathFinder(
              board,
              settings,
              open,
              mode,
              pitchFactor,
              protectedNets == null ? null : item -> ripCost(item, protectedNets));
      GridPathFinder.Result r = finder.search(MAX_EXPANSIONS, searchBudget());
      if (!r.found()) {
        continue;
      }
      Set<Integer> victims = rip(r.ripped());
      if (insert(finder, r.path(), idBefore)) {
        return victims;
      }
      removeNewItems(idBefore);
      if (!victims.isEmpty()) {
        // the ripped connections are gone; let the caller's metric decide
        return victims;
      }
    }
    return null;
  }

  /**
   * Returns true if any item created after idBefore takes part in a clearance violation. Both sides
   * of each pair are asked, because {@code isObstacle} is not symmetric: a violation can be
   * reported only by the older neighbour. Removals cannot add violations, and every change this
   * stage makes (inserts, pull-tight, shoves) produces items with new ids, so this is exact.
   */
  private boolean hasNewViolations(int idBefore) {
    for (Item item : board.getItems()) {
      if (item.getId() <= idBefore) {
        continue;
      }
      if (!item.clearanceViolations().isEmpty()) {
        return true;
      }
      for (int i = 0; i < item.tileShapeCount(); i++) {
        for (Item neighbour :
            board.overlappingItemsWithClearance(
                item.getTileShape(i), item.shapeLayer(i), new int[0], item.clearanceClassIndex())) {
          if (neighbour.getId() > idBefore) {
            continue;
          }
          for (var violation : neighbour.clearanceViolations()) {
            if (violation.firstItem == item || violation.secondItem == item) {
              return true;
            }
          }
        }
      }
    }
    return false;
  }

  private void removeNewItems(int idBefore) {
    List<Item> created = new ArrayList<>();
    for (Item item : board.getItems()) {
      if (item.getId() > idBefore) {
        created.add(item);
      }
    }
    board.removeItems(created);
  }

  private Metric metric() {
    return new Metric(drc.incompleteNetNumbers().size(), drc.getIncompleteCount());
  }

  private List<AirLine> airlines() {
    AirLine[] all = drc.getAllAirlines();
    return all == null ? List.of() : Arrays.asList(all);
  }

  /** Shortest airlines first: they are the most likely to succeed and disturb the least. */
  private List<AirLine> sortedAirlines() {
    List<AirLine> sorted = new ArrayList<>(airlines());
    sorted.sort(Comparator.comparingDouble(a -> a.fromCorner.distance(a.toCorner)));
    return sorted;
  }

  private static String key(AirLine airline) {
    return airline.net.netNumber + ":" + airline.fromItem.getId() + ":" + airline.toItem.getId();
  }

  private long remainingMillis() {
    return Math.max(0, deadline - System.currentTimeMillis());
  }

  /** Every search stops at the stage deadline, so the stage cannot overrun its budget. */
  private long searchBudget() {
    return Math.min(SEARCH_BUDGET_MS, remainingMillis());
  }

  private boolean outOfTime() {
    return System.currentTimeMillis() > deadline || (stop != null && stop.isStopRequested());
  }
}
