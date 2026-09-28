package io.jenkins.plugins.octanesuitegatebyembiti.models;

import io.jenkins.plugins.octanesuitegatebyembiti.entities.RunRecord;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class GateScopeResult implements Serializable {
  private static final long serialVersionUID = 1L;

  private final String name;
  private final String query;
  private final List<String> queryIds;
  private final String suiteRunId;
  private final List<String> suiteRunIds;
  private final GateMetrics metrics;
  private final List<RunRecord> runs;
  private final Map<String, List<RunRecord>> suiteRuns;
  private final boolean inactive;

  public GateScopeResult(
      String name, String query, List<String> queryIds, GateMetrics metrics, List<RunRecord> runs) {
    this(name, query, queryIds, "", List.of(), metrics, runs, Map.of());
  }

  public GateScopeResult(
      String name,
      String query,
      List<String> queryIds,
      String suiteRunId,
      List<String> suiteRunIds,
      GateMetrics metrics,
      List<RunRecord> runs,
      Map<String, List<RunRecord>> suiteRuns) {
    this(name, query, queryIds, suiteRunId, suiteRunIds, metrics, runs, suiteRuns, false);
  }

  private GateScopeResult(
      String name,
      String query,
      List<String> queryIds,
      String suiteRunId,
      List<String> suiteRunIds,
      GateMetrics metrics,
      List<RunRecord> runs,
      Map<String, List<RunRecord>> suiteRuns,
      boolean inactive) {
    this.name = name;
    this.query = query;
    this.queryIds = List.copyOf(queryIds);
    this.suiteRunId = suiteRunId;
    this.suiteRunIds = List.copyOf(suiteRunIds);
    this.metrics = metrics;
    this.runs = List.copyOf(runs);
    this.suiteRuns = GateResult.copySuiteRuns(suiteRuns);
    this.inactive = inactive;
  }

  public static GateScopeResult inactiveSuiteRunScope(String name) {
    return new GateScopeResult(
        name,
        "",
        List.of(),
        "",
        List.of(),
        new GateMetrics(0, 0, 0, 0, 0, 0),
        List.of(),
        Map.of(),
        true);
  }

  public String getName() {
    return name;
  }

  public String getQuery() {
    return query;
  }

  public List<String> getQueryIds() {
    return queryIds;
  }

  public String getSuiteRunId() {
    return suiteRunId;
  }

  public List<String> getSuiteRunIds() {
    return suiteRunIds;
  }

  public GateMetrics getMetrics() {
    return metrics;
  }

  public List<RunRecord> getRuns() {
    return runs;
  }

  public Map<String, List<RunRecord>> getSuiteRuns() {
    return GateResult.copySuiteRuns(suiteRuns);
  }

  public boolean isActive() {
    return !inactive;
  }

  public boolean isSuiteRunScope() {
    return !suiteRunIds.isEmpty();
  }

  public Map<String, Object> toMap() {
    return toMap(GateResult.PIPELINE_DETAIL_LIMIT);
  }

  Map<String, Object> toMap(int detailLimit) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("name", name);
    values.put("query", query);
    values.put("queryIds", queryIds);
    values.put("suiteRunId", suiteRunId);
    values.put("suiteRunIds", suiteRunIds);
    values.put("active", isActive());
    values.put("metrics", metrics.toMap());

    int safeLimit = Math.min(GateResult.PIPELINE_DETAIL_LIMIT, Math.max(0, detailLimit));
    int detailCount = Math.min(runs.size(), safeLimit);
    List<String> runIds = new ArrayList<>(detailCount);
    List<Map<String, Object>> runMaps = new ArrayList<>(detailCount);
    for (int index = 0; index < detailCount; index++) {
      RunRecord run = runs.get(index);
      RunRecord nonNullRun = Objects.requireNonNull(run);
      runIds.add(nonNullRun.getId());
      runMaps.add(nonNullRun.toMap());
    }
    values.put("runIds", runIds);
    values.put("runs", runMaps);
    values.put("runCount", runs.size());
    values.put("detailsTruncated", runs.size() > safeLimit);
    values.put(
        "suiteRuns", runs.size() > safeLimit ? Map.of() : GateResult.toSuiteRunMaps(suiteRuns));
    return values;
  }
}
