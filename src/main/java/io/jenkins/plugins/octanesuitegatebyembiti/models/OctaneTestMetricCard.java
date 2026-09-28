package io.jenkins.plugins.octanesuitegatebyembiti.models;

import io.jenkins.plugins.octanesuitegatebyembiti.utils.Util;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OctaneTestMetricCard implements Serializable {
  private static final long serialVersionUID = 1L;

  private final String key;
  private final String title;
  private final String value;
  private final String detail;
  private final String trendText;
  private final String trendTone;
  private final String icon;
  private final double progressPercent;
  private final String sparklinePoints;
  private final List<OctaneTestMetricSegment> segments;

  public OctaneTestMetricCard(
      String key,
      String title,
      String value,
      String detail,
      String trendText,
      String trendTone,
      String icon,
      double progressPercent,
      String sparklinePoints,
      List<OctaneTestMetricSegment> segments) {
    this.key = Util.trimToEmpty(key);
    this.title = Util.trimToEmpty(title);
    this.value = Util.trimToEmpty(value);
    this.detail = Util.trimToEmpty(detail);
    this.trendText = Util.trimToEmpty(trendText);
    this.trendTone = Util.trimToEmpty(trendTone);
    this.icon = Util.trimToEmpty(icon);
    this.progressPercent = Math.min(100.0, Math.max(0.0, progressPercent));
    this.sparklinePoints = Util.trimToEmpty(sparklinePoints);
    this.segments = segments == null ? List.of() : List.copyOf(segments);
  }

  public String getKey() {
    return key;
  }

  public String getTitle() {
    return title;
  }

  public String getValue() {
    return value;
  }

  public String getDetail() {
    return detail;
  }

  public String getTrendText() {
    return trendText;
  }

  public String getTrendTone() {
    return trendTone;
  }

  public String getIcon() {
    return icon;
  }

  public double getProgressPercent() {
    return progressPercent;
  }

  public String getProgressPercentText() {
    return String.format(java.util.Locale.ROOT, "%.2f", progressPercent);
  }

  public String getSparklinePoints() {
    return Util.trimToEmpty(sparklinePoints);
  }

  public List<OctaneTestMetricSegment> getSegments() {
    return segments == null ? List.of() : segments;
  }

  public boolean isSegmented() {
    return !getSegments().isEmpty();
  }

  public Map<String, Object> toMap() {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("key", key);
    values.put("title", title);
    values.put("value", value);
    values.put("detail", detail);
    values.put("trendText", trendText);
    values.put("trendTone", trendTone);
    values.put("icon", icon);
    values.put("progressPercent", progressPercent);
    values.put("sparklinePoints", sparklinePoints);
    List<Map<String, Object>> segmentValues = new ArrayList<>();
    for (OctaneTestMetricSegment segment : getSegments()) {
      segmentValues.add(segment.toMap());
    }
    values.put("segments", segmentValues);
    return values;
  }
}
