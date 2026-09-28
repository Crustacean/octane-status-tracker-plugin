package io.jenkins.plugins.octanesuitegatebyembiti.models;

import io.jenkins.plugins.octanesuitegatebyembiti.services.CriteriaException;
import io.jenkins.plugins.octanesuitegatebyembiti.utils.Util;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public class MetricsContext implements Serializable {
  private static final long serialVersionUID = 1L;

  private final GateMetrics regressionMetrics;
  private final GateMetrics totalMetrics;
  private final Map<String, GateMetrics> scopes;
  private final DefectCriteriaMetrics defectMetrics;

  public MetricsContext(GateMetrics regressionMetrics, Map<String, GateMetrics> scopes) {
    this(regressionMetrics, scopes, null);
  }

  public MetricsContext(
      GateMetrics regressionMetrics,
      Map<String, GateMetrics> scopes,
      DefectCriteriaMetrics defectMetrics) {
    this(regressionMetrics, scopes, defectMetrics, regressionMetrics);
  }

  public MetricsContext(
      GateMetrics regressionMetrics,
      Map<String, GateMetrics> scopes,
      DefectCriteriaMetrics defectMetrics,
      GateMetrics totalMetrics) {
    this.regressionMetrics = regressionMetrics;
    this.totalMetrics = totalMetrics == null ? regressionMetrics : totalMetrics;
    this.scopes = new LinkedHashMap<>(scopes);
    this.defectMetrics = defectMetrics;
  }

  public double value(String metricReference) {
    String trimmed = Util.trimToEmpty(metricReference);
    int dot = trimmed.indexOf('.');
    if (dot < 0) {
      if (isTotalAlias(trimmed)) {
        return totalMetrics.value(trimmed);
      }
      return regressionMetrics.value(trimmed);
    }

    String scope = trimmed.substring(0, dot);
    String metric = trimmed.substring(dot + 1);
    if ("regressions".equalsIgnoreCase(scope) || "regression".equalsIgnoreCase(scope)) {
      return regressionMetrics.value(metric);
    }
    if ("total".equalsIgnoreCase(scope)) {
      return totalMetrics.value(metric);
    }
    if ("defects".equalsIgnoreCase(scope)) {
      if (defectMetrics == null) {
        throw new CriteriaException("Defect metrics are unavailable.");
      }
      return defectMetrics.value(metric);
    }

    GateMetrics scopedMetrics = findScope(scope);
    if (scopedMetrics == null) {
      throw new CriteriaException("Unknown scope: " + scope);
    }
    return scopedMetrics.value(metric);
  }

  public boolean isPercentageMetric(String metricReference) {
    String trimmed = Util.trimToEmpty(metricReference);
    int dot = trimmed.indexOf('.');
    if (dot < 0) {
      return GateMetrics.isPercentageMetric(trimmed);
    }

    String scope = trimmed.substring(0, dot);
    String metric = trimmed.substring(dot + 1);
    if ("defects".equalsIgnoreCase(scope)) {
      return DefectCriteriaMetrics.isPercentageMetric(metric);
    }
    return GateMetrics.isPercentageMetric(metric);
  }

  private boolean isTotalAlias(String metricReference) {
    String normalized =
        Util.trimToEmpty(metricReference)
            .replace("_", "")
            .replace("-", "")
            .toLowerCase(Locale.ROOT);
    return "testsexecuted".equals(normalized)
        || "testsrun".equals(normalized)
        || "testsresolved".equals(normalized)
        || "totaltests".equals(normalized)
        || "executionpercentage".equals(normalized)
        || "completionpercentage".equals(normalized);
  }

  private GateMetrics findScope(String requestedScope) {
    for (Map.Entry<String, GateMetrics> entry : scopes.entrySet()) {
      if (entry.getKey().equalsIgnoreCase(requestedScope)) {
        return entry.getValue();
      }
    }
    return null;
  }
}
