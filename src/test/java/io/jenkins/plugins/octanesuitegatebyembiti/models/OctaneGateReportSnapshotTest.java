package io.jenkins.plugins.octanesuitegatebyembiti.models;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.jenkins.plugins.octanesuitegatebyembiti.entities.DefectRecord;
import io.jenkins.plugins.octanesuitegatebyembiti.entities.RunRecord;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.Test;

public class OctaneGateReportSnapshotTest {
  private final StatusClassifier classifier =
      new StatusClassifier(
          StatusClassifier.DEFAULT_PASSED_STATUSES,
          StatusClassifier.DEFAULT_FAILED_STATUSES,
          StatusClassifier.DEFAULT_NEUTRAL_STATUSES,
          StatusClassifier.DEFAULT_RUNNING_STATUSES);

  @Test
  public void formatsExistingTerminalTimerDurationForNotifications() {
    OctaneGateReportSnapshot snapshot =
        OctaneGateReportSnapshot.fromResult(
            OctaneGateReportState.PASSED,
            "Passed",
            result(),
            classifier,
            30,
            300,
            0,
            "2026-05-14T23:58:00Z");

    assertEquals(120_000L, snapshot.getTestingElapsedMillis());
    assertEquals(2L, snapshot.getTestingTimeSpentMinutes());
    assertEquals("2 minutes", snapshot.getTestingTimeSpentText());
  }

  @Test
  public void preservesSubMinutePrecisionForNotifications() {
    OctaneGateReportSnapshot snapshot =
        OctaneGateReportSnapshot.fromResult(
            OctaneGateReportState.PASSED,
            "Passed",
            result(),
            classifier,
            30,
            300,
            0,
            "2026-05-14T23:59:20Z");

    assertEquals(40_000L, snapshot.getTestingElapsedMillis());
    assertEquals(40L, snapshot.getTestingTimeSpentSeconds());
    assertEquals(0L, snapshot.getTestingTimeSpentMinutes());
    assertEquals("40 seconds", snapshot.getTestingTimeSpentText());
  }

  @Test
  public void formatsMinutesAndRemainingSecondsForNotifications() {
    OctaneGateReportSnapshot snapshot =
        OctaneGateReportSnapshot.fromResult(
            OctaneGateReportState.PASSED,
            "Passed",
            result(),
            classifier,
            30,
            300,
            0,
            "2026-05-14T23:58:30Z");

    assertEquals(90_000L, snapshot.getTestingElapsedMillis());
    assertEquals(90L, snapshot.getTestingTimeSpentSeconds());
    assertEquals(1L, snapshot.getTestingTimeSpentMinutes());
    assertEquals("1 minute, 30 seconds", snapshot.getTestingTimeSpentText());
  }

  @Test
  public void aggregatesRegressionPieTotalsAndSuiteRunBars() {
    OctaneGateReportSnapshot snapshot =
        OctaneGateReportSnapshot.fromResult(
            OctaneGateReportState.POLLING, "Polling", result(), classifier, 30);

    OctaneGateReportSection regressions = snapshot.getSections().get(0);
    assertEquals("Regressions suite runs", regressions.getName());
    assertEquals("regressions", regressions.getSource());
    assertEquals(5, regressions.getMetrics().getTotal());
    assertEquals(2, count(regressions, OctaneGateStatusBucket.PASSED));
    assertEquals(1, count(regressions, OctaneGateStatusBucket.FAILED));
    assertEquals(0, count(regressions, OctaneGateStatusBucket.BLOCKED));
    assertEquals(1, count(regressions, OctaneGateStatusBucket.SKIPPED));
    assertEquals(1, count(regressions, OctaneGateStatusBucket.RUNNING));
    assertEquals(2, regressions.getSuiteRuns().size());
    assertEquals("Ben Tester", regressions.getSuiteRuns().get(0).getDisplayName());
    assertEquals(2, regressions.getSuiteRunCount());
    assertEquals(2, regressions.getSuiteRuns().get(0).getTotal());
    assertEquals("height: 66.67%;", regressions.getSuiteRuns().get(0).getBarHeightStyle());
    assertEquals("height: 100.00%;", regressions.getSuiteRuns().get(1).getBarHeightStyle());
    assertEquals(83.333, snapshot.getExecutionProgress(), 0.001);
    assertEquals("83%", snapshot.getExecutionProgressText());
    assertEquals("83.33%", snapshot.getExecutionProgressTwoDecimalText());
    assertEquals(83.333, snapshot.getCompletionProgress(), 0.001);
    assertEquals("83%", snapshot.getCompletionProgressText());
    assertEquals("83.33%", snapshot.getCompletionProgressTwoDecimalText());
    assertEquals(5, snapshot.getPassRateTotal());
    assertEquals(3, snapshot.getPassRatePassed());
    assertEquals(60.0, snapshot.getPassRateProgress(), 0.001);
    assertEquals("60%", snapshot.getPassRateProgressText());
    assertEquals("60.00%", snapshot.getPassRateProgressTwoDecimalText());
    assertEquals("0.00%", snapshot.getAutomationProgressTwoDecimalText());
    assertEquals("All Testcase Pass Rate (3 / 5)", snapshot.getPassRateLabel());
    assertEquals("In Progress", snapshot.getJobStateLabel());
    assertEquals("2026/05/15 03:00:00", snapshot.getUpdatedAtDateTimeText());
    assertFalse(regressions.getPieSlices().isEmpty());
  }

