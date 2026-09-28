package io.jenkins.plugins.octanesuitegatebyembiti.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.jenkins.plugins.octanesuitegatebyembiti.entities.RunRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

class GateMetricsTest {
  @Test
  @ResourceLock(Resources.LOCALE)
  void statusAndMetricNamesDoNotDependOnControllerLocale() {
    Locale original = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"));
      GateMetrics metrics =
          GateMetrics.fromRuns(
              List.of(
                  new RunRecord("1", "Passed", "PASSED"),
                  new RunRecord("2", "Active", "IN PROGRESS"),
                  new RunRecord("3", "Skipped", "SKIPPED")),
              defaultClassifier());
      assertEquals(1, metrics.getRunning());
      assertEquals(1, metrics.getSkipped());
      assertEquals("executionrate", GateMetrics.normalizeMetricName("EXECUTIONRATE"));
      assertEquals("completionrate", GateMetrics.normalizeMetricName("COMPLETIONRATE"));
      assertEquals(200.0 / 3, metrics.getCompletionRate(), 0.000001);
      assertFalse(metrics.isTerminal());
    } finally {
      Locale.setDefault(original);
    }
  }

  @Test
  void centralizesExecutedCountAndRateCalculations() {
    assertEquals(4, GateMetrics.executedCount(2, 1, 1));
    assertEquals(5, GateMetrics.resolvedCount(2, 1, 1, 1));
    assertEquals(80.0, GateMetrics.executionRate(4, 5));
    assertEquals(100.0, GateMetrics.completionRate(5, 5));
    assertEquals(50.0, GateMetrics.passRate(2, 4));
  }

  @Test
  void ratesDefaultToZeroWithoutADenominator() {
    assertEquals(0.0, GateMetrics.executionRate(4, 0));
    assertEquals(0.0, GateMetrics.completionRate(5, 0));
    assertEquals(0.0, GateMetrics.passRate(2, 0));
  }

  @Test
  void skippedTestsCompleteTheSuiteWithoutIncreasingExecutionRate() {
    GateMetrics metrics = new GateMetrics(5, 4, 2, 2, 1, 0);

    assertEquals(4, metrics.getExecuted());
    assertEquals(5, metrics.getResolved());
    assertEquals(80.0, metrics.getExecutionRate());
    assertEquals(100.0, metrics.getCompletionRate());
  }

  @Test
  void inProgressTestKeepsCompletionBelowOneHundredPercent() {
    List<RunRecord> runs = new ArrayList<>();
    for (int index = 0; index < 9; index++) {
      runs.add(new RunRecord("passed-" + index, "Passed " + index, "passed"));
    }
    runs.add(new RunRecord("active-1", "Active", "list_node.run_status.in_progress"));

    GateMetrics metrics = GateMetrics.fromRuns(runs, defaultClassifier());

    assertEquals(9, metrics.getExecuted());
    assertEquals(9, metrics.getResolved());
    assertEquals(0, metrics.getSkipped());
    assertEquals(1, metrics.getRunning());
    assertEquals(90.0, metrics.getExecutionRate());
    assertEquals(90.0, metrics.getCompletionRate());
    assertFalse(metrics.isTerminal());
  }

  @Test
  void octaneSixteenManualRunNotCompletedTokenRemainsInProgress() {
    List<RunRecord> runs = new ArrayList<>();
    for (int index = 0; index < 4; index++) {
      runs.add(new RunRecord("passed-" + index, "Passed " + index, "passed"));
    }
    runs.add(new RunRecord("active-1", "Active", "list_node.run_native_status.not_completed"));

    GateMetrics metrics = GateMetrics.fromRuns(runs, defaultClassifier());

    assertEquals(4, metrics.getExecuted());
    assertEquals(4, metrics.getResolved());
    assertEquals(0, metrics.getSkipped());
    assertEquals(1, metrics.getRunning());
    assertEquals(80.0, metrics.getExecutionRate());
    assertEquals(80.0, metrics.getCompletionRate());
    assertFalse(metrics.isTerminal());
  }

  @Test
  void singleActiveTestGuardsNinetyNinePercentCompletion() {
    List<RunRecord> runs = new ArrayList<>();
    for (int index = 0; index < 99; index++) {
      runs.add(new RunRecord("passed-" + index, "Passed " + index, "passed"));
    }
    runs.add(new RunRecord("active-1", "Active", "In Progress"));

    GateMetrics metrics = GateMetrics.fromRuns(runs, defaultClassifier());

    assertEquals(99.0, metrics.getCompletionRate());
    assertEquals(1, metrics.getRunning());
    assertEquals(0, metrics.getSkipped());
    assertFalse(metrics.isTerminal());
  }

  @Test
  void activeTestsDoNotPolluteFailureOrExecutionRates() {
    List<RunRecord> runs = new ArrayList<>();
    for (int index = 0; index < 8; index++) {
      runs.add(new RunRecord("passed-" + index, "Passed " + index, "passed"));
    }
    runs.add(new RunRecord("failed-1", "Failed", "failed"));
    runs.add(new RunRecord("active-1", "Active", "list_node.run_status.in_progress"));

    GateMetrics metrics = GateMetrics.fromRuns(runs, defaultClassifier());

    assertEquals(9, metrics.getExecuted());
    assertEquals(90.0, metrics.getExecutionRate());
    assertEquals(100.0 / 9.0, metrics.getFailRate(), 0.000001);
    assertEquals(1, metrics.getRunning());
  }

  private StatusClassifier defaultClassifier() {
    return new StatusClassifier(
        StatusClassifier.DEFAULT_PASSED_STATUSES,
        StatusClassifier.DEFAULT_FAILED_STATUSES,
        StatusClassifier.DEFAULT_NEUTRAL_STATUSES,
        StatusClassifier.DEFAULT_RUNNING_STATUSES);
  }
}
