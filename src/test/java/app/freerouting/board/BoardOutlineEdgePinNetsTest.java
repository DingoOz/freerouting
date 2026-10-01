package app.freerouting.board;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.Freerouting;
import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.Pin;
import app.freerouting.board.model.structure.BoardOutline;
import app.freerouting.io.BoardReadResult;
import app.freerouting.io.specctra.DsnReader;
import app.freerouting.settings.GlobalSettings;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Nets with a pin on or outside the board outline (edge connectors) may cross the outline; all
 * other nets are blocked by it. The outline caches the edge-pin nets because the maze search asks
 * for every outline hit, so the cache must follow pin changes.
 */
class BoardOutlineEdgePinNetsTest {

  // R1 pin 1 (a square pad, like an edge connector finger) straddles the left board edge at x = 0;
  // R1 pin 2 and both R2 pins are inside.
  private static final String DSN =
      """
      (pcb test.dsn
        (parser (string_quote ") (space_in_quoted_tokens on) (host_cad "KiCad's Pcbnew"))
        (resolution um 10)
        (unit um)
        (structure
          (layer Top (type signal) (property (index 0)))
          (layer Bottom (type signal) (property (index 1)))
          (boundary (path pcb 0  0 0  50000 0  50000 -50000  0 -50000  0 0))
          (via Via600)
          (rule (width 250) (clearance 200))
        )
        (placement
          (component TEST:R
            (place R1 2000 -10000 front 0)
            (place R2 30000 -30000 front 0)
          )
        )
        (library
          (image TEST:R
            (pin Pad1600 1 -2000 0)
            (pin Pad1600 2 2000 0)
          )
          (padstack Pad1600
            (shape (rect Top -800 -800 800 800))
            (shape (rect Bottom -800 -800 800 800))
            (attach off))
          (padstack Via600 (shape (circle Top 600)) (shape (circle Bottom 600)) (attach off))
        )
        (network
          (net EDGE (pins R1-1 R2-1))
          (net INNER (pins R1-2 R2-2))
        )
        (wiring)
      )
      """;

  private RoutingBoard board;

  @BeforeEach
  void setUp() {
    Freerouting.globalSettings = new GlobalSettings();
    BoardReadResult result =
        DsnReader.readBoard(
            new ByteArrayInputStream(DSN.getBytes(StandardCharsets.UTF_8)), null, null);
    assertInstanceOf(BoardReadResult.Success.class, result);
    board = (RoutingBoard) ((BoardReadResult.Success) result).board();
  }

  @Test
  void onlyNetsWithPinsOnTheEdgeMayCrossTheOutline() {
    BoardOutline outline = board.getOutline();

    assertFalse(outline.isTraceObstacle(netOf("R1", "1")), "edge-pin net must not be blocked");
    assertTrue(outline.isTraceObstacle(netOf("R1", "2")), "interior net must be blocked");
  }

  @Test
  void edgePinNetsFollowNetReassignment() {
    BoardOutline outline = board.getOutline();
    int edgeNet = netOf("R1", "1");
    int innerNet = netOf("R1", "2");
    assertFalse(outline.isTraceObstacle(edgeNet)); // fills the cache

    board.generateSnapshot();
    pin("R1", "1").assignNetNo(innerNet);
    pin("R2", "1").assignNetNo(innerNet);

    assertFalse(outline.isTraceObstacle(innerNet), "the edge pin now carries the inner net");
    assertTrue(outline.isTraceObstacle(edgeNet), "the old edge net has no edge pin any more");
  }

  private int netOf(String component, String pinName) {
    return pin(component, pinName).getNetNumber(0);
  }

  private Pin pin(String component, String pinName) {
    for (Pin pin : board.getPins()) {
      if (pinName.equals(pin.name())
          && component.equals(board.components.get(pin.getComponentId()).name)) {
        return pin;
      }
    }
    assertNotNull(null, "pin " + component + "-" + pinName + " not found");
    return null;
  }
}