  @Test
  public void calculatesPassRateFromPassedFailedAndBlockedTestsOnly() {
    List<RunRecord> runs =
        List.of(
            new RunRecord("1", "passed one", "passed", "Ada Tester"),
            new RunRecord("2", "passed two", "passed", "Ada Tester"),
            new RunRecord("3", "failed", "failed", "Ada Tester"),
            new RunRecord("4", "blocked", "blocked", "Ada Tester"),
            new RunRecord("5", "skipped", "skipped", "Ada Tester"),
            new RunRecord("6", "planned", "planned", "Ada Tester"));
    GateResult result =
        new GateResult(
            "4501",
            "regressions.passRate == 50",
            true,
            false,
            GateMetrics.fromRuns(runs, classifier),
            runs,
            Map.of("4501", runs),
            Map.of(),
            Instant.parse("2026-05-15T00:00:00Z"));

    OctaneGateReportSnapshot snapshot =
        OctaneGateReportSnapshot.fromResult(
            OctaneGateReportState.POLLING, "Polling", result, classifier, 30);

    assertEquals(6, snapshot.getProjectTestTotal());
    assertEquals(4, snapshot.getExecutedTestCount());
    assertEquals(66.667, snapshot.getExecutionProgress(), 0.001);
    assertEquals(5, snapshot.getResolvedTestCount());
    assertEquals(83.333, snapshot.getCompletionProgress(), 0.001);
    assertEquals(4, snapshot.getPassRateTotal());
    assertEquals(2, snapshot.getPassRatePassed());
    assertEquals(50.0, snapshot.getPassRateProgress(), 0.001);
    assertEquals("All Testcase Pass Rate (2 / 4)", snapshot.getPassRateLabel());
    OctaneTesterPerformance tester = snapshot.getTesterPerformances().get(0);
    assertEquals(4, tester.getExecuted());
    assertEquals(2, tester.getPassed());
    assertEquals(1, tester.getFailed());
    assertEquals(1, tester.getBlocked());
    assertEquals(1, tester.getNoRun());
    assertEquals(50.0, tester.getPassRate(), 0.001);
  }

  @Test
  public void groupsSuiteRunBarsByRunByName() {
    Map<String, List<RunRecord>> suiteRuns = new LinkedHashMap<>();
    suiteRuns.put(
        "4501",
        List.of(
            new RunRecord("1", "one", "passed", "Alex Engineer"),
            new RunRecord("2", "two", "failed", "Alex Engineer")));
    suiteRuns.put("4502", List.of(new RunRecord("3", "three", "passed", "Alex Engineer")));
    GateResult result =
        new GateResult(
            "4501,4502",
            "100% execution",
            false,
            true,
            new GateMetrics(3, 3, 2, 1, 0, 0),
            List.of(
                new RunRecord("1", "one", "passed", "Alex Engineer"),
                new RunRecord("2", "two", "failed", "Alex Engineer"),
                new RunRecord("3", "three", "passed", "Alex Engineer")),
            suiteRuns,
            Map.of(),
            Instant.parse("2026-05-15T00:00:00Z"));

    OctaneGateReportSnapshot snapshot =
        OctaneGateReportSnapshot.fromResult(
            OctaneGateReportState.POLLING, "Polling", result, classifier, 30);

    OctaneGateReportSection regressions = snapshot.getSections().get(0);
    assertEquals(1, regressions.getSuiteRuns().size());
    assertEquals(2, regressions.getSuiteRunCount());
    assertEquals("Alex Engineer", regressions.getSuiteRuns().get(0).getDisplayName());
    assertEquals(List.of("4501", "4502"), regressions.getSuiteRuns().get(0).getSuiteRunIds());
    assertEquals(3, regressions.getSuiteRuns().get(0).getTotal());
    assertTrue(regressions.getSuiteRuns().get(0).getTitle().contains("4501, 4502"));
  }

  @Test
  public void sortsSuiteRunBarsByTotalThenSuiteRunId() {
    Map<String, List<RunRecord>> suiteRuns = new LinkedHashMap<>();
    suiteRuns.put(
        "9002",
        List.of(
            new RunRecord("1", "one", "passed", "Beta Tester"),
            new RunRecord("2", "two", "failed", "Beta Tester")));
    suiteRuns.put(
        "9001",
        List.of(
            new RunRecord("3", "three", "passed", "Alpha Tester"),
            new RunRecord("4", "four", "passed", "Alpha Tester")));
    suiteRuns.put("9003", List.of(new RunRecord("5", "five", "passed", "Gamma Tester")));
    List<RunRecord> runs =
        suiteRuns.values().stream()
            .flatMap(
                suiteRunRecords ->
                    suiteRunRecords == null ? Stream.<RunRecord>empty() : suiteRunRecords.stream())
            .toList();
    GateResult result =
        new GateResult(
            "9002,9001,9003",
            "100% execution",
            false,
            true,
            new GateMetrics(5, 5, 4, 1, 0, 0),
            runs,
            suiteRuns,
            Map.of(),
            Instant.parse("2026-05-15T00:00:00Z"));

    OctaneGateReportSnapshot snapshot =
        OctaneGateReportSnapshot.fromResult(
            OctaneGateReportState.POLLING, "Polling", result, classifier, 30);

    List<OctaneGateSuiteRunChart> charts = snapshot.getSections().get(0).getSuiteRuns();
    assertEquals(
        List.of("Gamma Tester", "Alpha Tester", "Beta Tester"),
        charts.stream().map(chart -> chart.getDisplayName()).toList());
    assertEquals(List.of(1, 2, 2), charts.stream().map(chart -> chart.getTotal()).toList());
  }

