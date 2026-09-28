package io.jenkins.plugins.octanesuitegatebyembiti.models;

import io.jenkins.plugins.octanesuitegatebyembiti.entities.DefectRecord;
import io.jenkins.plugins.octanesuitegatebyembiti.utils.Util;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class OctaneDefectSeveritySummary implements Serializable {
  private static final long serialVersionUID = 1L;

  private static final List<String> OPEN_TYPES =
      List.of("critical", "veryHigh", "high", "medium", "low", "unspecified");

  private static final String EMPTY_COLOR = "#AEAFB1";
  private static final String CLOSED_COLOR = "#5A5B5B";
  private static final String ALL_CLOSED_COLOR = "#7BE5B3";

  private final int critical;
  private final int veryHigh;
  private final int high;
  private final int medium;
  private final int low;
  private final int unspecified;
  private final int closed;
  private final int closedCritical;
  private final int closedVeryHigh;
  private final int closedHigh;
  private final int closedMedium;
  private final int closedLow;
  private final int closedUnspecified;

  private OctaneDefectSeveritySummary(
      int critical,
      int veryHigh,
      int high,
      int medium,
      int low,
      int unspecified,
      int closed,
      int closedCritical,
      int closedVeryHigh,
      int closedHigh,
      int closedMedium,
      int closedLow,
      int closedUnspecified) {
    this.critical = Math.max(0, critical);
    this.veryHigh = Math.max(0, veryHigh);
    this.high = Math.max(0, high);
    this.medium = Math.max(0, medium);
    this.low = Math.max(0, low);
    this.unspecified = Math.max(0, unspecified);
    this.closed = Math.max(0, closed);
    this.closedCritical = Math.max(0, closedCritical);
    this.closedVeryHigh = Math.max(0, closedVeryHigh);
    this.closedHigh = Math.max(0, closedHigh);
    this.closedMedium = Math.max(0, closedMedium);
    this.closedLow = Math.max(0, closedLow);
    this.closedUnspecified = Math.max(0, closedUnspecified);
  }

  public static OctaneDefectSeveritySummary empty() {
    return new OctaneDefectSeveritySummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
  }

  public static OctaneDefectSeveritySummary fromDefects(List<DefectRecord> defects) {
    if (defects == null || defects.isEmpty()) {
      return empty();
    }

    int[] openCounts = new int[Severity.values().length];
    int[] closedCounts = new int[Severity.values().length];
    int closed = 0;
    Set<String> seenDefectIds = new LinkedHashSet<>();
    for (DefectRecord defect : defects) {
      String key = defectKey(defect);
      if (!seenDefectIds.add(key)) {
        continue;
      }
      Severity severity = Severity.from(defect);
      if (!defect.isOpen()) {
        closed++;
        closedCounts[severity.ordinal()]++;
      } else {
        openCounts[severity.ordinal()]++;
      }
    }
    return new OctaneDefectSeveritySummary(
        openCounts[Severity.CRITICAL.ordinal()],
        openCounts[Severity.VERY_HIGH.ordinal()],
        openCounts[Severity.HIGH.ordinal()],
        openCounts[Severity.MEDIUM.ordinal()],
        openCounts[Severity.LOW.ordinal()],
        openCounts[Severity.UNSPECIFIED.ordinal()],
        closed,
        closedCounts[Severity.CRITICAL.ordinal()],
        closedCounts[Severity.VERY_HIGH.ordinal()],
        closedCounts[Severity.HIGH.ordinal()],
        closedCounts[Severity.MEDIUM.ordinal()],
        closedCounts[Severity.LOW.ordinal()],
        closedCounts[Severity.UNSPECIFIED.ordinal()]);
  }

  public int getCritical() {
    return critical;
  }

  public int getVeryHigh() {
    return veryHigh;
  }

  public int getHigh() {
    return high;
  }

  public int getMedium() {
    return medium;
  }

  public int getLow() {
    return low;
  }

  public int getUnspecified() {
    return unspecified;
  }

  public int getClosed() {
    return closed;
  }

  public int getClosedCount(String defectType) {
    switch (normalizeOpenType(defectType)) {
      case "critical":
        return closedCritical;
      case "veryhigh":
        return closedVeryHigh;
      case "high":
        return closedHigh;
      case "medium":
        return closedMedium;
      case "low":
        return closedLow;
      case "unspecified":
        return closedUnspecified + getUnclassifiedClosedCount();
      default:
        throw new IllegalArgumentException("Unknown closed defect type: " + defectType);
    }
  }

  public int getTotalCount(String defectType) {
    return getOpenCount(defectType) + getClosedCount(defectType);
  }

  public int getOpenTotal() {
    return critical + veryHigh + high + medium + low + unspecified;
  }

  public int getOpenCount(String defectType) {
    switch (normalizeOpenType(defectType)) {
      case "critical":
        return critical;
      case "veryhigh":
        return veryHigh;
      case "high":
        return high;
      case "medium":
        return medium;
      case "low":
        return low;
      case "unspecified":
        return unspecified;
      default:
        throw new IllegalArgumentException("Unknown open defect type: " + defectType);
    }
  }

  public static List<String> getOpenTypes() {
    return OPEN_TYPES;
  }

  public static String normalizeOpenType(String value) {
    String normalized =
        Util.trimToEmpty(value)
            .toLowerCase(Locale.ROOT)
            .replace(" ", "")
            .replace("_", "")
            .replace("-", "");
    switch (normalized) {
      case "critical":
      case "veryhigh":
      case "high":
      case "medium":
      case "low":
      case "unspecified":
        return normalized;
      default:
        return "";
    }
  }

  public int getTotal() {
    return getOpenTotal() + closed;
  }

  public boolean isVisible() {
    return getTotal() > 0;
  }

  public boolean isAllClosed() {
    return closed > 0 && getOpenTotal() == 0;
  }

  public List<Bucket> getBuckets() {
    List<Bucket> buckets = new ArrayList<>();
    buckets.add(
        new Bucket("Critical", "Critical severity", critical, colorFor(critical, "#9D1D34")));
    buckets.add(
        new Bucket("Very High", "Very High severity", veryHigh, colorFor(veryHigh, "#D1334C")));
    buckets.add(new Bucket("High", "High severity", high, colorFor(high, "#ED8D25")));
    buckets.add(new Bucket("Medium", "Medium severity", medium, colorFor(medium, "#FFD700")));
    buckets.add(new Bucket("Low", "Low severity", low, colorFor(low, "#ACAF4B")));
    buckets.add(
        new Bucket(
            "Unspecified", "Unspecified severity", unspecified, colorFor(unspecified, "#D4D59F")));
    buckets.add(new Bucket("Closed", "Closed Issues", closed, closedColor()));
    return buckets;
  }

  public Map<String, Object> toMap() {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("critical", critical);
    values.put("veryHigh", veryHigh);
    values.put("high", high);
    values.put("medium", medium);
    values.put("low", low);
    values.put("unspecified", unspecified);
    values.put("closed", closed);
    values.put("openTotal", getOpenTotal());
    values.put("total", getTotal());
    values.put("allClosed", isAllClosed());
    Map<String, Object> closedBySeverity = new LinkedHashMap<>();
    for (String type : OPEN_TYPES) {
      closedBySeverity.put(type, getClosedCount(type));
    }
    values.put("closedBySeverity", closedBySeverity);
    List<Map<String, Object>> bucketValues = new ArrayList<>();
    for (Bucket bucket : getBuckets()) {
      bucketValues.add(bucket.toMap());
    }
    values.put("buckets", bucketValues);
    return values;
  }

  private static String defectKey(DefectRecord defect) {
    if (!Util.isBlank(defect.getId())) {
      return defect.getId();
    }
    return defect.getName()
        + "|"
        + defect.getSeverity()
        + "|"
        + defect.getPriority()
        + "|"
        + defect.getRunId()
        + "|"
        + defect.getTestId();
  }

  private static String colorFor(int count, String activeColor) {
    return count > 0 ? activeColor : EMPTY_COLOR;
  }

  private String closedColor() {
    if (closed <= 0) {
      return EMPTY_COLOR;
    }
    return isAllClosed() ? ALL_CLOSED_COLOR : CLOSED_COLOR;
  }

  private int getUnclassifiedClosedCount() {
    int classified =
        closedCritical + closedVeryHigh + closedHigh + closedMedium + closedLow + closedUnspecified;
    return Math.max(0, closed - classified);
  }

  public static final class Bucket implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String label;
    private final String tooltip;
    private final int count;
    private final String color;

    private Bucket(String label, String tooltip, int count, String color) {
      this.label = label;
      this.tooltip = tooltip;
      this.count = count;
      this.color = color;
    }

    public String getLabel() {
      return label;
    }

    public int getCount() {
      return count;
    }

    public String getTooltip() {
      return tooltip;
    }

    public String getColor() {
      return color;
    }

    private Map<String, Object> toMap() {
      Map<String, Object> values = new LinkedHashMap<>();
      values.put("label", label);
      values.put("tooltip", tooltip);
      values.put("count", count);
      values.put("color", color);
      return values;
    }
  }

  private enum Severity {
    CRITICAL,
    VERY_HIGH,
    HIGH,
    MEDIUM,
    LOW,
    UNSPECIFIED;

    private static Severity from(DefectRecord defect) {
      String signal =
          (defect.getSeverity() + " " + defect.getPriority()).toLowerCase(Locale.ENGLISH);
      for (Severity severity : values()) {
        if (severity.matches(signal)) {
          return severity;
        }
      }
      return UNSPECIFIED;
    }

    private boolean matches(String signal) {
      return switch (this) {
        case CRITICAL ->
            signal.contains("critical") || signal.contains("blocker") || signal.contains("urgent");
        case VERY_HIGH -> signal.contains("very high") || signal.contains("very_high");
        case HIGH -> signal.contains("high");
        case MEDIUM -> signal.contains("medium") || signal.contains("major");
        case LOW -> signal.contains("low") || signal.contains("minor");
        case UNSPECIFIED -> false;
      };
    }
  }
}
