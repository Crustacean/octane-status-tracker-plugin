package io.jenkins.plugins.octanesuitegatebyembiti.services;

import io.jenkins.plugins.octanesuitegatebyembiti.entities.DefectRecord;
import io.jenkins.plugins.octanesuitegatebyembiti.entities.RunRecord;
import io.jenkins.plugins.octanesuitegatebyembiti.models.DefectCriteriaMetrics;
import io.jenkins.plugins.octanesuitegatebyembiti.models.GateMetrics;
import io.jenkins.plugins.octanesuitegatebyembiti.models.GateResult;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneDefectSeveritySummary;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneGateReportSnapshot;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneGateReportState;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneRiskHeatMap;
import io.jenkins.plugins.octanesuitegatebyembiti.models.StatusClassifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class OctaneScaleTestFixture {
  public static final int DEFECTS_PER_JOB = 1_000;
  private static final List<String> STATUSES =
      List.of("passed", "failed", "blocked", "skipped", "planned");
  private static final List<String> SEVERITIES =
      List.of("critical", "very high", "high", "medium", "low", "unspecified");

  private OctaneScaleTestFixture() {}

  public static OctaneGateReportSnapshot snapshot(int job, int suiteCount, int childRunsPerSuite) {
    return snapshot(result(job, suiteCount, childRunsPerSuite));
  }

  public static OctaneGateReportSnapshot snapshot(GateResult result) {
    return OctaneGateReportSnapshot.fromResult(
        OctaneGateReportState.POLLING,
        "Scale test polling.",
        result,
        classifier(),
        30,
        7200,
        1800,
        "2026-07-16T10:00:00Z");
  }

  public static GateResult result(int job, int suiteCount, int childRunsPerSuite) {
    Map<String, List<RunRecord>> suiteRuns = new LinkedHashMap<>();
    List<RunRecord> allRuns = new ArrayList<>(suiteCount * childRunsPerSuite);
    List<String> suiteIds = new ArrayList<>(suiteCount);
    for (int suite = 0; suite < suiteCount; suite++) {
      String suiteId = "job-" + job + "-suite-" + suite;
      suiteIds.add(suiteId);
      List<RunRecord> children = new ArrayList<>(childRunsPerSuite);
      for (int child = 0; child < childRunsPerSuite; child++) {
        String status = STATUSES.get(child % STATUSES.size());
        RunRecord run =
            new RunRecord(
                suiteId + "-run-" + child,
                "Run " + child,
                status,
                "tester-" + suite + "@example.test",
                "test-" + suite + "-" + child,
                "Test " + child,
                "project-" + job,
                "Scale project " + job);
        children.add(run);
        allRuns.add(run);
      }
      suiteRuns.put(suiteId, List.copyOf(children));
    }
    StatusClassifier classifier = classifier();
    GateMetrics metrics = GateMetrics.fromRuns(allRuns, classifier);
    List<DefectRecord> defects = new ArrayList<>(DEFECTS_PER_JOB);
    for (int defect = 0; defect < DEFECTS_PER_JOB; defect++) {
      RunRecord linkedRun = allRuns.get(defect % allRuns.size());
      defects.add(
          new DefectRecord(
              "job-" + job + "-defect-" + defect,
              "Scale defect " + defect,
              SEVERITIES.get(defect % SEVERITIES.size()),
              "",
              defect % 5 == 0 ? "closed" : "opened",
              linkedRun.getId(),
              linkedRun.getTestId(),
              "project-" + job,
              "Scale project " + job));
    }
    DefectCriteriaMetrics defectMetrics =
        new DefectCriteriaMetrics(OctaneDefectSeveritySummary.fromDefects(defects), List.of());
    Instant polledAt = Instant.parse("2026-07-16T12:00:00Z").plusSeconds(job);
    return new GateResult(
        String.join(",", suiteIds),
        "executionRate >= 0",
        false,
        metrics.isTerminal(),
        metrics,
        allRuns,
        suiteRuns,
        Map.of(),
        OctaneRiskHeatMap.disabled(),
        defectMetrics,
        polledAt);
  }

  public static StatusClassifier classifier() {
    return new StatusClassifier(
        StatusClassifier.DEFAULT_PASSED_STATUSES,
        StatusClassifier.DEFAULT_FAILED_STATUSES,
        StatusClassifier.DEFAULT_NEUTRAL_STATUSES,
        StatusClassifier.DEFAULT_RUNNING_STATUSES);
  }
}
