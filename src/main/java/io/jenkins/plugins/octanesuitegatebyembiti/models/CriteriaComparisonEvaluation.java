package io.jenkins.plugins.octanesuitegatebyembiti.models;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

public final class CriteriaComparisonEvaluation implements Serializable {
  private static final long serialVersionUID = 1L;

  private final String metricReference;
  private final String operator;
  private final double expectedValue;
  private final double actualValue;
  private final boolean percentage;
  private final boolean passed;

  public CriteriaComparisonEvaluation(
      String metricReference,
      String operator,
      double expectedValue,
      double actualValue,
      boolean percentage,
      boolean passed) {
    this.metricReference = metricReference;
    this.operator = operator;
    this.expectedValue = expectedValue;
    this.actualValue = actualValue;
    this.percentage = percentage;
    this.passed = passed;
  }

  public String getMetricReference() {
    return metricReference;
  }

  public String getOperator() {
    return operator;
  }

  public double getExpectedValue() {
    return expectedValue;
  }

  public double getActualValue() {
    return actualValue;
  }

  public boolean isPercentage() {
    return percentage;
  }

  public boolean isPassed() {
    return passed;
  }

  public String getCriterionLabel() {
    return metricReference + " " + operator + " " + formatValue(expectedValue);
  }

  public String getActualLabel() {
    return formatValue(actualValue);
  }

  public String getResultLabel() {
    return passed ? "OK" : "NOT OK";
  }

  public Map<String, Object> toMap() {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("metric", metricReference);
    value.put("operator", operator);
    value.put("expected", expectedValue);
    value.put("actual", actualValue);
    value.put("percentage", percentage);
    value.put("passed", passed);
    value.put("criterion", getCriterionLabel());
    value.put("actualLabel", getActualLabel());
    value.put("result", getResultLabel());
    return value;
  }

  private String formatValue(double value) {
    double normalized = Math.abs(value) < 0.000001 ? 0.0 : value;
    String formatted =
        BigDecimal.valueOf(normalized)
            .setScale(2, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString();
    return percentage ? formatted + "%" : formatted;
  }
}
