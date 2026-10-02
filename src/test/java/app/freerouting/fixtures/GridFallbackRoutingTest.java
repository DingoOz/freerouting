package app.freerouting.fixtures;

import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.autoroute.grid.GridFallbackRouter;
import app.freerouting.core.RoutingJob;
import app.freerouting.drc.DesignRulesChecker;
import app.freerouting.settings.sources.TestingSettings;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The grid fallback stage runs on a board the batch router left incomplete (here by capping the
 * routed items). It must never add clearance violations or open connections, and on this fixture it
 * must close at least one connection.
 */
class GridFallbackRoutingTest extends RoutingFixtureTest {

  @Test
  @Tag("slow")
  void gridFallbackClosesConnectionsWithoutAddingViolations() {
    var testingSettings = new TestingSettings();
    testingSettings.setMaxItems(20);
    testingSettings.setOptimizerEnabled(false);
    RoutingJob job = getRoutingJob("Issue508-DAC2020_bm01.dsn", testingSettings);
    job = runRoutingJob(job);

    DesignRulesChecker drc = new DesignRulesChecker(job.board, null);
    final int violationsBefore = drc.getAllClearanceViolations().size();

    GridFallbackRouter.Outcome outcome =
        GridFallbackRouter.run(job.board, job.routerSettings, null, null);

    assertTrue(outcome.connectionsBefore() > 0, "fixture must leave connections open");
    assertTrue(
        outcome.connectionsAfter() < outcome.connectionsBefore(),
        "fallback must close at least one connection: " + outcome);
    assertTrue(outcome.netsAfter() <= outcome.netsBefore(), "incomplete nets grew: " + outcome);
    assertTrue(
        drc.getAllClearanceViolations().size() <= violationsBefore,
        "fallback added clearance violations");
  }
}