  @Test
  public void parentOwnerProducesOneBarWhileChildRunnersDriveAutomationUsage() {
    List<RunRecord> runs =
        List.of(
            new RunRecord(
                "1", "automated", "passed", "Jenkins Agent", "Suite Owner", "", "", "", ""),
            new RunRecord(
                "2", "manual", "failed", "Default Manual Runner", "Suite Owner", "", "", "", ""));
    GateResult result =
        new GateResult(
            "55",
            "100% execution",
            false,
            true,
            new GateMetrics(2, 2, 1, 1, 0, 0),
            runs,
            Map.of("55", runs),
            Map.of(),
            Instant.parse("2026-05-15T00:00:00Z"));

    OctaneGateReportSnapshot snapshot =
        OctaneGateReportSnapshot.fromResult(
            OctaneGateReportState.POLLING, "Polling", result, classifier, 30);

    List<OctaneGateSuiteRunChart> charts = snapshot.getSections().get(0).getSuiteRuns();
    assertEquals(1, charts.size());
    assertEquals("Suite Owner", charts.get(0).getDisplayName());
    assertEquals(2, charts.get(0).getTotal());
    assertEquals(1, snapshot.getTestMetrics().getAutomatedTestCount());
    assertEquals(1, snapshot.getTestMetrics().getManualTestCount());
    assertEquals(1, snapshot.getTesterPerformances().size());
    assertEquals(2, snapshot.getTesterPerformances().get(0).getAutomationTestTotal());
    assertEquals(50, snapshot.getTesterPerformances().get(0).getAutomationPercentage());
  }

  @Test
  public void assignedAndUnassignedSuiteRunsRenderAsSeparateTesterBars() {
    List<RunRecord> runs =
        List.of(
            new RunRecord("1", "assigned", "passed", "Jenkins Agent", "Ada Owner", "", "", "", ""),
            new RunRecord(
                "2",
                "unassigned",
                "failed",
                "Default Manual Runner",
                "Unassigned (55)",
                "",
                "",
                "",
                ""));
    GateResult result =
        new GateResult(
            "55",
            "100% execution",
            false,
            true,
            new GateMetrics(2, 2, 1, 1, 0, 0),
            runs,
            Map.of("55", runs),
            Map.of(),
            Instant.parse("2026-05-15T00:00:00Z"));

    OctaneGateReportSnapshot snapshot =
        OctaneGateReportSnapshot.fromResult(
            OctaneGateReportState.POLLING, "Polling", result, classifier, 30);

    List<OctaneGateSuiteRunChart> charts = snapshot.getSections().get(0).getSuiteRuns();
    assertEquals(2, charts.size());
    assertEquals(
        List.of("Ada Owner", "Unassigned"),
        charts.stream().map(chart -> chart.getDisplayName()).toList());
    assertEquals(List.of(1, 1), charts.stream().map(chart -> chart.getTotal()).toList());
  }

  @Test
  public void collapsesNumberedUnassignedVariantsAcrossChartsAndTesterDetails() {
    Map<String, List<RunRecord>> suiteRuns = new LinkedHashMap<>();
    List<RunRecord> first = runsForOwner("first", "Unassigned (10221)", 5);
    List<RunRecord> second = runsForOwner("second", " unassigned (99401) ", 10);
    suiteRuns.put("10221", first);
    suiteRuns.put("99401", second);
    List<RunRecord> allRuns = Stream.concat(first.stream(), second.stream()).toList();
    GateResult result =
        new GateResult(
            "10221,99401",
            "regressions.executionRate == 100",
            false,
            true,
            GateMetrics.fromRuns(allRuns, classifier),
            allRuns,
            suiteRuns,
            Map.of(),
            Instant.parse("2026-05-15T00:00:00Z"));

    OctaneGateReportSnapshot snapshot =
        OctaneGateReportSnapshot.fromResult(
            OctaneGateReportState.PASSED, "Passed", result, classifier, 30);

    assertEquals(1, snapshot.getSections().get(0).getSuiteRuns().size());
    assertEquals(
        "Unassigned", snapshot.getSections().get(0).getSuiteRuns().get(0).getDisplayName());
    assertEquals(15, snapshot.getSections().get(0).getSuiteRuns().get(0).getTotal());
    assertEquals(1, snapshot.getTesterPerformances().size());
    assertEquals("Unassigned", snapshot.getTesterPerformances().get(0).getEmail());
    assertEquals(15, snapshot.getTesterPerformances().get(0).getTotal());
  }

  @Test
  public void groupsThreeHundredFiftyTestsByStatusWithoutChangingNumericTotals() {
    List<RunRecord> runs = new java.util.ArrayList<>();
    List<String> statuses = List.of("passed", "failed", "blocked", "skipped", "planned");
    for (int index = 0; index < 350; index++) {
      runs.add(
          new RunRecord(
              "run-" + index,
              "Run " + index,
              statuses.get(index % statuses.size()),
              "Runner " + index,
              "Tester " + index,
              "",
              "",
              "",
              ""));
    }
    GateResult result =
        new GateResult(
            "dense-suite",
            "regressions.executionRate >= 0",
            false,
            false,
            GateMetrics.fromRuns(runs, classifier),
            runs,
            Map.of("dense-suite", runs),
            Map.of(),
            Instant.parse("2026-05-15T00:00:00Z"));

    OctaneGateReportSnapshot snapshot =
        OctaneGateReportSnapshot.fromResult(
            OctaneGateReportState.POLLING,
            "Polling",
            result,
            classifier,
            30,
            7_200,
            0,
            "2026-05-15T00:00:00Z",
            250);
    OctaneGateReportSection section = snapshot.getSections().get(0);

    assertTrue(section.isStatusGrouped());
    assertFalse(section.isTooltipsEnabled());
    assertEquals("Status", section.getXAxis());
    assertEquals("Count", section.getYAxis());
    assertEquals(5, section.getSuiteRuns().size());
    assertEquals(350, section.getSuiteRuns().stream().mapToInt(chart -> chart.getTotal()).sum());
    assertEquals(350, section.getMetrics().getTotal());
    assertEquals(350, snapshot.getProjectTestTotal());
  }

