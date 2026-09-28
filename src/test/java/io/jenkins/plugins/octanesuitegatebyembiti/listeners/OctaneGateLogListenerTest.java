package io.jenkins.plugins.octanesuitegatebyembiti.listeners;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import hudson.model.TaskListener;
import io.jenkins.plugins.octanesuitegatebyembiti.entities.RunRecord;
import io.jenkins.plugins.octanesuitegatebyembiti.models.GateMetrics;
import io.jenkins.plugins.octanesuitegatebyembiti.models.GateRequest;
import io.jenkins.plugins.octanesuitegatebyembiti.models.GateResult;
import io.jenkins.plugins.octanesuitegatebyembiti.models.GateScopeResult;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneGateScope;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class OctaneGateLogListenerTest {
  @Test
  public void logsReadableSuiteRunSummaryAndCompactPollingMetrics() {
    OctaneGateLogListener logListener = new OctaneGateLogListener();
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    TaskListener listener = new CapturingTaskListener(output);
    GateRequest request = new GateRequest("octane-prod", "450312,450309");
    request.setCriteria(
        "(regressions.executionRate == 100 AND regressions.passRate >= 95) OR "
            + "(critical.executionRate == 100 AND critical.passRate == 100)");
    OctaneGateScope criticalScope = new OctaneGateScope("critical");
    criticalScope.setSuiteRunId("450306");
    request.setScopes(List.of(criticalScope));

    logListener.logLookupContext(listener, "1001", "2002");
    logListener.logWaiting(listener, request, request.getSuiteRunIds());
    logListener.logPollResult(listener, resultWithCriticalScope());
    logListener.logPollResult(listener, resultWithCriticalScope());

    String log = output.toString(StandardCharsets.UTF_8);
    String lineSeparator = System.lineSeparator();
    String regressionsMetrics =
        "Regressions suite runs: execution 0.00%, pass 0.00%, total 4, executed 0,"
            + " passed 0, failed 0, skipped 0, running 4.";
    String criticalMetrics =
        "Critical suite runs: execution 100.00%, pass 100.00%, total 2, executed 2,"
            + " passed 2, failed 0, skipped 0, running 0.";
    assertTrue(log.contains("ALM Octane lookup context: shared space 1001, workspace 2002."));
    assertTrue(log.contains("Waiting for ALM Octane suite run(s)"));
    assertTrue(log.contains("Regressions suite runs: 450312, 450309"));
    assertTrue(log.contains("Critical suite runs: 450306"));
    assertTrue(log.contains(regressionsMetrics));
    assertTrue(log.contains(criticalMetrics));
    assertTrue(
        log.contains(
            criticalMetrics + lineSeparator + lineSeparator + regressionsMetrics + lineSeparator));
    assertFalse(log.contains("child run statuses"));
    assertFalse(log.contains("suite run IDs 450306 metrics"));
  }

  @Test
  public void logsCriticalOnlyAuditAndOmitsRegressionPollMetrics() {
    OctaneGateLogListener logListener = new OctaneGateLogListener();
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    TaskListener listener = new CapturingTaskListener(output);

    logListener.logRegressionEvaluationSkipped(listener);
    GateResult result =
        new GateResult(
            "",
            "critical.passRate == 100",
            true,
            true,
            new GateMetrics(0, 0, 0, 0, 0, 0),
            List.of(),
            Map.of(),
            resultWithCriticalScope().getScopedResults(),
            Instant.parse("2026-05-13T00:00:00Z"));
    logListener.logPollResult(listener, result);

    String log = output.toString(StandardCharsets.UTF_8);
    assertTrue(
        log.contains(
            "[INFO/AUDIT] Regression suite-run evaluation is disabled because its selection is "
                + "empty or entirely owned by the critical scope. Skipping regression criteria."));
    assertFalse(log.contains("Regressions suite runs: execution"));
    assertTrue(log.contains("Critical suite runs: execution"));
  }

  @Test
  public void logsDynamicDiscoveryWarningsAndPoolAudits() {
    OctaneGateLogListener logListener = new OctaneGateLogListener();
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    TaskListener listener = new CapturingTaskListener(output);

    logListener.logDynamicSuiteSelector(listener, "Critical", "Release 2.4", "Sprint 3");
    logListener.logNoDynamicSuiteRuns(listener, "Critical", "Release 2.4", "Sprint 3");
    logListener.logSuiteRunsAdded(listener, "Critical", List.of("55", "56"));
    logListener.logSuiteRunsRemoved(listener, "Critical", List.of("55"));

    String log = output.toString(StandardCharsets.UTF_8);
    assertTrue(
        log.contains("continuous discovery for release 'Release 2.4' and sprint 'Sprint 3'"));
    assertTrue(log.contains("No active Critical suite runs were found"));
    assertTrue(log.contains("Use Jenkins Abort/Cancel to stop this pipeline"));
    assertTrue(log.contains("55,56 [ADDED]"));
    assertTrue(log.contains("55 [DELETED]"));
  }

  @Test
  public void logsReleaseOnlyContinuousDiscoveryWithoutAnEmptySprint() {
    OctaneGateLogListener logListener = new OctaneGateLogListener();
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    TaskListener listener = new CapturingTaskListener(output);

    logListener.logDynamicSuiteSelector(listener, "Regressions", "Kanban Release", "");
    logListener.logNoDynamicSuiteRuns(listener, "Regressions", "Kanban Release", "");

    String log = output.toString(StandardCharsets.UTF_8);
    assertTrue(log.contains("continuous discovery for release 'Kanban Release'"));
    assertTrue(
        log.contains("No active Regressions suite runs were found for release 'Kanban Release'"));
    assertFalse(log.contains("sprint ''"));
  }

  @Test
  public void logsCriteriaLifecycleWithoutPrintingTheVariableCatalogue() {
    OctaneGateLogListener logListener = new OctaneGateLogListener();
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    TaskListener listener = new CapturingTaskListener(output);

    logListener.logConfiguredCriteria(
        listener, "regressions.executionRate == 100 AND critical.passRate >= 95");
    logListener.logCriteriaVerified(listener);
    logListener.logActiveAppliedCriteria(listener, "critical.passRate >= 95");

    String log = output.toString(StandardCharsets.UTF_8);
    assertTrue(
        log.contains(
            "Configured criteria: regressions.executionRate == 100 AND critical.passRate >= 95"));
    assertTrue(log.contains("CRITERIA VERIFIED"));
    assertTrue(log.contains("Active applied criteria: critical.passRate >= 95"));
    assertFalse(log.contains("Available Variables for Custom Criteria Formulas:"));
  }

  private GateResult resultWithCriticalScope() {
    return new GateResult(
        "450312,450309",
        "(regressions.executionRate == 100 AND regressions.passRate >= 95) OR "
            + "(critical.executionRate == 100 AND critical.passRate == 100)",
        true,
        false,
        new GateMetrics(4, 0, 0, 0, 0, 4),
        List.of(
            new RunRecord("450313", "global one", "planned"),
            new RunRecord("450314", "global two", "planned"),
            new RunRecord("450310", "global three", "planned"),
            new RunRecord("450311", "global four", "planned")),
        Map.of(
            "450312",
            List.of(
                new RunRecord("450313", "global one", "planned"),
                new RunRecord("450314", "global two", "planned")),
            "450309",
            List.of(
                new RunRecord("450310", "global three", "planned"),
                new RunRecord("450311", "global four", "planned"))),
        Map.of(
            "critical",
            new GateScopeResult(
                "critical",
                "",
                List.of(),
                "450306",
                List.of("450306"),
                new GateMetrics(2, 2, 2, 0, 0, 0),
                List.of(
                    new RunRecord("450307", "critical one", "passed"),
                    new RunRecord("450308", "critical two", "passed")),
                Map.of(
                    "450306",
                    List.of(
                        new RunRecord("450307", "critical one", "passed"),
                        new RunRecord("450308", "critical two", "passed"))))),
        Instant.parse("2026-05-13T00:00:00Z"));
  }

  private static class CapturingTaskListener implements TaskListener {
    private final PrintStream logger;

    CapturingTaskListener(ByteArrayOutputStream output) {
      logger = new PrintStream(output, true, StandardCharsets.UTF_8);
    }

    @Override
    public PrintStream getLogger() {
      return logger;
    }
  }
}
