package app.freerouting.io.specctra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import app.freerouting.Freerouting;
import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.Pin;
import app.freerouting.core.library.Padstack;
import app.freerouting.core.library.Padstacks;
import app.freerouting.rules.NetClass;
import app.freerouting.rules.ViaRule;
import app.freerouting.settings.GlobalSettings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Padstack names in DSN files often contain decimal numbers (KiCad writes e.g. {@code
 * "Via[0-1]_635:304.8_um"}). They must be kept verbatim: stripping the digits emptied every {@code
 * use_via} via rule (so the router could not place a single via) and made distinct pad padstacks
 * collide, so pins silently received another padstack's shape.
 */
class PadstackNameResolutionTest {

  private static final String VIA_NAME = "Via[0-1]_635:304.8_um";
  private static final String SMALL_PAD = "Round[A]Pad_1600.000000_um";
  private static final String LARGE_PAD = "Round[A]Pad_1600.200000_um";

  private static final String DSN =
      """
      (pcb test.dsn
        (parser
          (string_quote ")
          (space_in_quoted_tokens on)
          (host_cad "KiCad's Pcbnew")
          (host_version "10.0.2")
        )
        (resolution um 10)
        (unit um)
        (structure
          (layer Top (type signal) (property (index 0)))
          (layer Bottom (type signal) (property (index 1)))
          (boundary (path pcb 0  0 0  50000 0  50000 -50000  0 -50000  0 0))
          (via "Via[0-1]_635:304.8_um")
          (rule (width 250) (clearance 200))
        )
        (placement
          (component TEST:R
            (place R1 10000 -10000 front 0)
            (place R2 40000 -40000 front 0)
          )
        )
        (library
          (image TEST:R
            (pin "Round[A]Pad_1600.000000_um" 1 -2000 0)
            (pin "Round[A]Pad_1600.200000_um" 2 2000 0)
          )
          (padstack "Round[A]Pad_1600.000000_um"
            (shape (circle Top 1600))
            (shape (circle Bottom 1600))
            (attach off)
          )
          (padstack "Round[A]Pad_1600.200000_um"
            (shape (circle Top 2400))
            (shape (circle Bottom 2400))
            (attach off)
          )
          (padstack "Via[0-1]_635:304.8_um"
            (shape (circle Top 635))
            (shape (circle Bottom 635))
            (attach off)
          )
        )
        (network
          (net A (pins R1-1 R2-1))
          (net B (pins R1-2 R2-2))
          (class kicad_default A B
            (circuit (use_via "Via[0-1]_635:304.8_um"))
            (rule (width 250) (clearance 200))
          )
        )
        (wiring)
      )
      """;

  private RoutingBoard board;

  @BeforeEach
  void setUp() throws IOException {
    Freerouting.globalSettings = new GlobalSettings();
    board = DsnTestFixtures.loadBoard(DSN.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void padstackNamesWithDecimalsAreKeptVerbatim() {
    assertNotNull(board.library.padstacks.get(VIA_NAME));
    assertNotNull(board.library.padstacks.get(SMALL_PAD));
    assertNotNull(board.library.padstacks.get(LARGE_PAD));
  }

  @Test
  void useViaWithDecimalNameResolvesToTheVia() {
    for (int i = 0; i < board.rules.netClasses.count(); i++) {
      NetClass netClass = board.rules.netClasses.get(i);
      ViaRule viaRule = netClass.getViaRule();
      assertNotNull(viaRule, "net class '" + netClass.getName() + "' has no via rule");
      assertEquals(
          1,
          viaRule.viaCount(),
          "net class '" + netClass.getName() + "' must be able to place the declared via");
      assertEquals(VIA_NAME, viaRule.getVia(0).getPadstack().name);
    }
  }

  @Test
  void padstacksDifferingOnlyInDecimalsKeepTheirOwnShapes() {
    Pin smallPin = null;
    Pin largePin = null;
    for (Pin pin : board.getPins()) {
      if ("1".equals(pin.name())) {
        smallPin = pin;
      } else if ("2".equals(pin.name())) {
        largePin = pin;
      }
    }
    assertNotNull(smallPin);
    assertNotNull(largePin);
    assertEquals(SMALL_PAD, smallPin.getPadstack().name);
    assertEquals(LARGE_PAD, largePin.getPadstack().name);
    assertNotEquals(
        smallPin.getPadstack().getShape(0).maxWidth(),
        largePin.getPadstack().getShape(0).maxWidth(),
        "pins must not share a padstack just because their names differ only in decimals");
  }

  @Test
  void getByReferencePrefersExactNameAndFallsBackToDottedSuffix() {
    Padstacks padstacks = board.library.padstacks;
    Padstack small = padstacks.get(SMALL_PAD);
    Padstack large = padstacks.get(LARGE_PAD);

    assertSame(large, padstacks.getByReference(LARGE_PAD));
    assertSame(small, padstacks.getByReference(SMALL_PAD));
    assertSame(padstacks.get(VIA_NAME), padstacks.getByReference(VIA_NAME + ".1"));
    assertNull(padstacks.getByReference("Unknown"));
    assertNull(padstacks.getByReference(null));
  }
}
