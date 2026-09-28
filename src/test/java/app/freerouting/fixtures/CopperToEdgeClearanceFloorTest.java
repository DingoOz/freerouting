package app.freerouting.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.board.model.structure.Unit;
import app.freerouting.core.RoutingJob;
import app.freerouting.settings.sources.DefaultSettings;
import app.freerouting.settings.sources.TestingSettings;
import org.junit.jupiter.api.Test;

/**
 * The implicit copper-to-edge default (250 um) is a minimum for boards whose DSN carries no edge
 * clearance. It must not lower the clearance the board's own rules already demand from the outline:
 * Issue690-ecc83.dsn uses a 400 um default clearance, so copper must stay 400 um from the edge. An
 * explicitly configured value is still honoured exactly.
 */
class CopperToEdgeClearanceFloorTest extends RoutingFixtureTest {

  private static final String FIXTURE = "Issue690-ecc83.dsn";
  private static final double BOARD_DEFAULT_CLEARANCE_UM = 400.0;

  @Test
  void defaultEdgeClearanceDoesNotLowerTheBoardClearance() {
    RoutingJob job = loadWithEdgeClearance(DefaultSettings.DEFAULT_COPPER_TO_EDGE_CLEARANCE_UM);

    assertEdgeClearanceToDefaultClass(job, BOARD_DEFAULT_CLEARANCE_UM);
  }

  @Test
  void explicitEdgeClearanceIsHonouredExactly() {
    final double explicitClearanceUm = 300.0;
    RoutingJob job = loadWithEdgeClearance(explicitClearanceUm);

    assertEdgeClearanceToDefaultClass(job, explicitClearanceUm);
  }

  private RoutingJob loadWithEdgeClearance(double copperToEdgeClearanceUm) {
    var testingSettings = new TestingSettings();
    testingSettings.setCopperToEdgeClearanceUm(copperToEdgeClearanceUm);
    testingSettings.setMaxPasses(1);
    testingSettings.setMaxItems(1);
    testingSettings.setJobTimeoutString("00:02:00");
    return runRoutingJob(getRoutingJob(FIXTURE, testingSettings));
  }

  private static void assertEdgeClearanceToDefaultClass(RoutingJob job, double expectedUm) {
    var matrix = job.board.rules.clearanceMatrix;
    int boardEdgeClassNo = matrix.getNo("board_edge");
    int defaultClassNo = matrix.getNo("default");
    assertTrue(boardEdgeClassNo >= 0, "Expected board_edge clearance class to be created.");
    assertTrue(defaultClassNo >= 0, "Expected the DSN default clearance class.");
    assertEquals(boardEdgeClassNo, job.board.getOutline().clearanceClassIndex());

    int expectedBoardUnits =
        (int)
            Math.round(
                Unit.scale(
                    expectedUm * Math.max(1, job.board.communication.resolution),
                    Unit.UM,
                    job.board.communication.unit));
    for (int layer = 0; layer < matrix.getLayerCount(); layer++) {
      assertEquals(
          expectedBoardUnits,
          matrix.getValue(boardEdgeClassNo, defaultClassNo, layer, false),
          "board_edge to default clearance on layer " + layer);
    }
  }
}