  @Test
  public void donutSlicesUseBoundedGeometryWithoutExternalLabelCoordinates() {
    Map<String, List<RunRecord>> suiteRuns = new LinkedHashMap<>();
    suiteRuns.put(
        "edge-labels",
        List.of(new RunRecord("1", "one", "passed"), new RunRecord("2", "two", "failed")));
    GateResult result =
        new GateResult(
            "edge-labels",
            "100% execution",
            false,
            true,
            new GateMetrics(2, 2, 1, 1, 0, 0),
            suiteRuns.get("edge-labels"),
            suiteRuns,
            Map.of(),
            Instant.parse("2026-05-15T00:00:00Z"));
    OctaneGateReportSnapshot snapshot =
        OctaneGateReportSnapshot.fromResult(
            OctaneGateReportState.POLLING, "Polling", result, classifier, 30);

    OctaneGateReportSection regressions = snapshot.getSections().get(0);
    assertEquals(2, regressions.getPieSlices().size());
    for (OctaneGatePieSlice slice : regressions.getPieSlices()) {
      assertFalse(slice.isFullCircle());
      assertTrue(slice.getPath().contains("46.000 46.000"));
    }
  }

  @Test
  public void suiteRunAxisLabelsUseLowercaseEmailUserName() {
    OctaneGateSuiteRunChart emailChart =
        OctaneGateSuiteRunChart.fromRunByGroup(
            "Ada.Tester@Example.COM",
            List.of("4501"),
            List.of(new RunRecord("1", "one", "passed", "Ada.Tester@Example.COM")),
            classifier);
    OctaneGateSuiteRunChart nameChart =
        OctaneGateSuiteRunChart.fromRunByGroup(
            "Ada Tester",
            List.of("4502"),
            List.of(new RunRecord("2", "two", "passed", "Ada Tester")),
            classifier);

    assertEquals("ada.tester", emailChart.getAxisLabel());
    assertEquals("ada tester", nameChart.getAxisLabel());
  }

  @Test
  public void createsSeparateCriticalScopeSectionForOverlappingSuiteRuns() {
    OctaneGateReportSnapshot snapshot =
        OctaneGateReportSnapshot.fromResult(
            OctaneGateReportState.POLLING, "Polling", result(), classifier, 30);

    OctaneGateReportSection critical = snapshot.getSections().get(1);
    assertEquals("Critical suite runs", critical.getName());
    assertEquals(List.of("4502", "4503"), critical.getSuiteRunIds());
    assertEquals(3, critical.getMetrics().getTotal());
    assertEquals(2, count(critical, OctaneGateStatusBucket.PASSED));
    assertEquals(1, count(critical, OctaneGateStatusBucket.FAILED));
  }

  @Test
  public void omitsInactiveScopeFromReportSections() {
    GateResult activeRegressionOnly =
        new GateResult(
            "4501",
            "regressions.executionRate == 100",
            true,
            true,
            new GateMetrics(1, 1, 1, 0, 0, 0),
            List.of(new RunRecord("1", "one", "passed")),
            Map.of("4501", List.of(new RunRecord("1", "one", "passed"))),
            Map.of("critical", GateScopeResult.inactiveSuiteRunScope("critical")),
            Instant.parse("2026-05-15T00:00:00Z"));

    OctaneGateReportSnapshot snapshot =
        OctaneGateReportSnapshot.fromResult(
            OctaneGateReportState.POLLING, "Polling", activeRegressionOnly, classifier, 30);

    assertEquals(1, snapshot.getSections().size());
    assertEquals("regressions", snapshot.getSections().get(0).getSource());
  }

  @Test
  public void formatsLastUpdatedTimeInEastAfricaTimeWithoutMillis() {
    OctaneGateReportSnapshot snapshot =
        OctaneGateReportSnapshot.fromResult(
            OctaneGateReportState.POLLING, "Polling", result(), classifier, 30);

    assertEquals("2026-05-15T00:00:00Z", snapshot.getUpdatedAt());
    assertEquals("03:00:00", snapshot.getUpdatedAtText());
  }

  @Test
  public void usesRequiredStatusColors() {
    assertEquals("var(--octane-status-passed)", OctaneGateStatusBucket.PASSED.getColor());
    assertEquals("var(--octane-status-failed)", OctaneGateStatusBucket.FAILED.getColor());
    assertEquals("var(--octane-status-blocked)", OctaneGateStatusBucket.BLOCKED.getColor());
    assertEquals("var(--octane-status-skipped)", OctaneGateStatusBucket.SKIPPED.getColor());
    assertEquals("var(--octane-status-no-run)", OctaneGateStatusBucket.RUNNING.getColor());
    assertEquals("#30D158", OctaneGateStatusBucket.PASSED.getTooltipColor());
    assertEquals("#FF453A", OctaneGateStatusBucket.FAILED.getTooltipColor());
    assertEquals("#FF9F0A", OctaneGateStatusBucket.BLOCKED.getTooltipColor());
    assertEquals("#BF5AF2", OctaneGateStatusBucket.SKIPPED.getTooltipColor());
    assertEquals("#8E8E93", OctaneGateStatusBucket.RUNNING.getTooltipColor());
  }

