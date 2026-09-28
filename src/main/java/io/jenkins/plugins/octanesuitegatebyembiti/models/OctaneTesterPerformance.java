package io.jenkins.plugins.octanesuitegatebyembiti.models;

import io.jenkins.plugins.octanesuitegatebyembiti.entities.RunRecord;
import io.jenkins.plugins.octanesuitegatebyembiti.utils.Util;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OctaneTesterPerformance implements Serializable {
  private static final long serialVersionUID = 1L;

  private final String email;
  private final int total;
  private final int executed;
  private final int passed;
  private final int failed;
  private final int blocked;
  private final int noRun;
  private final OctaneAutomationUsage automationUsage;

  private OctaneTesterPerformance(
      String email,
      int total,
      int executed,
      int passed,
      int failed,
      int blocked,
      int noRun,
      OctaneAutomationUsage automationUsage) {
    this.email = email;
    this.total = Math.max(0, total);
    this.executed = Math.max(0, executed);
    this.passed = Math.max(0, passed);
    this.failed = Math.max(0, failed);
    this.blocked = Math.max(0, blocked);
    this.noRun = Math.max(0, noRun);
    this.automationUsage =
        automationUsage == null ? OctaneAutomationUsage.empty() : automationUsage;
  }

  public static List<OctaneTesterPerformance> fromResult(
      GateResult result, StatusClassifier classifier) {
    Map<String, TesterAccumulator> testers = new LinkedHashMap<>();
    addResultRuns(testers, "regressions", result.getSuiteRuns(), result.getRuns());
    for (Map.Entry<String, GateScopeResult> entry : result.getScopedResults().entrySet()) {
      GateScopeResult scope = entry.getValue();
      if (!scope.isActive()) {
        continue;
      }
      addResultRuns(testers, "scope-" + entry.getKey(), scope.getSuiteRuns(), scope.getRuns());
    }

    List<OctaneTesterPerformance> performance = new ArrayList<>();
    for (TesterAccumulator tester : testers.values()) {
      performance.add(tester.toPerformance(classifier));
    }
    performance.sort(
        Comparator.comparing(
            (OctaneTesterPerformance tester) -> tester.getEmail(), String.CASE_INSENSITIVE_ORDER));
    return List.copyOf(performance);
  }

  public String getEmail() {
    return email;
  }

  public int getTotal() {
    return total;
  }

  public int getExecuted() {
    return executed;
  }

  public int getPassed() {
    return passed;
  }

  public int getFailed() {
    return failed;
  }

  public int getBlocked() {
    return blocked;
  }

  public int getNoRun() {
    return noRun;
  }

  public int getAutomationTestTotal() {
    return getAutomationUsage().getTotal();
  }

  public int getAutomationPercentage() {
    return getAutomationUsage().getPercentage();
  }

  public String getAutomationPercentageText() {
    return getAutomationUsage().getPercentageText();
  }

  public double getExecutionRate() {
    return GateMetrics.executionRate(executed, total);
  }

  public double getPassRate() {
    return GateMetrics.passRate(passed, executed);
  }

  public String getExecutionRateText() {
    return Util.formatCompactPercentage(getExecutionRate());
  }

  public String getPassRateText() {
    return Util.formatCompactPercentage(getPassRate());
  }

  public Map<String, Object> toMap() {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("email", email);
    values.put("total", total);
    values.put("executed", executed);
    values.put("passed", passed);
    values.put("executionRate", getExecutionRate());
    values.put("executionRateText", getExecutionRateText());
    values.put("passRate", getPassRate());
    values.put("passRateText", getPassRateText());
    return values;
  }

  private static void addResultRuns(
      Map<String, TesterAccumulator> testers,
      String source,
      Map<String, List<RunRecord>> suiteRuns,
      List<RunRecord> fallbackRuns) {
    if (suiteRuns.isEmpty()) {
      addRuns(testers, source, "matched-runs", fallbackRuns);
      return;
    }
    for (Map.Entry<String, List<RunRecord>> entry : suiteRuns.entrySet()) {
      addRuns(testers, source, entry.getKey(), entry.getValue());
    }
  }

  private static void addRuns(
      Map<String, TesterAccumulator> testers,
      String source,
      String suiteRunId,
      List<RunRecord> runs) {
    for (int index = 0; index < runs.size(); index++) {
      RunRecord run = runs.get(index);
      String email = TesterIdentityResolver.displayName(run.getSuiteOwnerName());
      String testerKey = TesterIdentityResolver.key(email);
      testers.putIfAbsent(testerKey, new TesterAccumulator(email));
      String runKey =
          Util.isBlank(run.getId())
              ? source + ":" + suiteRunId + ":anonymous-" + index
              : run.getId();
      testers.get(testerKey).put(runKey, run);
    }
  }

  private static class TesterAccumulator {
    private final String email;
    private final Map<String, RunRecord> runsById = new LinkedHashMap<>();

    private TesterAccumulator(String email) {
      this.email = email;
    }

    private void put(String runId, RunRecord run) {
      runsById.put(runId, run);
    }

    private OctaneTesterPerformance toPerformance(StatusClassifier classifier) {
      int executed = 0;
      int passed = 0;
      int failed = 0;
      int blocked = 0;
      int noRun = 0;
      for (RunRecord run : runsById.values()) {
        OctaneGateStatusBucket status =
            OctaneGateStatusBucket.fromOutcome(classifier.classify(run.getStatus()));
        if (status.isExecuted()) {
          executed++;
        }
        if (status == OctaneGateStatusBucket.PASSED) {
          passed++;
        } else if (status == OctaneGateStatusBucket.FAILED) {
          failed++;
        } else if (status == OctaneGateStatusBucket.BLOCKED) {
          blocked++;
        } else if (status == OctaneGateStatusBucket.RUNNING) {
          noRun++;
        }
      }
      return new OctaneTesterPerformance(
          email,
          runsById.size(),
          executed,
          passed,
          failed,
          blocked,
          noRun,
          OctaneAutomationUsage.fromRuns(List.copyOf(runsById.values())));
    }
  }

  private OctaneAutomationUsage getAutomationUsage() {
    return automationUsage == null ? OctaneAutomationUsage.empty() : automationUsage;
  }
}
