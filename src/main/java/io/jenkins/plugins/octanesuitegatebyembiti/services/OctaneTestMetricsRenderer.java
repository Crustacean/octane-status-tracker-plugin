package io.jenkins.plugins.octanesuitegatebyembiti.services;

import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneTestMetricCard;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneTestMetricSegment;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneTestMetrics;
import java.util.Locale;

public class OctaneTestMetricsRenderer {
  public String render(OctaneTestMetrics metrics) {
    OctaneTestMetrics safeMetrics = metrics == null ? OctaneTestMetrics.empty() : metrics;
    StringBuilder html = new StringBuilder();
    html.append("<div class=\"octane-test-metrics-grid\">");
    for (OctaneTestMetricCard card : safeMetrics.getCards()) {
      html.append("<article class=\"octane-test-metric-card octane-test-metric-")
          .append(escape(card.getKey()))
          .append("\" data-test-metric-key=\"")
          .append(escape(card.getKey()))
          .append("\">");
      html.append("<div class=\"octane-test-metric-heading\">")
          .append(icon(card.getIcon()))
          .append("<span>")
          .append(escape(card.getTitle()))
          .append("</span></div>");
      renderVisualization(html, card);
      html.append("<div class=\"octane-test-metric-detail\">")
          .append(escape(card.getDetail()))
          .append("</div>");
      html.append("<div class=\"octane-test-metric-trend octane-test-metric-trend-")
          .append(escape(card.getTrendTone().toLowerCase(Locale.ROOT)))
          .append("\">")
          .append(escape(card.getTrendText()))
          .append("</div>");
      html.append("</article>");
    }
    html.append("</div>");
    return html.toString();
  }

  private void renderVisualization(StringBuilder html, OctaneTestMetricCard card) {
    switch (card.getKey()) {
      case "automation-usage":
        renderAutomationUsage(html, card);
        return;
      case "success-rate":
        renderSuccessRate(html, card);
        return;
      case "execution":
        renderExecution(html, card);
        return;
      case "defects":
        renderDefects(html, card);
        return;
      default:
        renderValue(html, card);
    }
  }

  private void renderAutomationUsage(StringBuilder html, OctaneTestMetricCard card) {
    html.append("<div class=\"octane-test-metric-visual octane-test-metric-visual-automation\">");
    renderValue(html, card);
    html.append("<div class=\"octane-test-metric-automation-segments")
        .append(card.isSegmented() ? "" : " octane-test-metric-automation-segments-empty")
        .append("\" data-test-metric-segments=\"true\" role=\"img\" aria-label=\"")
        .append(escape(card.getDetail()))
        .append("\"><div class=\"octane-test-metric-automation-track\" aria-hidden=\"true\">");
    for (OctaneTestMetricSegment segment : card.getSegments()) {
      html.append(
              "<span class=\"octane-test-metric-automation-color octane-test-metric-automation-")
          .append(escape(segment.getSeverityKey()))
          .append("\" style=\"--octane-test-metric-segment-share:")
          .append(segment.getPercentageText())
          .append("%\"></span>");
    }
    if (!card.isSegmented()) {
      html.append("<span class=\"octane-test-metric-automation-empty-track\"></span>");
    }
    html.append("</div><div class=\"octane-test-metric-automation-labels\">");
    for (OctaneTestMetricSegment segment : card.getSegments()) {
      html.append(
              "<div class=\"octane-test-metric-automation-segment\" data-test-metric-segment=\"true\" data-full-label=\"")
          .append(escape(segment.getLabel()))
          .append("\" data-short-label=\"")
          .append(escape(segment.getShortLabel()))
          .append("\" style=\"--octane-test-metric-segment-share:")
          .append(segment.getPercentageText())
          .append("%\"><span class=\"octane-test-metric-segment-label\">")
          .append(escape(segment.getLabel()))
          .append("</span></div>");
    }
    html.append("</div></div></div>");
  }

  private void renderSuccessRate(StringBuilder html, OctaneTestMetricCard card) {
    html.append("<div class=\"octane-test-metric-visual octane-test-metric-gauge\">")
        .append(
            "<svg class=\"octane-test-metric-gauge-svg\" viewBox=\"0 0 84 48\" aria-hidden=\"true\">")
        .append(
            "<path class=\"octane-test-metric-gauge-track\" d=\"M12 42 A30 30 0 0 1 72 42\" pathLength=\"100\" />")
        .append(
            "<path class=\"octane-test-metric-gauge-fill\" d=\"M12 42 A30 30 0 0 1 72 42\" pathLength=\"100\" stroke-dasharray=\"")
        .append(card.getProgressPercentText())
        .append(" 100\" />")
        .append("<text class=\"octane-test-metric-gauge-value\" x=\"42\" y=\"43\">")
        .append(escape(card.getValue()))
        .append("</text></svg></div>");
  }

