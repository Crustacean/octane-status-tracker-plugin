package io.jenkins.plugins.octanesuitegatebyembiti.services;

import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneDefectSeveritySummary.Bucket;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneRiskHeatMap;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneRiskHeatMapNode;
import io.jenkins.plugins.octanesuitegatebyembiti.utils.Util;
import java.util.Locale;

public class OctaneRiskHeatMapRenderer {
  private static final double CENTER = 320.0;
  private static final double INNER_RADIUS = 56.0;
  private static final double RING_WIDTH = 48.0;
  private static final double GAP_DEGREES = 0.55;

  public String render(OctaneRiskHeatMap heatMap) {
    return render(heatMap, false, "");
  }

  public String render(OctaneRiskHeatMap heatMap, boolean building, String updatedAtText) {
    if (heatMap == null || !heatMap.isEnabled()) {
      return unavailable(
          "Risk heat map is disabled. Set riskHeatMap: true to fetch defect risk data.");
    }
    if (!heatMap.isAvailable() || heatMap.getRoot() == null) {
      return unavailable(heatMap.getMessage());
    }

    StringBuilder html = new StringBuilder();
    html.append("<div class=\"octane-risk-heat-map-panel-inner\">");
    html.append("<div class=\"octane-risk-heat-map-container\">");
    html.append("<svg class=\"octane-risk-heat-map\" viewBox=\"0 0 640 640\" role=\"img\" ")
        .append("aria-label=\"Octane defect risk heat map\">");
    html.append("<circle class=\"octane-risk-heat-map-center\" cx=\"320\" cy=\"320\" r=\"54\" />");
    html.append("<text class=\"octane-risk-heat-map-score\" x=\"320\" y=\"314\">")
        .append(heatMap.getRiskScore())
        .append("</text>");
    html.append("<text class=\"octane-risk-heat-map-label\" x=\"320\" y=\"342\">Risk</text>");
    renderChildren(html, heatMap.getRoot(), 0.0, 360.0, 0);
    html.append("</svg>");
    html.append("</div>");
    appendDefectSeverityBar(html, heatMap, building, updatedAtText);
    html.append("</div>");
    return html.toString();
  }

  private void appendDefectSeverityBar(
      StringBuilder html, OctaneRiskHeatMap heatMap, boolean building, String updatedAtText) {
    if (!heatMap.getDefectSeveritySummary().isVisible()) {
      return;
    }
    String lastUpdated = building ? "JUST NOW" : Util.trimToEmpty(updatedAtText);
    if (Util.isBlank(lastUpdated)) {
      lastUpdated = "UNKNOWN";
    }
    html.append("<div class=\"octane-risk-issues-container\">");
    html.append("<div class=\"octane-defect-severity-tracker\">");
    html.append("<div class=\"octane-defect-severity-bar\" role=\"list\" ")
        .append("aria-label=\"Defect severity status\">");
    for (var bucket : heatMap.getDefectSeveritySummary().getBuckets()) {
      html.append(
              "<span class=\"octane-defect-severity-segment\" role=\"listitem\" style=\"background:")
          .append(bucket.getColor())
          .append(";color:")
          .append(textColorFor(bucket, heatMap))
          .append("\"><span class=\"octane-defect-severity-count\">")
          .append(bucket.getCount())
          .append("</span><span class=\"octane-defect-severity-label\">")
          .append(escape(bucket.getLabel()))
          .append("</span></span>");
    }
    html.append("</div>");
    html.append("<div class=\"octane-defect-severity-meta\">")
        .append("<span>TOTAL ISSUES: ")
        .append(heatMap.getDefectSeveritySummary().getOpenTotal())
        .append("</span><span>LAST UPDATED: ")
        .append(escape(lastUpdated))
        .append("</span></div>");
    html.append("</div>");
    html.append("</div>");
  }

