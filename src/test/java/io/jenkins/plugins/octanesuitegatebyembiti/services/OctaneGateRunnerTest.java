package io.jenkins.plugins.octanesuitegatebyembiti.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import hudson.model.TaskListener;
import io.jenkins.plugins.octanesuitegatebyembiti.entities.DefectRecord;
import io.jenkins.plugins.octanesuitegatebyembiti.entities.RunRecord;
import io.jenkins.plugins.octanesuitegatebyembiti.listeners.OctaneGateLogListener;
import io.jenkins.plugins.octanesuitegatebyembiti.listeners.OctaneGateReportPublisher;
import io.jenkins.plugins.octanesuitegatebyembiti.models.GateMetrics;
import io.jenkins.plugins.octanesuitegatebyembiti.models.GateRequest;
import io.jenkins.plugins.octanesuitegatebyembiti.models.GateResult;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneDefectGroup;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneDefectLedger;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneGateReportSnapshot;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneGateReportState;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneGateScope;
import io.jenkins.plugins.octanesuitegatebyembiti.models.StatusClassifier;
import io.jenkins.plugins.octanesuitegatebyembiti.models.SuiteRunSelector;
import io.jenkins.plugins.octanesuitegatebyembiti.repositories.OctaneClient;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class OctaneGateRunnerTest {
  @Test
  public void appliedCriteriaTrackerReportsOnlyTheInitialValueAndChanges() {
    OctaneGateRunner.PollingState state =
        new OctaneGateRunner.PollingState(Instant.parse("2026-05-16T14:00:00Z"));

    assertTrue(state.shouldLogAppliedCriteria("regressions.executionRate == 100"));
    assertFalse(state.shouldLogAppliedCriteria("regressions.executionRate == 100"));
    assertTrue(state.shouldLogAppliedCriteria("critical.executionRate == 100"));
    assertFalse(state.shouldLogAppliedCriteria("critical.executionRate == 100"));
  }

  @Test
  public void criticalSuiteRunsOwnOverlappingRegressionIdsForCriteria() {
    GateRequest request = new GateRequest("octane-prod", "450297,450300,450303,450303");
    OctaneGateScope critical = new OctaneGateScope("critical");
    critical.setSuiteRunId("450303,450204,450204");
    request.setScopes(List.of(critical));

    assertEquals(
        List.of("450297", "450300"), OctaneGateRunner.regressionSuiteRunIdsForCriteria(request));
    assertEquals(List.of("450303", "450204"), critical.getSuiteRunIds());
  }

  @Test
  public void identicalRegressionAndCriticalIdsMatchCriticalOnlyConfiguration() throws Exception {
    GateRequest overlapping = new GateRequest("octane-prod", "75295");
    OctaneGateScope overlappingCritical = new OctaneGateScope("critical");
    overlappingCritical.setSuiteRunId("75295");
    overlapping.setScopes(List.of(overlappingCritical));

    GateRequest criticalOnly = new GateRequest("octane-prod", "");
    OctaneGateScope onlyCritical = new OctaneGateScope("critical");
    onlyCritical.setSuiteRunId("75295");
    criticalOnly.setScopes(List.of(onlyCritical));

    OctaneGateRunner.validateSuiteRunSources(overlapping);
    OctaneGateRunner.validateSuiteRunSources(criticalOnly);
    assertEquals(
        OctaneGateRunner.regressionSuiteRunIdsForCriteria(criticalOnly),
        OctaneGateRunner.regressionSuiteRunIdsForCriteria(overlapping));
    assertTrue(OctaneGateRunner.regressionSuiteRunIdsForCriteria(overlapping).isEmpty());
  }

  @Test
  public void emptyRegressionRequiresCriticalSuiteRunIds() throws Exception {
    GateRequest request = new GateRequest("octane-prod", "");

    try {
      OctaneGateRunner.validateSuiteRunSources(request);
      fail("Expected a critical suite run validation error.");
    } catch (hudson.AbortException e) {
      assertTrue(e.getMessage().contains("critical Octane suite run selection is required"));
    }
  }

  @Test
  public void releaseSprintSelectionKeepsRegressionEvaluationEnabled() {
    GateRequest request = new GateRequest("octane-prod", "Release 2.4, Sprint 3");

    assertTrue(OctaneGateRunner.regressionSelectionEnabled(request));
    assertTrue(OctaneGateRunner.regressionSuiteRunIdsForCriteria(request).isEmpty());
  }

  @Test
  public void releaseOnlySelectionKeepsRegressionEvaluationEnabled() {
    GateRequest request = new GateRequest("octane-prod", "Kanban Release 2.4");

    assertTrue(OctaneGateRunner.regressionSelectionEnabled(request));
    assertTrue(request.getSuiteRunSelector().isDynamic());
    assertEquals("", request.getSuiteRunSelector().getSprintName());
    assertTrue(OctaneGateRunner.regressionSuiteRunIdsForCriteria(request).isEmpty());
  }

  @Test
  public void identicalReleaseSprintSelectionsAreEvaluatedAsCriticalOnly() {
    GateRequest request = new GateRequest("octane-prod", "Release 2.4, Sprint 3");
    OctaneGateScope critical = new OctaneGateScope("critical");
    critical.setSuiteRunId("Release 2.4, Sprint 3");
    request.setScopes(List.of(critical));

    assertFalse(OctaneGateRunner.regressionSelectionEnabled(request));
  }

  @Test
  public void identicalDynamicSelectorsShareOneLookupPerPollingCycle() throws Exception {
    SuiteRunSelector regression = SuiteRunSelector.parse("Release 2.4, Sprint 3");
    SuiteRunSelector critical = SuiteRunSelector.parse("Release 2.4, Sprint 3");
    AtomicInteger lookups = new AtomicInteger();
    OctaneGateRunner.SuiteRunDiscoveryCycle cycle = new OctaneGateRunner.SuiteRunDiscoveryCycle();

    List<String> regressionIds =
        cycle.resolve(
            regression,
            selector -> {
              lookups.incrementAndGet();
              return List.of("1001", "1002");
            });
    List<String> criticalIds =
        cycle.resolve(
            critical,
            selector -> {
              lookups.incrementAndGet();
              return List.of("unexpected");
            });

    assertEquals(1, lookups.get());
    assertEquals(List.of("1001", "1002"), regressionIds);
    assertEquals(regressionIds, criticalIds);
  }

  @Test
  public void dynamicSelectorDiscoveryRefreshesOnEveryPollingCycle() throws Exception {
    SuiteRunSelector selector = SuiteRunSelector.parse("Release 2.4, Sprint 3");
    AtomicInteger lookups = new AtomicInteger();
    OctaneGateRunner.DynamicSuiteRunDiscovery discovery =
        ignored -> lookups.incrementAndGet() == 1 ? List.of("1001") : List.of("1001", "1002");

    List<String> firstPoll =
        new OctaneGateRunner.SuiteRunDiscoveryCycle().resolve(selector, discovery);
    List<String> secondPoll =
        new OctaneGateRunner.SuiteRunDiscoveryCycle().resolve(selector, discovery);

    assertEquals(List.of("1001"), firstPoll);
    assertEquals(List.of("1001", "1002"), secondPoll);
    assertEquals(2, lookups.get());
  }

  @Test
  public void explicitIdSelectionDoesNotInvokeDynamicDiscovery() throws Exception {
    SuiteRunSelector selector = SuiteRunSelector.parse("1001,1002,1002");
    AtomicInteger lookups = new AtomicInteger();

    List<String> ids =
        new OctaneGateRunner.SuiteRunDiscoveryCycle()
            .resolve(
                selector,
                ignored -> {
                  lookups.incrementAndGet();
                  return List.of();
                });

    assertEquals(List.of("1001", "1002"), ids);
    assertEquals(0, lookups.get());
  }

  @Test
  public void explicitIdPollingSurvivesCriticalBucketRemoval() throws Exception {
    assertPollingSurvivesBucketRemoval("1001", "2001", "critical");
  }

  @Test
  public void explicitIdPollingSurvivesRegressionBucketRemoval() throws Exception {
    assertPollingSurvivesBucketRemoval("1001", "2001", "regressions");
  }

  @Test
  public void releasePollingSurvivesCriticalBucketRemoval() throws Exception {
    assertPollingSurvivesBucketRemoval(
        "Regression Release, Regression Sprint", "Critical Release, Critical Sprint", "critical");
  }

  @Test
  public void releasePollingSurvivesRegressionBucketRemoval() throws Exception {
    assertPollingSurvivesBucketRemoval(
        "Regression Release, Regression Sprint",
        "Critical Release, Critical Sprint",
        "regressions");
  }

  @Test
  public void criticalBucketRepopulationRestoresTheCompleteReportPipeline() throws Exception {
    assertBucketRepopulationRestoresTheCompleteReportPipeline("critical");
  }

  @Test
  public void regressionBucketRepopulationRestoresTheCompleteReportPipeline() throws Exception {
    assertBucketRepopulationRestoresTheCompleteReportPipeline("regressions");
  }

  @Test
  public void identicalAndOmittedRegressionIdsProduceTheSameCriticalOnlyResult() throws Exception {
    OctaneGateRunner runner =
        new OctaneGateRunner(
            Clock.fixed(Instant.parse("2026-05-16T14:00:00Z"), ZoneOffset.UTC),
            new OctaneGateLogListener());
    GateRequest overlapping = criticalOnlyRequest("75295");
    GateRequest omitted = criticalOnlyRequest("");

    GateResult overlappingResult = refreshCriticalOnlyResult(runner, overlapping);
    GateResult omittedResult = refreshCriticalOnlyResult(runner, omitted);

    assertTrue(overlappingResult.isPassed());
    assertTrue(omittedResult.isPassed());
    assertTrue(overlappingResult.isTerminal());
    assertTrue(omittedResult.isTerminal());
    assertFalse(overlappingResult.isRegressionEvaluationEnabled());
    assertFalse(omittedResult.isRegressionEvaluationEnabled());
    assertEquals(
        overlappingResult.getScopedMetrics().get("critical").getPassRate(),
        omittedResult.getScopedMetrics().get("critical").getPassRate(),
        0.000001);
    assertEquals(
        overlappingResult.getCriteriaEvaluation().toMap(),
        omittedResult.getCriteriaEvaluation().toMap());
    assertEquals("critical.executionRate == 100", overlappingResult.getCriteria());
    assertEquals(overlappingResult.getCriteria(), omittedResult.getCriteria());
    assertFalse(overlappingResult.getCriteria().contains("regressions."));
  }

  @Test
  public void taskCriteriaScenariosUseOnlyApplicableBuckets() throws Exception {
    String criticalAndDefectCriteria =
        "(critical.executionRate == 100 AND critical.passRate == 100) "
            + "AND (defects.major < 10% AND defects.minor < 20%) "
            + "AND (defects.Unspecified == 0%)";

    GateResult identicalIds =
        refreshTaskCriteriaScenario(
            "1196,1200,1204",
            "(regressions.executionRate == 100 AND regressions.passRate >= 95) "
                + "AND "
                + criticalAndDefectCriteria);
    GateResult omittedRegressionOr =
        refreshTaskCriteriaScenario(
            "",
            "(regressions.executionRate == 100 AND regressions.passRate >= 95) "
                + "OR "
                + criticalAndDefectCriteria);
    GateResult distinctRegression =
        refreshTaskCriteriaScenario("1197,2200,1201", criticalAndDefectCriteria);
    GateResult mixedRegressionBranches =
        refreshTaskCriteriaScenario(
            "",
            "(regressions.executionRate == 100 AND critical.executionRate == 100) "
                + "AND (critical.passRate == 100 OR regressions.passRate >= 95) "
                + "AND (defects.major < 10% AND defects.minor < 20%) "
                + "AND (defects.Unspecified == 0%)");
    GateResult mixedRegressionOrBranches =
        refreshTaskCriteriaScenario(
            "",
            "(regressions.executionRate == 100 AND critical.executionRate == 100) "
                + "OR (critical.passRate == 100 OR regressions.passRate >= 95) "
                + "AND (defects.major < 10% AND defects.minor < 20%) "
                + "AND (defects.Unspecified == 0%)");

    assertCriticalOnlyScenario(identicalIds, criticalAndDefectCriteria);
    assertCriticalOnlyScenario(omittedRegressionOr, criticalAndDefectCriteria);
    assertCriticalOnlyScenario(mixedRegressionBranches, criticalAndDefectCriteria);
    assertCriticalOnlyScenario(
        mixedRegressionOrBranches,
        "(critical.executionRate == 100 OR critical.passRate == 100) "
            + "AND (defects.major < 10% AND defects.minor < 20%) "
            + "AND (defects.Unspecified == 0%)");

    assertTrue(distinctRegression.isRegressionEvaluationEnabled());
    assertTrue(distinctRegression.isPassed());
    assertEquals(criticalAndDefectCriteria, distinctRegression.getCriteria());
    assertNoRegressionComparisons(distinctRegression);
    assertEquals(3, distinctRegression.getSuiteRuns().size());
  }

  @Test
  public void nonCriticalSuiteRunScopesDoNotOwnRegressionIds() {
    GateRequest request = new GateRequest("octane-prod", "450297,450300,450303");
    OctaneGateScope smoke = new OctaneGateScope("smoke");
    smoke.setSuiteRunId("450303");
    request.setScopes(List.of(smoke));

    assertEquals(
        List.of("450297", "450300", "450303"),
        OctaneGateRunner.regressionSuiteRunIdsForCriteria(request));
  }

  @Test
  public void timeoutMinutesExtendedDefaultsToZeroAndRejectsNegativeValues() {
    GateRequest request = new GateRequest("octane-prod", "4501");

    assertEquals(0, request.getTimeoutMinutesExtended());

    request.setTimeoutMinutesExtended(30);
    assertEquals(30, request.getTimeoutMinutesExtended());

    request.setTimeoutMinutesExtended(-5);
    assertEquals(0, request.getTimeoutMinutesExtended());
  }

  @Test
  public void passingCriteriaRemainInPollingWhileAnyRunIsInProgress() throws Exception {
    GateRequest request = new GateRequest("octane-prod", "4501");
    request.setCriteria("regressions.passRate >= 90");
    request.setTimeoutMinutes(5);
    request.setTimeoutMinutesExtended(0);
    List<RunRecord> runs = new java.util.ArrayList<>();
    for (int index = 0; index < 9; index++) {
      runs.add(new RunRecord("passed-" + index, "Passed " + index, "passed"));
    }
    runs.add(new RunRecord("active-1", "In progress", "list_node.run_status.in_progress"));
    OctaneGateRunner runner =
        new OctaneGateRunner(
            Clock.fixed(Instant.parse("2026-05-16T14:00:00Z"), ZoneOffset.UTC),
            new OctaneGateLogListener());

    GateResult result =
        runner.poll(
            new NoDefectOctaneClient(),
            request,
            Map.of("4501", runs),
            Map.of(),
            true,
            false,
            "1001",
            "2001",
            CriteriaExpression.parse(request.getCriteria()),
            request.createStatusClassifier(),
            taskListener(new ByteArrayOutputStream()),
            new OctaneDefectLedger());

    assertTrue(result.isPassed());
    assertFalse(result.isTerminal());
    assertEquals(90.0, result.getMetrics().getCompletionRate(), 0.000001);
    assertEquals(1, result.getMetrics().getRunning());
    assertFalse(OctaneGateRunner.isReadyToFinalizeWithoutExtendedTimeout(result, false));
    assertFalse(OctaneGateRunner.isReadyToFinalizeWithoutExtendedTimeout(result, true));
  }

  @Test
  public void terminalResultCanFinalizeWithoutExtendedTimeout() {
    GateRequest request = new GateRequest("octane-prod", "4501");
    GateResult result = previousPassedResult(request);

    assertTrue(OctaneGateRunner.isReadyToFinalizeWithoutExtendedTimeout(result, false));
    assertFalse(OctaneGateRunner.isReadyToFinalizeWithoutExtendedTimeout(result, true));
  }

  @Test
  public void threePollLifecycleFinalizesOnlyAfterTheLastActiveRunCompletes() throws Exception {
    GateRequest request = new GateRequest("octane-prod", "4501");
    request.setCriteria("regressions.completionRate == 100");
    request.setTimeoutMinutes(5);
    request.setTimeoutMinutesExtended(0);
    OctaneGateRunner runner =
        new OctaneGateRunner(
            Clock.fixed(Instant.parse("2026-08-26T08:00:00Z"), ZoneOffset.UTC),
            new OctaneGateLogListener());
    List<List<RunRecord>> pollRuns = List.of(statusRuns(5, 5), statusRuns(9, 1), statusRuns(10, 0));
    AtomicInteger intervalUpdates = new AtomicInteger();
    AtomicInteger finalReports = new AtomicInteger();

    for (List<RunRecord> runs : pollRuns) {
      GateResult result =
          runner.poll(
              new NoDefectOctaneClient(),
              request,
              Map.of("4501", runs),
              Map.of(),
              true,
              false,
              "1001",
              "2001",
              CriteriaExpression.parse(request.getCriteria()),
              request.createStatusClassifier(),
              taskListener(new ByteArrayOutputStream()),
              new OctaneDefectLedger());
      if (OctaneGateRunner.isReadyToFinalizeWithoutExtendedTimeout(result, false)) {
        finalReports.incrementAndGet();
      } else {
        intervalUpdates.incrementAndGet();
      }
    }

    assertEquals(2, intervalUpdates.get());
    assertEquals(1, finalReports.get());
  }

  @Test
  public void configuredNeutralPlannedRunCanFinalizeWithoutWaitingForTimeout() throws Exception {
    GateRequest request = new GateRequest("octane-prod", "4501");
    request.setCriteria("regressions.completionRate == 100");
    request.setNeutralStatuses("skipped,planned");
    request.setTimeoutMinutes(5);
    request.setTimeoutMinutesExtended(0);
    List<RunRecord> runs =
        List.of(new RunRecord("planned-1", "Descoped", "list_node.run_native_status.planned"));
    OctaneGateRunner runner =
        new OctaneGateRunner(
            Clock.fixed(Instant.parse("2026-08-26T08:00:00Z"), ZoneOffset.UTC),
            new OctaneGateLogListener());

    GateResult result =
        runner.poll(
            new NoDefectOctaneClient(),
            request,
            Map.of("4501", runs),
            Map.of(),
            true,
            false,
            "1001",
            "2001",
            CriteriaExpression.parse(request.getCriteria()),
            request.createStatusClassifier(),
            taskListener(new ByteArrayOutputStream()),
            new OctaneDefectLedger());

    assertEquals(1, result.getMetrics().getSkipped());
    assertEquals(0, result.getMetrics().getRunning());
    assertTrue(result.isTerminal());
    assertTrue(result.isPassed());
    assertTrue(OctaneGateRunner.isReadyToFinalizeWithoutExtendedTimeout(result, false));
  }

  @Test
  public void refreshPassedResultPublishesFreshMetricsBeforeFinal() throws Exception {
    OctaneGateRunner runner =
        new OctaneGateRunner(
            Clock.fixed(Instant.parse("2026-05-16T14:00:00Z"), ZoneOffset.UTC),
            new OctaneGateLogListener());
    GateRequest request = new GateRequest("octane-prod", "4501");
    request.setCriteria("100% execution");
    StatusClassifier classifier = request.createStatusClassifier();
    AtomicReference<GateResult> publishedResult = new AtomicReference<>();
    ByteArrayOutputStream log = new ByteArrayOutputStream();

    GateResult refreshedResult =
        runner.refreshPassedResult(
            new FakeOctaneClient(
                List.of(new RunRecord("1", "one", "passed"), new RunRecord("2", "two", "passed"))),
            previousPassedResult(request),
            request,
            request.getSuiteRunIds(),
            "1001",
            "2001",
            CriteriaExpression.parse(request.getCriteria()),
            classifier,
            taskListener(log),
            capturingPublisher(publishedResult));

    assertEquals(2, refreshedResult.getMetrics().getTotal());
    assertEquals(2, refreshedResult.getMetrics().getExecuted());
    assertTrue(refreshedResult.isPassed());
    assertNotNull(publishedResult.get());
    assertEquals(2, publishedResult.get().getMetrics().getTotal());
    String auditLog = log.toString(StandardCharsets.UTF_8);
    assertTrue(auditLog.contains("FINALIZING: fetching the authoritative final state"));
    assertTrue(
        auditLog.contains("Final ALM Octane reconciliation completed at 2026/05/16 17:00:00"));
  }

  @Test
  public void refreshPassedResultCanReturnNonPassingFreshMetrics() throws Exception {
    OctaneGateRunner runner =
        new OctaneGateRunner(
            Clock.fixed(Instant.parse("2026-05-16T14:00:00Z"), ZoneOffset.UTC),
            new OctaneGateLogListener());
    GateRequest request = new GateRequest("octane-prod", "4501");
    request.setCriteria("100% execution");
    StatusClassifier classifier = request.createStatusClassifier();

    GateResult refreshedResult =
        runner.refreshPassedResult(
            new FakeOctaneClient(List.of(new RunRecord("1", "one", "planned"))),
            previousPassedResult(request),
            request,
            request.getSuiteRunIds(),
            "1001",
            "2001",
            CriteriaExpression.parse(request.getCriteria()),
            classifier,
            taskListener(new ByteArrayOutputStream()),
            new OctaneGateReportPublisher() {});

    assertFalse(refreshedResult.isPassed());
    assertEquals(1, refreshedResult.getMetrics().getRunning());
  }

  @Test
  public void refreshPassedResultKeepsPreviousPassWhenFinalRefreshFails() throws Exception {
    OctaneGateRunner runner =
        new OctaneGateRunner(
            Clock.fixed(Instant.parse("2026-05-16T14:00:00Z"), ZoneOffset.UTC),
            new OctaneGateLogListener());
    GateRequest request = new GateRequest("octane-prod", "4501");
    request.setCriteria("100% execution");
    GateResult previousResult = previousPassedResult(request);
    ByteArrayOutputStream log = new ByteArrayOutputStream();

    GateResult refreshedResult =
        runner.refreshPassedResult(
            new FailingOctaneClient(),
            previousResult,
            request,
            request.getSuiteRunIds(),
            "1001",
            "2001",
            CriteriaExpression.parse(request.getCriteria()),
            request.createStatusClassifier(),
            taskListener(log),
            new OctaneGateReportPublisher() {});

    assertSame(previousResult, refreshedResult);
    assertTrue(log.toString(StandardCharsets.UTF_8).contains("Skipped final ALM Octane refresh"));
  }

  @Test
  public void defectCriteriaFetchesDefectsWithoutEnablingHeatMapReport() throws Exception {
    OctaneGateRunner runner =
        new OctaneGateRunner(
            Clock.fixed(Instant.parse("2026-05-16T14:00:00Z"), ZoneOffset.UTC),
            new OctaneGateLogListener());
    GateRequest request = new GateRequest("octane-prod", "4501");
    OctaneDefectGroup major = new OctaneDefectGroup("major");
    major.setTypes("Critical, Very High, High, Unspecified");
    request.setDefectGroups(List.of(major));
    request.setCriteria("defects.MAJOR == 100% AND defects.criticalCount == 1");

    GateResult result =
        runner.refreshPassedResult(
            new DefectAwareOctaneClient(),
            previousPassedResult(request),
            request,
            request.getSuiteRunIds(),
            "1001",
            "2001",
            CriteriaExpression.parse(request.getCriteria()),
            request.createStatusClassifier(),
            taskListener(new ByteArrayOutputStream()),
            new OctaneGateReportPublisher() {});

    assertTrue(result.isPassed());
    assertFalse(result.getRiskHeatMap().isEnabled());
    assertEquals(1.0, result.getDefectMetrics().value("majorCount"), 0.000001);
    assertEquals(100.0, result.getDefectMetrics().value("major"), 0.000001);
  }

  @Test
  public void suiteScopedDefectPoolFeedsAllChartsAndTheEmailTable() throws Exception {
    GateRequest request = new GateRequest("octane-prod", "regression-suite");
    request.setCriteria("defects.highCount >= 0");
    request.setRiskHeatMap(true);
    OctaneGateScope critical = new OctaneGateScope("critical");
    critical.setSuiteRunId("critical-suite");
    request.setScopes(List.of(critical));
    RunRecord regressionRun = scopedRun("regression-run", "regression-test");
    RunRecord criticalRun = scopedRun("critical-run", "critical-test");
    ScopeIsolationDefectClient client = new ScopeIsolationDefectClient();
    StatusClassifier classifier = request.createStatusClassifier();
    OctaneGateRunner runner =
        new OctaneGateRunner(
            Clock.fixed(Instant.parse("2026-05-16T14:00:00Z"), ZoneOffset.UTC),
            new OctaneGateLogListener());

    GateResult result =
        runner.poll(
            client,
            request,
            Map.of("regression-suite", List.of(regressionRun)),
            Map.of("critical", Map.of("critical-suite", List.of(criticalRun))),
            true,
            false,
            "1001",
            "2001",
            CriteriaExpression.parse(request.getCriteria()),
            classifier,
            taskListener(new ByteArrayOutputStream()),
            new OctaneDefectLedger());
    OctaneGateReportSnapshot snapshot =
        OctaneGateReportSnapshot.fromResult(
            OctaneGateReportState.POLLING,
            "Polling",
            result,
            classifier,
            30,
            300,
            0,
            "2026-05-16T13:59:00Z",
            GateRequest.DEFAULT_LIMIT_FOR_METRIC_RUNS_IN_SUITE);

    assertEquals(5, result.getDefects().size());
    assertEquals(5, result.getRiskHeatMap().getDefectSeveritySummary().getTotal());
    assertEquals(5, snapshot.getTestManagement().getTotalDefects());
    assertEquals(5, snapshot.getDefectTrend().getRaisedTotal());
    assertEquals(
        5,
        snapshot.getDefectTrend().getDensityBuckets().stream()
            .mapToInt(bucket -> bucket.getNewDefects())
            .sum());

    Set<String> chartIds = new LinkedHashSet<>();
    for (var category : snapshot.getTestManagement().getFailureCategories()) {
      for (var defect : category.getDefects()) {
        chartIds.add(defect.getId());
      }
    }
    String email =
        new OctaneEmailBodyRenderer()
            .render(
                "{{EXECUTION_DETAILS}}\n{{REPORT_SCREENSHOT}}",
                "Project",
                "Domain",
                snapshot,
                "https://jenkins.example/job/1/octane-gate-report/",
                "report-image",
                "LIGHT",
                false,
                "",
                false,
                true,
                "",
                100,
                false);
    Set<String> emailIds = new LinkedHashSet<>();
    for (DefectRecord defect : client.defects) {
      if (email.contains(">" + defect.getId() + "</td>")) {
        emailIds.add(defect.getId());
      }
    }
    assertEquals(chartIds, emailIds);
    assertEquals(
        Set.of("regression-1", "regression-2", "regression-3", "critical-1", "critical-2"),
        chartIds);
  }

  @Test
  public void totalFormulaMetricsDeduplicateRunsAcrossRegressionAndCriticalTargets()
      throws Exception {
    GateRequest request = new GateRequest("octane-prod", "1001");
    request.setCriteria(
        "total.total == 4 AND tests_executed == 2 AND tests_resolved == 3 "
            + "AND total.executionRate == 50 AND total.completionRate == 75");
    OctaneGateScope critical = new OctaneGateScope("critical");
    critical.setSuiteRunId("2001");
    request.setScopes(List.of(critical));
    RunRecord overlappingPlanned = new RunRecord("run-2", "overlap", "planned");
    Map<String, List<RunRecord>> regressionRuns =
        Map.of("1001", List.of(new RunRecord("run-1", "passed", "passed"), overlappingPlanned));
    Map<String, Map<String, List<RunRecord>>> scopeRuns =
        Map.of(
            "critical",
            Map.of(
                "2001",
                List.of(
                    overlappingPlanned,
                    new RunRecord("run-3", "failed", "failed"),
                    new RunRecord("run-4", "skipped", "skipped"))));
    OctaneGateRunner runner =
        new OctaneGateRunner(
            Clock.fixed(Instant.parse("2026-05-16T14:00:00Z"), ZoneOffset.UTC),
            new OctaneGateLogListener());

    GateResult result =
        runner.poll(
            new NoDefectOctaneClient(),
            request,
            regressionRuns,
            scopeRuns,
            true,
            false,
            "1001",
            "2001",
            CriteriaExpression.parse(request.getCriteria()),
            request.createStatusClassifier(),
            taskListener(new ByteArrayOutputStream()),
            new OctaneDefectLedger());

    assertTrue(result.isPassed());
    assertEquals(5, result.getCriteriaEvaluation().getComparisons().size());
  }

  private GateResult previousPassedResult(GateRequest request) {
    List<RunRecord> runs = List.of(new RunRecord("1", "one", "passed"));
    return new GateResult(
        request.getSuiteRunId(),
        request.getCriteria(),
        true,
        true,
        new GateMetrics(1, 1, 1, 0, 0, 0),
        runs,
        Map.of("4501", runs),
        Map.of(),
        Instant.parse("2026-05-16T13:59:00Z"));
  }

  @SuppressWarnings("unchecked")
  private void assertPollingSurvivesBucketRemoval(
      String regressionSelector, String criticalSelector, String removedBucket) throws Exception {
    GateRequest request = new GateRequest("octane-prod", regressionSelector);
    request.setCriteria("regressions.executionRate == 100 AND critical.executionRate == 100");
    OctaneGateScope critical = new OctaneGateScope("critical");
    critical.setSuiteRunId(criticalSelector);
    request.setScopes(List.of(critical));

    boolean dynamicSelectors = regressionSelector.contains("Release");
    assertEquals(dynamicSelectors, request.getSuiteRunSelector().isDynamic());
    assertEquals(dynamicSelectors, critical.getSuiteRunSelector().isDynamic());

    List<RunRecord> passedRuns = List.of(new RunRecord("run-1", "passed", "passed"));
    boolean regressionRemoved = "regressions".equals(removedBucket);
    boolean criticalRemoved = "critical".equals(removedBucket);
    Map<String, List<RunRecord>> regressionRuns =
        regressionRemoved ? Map.of() : Map.of("1001", passedRuns);
    Map<String, Map<String, List<RunRecord>>> scopeRuns =
        Map.of("critical", criticalRemoved ? Map.of() : Map.of("2001", passedRuns));

    OctaneGateRunner runner =
        new OctaneGateRunner(
            Clock.fixed(Instant.parse("2026-05-16T14:00:00Z"), ZoneOffset.UTC),
            new OctaneGateLogListener());
    GateResult result =
        runner.poll(
            new NoDefectOctaneClient(),
            request,
            regressionRuns,
            scopeRuns,
            true,
            false,
            "1001",
            "2001",
            CriteriaExpression.parse(request.getCriteria()),
            request.createStatusClassifier(),
            taskListener(new ByteArrayOutputStream()),
            new OctaneDefectLedger());

    assertTrue(result.isPassed());
    assertTrue(result.isTerminal());
    assertFalse(result.getCriteria().contains(removedBucket + "."));
    assertEquals(1, result.getCriteriaEvaluation().getComparisons().size());

    Map<String, Object> pipelineMap = result.toPipelineMap();
    Map<String, Object> regressions = (Map<String, Object>) pipelineMap.get("regressions");
    Map<String, Object> scopes = (Map<String, Object>) pipelineMap.get("scopes");
    Map<String, Object> criticalMetrics = (Map<String, Object>) scopes.get("critical");
    assertNotNull(criticalMetrics);

    if (regressionRemoved) {
      assertFalse(result.isRegressionEvaluationEnabled());
      assertEquals(false, regressions.get("active"));
      assertEquals(0.0, regressions.get("executionRate"));
      assertEquals(true, criticalMetrics.get("active"));
    } else {
      assertTrue(result.isRegressionEvaluationEnabled());
      assertEquals(true, regressions.get("active"));
      assertEquals(false, criticalMetrics.get("active"));
      assertEquals(0.0, criticalMetrics.get("executionRate"));
      Map<String, Object> scopeDetails = (Map<String, Object>) pipelineMap.get("scopeDetails");
      assertNotNull(scopeDetails.get("critical"));
    }

    OctaneGateReportSnapshot snapshot =
        OctaneGateReportSnapshot.fromResult(
            OctaneGateReportState.POLLING, "Polling", result, request.createStatusClassifier(), 30);
    assertEquals(1, snapshot.getSections().size());
    assertEquals(
        regressionRemoved ? "critical" : "regressions", snapshot.getSections().get(0).getSource());
    assertEquals(
        500, HeadlessBrowserReportScreenshotService.estimateViewportHeight(snapshot, 1400));
    assertScreenshotContainsOnlyActiveSection(snapshot, regressionRemoved);

    OctaneGateReportSnapshot finalSnapshot =
        OctaneGateReportSnapshot.fromResult(
            OctaneGateReportState.PASSED, "Passed", result, request.createStatusClassifier(), 30);
    assertEquals(
        500, HeadlessBrowserReportScreenshotService.estimateViewportHeight(finalSnapshot, 1400));
    assertScreenshotContainsOnlyActiveSection(finalSnapshot, regressionRemoved);
  }

  private void assertScreenshotContainsOnlyActiveSection(
      OctaneGateReportSnapshot snapshot, boolean regressionRemoved) {
    String screenshotHtml = new OctaneReportZoneHtmlRenderer().render(snapshot, "DARK", 1400);
    String activeSource = regressionRemoved ? "critical" : "regressions";
    String removedSource = regressionRemoved ? "regressions" : "critical";

    assertTrue(screenshotHtml.contains("data-card-key=\"distribution-" + activeSource + "\""));
    assertTrue(screenshotHtml.contains("data-card-key=\"bars-" + activeSource + "\""));
    assertFalse(screenshotHtml.contains("data-card-key=\"distribution-" + removedSource + "\""));
    assertFalse(screenshotHtml.contains("data-card-key=\"bars-" + removedSource + "\""));
  }

  @SuppressWarnings("unchecked")
  private void assertBucketRepopulationRestoresTheCompleteReportPipeline(String repopulatedBucket)
      throws Exception {
    GateRequest request = new GateRequest("octane-prod", "Regression Release, Regression Sprint");
    request.setCriteria(
        "(regressions.executionRate == 100) AND (critical.executionRate == 100) "
            + "AND (defects.criticalCount == 1)");
    request.setRiskHeatMap(true);
    OctaneGateScope critical = new OctaneGateScope("critical");
    critical.setSuiteRunId("Critical Release, Critical Sprint");
    request.setScopes(List.of(critical));

    RunRecord existingRun =
        new RunRecord(
            "existing-run",
            "Existing run",
            "passed",
            "Existing Tester",
            "existing-test",
            "Existing test",
            "project-1",
            "Project");
    RunRecord repopulatedRun =
        new RunRecord(
            "repopulated-run",
            "Repopulated run",
            "passed",
            "New Tester",
            "repopulated-test",
            "Repopulated test",
            "project-1",
            "Project");
    boolean regressionRepopulated = "regressions".equals(repopulatedBucket);
    Map<String, List<RunRecord>> inactiveRegressionRuns =
        regressionRepopulated ? Map.of() : Map.of("regression-suite", List.of(existingRun));
    Map<String, Map<String, List<RunRecord>>> inactiveScopeRuns =
        Map.of(
            "critical",
            regressionRepopulated ? Map.of("critical-suite", List.of(existingRun)) : Map.of());
    Map<String, List<RunRecord>> activeRegressionRuns =
        Map.of("regression-suite", List.of(regressionRepopulated ? repopulatedRun : existingRun));
    Map<String, Map<String, List<RunRecord>>> activeScopeRuns =
        Map.of(
            "critical",
            Map.of(
                "critical-suite", List.of(regressionRepopulated ? existingRun : repopulatedRun)));

    RepopulationDefectClient client = new RepopulationDefectClient("repopulated-run");
    OctaneDefectLedger defectLedger = new OctaneDefectLedger();
    OctaneGateRunner runner =
        new OctaneGateRunner(
            Clock.fixed(Instant.parse("2026-05-16T14:00:00Z"), ZoneOffset.UTC),
            new OctaneGateLogListener());
    CriteriaExpression criteria = CriteriaExpression.parse(request.getCriteria());
    TaskListener listener = taskListener(new ByteArrayOutputStream());

    GateResult inactiveResult =
        runner.poll(
            client,
            request,
            inactiveRegressionRuns,
            inactiveScopeRuns,
            true,
            false,
            "1001",
            "2001",
            criteria,
            request.createStatusClassifier(),
            listener,
            defectLedger);
    assertFalse(inactiveResult.getCriteria().contains(repopulatedBucket + "."));
    assertEquals(0, inactiveResult.getDefects().size());

    GateResult activeResult =
        runner.poll(
            client,
            request,
            activeRegressionRuns,
            activeScopeRuns,
            true,
            false,
            "1001",
            "2001",
            criteria,
            request.createStatusClassifier(),
            listener,
            defectLedger);

    assertEquals(request.getCriteria(), activeResult.getCriteria());
    assertEquals(3, activeResult.getCriteriaEvaluation().getComparisons().size());
    assertTrue(activeResult.isRegressionEvaluationEnabled());
    assertTrue(activeResult.getScopedResults().get("critical").isActive());
    assertTrue(activeResult.isPassed());
    assertEquals(2, activeResult.getDefects().size());
    assertEquals(1, activeResult.getRiskHeatMap().getDefectSeveritySummary().getOpenTotal());
    assertEquals(1, activeResult.getRiskHeatMap().getDefectSeveritySummary().getClosed());
    assertEquals(2, client.getLinkedDefectPolls());
    assertTrue(client.isRepopulatedRunObserved());

    Map<String, Object> pipelineMap = activeResult.toPipelineMap();
    assertEquals(true, ((Map<String, Object>) pipelineMap.get("regressions")).get("active"));
    assertEquals(
        true,
        ((Map<String, Object>) ((Map<String, Object>) pipelineMap.get("scopes")).get("critical"))
            .get("active"));

    assertRestoredEmailReport(activeResult, request, OctaneGateReportState.POLLING);
    assertRestoredEmailReport(activeResult, request, OctaneGateReportState.PASSED);
  }

  @SuppressWarnings("unchecked")
  private void assertRestoredEmailReport(
      GateResult result, GateRequest request, OctaneGateReportState state) {
    OctaneGateReportSnapshot snapshot =
        OctaneGateReportSnapshot.fromResult(
            state, state.getLabel(), result, request.createStatusClassifier(), 30);
    assertEquals(2, snapshot.getReportSections().size());
    assertEquals(
        880, HeadlessBrowserReportScreenshotService.estimateViewportHeight(snapshot, 1400));

    String screenshotHtml = new OctaneReportZoneHtmlRenderer().render(snapshot, "DARK", 1400);
    assertTrue(screenshotHtml.contains("data-card-key=\"distribution-regressions\""));
    assertTrue(screenshotHtml.contains("data-card-key=\"bars-regressions\""));
    assertTrue(screenshotHtml.contains("data-card-key=\"distribution-critical\""));
    assertTrue(screenshotHtml.contains("data-card-key=\"bars-critical\""));

    OctaneReportDataMapper.ReportData reportData = new OctaneReportDataMapper().map(snapshot);
    assertEquals(2, reportData.sections().size());
    Map<String, Object> criteriaData = (Map<String, Object>) reportData.complete().get("criteria");
    assertEquals(snapshot.getCriteria(), criteriaData.get("expression"));
    assertEquals(3, ((List<Map<String, Object>>) criteriaData.get("rows")).size());

    String emailHtml =
        new OctaneEmailBodyRenderer()
            .render(
                "{{CRITERIA}}\n{{EXECUTION_DETAILS}}\n{{REPORT_SCREENSHOT}}",
                "Project",
                "Domain",
                snapshot,
                "https://jenkins.example/job/1/octane-gate-report/",
                "report-image",
                "DARK");
    assertTrue(emailHtml.contains("Criteria evaluation"));
    assertTrue(emailHtml.contains("regressions.executionRate"));
    assertTrue(emailHtml.contains("critical.executionRate"));
    assertTrue(emailHtml.contains("defects.criticalCount"));
    assertTrue(emailHtml.contains("cid:report-image"));
  }

  private GateRequest criticalOnlyRequest(String regressionSuiteRunId) {
    GateRequest request = new GateRequest("octane-prod", regressionSuiteRunId);
    request.setCriteria("regressions.executionRate == 100 AND critical.executionRate == 100");
    OctaneGateScope critical = new OctaneGateScope("critical");
    critical.setSuiteRunId("75295");
    request.setScopes(List.of(critical));
    return request;
  }

  private GateResult refreshCriticalOnlyResult(OctaneGateRunner runner, GateRequest request)
      throws Exception {
    return runner.refreshPassedResult(
        new FakeOctaneClient(List.of(new RunRecord("1", "critical", "passed"))),
        previousPassedResult(request),
        request,
        OctaneGateRunner.regressionSuiteRunIdsForCriteria(request),
        "1001",
        "2001",
        CriteriaExpression.parse(request.getCriteria()),
        request.createStatusClassifier(),
        taskListener(new ByteArrayOutputStream()),
        new OctaneGateReportPublisher() {});
  }

  private GateResult refreshTaskCriteriaScenario(String regressionSuiteRunIds, String criteria)
      throws Exception {
    GateRequest request = new GateRequest("octane-prod", regressionSuiteRunIds);
    request.setCriteria(criteria);
    OctaneGateScope critical = new OctaneGateScope("critical");
    critical.setSuiteRunId("1196,1200,1204");
    request.setScopes(List.of(critical));
    OctaneDefectGroup major = new OctaneDefectGroup("major");
    major.setTypes("Critical, Very High, High, Unspecified");
    OctaneDefectGroup minor = new OctaneDefectGroup("minor");
    minor.setTypes("Low, Medium");
    request.setDefectGroups(List.of(major, minor));

    OctaneGateRunner runner =
        new OctaneGateRunner(
            Clock.fixed(Instant.parse("2026-05-16T14:00:00Z"), ZoneOffset.UTC),
            new OctaneGateLogListener());
    return runner.refreshPassedResult(
        new NoDefectOctaneClient(),
        previousPassedResult(request),
        request,
        OctaneGateRunner.regressionSuiteRunIdsForCriteria(request),
        "1001",
        "2001",
        CriteriaExpression.parse(criteria),
        request.createStatusClassifier(),
        taskListener(new ByteArrayOutputStream()),
        new OctaneGateReportPublisher() {});
  }

  private void assertCriticalOnlyScenario(GateResult result, String expectedCriteria) {
    assertFalse(result.isRegressionEvaluationEnabled());
    assertTrue(result.isPassed());
    assertEquals(expectedCriteria, result.getCriteria());
    assertNoRegressionComparisons(result);
    assertTrue(result.getSuiteRuns().isEmpty());
  }

  private void assertNoRegressionComparisons(GateResult result) {
    assertEquals(5, result.getCriteriaEvaluation().getComparisons().size());
    assertTrue(
        result.getCriteriaEvaluation().getComparisons().stream()
            .noneMatch(
                comparison ->
                    comparison
                        .getMetricReference()
                        .toLowerCase(java.util.Locale.ROOT)
                        .startsWith("regression")));
  }

  private OctaneGateReportPublisher capturingPublisher(AtomicReference<GateResult> result) {
    return new OctaneGateReportPublisher() {
      @Override
      public void onPoll(GateResult pollResult, StatusClassifier classifier) {
        result.set(pollResult);
      }
    };
  }

  private TaskListener taskListener(ByteArrayOutputStream log) {
    return () -> new PrintStream(log, true, StandardCharsets.UTF_8);
  }

  private static class FakeOctaneClient extends OctaneClient {
    private final List<RunRecord> runs;

    FakeOctaneClient(List<RunRecord> runs) {
      super("https://octane.example", "client-id", hudson.util.Secret.fromString("client-secret"));
      this.runs = runs;
    }

    @Override
    public List<RunRecord> fetchSuiteChildRuns(
        String sharedSpaceId, String workspaceId, String suiteRunId) {
      return runs;
    }

    @Override
    public Map<String, List<RunRecord>> fetchSuiteChildRuns(
        String sharedSpaceId, String workspaceId, List<String> suiteRunIds) {
      return suiteRunIds.stream().collect(java.util.stream.Collectors.toMap(id -> id, id -> runs));
    }
  }

  private static class FailingOctaneClient extends OctaneClient {
    FailingOctaneClient() {
      super("https://octane.example", "client-id", hudson.util.Secret.fromString("client-secret"));
    }

    @Override
    public List<RunRecord> fetchSuiteChildRuns(
        String sharedSpaceId, String workspaceId, String suiteRunId) throws IOException {
      throw new IOException("Octane not ready");
    }

    @Override
    public Map<String, List<RunRecord>> fetchSuiteChildRuns(
        String sharedSpaceId, String workspaceId, List<String> suiteRunIds) throws IOException {
      throw new IOException("Octane not ready");
    }
  }

  private static class DefectAwareOctaneClient extends FakeOctaneClient {
    private final List<DefectRecord> defects =
        List.of(
            new DefectRecord("d1", "Critical defect", "Critical", "", "opened", "1", "", "", ""));

    DefectAwareOctaneClient() {
      super(List.of(new RunRecord("1", "one", "passed")));
    }

    @Override
    public List<DefectRecord> fetchLinkedDefects(
        String sharedSpaceId,
        String workspaceId,
        Map<String, List<RunRecord>> suiteRuns,
        String defectQuery,
        int maxDefects) {
      return defects;
    }

    @Override
    public List<DefectRecord> fetchDefectsByIds(
        String sharedSpaceId, String workspaceId, List<String> defectIds, int maxDefects) {
      return defects;
    }
  }

  private static class NoDefectOctaneClient extends FakeOctaneClient {
    NoDefectOctaneClient() {
      super(List.of(new RunRecord("1", "one", "passed")));
    }

    @Override
    public List<DefectRecord> fetchLinkedDefects(
        String sharedSpaceId,
        String workspaceId,
        Map<String, List<RunRecord>> suiteRuns,
        String defectQuery,
        int maxDefects) {
      return List.of();
    }

    @Override
    public List<DefectRecord> fetchDefectsByIds(
        String sharedSpaceId, String workspaceId, List<String> defectIds, int maxDefects) {
      return List.of();
    }
  }

  private RunRecord scopedRun(String runId, String testId) {
    return new RunRecord(
        runId, "Run " + runId, "failed", "Tester", testId, "Test " + testId, "", "");
  }

  private static class ScopeIsolationDefectClient extends FakeOctaneClient {
    private final List<DefectRecord> defects;

    ScopeIsolationDefectClient() {
      super(List.of());
      List<DefectRecord> values = new java.util.ArrayList<>();
      values.add(defect("regression-1", "regression-run", "opened"));
      values.add(defect("regression-2", "regression-run", "opened"));
      values.add(defect("regression-3", "regression-run", "opened"));
      values.add(defect("critical-1", "critical-run", "opened"));
      values.add(defect("critical-2", "critical-run", "closed"));
      for (int index = 1; index <= 5; index++) {
        values.add(defect("unrelated-" + index, "other-run-" + index, "opened"));
      }
      defects = List.copyOf(values);
    }

    @Override
    public List<DefectRecord> fetchLinkedDefects(
        String sharedSpaceId,
        String workspaceId,
        Map<String, List<RunRecord>> suiteRuns,
        String defectQuery,
        int maxDefects) {
      return defects;
    }

    @Override
    public List<DefectRecord> fetchDefectsByIds(
        String sharedSpaceId, String workspaceId, List<String> defectIds, int maxDefects) {
      return defects;
    }

    private static DefectRecord defect(String id, String runId, String phase) {
      return new DefectRecord(id, "Defect " + id, "High", "", phase, runId, "", "", "");
    }
  }

  private static List<RunRecord> statusRuns(int passed, int inProgress) {
    List<RunRecord> runs = new java.util.ArrayList<>();
    for (int index = 0; index < passed; index++) {
      runs.add(new RunRecord("passed-" + index, "Passed " + index, "passed"));
    }
    for (int index = 0; index < inProgress; index++) {
      runs.add(
          new RunRecord("active-" + index, "Active " + index, "list_node.run_status.in_progress"));
    }
    return List.copyOf(runs);
  }

  private static class RepopulationDefectClient extends FakeOctaneClient {
    private final String repopulatedRunId;
    private final AtomicInteger linkedDefectPolls = new AtomicInteger();
    private boolean repopulatedRunObserved;

    RepopulationDefectClient(String repopulatedRunId) {
      super(List.of());
      this.repopulatedRunId = repopulatedRunId;
    }

    @Override
    public List<DefectRecord> fetchLinkedDefects(
        String sharedSpaceId,
        String workspaceId,
        Map<String, List<RunRecord>> suiteRuns,
        String defectQuery,
        int maxDefects) {
      linkedDefectPolls.incrementAndGet();
      boolean currentPollContainsRepopulatedRun =
          suiteRuns.values().stream()
              .flatMap(runs -> runs.stream())
              .anyMatch(run -> repopulatedRunId.equals(run.getId()));
      repopulatedRunObserved |= currentPollContainsRepopulatedRun;
      if (!currentPollContainsRepopulatedRun) {
        return List.of();
      }
      return repopulatedDefects();
    }

    private List<DefectRecord> repopulatedDefects() {
      return List.of(
          new DefectRecord(
              "open-defect",
              "Open defect",
              "Critical",
              "Highest",
              "opened",
              repopulatedRunId,
              "repopulated-test",
              "project-1",
              "Project"),
          new DefectRecord(
              "closed-defect",
              "Closed defect",
              "High",
              "Highest",
              "closed",
              repopulatedRunId,
              "repopulated-test",
              "project-1",
              "Project"));
    }

    @Override
    public List<DefectRecord> fetchDefectsByIds(
        String sharedSpaceId, String workspaceId, List<String> defectIds, int maxDefects) {
      return repopulatedRunObserved ? repopulatedDefects() : List.of();
    }

    int getLinkedDefectPolls() {
      return linkedDefectPolls.get();
    }

    boolean isRepopulatedRunObserved() {
      return repopulatedRunObserved;
    }
  }
}