  @Test
  public void suiteRunDominantStatusUsesLargestStatusCount() {
    OctaneGateSuiteRunChart chart =
        OctaneGateSuiteRunChart.fromRunByGroup(
            "Ada Tester",
            List.of("4501"),
            List.of(
                new RunRecord("1", "one", "passed", "Ada Tester"),
                new RunRecord("2", "two", "failed", "Ada Tester"),
                new RunRecord("3", "three", "failed", "Ada Tester")),
            classifier);

    assertEquals("Failed", chart.getDominantStatusLabel());
    assertEquals("#FF453A", chart.getDominantStatusColor());
    assertEquals(2, chart.getDominantStatusCount());
  }

  @Test
  public void suiteRunDominantStatusTieBreaksTowardRisk() {
    assertDominantStatusForTie("failed", "blocked", "Failed", "#FF453A");
    assertDominantStatusForTie("blocked", "planned", "Blocked", "#FF9F0A");
    assertDominantStatusForTie("planned", "skipped", "Skipped", "#BF5AF2");
    assertDominantStatusForTie("skipped", "passed", "Passed", "#30D158");
  }

  @Test
  public void emptySuiteRunHasNoDominantStatus() {
    OctaneGateSuiteRunChart chart =
        OctaneGateSuiteRunChart.fromRunByGroup(
            "Empty Tester", List.of("empty"), List.of(), classifier);

    assertEquals("", chart.getDominantStatusLabel());
    assertEquals("", chart.getDominantStatusColor());
    assertEquals(0, chart.getDominantStatusCount());
  }

  @Test
  public void reportsBlockedStatusesSeparatelyFromFailedChartBucket() {
    Map<String, List<RunRecord>> suiteRuns = new LinkedHashMap<>();
    suiteRuns.put(
        "blocked-suite",
        List.of(
            new RunRecord("1", "one", "blocked"),
            new RunRecord("2", "two", "failed"),
            new RunRecord("3", "three", "passed")));
    GateResult result =
        new GateResult(
            "blocked-suite",
            "100% execution",
            false,
            true,
            new GateMetrics(3, 3, 1, 2, 0, 0),
            suiteRuns.get("blocked-suite"),
            suiteRuns,
            Map.of(),
            Instant.parse("2026-05-15T00:00:00Z"));

    OctaneGateReportSnapshot snapshot =
        OctaneGateReportSnapshot.fromResult(
            OctaneGateReportState.POLLING, "Polling", result, classifier, 30);

    OctaneGateReportSection regressions = snapshot.getSections().get(0);
    assertEquals(1, count(regressions, OctaneGateStatusBucket.PASSED));
    assertEquals(1, count(regressions, OctaneGateStatusBucket.FAILED));
    assertEquals(1, count(regressions, OctaneGateStatusBucket.BLOCKED));
    assertEquals(0, count(regressions, OctaneGateStatusBucket.SKIPPED));
    assertEquals(0, count(regressions, OctaneGateStatusBucket.RUNNING));
    assertEquals(1, suiteRunStatusCount(regressions, OctaneGateStatusBucket.BLOCKED));
  }

  @Test
  public void storesTimerConfigurationForReportCountdowns() {
    GateRequest request = new GateRequest("octane-prod", "4501");
    request.setPollIntervalSeconds(17);
    request.setTimeoutMinutes(45);
    request.setTimeoutMinutesExtended(12);
    request.setBasePassrateFigure(80);
    request.setBaseExecutionFigure(90);

    OctaneGateReportSnapshot snapshot =
        OctaneGateReportSnapshot.waiting(request, 17, "2026-05-15T00:00:00Z");

    assertEquals(17, snapshot.getRefreshSeconds());
    assertEquals("00:17", snapshot.getRefreshCountdownText());
    assertEquals("Started", snapshot.getJobStateLabel());
    assertEquals(2700, snapshot.getTimeoutSeconds());
    assertEquals(720, snapshot.getTimeoutExtendedSeconds());
    assertEquals("2026-05-15T00:00:00Z", snapshot.getStartedAt());
    assertEquals(80, snapshot.getBasePassrateFigure());
    assertEquals(90, snapshot.getBaseExecutionFigure());
  }