  private String textColorFor(Bucket bucket, OctaneRiskHeatMap heatMap) {
    String label = bucket.getLabel().toLowerCase(Locale.ENGLISH);
    if (label.equals("medium") || label.equals("low") || label.equals("unspecified")) {
      return "#000000";
    }
    if (label.equals("closed") && heatMap.getDefectSeveritySummary().isAllClosed()) {
      return "#000000";
    }
    return "#ffffff";
  }

  private String unavailable(String message) {
    String text = Util.isBlank(message) ? "Risk heat map is unavailable." : message;
    return "<div class=\"octane-risk-heat-map-empty\">" + escape(text) + "</div>";
  }

  private void renderChildren(
      StringBuilder html,
      OctaneRiskHeatMapNode parent,
      double startAngle,
      double endAngle,
      int depth) {
    if (parent.getChildren().isEmpty() || depth >= 5) {
      return;
    }
    int totalWeight = 0;
    for (OctaneRiskHeatMapNode child : parent.getChildren()) {
      totalWeight += child.getWeight();
    }
    if (totalWeight <= 0) {
      return;
    }

    double cursor = startAngle;
    for (OctaneRiskHeatMapNode child : parent.getChildren()) {
      double span = (endAngle - startAngle) * child.getWeight() / totalWeight;
      double childStart = cursor;
      double childEnd = cursor + span;
      appendSlice(html, child, childStart, childEnd, depth);
      renderChildren(html, child, childStart, childEnd, depth + 1);
      cursor = childEnd;
    }
  }

  private void appendSlice(
      StringBuilder html,
      OctaneRiskHeatMapNode node,
      double startAngle,
      double endAngle,
      int depth) {
    double innerRadius = INNER_RADIUS + (depth * RING_WIDTH);
    double outerRadius = innerRadius + RING_WIDTH - 2.0;
    double paddedStart = startAngle + GAP_DEGREES;
    double paddedEnd = endAngle - GAP_DEGREES;
    if (paddedEnd <= paddedStart) {
      paddedStart = startAngle;
      paddedEnd = endAngle;
    }

    html.append("<path class=\"octane-risk-heat-map-slice\" d=\"")
        .append(pathForSlice(innerRadius, outerRadius, paddedStart, paddedEnd))
        .append("\" fill=\"")
        .append(node.getColor())
        .append("\"><title>")
        .append(escape(titleFor(node)))
        .append("</title></path>");
  }

  private String titleFor(OctaneRiskHeatMapNode node) {
    return node.getLabel()
        + " | risk "
        + node.getRiskScore()
        + " | tests "
        + node.getCount()
        + " | defects "
        + node.getDefectCount();
  }

  private String pathForSlice(
      double innerRadius, double outerRadius, double startAngle, double endAngle) {
    double safeEnd = Math.min(endAngle, startAngle + 359.99);
    Point outerStart = point(outerRadius, startAngle);
    Point outerEnd = point(outerRadius, safeEnd);
    Point innerEnd = point(innerRadius, safeEnd);
    Point innerStart = point(innerRadius, startAngle);
    int largeArc = safeEnd - startAngle > 180 ? 1 : 0;
    return String.format(
        Locale.ENGLISH,
        "M %.3f %.3f A %.3f %.3f 0 %d 1 %.3f %.3f " + "L %.3f %.3f A %.3f %.3f 0 %d 0 %.3f %.3f Z",
        outerStart.x,
        outerStart.y,
        outerRadius,
        outerRadius,
        largeArc,
        outerEnd.x,
        outerEnd.y,
        innerEnd.x,
        innerEnd.y,
        innerRadius,
        innerRadius,
        largeArc,
        innerStart.x,
        innerStart.y);
  }

  private Point point(double radius, double angleDegrees) {
    double radians = Math.toRadians(angleDegrees - 90.0);
    return new Point(CENTER + (radius * Math.cos(radians)), CENTER + (radius * Math.sin(radians)));
  }

  private String escape(String value) {
    String escaped = Util.trimToEmpty(value);
    return escaped
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }

  private static final class Point {
    private final double x;
    private final double y;

    private Point(double x, double y) {
      this.x = x;
      this.y = y;
    }
  }
}
