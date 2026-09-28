package io.jenkins.plugins.octanesuitegatebyembiti.services;

import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneExecutionStatusDistribution;

public class OctaneExecutionStatusDistributionRenderer {
  public String render(OctaneExecutionStatusDistribution distribution) {
    OctaneExecutionStatusDistribution safeDistribution =
        distribution == null
            ? OctaneExecutionStatusDistribution.fromStatusCounts(java.util.List.of())
            : distribution;
    StringBuilder html = new StringBuilder();
    html.append("<div class=\"octane-execution-breakdown\">");
    if (safeDistribution.isEmpty()) {
      html.append("<div class=\"octane-execution-breakdown-empty\">")
          .append("Execution status distribution will appear after Octane returns test runs.")
          .append("</div>");
      html.append("</div>");
      return html.toString();
    }

    html.append("<div class=\"octane-execution-breakdown-content\" data-status-count=\"")
        .append(safeDistribution.getStatusCount())
        .append("\">")
        .append("<div class=\"octane-execution-half-pie-wrap\">")
        .append("<svg class=\"octane-execution-half-pie\" viewBox=\"0 36 320 160\" role=\"img\"")
        .append(" preserveAspectRatio=\"xMidYMid meet\"")
        .append(" aria-label=\"Execution status distribution\">");
    for (OctaneExecutionStatusDistribution.Segment segment : safeDistribution.getSegments()) {
      html.append("<path class=\"octane-execution-half-pie-segment\" d=\"")
          .append(escape(segment.getPath()))
          .append("\" stroke=\"")
          .append(escape(segment.getColor()))
          .append("\"><title>")
          .append(escape(segment.getTitle()))
          .append("</title></path>");
    }
    html.append("<text class=\"octane-execution-half-pie-total\" x=\"160\" y=\"146\">")
        .append(safeDistribution.getTotal())
        .append("</text>")
        .append("<text class=\"octane-execution-half-pie-label\" x=\"160\" y=\"172\">")
        .append("Testcases</text></svg></div>");

    html.append("<div class=\"octane-execution-breakdown-list\">");
    for (OctaneExecutionStatusDistribution.Segment segment : safeDistribution.getSegments()) {
      html.append("<div class=\"octane-execution-breakdown-row\">")
          .append("<span class=\"octane-execution-breakdown-swatch\" style=\"background:")
          .append(escape(segment.getColor()))
          .append(";\"></span>")
          .append("<span class=\"octane-execution-breakdown-label\">")
          .append(escape(segment.getLabel()))
          .append("</span>")
          .append("<span class=\"octane-execution-breakdown-metric\">")
          .append(segment.getCount())
          .append(" <span class=\"octane-execution-breakdown-pipe\">|</span> ")
          .append(escape(segment.getPercentageLabel()))
          .append("</span></div>");
    }
    html.append("</div></div></div>");
    return html.toString();
  }

  private String escape(String value) {
    if (value == null) {
      return "";
    }
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }
}
