package io.jenkins.plugins.octanesuitegatebyembiti.models;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class CriteriaEvaluation implements Serializable {
  private static final long serialVersionUID = 1L;

  private final boolean available;
  private final boolean passed;
  private final List<CriteriaComparisonEvaluation> comparisons;

  private CriteriaEvaluation(
      boolean available, boolean passed, List<CriteriaComparisonEvaluation> comparisons) {
    this.available = available;
    this.passed = passed;
    this.comparisons = List.copyOf(comparisons);
  }

  public static CriteriaEvaluation available(
      boolean passed, List<CriteriaComparisonEvaluation> comparisons) {
    return new CriteriaEvaluation(true, passed, comparisons);
  }

  public static CriteriaEvaluation unavailable() {
    return new CriteriaEvaluation(false, false, List.of());
  }

  public boolean isAvailable() {
    return available;
  }

  public boolean isPassed() {
    return passed;
  }

  public List<CriteriaComparisonEvaluation> getComparisons() {
    return comparisons;
  }

  public Map<String, Object> toMap() {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("available", available);
    value.put("passed", passed);
    List<Map<String, Object>> comparisonValues = new ArrayList<>(comparisons.size());
    for (CriteriaComparisonEvaluation comparison : comparisons) {
      comparisonValues.add(Objects.requireNonNull(comparison).toMap());
    }
    value.put("comparisons", comparisonValues);
    return value;
  }
}
