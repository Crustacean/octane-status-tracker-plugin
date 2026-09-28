package io.jenkins.plugins.octanesuitegatebyembiti.models;

import io.jenkins.plugins.octanesuitegatebyembiti.entities.DefectRecord;
import io.jenkins.plugins.octanesuitegatebyembiti.entities.RunRecord;
import io.jenkins.plugins.octanesuitegatebyembiti.utils.Util;
import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class GateResult implements Serializable {
  private static final long serialVersionUID = 1L;
  static final int PIPELINE_DETAIL_LIMIT = 10_000;

  private final String suiteRunId;
  private final String criteria;
  private final boolean passed;
  private final boolean terminal;
  private final GateMetrics metrics;
  private final List<RunRecord> runs;
  private final Map<String, List<RunRecord>> suiteRuns;
  private final Map<String, GateScopeResult> scopedResults;
  private final OctaneRiskHeatMap riskHeatMap;
  private final DefectCriteriaMetrics defectMetrics;
  private final List<DefectRecord> defects;
  private final CriteriaEvaluation criteriaEvaluation;
  private final Instant polledAt;

  public GateResult(
      String suiteRunId,
      String criteria,
      boolean passed,
      boolean terminal,
      GateMetrics metrics,
      Map<String, GateMetrics> scopedMetrics,
      Instant polledAt) {
    this(
        suiteRunId,
        criteria,
        passed,
        terminal,
        metrics,
        List.of(),
        Map.of(),
        toScopeResults(scopedMetrics),
        OctaneRiskHeatMap.disabled(),
        polledAt);
  }

  public GateResult(
      String suiteRunId,
      String criteria,
      boolean passed,
      boolean terminal,
      GateMetrics metrics,
      List<RunRecord> runs,
      Map<String, List<RunRecord>> suiteRuns,
      Map<String, GateScopeResult> scopedResults,
      Instant polledAt) {
    this(
        suiteRunId,
        criteria,
        passed,
        terminal,
        metrics,
        runs,
        suiteRuns,
        scopedResults,
        OctaneRiskHeatMap.disabled(),
        polledAt);
  }

  public GateResult(
      String suiteRunId,
      String criteria,
      boolean passed,
      boolean terminal,
      GateMetrics metrics,
      List<RunRecord> runs,
      Map<String, List<RunRecord>> suiteRuns,
      Map<String, GateScopeResult> scopedResults,
      OctaneRiskHeatMap riskHeatMap,
      Instant polledAt) {
    this(
        suiteRunId,
        criteria,
        passed,
        terminal,
        metrics,
        runs,
        suiteRuns,
        scopedResults,
        riskHeatMap,
        new DefectCriteriaMetrics(OctaneDefectSeveritySummary.empty(), List.of()),
        polledAt);
  }

  public GateResult(
      String suiteRunId,
      String criteria,
      boolean passed,
      boolean terminal,
      GateMetrics metrics,
      List<RunRecord> runs,
      Map<String, List<RunRecord>> suiteRuns,
      Map<String, GateScopeResult> scopedResults,
      OctaneRiskHeatMap riskHeatMap,
      DefectCriteriaMetrics defectMetrics,
      Instant polledAt) {
    this(
        suiteRunId,
        criteria,
        passed,
        terminal,
        metrics,
        runs,
        suiteRuns,
        scopedResults,
        riskHeatMap,
        defectMetrics,
        CriteriaEvaluation.unavailable(),
        polledAt);
  }

  public GateResult(
      String suiteRunId,
      String criteria,
      boolean passed,
      boolean terminal,
      GateMetrics metrics,
      List<RunRecord> runs,
      Map<String, List<RunRecord>> suiteRuns,
      Map<String, GateScopeResult> scopedResults,
      OctaneRiskHeatMap riskHeatMap,
      DefectCriteriaMetrics defectMetrics,
      CriteriaEvaluation criteriaEvaluation,
      Instant polledAt) {
    this(
        suiteRunId,
        criteria,
        passed,
        terminal,
        metrics,
        runs,
        suiteRuns,
        scopedResults,
        riskHeatMap,
        defectMetrics,
        List.of(),
        criteriaEvaluation,
        polledAt);
  }

  public GateResult(
      String suiteRunId,
      String criteria,
      boolean passed,
      boolean terminal,
      GateMetrics metrics,
      List<RunRecord> runs,
      Map<String, List<RunRecord>> suiteRuns,
      Map<String, GateScopeResult> scopedResults,
      OctaneRiskHeatMap riskHeatMap,
      DefectCriteriaMetrics defectMetrics,
      List<DefectRecord> defects,
      CriteriaEvaluation criteriaEvaluation,
      Instant polledAt) {
    this.suiteRunId = suiteRunId;
    this.criteria = criteria;
    this.passed = passed;
    this.terminal = terminal;
    this.metrics = metrics;
    this.runs = List.copyOf(runs);
    this.suiteRuns = copySuiteRuns(suiteRuns);
    this.scopedResults = new LinkedHashMap<>(scopedResults);
    this.riskHeatMap = riskHeatMap == null ? OctaneRiskHeatMap.disabled() : riskHeatMap;
    this.defectMetrics =
        defectMetrics == null
            ? new DefectCriteriaMetrics(OctaneDefectSeveritySummary.empty(), List.of())
            : defectMetrics;
    this.defects = defects == null ? List.of() : List.copyOf(defects);
    this.criteriaEvaluation =
        criteriaEvaluation == null ? CriteriaEvaluation.unavailable() : criteriaEvaluation;
    this.polledAt = polledAt;
  }

  public String getSuiteRunId() {
    return suiteRunId;
  }

  public String getCriteria() {
    return criteria;
  }

  public boolean isPassed() {
    return passed;
  }

  public boolean isTerminal() {
    return terminal;
  }

  public GateMetrics getMetrics() {
    return metrics;
  }

  public boolean isRegressionEvaluationEnabled() {
    return !Util.splitIdList(suiteRunId).isEmpty();
  }

  public List<RunRecord> getRuns() {
    return runs;
  }

  public Map<String, List<RunRecord>> getSuiteRuns() {
    return copySuiteRuns(suiteRuns);
  }

  public Map<String, GateMetrics> getScopedMetrics() {
    Map<String, GateMetrics> scopedMetrics = new LinkedHashMap<>();
    for (Map.Entry<String, GateScopeResult> entry : scopedResults.entrySet()) {
      if (entry.getValue().isActive()) {
        scopedMetrics.put(entry.getKey(), entry.getValue().getMetrics());
      }
    }
    return scopedMetrics;
  }

  public Map<String, GateScopeResult> getScopedResults() {
    return new LinkedHashMap<>(scopedResults);
  }

  public Instant getPolledAt() {
    return polledAt;
  }

  public OctaneRiskHeatMap getRiskHeatMap() {
    return riskHeatMap;
  }

  public DefectCriteriaMetrics getDefectMetrics() {
    return defectMetrics == null
        ? new DefectCriteriaMetrics(OctaneDefectSeveritySummary.empty(), List.of())
        : defectMetrics;
  }

  public List<DefectRecord> getDefects() {
    return defects == null ? List.of() : defects;
  }

  public CriteriaEvaluation getCriteriaEvaluation() {
    return criteriaEvaluation == null ? CriteriaEvaluation.unavailable() : criteriaEvaluation;
  }

  public Map<String, Object> toPipelineMap() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("suiteRunId", suiteRunId);
    result.put("suiteRunIds", List.copyOf(Util.splitIdList(suiteRunId)));
    result.put("criteria", criteria);
    result.put("passed", passed);
    result.put("terminal", terminal);
    result.put("regressionEvaluationEnabled", isRegressionEvaluationEnabled());
    result.put("polledAt", polledAt.toString());
    result.put("metrics", metrics.toMap());
    result.put("regressions", pipelineMetrics(metrics, isRegressionEvaluationEnabled()));

    Map<String, Object> scopes = new LinkedHashMap<>();
    Map<String, Object> scopeDetails = new LinkedHashMap<>();
    int perScopeDetailLimit =
        scopedResults.isEmpty() ? 0 : Math.max(1, PIPELINE_DETAIL_LIMIT / scopedResults.size());
    for (Map.Entry<String, GateScopeResult> entry : scopedResults.entrySet()) {
      GateScopeResult scopeResult = entry.getValue();
      scopes.put(entry.getKey(), pipelineMetrics(scopeResult.getMetrics(), scopeResult.isActive()));
      scopeDetails.put(entry.getKey(), scopeResult.toMap(perScopeDetailLimit));
    }
    result.put("scopes", scopes);
    result.put("scopeDetails", scopeDetails);
    result.put("runCount", runs.size());
    result.put("suiteRunCount", suiteRuns.size());
    result.put("detailsTruncated", runs.size() > PIPELINE_DETAIL_LIMIT);
    result.put("runs", toRunMaps(runs, PIPELINE_DETAIL_LIMIT));
    result.put(
        "suiteRuns", runs.size() > PIPELINE_DETAIL_LIMIT ? Map.of() : toSuiteRunMaps(suiteRuns));
    result.put("riskHeatMap", riskHeatMap.toMap());
    result.put("defects", getDefectMetrics().toMap());
    result.put("criteriaEvaluation", getCriteriaEvaluation().toMap());
    return result;
  }

  private static Map<String, Object> pipelineMetrics(GateMetrics metrics, boolean active) {
    Map<String, Object> values = new LinkedHashMap<>(metrics.toMap());
    values.put("active", active);
    return values;
  }

  private static Map<String, GateScopeResult> toScopeResults(
      Map<String, GateMetrics> scopedMetrics) {
    Map<String, GateScopeResult> scopedResults = new LinkedHashMap<>();
    for (Map.Entry<String, GateMetrics> entry : scopedMetrics.entrySet()) {
      scopedResults.put(
          entry.getKey(),
          new GateScopeResult(entry.getKey(), "", List.of(), entry.getValue(), List.of()));
    }
    return scopedResults;
  }

  static Map<String, List<RunRecord>> copySuiteRuns(Map<String, List<RunRecord>> suiteRuns) {
    Map<String, List<RunRecord>> copy = new LinkedHashMap<>();
    for (Map.Entry<String, List<RunRecord>> entry : suiteRuns.entrySet()) {
      copy.put(entry.getKey(), List.copyOf(entry.getValue()));
    }
    return copy;
  }

  static List<Map<String, Object>> toRunMaps(List<RunRecord> runs) {
    return toRunMaps(runs, Integer.MAX_VALUE);
  }

  private static List<Map<String, Object>> toRunMaps(List<RunRecord> runs, int limit) {
    List<Map<String, Object>> runMaps = new ArrayList<>();
    int end = Math.min(runs.size(), Math.max(0, limit));
    for (int index = 0; index < end; index++) {
      runMaps.add(runs.get(index).toMap());
    }
    return runMaps;
  }

  static Map<String, Object> toSuiteRunMaps(Map<String, List<RunRecord>> suiteRuns) {
    Map<String, Object> suiteRunMaps = new LinkedHashMap<>();
    for (Map.Entry<String, List<RunRecord>> entry : suiteRuns.entrySet()) {
      suiteRunMaps.put(entry.getKey(), toRunMaps(entry.getValue()));
    }
    return suiteRunMaps;
  }
}
