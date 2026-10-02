package app.freerouting.autoroute.pipeline;

import app.freerouting.autoroute.events.BoardUpdatedEventListener;
import app.freerouting.autoroute.events.TaskStateChangedEventListener;
import app.freerouting.autoroute.grid.GridFallbackRouter;
import app.freerouting.core.RoutingJob;
import app.freerouting.core.RoutingJobState;
import app.freerouting.core.RoutingStage;
import app.freerouting.core.results.RoutingResultManifest;
import app.freerouting.core.scoring.BoardStatistics;
import app.freerouting.settings.RouterSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Sequences the shared fanout, autoroute, and optimization stages for a routing job. */
public final class RoutingPipeline {

  /** Receives lifecycle callbacks between pipeline stages. */
  public interface StageListener {
    /** Invoked after fanout and autorouting have completed. */
    default void afterRouting(BatchAutorouter autorouter) {}

    /** Invoked immediately before optimization starts. */
    default void beforeOptimization(BatchOptimizer optimizer) {}

    /** Invoked after optimization has completed. */
    default void afterOptimization(BatchOptimizer optimizer) {}
  }

  private final RoutingJob job;
  private final BatchAutorouter autorouter;
  private final BatchOptimizer optimizer;
  private final List<StageListener> stageListeners = new ArrayList<>();

  private RoutingPipeline(RoutingJob job, Function<RoutingJob, BatchOptimizer> optimizerFactory) {
    this.job = job;
    normalizeRouterAlgorithm(job);
    this.autorouter = new BatchAutorouter(job);
    this.optimizer = job.routerSettings.getRunOptimizer() ? optimizerFactory.apply(job) : null;
  }

  /** Creates the canonical routing pipeline for a routing job. */
  public static RoutingPipeline create(RoutingJob job) {
    return new RoutingPipeline(job, BatchOptimizer::create);
  }

  /** Creates a pipeline using the GUI optimizer policy. */
  public static RoutingPipeline createForGui(RoutingJob job) {
    return create(job);
  }

  /** Creates a pipeline using the headless optimizer policy. */
  public static RoutingPipeline createForHeadless(RoutingJob job) {
    return create(job);
  }

  /** Returns the shared autorouter stage. */
  public BatchAutorouter getAutorouter() {
    return this.autorouter;
  }

  /** Returns the configured optimizer stage, or {@code null} when optimization is disabled. */
  public BatchOptimizer getOptimizer() {
    return this.optimizer;
  }

  /** Registers a listener for stage transitions. */
  public void addStageListener(StageListener listener) {
    this.stageListeners.add(listener);
  }

  /** Forwards board updates from both algorithm stages to the supplied listener. */
  public void addBoardUpdatedEventListener(BoardUpdatedEventListener listener) {
    this.autorouter.addBoardUpdatedEventListener(listener);
    if (this.optimizer != null) {
      this.optimizer.addBoardUpdatedEventListener(listener);
    }
  }

  /** Forwards task-state updates from both algorithm stages to the supplied listener. */
  public void addTaskStateChangedEventListener(TaskStateChangedEventListener listener) {
    this.autorouter.addTaskStateChangedEventListener(listener);
    if (this.optimizer != null) {
      this.optimizer.addTaskStateChangedEventListener(listener);
    }
  }

  /** Runs the configured stages in order. */
  public void run() {
    if (this.job != null && this.job.board != null) {
      this.job.board.awaitPostLoad();
    }
    runRoutingStage();
    runOptimizationStage();
    this.job.stage = RoutingStage.IDLE;
  }

  private void runRoutingStage() {
    boolean routerEnabled =
        this.job.routerSettings.getRunRouter()
            && (this.job.routerSettings.autorouter.maxPasses == null
                || this.job.routerSettings.autorouter.maxPasses >= 0);

    if (routerEnabled || this.job.routerSettings.isFanoutEnabled()) {
      this.job.stage = RoutingStage.ROUTING;
    }

    if (routerEnabled && !this.job.thread.isStopAutoRouterRequested()) {
      this.autorouter.runBatchLoop();
      // The batch loop requests an autorouter-only stop when it finishes, so only a full stop
      // or a timed-out job skips the fallback. A max_items cap means the caller wants bounded
      // routing work, so the fallback does not extend it.
      Integer maxItems = this.job.routerSettings.autorouter.maxItems;
      if (this.job.routerSettings.isGridFallback()
          && (maxItems == null || maxItems <= 0 || maxItems == Integer.MAX_VALUE)
          && !this.job.thread.isStopRequested()
          && this.job.state != RoutingJobState.TIMED_OUT) {
        this.job.board.finishAutoroute();
        GridFallbackRouter.Outcome outcome =
            GridFallbackRouter.run(
                this.job.board, this.job.routerSettings, this.job.thread, this.job.timeoutAt);
        this.job.logInfo(outcome.summary());
        recordGridFallbackInAutorouterPhase(outcome);
      }
    } else if (this.job.routerSettings.isFanoutEnabled()
        && !this.job.thread.isStopAutoRouterRequested()) {
      Integer originalMaxPasses = this.job.routerSettings.autorouter.maxPasses;
      try {
        this.job.routerSettings.autorouter.maxPasses = 0;
        this.autorouter.runBatchLoop();
      } finally {
        this.job.routerSettings.autorouter.maxPasses = originalMaxPasses;
      }
    }

    this.job.board.finishAutoroute();
    for (StageListener listener : this.stageListeners) {
      listener.afterRouting(this.autorouter);
    }
  }

  /** The fallback is part of the routing stage, so the autorouter "after" snapshot includes it. */
  private void recordGridFallbackInAutorouterPhase(GridFallbackRouter.Outcome outcome) {
    RoutingResultManifest.PhaseDetail phase = this.job.resultPhaseMetrics.autorouter;
    if (phase.after == null) {
      return;
    }
    if (outcome.accepted() > 0) {
      phase.after =
          RoutingResultManifest.PhaseSnapshot.fromBoardStatistics(
              new BoardStatistics(this.job.board), this.job.routerSettings, "current");
      phase.after.score = phase.after.routerScore;
    }
    if (phase.durationSeconds != null) {
      phase.durationSeconds += outcome.millis() / 1000.0f;
    }
  }

  private void runOptimizationStage() {
    if (this.optimizer == null || this.job.thread.isStopRequested()) {
      return;
    }

    this.job.stage = RoutingStage.OPTIMIZATION;
    for (StageListener listener : this.stageListeners) {
      listener.beforeOptimization(this.optimizer);
    }
    this.optimizer.runBatchLoop();
    for (StageListener listener : this.stageListeners) {
      listener.afterOptimization(this.optimizer);
    }
  }

  private static void normalizeRouterAlgorithm(RoutingJob job) {
    String algorithm = job.routerSettings.autorouter.algorithm;
    if (!RouterSettings.ALGORITHM_CURRENT.equals(algorithm)) {
      job.logWarning(
          "The algorithm '"
              + algorithm
              + "' is not supported. The default algorithm '"
              + RouterSettings.ALGORITHM_CURRENT
              + "' will be used instead.");
      job.routerSettings.autorouter.algorithm = RouterSettings.ALGORITHM_CURRENT;
    }
  }
}
