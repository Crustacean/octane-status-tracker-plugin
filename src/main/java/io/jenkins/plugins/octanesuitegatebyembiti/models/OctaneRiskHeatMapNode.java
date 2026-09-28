package io.jenkins.plugins.octanesuitegatebyembiti.models;

import io.jenkins.plugins.octanesuitegatebyembiti.utils.Util;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OctaneRiskHeatMapNode implements Serializable {
  private static final long serialVersionUID = 1L;

  private final String type;
  private final String label;
  private final int riskScore;
  private final int count;
  private final int defectCount;
  private final String color;
  private final List<OctaneRiskHeatMapNode> children;

  public OctaneRiskHeatMapNode(
      String type,
      String label,
      int riskScore,
      int count,
      int defectCount,
      List<OctaneRiskHeatMapNode> children) {
    this.type = Util.trimToEmpty(type);
    this.label = Util.trimToEmpty(label);
    this.riskScore = Math.max(0, Math.min(100, riskScore));
    this.count = Math.max(0, count);
    this.defectCount = Math.max(0, defectCount);
    this.color = colorForRisk(this.riskScore);
    this.children = children == null ? List.of() : List.copyOf(children);
  }

  public String getType() {
    return type;
  }

  public String getLabel() {
    return label;
  }

  public int getRiskScore() {
    return riskScore;
  }

  public int getCount() {
    return count;
  }

  public int getDefectCount() {
    return defectCount;
  }

  public String getColor() {
    return color;
  }

  public List<OctaneRiskHeatMapNode> getChildren() {
    return Collections.unmodifiableList(children);
  }

  public int getWeight() {
    int childWeight = 0;
    for (OctaneRiskHeatMapNode child : children) {
      childWeight += child.getWeight();
    }
    return Math.max(1, count + defectCount + childWeight);
  }

  public Map<String, Object> toMap() {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("type", type);
    values.put("label", label);
    values.put("riskScore", riskScore);
    values.put("count", count);
    values.put("defectCount", defectCount);
    values.put("color", color);
    List<Map<String, Object>> childValues = new ArrayList<>();
    for (OctaneRiskHeatMapNode child : children) {
      childValues.add(child.toMap());
    }
    values.put("children", childValues);
    return values;
  }

  public static String colorForRisk(int riskScore) {
    if (riskScore >= 71) {
      return "#990000";
    }
    if (riskScore >= 46) {
      return "#ffb74d";
    }
    if (riskScore >= 21) {
      return "#4391F5";
    }
    return "#009900";
  }
}
