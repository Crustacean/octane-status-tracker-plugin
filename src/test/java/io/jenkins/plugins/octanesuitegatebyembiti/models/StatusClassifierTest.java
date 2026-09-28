package io.jenkins.plugins.octanesuitegatebyembiti.models;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.jenkins.plugins.octanesuitegatebyembiti.entities.RunRecord;
import java.util.List;
import org.junit.jupiter.api.Test;

class StatusClassifierTest {
  private final StatusClassifier classifier =
      new StatusClassifier(
          StatusClassifier.DEFAULT_PASSED_STATUSES,
          StatusClassifier.DEFAULT_FAILED_STATUSES,
          StatusClassifier.DEFAULT_NEUTRAL_STATUSES,
          StatusClassifier.DEFAULT_RUNNING_STATUSES);

  @Test
  void mapsNativeLogicalNamesWithoutConvertingActiveStatesToSkipped() {
    assertEquals(
        StatusClassifier.Outcome.RUNNING, classifier.classify("list_node.run_status.in_progress"));
    assertEquals(
        StatusClassifier.Outcome.RUNNING,
        classifier.classify("list_node.run_native_status.in_progress"));
    assertEquals(StatusClassifier.Outcome.RUNNING, classifier.classify("In Progress"));
    assertEquals(StatusClassifier.Outcome.RUNNING, classifier.classify("Planned"));
    assertEquals(StatusClassifier.Outcome.RUNNING, classifier.classify("Queued"));
    assertEquals(StatusClassifier.Outcome.RUNNING, classifier.classify("Running"));
    assertEquals(StatusClassifier.Outcome.RUNNING, classifier.classify("Not Completed"));
    assertEquals(
        StatusClassifier.Outcome.RUNNING,
        classifier.classify("list_node.run_native_status.not_completed"));
    assertEquals(
        StatusClassifier.Outcome.PASSED, classifier.classify("list_node.run_status.passed"));
    assertEquals(
        StatusClassifier.Outcome.FAILED, classifier.classify("list_node.run_status.failed"));
    assertEquals(
        StatusClassifier.Outcome.BLOCKED, classifier.classify("list_node.run_status.blocked"));
    assertEquals(
        StatusClassifier.Outcome.NEUTRAL, classifier.classify("list_node.run_status.skipped"));
    assertEquals(
        StatusClassifier.Outcome.STOPPED, classifier.classify("list_node.run_status.stopped"));
  }

  @Test
  void nativeActiveStatesTakePrecedenceOverConflictingCustomAliases() {
    StatusClassifier conflictingClassifier =
        new StatusClassifier(
            StatusClassifier.DEFAULT_PASSED_STATUSES,
            StatusClassifier.DEFAULT_FAILED_STATUSES,
            "skipped,in_progress,not_completed,queued,running",
            StatusClassifier.DEFAULT_RUNNING_STATUSES);

    assertEquals(StatusClassifier.Outcome.RUNNING, conflictingClassifier.classify("In Progress"));
    assertEquals(StatusClassifier.Outcome.RUNNING, conflictingClassifier.classify("Not Completed"));
    assertEquals(StatusClassifier.Outcome.RUNNING, conflictingClassifier.classify("Queued"));
    assertEquals(StatusClassifier.Outcome.RUNNING, conflictingClassifier.classify("Running"));
  }

  @Test
  void plannedRetainsItsConfiguredNeutralOverride() {
    StatusClassifier configuredClassifier =
        new StatusClassifier(
            StatusClassifier.DEFAULT_PASSED_STATUSES,
            StatusClassifier.DEFAULT_FAILED_STATUSES,
            "skipped,planned",
            StatusClassifier.DEFAULT_RUNNING_STATUSES);

    assertEquals(StatusClassifier.Outcome.NEUTRAL, configuredClassifier.classify("planned"));
    assertEquals(
        StatusClassifier.Outcome.NEUTRAL,
        configuredClassifier.classify("list_node.run_native_status.planned"));
  }

  @Test
  void unknownStatusesRemainActiveUntilOctaneReturnsATerminalState() {
    assertEquals(
        StatusClassifier.Outcome.RUNNING,
        classifier.classify("list_node.run_status.requires_attention"));
    assertEquals(StatusClassifier.Outcome.RUNNING, classifier.classify(""));
  }

  @Test
  void suiteRunChartRendersInProgressAsItsOwnVisibleStatusRatherThanSkipped() {
    OctaneGateSuiteRunChart chart =
        OctaneGateSuiteRunChart.fromRuns(
            "4501",
            List.of(new RunRecord("active-1", "Active", "list_node.run_status.in_progress")),
            classifier);

    assertEquals(1, chart.getRunningCount());
    assertEquals(1, chart.getInProgressCount());
    assertEquals(0, chart.getSkippedCount());
    assertEquals("In Progress", OctaneGateStatusBucket.RUNNING.getLabel());
  }
}
