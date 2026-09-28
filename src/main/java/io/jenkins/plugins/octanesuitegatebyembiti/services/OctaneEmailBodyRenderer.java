package io.jenkins.plugins.octanesuitegatebyembiti.services;

import io.jenkins.plugins.octanesuitegatebyembiti.models.CriteriaComparisonEvaluation;
import io.jenkins.plugins.octanesuitegatebyembiti.models.CriteriaEvaluation;
import io.jenkins.plugins.octanesuitegatebyembiti.models.DefectCriteriaMetrics;
import io.jenkins.plugins.octanesuitegatebyembiti.models.DefectLoggingCompliance;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneDefectGroup;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneDefectSeveritySummary;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneDefinedScope;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneExecutionStatusDistribution;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneGateReportSnapshot;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneGateReportState;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneReportTheme;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneTestManagementAnalytics;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneTestMetrics;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneTesterPerformance;
import io.jenkins.plugins.octanesuitegatebyembiti.utils.Util;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OctaneEmailBodyRenderer {
  private static final String PASS_COLOR = "#009900";
  private static final String FAIL_COLOR = "#990000";
  private static final String NEUTRAL_COLOR = "#737373";
  private static final String LIGHT_SYSTEM_GREEN = "#34C759";
  private static final String LIGHT_SYSTEM_ORANGE = "#FF9500";
  private static final String LIGHT_SYSTEM_RED = "#FF3B30";
  private static final String DARK_SYSTEM_GREEN = "#30D158";
  private static final String DARK_SYSTEM_ORANGE = "#FF9F0A";
  private static final String DARK_SYSTEM_RED = "#FF453A";
  private static final String SECTION_TITLE_STYLE =
      "font-family:Arial,sans-serif;font-size:16px;font-weight:600;line-height:1.25;"
          + "padding:0 0 8px;text-align:left;";
  private static final String TABLE_HEADER_STYLE =
      "font-family:Arial,sans-serif;font-size:15px;font-weight:600;line-height:1.4;";
  private static final String TABLE_VALUE_STYLE =
      "font-family:Arial,sans-serif;font-size:15px;font-weight:400;line-height:1.4;";
  private static final String TABLE_CELL_PADDING = "padding:4px 8px;";
  private static final String CRITERIA_TOKEN = "{{CRITERIA}}";
  private static final String SET_CRITERIA_TOKEN = "Set criteria: " + CRITERIA_TOKEN;
  private static final String EXECUTION_DETAILS_TOKEN = "{{EXECUTION_DETAILS}}";
  private static final String REPORT_SCREENSHOT_TOKEN = "{{REPORT_SCREENSHOT}}";
  private static final String TEST_FAILURE_ANALYSIS_FOCUS_QUERY =
      "octaneFocus=test-management-failures&octaneFocusMode=individual";
  private static final String TEST_MANAGEMENT_ZONE_FRAGMENT = "octane-test-management-zone";
  private static final Pattern PROJECT_SUMMARY_BULLET = Pattern.compile("^(\\*{1,5})\\s*(.*)$");
  private static final Pattern BOLD_ITALIC_MARKDOWN = Pattern.compile("_\\*\\*(.*?)\\*\\*_");
  private static final Pattern BOLD_MARKDOWN = Pattern.compile("\\*\\*(.*?)\\*\\*");
  private static final Pattern ITALIC_MARKDOWN = Pattern.compile("_(.*?)_");
  private static final String[][] DEFECT_SEVERITIES = {
    {"critical", "Critical"},
    {"veryHigh", "Very High"},
    {"high", "High"},
    {"medium", "Medium"},
    {"low", "Low"},
    {"unspecified", "Unspecified"}
  };
  private static final String DEFAULT_TEMPLATE =
      """
      Hello Team,

      The automated job for {{PROJECT_NAME}} tests has run for {{DURATION}}, has an execution rate of {{EXECUTIONRATE}} and a pass rate of {{PASSRATE}}, and is {{GATE_RESULT}}.

      {{CRITERIA}}

      Click here to {{REPORT_LINK}}.

      See below the execution details:

      {{EXECUTION_DETAILS}}

      {{REPORT_SCREENSHOT}}

      Thanks.
      QA Automation Team
      """;
  private static final DateTimeFormatter EMAIL_DATE_FORMATTER =
      DateTimeFormatter.ofPattern("HH:mm:ss dd/MM/yyyy").withZone(ZoneId.of("Africa/Nairobi"));

  public String render(String configuredBody, OctaneGateReportSnapshot snapshot, String reportUrl) {
    String body =
        Util.trimToEmpty(configuredBody).isEmpty()
            ? "Attached is the Octane report-zone screenshot."
            : Util.trimToEmpty(configuredBody);
    String normalizedReportUrl = Util.trimToEmpty(reportUrl);
    boolean bodyContainsReportUrl =
        !normalizedReportUrl.isEmpty() && body.contains(normalizedReportUrl);
    Verdict verdict = verdict(snapshot == null ? null : snapshot.getState());

    StringBuilder html = new StringBuilder();
    html.append("<div style=\"font-family:Arial,sans-serif;color:#202124;line-height:1.5;\">");
    html.append("<p>").append(renderPlainText(body, normalizedReportUrl)).append("</p>");
    html.append("<h2 style=\"font-size:1.25rem;margin:1.25rem 0 0.75rem;\">")
        .append("Octane Criteria Result")
        .append("</h2>");
    appendLabelValue(
        html,
        "Octane Criteria Verdict",
        "<strong style=\"color:" + verdict.color + ";\">" + verdict.label + "</strong>");
    appendLabelValue(
        html,
        "Criteria",
        "<code style=\"white-space:normal;overflow-wrap:anywhere;\">"
            + escape(snapshot == null ? "" : snapshot.getCriteria())
            + "</code>");
    appendLabelValue(
        html, "Gate state", escape(snapshot == null ? "Not available" : snapshot.getStateLabel()));
    appendLabelValue(
        html,
        "Reason",
        escape(snapshot == null ? "Gate report data is unavailable." : snapshot.getMessage()));
    appendEvaluationTable(html, snapshot);
    if (!bodyContainsReportUrl && !normalizedReportUrl.isEmpty()) {
      html.append("<p style=\"margin:1rem 0 0;\"><strong>Octane Gate Report:</strong> ")
          .append(reportLink(normalizedReportUrl))
          .append("</p>");
    }
    html.append("</div>");
    return html.toString();
  }

  public String render(
      String configuredBody,
      String projectName,
      String domainName,
      OctaneGateReportSnapshot snapshot,
      String reportUrl,
      String screenshotContentId) {
    return render(
        configuredBody,
        projectName,
        domainName,
        snapshot,
        reportUrl,
        screenshotContentId,
        OctaneReportTheme.LIGHT.name());
  }

  public String render(
      String configuredBody,
      String projectName,
      String domainName,
      OctaneGateReportSnapshot snapshot,
      String reportUrl,
      String screenshotContentId,
      String theme) {
    return render(
        configuredBody,
        projectName,
        domainName,
        snapshot,
        reportUrl,
        screenshotContentId,
        theme,
        false);
  }

  public String render(
      String configuredBody,
      String projectName,
      String domainName,
      OctaneGateReportSnapshot snapshot,
      String reportUrl,
      String screenshotContentId,
      String theme,
      boolean printDefectGroups) {
    return render(
        configuredBody,
        projectName,
        domainName,
        snapshot,
        reportUrl,
        screenshotContentId,
        theme,
        printDefectGroups,
        "",
        false,
        false,
        "",
        0);
  }

  public String render(
      String configuredBody,
      String projectName,
      String domainName,
      OctaneGateReportSnapshot snapshot,
      String reportUrl,
      String screenshotContentId,
      String theme,
      boolean printDefectGroups,
      String projectSummary,
      boolean includeProjectSummary,
      boolean printDefectsOnEmailBody,
      String defectFilter,
      int defectLimit) {
    return render(
        configuredBody,
        projectName,
        domainName,
        snapshot,
        reportUrl,
        screenshotContentId,
        theme,
        printDefectGroups,
        projectSummary,
        includeProjectSummary,
        printDefectsOnEmailBody,
        defectFilter,
        defectLimit,
        false);
  }

  public String render(
      String configuredBody,
      String projectName,
      String domainName,
      OctaneGateReportSnapshot snapshot,
      String reportUrl,
      String screenshotContentId,
      String theme,
      boolean printDefectGroups,
      String projectSummary,
      boolean includeProjectSummary,
      boolean printDefectsOnEmailBody,
      String defectFilter,
      int defectLimit,
      boolean printTestersOnEmailBody) {
    String template = reportTemplate(configuredBody);
    String normalizedReportUrl = Util.trimToEmpty(reportUrl);
    Verdict verdict = emailVerdict(snapshot == null ? null : snapshot.getState(), theme);
    String criteriaHtml =
        renderProjectSummary(projectSummary, includeProjectSummary)
            + CriteriaEmailTranslator.renderHtml(snapshot == null ? "" : snapshot.getCriteria())
            + renderDefectGroupsParagraph(snapshot, printDefectGroups);
    String rendered = escape(template).replace("\r\n", "\n").replace('\r', '\n');
    rendered = rendered.replace("{{PROJECT_NAME}}", escape(defaultText(projectName, "Octane")));
    rendered =
        rendered.replace("{{DOMAIN_NAME}}", escape(defaultText(domainName, "Not specified")));
    rendered =
        rendered.replace(
            "{{DURATION}}",
            escape(snapshot == null ? "time unavailable" : snapshot.getTestingTimeSpentText()));
    rendered =
        rendered.replace("{{EXECUTIONRATE}}", escape(formattedReportExecutionRate(snapshot)));
    rendered = rendered.replace("{{PASSRATE}}", escape(formattedReportPassRate(snapshot)));
    rendered =
        rendered.replace(
            "{{UPDATED_AT_TEXT}}",
            escape(
                snapshot == null
                    ? "Unknown"
                    : defaultText(snapshot.getUpdatedAtText(), "Unknown")));
    rendered =
        rendered.replace(
            "{{LAST_UPDATE}}",
            escape(
                snapshot == null
                    ? "Unknown"
                    : defaultText(snapshot.getUpdatedAtText(), "Unknown")));
    rendered = renderInlineMarkdownPreservingComponentTokens(rendered);
    rendered =
        rendered.replace(
            "{{GATE_RESULT}}",
            "<strong style=\"color:"
                + verdict.color
                + ";font-weight:700;\">"
                + verdict.label
                + "</strong>");
    rendered = rendered.replace(SET_CRITERIA_TOKEN, criteriaHtml);
    rendered = rendered.replace(CRITERIA_TOKEN, criteriaHtml);
    rendered =
        rendered.replace(
            "{{REPORT_LINK}}",
            normalizedReportUrl.isEmpty()
                ? "view the report output (link unavailable)"
                : reportLink(normalizedReportUrl, "view the report output"));
    rendered =
        renderExecutionReport(
            rendered,
            projectName,
            domainName,
            snapshot,
            normalizedReportUrl,
            screenshotContentId,
            theme,
            printDefectsOnEmailBody,
            defectFilter,
            defectLimit,
            printTestersOnEmailBody);
    rendered = rendered.replace("\n", "<br>");

    return "<div style=\"color:#202124;font-family:Arial,sans-serif;font-size:15px;"
        + "line-height:1.5;margin:0 auto;max-width:1200px;\">"
        + rendered
        + "</div>";
  }

  private String reportTemplate(String configuredBody) {
    String configured = Util.trimToEmpty(configuredBody);
    if (configured.contains("{{")) {
      return configured;
    }
    if (configured.isEmpty()) {
      return DEFAULT_TEMPLATE;
    }
    return DEFAULT_TEMPLATE.replace("Hello Team,\n\n", "Hello Team,\n\n" + configured + "\n\n");
  }

  private String renderExecutionReport(
      String template,
      String projectName,
      String domainName,
      OctaneGateReportSnapshot snapshot,
      String reportUrl,
      String screenshotContentId,
      String theme,
      boolean printDefectsOnEmailBody,
      String defectFilter,
      int defectLimit,
      boolean printTestersOnEmailBody) {
    String details = renderExecutionDetails(projectName, domainName, snapshot, theme);
    String definedScope = renderDefinedScope(snapshot);
    String testerExecution = renderTesterExecution(snapshot, printTestersOnEmailBody, theme);
    String screenshot = renderInlineScreenshot(screenshotContentId, projectName);
    String defects =
        renderDefectsTable(snapshot, printDefectsOnEmailBody, defectFilter, defectLimit, reportUrl);
    int detailsStart = template.indexOf(EXECUTION_DETAILS_TOKEN);
    int screenshotStart = template.indexOf(REPORT_SCREENSHOT_TOKEN);
    if (detailsStart >= 0 && screenshotStart > detailsStart) {
      String contents = details + definedScope + testerExecution + screenshot + defects;
      return template.substring(0, detailsStart)
          + wrapExecutionReport(contents)
          + template.substring(screenshotStart + REPORT_SCREENSHOT_TOKEN.length());
    }
    return template
        .replace(
            EXECUTION_DETAILS_TOKEN, wrapExecutionReport(details + definedScope + testerExecution))
        .replace(REPORT_SCREENSHOT_TOKEN, screenshot + defects);
  }

  private String renderProjectSummary(String summary, boolean includeProjectSummary) {
    String normalized = Util.trimToEmpty(summary);
    if (!includeProjectSummary || normalized.isEmpty()) {
      return "";
    }
    return "<table data-octane-email-section=\"project-summary\" role=\"presentation\" "
        + "cellpadding=\"0\" cellspacing=\"0\" style=\"border-collapse:collapse;"
        + "margin:0 0 16px;width:100%;\"><tr><td style=\""
        + SECTION_TITLE_STYLE
        + "\">Project Summary</td></tr><tr><td>"
        + renderProjectSummaryContents(normalized)
        + "</td></tr></table>";
  }

  private String renderProjectSummaryContents(String summary) {
    StringBuilder html = new StringBuilder();
    int listLevel = 0;
    for (String line : summary.split("\\R")) {
      String normalizedLine = line.trim();
      if (normalizedLine.isEmpty()) {
        continue;
      }
      Matcher bullet = PROJECT_SUMMARY_BULLET.matcher(normalizedLine);
      if (!bullet.matches()) {
        listLevel = closeSummaryList(html, listLevel);
        html.append("<p style=\"margin:0 0 8px;\">")
            .append(renderInlineMarkdown(escape(normalizedLine)))
            .append("</p>");
        continue;
      }

      int requestedLevel = bullet.group(1).length();
      int level = listLevel == 0 ? 1 : Math.min(requestedLevel, listLevel + 1);
      if (level > listLevel) {
        while (listLevel < level) {
          listLevel++;
          html.append(summaryListStart(listLevel));
        }
      } else {
        html.append("</li>");
        while (listLevel > level) {
          html.append("</ul></li>");
          listLevel--;
        }
      }
      html.append("<li>").append(renderInlineMarkdown(escape(bullet.group(2))));
    }
    closeSummaryList(html, listLevel);
    return html.toString();
  }

  private int closeSummaryList(StringBuilder html, int listLevel) {
    if (listLevel <= 0) {
      return 0;
    }
    html.append("</li>");
    while (listLevel > 1) {
      html.append("</ul></li>");
      listLevel--;
    }
    html.append("</ul>");
    return 0;
  }

  private String summaryListStart(int level) {
    String[] markers = {"disc", "circle", "square"};
    return "<ul style=\"list-style-type:"
        + markers[(level - 1) % markers.length]
        + ";padding-left:15px;\">";
  }

  private String renderInlineMarkdownPreservingComponentTokens(String value) {
    String[] tokens = {
      CRITERIA_TOKEN,
      "{{GATE_RESULT}}",
      "{{REPORT_LINK}}",
      EXECUTION_DETAILS_TOKEN,
      REPORT_SCREENSHOT_TOKEN
    };
    String[] placeholders = {
      "OCTANEMAILCOMPONENTTOKEN0",
      "OCTANEMAILCOMPONENTTOKEN1",
      "OCTANEMAILCOMPONENTTOKEN2",
      "OCTANEMAILCOMPONENTTOKEN3",
      "OCTANEMAILCOMPONENTTOKEN4"
    };
    String protectedValue = value;
    for (int index = 0; index < tokens.length; index++) {
      protectedValue = protectedValue.replace(tokens[index], placeholders[index]);
    }
    String rendered = renderInlineMarkdown(protectedValue);
    for (int index = 0; index < tokens.length; index++) {
      rendered = rendered.replace(placeholders[index], tokens[index]);
    }
    return rendered;
  }

  private String renderInlineMarkdown(String value) {
    String rendered =
        BOLD_ITALIC_MARKDOWN.matcher(value).replaceAll("<strong><em>$1</em></strong>");
    rendered = BOLD_MARKDOWN.matcher(rendered).replaceAll("<strong>$1</strong>");
    return ITALIC_MARKDOWN.matcher(rendered).replaceAll("<em>$1</em>");
  }

  private String wrapExecutionReport(String contents) {
    StringBuilder html = new StringBuilder();
    html.append(
        "<table data-octane-email-section=\"execution-report\" role=\"presentation\" "
            + "cellpadding=\"0\" cellspacing=\"0\" "
            + "style=\"border-collapse:collapse;width:100%;\"><tr><td "
            + "style=\"border:2px solid #374151;padding:16px;\">");
    html.append(contents);
    html.append("</td></tr></table>");
    return html.toString();
  }

  private String renderExecutionDetails(
      String projectName, String domainName, OctaneGateReportSnapshot snapshot, String theme) {
    StringBuilder html = new StringBuilder();
    html.append(
        "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" "
            + "style=\"border-collapse:collapse;width:100%;\"><tr>");
    html.append("<td style=\"vertical-align:top;width:50%;\">");
    appendProjectDetailsTable(html, projectName, domainName, snapshot);
    html.append("</td><td style=\"font-size:1px;line-height:1px;width:24px;\">&nbsp;</td>");
    html.append("<td style=\"vertical-align:top;width:50%;\">");
    appendExecutionTable(html, snapshot, theme);
    html.append("</td></tr></table>");
    appendSpacer(html, 28);
    appendDefectAnalysisTables(html, snapshot);
    appendSpacer(html, 28);
    appendCriteriaAndReconciliationTables(html, snapshot, theme);
    return html.toString();
  }

  private String renderDefinedScope(OctaneGateReportSnapshot snapshot) {
    List<OctaneDefinedScope> scopes = snapshot == null ? List.of() : snapshot.getDefinedScope();
    if (scopes.isEmpty()) {
      return "";
    }
    int split = definedScopeSplit(scopes.size());
    StringBuilder html = new StringBuilder();
    appendSpacer(html, 28);
    html.append(
            "<table data-octane-email-section=\"defined-scope\" role=\"presentation\" "
                + "cellpadding=\"0\" cellspacing=\"0\" "
                + "style=\"border-collapse:collapse;width:100%;\"><tr><td style=\"")
        .append(SECTION_TITLE_STYLE)
        .append("\">Defined Scope</td></tr><tr><td>");
    if (split >= scopes.size()) {
      appendDefinedScopeTable(html, scopes);
    } else {
      html.append(
          "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" "
              + "style=\"border-collapse:collapse;width:100%;\"><tr>");
      html.append("<td style=\"vertical-align:top;width:50%;\">");
      appendDefinedScopeTable(html, scopes.subList(0, split));
      html.append("</td><td style=\"font-size:1px;line-height:1px;width:24px;\">&nbsp;</td>");
      html.append("<td style=\"vertical-align:top;width:50%;\">");
      appendDefinedScopeTable(html, scopes.subList(split, scopes.size()));
      html.append("</td></tr></table>");
    }
    html.append("</td></tr></table>");
    return html.toString();
  }

  private String renderTesterExecution(
      OctaneGateReportSnapshot snapshot, boolean printTestersOnEmailBody, String theme) {
    if (!printTestersOnEmailBody) {
      return "";
    }
    List<OctaneTesterPerformance> testers =
        snapshot == null ? List.of() : snapshot.getTesterPerformances();
    int[] totals = testerExecutionTotals(testers);
    TesterAutomationContext automation = testerAutomationContext(snapshot, theme);
    StringBuilder html = new StringBuilder();
    appendSpacer(html, 28);
    html.append(
            "<table data-octane-email-section=\"tester-execution\" role=\"presentation\" "
                + "cellpadding=\"0\" cellspacing=\"0\" "
                + "style=\"border-collapse:collapse;width:100%;\"><tr><td style=\"")
        .append(SECTION_TITLE_STYLE)
        .append("\">Execution Status per tester</td></tr><tr><td>");
    if (testers.size() <= 10) {
      appendTesterExecutionTable(html, testers, "single", true, totals, automation);
    } else {
      int split = (testers.size() + 1) / 2;
      html.append(
          "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" "
              + "style=\"border-collapse:collapse;width:100%;\"><tr>");
      html.append("<td style=\"vertical-align:top;width:50%;\">");
      appendTesterExecutionTable(
          html, testers.subList(0, split), "left", false, totals, automation);
      html.append("</td><td style=\"font-size:1px;line-height:1px;width:24px;\">&nbsp;</td>");
      html.append("<td style=\"vertical-align:top;width:50%;\">");
      appendTesterExecutionTable(
          html, testers.subList(split, testers.size()), "right", false, totals, automation);
      html.append("</td></tr><tr><td colspan=\"3\" style=\"padding-top:8px;\">");
      appendTesterExecutionSummaryTable(html, totals, automation);
      html.append("</td></tr></table>");
    }
    html.append("</td></tr></table>");
    return html.toString();
  }

  private void appendTesterExecutionTable(
      StringBuilder html,
      List<OctaneTesterPerformance> testers,
      String column,
      boolean includeSummary,
      int[] totals,
      TesterAutomationContext automation) {
    html.append("<table data-octane-email-table=\"tester-execution\" data-octane-email-column=\"")
        .append(column)
        .append(
            "\" cellpadding=\"0\" cellspacing=\"0\" style=\"border-collapse:collapse;"
                + "table-layout:fixed;width:100%;\">");
    appendTesterExecutionColumns(html);
    appendTesterExecutionHeader(html);
    html.append("<tbody>");
    for (OctaneTesterPerformance tester : testers) {
      appendTesterExecutionRow(html, tester, automation);
    }
    html.append("</tbody>");
    if (includeSummary) {
      html.append("<tfoot>");
      appendTesterExecutionTotalRow(html, totals, automation);
      html.append("</tfoot>");
    }
    html.append("</table>");
  }

  private void appendTesterExecutionSummaryTable(
      StringBuilder html, int[] totals, TesterAutomationContext automation) {
    html.append(
        "<table data-octane-email-table=\"tester-execution-summary\" cellpadding=\"0\" "
            + "cellspacing=\"0\" style=\"border-collapse:collapse;table-layout:fixed;"
            + "width:100%;\">");
    appendTesterExecutionColumns(html);
    html.append("<tbody>");
    appendTesterExecutionTotalRow(html, totals, automation);
    html.append("</tbody></table>");
  }

  private void appendTesterExecutionColumns(StringBuilder html) {
    html.append(
        "<colgroup><col style=\"width:28%;\"><col style=\"width:12%;\">"
            + "<col style=\"width:12%;\"><col style=\"width:12%;\">"
            + "<col style=\"width:12%;\"><col style=\"width:12%;\">"
            + "<col style=\"width:12%;\"></colgroup>");
  }

  private void appendTesterExecutionHeader(StringBuilder html) {
    html.append("<thead><tr>");
    appendHeader(html, "Testers", "left");
    appendHeader(html, "No Run", "right");
    appendHeader(html, "Blocked", "right");
    appendHeader(html, "Failed", "right");
    appendHeader(html, "Passed", "right");
    appendHeader(html, "Total", "right");
    appendHeader(html, "Automation", "center");
    html.append("</tr></thead>");
  }

  private void appendTesterExecutionRow(
      StringBuilder html, OctaneTesterPerformance tester, TesterAutomationContext automation) {
    html.append("<tr data-octane-tester-row=\"true\">");
    appendTesterExecutionLabelCell(
        html,
        testerUsername(tester.getEmail()),
        false,
        tester.getBlocked() + tester.getFailed() > 0,
        automation.theme);
    appendTesterExecutionValueCell(html, tester.getNoRun(), false);
    appendTesterExecutionValueCell(html, tester.getBlocked(), false);
    appendTesterExecutionValueCell(html, tester.getFailed(), false);
    appendTesterExecutionValueCell(html, tester.getPassed(), false);
    appendTesterExecutionValueCell(html, tester.getTotal(), false);
    appendTesterExecutionAutomationCell(
        html,
        tester.getAutomationPercentageText(),
        automationUsageCellStyle(
            tester.getAutomationPercentage(),
            tester.getAutomationTestTotal(),
            automation.target,
            automation.theme),
        false,
        "data-octane-tester-automation=\"true\"");
    html.append("</tr>");
  }

  private void appendTesterExecutionTotalRow(
      StringBuilder html, int[] totals, TesterAutomationContext automation) {
    html.append("<tr data-octane-tester-total=\"true\">");
    appendTesterExecutionLabelCell(html, "Total", true);
    for (int total : totals) {
      appendTesterExecutionValueCell(html, total, true);
    }
    appendTesterExecutionAutomationCell(
        html,
        automation.totalPercentageText,
        automation.totalStyle,
        true,
        "data-octane-tester-automation-total=\"true\"");
    html.append("</tr>");
  }

  private void appendTesterExecutionAutomationCell(
      StringBuilder html,
      String value,
      EmailValueCellStyle valueCellStyle,
      boolean emphasized,
      String dataAttribute) {
    html.append("<td ").append(dataAttribute);
    if (!valueCellStyle.bgcolor.isEmpty()) {
      html.append(" bgcolor=\"").append(valueCellStyle.bgcolor).append("\"");
    }
    html.append(" style=\"border:1px solid #d0d7de;")
        .append(valueCellStyle.inlineCss)
        .append(emphasized ? TABLE_HEADER_STYLE : TABLE_VALUE_STYLE)
        .append(TABLE_CELL_PADDING)
        .append("text-align:center;vertical-align:middle;\">")
        .append(escape(value))
        .append("</td>");
  }

  private String testerUsername(String email) {
    String normalized = Util.trimToEmpty(email);
    int separator = normalized.indexOf('@');
    String username = separator < 0 ? normalized : normalized.substring(0, separator);
    return username.toLowerCase(Locale.ROOT);
  }

  private void appendTesterExecutionLabelCell(
      StringBuilder html, String label, boolean emphasized) {
    appendTesterExecutionLabelCell(html, label, emphasized, false, "");
  }

  private void appendTesterExecutionLabelCell(
      StringBuilder html,
      String label,
      boolean emphasized,
      boolean requiresAttention,
      String theme) {
    String background = requiresAttention ? systemOrange(emailTheme(theme)) : "#f6f8fa";
    html.append("<th scope=\"row\"")
        .append(requiresAttention ? " data-octane-tester-attention=\"true\"" : "")
        .append(" bgcolor=\"")
        .append(background)
        .append("\" style=\"background-color:")
        .append(background)
        .append(';')
        .append(requiresAttention ? "color:#000000;" : "")
        .append("border:1px solid #d0d7de;")
        .append(emphasized ? TABLE_HEADER_STYLE : TABLE_VALUE_STYLE)
        .append(TABLE_CELL_PADDING)
        .append(
            "overflow-wrap:anywhere;text-align:left;vertical-align:middle;"
                + "white-space:normal;word-break:break-word;\">")
        .append(escape(label))
        .append("</th>");
  }

  private void appendTesterExecutionValueCell(StringBuilder html, int value, boolean emphasized) {
    html.append("<td style=\"border:1px solid #d0d7de;")
        .append(emphasized ? TABLE_HEADER_STYLE : TABLE_VALUE_STYLE)
        .append(TABLE_CELL_PADDING)
        .append("text-align:right;vertical-align:middle;\">")
        .append(value)
        .append("</td>");
  }

  private int[] testerExecutionTotals(List<OctaneTesterPerformance> testers) {
    int[] totals = new int[5];
    for (OctaneTesterPerformance tester : testers) {
      totals[0] += tester.getNoRun();
      totals[1] += tester.getBlocked();
      totals[2] += tester.getFailed();
      totals[3] += tester.getPassed();
      totals[4] += tester.getTotal();
    }
    return totals;
  }

  private TesterAutomationContext testerAutomationContext(
      OctaneGateReportSnapshot snapshot, String theme) {
    OctaneTestMetrics metrics =
        snapshot == null ? OctaneTestMetrics.empty() : snapshot.getTestMetrics();
    return new TesterAutomationContext(
        metrics.getAutomatedTestingTarget(),
        theme,
        metrics.getAutomationPercentageText(),
        automationUsageCellStyle(snapshot, theme));
  }

  private int definedScopeSplit(int total) {
    if (total <= 10) {
      return total;
    }
    return (total + 1) / 2;
  }

  private void appendDefinedScopeTable(StringBuilder html, List<OctaneDefinedScope> definedScope) {
    html.append(
        "<table data-octane-email-table=\"defined-scope\" cellpadding=\"0\" "
            + "cellspacing=\"0\" style=\"border-collapse:collapse;table-layout:fixed;"
            + "width:100%;\"><colgroup><col style=\"width:58%;\">"
            + "<col style=\"width:42%;\"></colgroup><thead><tr>");
    html.append("<th scope=\"col\" style=\"background:#f6f8fa;border:1px solid #d0d7de;")
        .append(TABLE_HEADER_STYLE)
        .append(TABLE_CELL_PADDING)
        .append("text-align:left;\">Project</th>");
    html.append("<th scope=\"col\" style=\"background:#f6f8fa;border:1px solid #d0d7de;")
        .append(TABLE_HEADER_STYLE)
        .append(TABLE_CELL_PADDING)
        .append("overflow-wrap:anywhere;text-align:left;word-break:break-word;\">Owner</th>")
        .append("</tr></thead><tbody>");
    for (OctaneDefinedScope scope : definedScope) {
      html.append("<tr><td style=\"border:1px solid #d0d7de;")
          .append(TABLE_VALUE_STYLE)
          .append(TABLE_CELL_PADDING)
          .append("overflow-wrap:anywhere;text-align:left;vertical-align:middle;")
          .append("white-space:normal;word-break:break-word;\">")
          .append(escape(scope.getProject()))
          .append("</td><td style=\"border:1px solid #d0d7de;")
          .append(TABLE_VALUE_STYLE)
          .append(TABLE_CELL_PADDING)
          .append("overflow-wrap:anywhere;text-align:left;vertical-align:middle;")
          .append("white-space:normal;word-break:break-word;\">")
          .append(escape(scope.getDisplayOwner()))
          .append("</td></tr>");
    }
    html.append("</tbody></table>");
  }

  private void appendCriteriaAndReconciliationTables(
      StringBuilder html, OctaneGateReportSnapshot snapshot, String theme) {
    html.append(
        "<table data-octane-email-section=\"criteria-reconciliation\" role=\"presentation\" "
            + "cellpadding=\"0\" cellspacing=\"0\" "
            + "style=\"border-collapse:collapse;width:100%;\"><tr>");
    html.append("<td style=\"vertical-align:top;width:50%;\">");
    appendEvaluationTable(html, snapshot);
    html.append("</td><td style=\"font-size:1px;line-height:1px;width:24px;\">&nbsp;</td>");
    html.append("<td style=\"vertical-align:top;width:50%;\">");
    appendDefectReconciliationTable(html, snapshot, theme);
    html.append("</td></tr></table>");
  }

  private void appendDefectReconciliationTable(
      StringBuilder html, OctaneGateReportSnapshot snapshot, String theme) {
    DefectLoggingCompliance reconciliation = defectReconciliation(snapshot);
    ReconciliationStyle statusStyle = reconciliationStyle(reconciliation.getStatus(), theme);
    html.append(
        "<table data-octane-email-table=\"defect-reconciliation\" cellpadding=\"0\" "
            + "cellspacing=\"0\" style=\"border-collapse:collapse;table-layout:fixed;"
            + "width:100%;\">");
    html.append("<caption style=\"")
        .append(SECTION_TITLE_STYLE)
        .append("\">Defect Logging Compliance</caption>");
    html.append("<colgroup><col style=\"width:68%;\"><col style=\"width:32%;\"></colgroup>");
    html.append("<tbody>");
    appendReconciliationRow(html, "Blocked Tests", reconciliation.getBlockedTests(), false, false);
    appendReconciliationRow(html, "Failed Tests", reconciliation.getFailedTests(), false, false);
    appendReconciliationRow(
        html, "Total Expected Defects", reconciliation.getExpectedDefects(), true, false);
    appendReconciliationRow(
        html, "Actual Open Defects", reconciliation.getOpenDefects(), true, true);
    appendReconciliationStatusRow(html, reconciliation, statusStyle);
    html.append("</tbody></table>");
  }

  private DefectLoggingCompliance defectReconciliation(OctaneGateReportSnapshot snapshot) {
    return snapshot == null
        ? DefectLoggingCompliance.from(0, 0, 0)
        : snapshot.getTestManagement().getDefectLoggingCompliance();
  }

  private void appendReconciliationRow(
      StringBuilder html, String label, int value, boolean emphasized, boolean strongDivider) {
    String fontWeight = emphasized ? "font-weight:600;" : "";
    String borderBottom = strongDivider ? "border-bottom:2px solid #d0d7de;" : "";
    html.append("<tr><th scope=\"row\" style=\"background:#f6f8fa;border:1px solid #d0d7de;")
        .append(borderBottom)
        .append(TABLE_VALUE_STYLE)
        .append(fontWeight)
        .append(TABLE_CELL_PADDING)
        .append("text-align:left;\">")
        .append(escape(label))
        .append("</th><td style=\"border:1px solid #d0d7de;")
        .append(borderBottom)
        .append(TABLE_VALUE_STYLE)
        .append(fontWeight)
        .append(TABLE_CELL_PADDING)
        .append("text-align:right;\">")
        .append(value)
        .append("</td></tr>");
  }

  private void appendReconciliationStatusRow(
      StringBuilder html, DefectLoggingCompliance reconciliation, ReconciliationStyle statusStyle) {
    String percentagePrefix =
        reconciliation.getStatus() == DefectLoggingCompliance.Status.SURPLUS ? "+" : "";
    String statusText =
        percentagePrefix
            + Util.formatCompactPercentage(reconciliation.getDiscrepancyPercentage())
            + " ("
            + reconciliation.getStatus().getLabel()
            + ")";
    html.append("<tr bgcolor=\"")
        .append(statusStyle.backgroundColor)
        .append("\"><th scope=\"row\" style=\"background-color:")
        .append(statusStyle.backgroundColor)
        .append(";border:1px solid #d0d7de;border-left:4px solid ")
        .append(statusStyle.accentColor)
        .append(";")
        .append(TABLE_HEADER_STYLE)
        .append("padding:10px;text-align:left;\">Reconciliation Discrepancy</th>")
        .append("<td style=\"background-color:")
        .append(statusStyle.backgroundColor)
        .append(";border:1px solid #d0d7de;border-right:4px solid ")
        .append(statusStyle.accentColor)
        .append(";color:")
        .append(statusStyle.textColor)
        .append(";")
        .append(TABLE_VALUE_STYLE)
        .append("font-weight:700;padding:10px;text-align:right;white-space:nowrap;\">")
        .append(statusText)
        .append("</td></tr>");
  }

  private ReconciliationStyle reconciliationStyle(
      DefectLoggingCompliance.Status status, String theme) {
    boolean darkTheme = emailTheme(theme) == OctaneReportTheme.DARK;
    if (status == DefectLoggingCompliance.Status.TALLY) {
      return new ReconciliationStyle(
          darkTheme ? DARK_SYSTEM_GREEN : LIGHT_SYSTEM_GREEN, "#E8F8ED", "#166534");
    }
    if (status == DefectLoggingCompliance.Status.UNDER_REPORTED) {
      return new ReconciliationStyle(
          darkTheme ? DARK_SYSTEM_RED : LIGHT_SYSTEM_RED, "#FDECEB", "#991B1B");
    }
    return new ReconciliationStyle(
        darkTheme ? DARK_SYSTEM_ORANGE : LIGHT_SYSTEM_ORANGE, "#FFF3E0", "#92400E");
  }

  private void appendDefectAnalysisTables(StringBuilder html, OctaneGateReportSnapshot snapshot) {
    DefectCriteriaMetrics metrics =
        snapshot == null
            ? new DefectCriteriaMetrics(OctaneDefectSeveritySummary.empty(), List.of())
            : snapshot.getDefectMetrics();
    html.append(
        "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" "
            + "style=\"border-collapse:collapse;width:100%;\"><tr>");
    html.append("<td style=\"vertical-align:top;width:50%;\">");
    appendDefectDistributionMatrix(html, metrics);
    html.append("</td><td style=\"font-size:1px;line-height:1px;width:24px;\">&nbsp;</td>");
    html.append("<td style=\"vertical-align:top;width:50%;\">");
    appendDefectStatusTable(html, metrics);
    html.append("</td></tr></table>");
  }

  private void appendDefectDistributionMatrix(StringBuilder html, DefectCriteriaMetrics metrics) {
    OctaneDefectSeveritySummary summary = metrics.getSeveritySummary();
    int[] priorityTotals = new int[3];
    html.append(
        defectTableStart(
            "defect-distribution", "Defect Distribution Matrix (Severity vs. Priority)"));
    html.append("<thead><tr>");
    appendHeader(html, "Severity / Priority", "left");
    appendHeader(html, "Highest", "right");
    appendHeader(html, "Medium", "right");
    appendHeader(html, "Low", "right");
    html.append("</tr></thead><tbody>");
    for (String[] severity : DEFECT_SEVERITIES) {
      int count = summary.getTotalCount(severity[0]);
      int priorityIndex = priorityIndex(metrics, severity[0]);
      priorityTotals[priorityIndex] += count;
      html.append("<tr>");
      appendDefectRowHeader(html, severity[1]);
      for (int index = 0; index < priorityTotals.length; index++) {
        String priority = priorityLabel(index);
        appendDefectCell(
            html,
            index == priorityIndex ? count : 0,
            severity[1] + " severity, " + priority + " priority");
      }
      html.append("</tr>");
    }
    html.append("<tr>");
    int totalDefects = priorityTotals[0] + priorityTotals[1] + priorityTotals[2];
    appendDefectRowHeader(html, "Total (" + totalDefects + ")");
    for (int index = 0; index < priorityTotals.length; index++) {
      String priority = priorityLabel(index);
      appendDefectCell(html, priorityTotals[index], "All severities, " + priority + " priority");
    }
    html.append("</tr></tbody></table>");
  }

  private void appendDefectStatusTable(StringBuilder html, DefectCriteriaMetrics metrics) {
    OctaneDefectSeveritySummary summary = metrics.getSeveritySummary();
    List<DefectStatusColumn> columns = defectStatusColumns(metrics);
    html.append(defectTableStart("defect-status", "Defect Status Table (by Severity)"));
    html.append("<thead><tr>");
    appendHeader(html, "Defect Status", "left");
    for (DefectStatusColumn column : columns) {
      appendHeader(html, titleCase(column.label), "right");
    }
    html.append("</tr></thead><tbody>");
    appendDefectStatusRow(
        html,
        "Open (" + summary.getOpenTotal() + ")",
        "Open",
        summary,
        columns,
        DefectStatusCount.OPEN);
    appendDefectStatusRow(
        html,
        "Closed (" + summary.getClosed() + ")",
        "Closed",
        summary,
        columns,
        DefectStatusCount.CLOSED);
    appendDefectStatusRow(
        html,
        "Total (" + summary.getTotal() + ")",
        "Total",
        summary,
        columns,
        DefectStatusCount.TOTAL);
    html.append("</tbody></table>");
  }

  private void appendDefectStatusRow(
      StringBuilder html,
      String rowLabel,
      String accessibleLabel,
      OctaneDefectSeveritySummary summary,
      List<DefectStatusColumn> columns,
      DefectStatusCount countType) {
    html.append("<tr>");
    appendDefectRowHeader(html, rowLabel);
    for (DefectStatusColumn column : columns) {
      int count = defectStatusCount(summary, column.types, countType);
      appendDefectCell(html, count, titleCase(column.label) + ", " + accessibleLabel);
    }
    html.append("</tr>");
  }

  private List<DefectStatusColumn> defectStatusColumns(DefectCriteriaMetrics metrics) {
    OctaneDefectSeveritySummary summary = metrics.getSeveritySummary();
    List<DefectStatusColumn> columns = new ArrayList<>();
    Set<String> groupedTypes = new LinkedHashSet<>();
    for (OctaneDefectGroup group : metrics.getConfiguredGroups()) {
      List<String> types = group.getNormalizedTypes();
      if (types.isEmpty()) {
        continue;
      }
      columns.add(new DefectStatusColumn(group.getName(), types));
      groupedTypes.addAll(types);
    }

    boolean includeAllStandaloneSeverities = columns.isEmpty();
    for (String[] severity : DEFECT_SEVERITIES) {
      String type = OctaneDefectSeveritySummary.normalizeOpenType(severity[0]);
      if (groupedTypes.contains(type)) {
        continue;
      }
      if (includeAllStandaloneSeverities || summary.getTotalCount(type) > 0) {
        columns.add(new DefectStatusColumn(severity[1], List.of(type)));
      }
    }
    return columns;
  }

  private int defectStatusCount(
      OctaneDefectSeveritySummary summary, List<String> types, DefectStatusCount countType) {
    int count = 0;
    for (String type : types) {
      if (countType == DefectStatusCount.OPEN) {
        count += summary.getOpenCount(type);
      } else if (countType == DefectStatusCount.CLOSED) {
        count += summary.getClosedCount(type);
      } else {
        count += summary.getTotalCount(type);
      }
    }
    return count;
  }

  private String renderDefectGroupsParagraph(
      OctaneGateReportSnapshot snapshot, boolean printDefectGroups) {
    if (!printDefectGroups) {
      return "";
    }
    DefectCriteriaMetrics metrics =
        snapshot == null
            ? new DefectCriteriaMetrics(OctaneDefectSeveritySummary.empty(), List.of())
            : snapshot.getDefectMetrics();
    String defectGroups = formatDefectGroups(metrics);
    if (defectGroups.isEmpty()) {
      return "";
    }
    return "\n<p style=\"margin:8px 0 0;\">Defect groups: ( " + defectGroups + " )</p>";
  }

  private String formatDefectGroups(DefectCriteriaMetrics metrics) {
    List<String> entries = new ArrayList<>();
    Set<String> groupedTypes = new LinkedHashSet<>();
    for (OctaneDefectGroup group : metrics.getConfiguredGroups()) {
      List<String> types = group.getNormalizedTypes();
      if (types.isEmpty()) {
        continue;
      }
      groupedTypes.addAll(types);
      List<String> labels = new ArrayList<>();
      for (String type : types) {
        labels.add(escape(defectTypeLabel(type)));
      }
      entries.add(
          "<strong>"
              + escape(titleCase(group.getName()))
              + ":</strong> "
              + String.join(", ", labels));
    }

    for (String[] severity : DEFECT_SEVERITIES) {
      String type = OctaneDefectSeveritySummary.normalizeOpenType(severity[0]);
      if (!groupedTypes.contains(type)) {
        entries.add(escape(severity[1]));
      }
    }
    return String.join(" ; ", entries);
  }

  private String defectTypeLabel(String type) {
    String normalized = OctaneDefectSeveritySummary.normalizeOpenType(type);
    for (String[] severity : DEFECT_SEVERITIES) {
      if (normalized.equals(OctaneDefectSeveritySummary.normalizeOpenType(severity[0]))) {
        return severity[1];
      }
    }
    return titleCase(type);
  }

  private String defectTableStart(String tableName, String caption) {
    return "<table data-octane-email-table=\""
        + tableName
        + "\" cellpadding=\"0\" cellspacing=\"0\" style=\"border-collapse:collapse;"
        + "table-layout:fixed;width:100%;\"><caption style=\""
        + SECTION_TITLE_STYLE
        + "\">"
        + escape(caption)
        + "</caption>";
  }

  private void appendDefectRowHeader(StringBuilder html, String label) {
    html.append("<th scope=\"row\" style=\"background:#f6f8fa;border:1px solid #d0d7de;")
        .append(TABLE_HEADER_STYLE)
        .append(TABLE_CELL_PADDING)
        .append("text-align:left;\">")
        .append(escape(label))
        .append("</th>");
  }

  private void appendDefectCell(StringBuilder html, int count, String accessibleLabel) {
    html.append("<td aria-label=\"")
        .append(escape(accessibleLabel))
        .append(": ")
        .append(count)
        .append("\" style=\"border:1px solid #d0d7de;")
        .append(TABLE_VALUE_STYLE)
        .append(TABLE_CELL_PADDING)
        .append("text-align:right;\">")
        .append(count)
        .append("</td>");
  }

  private int priorityIndex(DefectCriteriaMetrics metrics, String severity) {
    if (metrics.isTypeInGroup("major", severity)) {
      return 0;
    }
    if (metrics.isTypeInGroup("minor", severity)) {
      return 1;
    }
    if ("low".equals(OctaneDefectSeveritySummary.normalizeOpenType(severity))) {
      return 2;
    }
    if ("medium".equals(OctaneDefectSeveritySummary.normalizeOpenType(severity))) {
      return 1;
    }
    return 0;
  }

  private String priorityLabel(int index) {
    if (index == 0) {
      return "Highest";
    }
    if (index == 1) {
      return "Medium";
    }
    return "Low";
  }

  private String titleCase(String value) {
    String normalized =
        Util.trimToEmpty(value)
            .replaceAll("([a-z])([A-Z])", "$1 $2")
            .replace('-', ' ')
            .replace('_', ' ');
    if (normalized.isEmpty()) {
      return "";
    }
    List<String> words = new ArrayList<>();
    for (String word : normalized.split("\\s+")) {
      if (word.isEmpty()) {
        continue;
      }
      words.add(
          word.substring(0, 1).toUpperCase(Locale.ROOT)
              + word.substring(1).toLowerCase(Locale.ROOT));
    }
    return String.join(" ", words);
  }

  private void appendProjectDetailsTable(
      StringBuilder html,
      String projectName,
      String domainName,
      OctaneGateReportSnapshot snapshot) {
    html.append(dataTableStart("Project Details"));
    appendDetailRow(html, "Domain", defaultText(domainName, "Not specified"));
    appendDetailRow(html, "Project", defaultText(projectName, "Octane"));
    appendDetailRow(
        html, "Start date", formatTimestamp(snapshot == null ? "" : snapshot.getStartedAt()));
    String endDate =
        snapshot != null && snapshot.isBuilding()
            ? "In Progress"
            : formatTimestamp(snapshot == null ? "" : snapshot.getUpdatedAt());
    appendDetailRow(html, "End date", endDate);
    html.append("</tbody></table>");
  }

  private void appendExecutionTable(
      StringBuilder html, OctaneGateReportSnapshot snapshot, String theme) {
    int total = snapshot == null ? 0 : snapshot.getProjectTestTotal();
    int executed = snapshot == null ? 0 : snapshot.getExecutedTestCount();
    int skipped = statusCount(snapshot, "Skipped");
    String executionRate =
        snapshot == null ? "0%" : Util.formatCompactPercentage(snapshot.getExecutionProgress());
    String passRate =
        snapshot == null ? "0%" : Util.formatCompactPercentage(snapshot.getPassRateProgress());
    EmailValueCellStyle passRateStyle = passRateCellStyle(snapshot, theme);
    html.append(dataTableStart("Test case execution"));
    appendDetailRow(html, "Total test cases", total);
    appendDetailRow(html, "Executed test cases", executed);
    appendDetailRow(html, "Blocked test cases", statusCount(snapshot, "Blocked"));
    appendDetailRow(html, "Passed test cases", statusCount(snapshot, "Passed"));
    appendDetailRow(html, "Failed test cases", statusCount(snapshot, "Failed"));
    appendDetailRow(html, "No run test cases", Math.max(0, total - executed - skipped));
    appendDetailRow(html, "Skipped test cases", skipped);
    appendDetailRow(html, "Execution rate", executionRate);
    appendDetailRow(html, "Pass Rate", passRate, passRateStyle);
    String automationUsage =
        snapshot == null ? "0%" : snapshot.getTestMetrics().getAutomationPercentageText();
    appendDetailRow(
        html, "Automation Usage", automationUsage, automationUsageCellStyle(snapshot, theme));
    html.append("</tbody></table>");
  }

  private String formattedReportExecutionRate(OctaneGateReportSnapshot snapshot) {
    return snapshot == null ? "0%" : snapshot.getExecutionProgressTwoDecimalText();
  }

  private String formattedReportPassRate(OctaneGateReportSnapshot snapshot) {
    return snapshot == null ? "0%" : snapshot.getPassRateProgressTwoDecimalText();
  }

  private String dataTableStart(String caption) {
    return "<table cellpadding=\"0\" cellspacing=\"0\" style=\"border-collapse:collapse;"
        + "table-layout:fixed;width:100%;\"><caption style=\""
        + SECTION_TITLE_STYLE
        + "\">"
        + escape(caption)
        + "</caption><tbody>";
  }

  private void appendDetailRow(StringBuilder html, String label, Object value) {
    appendDetailRow(html, label, value, EmailValueCellStyle.defaultStyle());
  }

  private void appendDetailRow(
      StringBuilder html, String label, Object value, EmailValueCellStyle valueCellStyle) {
    html.append(
            "<tr><th scope=\"row\" style=\"background:#e5e7eb;border:1px solid #374151;"
                + TABLE_HEADER_STYLE
                + TABLE_CELL_PADDING
                + "text-align:right;width:44%;\">")
        .append(escape(label))
        .append("</th><td");
    if (!valueCellStyle.bgcolor.isEmpty()) {
      html.append(" bgcolor=\"").append(valueCellStyle.bgcolor).append("\"");
    }
    html.append(" style=\"border:1px solid #374151;")
        .append(valueCellStyle.inlineCss)
        .append(TABLE_VALUE_STYLE)
        .append(TABLE_CELL_PADDING)
        .append("text-align:left;word-break:break-word;\">")
        .append(escape(String.valueOf(value)))
        .append("</td></tr>");
  }

  private int statusCount(OctaneGateReportSnapshot snapshot, String label) {
    if (snapshot == null) {
      return 0;
    }
    for (OctaneExecutionStatusDistribution.Segment segment :
        snapshot.getExecutionStatusDistribution().getSegments()) {
      if (label.equalsIgnoreCase(segment.getLabel())) {
        return segment.getCount();
      }
    }
    return 0;
  }

  private EmailValueCellStyle passRateCellStyle(OctaneGateReportSnapshot snapshot, String theme) {
    OctaneReportTheme emailTheme = emailTheme(theme);
    OctaneGateReportState state = snapshot == null ? null : snapshot.getState();
    if (state != null && state.isBuilding()) {
      return EmailValueCellStyle.painted(systemOrange(emailTheme), "#000000");
    }
    if (state == OctaneGateReportState.PASSED) {
      return EmailValueCellStyle.painted(systemGreen(emailTheme), "#000000");
    }
    if (isFailState(state)) {
      return EmailValueCellStyle.painted(systemRed(emailTheme), "#000000");
    }
    return EmailValueCellStyle.fallbackStyle();
  }

  private EmailValueCellStyle automationUsageCellStyle(
      OctaneGateReportSnapshot snapshot, String theme) {
    OctaneTestMetrics metrics =
        snapshot == null ? OctaneTestMetrics.empty() : snapshot.getTestMetrics();
    return automationUsageCellStyle(
        metrics.getAutomationPercentage(),
        metrics.getAutomationTestTotal(),
        metrics.getAutomatedTestingTarget(),
        theme);
  }

  private EmailValueCellStyle automationUsageCellStyle(
      int automationPercentage, int automationTestTotal, int automatedTestingTarget, String theme) {
    if (automationTestTotal == 0) {
      return EmailValueCellStyle.fallbackStyle();
    }
    switch (OctaneTestMetrics.automationTargetTone(
        automationPercentage, automationTestTotal, automatedTestingTarget)) {
      case "positive":
        return EmailValueCellStyle.painted(systemGreen(emailTheme(theme)), "#000000");
      case "warning":
        return EmailValueCellStyle.painted(systemOrange(emailTheme(theme)), "#000000");
      case "negative":
        return EmailValueCellStyle.painted(systemRed(emailTheme(theme)), "#000000");
      default:
        return EmailValueCellStyle.fallbackStyle();
    }
  }

  private String systemGreen(OctaneReportTheme theme) {
    return theme == OctaneReportTheme.DARK ? DARK_SYSTEM_GREEN : LIGHT_SYSTEM_GREEN;
  }

  private String systemOrange(OctaneReportTheme theme) {
    return theme == OctaneReportTheme.DARK ? DARK_SYSTEM_ORANGE : LIGHT_SYSTEM_ORANGE;
  }

  private String systemRed(OctaneReportTheme theme) {
    return theme == OctaneReportTheme.DARK ? DARK_SYSTEM_RED : LIGHT_SYSTEM_RED;
  }

  private OctaneReportTheme emailTheme(String theme) {
    try {
      return OctaneReportTheme.from(theme);
    } catch (IllegalArgumentException e) {
      return OctaneReportTheme.SYSTEM;
    }
  }

  private boolean isFailState(OctaneGateReportState state) {
    return state == OctaneGateReportState.FAILED
        || state == OctaneGateReportState.UNSTABLE
        || state == OctaneGateReportState.TIMED_OUT;
  }

  private String renderInlineScreenshot(String contentId, String projectName) {
    String normalizedContentId = Util.trimToEmpty(contentId);
    if (normalizedContentId.isEmpty()) {
      return "<p><strong>Execution report screenshot unavailable.</strong></p>";
    }
    StringBuilder html = new StringBuilder();
    appendSpacer(html, 28);
    html.append(
            "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" "
                + "style=\"border-collapse:collapse;margin:0;width:100%;\"><tr><td style=\""
                + SECTION_TITLE_STYLE
                + "\">Execution graph</td></tr><tr><td "
                + "style=\"font-size:0;line-height:0;padding:0;\">")
        .append("<img src=\"cid:")
        .append(escape(normalizedContentId))
        .append("\" alt=\"")
        .append(escape(defaultText(projectName, "Octane")))
        .append(
            " gate execution report charts\" style=\"border:0;display:block;height:auto;"
                + "margin:0;max-width:100%;padding:0;width:100%;\"></td></tr></table>");
    return html.toString();
  }

  private String renderDefectsTable(
      OctaneGateReportSnapshot snapshot,
      boolean enabled,
      String filter,
      int limit,
      String reportUrl) {
    if (!enabled || snapshot == null) {
      return "";
    }
    DefectTableSelection selection = defectTableSelection(snapshot, filter, limit);
    if (selection.visibleDefects.isEmpty()) {
      return "";
    }

    StringBuilder html = new StringBuilder();
    appendSpacer(html, 28);
    html.append(
        "<table data-octane-email-table=\"defects\" cellpadding=\"0\" cellspacing=\"0\" "
            + "style=\"border-collapse:collapse;table-layout:fixed;width:100%;\">"
            + "<caption style=\""
            + SECTION_TITLE_STYLE
            + "\">Defects</caption><colgroup><col style=\"width:12%;\">"
            + "<col style=\"width:43%;\"><col style=\"width:25%;\">"
            + "<col style=\"width:20%;\"></colgroup><thead><tr>");
    appendHeader(html, "ID", "left");
    appendHeader(html, "Description", "left");
    appendHeader(html, "Severity", "left");
    appendHeader(html, "Status", "left");
    html.append("</tr></thead><tbody>");
    for (OctaneTestManagementAnalytics.DefectDetail defect : selection.visibleDefects) {
      html.append("<tr>");
      appendDefectTextCell(html, defect.getId());
      appendDefectDescriptionCell(html, defect.getDescription());
      appendDefectTextCell(html, defect.getSeverityLabel());
      appendDefectTextCell(html, defect.getStatus());
      html.append("</tr>");
    }
    if (selection.overflowed) {
      appendDefectOverflowRow(html, reportUrl, 4, selection.errorCount);
    }
    html.append("</tbody></table>");
    return html.toString();
  }

  private DefectTableSelection defectTableSelection(
      OctaneGateReportSnapshot snapshot, String filter, int limit) {
    List<String> criteria =
        List.of(Util.trimToEmpty(filter).split(",")).stream()
            .map(value -> value.trim())
            .filter(value -> !value.isEmpty())
            .map(value -> value.toLowerCase(Locale.ROOT))
            .toList();
    List<OctaneTestManagementAnalytics.DefectDetail> defects = new ArrayList<>();
    for (OctaneTestManagementAnalytics.FailureCategory category :
        snapshot.getTestManagement().getFailureCategories()) {
      for (OctaneTestManagementAnalytics.DefectDetail defect : category.getDefects()) {
        if (criteria.isEmpty() || matchesDefectFilter(defect, criteria)) {
          defects.add(defect);
        }
      }
    }
    defects.sort(
        Comparator.comparingLong(
                (OctaneTestManagementAnalytics.DefectDetail defect) -> defectId(defect.getId()))
            .thenComparing(defect -> defect.getId(), String.CASE_INSENSITIVE_ORDER));
    int errorCount =
        criteria.isEmpty() ? snapshot.getTestManagement().getTotalDefects() : defects.size();
    boolean overflowed = limit > 0 && errorCount > limit;
    List<OctaneTestManagementAnalytics.DefectDetail> visibleDefects =
        limit > 0 && defects.size() > limit
            ? List.copyOf(defects.subList(0, limit))
            : List.copyOf(defects);
    return new DefectTableSelection(visibleDefects, errorCount, overflowed);
  }

  private boolean matchesDefectFilter(
      OctaneTestManagementAnalytics.DefectDetail defect, List<String> criteria) {
    Set<String> exactValues = new LinkedHashSet<>();
    exactValues.add(Util.trimToEmpty(defect.getId()).toLowerCase(Locale.ROOT));
    exactValues.add(Util.trimToEmpty(defect.getDescription()).toLowerCase(Locale.ROOT));
    exactValues.add(Util.trimToEmpty(defect.getSeverity()).toLowerCase(Locale.ROOT));
    exactValues.add(Util.trimToEmpty(defect.getSeverityLabel()).toLowerCase(Locale.ROOT));
    exactValues.add(Util.trimToEmpty(defect.getStatus()).toLowerCase(Locale.ROOT));
    return criteria.stream().allMatch(criterion -> exactValues.contains(criterion));
  }

  private void appendDefectOverflowRow(
      StringBuilder html, String reportUrl, int totalColumns, int errorCount) {
    String target = testFailureAnalysisUrl(reportUrl);
    if (target.isEmpty()) {
      return;
    }
    html.append("<tr data-octane-defect-overflow=\"true\" data-octane-error-count=\"")
        .append(Math.max(0, errorCount))
        .append("\"><td colspan=\"")
        .append(totalColumns)
        .append("\" style=\"border:1px solid #d0d7de;")
        .append(TABLE_VALUE_STYLE)
        .append(TABLE_CELL_PADDING)
        .append("text-align:center;\"><a href=\"")
        .append(escape(target))
        .append("\" style=\"color:#0969da;text-decoration:underline;\">")
        .append("view all defects</a></td></tr>");
  }

  private String testFailureAnalysisUrl(String reportUrl) {
    String normalized = Util.trimToEmpty(reportUrl);
    if (normalized.isEmpty()) {
      return "";
    }
    int fragmentStart = normalized.indexOf('#');
    String base = fragmentStart >= 0 ? normalized.substring(0, fragmentStart) : normalized;
    String separator;
    if (!base.contains("?")) {
      separator = "?";
    } else if (base.endsWith("?") || base.endsWith("&")) {
      separator = "";
    } else {
      separator = "&";
    }
    return base
        + separator
        + TEST_FAILURE_ANALYSIS_FOCUS_QUERY
        + "#"
        + TEST_MANAGEMENT_ZONE_FRAGMENT;
  }

  private long defectId(String value) {
    try {
      return Long.parseLong(Util.trimToEmpty(value));
    } catch (NumberFormatException ignored) {
      return Long.MAX_VALUE;
    }
  }

  private void appendDefectTextCell(StringBuilder html, String value) {
    html.append("<td style=\"border:1px solid #d0d7de;")
        .append(TABLE_VALUE_STYLE)
        .append(TABLE_CELL_PADDING)
        .append(
            "overflow-wrap:anywhere;text-align:left;vertical-align:top;word-break:break-word;\">")
        .append(escape(value))
        .append("</td>");
  }

  private void appendDefectDescriptionCell(StringBuilder html, String value) {
    html.append("<td style=\"border:1px solid #d0d7de;")
        .append(TABLE_VALUE_STYLE)
        .append(TABLE_CELL_PADDING)
        .append(
            "overflow-wrap:anywhere;text-align:left;vertical-align:top;word-break:break-word;\">")
        .append(escape(value).replace("\n", "<br>"))
        .append("</td>");
  }

  private void appendSpacer(StringBuilder html, int height) {
    html.append(
            "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" "
                + "style=\"border-collapse:collapse;width:100%;\"><tr><td height=\"")
        .append(height)
        .append("\" style=\"font-size:1px;height:")
        .append(height)
        .append("px;line-height:1px;\">&nbsp;</td></tr></table>");
  }

  private String formatTimestamp(String value) {
    try {
      return EMAIL_DATE_FORMATTER.format(Instant.parse(value)) + " EAT";
    } catch (RuntimeException e) {
      return defaultText(value, "Not available");
    }
  }

  private String defaultText(String value, String fallback) {
    String normalized = Util.trimToEmpty(value);
    return normalized.isEmpty() ? fallback : normalized;
  }

  private void appendEvaluationTable(StringBuilder html, OctaneGateReportSnapshot snapshot) {
    CriteriaEvaluation evaluation =
        snapshot == null ? CriteriaEvaluation.unavailable() : snapshot.getCriteriaEvaluation();
    if (!evaluation.isAvailable() || evaluation.getComparisons().isEmpty()) {
      html.append("<p style=\"margin:1rem 0 0;\"><strong>Criteria evaluation:</strong> ")
          .append("Detailed evaluation unavailable for this build.</p>");
      return;
    }

    html.append(
        "<table data-octane-email-table=\"criteria-evaluation\" cellpadding=\"0\" "
            + "cellspacing=\"0\" style=\"border-collapse:collapse;table-layout:fixed;"
            + "width:100%;\">");
    html.append("<caption style=\"")
        .append(SECTION_TITLE_STYLE)
        .append("\">Criteria evaluation</caption>");
    html.append(
        "<colgroup><col style=\"width:58%;\"><col style=\"width:22%;\"><col style=\"width:20%;\"></colgroup>");
    html.append("<thead><tr>");
    appendHeader(html, "Criterion", "left");
    appendHeader(html, "Actual", "right");
    appendHeader(html, "Result", "left");
    html.append("</tr></thead><tbody>");
    for (CriteriaComparisonEvaluation comparison : evaluation.getComparisons()) {
      String color = comparison.isPassed() ? PASS_COLOR : FAIL_COLOR;
      html.append("<tr>");
      html.append("<td style=\"border:1px solid #d0d7de;")
          .append(TABLE_VALUE_STYLE)
          .append(TABLE_CELL_PADDING)
          .append("text-align:left;\"><code>")
          .append(escape(comparison.getCriterionLabel()))
          .append("</code></td>");
      html.append(
              "<td style=\"border:1px solid #d0d7de;"
                  + TABLE_VALUE_STYLE
                  + TABLE_CELL_PADDING
                  + "text-align:right;white-space:nowrap;\">")
          .append(escape(comparison.getActualLabel()))
          .append("</td>");
      html.append("<td style=\"border:1px solid #d0d7de;color:")
          .append(color)
          .append(";")
          .append(TABLE_VALUE_STYLE)
          .append("font-weight:600;")
          .append(TABLE_CELL_PADDING)
          .append("text-align:left;white-space:nowrap;\">")
          .append(comparison.getResultLabel())
          .append("</td>");
      html.append("</tr>");
    }
    html.append("</tbody></table>");
  }

  private void appendHeader(StringBuilder html, String label, String alignment) {
    html.append("<th scope=\"col\" style=\"background:#f6f8fa;border:1px solid #d0d7de;")
        .append(TABLE_HEADER_STYLE)
        .append(TABLE_CELL_PADDING)
        .append("text-align:")
        .append(alignment)
        .append(";\">")
        .append(label)
        .append("</th>");
  }

  private void appendLabelValue(StringBuilder html, String label, String valueHtml) {
    html.append("<p style=\"margin:0.35rem 0;\"><strong>")
        .append(label)
        .append(":</strong> ")
        .append(valueHtml)
        .append("</p>");
  }

  private String renderPlainText(String value, String reportUrl) {
    String rendered = escape(value).replace("\r\n", "\n").replace('\r', '\n').replace("\n", "<br>");
    if (reportUrl.isEmpty()) {
      return rendered;
    }
    return rendered.replace(escape(reportUrl), reportLink(reportUrl));
  }

  private String reportLink(String reportUrl) {
    return reportLink(reportUrl, reportUrl);
  }

  private String reportLink(String reportUrl, String label) {
    String escaped = escape(reportUrl);
    return "<a href=\""
        + escaped
        + "\" style=\"color:#0969da;text-decoration:underline;\">"
        + escape(label)
        + "</a>";
  }

  private Verdict emailVerdict(OctaneGateReportState state, String theme) {
    if (state != null && state.isBuilding()) {
      String ongoingColor =
          emailTheme(theme) == OctaneReportTheme.DARK ? DARK_SYSTEM_ORANGE : LIGHT_SYSTEM_ORANGE;
      return new Verdict("ONGOING", ongoingColor);
    }
    Verdict gateVerdict = verdict(state);
    return "PASS".equals(gateVerdict.label)
        ? new Verdict("SUCCESS", gateVerdict.color)
        : gateVerdict;
  }

  private Verdict verdict(OctaneGateReportState state) {
    if (state == OctaneGateReportState.PASSED) {
      return new Verdict("PASS", PASS_COLOR);
    }
    if (state == OctaneGateReportState.FAILED
        || state == OctaneGateReportState.UNSTABLE
        || state == OctaneGateReportState.TIMED_OUT) {
      return new Verdict("FAIL", FAIL_COLOR);
    }
    return new Verdict("NOT EVALUATED", NEUTRAL_COLOR);
  }

  private enum DefectStatusCount {
    OPEN,
    CLOSED,
    TOTAL
  }

  private static class ReconciliationStyle {
    private final String accentColor;
    private final String backgroundColor;
    private final String textColor;

    private ReconciliationStyle(String accentColor, String backgroundColor, String textColor) {
      this.accentColor = accentColor;
      this.backgroundColor = backgroundColor;
      this.textColor = textColor;
    }
  }

  private static class DefectStatusColumn {
    private final String label;
    private final List<String> types;

    DefectStatusColumn(String label, List<String> types) {
      this.label = Util.trimToEmpty(label);
      this.types = List.copyOf(types);
    }
  }

  private static class DefectTableSelection {
    private final List<OctaneTestManagementAnalytics.DefectDetail> visibleDefects;
    private final int errorCount;
    private final boolean overflowed;

    private DefectTableSelection(
        List<OctaneTestManagementAnalytics.DefectDetail> visibleDefects,
        int errorCount,
        boolean overflowed) {
      this.visibleDefects = List.copyOf(visibleDefects);
      this.errorCount = Math.max(0, errorCount);
      this.overflowed = overflowed;
    }
  }

  private String escape(String value) {
    return Util.trimToEmpty(value)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }

  private static class EmailValueCellStyle {
    private final String bgcolor;
    private final String inlineCss;

    private EmailValueCellStyle(String bgcolor, String inlineCss) {
      this.bgcolor = Util.trimToEmpty(bgcolor);
      this.inlineCss = inlineCss;
    }

    private static EmailValueCellStyle defaultStyle() {
      return new EmailValueCellStyle("", "");
    }

    private static EmailValueCellStyle fallbackStyle() {
      return new EmailValueCellStyle("", "background-color:transparent;color:inherit;");
    }

    private static EmailValueCellStyle painted(String backgroundColor, String fontColor) {
      return new EmailValueCellStyle(
          backgroundColor, "background-color:" + backgroundColor + ";color:" + fontColor + ";");
    }
  }

  private static class TesterAutomationContext {
    private final int target;
    private final String theme;
    private final String totalPercentageText;
    private final EmailValueCellStyle totalStyle;

    private TesterAutomationContext(
        int target, String theme, String totalPercentageText, EmailValueCellStyle totalStyle) {
      this.target = target;
      this.theme = Util.trimToEmpty(theme);
      this.totalPercentageText = Util.trimToEmpty(totalPercentageText);
      this.totalStyle = totalStyle;
    }
  }

  private static class Verdict {
    private final String label;
    private final String color;

    Verdict(String label, String color) {
      this.label = label;
      this.color = color;
    }
  }
}
