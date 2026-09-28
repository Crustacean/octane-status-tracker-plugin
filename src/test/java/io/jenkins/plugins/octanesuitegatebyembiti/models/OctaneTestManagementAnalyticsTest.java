package io.jenkins.plugins.octanesuitegatebyembiti.models;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.jenkins.plugins.octanesuitegatebyembiti.entities.DefectRecord;
import io.jenkins.plugins.octanesuitegatebyembiti.entities.RunRecord;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class OctaneTestManagementAnalyticsTest {
  private static final String STARTED_AT = "2026-07-23T08:00:00Z";

  @Test
  public void buildsTimelineCategoriesAndTesterMetricsFromGateFacts() {
    List<RunRecord> runs = runs();
    List<DefectRecord> defects = defects();
    CriteriaEvaluation criteriaEvaluation =
        CriteriaEvaluation.available(
            false,
            List.of(
                new CriteriaComparisonEvaluation(
                    "regressions.executionRate", ">=", 80, 66.67, true, false),
                new CriteriaComparisonEvaluation("defects.major", "<", 1, 3, true, false)));

    OctaneTestManagementAnalytics analytics =
        analyticsAt("2026-07-23T08:01:00Z", runs, defects, criteriaEvaluation);

    OctaneTestManagementAnalytics.TimelinePoint point = analytics.getPoints().get(0);
    assertEquals(6, point.getTotal());
    assertEquals(1, point.getPlanned());
    assertEquals(1, point.getInProgress());
    assertEquals(1, point.getSkipped());
    assertEquals(1, point.getBlocked());
    assertEquals(1, point.getFailed());
    assertEquals(1, point.getPassed());
    assertEquals(3, point.getExecuted());

    List<OctaneTestManagementAnalytics.FailureCategory> categories =
        analytics.getFailureCategories();
    assertEquals(4, categories.size());
    assertEquals(3, categories.stream().mapToInt(category -> category.getOpenCount()).sum());
    assertEquals(1, categories.stream().mapToInt(category -> category.getClosedCount()).sum());
    assertTrue(
        categories.stream()
            .map(category -> category.getLabel())
            .anyMatch(label -> label.toLowerCase().contains("environment")));
    assertFalse(
        categories.stream()
            .map(category -> category.getLabel())
            .anyMatch(label -> "Genuine Bug".equals(label)));

    assertEquals(4, analytics.getTotalDefects());
    assertEquals(3, analytics.getOpenDefects());
    assertEquals(1, analytics.getClosedDefects());
    assertTrue(analytics.isDefectCriteriaConfigured());
    assertFalse(analytics.isDefectCompliant());
    assertEquals("Tester Alpha", analytics.getTopVolumeTesters().get(0).getName());
    assertEquals(3, analytics.getTopVolumeTesters().get(0).getTotal());
    assertEquals(0, analytics.getTopVolumeTesters().get(0).getOpenDefects());
    assertEquals("Tester Beta", analytics.getTopDefectTesters().get(0).getName());
    assertEquals(2, analytics.getTopDefectTesters().get(0).getOpenDefects());

    List<OctaneTestManagementAnalytics.MetricQuadrant> metrics = analytics.getMetricQuadrants();
    assertEquals("warning", metrics.get(0).getTone());
    assertEquals("1 surplus", metrics.get(0).getValue());
    assertEquals("2 expected | 3 open", metrics.get(0).getDetail());
    assertEquals("bad", metrics.get(1).getTone());
    assertEquals("warning", metrics.get(2).getTone());
    assertEquals("bad", metrics.get(3).getTone());
    assertEquals("Top Testers by Volume", metrics.get(2).getTitle());
    assertEquals("Top Testers by Open Defects", metrics.get(3).getTitle());
    assertEquals("3 tests", metrics.get(2).getItems().get(0).get("primaryValue"));
    assertEquals("33%", metrics.get(2).getItems().get(0).get("secondaryValue"));
    assertEquals("2 open", metrics.get(3).getItems().get(0).get("primaryValue"));
  }

  @Test
  public void appendsPollingHistoryAndReplacesDuplicateTimestamp() {
    OctaneTestManagementAnalytics first =
        analyticsAt(
            "2026-07-23T08:01:00Z",
            runs().subList(0, 2),
            List.of(),
            CriteriaEvaluation.unavailable());
    OctaneTestManagementAnalytics second =
        analyticsAt("2026-07-23T08:02:00Z", runs(), defects(), CriteriaEvaluation.unavailable());

    OctaneTestManagementAnalytics history = first.appendLatest(second);
    assertEquals(2, history.getPoints().size());
    assertEquals(60_000L, history.getPoints().get(0).getElapsedMillis());
    assertEquals(120_000L, history.getPoints().get(1).getElapsedMillis());
    assertEquals(4, history.getTotalDefects());

    OctaneTestManagementAnalytics replacement = history.appendLatest(second);
    assertEquals(2, replacement.getPoints().size());
    assertEquals(6, replacement.getPoints().get(1).getTotal());
  }

  @Test
  public void classifiesQueuedRunsAsInProgressRatherThanPlannedOrSkipped() {
    OctaneTestManagementAnalytics analytics =
        analyticsAt(
            "2026-07-23T08:01:00Z",
            List.of(run("1", "list_node.run_status.queued", "Tester Alpha", "test-1")),
            List.of(),
            CriteriaEvaluation.unavailable());

    OctaneTestManagementAnalytics.TimelinePoint point = analytics.getPoints().get(0);
    assertEquals(1, point.getInProgress());
    assertEquals(0, point.getPlanned());
    assertEquals(0, point.getSkipped());
  }

  @Test
  public void attributesDashboardTesterMetricsToSuiteOwnerInsteadOfChildRunner() {
    RunRecord childRun =
        new RunRecord(
            "1",
            "Automated child",
            "passed",
            "Jenkins Agent",
            "Suite Owner",
            "test-1",
            "Test 1",
            "",
            "");

    OctaneTestManagementAnalytics analytics =
        analyticsAt(
            "2026-07-23T08:01:00Z", List.of(childRun), List.of(), CriteriaEvaluation.unavailable());

    assertEquals("Suite Owner", analytics.getTopVolumeTesters().get(0).getName());
  }

  @Test
  public void aggregatesOnlyPositiveExecutionDeltasIntoTenDiscreteIntervals() {
    List<RunRecord> firstRuns =
        List.of(
            run("1", "passed", "Tester Alpha", "test-1"),
            run("2", "failed", "Tester Beta", "test-2"),
            run("3", "planned", "Tester Gamma", "test-3"));
    List<RunRecord> secondRuns =
        List.of(
            run("1", "passed", "Tester Alpha", "test-1"),
            run("2", "passed", "Tester Beta", "test-2"),
            run("3", "blocked", "Tester Gamma", "test-3"),
            run("4", "skipped", "Tester Delta", "test-4"));
    OctaneTestManagementAnalytics history =
        analyticsAt("2026-07-23T08:01:00Z", firstRuns, List.of(), CriteriaEvaluation.unavailable())
            .appendLatest(
                analyticsAt(
                    "2026-07-23T08:02:00Z",
                    secondRuns,
                    List.of(),
                    CriteriaEvaluation.unavailable()));

    List<OctaneTestManagementAnalytics.ExecutionInterval> intervals =
        history.getExecutionIntervals();
    assertEquals(10, intervals.size());
    assertEquals(1, intervals.get(0).getPassed());
    assertEquals(1, intervals.get(0).getFailed());
    assertEquals(2, intervals.get(0).getTotal());
    assertEquals(1, intervals.get(1).getPassed());
    assertEquals(0, intervals.get(1).getFailed());
    assertEquals(1, intervals.get(1).getBlocked());
    assertEquals(1, intervals.get(1).getSkipped());
    assertEquals(3, intervals.get(1).getTotal());
    assertEquals(0, intervals.get(2).getTotal());
    assertEquals(10, ((List<?>) history.toMap().get("executionIntervals")).size());
  }

  @Test
  public void clustersSimilarDefectTitlesWithoutPredefinedRootCauseBuckets() {
    List<DefectRecord> clusteredDefects =
        List.of(
            defect("d1", "API timeout while creating order", "high", "opened", "1", "test-1"),
            defect("d2", "API timeout while cancelling order", "high", "fixed", "2", "test-2"),
            defect("d3", "Browser selector missing", "medium", "opened", "3", "test-3"));

    OctaneTestManagementAnalytics analytics =
        analyticsAt(
            "2026-07-23T08:01:00Z",
            runs().subList(0, 3),
            clusteredDefects,
            CriteriaEvaluation.unavailable());

    assertEquals(2, analytics.getFailureCategories().size());
    OctaneTestManagementAnalytics.FailureCategory timeoutCluster =
        analytics.getFailureCategories().stream()
            .filter(category -> category.getDefects().size() == 2)
            .findFirst()
            .orElseThrow();
    assertTrue(timeoutCluster.getLabel().toLowerCase().contains("api"));
    assertEquals(1, timeoutCluster.getOpenCount());
    assertEquals(1, timeoutCluster.getClosedCount());
  }

  @Test
  public void formatsAndCategorizesDefectsFromNameAndDescription() {
    List<DefectRecord> values =
        List.of(
            new DefectRecord(
                "a",
                "DEF-101",
                "NullPointer in Gateway",
                "high",
                "",
                "opened",
                "1",
                "test-1",
                "",
                ""),
            new DefectRecord("b", "DEF-102", "", "high", "", "opened", "2", "test-2", "", ""),
            new DefectRecord(
                "c", "", "Timeout on Checkout", "high", "", "opened", "3", "test-3", "", ""));

    OctaneTestManagementAnalytics analytics =
        analyticsAt(
            "2026-07-23T08:01:00Z", runs().subList(0, 3), values, CriteriaEvaluation.unavailable());
    Map<String, OctaneTestManagementAnalytics.DefectDetail> details = defectDetails(analytics);

    assertEquals("DEF-101:\nNullPointer in Gateway", details.get("a").getDescription());
    assertEquals("DEF-102", details.get("b").getDescription());
    assertEquals("Timeout on Checkout", details.get("c").getDescription());
    assertTrue(
        analytics.getFailureCategories().stream()
            .anyMatch(category -> category.getLabel().toLowerCase().contains("timeout")));
  }

  @Test
  public void categorizesUsingDescriptionWhenDefectNameHasNoCategorySignal() {
    DefectRecord loanDefect =
        new DefectRecord(
            "loan-1",
            "Issue",
            "Timeout when i select business loans",
            "high",
            "",
            "opened",
            "1",
            "test-1",
            "",
            "");

    OctaneTestManagementAnalytics analytics =
        analyticsAt(
            "2026-07-23T08:01:00Z",
            runs().subList(0, 1),
            List.of(loanDefect),
            CriteriaEvaluation.unavailable());

    assertEquals(1, analytics.getFailureCategories().size());
    OctaneTestManagementAnalytics.FailureCategory category =
        analytics.getFailureCategories().get(0);
    assertTrue(category.getLabel().toLowerCase().contains("loan"));
    assertEquals(1, category.getDefects().size());
    assertEquals(
        "Issue:\nTimeout when i select business loans",
        category.getDefects().get(0).getDescription());
  }

  @Test
  public void normalizesDefectListStatusSeverityAndHierarchyColors() {
    List<DefectRecord> values =
        List.of(
            defect(
                "d1",
                "Checkout payment critical failure",
                "list_node.defect_severity.critical",
                "new",
                "1",
                "test-1"),
            defect("d2", "Checkout payment very high failure", "VERY_HIGH", "fixed", "2", "test-2"),
            defect("d3", "Checkout payment high failure", "Severity High", "opened", "3", "test-3"),
            defect("d4", "Checkout payment medium failure", "medium", "resolved", "4", "test-4"),
            defect("d5", "Checkout payment low failure", "low", "opened", "5", "test-5"),
            defect("d6", "Checkout payment unknown failure", "", "new", "6", "test-6"));

    OctaneTestManagementAnalytics analytics =
        analyticsAt("2026-07-23T08:01:00Z", runs(), values, CriteriaEvaluation.unavailable());
    Map<String, OctaneTestManagementAnalytics.DefectDetail> details = new java.util.HashMap<>();
    for (OctaneTestManagementAnalytics.FailureCategory category :
        analytics.getFailureCategories()) {
      for (OctaneTestManagementAnalytics.DefectDetail detail : category.getDefects()) {
        details.put(detail.getId(), detail);
      }
    }

    assertEquals("Critical", details.get("d1").getSeverity());
    assertEquals("Very High", details.get("d2").getSeverity());
    assertEquals("High", details.get("d3").getSeverity());
    assertEquals("Medium", details.get("d4").getSeverity());
    assertEquals("Low", details.get("d5").getSeverity());
    assertEquals("Unspecified", details.get("d6").getSeverity());
    assertEquals("Open", details.get("d1").getStatus());
    assertEquals("Closed", details.get("d2").getStatus());
    assertEquals("Closed", details.get("d4").getStatus());
    assertEquals("#FF3B30", details.get("d1").getSeverityColor());
    assertEquals("#FFCC00", details.get("d2").getSeverityColor());
    assertEquals("#FF9500", details.get("d3").getSeverityColor());
    assertEquals("#AF52DE", details.get("d4").getSeverityColor());
    assertEquals("#5AC8FA", details.get("d5").getSeverityColor());
    assertEquals("#8E8E93", details.get("d6").getSeverityColor());
    assertEquals("#000000", details.get("d1").getSeverityTextColor());
    assertEquals("#000000", details.get("d4").getSeverityTextColor());
    assertEquals("#34C759", details.get("d2").getStatusColor());
    assertTrue(
        analytics.getFailureCategories().stream()
            .anyMatch(
                category ->
                    "Critical".equals(category.getHighestOpenSeverity())
                        && "#FF3B30".equals(category.getOpenColor())));
  }

  @Test
  public void displaysConfiguredDefectGroupsWhilePreservingUnderlyingSeverity() {
    OctaneDefectGroup major = defectGroup("major", "Critical, Very High, High, Unspecified");
    OctaneDefectGroup minor = defectGroup("minor", "Low, Medium");
    List<DefectRecord> values =
        List.of(
            defect("d1", "Critical payment failure", "critical", "new", "1", "test-1"),
            defect("d2", "Very high payment failure", "VERY_HIGH", "new", "2", "test-2"),
            defect("d3", "High payment failure", "high", "new", "3", "test-3"),
            defect("d4", "Unspecified payment failure", "", "new", "4", "test-4"),
            defect("d5", "Medium payment failure", "medium", "new", "5", "test-5"),
            defect("d6", "Low payment failure", "low", "new", "6", "test-6"));

    OctaneTestManagementAnalytics analytics =
        analyticsAt(
            "2026-07-23T08:01:00Z",
            runs(),
            values,
            CriteriaEvaluation.unavailable(),
            List.of(major, minor));
    Map<String, OctaneTestManagementAnalytics.DefectDetail> details = defectDetails(analytics);

    assertEquals("Critical", details.get("d1").getSeverity());
    assertEquals("Major", details.get("d1").getSeverityLabel());
    assertEquals("Major", details.get("d2").getSeverityLabel());
    assertEquals("Major", details.get("d3").getSeverityLabel());
    assertEquals("Major", details.get("d4").getSeverityLabel());
    assertEquals("Minor", details.get("d5").getSeverityLabel());
    assertEquals("Minor", details.get("d6").getSeverityLabel());
    assertEquals("Critical", details.get("d1").getSeverityColorKey());
    assertEquals("Critical", details.get("d4").getSeverityColorKey());
    assertEquals("Medium", details.get("d5").getSeverityColorKey());
    assertEquals("Medium", details.get("d6").getSeverityColorKey());
    assertEquals(1, details.get("d1").getSeveritySortRank());
    assertEquals(1, details.get("d4").getSeveritySortRank());
    assertEquals(4, details.get("d5").getSeveritySortRank());
    assertEquals(4, details.get("d6").getSeveritySortRank());
    assertEquals("#FF3B30", details.get("d1").getSeverityColor());
    assertEquals("#FF3B30", details.get("d4").getSeverityColor());
    assertEquals("#AF52DE", details.get("d5").getSeverityColor());
    assertEquals("#AF52DE", details.get("d6").getSeverityColor());
    assertEquals("Major", defectMap(analytics, "d1").get("severityLabel"));
    assertEquals("Critical", defectMap(analytics, "d4").get("severityColorKey"));
    assertEquals("Medium", defectMap(analytics, "d6").get("severityColorKey"));
    assertEquals(1, defectMap(analytics, "d4").get("severitySortRank"));
    assertEquals(4, defectMap(analytics, "d6").get("severitySortRank"));
  }

  @Test
  public void displaysIndividualSeverityWhenNoMatchingGroupIsConfigured() {
    OctaneDefectGroup major = defectGroup("major", "Critical, Very High");
    List<DefectRecord> values =
        List.of(
            defect("d1", "Critical payment failure", "critical", "new", "1", "test-1"),
            defect("d2", "Medium payment failure", "medium", "new", "2", "test-2"));

    OctaneTestManagementAnalytics analytics =
        analyticsAt(
            "2026-07-23T08:01:00Z",
            runs(),
            values,
            CriteriaEvaluation.unavailable(),
            List.of(major));
    Map<String, OctaneTestManagementAnalytics.DefectDetail> details = defectDetails(analytics);

    assertEquals("Major", details.get("d1").getSeverityLabel());
    assertEquals("Medium", details.get("d2").getSeverityLabel());
    assertEquals(1, details.get("d1").getSeveritySortRank());
    assertEquals(5, details.get("d2").getSeveritySortRank());
    assertEquals("#FF3B30", details.get("d1").getSeverityColor());
    assertEquals("#AF52DE", details.get("d2").getSeverityColor());
  }

  @Test
  public void defaultsComplianceToZeroOpenDefectsWithoutDefectCriteria() {
    OctaneTestManagementAnalytics analytics =
        analyticsAt(
            "2026-07-23T08:01:00Z",
            runs().subList(0, 1),
            List.of(),
            CriteriaEvaluation.unavailable());

    assertFalse(analytics.isDefectCriteriaConfigured());
    assertTrue(analytics.isDefectCompliant());
    assertEquals(0, analytics.getExpectedOpenDefects());
    assertEquals("No open defects", analytics.getMetricQuadrants().get(0).getValue());
    assertEquals("0 expected | 0 open", analytics.getMetricQuadrants().get(0).getDetail());
    assertEquals("neutral", analytics.getMetricQuadrants().get(0).getTone());
    assertEquals("neutral", analytics.getMetricQuadrants().get(1).getTone());
  }

  @Test
  public void reconcilesExpectedOpenDefectsAgainstFailedAndBlockedTests() {
    List<RunRecord> failedAndBlocked =
        List.of(
            run("1", "failed", "Tester Alpha", "test-1"),
            run("2", "blocked", "Tester Alpha", "test-2"),
            run("3", "passed", "Tester Alpha", "test-3"));
    OctaneTestManagementAnalytics compliant =
        analyticsAt(
            "2026-07-23T08:01:00Z",
            failedAndBlocked,
            List.of(
                defect("d1", "Failure one", "high", "new", "1", "test-1"),
                defect("d2", "Failure two", "medium", "new", "2", "test-2")),
            CriteriaEvaluation.unavailable());
    OctaneTestManagementAnalytics underReported =
        analyticsAt(
            "2026-07-23T08:01:00Z",
            failedAndBlocked,
            List.of(defect("d1", "Failure one", "high", "new", "1", "test-1")),
            CriteriaEvaluation.unavailable());

    assertEquals(2, compliant.getExpectedOpenDefects());
    assertEquals(0, compliant.getOpenDefectVariance());
    assertEquals("Compliant", compliant.getMetricQuadrants().get(0).getValue());
    assertEquals("2 expected | 2 open", compliant.getMetricQuadrants().get(0).getDetail());
    assertEquals("good", compliant.getMetricQuadrants().get(0).getTone());
    assertEquals(-1, underReported.getOpenDefectVariance());
    assertEquals("1 under-reported", underReported.getMetricQuadrants().get(0).getValue());
    assertEquals("warning", underReported.getMetricQuadrants().get(0).getTone());
  }

  @Test
  public void reconcilesClosedDefectsAgainstFinalRerunStatuses() {
    OctaneTestManagementAnalytics initial =
        analyticsAt(
            "2026-07-23T08:01:00Z",
            List.of(
                run("1", "failed", "Tester Alpha", "test-1"),
                run("2", "blocked", "Tester Alpha", "test-2")),
            List.of(
                defect("d1", "Failure one", "high", "new", "1", "test-1"),
                defect("d2", "Failure two", "medium", "new", "2", "test-2")),
            CriteriaEvaluation.unavailable());
    OctaneTestManagementAnalytics finalRerun =
        analyticsAt(
            "2026-07-23T08:02:00Z",
            List.of(
                run("1", "passed", "Tester Alpha", "test-1"),
                run("2", "passed", "Tester Alpha", "test-2")),
            List.of(
                defect("d1", "Failure one", "high", "closed", "1", "test-1"),
                defect("d2", "Failure two", "medium", "fixed", "2", "test-2")),
            CriteriaEvaluation.unavailable());

    OctaneTestManagementAnalytics reconciled = initial.appendLatest(finalRerun);

    assertEquals(0, reconciled.getExpectedOpenDefects());
    assertEquals(0, reconciled.getOpenDefects());
    assertEquals(2, reconciled.getClosedDefects());
    assertTrue(reconciled.isDefectCompliant());
    assertEquals("No open defects", reconciled.getMetricQuadrants().get(0).getValue());
    assertEquals("0 expected | 0 open", reconciled.getMetricQuadrants().get(0).getDetail());
    assertEquals("neutral", reconciled.getMetricQuadrants().get(0).getTone());
  }

  @Test
  public void appliesWarningThroughSeventyPercentAndActionAboveSeventyPercent() {
    List<RunRecord> runs =
        List.of(
            run("1", "failed", "Tester Alpha", "test-1"),
            run("2", "passed", "Tester Alpha", "test-2"));
    OctaneTestManagementAnalytics warning =
        analyticsAt(
            "2026-07-23T08:01:00Z",
            runs,
            defectsWithPhases(7, 3),
            CriteriaEvaluation.unavailable());
    OctaneTestManagementAnalytics action =
        analyticsAt(
            "2026-07-23T08:01:00Z",
            runs,
            defectsWithPhases(8, 2),
            CriteriaEvaluation.unavailable());

    assertEquals("warning", warning.getMetricQuadrants().get(1).getTone());
    assertEquals("bad", action.getMetricQuadrants().get(1).getTone());

    List<RunRecord> allBelowTarget =
        List.of(
            run("10", "planned", "Tester One", "test-10"),
            run("11", "planned", "Tester Two", "test-11"),
            run("12", "planned", "Tester Three", "test-12"),
            run("13", "planned", "Tester Four", "test-13"));
    OctaneTestManagementAnalytics volumeAction =
        analyticsAt(
            "2026-07-23T08:01:00Z", allBelowTarget, List.of(), CriteriaEvaluation.unavailable());
    assertEquals("bad", volumeAction.getMetricQuadrants().get(2).getTone());
  }

  @Test
  public void attributesGateDefectsToTheOnlyTesterWhenOctaneRelationFieldsAreAbsent() {
    List<RunRecord> singleTesterRuns =
        List.of(
            run("1", "failed", "Tester Alpha", "test-1"),
            run("2", "passed", "Tester Alpha", "test-2"));
    List<DefectRecord> defects = List.of(defect("d1", "Unlinked failure", "high", "new", "", ""));

    OctaneTestManagementAnalytics analytics =
        analyticsAt(
            "2026-07-23T08:01:00Z", singleTesterRuns, defects, CriteriaEvaluation.unavailable());
    OctaneTestManagementAnalytics.MetricQuadrant testerDefects =
        analytics.getMetricQuadrants().get(3);

    assertEquals(1, analytics.getTopDefectTesters().size());
    assertEquals("Tester Alpha", analytics.getTopDefectTesters().get(0).getName());
    assertEquals(1, analytics.getTopDefectTesters().get(0).getOpenDefects());
    assertEquals("1 open", testerDefects.getValue());
    assertEquals(1, testerDefects.getItems().size());
  }

  private OctaneTestManagementAnalytics analyticsAt(
      String polledAt,
      List<RunRecord> runs,
      List<DefectRecord> defects,
      CriteriaEvaluation criteriaEvaluation) {
    return analyticsAt(polledAt, runs, defects, criteriaEvaluation, List.of());
  }

  private OctaneTestManagementAnalytics analyticsAt(
      String polledAt,
      List<RunRecord> runs,
      List<DefectRecord> defects,
      CriteriaEvaluation criteriaEvaluation,
      List<OctaneDefectGroup> defectGroups) {
    StatusClassifier classifier = classifier();
    GateResult result =
        new GateResult(
            "4501",
            "regressions.executionRate >= 80",
            false,
            false,
            GateMetrics.fromRuns(runs, classifier),
            runs,
            Map.of("4501", runs),
            Map.of(),
            OctaneRiskHeatMap.disabled(),
            new DefectCriteriaMetrics(
                OctaneDefectSeveritySummary.fromDefects(defects), defectGroups),
            defects,
            criteriaEvaluation,
            Instant.parse(polledAt));
    return OctaneTestManagementAnalytics.fromResult(STARTED_AT, 600_000L, result, classifier, 80);
  }

  private Map<String, OctaneTestManagementAnalytics.DefectDetail> defectDetails(
      OctaneTestManagementAnalytics analytics) {
    Map<String, OctaneTestManagementAnalytics.DefectDetail> details = new java.util.HashMap<>();
    for (OctaneTestManagementAnalytics.FailureCategory category :
        analytics.getFailureCategories()) {
      for (OctaneTestManagementAnalytics.DefectDetail detail : category.getDefects()) {
        details.put(detail.getId(), detail);
      }
    }
    return details;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> defectMap(OctaneTestManagementAnalytics analytics, String defectId) {
    List<Map<String, Object>> categories =
        (List<Map<String, Object>>) analytics.toMap().get("failureCategories");
    for (Map<String, Object> category : categories) {
      List<Map<String, Object>> defects = (List<Map<String, Object>>) category.get("defects");
      for (Map<String, Object> defect : defects) {
        if (defectId.equals(defect.get("id"))) {
          return defect;
        }
      }
    }
    throw new AssertionError("Missing defect " + defectId);
  }

  private OctaneDefectGroup defectGroup(String name, String types) {
    OctaneDefectGroup group = new OctaneDefectGroup(name);
    group.setTypes(types);
    return group;
  }

  private List<RunRecord> runs() {
    return List.of(
        run("1", "passed", "Tester Alpha", "test-1"),
        run("2", "failed", "Tester Beta", "test-2"),
        run("3", "list_node.run_status.in_progress", "Tester Alpha", "test-3"),
        run("4", "skipped", "Tester Gamma", "test-4"),
        run("5", "blocked", "Tester Beta", "test-5"),
        run("6", "planned", "Tester Alpha", "test-2"));
  }

  private RunRecord run(String id, String status, String tester, String testId) {
    return new RunRecord(id, "Run " + id, status, tester, testId, "Test " + id, "", "");
  }

  private List<DefectRecord> defects() {
    return List.of(
        defect("d1", "Environment unavailable", "critical", "new", "2", "test-2"),
        defect("d2", "Broken selector in script", "high", "fixed", "1", "test-1"),
        defect("d3", "Stale test data", "medium", "opened", "5", "test-5"),
        defect("d4", "Checkout total is wrong", "very high", "new", "", "test-4"));
  }

  private List<DefectRecord> defectsWithPhases(int open, int closed) {
    List<DefectRecord> values = new java.util.ArrayList<>();
    for (int index = 0; index < open; index++) {
      values.add(defect("open-" + index, "Open defect " + index, "high", "new", "1", "test-1"));
    }
    for (int index = 0; index < closed; index++) {
      values.add(
          defect("closed-" + index, "Closed defect " + index, "medium", "fixed", "1", "test-1"));
    }
    return List.copyOf(values);
  }

  private DefectRecord defect(
      String id, String name, String severity, String phase, String runId, String testId) {
    return new DefectRecord(id, name, severity, "", phase, runId, testId, "", "");
  }

  private StatusClassifier classifier() {
    return new StatusClassifier(
        StatusClassifier.DEFAULT_PASSED_STATUSES,
        StatusClassifier.DEFAULT_FAILED_STATUSES,
        StatusClassifier.DEFAULT_NEUTRAL_STATUSES,
        StatusClassifier.DEFAULT_RUNNING_STATUSES);
  }
}