  @Test
  public void withMethodsPreserveUnchangedSnapshotFieldsAcrossCopies() {
    OctaneGateReportSnapshot original =
        OctaneGateReportSnapshot.fromResult(
            OctaneGateReportState.POLLING,
            "Polling",
            result(),
            classifier,
            17,
            2700,
            720,
            "2026-05-14T23:45:00Z");
    OctaneTestMetrics testMetrics = original.getTestMetrics().withAutomatedTestingTarget(73);
    OctaneDefectTrend defectTrend = OctaneDefectTrend.start("2026-05-14T23:45:00Z", 3_420_000L);
    OctaneRiskHeatMap riskHeatMap = OctaneRiskHeatMap.waiting();
    OctaneTestManagementAnalytics testManagement =
        OctaneTestManagementAnalytics.empty("2026-05-14T23:45:00Z", 3_420_000L);
    List<OctaneDefinedScope> definedScope =
        List.of(new OctaneDefinedScope("Payments", "Quality Team"));
    Map<String, String> suiteAttributions = Map.of("4501", "owner@example.com");

    OctaneGateReportSnapshot copied =
        original
            .withTestMetrics(testMetrics)
            .withDefectTrend(defectTrend)
            .withRiskHeatMap(riskHeatMap)
            .withTestManagement(testManagement)
            .withTesterThresholds(71, 88)
            .withState(OctaneGateReportState.FINALIZING, "Finalizing", "2026-05-15T00:01:00Z")
            .withDefinedScope(definedScope)
            .withSuiteAttributions(suiteAttributions)
            .withGraphTitles("Release Regression", "Release Critical");

    assertEquals(OctaneGateReportState.FINALIZING, copied.getState());
    assertEquals("Finalizing", copied.getMessage());
    assertEquals("2026-05-15T00:01:00Z", copied.getUpdatedAt());
    assertSame(testMetrics, copied.getTestMetrics());
    assertSame(defectTrend, copied.getDefectTrend());
    assertSame(riskHeatMap, copied.getRiskHeatMap());
    assertEquals(88, copied.getTestManagement().getExecutionTarget());
    assertEquals(71, copied.getBasePassrateFigure());
    assertEquals(88, copied.getBaseExecutionFigure());
    assertEquals(suiteAttributions, copied.getSuiteAttributions());
    assertEquals("Payments", copied.getDefinedScope().get(0).getProject());
    assertEquals(
        "Release Regression Status Distribution",
        copied.getSections().get(0).getStatusDistributionTitle());

    assertEquals(original.getCriteria(), copied.getCriteria());
    assertEquals(original.getSuiteRunId(), copied.getSuiteRunId());
    assertEquals(original.getRefreshSeconds(), copied.getRefreshSeconds());
    assertEquals(original.getTimeoutSeconds(), copied.getTimeoutSeconds());
    assertEquals(original.getTimeoutExtendedSeconds(), copied.getTimeoutExtendedSeconds());
    assertEquals(original.getStartedAt(), copied.getStartedAt());
    assertSame(original.getDefectMetrics(), copied.getDefectMetrics());
    assertSame(original.getCriteriaEvaluation(), copied.getCriteriaEvaluation());
    assertEquals(original.getTesterPerformances(), copied.getTesterPerformances());
  }

  @Test
  public void calculatesTesterDetailsAcrossOverlappingSuiteRunScopes() {
    OctaneGateReportSnapshot snapshot =
        OctaneGateReportSnapshot.fromResult(
                OctaneGateReportState.POLLING, "Polling", result(), classifier, 30)
            .withTesterThresholds(70, 90);

    List<OctaneTesterPerformance> passRateDetails = snapshot.getTesterPassRateDetails();
    List<OctaneTesterPerformance> executionDetails = snapshot.getTesterExecutionDetails();

    assertEquals(List.of("Ada Tester", "Ben Tester"), emails(passRateDetails));
    assertEquals(List.of("Ada Tester"), emails(executionDetails));
    OctaneTesterPerformance ada =
        snapshot.getTesterPerformances().stream()
            .filter(tester -> "Ada Tester".equals(tester.getEmail()))
            .findFirst()
            .orElseThrow();
    OctaneTesterPerformance ben =
        snapshot.getTesterPerformances().stream()
            .filter(tester -> "Ben Tester".equals(tester.getEmail()))
            .findFirst()
            .orElseThrow();
    assertEquals(3, ada.getTotal());
    assertEquals(2, ada.getExecuted());
    assertEquals(1, ada.getPassed());
    assertEquals(1, ada.getFailed());
    assertEquals(0, ada.getBlocked());
    assertEquals(1, ada.getNoRun());
    assertEquals(66.667, ada.getExecutionRate(), 0.001);
    assertEquals(50.0, ada.getPassRate(), 0.001);
    assertEquals("66.7%", ada.getExecutionRateText());
    assertEquals(2, ben.getTotal());
    assertEquals(2, ben.getExecuted());
    assertEquals(1, ben.getPassed());
    assertEquals(1, ben.getFailed());
    assertEquals(0, ben.getBlocked());
    assertEquals(0, ben.getNoRun());
  }

  @Test
  public void excludesUnstartedTestersFromPassRateDetails() {
    List<RunRecord> runs =
        List.of(
            new RunRecord("10", "planned", "planned", "New Tester"),
            new RunRecord("11", "failed", "failed", "Started Tester"));
    GateResult result =
        new GateResult(
            "4501",
            "regressions.passRate >= 90",
            false,
            false,
            GateMetrics.fromRuns(runs, classifier),
            runs,
            Map.of("4501", runs),
            Map.of(),
            Instant.parse("2026-05-15T00:00:00Z"));

    OctaneGateReportSnapshot snapshot =
        OctaneGateReportSnapshot.fromResult(
                OctaneGateReportState.POLLING, "Polling", result, classifier, 30)
            .withTesterThresholds(90, 90);

    assertEquals(List.of("Started Tester"), emails(snapshot.getTesterPassRateDetails()));
    assertEquals(List.of("New Tester"), emails(snapshot.getTesterExecutionDetails()));
    assertEquals(1, snapshot.getTesterPassRateDetailsCount());
    assertEquals(1, snapshot.getTesterExecutionDetailsCount());
  }

  @Test
  public void handlesZeroRunResults() {
    GateResult result =
        new GateResult(
            "empty",
            "100% pass",
            false,
            false,
            new GateMetrics(0, 0, 0, 0, 0, 0),
            List.of(),
            Map.of("empty", List.of()),
            Map.of(),
            Instant.parse("2026-05-15T00:00:00Z"));

    OctaneGateReportSnapshot snapshot =
        OctaneGateReportSnapshot.fromResult(
            OctaneGateReportState.POLLING, "Polling", result, classifier, 30);

    OctaneGateReportSection regressions = snapshot.getSections().get(0);
    assertTrue(regressions.isEmpty());
    assertEquals(0.0, snapshot.getExecutionProgress(), 0.001);
    assertEquals("0%", snapshot.getExecutionProgressText());
    assertEquals(0.0, snapshot.getCompletionProgress(), 0.001);
    assertEquals("0%", snapshot.getCompletionProgressText());
    assertEquals(0, snapshot.getPassRateTotal());
    assertEquals(0, snapshot.getPassRatePassed());
    assertEquals(0.0, snapshot.getPassRateProgress(), 0.001);
    assertEquals("0%", snapshot.getPassRateProgressText());
    assertEquals("All Testcase Pass Rate (0 / 0)", snapshot.getPassRateLabel());
    assertTrue(regressions.getPieSlices().isEmpty());
    assertTrue(snapshot.getReportSections().isEmpty());
    assertFalse(snapshot.hasReportSections());
    assertEquals(1, regressions.getSuiteRuns().size());
    assertEquals(0, regressions.getSuiteRuns().get(0).getTotal());
    assertEquals("height: 0%;", regressions.getSuiteRuns().get(0).getBarHeightStyle());
  }