  private void renderExecution(StringBuilder html, OctaneTestMetricCard card) {
    html.append("<div class=\"octane-test-metric-visual octane-test-metric-visual-progress\">");
    renderValue(html, card);
    html.append("<div class=\"octane-test-metric-progress-wrap\">")
        .append("<progress class=\"octane-test-metric-progress\" max=\"100\" value=\"")
        .append(card.getProgressPercentText())
        .append("\" aria-label=\"")
        .append(escape(card.getTitle()))
        .append(" ")
        .append(escape(card.getValue()))
        .append("\"></progress></div></div>");
  }

  private void renderDefects(StringBuilder html, OctaneTestMetricCard card) {
    html.append("<div class=\"octane-test-metric-visual octane-test-metric-visual-defects\">");
    renderValue(html, card);
    html.append("<div class=\"octane-test-metric-defect-segments")
        .append(card.isSegmented() ? "" : " octane-test-metric-defect-segments-empty")
        .append("\" data-test-metric-segments=\"true\">")
        .append("<div class=\"octane-test-metric-defect-track\" aria-hidden=\"true\">");
    for (OctaneTestMetricSegment segment : card.getSegments()) {
      html.append("<span class=\"octane-test-metric-defect-color octane-test-metric-defect-color-")
          .append(escape(segment.getSeverityKey()))
          .append("\" style=\"--octane-test-metric-segment-share:")
          .append(segment.getPercentageText())
          .append("%\"></span>");
    }
    if (!card.isSegmented()) {
      html.append("<span class=\"octane-test-metric-defect-empty-track\"></span>");
    }
    html.append("</div><div class=\"octane-test-metric-defect-labels\">");
    for (OctaneTestMetricSegment segment : card.getSegments()) {
      html.append(
              "<div class=\"octane-test-metric-defect-segment\" data-test-metric-segment=\"true\" data-full-label=\"")
          .append(escape(segment.getLabel()))
          .append("\" data-short-label=\"")
          .append(escape(segment.getShortLabel()))
          .append("\" style=\"--octane-test-metric-segment-share:")
          .append(segment.getPercentageText())
          .append("%\"><span class=\"octane-test-metric-defect-label\">")
          .append(escape(segment.getLabel()))
          .append("</span></div>");
    }
    html.append("</div></div></div>");
  }

  private void renderValue(StringBuilder html, OctaneTestMetricCard card) {
    html.append("<div class=\"octane-test-metric-value\">")
        .append(escape(card.getValue()))
        .append("</div>");
  }

  private String icon(String icon) {
    if ("automation".equals(icon)) {
      return "<svg class=\"octane-test-metric-icon\" viewBox=\"0 0 24 24\" aria-hidden=\"true\">"
          + "<path d=\"M13 2L4 14h7l-1 8 9-12h-7z\"/></svg>";
    }
    if ("chart".equals(icon)) {
      return "<svg class=\"octane-test-metric-icon\" viewBox=\"0 0 24 24\" aria-hidden=\"true\">"
          + "<path d=\"M4 20h16\"/><path d=\"M6 16v-5\"/><path d=\"M12 16V7\"/>"
          + "<path d=\"M18 16v-9\"/></svg>";
    }
    if ("activity".equals(icon)) {
      return "<svg class=\"octane-test-metric-icon\" viewBox=\"0 0 24 24\" aria-hidden=\"true\">"
          + "<path d=\"M4 12h4l2-6 4 12 2-6h4\"/></svg>";
    }
    if ("defect".equals(icon)) {
      return "<svg class=\"octane-test-metric-icon\" viewBox=\"0 0 24 24\" aria-hidden=\"true\">"
          + "<path d=\"M12 3l9 16H3z\"/><path d=\"M12 9v4\"/><path d=\"M12 17h.01\"/></svg>";
    }
    return "<svg class=\"octane-test-metric-icon\" viewBox=\"0 0 24 24\" aria-hidden=\"true\">"
        + "<circle cx=\"12\" cy=\"13\" r=\"8\"/><path d=\"M12 9v4l3 2\"/><path d=\"M9 2h6\"/>"
        + "</svg>";
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
