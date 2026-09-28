package io.jenkins.plugins.octanesuitegatebyembiti.models;

import io.jenkins.plugins.octanesuitegatebyembiti.entities.RunRecord;
import io.jenkins.plugins.octanesuitegatebyembiti.utils.Util;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Preserves the first valid parent-suite owner independently of mutable child execution actors. */
public final class OctaneSuiteAttributions {
  private static final String UNASSIGNED = "unassigned";

  private OctaneSuiteAttributions() {}

  public static Map<String, String> mergeFirstValid(
      Map<String, String> persisted, GateResult current) {
    Map<String, String> merged = validCopy(persisted);
    capture(merged, current == null ? Map.of() : current.getSuiteRuns());
    if (current != null) {
      for (GateScopeResult scope : current.getScopedResults().values()) {
        if (scope.isActive()) {
          capture(merged, scope.getSuiteRuns());
        }
      }
    }
    return immutableOrderedMap(merged);
  }

  public static GateResult apply(GateResult result, Map<String, String> attributions) {
    if (result == null) {
      return null;
    }
    Map<String, String> stable = validCopy(attributions);
    if (stable.isEmpty()) {
      return result;
    }

    ReconciledRuns regressions = reconcileSuiteRuns(result.getSuiteRuns(), stable);
    List<RunRecord> regressionRuns = reconcileRuns(result.getRuns(), regressions.ownersByRunId);
    Map<String, GateScopeResult> scopes = new LinkedHashMap<>();
    for (Map.Entry<String, GateScopeResult> entry : result.getScopedResults().entrySet()) {
      scopes.put(entry.getKey(), reconcileScope(entry.getValue(), stable));
    }

    return new GateResult(
        result.getSuiteRunId(),
        result.getCriteria(),
        result.isPassed(),
        result.isTerminal(),
        result.getMetrics(),
        regressionRuns,
        regressions.suiteRuns,
        scopes,
        result.getRiskHeatMap(),
        result.getDefectMetrics(),
        result.getDefects(),
        result.getCriteriaEvaluation(),
        result.getPolledAt());
  }

  public static boolean isValidOwner(String owner) {
    String value = Util.trimToEmpty(owner);
    if (value.isEmpty()) {
      return false;
    }
    String normalized = value.toLowerCase(java.util.Locale.ROOT);
    return !normalized.equals(UNASSIGNED) && !normalized.startsWith(UNASSIGNED + " (");
  }

  private static GateScopeResult reconcileScope(
      GateScopeResult scope, Map<String, String> attributions) {
    if (scope == null || !scope.isActive() || scope.getSuiteRuns().isEmpty()) {
      return scope;
    }
    ReconciledRuns reconciled = reconcileSuiteRuns(scope.getSuiteRuns(), attributions);
    return new GateScopeResult(
        scope.getName(),
        scope.getQuery(),
        scope.getQueryIds(),
        scope.getSuiteRunId(),
        scope.getSuiteRunIds(),
        scope.getMetrics(),
        reconcileRuns(scope.getRuns(), reconciled.ownersByRunId),
        reconciled.suiteRuns);
  }

  private static ReconciledRuns reconcileSuiteRuns(
      Map<String, List<RunRecord>> suiteRuns, Map<String, String> attributions) {
    Map<String, List<RunRecord>> reconciled = new LinkedHashMap<>();
    Map<String, String> ownersByRunId = new LinkedHashMap<>();
    for (Map.Entry<String, List<RunRecord>> entry : suiteRuns.entrySet()) {
      String owner = attributions.get(entry.getKey());
      List<RunRecord> runs = new ArrayList<>(entry.getValue().size());
      for (RunRecord run : entry.getValue()) {
        RunRecord attributed = isValidOwner(owner) ? run.withSuiteOwnerName(owner) : run;
        runs.add(attributed);
        if (!Util.isBlank(attributed.getId()) && isValidOwner(attributed.getSuiteOwnerName())) {
          ownersByRunId.putIfAbsent(attributed.getId(), attributed.getSuiteOwnerName());
        }
      }
      reconciled.put(entry.getKey(), List.copyOf(runs));
    }
    return new ReconciledRuns(immutableOrderedMap(reconciled), immutableOrderedMap(ownersByRunId));
  }

  private static List<RunRecord> reconcileRuns(
      List<RunRecord> runs, Map<String, String> ownersByRunId) {
    List<RunRecord> reconciled = new ArrayList<>(runs.size());
    for (RunRecord run : runs) {
      String owner = ownersByRunId.get(run.getId());
      reconciled.add(isValidOwner(owner) ? run.withSuiteOwnerName(owner) : run);
    }
    return List.copyOf(reconciled);
  }

  private static void capture(
      Map<String, String> attributions, Map<String, List<RunRecord>> suiteRuns) {
    for (Map.Entry<String, List<RunRecord>> entry : suiteRuns.entrySet()) {
      String suiteRunId = Util.trimToEmpty(entry.getKey());
      if (suiteRunId.isEmpty() || attributions.containsKey(suiteRunId)) {
        continue;
      }
      for (RunRecord run : entry.getValue()) {
        if (isValidOwner(run.getSuiteOwnerName())) {
          attributions.put(suiteRunId, run.getSuiteOwnerName().trim());
          break;
        }
      }
    }
  }

  private static Map<String, String> validCopy(Map<String, String> source) {
    Map<String, String> copy = new LinkedHashMap<>();
    if (source == null) {
      return copy;
    }
    for (Map.Entry<String, String> entry : source.entrySet()) {
      String suiteRunId = Util.trimToEmpty(entry.getKey());
      String owner = Util.trimToEmpty(entry.getValue());
      if (!suiteRunId.isEmpty() && isValidOwner(owner)) {
        copy.putIfAbsent(suiteRunId, owner);
      }
    }
    return copy;
  }

  private static <K, V> Map<K, V> immutableOrderedMap(Map<K, V> source) {
    return Collections.unmodifiableMap(new LinkedHashMap<>(source));
  }

  private record ReconciledRuns(
      Map<String, List<RunRecord>> suiteRuns, Map<String, String> ownersByRunId) {}
}