  @Test
  public void criticalOnlySnapshotDoesNotCreateARegressionSection() {
    List<RunRecord> criticalRuns = List.of(new RunRecord("1", "critical", "passed"));
    GateScopeResult critical =
        new GateScopeResult(
            "critical",
            "",
            List.of(),
            "75295",
            List.of("75295"),
            new GateMetrics(1, 1, 1, 0, 0, 0),
            criticalRuns,
            Map.of("75295", criticalRuns));
    GateResult result =
        new GateResult(
            "",
            "critical.passRate == 100",
            true,
            true,
            new GateMetrics(0, 0, 0, 0, 0, 0),
            List.of(),
            Map.of(),
            Map.of("critical", critical),
            Instant.parse("2026-05-15T00:00:00Z"));

    OctaneGateReportSnapshot snapshot =
        OctaneGateReportSnapshot.fromResult(
            OctaneGateReportState.PASSED, "Passed", result, classifier, 30);

    assertTrue(snapshot.isCriticalOnlyReport());
    assertEquals("Passed", snapshot.getJobStateLabel());
    assertEquals(1, snapshot.getSections().size());
    assertEquals("critical", snapshot.getSections().get(0).getSource());
    assertEquals(1, snapshot.getProjectTestTotal());
  }

  @Test
  public void calculatesTestMetricsFromCurrentSnapshotAndPreviousCycle() {
    OctaneGateReportSnapshot previous =
        OctaneGateReportSnapshot.fromResult(
            OctaneGateReportState.PASSED,
            "Previous",
            result(),
            classifier,
            30,
            3600,
            "2026-05-14T23:45:00Z");
    OctaneGateReportSnapshot current =
        OctaneGateReportSnapshot.fromResult(
                OctaneGateReportState.PASSED,
                "Current",
                resultWithRiskHeatMap(),
                classifier,
                30,
                3600,
                "2026-05-14T23:50:00Z")
            .withCalculatedTestMetrics(previous);

    assertEquals("0%", metric(current, "automation-usage").getValue());
    assertEquals(
        "0/6 tests automated. Target 100%", metric(current, "automation-usage").getDetail());
    assertEquals("No change from last cycle", metric(current, "automation-usage").getTrendText());
    assertEquals("negative", metric(current, "automation-usage").getTrendTone());
    assertEquals("60.0%", metric(current, "success-rate").getValue());
    assertEquals("3 / 5 passed", metric(current, "success-rate").getDetail());
    assertEquals("83.3%", metric(current, "execution").getValue());
    assertEquals("5 / 6 executed", metric(current, "execution").getDetail());
    assertEquals("3 open", metric(current, "defects").getValue());
    assertEquals("50.0 per 100 tests", metric(current, "defects").getDetail());
  }

  @Test
  public void testMetricsHandleZeroExecutedAndMissingRiskMap() {
    GateResult result =
        new GateResult(
            "empty",
            "100% pass",
            false,
            false,
            new GateMetrics(0, 0, 0, 0, 0, 0),
            List.of(),
            Map.of("empty", List.of()),
            Map.of(),
            Instant.parse("2026-05-15T00:00:00Z"));

    OctaneGateReportSnapshot snapshot =
        OctaneGateReportSnapshot.fromResult(
            OctaneGateReportState.POLLING, "Polling", result, classifier, 30);

    assertEquals("0%", metric(snapshot, "automation-usage").getValue());
    assertEquals("Waiting for run data", metric(snapshot, "automation-usage").getTrendText());
    assertEquals("0.0%", metric(snapshot, "success-rate").getValue());
    assertEquals("0.0%", metric(snapshot, "execution").getValue());
    assertEquals("N/A", metric(snapshot, "defects").getValue());
    assertEquals("Risk heat map unavailable", metric(snapshot, "defects").getDetail());
  }

  @Test
  public void reportSectionsOmitBypassedRegressionAndKeepCriticalData() {
    Map<String, List<RunRecord>> criticalSuiteRuns = new LinkedHashMap<>();
    criticalSuiteRuns.put(
        "4502",
        List.of(
            new RunRecord("1", "critical one", "passed"),
            new RunRecord("2", "critical two", "passed")));
    GateResult result =
        new GateResult(
            "",
            "critical.passRate == 100",
            true,
            true,
            new GateMetrics(0, 0, 0, 0, 0, 0),
            List.of(),
            Map.of(),
            Map.of(
                "critical",
                new GateScopeResult(
                    "critical",
                    "",
                    List.of(),
                    "4502",
                    List.of("4502"),
                    new GateMetrics(2, 2, 2, 0, 0, 0),
                    criticalSuiteRuns.get("4502"),
                    criticalSuiteRuns)),
            Instant.parse("2026-05-15T00:00:00Z"));

    OctaneGateReportSnapshot snapshot =
        OctaneGateReportSnapshot.fromResult(
            OctaneGateReportState.POLLING, "Polling", result, classifier, 30);

    assertEquals(1, snapshot.getSections().size());
    assertEquals(1, snapshot.getReportSections().size());
    assertEquals("critical", snapshot.getReportSections().get(0).getSource());
    assertTrue(snapshot.hasReportSections());
  }

