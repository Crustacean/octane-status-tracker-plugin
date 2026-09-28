package io.jenkins.plugins.octanesuitegatebyembiti.models;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.jenkins.plugins.octanesuitegatebyembiti.entities.DefectRecord;
import io.jenkins.plugins.octanesuitegatebyembiti.entities.RunRecord;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class GateResultTest {
  @Test
  public void scopeExportsBoundDefaultAndExplicitLimitsWithoutChangingTotals() {
    List<RunRecord> runs = new ArrayList<>();
    for (int index = 0; index <= GateResult.PIPELINE_DETAIL_LIMIT; index++) {
      runs.add(new RunRecord(Integer.toString(index), "run", "passed"));
    }
    GateScopeResult scope =
        new GateScopeResult(
            "critical",
            "",
            List.of(),
            new GateMetrics(runs.size(), runs.size(), runs.size(), 0, 0, 0),
            runs);
    for (Map<String, Object> exported : List.of(scope.toMap(), scope.toMap(Integer.MAX_VALUE))) {
      assertEquals(GateResult.PIPELINE_DETAIL_LIMIT, ((List<?>) exported.get("runs")).size());
      assertEquals(GateResult.PIPELINE_DETAIL_LIMIT, ((List<?>) exported.get("runIds")).size());
      assertEquals(runs.size(), exported.get("runCount"));
      assertEquals(true, exported.get("detailsTruncated"));
    }
    assertTrue(((List<?>) scope.toMap(-1).get("runs")).isEmpty());
    assertEquals(5, ((List<?>) scope.toMap(5).get("runs")).size());
  }

  @Test
  public void exposesSuiteRunIdsInPipelineMap() {
    GateResult result =
        new GateResult(
            "1196,1200",
            "100% pass",
            true,
            true,
            new GateMetrics(1, 1, 1, 0, 0, 0),
            Map.of(),
            Instant.parse("2026-05-13T00:00:00Z"));

    assertEquals(List.of("1196", "1200"), result.toPipelineMap().get("suiteRunIds"));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void boundsPipelineRunDetailsAndReportsTruncation() {
    List<RunRecord> runs = new ArrayList<>();
    for (int index = 0; index < GateResult.PIPELINE_DETAIL_LIMIT + 1; index++) {
      runs.add(new RunRecord(Integer.toString(index), "run " + index, "passed"));
    }
    GateResult result =
        new GateResult(
            "1196",
            "100% pass",
            true,
            true,
            new GateMetrics(runs.size(), runs.size(), runs.size(), 0, 0, 0),
            runs,
            Map.of("1196", runs),
            Map.of(),
            Instant.parse("2026-05-13T00:00:00Z"));

    Map<String, Object> pipelineMap = result.toPipelineMap();

    assertEquals(runs.size(), pipelineMap.get("runCount"));
    assertEquals(true, pipelineMap.get("detailsTruncated"));
    assertEquals(
        GateResult.PIPELINE_DETAIL_LIMIT,
        ((List<Map<String, Object>>) pipelineMap.get("runs")).size());
    assertTrue(((Map<String, Object>) pipelineMap.get("suiteRuns")).isEmpty());
  }

  @Test
  @SuppressWarnings("unchecked")
  public void exposesRegressionMetricsAliasInPipelineMap() {
    GateResult result =
        new GateResult(
            "1196,1200",
            "regressions.passRate == 100",
            true,
            true,
            new GateMetrics(2, 2, 2, 0, 0, 0),
            Map.of(),
            Instant.parse("2026-05-13T00:00:00Z"));

    Map<String, Object> regressions =
        (Map<String, Object>) result.toPipelineMap().get("regressions");
    assertEquals(100.0, regressions.get("passRate"));
    assertEquals(100.0, regressions.get("executionRate"));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void exposesDetailedCriteriaEvaluationInPipelineMap() {
    CriteriaEvaluation evaluation =
        CriteriaEvaluation.available(
            false,
            List.of(
                new CriteriaComparisonEvaluation(
                    "regressions.passRate", ">=", 95, 92.5, true, false)));
    GateResult result =
        new GateResult(
            "1196",
            "regressions.passRate >= 95",
            false,
            true,
            new GateMetrics(10, 10, 9, 1, 0, 0),
            List.of(),
            Map.of(),
            Map.of(),
            OctaneRiskHeatMap.disabled(),
            new DefectCriteriaMetrics(OctaneDefectSeveritySummary.empty(), List.of()),
            evaluation,
            Instant.parse("2026-05-13T00:00:00Z"));

    Map<String, Object> criteriaEvaluation =
        (Map<String, Object>) result.toPipelineMap().get("criteriaEvaluation");
    List<Map<String, Object>> comparisons =
        (List<Map<String, Object>>) criteriaEvaluation.get("comparisons");
    assertEquals(false, criteriaEvaluation.get("passed"));
    assertEquals("regressions.passRate >= 95%", comparisons.get(0).get("criterion"));
    assertEquals("92.5%", comparisons.get(0).get("actualLabel"));
    assertEquals("NOT OK", comparisons.get(0).get("result"));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void keepsScopedMetricsCompatibleAndAddsScopeDetails() {
    GateResult result =
        new GateResult(
            "1196,1200",
            "critical.passRate == 100",
            true,
            true,
            new GateMetrics(2, 2, 1, 1, 0, 0),
            List.of(
                new RunRecord("101", "normal one", "passed"),
                new RunRecord("102", "normal two", "failed")),
            Map.of(
                "1196",
                List.of(new RunRecord("101", "normal one", "passed")),
                "1200",
                List.of(new RunRecord("102", "normal two", "failed"))),
            Map.of(
                "critical",
                new GateScopeResult(
                    "critical",
                    "test={((product_areas={id=1004||id=1005}))}",
                    List.of("1004", "1005"),
                    new GateMetrics(1, 1, 1, 0, 0, 0),
                    List.of(new RunRecord("101", "critical one", "passed")))),
            Instant.parse("2026-05-13T00:00:00Z"));

    Map<String, Object> pipelineMap = result.toPipelineMap();
    Map<String, Object> scopes = (Map<String, Object>) pipelineMap.get("scopes");
    Map<String, Object> criticalMetrics = (Map<String, Object>) scopes.get("critical");
    assertEquals(100.0, criticalMetrics.get("passRate"));

    Map<String, Object> scopeDetails = (Map<String, Object>) pipelineMap.get("scopeDetails");
    Map<String, Object> criticalDetails = (Map<String, Object>) scopeDetails.get("critical");
    assertEquals(List.of("1004", "1005"), criticalDetails.get("queryIds"));
    assertEquals(List.of("101"), criticalDetails.get("runIds"));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void exposesSuiteRunBackedScopeDetails() {
    GateResult result =
        new GateResult(
            "450297,450300,450303",
            "critical.passRate == 100",
            true,
            true,
            new GateMetrics(4, 4, 4, 0, 0, 0),
            List.of(
                new RunRecord("450298", "normal one", "passed"),
                new RunRecord("450299", "normal two", "passed"),
                new RunRecord("450301", "normal three", "passed"),
                new RunRecord("450304", "overlap child", "passed")),
            Map.of(
                "450297",
                List.of(new RunRecord("450298", "normal one", "passed")),
                "450300",
                List.of(
                    new RunRecord("450299", "normal two", "passed"),
                    new RunRecord("450301", "normal three", "passed")),
                "450303",
                List.of(new RunRecord("450304", "overlap child", "passed"))),
            Map.of(
                "critical",
                new GateScopeResult(
                    "critical",
                    "",
                    List.of(),
                    "450303,450204",
                    List.of("450303", "450204"),
                    new GateMetrics(2, 2, 2, 0, 0, 0),
                    List.of(
                        new RunRecord("450304", "overlap child", "passed"),
                        new RunRecord("450205", "critical child", "passed")),
                    Map.of(
                        "450303",
                        List.of(new RunRecord("450304", "overlap child", "passed")),
                        "450204",
                        List.of(new RunRecord("450205", "critical child", "passed"))))),
            Instant.parse("2026-05-13T00:00:00Z"));

    Map<String, Object> pipelineMap = result.toPipelineMap();
    Map<String, Object> scopes = (Map<String, Object>) pipelineMap.get("scopes");
    Map<String, Object> criticalMetrics = (Map<String, Object>) scopes.get("critical");
    assertEquals(100.0, criticalMetrics.get("passRate"));

    Map<String, Object> scopeDetails = (Map<String, Object>) pipelineMap.get("scopeDetails");
    Map<String, Object> criticalDetails = (Map<String, Object>) scopeDetails.get("critical");
    assertEquals(List.of("450303", "450204"), criticalDetails.get("suiteRunIds"));
    assertEquals(List.of("450304", "450205"), criticalDetails.get("runIds"));

    Map<String, Object> criticalSuiteRuns = (Map<String, Object>) criticalDetails.get("suiteRuns");
    assertTrue(criticalSuiteRuns.containsKey("450303"));
    assertTrue(criticalSuiteRuns.containsKey("450204"));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void preservesInactiveScopeAsSafeZeroValuedPipelineResult() {
    GateResult result =
        new GateResult(
            "1196",
            "regressions.executionRate == 100",
            true,
            true,
            new GateMetrics(1, 1, 1, 0, 0, 0),
            List.of(new RunRecord("101", "regression", "passed")),
            Map.of("1196", List.of(new RunRecord("101", "regression", "passed"))),
            Map.of("critical", GateScopeResult.inactiveSuiteRunScope("critical")),
            Instant.parse("2026-05-13T00:00:00Z"));

    Map<String, Object> pipelineMap = result.toPipelineMap();
    Map<String, Object> scopes = (Map<String, Object>) pipelineMap.get("scopes");
    Map<String, Object> critical = (Map<String, Object>) scopes.get("critical");
    Map<String, Object> scopeDetails = (Map<String, Object>) pipelineMap.get("scopeDetails");
    Map<String, Object> criticalDetails = (Map<String, Object>) scopeDetails.get("critical");

    assertEquals(false, critical.get("active"));
    assertEquals(0.0, critical.get("executionRate"));
    assertEquals(0.0, critical.get("passRate"));
    assertEquals(false, criticalDetails.get("active"));
    assertEquals(List.of(), criticalDetails.get("suiteRunIds"));
    assertFalse(result.getScopedMetrics().containsKey("critical"));
  }

  @Test
  public void extractsIdsFromOctaneScopeQueries() {
    OctaneGateScope scope =
        new OctaneGateScope("critical", "test EQ {product_areas EQ {id EQ 1004||id=1005}}");

    assertEquals(List.of("1004", "1005"), scope.getReferencedIds());
  }

  @Test
  public void parsesSuiteRunIdsFromSuiteRunBackedScopes() {
    OctaneGateScope scope = new OctaneGateScope("critical");
    scope.setSuiteRunId("450303, 450204 450303");

    assertEquals(List.of("450303", "450204"), scope.getSuiteRunIds());
    assertTrue(scope.isSuiteRunScope());
  }

  @Test
  @SuppressWarnings("unchecked")
  public void exposesGroupedDefectRatesAndCountsInPipelineMap() {
    OctaneDefectGroup major = new OctaneDefectGroup("major");
    major.setTypes("Critical, High");
    DefectCriteriaMetrics defectMetrics =
        new DefectCriteriaMetrics(
            OctaneDefectSeveritySummary.fromDefects(
                List.of(
                    new DefectRecord("d1", "Critical", "Critical", "", "opened", "1", "", "", ""),
                    new DefectRecord("d2", "Closed low", "Low", "", "closed", "1", "", "", ""))),
            List.of(major));
    GateResult result =
        new GateResult(
            "1196",
            "defects.major < 20%",
            true,
            true,
            new GateMetrics(10, 10, 10, 0, 0, 0),
            List.of(),
            Map.of(),
            Map.of(),
            OctaneRiskHeatMap.disabled(),
            defectMetrics,
            Instant.parse("2026-05-13T00:00:00Z"));

    Map<String, Object> defects = (Map<String, Object>) result.toPipelineMap().get("defects");
    Map<String, Object> groups = (Map<String, Object>) defects.get("groups");
    Map<String, Object> majorMetrics = (Map<String, Object>) groups.get("major");
    assertEquals(2, defects.get("totalDefectsRaised"));
    assertEquals(1, defects.get("closedCount"));
    assertEquals(1, majorMetrics.get("count"));
    assertEquals(50.0, majorMetrics.get("rate"));
  }
}
