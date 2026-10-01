package app.freerouting.autoroute.grid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.Freerouting;
import app.freerouting.TestFixtures;
import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.core.RoutingJob;
import app.freerouting.drc.AirLine;
import app.freerouting.drc.DesignRulesChecker;
import app.freerouting.management.BoardLoader;
import app.freerouting.settings.GlobalSettings;
import app.freerouting.settings.RouterSettings;
import app.freerouting.settings.SettingsMerger;
import app.freerouting.settings.sources.DefaultSettings;
import app.freerouting.settings.sources.DsnFileSettings;
import java.util.Arrays;
import java.util.Comparator;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Grid search on an unrouted board: found paths must connect and stay clearance-clean. */
class GridPathFinderTest {

  private RoutingBoard board;
  private RouterSettings settings;

  @BeforeEach
  void loadUnroutedBoard() throws Exception {
    Freerouting.globalSettings = new GlobalSettings();
    RoutingJob job = new RoutingJob(UUID.randomUUID());
    job.setInput(TestFixtures.resolveFile("Issue508-DAC2020_bm01.dsn"));
    job.routerSettings =
        new SettingsMerger(
                new DefaultSettings(),
                new DsnFileSettings(job.input.getData(), job.input.getFilename()))
            .merge();
    assertTrue(BoardLoader.loadBoardIfNeeded(job));
    board = job.board;
    settings = job.routerSettings;
  }

  private AirLine shortestAirline() {
    DesignRulesChecker drc = new DesignRulesChecker(board, null);
    drc.calculateAllIncompletes();
    return Arrays.stream(drc.getAllAirlines())
        .min(Comparator.comparingDouble(a -> a.fromCorner.distance(a.toCorner)))
        .orElseThrow();
  }

  @Test
  void foundPathConnectsWithoutClearanceViolations() {
    AirLine airline = shortestAirline();
    final int violationsBefore =
        new DesignRulesChecker(board, null).getAllClearanceViolations().size();

    GridPathFinder finder =
        new GridPathFinder(board, settings, airline, GridPathFinder.Mode.ALL_OBSTACLES, 1.0, null);
    GridPathFinder.Result result = finder.search(200_000, 10_000);
    assertTrue(result.found(), "no grid path: " + result.reason());
    assertTrue(result.ripped().isEmpty(), "ALL_OBSTACLES must not rip anything");
    assertEquals(airline.toCorner.round(), result.path().getLast().point());

    finder.insert(result.path());

    int net = airline.net.netNumber;
    assertTrue(airline.fromItem.getConnectedSet(net).contains(airline.toItem));
    assertEquals(
        violationsBefore, new DesignRulesChecker(board, null).getAllClearanceViolations().size());
  }

  @Test
  void ripupReportsOnlyUnfixedForeignItemsAndNeverRipsProtectedNets() {
    // Route the shortest connection so the board has unfixed traces to rip.
    AirLine first = shortestAirline();
    GridPathFinder firstFinder =
        new GridPathFinder(board, settings, first, GridPathFinder.Mode.ALL_OBSTACLES, 1.0, null);
    firstFinder.insert(firstFinder.search(200_000, 10_000).path());

    DesignRulesChecker drc = new DesignRulesChecker(board, null);
    drc.calculateAllIncompletes();
    for (AirLine airline : drc.getAllAirlines()) {
      int net = airline.net.netNumber;
      GridPathFinder.Result free =
          new GridPathFinder(board, settings, airline, GridPathFinder.Mode.RIPUP, 1.0, item -> 0.0)
              .search(20_000, 2_000);
      for (var item : free.ripped()) {
        assertTrue(item.isRoutable(), "ripped a fixed item: " + item);
        assertFalse(item.containsNet(net), "ripped an item of the routed net: " + item);
      }
      GridPathFinder.Result guarded =
          new GridPathFinder(
                  board,
                  settings,
                  airline,
                  GridPathFinder.Mode.RIPUP,
                  1.0,
                  item -> Double.POSITIVE_INFINITY)
              .search(20_000, 2_000);
      assertTrue(guarded.ripped().isEmpty(), "protected nets were ripped");
    }
  }
}
