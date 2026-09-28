package io.jenkins.plugins.octanesuitegatebyembiti.models;

import static org.junit.Assert.assertEquals;

import io.jenkins.plugins.octanesuitegatebyembiti.entities.RunRecord;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class OctaneAutomationUsageTest {
  private final StatusClassifier classifier =
      new StatusClassifier(
          StatusClassifier.DEFAULT_PASSED_STATUSES,
          StatusClassifier.DEFAULT_FAILED_STATUSES,
          StatusClassifier.DEFAULT_NEUTRAL_STATUSES,
          StatusClassifier.DEFAULT_RUNNING_STATUSES);

  @Test
  public void deduplicatesRunsAndClassifiesExecutionActorCaseInsensitively() {
    RunRecord automated = run("1", "qa-JENKINS-agent", "passed", "Ada");
    RunRecord manual = run("2", "Default Manual Runner", "failed", "Ada");

    OctaneAutomationUsage usage =
        OctaneAutomationUsage.fromRuns(List.of(automated, automated, manual));

    assertEquals(1, usage.getAutomatedCount());
    assertEquals(1, usage.getManualCount());
    assertEquals(50, usage.getPercentage());
    assertEquals("50%", usage.getPercentageText());
    assertEquals("🔥", usage.getEmoji());
    assertEquals("🐢", OctaneAutomationUsage.empty().getEmoji());
    assertEquals("🐢", OctaneAutomationUsage.emojiForPercentage(0));
    assertEquals("🔥", OctaneAutomationUsage.emojiForPercentage(1));
  }

  @Test
  public void locksAutomationUsageToEachSectionAndTesterBar() {
    List<RunRecord> regressions =
        List.of(
            run("r1", "Jenkins", "passed", "Regression Owner"),
            run("r2", "Jenkins Agent", "failed", "Regression Owner"));
    List<RunRecord> critical =
        List.of(
            run("c1", "Default Manual Runner", "passed", "Critical Owner"),
            run("c2", "Ada Tester", "blocked", "Critical Owner"));
    GateScopeResult criticalScope =
        new GateScopeResult(
            "critical",
            "",
            List.of(),
            "critical-suite",
            List.of("critical-suite"),
            GateMetrics.fromRuns(critical, classifier),
            critical,
            Map.of("critical-suite", critical));
    GateResult result =
        new GateResult(
            "regression-suite",
            "100% execution",
            false,
            false,
            GateMetrics.fromRuns(regressions, classifier),
            regressions,
            Map.of("regression-suite", regressions),
            Map.of("critical", criticalScope),
            Instant.parse("2026-08-04T10:00:00Z"));

    OctaneGateReportSnapshot snapshot =
        OctaneGateReportSnapshot.fromResult(
            OctaneGateReportState.POLLING, "Polling", result, classifier, 30);

    OctaneGateReportSection regressionSection = snapshot.getSections().get(0);
    OctaneGateReportSection criticalSection = snapshot.getSections().get(1);
    assertEquals(100, regressionSection.getAutomationPercentage());
    assertEquals(100, regressionSection.getSuiteRuns().get(0).getAutomationPercentage());
    assertEquals(0, criticalSection.getAutomationPercentage());
    assertEquals(0, criticalSection.getSuiteRuns().get(0).getAutomationPercentage());
    assertEquals(2, regressionSection.getExecutedTestCount());
    assertEquals(2, criticalSection.getExecutedTestCount());
  }

  private RunRecord run(String id, String actor, String status, String owner) {
    return new RunRecord(id, "Run " + id, status, actor, owner, "test-" + id, "", "", "");
  }
}