  private int count(OctaneGateReportSection section, OctaneGateStatusBucket bucket) {
    return section.getTotals().stream()
        .filter(status -> status.getBucket() == bucket)
        .findFirst()
        .orElseThrow()
        .getCount();
  }

  private int suiteRunStatusCount(OctaneGateReportSection section, OctaneGateStatusBucket bucket) {
    return section.getSuiteRuns().get(0).getStatuses().stream()
        .filter(status -> status.getBucket() == bucket)
        .findFirst()
        .orElseThrow()
        .getCount();
  }

  private OctaneTestMetricCard metric(OctaneGateReportSnapshot snapshot, String key) {
    return snapshot.getTestMetrics().getCards().stream()
        .filter(card -> key.equals(card.getKey()))
        .findFirst()
        .orElseThrow();
  }

  private List<String> emails(List<OctaneTesterPerformance> testers) {
    return testers.stream().map(tester -> tester.getEmail()).toList();
  }

  private List<RunRecord> runsForOwner(String prefix, String owner, int count) {
    List<RunRecord> runs = new java.util.ArrayList<>();
    for (int index = 0; index < count; index++) {
      runs.add(
          new RunRecord(
              prefix + "-" + index,
              "Run " + index,
              "passed",
              "Default Manual Runner",
              owner,
              "",
              "",
              "",
              ""));
    }
    return List.copyOf(runs);
  }

  private void assertDominantStatusForTie(
      String firstStatus, String secondStatus, String expectedLabel, String expectedColor) {
    OctaneGateSuiteRunChart chart =
        OctaneGateSuiteRunChart.fromRunByGroup(
            "Tie Tester",
            List.of("4501"),
            List.of(
                new RunRecord("1", "one", firstStatus, "Tie Tester"),
                new RunRecord("2", "two", secondStatus, "Tie Tester")),
            classifier);

    assertEquals(expectedLabel, chart.getDominantStatusLabel());
    assertEquals(expectedColor, chart.getDominantStatusColor());
    assertEquals(1, chart.getDominantStatusCount());
  }

  private GateResult result() {
    Map<String, List<RunRecord>> regressionSuiteRuns = new LinkedHashMap<>();
    regressionSuiteRuns.put(
        "4501",
        List.of(
            new RunRecord("1", "one", "passed", "Ada Tester"),
            new RunRecord("2", "two", "failed", "Ada Tester"),
            new RunRecord("3", "three", "planned", "Ada Tester")));
    regressionSuiteRuns.put(
        "4502",
        List.of(
            new RunRecord("4", "four", "passed", "Ben Tester"),
            new RunRecord("5", "five", "skipped", "Ben Tester")));

    Map<String, List<RunRecord>> criticalSuiteRuns = new LinkedHashMap<>();
    criticalSuiteRuns.put(
        "4502",
        List.of(
            new RunRecord("4", "four", "passed", "Ben Tester"),
            new RunRecord("5", "five", "failed", "Ben Tester")));
    criticalSuiteRuns.put("4503", List.of(new RunRecord("6", "six", "passed", "Cara Tester")));

    return new GateResult(
        "4501,4502",
        "critical.passRate == 100",
        false,
        false,
        new GateMetrics(5, 4, 2, 1, 1, 1),
        List.of(
            new RunRecord("1", "one", "passed", "Ada Tester"),
            new RunRecord("2", "two", "failed", "Ada Tester"),
            new RunRecord("3", "three", "planned", "Ada Tester"),
            new RunRecord("4", "four", "passed", "Ben Tester"),
            new RunRecord("5", "five", "skipped", "Ben Tester")),
        regressionSuiteRuns,
        Map.of(
            "critical",
            new GateScopeResult(
                "critical",
                "",
                List.of(),
                "4502,4503",
                List.of("4502", "4503"),
                new GateMetrics(3, 3, 2, 1, 0, 0),
                List.of(
                    new RunRecord("4", "four", "passed", "Ben Tester"),
                    new RunRecord("5", "five", "failed", "Ben Tester"),
                    new RunRecord("6", "six", "passed", "Cara Tester")),
                criticalSuiteRuns)),
        Instant.parse("2026-05-15T00:00:00Z"));
  }

  private GateResult resultWithRiskHeatMap() {
    GateResult base = result();
    List<DefectRecord> defects =
        List.of(
            new DefectRecord(
                "d1", "Critical defect", "critical", "", "new", "1", "1", "p1", "Project"),
            new DefectRecord("d2", "High defect", "high", "", "opened", "2", "2", "p1", "Project"),
            new DefectRecord("d3", "Missing severity", "", "", "opened", "3", "3", "p1", "Project"),
            new DefectRecord(
                "d4", "Closed defect", "low", "", "closed", "4", "4", "p1", "Project"));
    OctaneRiskHeatMap riskHeatMap =
        OctaneRiskHeatMap.of(
            new OctaneRiskHeatMapNode("project", "Project", 80, 6, 4, List.of()),
            4,
            4,
            0,
            1,
            OctaneDefectSeveritySummary.fromDefects(defects));
    return new GateResult(
        base.getSuiteRunId(),
        base.getCriteria(),
        base.isPassed(),
        base.isTerminal(),
        base.getMetrics(),
        base.getRuns(),
        base.getSuiteRuns(),
        base.getScopedResults(),
        riskHeatMap,
        base.getPolledAt());
  }
}
