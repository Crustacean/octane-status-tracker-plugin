package io.jenkins.plugins.octanesuitegatebyembiti.models;

import io.jenkins.plugins.octanesuitegatebyembiti.utils.Util;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

public final class OctaneTestMetricSegment implements Serializable {
  private static final long serialVersionUID = 1L;

  private final String label;
  private final String shortLabel;
  private final int count;
  private final double percentage;
  private final String severityKey;
  private final int severityRank;

  public OctaneTestMetricSegment(
      String label,
      String shortLabel,
      int count,
      double percentage,
      String severityKey,
      int severityRank) {
    this.label = Util.trimToEmpty(label);
    this.shortLabel = Util.trimToEmpty(shortLabel);
    this.count = Math.max(0, count);
    this.percentage = Math.min(100.0, Math.max(0.0, percentage));
    this.severityKey = Util.trimToEmpty(severityKey);
    this.severityRank = Math.max(0, severityRank);
  }

  public String getLabel() {
    return label;
  }

  public String getShortLabel() {
    return shortLabel;
  }

  public int getCount() {
    return count;
  }

  public double getPercentage() {
    return percentage;
  }

  public String getPercentageText() {
    return String.format(java.util.Locale.ROOT, "%.4f", percentage);
  }

  public String getSeverityKey() {
    return severityKey;
  }

  public int getSeverityRank() {
    return severityRank;
  }

  public Map<String, Object> toMap() {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("label", label);
    values.put("shortLabel", shortLabel);
    values.put("count", count);
    values.put("percentage", percentage);
    values.put("severityKey", severityKey);
    values.put("severityRank", severityRank);
    return values;
  }
}
