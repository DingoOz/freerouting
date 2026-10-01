package app.freerouting.autoroute.grid;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import app.freerouting.Freerouting;
import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.Item;
import app.freerouting.core.RoutingJob;
import app.freerouting.drc.DesignRulesChecker;
import app.freerouting.io.specctra.SesReader;
import app.freerouting.management.BoardLoader;
import app.freerouting.settings.GlobalSettings;
import app.freerouting.settings.SettingsMerger;
import app.freerouting.settings.sources.DefaultSettings;
import app.freerouting.settings.sources.DsnFileSettings;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Development harness for {@link GridFallbackRouter}: loads already-routed boards (DSN + SES), runs
 * only the fallback stage and reports incomplete nets and full-DRC clearance counts before and
 * after. Skipped unless {@code GRID_PROBE_LIST} names a file of "dsn&lt;TAB&gt;ses" lines. Writes
 * {@code logs/grid-probe/stage.tsv} (or {@code GRID_PROBE_OUT}).
 */
@Tag("slow")
class GridProbeTest {

  @Test
  void probe() throws IOException {
    String list = System.getenv("GRID_PROBE_LIST");
    assumeTrue(list != null, "GRID_PROBE_LIST not set");
    Freerouting.globalSettings = new GlobalSettings();
    Path out = Path.of("logs/grid-probe");
    Files.createDirectories(out);
    String outName = System.getenv().getOrDefault("GRID_PROBE_OUT", "stage.tsv");
    try (PrintWriter tsv = new PrintWriter(out.resolve(outName).toFile())) {
      tsv.println(
          "board\tunconnected_before\tunconnected_after\tclearance_before\tclearance_after"
              + "\tairlines_before\tairlines_after\taccepted\tattempts\tms");
      for (String line : Files.readAllLines(Path.of(list))) {
        if (line.isBlank()) {
          continue;
        }
        String[] parts = line.split("\t");
        try {
          probeBoard(new File(parts[0]), new File(parts[1]), tsv);
        } catch (Exception e) {
          tsv.println(parts[0] + "\tERROR " + e);
        }
        tsv.flush();
      }
    }
  }

  /** Same count as the benchmark: nets with more than one connected set. */
  private static int unconnectedPairs(DesignRulesChecker drc) {
    return (int)
        drc.getAllUnconnectedItems().stream()
            .filter(u -> "unconnectedItems".equals(u.type))
            .count();
  }

  private void probeBoard(File dsn, File ses, PrintWriter tsv) throws IOException {
    RoutingJob job = new RoutingJob(UUID.randomUUID());
    job.setInput(dsn);
    job.routerSettings =
        new SettingsMerger(
                new DefaultSettings(),
                new DsnFileSettings(job.input.getData(), job.input.getFilename()))
            .merge();
    if (!BoardLoader.loadBoardIfNeeded(job)) {
      throw new IOException("cannot load " + dsn);
    }
    Set<Item> dsnItems = new HashSet<>(job.board.getItems());
    try (FileInputStream in = new FileInputStream(ses)) {
      SesReader.read(in, job.board);
    }
    // SesReader inserts wiring as USER_FIXED; it is router output, so make it movable again.
    for (Item item : job.board.getItems()) {
      if (!dsnItems.contains(item)) {
        item.unfix();
      }
    }
    RoutingBoard board = job.board;
    DesignRulesChecker drc = new DesignRulesChecker(board, null);
    int u0 = unconnectedPairs(drc);
    int v0 = drc.getAllClearanceViolations().size();
    GridFallbackRouter.Outcome o = GridFallbackRouter.run(board, job.routerSettings, null, null);
    int u1 = unconnectedPairs(drc);
    int v1 = drc.getAllClearanceViolations().size();
    tsv.println(
        String.join(
            "\t",
            dsn.getParentFile().getName(),
            String.valueOf(u0),
            String.valueOf(u1),
            String.valueOf(v0),
            String.valueOf(v1),
            String.valueOf(o.connectionsBefore()),
            String.valueOf(o.connectionsAfter()),
            String.valueOf(o.accepted()),
            String.valueOf(o.attempts()),
            String.valueOf(o.millis())));
  }
}
