package io.jenkins.plugins.octanesuitegatebyembiti.models;

import io.jenkins.plugins.octanesuitegatebyembiti.services.CriteriaException;
import io.jenkins.plugins.octanesuitegatebyembiti.utils.Util;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class DefectCriteriaMetrics implements Serializable {
  private static final long serialVersionUID = 1L;

  private final int totalDefectsRaised;
  private final OctaneDefectSeveritySummary severitySummary;
  private final Map<String, OctaneDefectGroup> groups;

  public DefectCriteriaMetrics(
      OctaneDefectSeveritySummary severitySummary, List<OctaneDefectGroup> configuredGroups) {
    this.severitySummary =
        severitySummary == null ? OctaneDefectSeveritySummary.empty() : severitySummary;
    this.totalDefectsRaised = this.severitySummary.getTotal();
    this.groups = new LinkedHashMap<>();
    if (configuredGroups != null) {
      for (OctaneDefectGroup group : configuredGroups) {
        if (group != null) {
          groups.put(OctaneDefectGroup.normalizeName(group.getName()), group);
        }
      }
    }
  }

  public double value(String metricName) {
    String requested = Util.trimToEmpty(metricName);
    boolean countRequested = requested.toLowerCase(Locale.ROOT).endsWith("count");
    String baseName =
        countRequested ? requested.substring(0, requested.length() - "count".length()) : requested;
    int count = countFor(baseName);
    if (countRequested) {
      return count;
    }
    return rate(count);
  }

  public static boolean isPercentageMetric(String metricName) {
    return !Util.trimToEmpty(metricName).toLowerCase(Locale.ROOT).endsWith("count");
  }

  public int getTotalDefectsRaised() {
    return totalDefectsRaised;
  }

  public int getOpenCount() {
    return severitySummary.getOpenTotal();
  }

  public OctaneDefectSeveritySummary getSeveritySummary() {
    return severitySummary;
  }

  public List<OctaneDefectGroup> getConfiguredGroups() {
    return List.copyOf(groups.values());
  }

  public boolean isTypeInGroup(String groupName, String defectType) {
    OctaneDefectGroup group = groups.get(OctaneDefectGroup.normalizeName(groupName));
    String normalizedType = OctaneDefectSeveritySummary.normalizeOpenType(defectType);
    return group != null
        && !normalizedType.isEmpty()
        && group.getNormalizedTypes().contains(normalizedType);
  }

  public Map<String, Object> toMap() {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("totalDefectsRaised", totalDefectsRaised);
    values.put("openCount", getOpenCount());
    values.put("closedCount", severitySummary.getClosed());
    values.put("openRate", rate(getOpenCount()));

    Map<String, Object> severityValues = new LinkedHashMap<>();
    for (String type : OctaneDefectSeveritySummary.getOpenTypes()) {
      severityValues.put(type, metricMap(severitySummary.getOpenCount(type)));
    }
    values.put("types", severityValues);

    Map<String, Object> groupValues = new LinkedHashMap<>();
    for (OctaneDefectGroup group : groups.values()) {
      groupValues.put(group.getName(), metricMap(groupCount(group)));
    }
    values.put("groups", groupValues);
    return values;
  }

  private int countFor(String name) {
    String normalizedType = OctaneDefectSeveritySummary.normalizeOpenType(name);
    if (!normalizedType.isEmpty()) {
      return severitySummary.getOpenCount(normalizedType);
    }

    String normalizedName = OctaneDefectGroup.normalizeName(name);
    if ("open".equals(normalizedName) || "total".equals(normalizedName)) {
      return severitySummary.getOpenTotal();
    }
    OctaneDefectGroup group = groups.get(normalizedName);
    if (group != null) {
      return groupCount(group);
    }
    throw new CriteriaException("Unknown defect type or group: " + name);
  }

  private int groupCount(OctaneDefectGroup group) {
    int count = 0;
    for (String type : group.getNormalizedTypes()) {
      count += severitySummary.getOpenCount(type);
    }
    return count;
  }

  private Map<String, Object> metricMap(int count) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("count", count);
    values.put("rate", rate(count));
    return values;
  }

  private double rate(int count) {
    return totalDefectsRaised == 0 ? 0.0 : count * 100.0 / totalDefectsRaised;
  }
}
