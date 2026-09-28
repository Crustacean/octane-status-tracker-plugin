package io.jenkins.plugins.octanesuitegatebyembiti.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.octanesuitegatebyembiti.entities.DefectRecord;
import io.jenkins.plugins.octanesuitegatebyembiti.entities.RunRecord;
import io.jenkins.plugins.octanesuitegatebyembiti.models.CriteriaComparisonEvaluation;
import io.jenkins.plugins.octanesuitegatebyembiti.models.CriteriaEvaluation;
import io.jenkins.plugins.octanesuitegatebyembiti.models.DefectCriteriaMetrics;
import io.jenkins.plugins.octanesuitegatebyembiti.models.GateMetrics;
import io.jenkins.plugins.octanesuitegatebyembiti.models.GateResult;
import io.jenkins.plugins.octanesuitegatebyembiti.models.MetricsContext;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneDefectGroup;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneDefectSeveritySummary;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneDefinedScope;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneGateReportSnapshot;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneGateReportState;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneReportTheme;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneRiskHeatMap;
import io.jenkins.plugins.octanesuitegatebyembiti.models.StatusClassifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

public class OctaneEmailBodyRendererTest {
  private static final String REPORT_URL =
      "https://jenkins.example/job/example/1/octaneSuiteGateReport/";
  private final OctaneEmailBodyRenderer renderer = new OctaneEmailBodyRenderer();
  private final StatusClassifier classifier =
      new StatusClassifier(
          StatusClassifier.DEFAULT_PASSED_STATUSES,
          StatusClassifier.DEFAULT_FAILED_STATUSES,
          StatusClassifier.DEFAULT_NEUTRAL_STATUSES,
          StatusClassifier.DEFAULT_RUNNING_STATUSES);

  @Test
  public void rendersPassedVerdictAndOrderedCriteriaTable() {
    String html =
        renderer.render(
            "Attached report.", snapshot(OctaneGateReportState.PASSED, "Gate passed."), REPORT_URL);

    assertTrue(html.contains("Octane Criteria Verdict"));
    assertTrue(html.contains("color:#009900;\">PASS"));
    assertTrue(html.contains("<table"));
    assertTrue(html.contains("<th scope=\"col\""));
    assertTrue(html.contains("regressions.executionRate == 100%"));
    assertTrue(html.contains("regressions.passRate &gt;= 95%"));
    assertTrue(html.contains(">92.5%</td>"));
    assertTrue(html.contains(">OK</td>"));
    assertTrue(html.contains(">NOT OK</td>"));
    assertTrue(html.indexOf("regressions.executionRate") < html.indexOf("regressions.passRate"));
  }

  @Test
  public void mapsFailureAndNonEvaluatedStates() {
    String failed =
        renderer.render(
            "Report", snapshot(OctaneGateReportState.TIMED_OUT, "Timed out."), REPORT_URL);
    String unavailable =
        renderer.render(
            "Report",
            OctaneGateReportSnapshot.error("Polling error", "100% execution", "1", 30),
            REPORT_URL);

    assertTrue(failed.contains("color:#990000;\">FAIL"));
    assertTrue(failed.contains("Timed out"));
    assertTrue(unavailable.contains("NOT EVALUATED"));
    assertTrue(unavailable.contains("Detailed evaluation unavailable for this build."));
  }

  @Test
  public void omitsBypassedRegressionRulesFromPrintedCriteriaAndEvaluationTable() {
    String criteria = "regressions.executionRate == 100 AND critical.executionRate == 100";
    GateMetrics criticalMetrics = new GateMetrics(2, 2, 2, 0, 0, 0);
    MetricsContext metricsContext =
        new MetricsContext(new GateMetrics(0, 0, 0, 0, 0, 0), Map.of("critical", criticalMetrics));
    CriteriaExpression expression = CriteriaExpression.parse(criteria);
    CriteriaEvaluation evaluation = expression.evaluateDetailed(metricsContext, false);
    GateResult result =
        new GateResult(
            "",
            expression.effectiveExpression(metricsContext, false),
            true,
            true,
            new GateMetrics(0, 0, 0, 0, 0, 0),
            List.of(),
            Map.of(),
            Map.of(),
            OctaneRiskHeatMap.disabled(),
            new DefectCriteriaMetrics(OctaneDefectSeveritySummary.empty(), List.of()),
            evaluation,
            Instant.parse("2026-06-30T12:00:00Z"));
    OctaneGateReportSnapshot snapshot =
        OctaneGateReportSnapshot.fromResult(
            OctaneGateReportState.PASSED, "Passed", result, classifier, 30);

    String html = renderer.render("Report", snapshot, REPORT_URL);
    int tableStart = html.indexOf("data-octane-email-table=\"criteria-evaluation\"");
    int tableEnd = html.indexOf("</table>", tableStart);
    String evaluationTable = html.substring(tableStart, tableEnd);

    assertTrue(evaluationTable.contains("critical.executionRate == 100%"));
    assertFalse(evaluationTable.contains("regressions.executionRate"));
    assertTrue(html.contains("Criteria:</strong> <code"));
    int criteriaStart = html.indexOf("Criteria:</strong> <code");
    int criteriaEnd = html.indexOf("</code>", criteriaStart);
    String appliedCriteria = html.substring(criteriaStart, criteriaEnd);
    assertTrue(appliedCriteria.contains("critical.executionRate == 100"));
    assertFalse(appliedCriteria.contains("critical.executionRate == 100%"));
    assertFalse(html.contains("regressions.executionRate"));
  }

  @Test
  public void escapesBodyAndIncludesReportLinkOnlyOnce() {
    String configuredBody = "Hello <script>alert('x')</script>\n" + REPORT_URL;

    String html =
        renderer.render(
            configuredBody, snapshot(OctaneGateReportState.PASSED, "Gate passed."), REPORT_URL);

    assertFalse(html.contains("<script>"));
    assertTrue(html.contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;"));
    assertEquals(2, occurrences(html, REPORT_URL));
    assertEquals(1, occurrences(html, "href=\"" + REPORT_URL + "\""));
  }

  @Test
  public void fallsBackForLegacyResultWithoutDetailedEvaluation() {
    GateResult result =
        new GateResult(
            "1",
            "regressions.passRate == 100",
            true,
            true,
            new GateMetrics(1, 1, 1, 0, 0, 0),
            Map.of(),
            Instant.parse("2026-06-30T12:00:00Z"));
    OctaneGateReportSnapshot snapshot =
        OctaneGateReportSnapshot.fromResult(
            OctaneGateReportState.PASSED, "Passed", result, classifier, 30);

    String html = renderer.render("Report", snapshot, REPORT_URL);

    assertTrue(html.contains("PASS"));
    assertTrue(html.contains("Detailed evaluation unavailable for this build."));
  }

  @Test
  public void rendersProjectExecutionTablesAndCidScreenshotFromTemplate() {
    String template =
        """
        Hello Team,

        The automated job for {{PROJECT_NAME}} tests has run for {{DURATION}}, has an execution rate of {{EXECUTIONRATE}} and a pass rate of {{PASSRATE}}, and is {{GATE_RESULT}}.

        Set criteria: {{CRITERIA}}

        Click here to {{REPORT_LINK}}.

        See below the execution details:

        {{EXECUTION_DETAILS}}

        {{REPORT_SCREENSHOT}}

        Thanks.
        QA Automation Team
        """;

    String html =
        renderer.render(
            template,
            "Business Payments Secure Checkout",
            "FS_TRIBE_DOMAIN",
            snapshot(OctaneGateReportState.PASSED, "Gate passed."),
            REPORT_URL,
            "octane-report-zone.png");

    assertTrue(html.contains("Business Payments Secure Checkout"));
    assertTrue(
        html.contains(
            "tests has run for 0 seconds, has an execution rate of 100.00% and a pass rate of 90.00%, and is"));
    assertFalse(html.contains("{{DURATION}}"));
    assertFalse(html.contains("{{EXECUTIONRATE}}"));
    assertFalse(html.contains("{{PASSRATE}}"));
    assertTrue(html.contains("FS_TRIBE_DOMAIN"));
    assertTrue(html.contains("color:#009900;font-weight:700;\">SUCCESS"));
    assertFalse(html.contains("Set criteria:"));
    assertTrue(
        html.contains(
            "To get a green light for release, the build must pass 1 non-negotiable QA checks:"));
    assertTrue(
        html.contains(
            "<li>Regression tests completed must be exactly 100% OR Regression test pass rate "
                + "must be at least 95</li>"));
    assertTrue(html.contains(">view the report output</a>"));
    assertTrue(html.contains("Project Details"));
    assertTrue(html.contains("Test case execution"));
    assertTrue(html.contains("Total test cases"));
    assertTrue(html.contains("Execution rate"));
    assertTrue(html.contains("Pass Rate"));
    assertTrue(html.indexOf("Execution rate") < html.indexOf("Pass Rate"));
    assertTrue(html.contains(">90%</td>"));
    assertTrue(html.contains("Defect Distribution Matrix (Severity vs. Priority)"));
    assertTrue(html.contains("Defect Status Table (by Severity)"));
    assertTrue(html.contains(">Highest</th>"));
    assertTrue(html.contains(">Medium</th>"));
    assertTrue(html.contains(">Low</th>"));
    assertTrue(html.contains("aria-label=\"All severities, Highest priority: 5\""));
    assertTrue(html.contains("aria-label=\"Low severity, Medium priority: 2\""));
    String statusTable = emailTable(html, "defect-status");
    assertTrue(statusTable.contains(">Major</th>"));
    assertTrue(statusTable.contains(">Minor</th>"));
    assertFalse(statusTable.contains(">Low</th>"));
    assertFalse(statusTable.contains(">Critical</th>"));
    assertFalse(statusTable.contains(">Very High</th>"));
    assertFalse(statusTable.contains(">High</th>"));
    assertFalse(statusTable.contains(">Medium</th>"));
    assertFalse(statusTable.contains(">Unspecified</th>"));
    assertTrue(statusTable.contains("aria-label=\"Major, Open: 4\""));
    assertTrue(statusTable.contains("aria-label=\"Major, Closed: 1\""));
    assertTrue(statusTable.contains("aria-label=\"Major, Total: 5\""));
    assertTrue(statusTable.contains("aria-label=\"Minor, Open: 2\""));
    assertTrue(statusTable.contains("aria-label=\"Minor, Closed: 1\""));
    assertTrue(statusTable.contains("aria-label=\"Minor, Total: 3\""));
    assertTrue(emailTable(html, "defect-distribution").contains(">Total (8)</th>"));
    assertTrue(statusTable.contains(">Open (6)</th>"));
    assertTrue(statusTable.contains(">Closed (2)</th>"));
    assertTrue(statusTable.contains(">Total (8)</th>"));
    assertFalse(statusTable.contains("Total Defects"));
    assertTrue(html.contains("Criteria evaluation"));
    String criteriaTable = emailTable(html, "criteria-evaluation");
    assertTrue(criteriaTable.contains("regressions.executionRate == 100%"));
    assertTrue(criteriaTable.contains("regressions.passRate &gt;= 95%"));
    assertTrue(html.contains("Defect Logging Compliance"));
    assertTrue(html.contains("data-octane-email-section=\"criteria-reconciliation\""));
    assertTrue(html.contains("data-octane-email-table=\"defect-reconciliation\""));
    assertTrue(html.indexOf("Project Details") < html.indexOf("Defect Distribution Matrix"));
    assertTrue(html.indexOf("Defect Distribution Matrix") < html.indexOf("Criteria evaluation"));
    assertTrue(html.contains("border:2px solid #374151;padding:16px"));
    int reportStart = html.indexOf("data-octane-email-section=\"execution-report\"");
    int screenshotStart = html.indexOf("src=\"cid:octane-report-zone.png\"");
    int signOffStart = html.indexOf("Thanks.");
    int reportEnd = html.lastIndexOf("</td></tr></table>", signOffStart);
    assertTrue(reportStart >= 0);
    assertTrue(reportStart < screenshotStart);
    assertTrue(screenshotStart < reportEnd);
    assertTrue(reportEnd < signOffStart);
    assertEquals(1, occurrences(html, "data-octane-email-section=\"execution-report\""));
    assertEquals(7, occurrences(html, "font-size:16px;font-weight:600;line-height:1.25"));
    assertTrue(html.contains("Execution graph</td>"));
    assertTrue(html.contains("font-size:15px;font-weight:600;line-height:1.4"));
    assertTrue(html.contains("font-size:15px;font-weight:400;line-height:1.4"));
    assertTrue(html.contains("padding:4px 8px"));
    assertTrue(html.contains("height:28px;line-height:1px;"));
    assertFalse(html.contains("height:12px;line-height:1px;"));
    assertFalse(html.contains("height:24px;line-height:1px;"));
    assertTrue(html.contains("border-collapse:collapse;margin:0;width:100%"));
    assertTrue(html.contains("font-size:0;line-height:0;padding:0;\"><img"));
    assertTrue(html.contains("margin:0;max-width:100%;padding:0;width:100%"));
    assertTrue(html.contains("table-layout:fixed"));
    assertTrue(html.contains("<colgroup>"));
    assertTrue(html.contains("src=\"cid:octane-report-zone.png\""));
    assertTrue(html.contains("gate execution report charts"));
    assertFalse(html.contains("position:absolute"));
  }

  @Test
  public void rendersEscapedProjectSummaryBulletsImmediatelyBeforeCriteria() {
    String html =
        renderCustomizedEmail(
            snapshot(OctaneGateReportState.PASSED, "Gate passed."),
            "*NoSpaceText\n** Child <value>\n***Third level\n**** Fourth level\n*****Fifth level",
            true,
            false,
            "",
            0);

    assertTrue(html.contains("data-octane-email-section=\"project-summary\""));
    assertTrue(html.contains("<li>NoSpaceText"));
    assertTrue(html.contains("list-style-type:disc;padding-left:15px"));
    assertTrue(html.contains("list-style-type:circle;padding-left:15px"));
    assertTrue(html.contains("list-style-type:square;padding-left:15px"));
    assertTrue(html.contains("<li>Child &lt;value&gt;"));
    assertTrue(html.contains("<li>Third level"));
    assertTrue(html.contains("<li>Fourth level"));
    assertTrue(html.contains("<li>Fifth level</li>"));
    assertEquals(2, occurrences(html, "list-style-type:disc;padding-left:15px"));
    assertEquals(2, occurrences(html, "list-style-type:circle;padding-left:15px"));
    assertTrue(
        html.indexOf("data-octane-email-section=\"project-summary\"")
            < html.indexOf("To get a green light for release"));

    String singleBullet =
        renderCustomizedEmail(
            snapshot(OctaneGateReportState.PASSED, "Gate passed."),
            "*NoSpaceText",
            true,
            false,
            "",
            0);
    assertTrue(singleBullet.contains("<li>NoSpaceText</li>"));
  }

  @Test
  public void rendersInlineMarkdownAfterTemplateVariableInterpolation() {
    String template =
        "**bold word**\n_italic word_\n_**bold italic word**_\n**{{PROJECT_NAME}}**\n"
            + "**first phrase** and **second phrase**";

    String html =
        renderer.render(
            template,
            "Global Pay",
            "Domain",
            snapshot(OctaneGateReportState.PASSED, "Gate passed."),
            REPORT_URL,
            "octane-report-zone.png");

    assertTrue(html.contains("<strong>bold word</strong>"));
    assertTrue(html.contains("<em>italic word</em>"));
    assertTrue(html.contains("<strong><em>bold italic word</em></strong>"));
    assertTrue(html.contains("<strong>Global Pay</strong>"));
    assertTrue(html.contains("<strong>first phrase</strong> and <strong>second phrase</strong>"));
  }

  @Test
  public void rendersSummaryIndentationBeforeInlineMarkdown() {
    String html =
        renderCustomizedEmail(
            snapshot(OctaneGateReportState.PASSED, "Gate passed."),
            "*make money **or building dormitory**",
            true,
            false,
            "",
            0);

    assertTrue(html.contains("<li>make money <strong>or building dormitory</strong></li>"));
    assertFalse(html.contains("<strong>*make money"));
  }

  @Test
  public void omitsProjectSummaryWhenEmptyOrDisabled() {
    OctaneGateReportSnapshot snapshot = snapshot(OctaneGateReportState.PASSED, "Gate passed.");

    assertFalse(
        renderCustomizedEmail(snapshot, "*Hidden", false, false, "", 0)
            .contains("data-octane-email-section=\"project-summary\""));
    assertFalse(
        renderCustomizedEmail(snapshot, "  ", true, false, "", 0)
            .contains("data-octane-email-section=\"project-summary\""));
  }

  @Test
  public void filtersSortsAndLimitsDefectsBelowExecutionScreenshot() {
    String html =
        renderCustomizedEmail(
            snapshot(OctaneGateReportState.PASSED, "Gate passed."), "", false, true, "major", 3);
    String table = emailTable(html, "defects");

    assertTrue(table.contains(">ID</th>"));
    assertTrue(table.contains(">Description</th>"));
    assertTrue(table.contains(">Severity</th>"));
    assertTrue(table.contains(">Status</th>"));
    assertTrue(table.contains(">1</td>"));
    assertTrue(table.contains(">2</td>"));
    assertTrue(table.contains(">3</td>"));
    assertFalse(table.contains(">4</td>"));
    assertTrue(table.contains(">Major</td>"));
    assertTrue(table.indexOf(">1</td>") < table.indexOf(">2</td>"));
    assertTrue(table.contains("data-octane-defect-overflow=\"true\""));
    assertTrue(html.indexOf("src=\"cid:octane-report-zone.png\"") < html.indexOf(table));

    String closedOnly =
        emailTable(
            renderCustomizedEmail(
                snapshot(OctaneGateReportState.PASSED, "Gate passed."),
                "",
                false,
                true,
                "Closed",
                0),
            "defects");
    assertTrue(closedOnly.contains(">7</td>"));
    assertTrue(closedOnly.contains(">8</td>"));
    assertFalse(closedOnly.contains(">1</td>"));
    assertFalse(closedOnly.contains("data-octane-defect-overflow=\"true\""));
  }

  @Test
  public void rendersCombinedDefectDescriptionsInTheEmailTable() {
    List<DefectRecord> defects =
        List.of(
            new DefectRecord(
                "101",
                "Rosebella",
                "<html><body><p>error on dormant refund</p>"
                    + "<p>Trace Id :24ba66e4</p><br>"
                    + "<p>Solution :Contact admin</p></body></html>",
                "High",
                "",
                "opened",
                "run",
                "test",
                "",
                ""),
            new DefectRecord("102", "DEF-102", "", "High", "", "opened", "run", "test", "", ""),
            new DefectRecord(
                "103", "", "Timeout on Checkout", "High", "", "opened", "run", "test", "", ""));
    String table =
        emailTable(
            renderCustomizedEmail(snapshotWithDefects(defects), "", false, true, "", 0), "defects");

    assertTrue(table.contains(">Description</th>"));
    assertFalse(table.contains(">Name</th>"));
    assertTrue(
        table.contains(
            ">Rosebella:<br>error on dormant refund<br>Trace Id :24ba66e4"
                + "<br>Solution :Contact admin</td>"));
    assertFalse(table.contains("&lt;html&gt;"));
    assertFalse(table.contains("&lt;body&gt;"));
    assertTrue(table.contains(">DEF-102</td>"));
    assertTrue(table.contains(">Timeout on Checkout</td>"));
  }

  @Test
  public void countsExactFilteredDefectsBeforeApplyingTheDisplayLimit() {
    OctaneGateReportSnapshot snapshot = defectOverflowSnapshot();

    String overflow = renderCustomizedEmail(snapshot, "", false, true, "major, open", 2);
    String atThreshold = renderCustomizedEmail(snapshot, "", false, true, "major, open", 3);
    String table = emailTable(overflow, "defects");

    assertTrue(table.contains("data-octane-error-count=\"3\""));
    assertTrue(table.contains(">1</td>"));
    assertTrue(table.contains(">2</td>"));
    assertFalse(table.contains(">3</td>"));
    assertFalse(atThreshold.contains("data-octane-defect-overflow=\"true\""));
  }

  @Test
  public void countsAllDefectsForAFalsyFilterAndLinksToFocusedFailureAnalysis() {
    OctaneGateReportSnapshot snapshot = defectOverflowSnapshot();

    String overflow = renderCustomizedEmail(snapshot, "", false, true, null, 5);
    String atThreshold = renderCustomizedEmail(snapshot, "", false, true, "", 10);
    String table = emailTable(overflow, "defects");

    assertTrue(table.contains("data-octane-error-count=\"10\""));
    assertTrue(
        table.contains(
            "<td colspan=\"4\" style=\"border:1px solid #d0d7de;"
                + "font-family:Arial,sans-serif;font-size:15px;font-weight:400;"
                + "line-height:1.4;padding:4px 8px;text-align:center;\">"));
    assertTrue(table.contains(">view all defects</a>"));
    assertTrue(table.indexOf(">5</td>") < table.indexOf(">view all defects</a>"));
    assertEquals(1, occurrences(table, ">view all defects</a>"));
    assertTrue(
        table.contains(
            "href=\"https://jenkins.example/job/example/1/octaneSuiteGateReport/"
                + "?octaneFocus=test-management-failures&amp;"
                + "octaneFocusMode=individual#octane-test-management-zone\""));
    assertFalse(atThreshold.contains("data-octane-defect-overflow=\"true\""));
  }

  @Test
  public void omitsDefectTableWhenDisabledOrNoDefectsMatch() {
    OctaneGateReportSnapshot snapshot = snapshot(OctaneGateReportState.PASSED, "Gate passed.");

    assertFalse(
        renderCustomizedEmail(snapshot, "", false, false, "", 0)
            .contains("data-octane-email-table=\"defects\""));
    String noMatches = renderCustomizedEmail(snapshot, "", false, true, "does-not-match", 0);
    assertFalse(noMatches.contains("data-octane-email-table=\"defects\""));
    assertFalse(noMatches.contains(">Defects</caption>"));
  }

  @Test
  public void rendersDefinedScopeImmediatelyAboveExecutionGraphAndOmitsEmptyScope() {
    OctaneGateReportSnapshot populated =
        snapshot(OctaneGateReportState.PASSED, "Gate passed.")
            .withDefinedScope(
                OctaneDefinedScope.parse(
                    "regressions: \"SMTSL, MMI\" - james, security tests - "
                        + "\"Mary, tom, bob\", mini apps"));

    String html =
        renderer.render(
            "", "Project", "Domain", populated, REPORT_URL, "octane-report.png", "LIGHT");
    String empty =
        renderer.render(
            "",
            "Project",
            "Domain",
            snapshot(OctaneGateReportState.PASSED, "Gate passed."),
            REPORT_URL,
            "octane-report.png",
            "LIGHT");

    assertTrue(html.contains("data-octane-email-section=\"defined-scope\""));
    assertTrue(html.contains(">regressions: SMTSL, MMI</td>"));
    assertTrue(html.contains(">james</td>"));
    assertTrue(html.contains(">security tests</td>"));
    assertTrue(html.contains(">Mary, tom, bob</td>"));
    assertFalse(html.contains("&quot;SMTSL, MMI&quot;"));
    assertFalse(html.contains("&quot;Mary, tom, bob&quot;"));
    assertTrue(html.contains(">mini apps</td>"));
    assertTrue(html.substring(html.indexOf(">mini apps</td>")).contains(">-</td>"));
    int scopeTableStart = html.indexOf("data-octane-email-table=\"defined-scope\"");
    int scopeTableEnd = html.indexOf("</table>", scopeTableStart);
    String scopeTable = html.substring(scopeTableStart, scopeTableEnd);
    assertTrue(scopeTable.contains("table-layout:fixed"));
    assertTrue(scopeTable.contains("<col style=\"width:58%;\">"));
    assertTrue(scopeTable.contains("<col style=\"width:42%;\">"));
    assertFalse(scopeTable.contains("text-align:center"));
    assertTrue(scopeTable.contains("word-break:break-word"));
    assertTrue(html.indexOf("Defined Scope") < html.indexOf("Execution graph"));
    assertFalse(empty.contains("data-octane-email-section=\"defined-scope\""));
    assertFalse(empty.contains("No defined scope!"));
  }

  @Test
  public void splitsDefinedScopeTablesAccordingToEmailRowRules() {
    String nine = renderDefinedScopeEmail(9);
    String thirteen = renderDefinedScopeEmail(13);
    String thirtyThree = renderDefinedScopeEmail(33);

    assertEquals(1, occurrences(nine, "data-octane-email-table=\"defined-scope\""));
    assertEquals(2, occurrences(thirteen, "data-octane-email-table=\"defined-scope\""));
    assertEquals(2, occurrences(thirtyThree, "data-octane-email-table=\"defined-scope\""));
    int nineTableStart = nine.indexOf("data-octane-email-table=\"defined-scope\"");
    int nineTableEnd = nine.indexOf("</table>", nineTableStart);
    assertEquals(9, occurrences(nine.substring(nineTableStart, nineTableEnd), "<tr><td"));
    int firstTableStart = thirteen.indexOf("data-octane-email-table=\"defined-scope\"");
    int firstTableEnd = thirteen.indexOf("</table>", firstTableStart);
    String firstTable = thirteen.substring(firstTableStart, firstTableEnd);
    int secondTableStart =
        thirteen.indexOf("data-octane-email-table=\"defined-scope\"", firstTableEnd);
    int secondTableEnd = thirteen.indexOf("</table>", secondTableStart);
    String secondTable = thirteen.substring(secondTableStart, secondTableEnd);
    assertEquals(7, occurrences(firstTable, "<tr><td"));
    assertEquals(6, occurrences(secondTable, "<tr><td"));
    assertTrue(firstTable.contains("Project 7"));
    assertFalse(firstTable.contains("Project 8"));
    assertTrue(secondTable.contains("Project 8"));
    assertTrue(secondTable.contains("Project 13"));
    int thirtyThreeFirstEnd =
        thirtyThree.indexOf(
            "</table>", thirtyThree.indexOf("data-octane-email-table=\"defined-scope\""));
    assertTrue(thirtyThree.substring(0, thirtyThreeFirstEnd).contains("Project 17"));
    assertFalse(thirtyThree.substring(0, thirtyThreeFirstEnd).contains("Project 18"));
  }

  @Test
  public void omitsTesterExecutionSectionWhenDisabledOrUnspecified() {
    OctaneGateReportSnapshot snapshot = testerSnapshot(2);

    String disabled = renderTesterExecutionEmail(snapshot, false);
    String unspecified =
        renderer.render(
            "{{EXECUTION_DETAILS}}\n{{REPORT_SCREENSHOT}}",
            "Project",
            "Domain",
            snapshot,
            REPORT_URL,
            "octane-report.png",
            OctaneReportTheme.LIGHT.name());

    assertFalse(disabled.contains("Execution Status per tester"));
    assertFalse(disabled.contains("data-octane-email-section=\"tester-execution\""));
    assertFalse(disabled.contains("data-octane-email-table=\"tester-execution\""));
    assertFalse(unspecified.contains("Execution Status per tester"));
    assertFalse(unspecified.contains("data-octane-email-section=\"tester-execution\""));
  }

  @Test
  public void rendersTesterExecutionSchemaValuesAndOverallTotalsAboveGraph() {
    Map<String, List<String>> testerStatuses = new LinkedHashMap<>();
    testerStatuses.put(
        "Tester Alpha", List.of("planned", "in_progress", "blocked", "failed", "passed", "passed"));
    testerStatuses.put("Tester Beta", List.of("planned", "passed"));

    String html = renderTesterExecutionEmail(testerSnapshot(testerStatuses), true);
    String table = testerColumnTable(html, "single");
    String header = table.substring(table.indexOf("<thead>"), table.indexOf("</thead>"));

    assertTrue(header.indexOf(">Testers</th>") < header.indexOf(">No Run</th>"));
    assertTrue(header.indexOf(">No Run</th>") < header.indexOf(">Blocked</th>"));
    assertTrue(header.indexOf(">Blocked</th>") < header.indexOf(">Failed</th>"));
    assertTrue(header.indexOf(">Failed</th>") < header.indexOf(">Passed</th>"));
    assertTrue(header.indexOf(">Passed</th>") < header.indexOf(">Total</th>"));
    assertTrue(header.indexOf(">Total</th>") < header.indexOf(">Automation</th>"));
    assertEquals(7, occurrences(header, "<th scope=\"col\""));
    assertEquals(List.of(2, 1, 1, 2, 6), numericCells(testerRow(table, "tester alpha")));
    assertEquals(List.of(1, 0, 0, 1, 2), numericCells(testerRow(table, "tester beta")));
    assertEquals(List.of(3, 1, 1, 3, 8), numericCells(testerTotalRow(html)));
    assertEquals(1, occurrences(html, "data-octane-tester-total=\"true\""));
    assertTrue(html.indexOf("Execution Status per tester") < html.indexOf("Execution graph"));
  }

  @Test
  public void highlightsOnlyTesterNamesWithBlockedOrFailedTests() {
    Map<String, List<String>> testerStatuses = new LinkedHashMap<>();
    testerStatuses.put(
        "Tester A", List.of("planned", "passed", "passed", "passed", "passed", "passed"));
    testerStatuses.put("Tester B", List.of("blocked", "passed", "passed"));
    testerStatuses.put("Tester C", List.of("failed", "failed", "failed", "passed"));

    String html = renderTesterExecutionEmail(testerSnapshot(testerStatuses), true);
    String table = testerColumnTable(html, "single");
    String testerA = testerRow(table, "tester a");
    String testerB = testerRow(table, "tester b");
    String testerC = testerRow(table, "tester c");

    assertFalse(testerA.contains("data-octane-tester-attention=\"true\""));
    assertTrue(testerA.contains("background-color:#f6f8fa;"));
    assertTrue(testerB.contains("data-octane-tester-attention=\"true\""));
    assertTrue(testerB.contains("background-color:#FF9500;color:#000000;"));
    assertTrue(testerC.contains("data-octane-tester-attention=\"true\""));
    assertTrue(testerC.contains("background-color:#FF9500;color:#000000;"));
  }

  @Test
  public void formatsTesterEmailUsernameForTheBreakdownTable() {
    String html = renderTesterExecutionEmail(testerAutomationSnapshot(80), true);
    String table = testerColumnTable(html, "single");

    assertTrue(table.contains(">jane.smith</th>"));
    assertFalse(table.contains("Jane.Smith@example.com"));
  }

  @Test
  public void paintsIndividualAutomationAgainstTargetAndMirrorsAggregateSummary() {
    String html = renderTesterExecutionEmail(testerAutomationSnapshot(80), true);
    String table = testerColumnTable(html, "single");
    String janeAutomation =
        testerAutomationCell(testerRow(table, "jane.smith"), "data-octane-tester-automation");
    String totalAutomation =
        testerAutomationCell(testerTotalRow(html), "data-octane-tester-automation-total");
    String summaryAutomation = detailRow(html, "Automation Usage");

    assertTrue(janeAutomation.contains(">100%</td>"));
    assertTrue(janeAutomation.contains("bgcolor=\"#34C759\""));
    assertTrue(janeAutomation.contains("background-color:#34C759;color:#000000;"));
    assertTrue(janeAutomation.contains("text-align:center;"));

    assertTrue(totalAutomation.contains(">50%</td>"));
    assertTrue(totalAutomation.contains("bgcolor=\"#FF3B30\""));
    assertTrue(totalAutomation.contains("background-color:#FF3B30;color:#000000;"));
    assertAutomationUsageCell(html, "50%", "#FF3B30", "#000000", true);
    assertTrue(summaryAutomation.contains("background-color:#FF3B30;color:#000000;"));
    assertEquals(emailValueCellText(summaryAutomation), emailValueCellText(totalAutomation));
  }

  @Test
  public void balancesTesterExecutionRowsAcrossEmailColumns() {
    String nine = renderTesterExecutionEmail(testerSnapshot(9), true);
    String twentyFive = renderTesterExecutionEmail(testerSnapshot(25), true);
    String fortyThree = renderTesterExecutionEmail(testerSnapshot(43), true);

    assertEquals(1, occurrences(nine, "data-octane-email-table=\"tester-execution\""));
    assertEquals(9, occurrences(testerColumnTable(nine, "single"), "data-octane-tester-row"));
    assertEquals(2, occurrences(twentyFive, "data-octane-email-table=\"tester-execution\""));
    assertEquals(13, occurrences(testerColumnTable(twentyFive, "left"), "data-octane-tester-row"));
    assertEquals(12, occurrences(testerColumnTable(twentyFive, "right"), "data-octane-tester-row"));
    assertEquals(22, occurrences(testerColumnTable(fortyThree, "left"), "data-octane-tester-row"));
    assertEquals(21, occurrences(testerColumnTable(fortyThree, "right"), "data-octane-tester-row"));
    assertEquals(1, occurrences(fortyThree, "data-octane-tester-total=\"true\""));
  }

  @Test
  public void executionTableExcludesSkippedAndNoRunTestsFromPassRate() {
    OctaneGateReportSnapshot snapshot =
        reconciliationSnapshot(
            OctaneGateReportState.POLLING,
            List.of("passed", "passed", "failed", "blocked", "skipped", "planned"),
            0);

    String html = renderExecutionDetails(snapshot, OctaneReportTheme.LIGHT);

    assertTrue(detailRow(html, "Executed test cases").contains(">4</td>"));
    assertTrue(detailRow(html, "No run test cases").contains(">1</td>"));
    assertTrue(detailRow(html, "Skipped test cases").contains(">1</td>"));
    assertTrue(detailRow(html, "Execution rate").contains(">66.7%</td>"));
    assertTrue(passRateRow(html).contains(">50%</td>"));
  }

  @Test
  public void templateRateTokensReuseLiveReportTwoDecimalValues() {
    OctaneGateReportSnapshot snapshot =
        reconciliationSnapshot(
            OctaneGateReportState.POLLING,
            List.of("passed", "passed", "failed", "blocked", "skipped", "planned"),
            0);

    String html =
        renderer.render(
            "Rates: {{EXECUTIONRATE}} execution, {{PASSRATE}} pass.",
            "Payments",
            "Finance",
            snapshot,
            REPORT_URL,
            "octane-progress.png");

    assertTrue(html.contains("Rates: 66.67% execution, 50.00% pass."));
    assertFalse(html.contains("{{EXECUTIONRATE}}"));
    assertFalse(html.contains("{{PASSRATE}}"));
  }

  @Test
  public void rendersOngoingIntervalReportInSystemOrangeWithInlineScreenshot() {
    String html =
        renderer.render(
            """
            The automated job for {{PROJECT_NAME}} tests is {{GATE_RESULT}}, with an execution rate of {{EXECUTIONRATE}}, pass rate of {{PASSRATE}}.
            The latest Octane update was {{LAST_UPDATE}}.

            {{EXECUTION_DETAILS}}

            {{REPORT_SCREENSHOT}}
            """,
            "Payments",
            "Finance",
            snapshot(OctaneGateReportState.POLLING, "Gate polling."),
            REPORT_URL,
            "octane-progress.png",
            OctaneReportTheme.DARK.name());

    assertTrue(html.contains("color:#FF9F0A;font-weight:700;\">ONGOING"));
    assertTrue(html.contains("with an execution rate of 100.00%, pass rate of 90.00%."));
    assertTrue(html.contains("The latest Octane update was 15:00:00."));
    assertFalse(html.contains("{{LAST_UPDATE}}"));
    assertFalse(html.contains("{{EXECUTIONRATE}}"));
    assertFalse(html.contains("{{PASSRATE}}"));
    assertTrue(html.contains("data-octane-email-section=\"execution-report\""));
    assertTrue(html.contains("src=\"cid:octane-progress.png\""));
    assertPassRateCell(html, "#FF9F0A", "#000000", true);
    assertTrue(detailRow(html, "End date").contains(">In Progress</td>"));
    assertTrue(html.contains("Defect Logging Compliance"));
  }

  @Test
  public void rendersFinalTimestampInEndDateCellForCompletedReport() {
    String html =
        renderExecutionDetails(
            snapshot(OctaneGateReportState.PASSED, "Gate passed."), OctaneReportTheme.LIGHT);

    String endDateRow = detailRow(html, "End date");
    assertTrue(endDateRow.contains(">15:00:00 30/06/2026 EAT</td>"));
    assertFalse(endDateRow.contains("In Progress"));
  }

  @Test
  public void reconcilesDefectsForTallyUnderReportedAndSurplusStates() {
    String zeroTally =
        renderExecutionDetails(
            reconciliationSnapshot(OctaneGateReportState.POLLING, List.of("passed"), 0),
            OctaneReportTheme.LIGHT);
    String tally =
        renderExecutionDetails(
            reconciliationSnapshot(
                OctaneGateReportState.PASSED, List.of("passed", "blocked", "failed"), 2),
            OctaneReportTheme.LIGHT);
    String underReported =
        renderExecutionDetails(
            reconciliationSnapshot(
                OctaneGateReportState.FAILED, List.of("blocked", "failed", "failed"), 1),
            OctaneReportTheme.LIGHT);
    String surplus =
        renderExecutionDetails(
            reconciliationSnapshot(OctaneGateReportState.POLLING, List.of("passed"), 2),
            OctaneReportTheme.DARK);
    String closedAfterPassingRerun =
        renderExecutionDetails(
            reconciliationSnapshot(OctaneGateReportState.PASSED, List.of("passed", "passed"), 0, 2),
            OctaneReportTheme.LIGHT);
    String closedDefectsExcludedFromActual =
        renderExecutionDetails(
            reconciliationSnapshot(
                OctaneGateReportState.PASSED, List.of("blocked", "passed"), 1, 2),
            OctaneReportTheme.LIGHT);

    String zeroTallyTable = emailTable(zeroTally, "defect-reconciliation");
    assertReconciliationCounts(zeroTallyTable, 0, 0, 0, 0);
    assertTrue(zeroTallyTable.contains("0% (TALLY)"));

    String tallyTable = emailTable(tally, "defect-reconciliation");
    assertReconciliationCounts(tallyTable, 1, 1, 2, 2);
    assertTrue(tallyTable.contains("0% (TALLY)"));
    assertTrue(tallyTable.contains("border-left:4px solid #34C759"));

    String underReportedTable = emailTable(underReported, "defect-reconciliation");
    assertReconciliationCounts(underReportedTable, 1, 2, 3, 1);
    assertTrue(underReportedTable.contains("66.7% (UNDER-REPORTED)"));
    assertTrue(underReportedTable.contains("border-left:4px solid #FF3B30"));

    String surplusTable = emailTable(surplus, "defect-reconciliation");
    assertReconciliationCounts(surplusTable, 0, 0, 0, 2);
    assertTrue(surplusTable.contains("+100% (SURPLUS)"));
    assertTrue(surplusTable.contains("border-left:4px solid #FF9F0A"));

    String closedAfterPassingRerunTable =
        emailTable(closedAfterPassingRerun, "defect-reconciliation");
    assertReconciliationCounts(closedAfterPassingRerunTable, 0, 0, 0, 0);
    assertTrue(closedAfterPassingRerunTable.contains("0% (TALLY)"));

    String closedDefectsExcludedTable =
        emailTable(closedDefectsExcludedFromActual, "defect-reconciliation");
    assertReconciliationCounts(closedDefectsExcludedTable, 1, 0, 1, 1);
    assertTrue(closedDefectsExcludedTable.contains("0% (TALLY)"));
    assertFalse(closedDefectsExcludedTable.contains("SURPLUS"));
  }

  @Test
  public void placesCriteriaAndReconciliationTablesBesideEachOtherInFinalAndIntervalEmails() {
    OctaneGateReportSnapshot finalSnapshot =
        reconciliationSnapshot(
            OctaneGateReportState.PASSED, List.of("passed", "blocked", "failed"), 2);
    OctaneGateReportSnapshot intervalSnapshot =
        reconciliationSnapshot(
            OctaneGateReportState.POLLING, List.of("passed", "blocked", "failed"), 1);

    String finalHtml = renderExecutionDetails(finalSnapshot, OctaneReportTheme.LIGHT);
    String intervalHtml = renderExecutionDetails(intervalSnapshot, OctaneReportTheme.DARK);

    assertPairedCriteriaAndReconciliationTables(finalHtml);
    assertPairedCriteriaAndReconciliationTables(intervalHtml);
  }

  @Test
  public void paintsPassRateValueCellForThemeAndCriteriaStatus() {
    String lightPass =
        renderExecutionDetails(
            snapshot(OctaneGateReportState.PASSED, "Gate passed."), OctaneReportTheme.LIGHT);
    String lightFail =
        renderExecutionDetails(
            snapshot(OctaneGateReportState.FAILED, "Gate failed."), OctaneReportTheme.LIGHT);
    String darkPass =
        renderExecutionDetails(
            snapshot(OctaneGateReportState.PASSED, "Gate passed."), OctaneReportTheme.DARK);
    String darkFail =
        renderExecutionDetails(
            snapshot(OctaneGateReportState.FAILED, "Gate failed."), OctaneReportTheme.DARK);
    String fallback =
        renderExecutionDetails(
            snapshot(OctaneGateReportState.ERROR, "Gate error."), OctaneReportTheme.LIGHT);

    assertPassRateCell(lightPass, "#34C759", "#000000", true);
    assertPassRateCell(lightFail, "#FF3B30", "#000000", true);
    assertPassRateCell(darkPass, "#30D158", "#000000", true);
    assertPassRateCell(darkFail, "#FF453A", "#000000", true);
    assertPassRateCell(fallback, "transparent", "inherit", false);
  }

  @Test
  public void placesAutomationUsageBelowPassRateAndPaintsItsTargetStatus() {
    String achieved = renderExecutionDetails(automationSnapshot(8, 2, 80), OctaneReportTheme.LIGHT);
    String warning = renderExecutionDetails(automationSnapshot(7, 3, 80), OctaneReportTheme.DARK);
    String action = renderExecutionDetails(automationSnapshot(6, 4, 80), OctaneReportTheme.LIGHT);
    String unavailable =
        renderExecutionDetails(automationSnapshot(0, 0, 80), OctaneReportTheme.LIGHT);

    assertTrue(achieved.indexOf("Pass Rate") < achieved.indexOf("Automation Usage"));
    assertAutomationUsageCell(achieved, "80%", "#34C759", "#000000", true);
    assertAutomationUsageCell(warning, "70%", "#FF9F0A", "#000000", true);
    assertAutomationUsageCell(action, "60%", "#FF3B30", "#000000", true);
    assertAutomationUsageCell(unavailable, "0%", "transparent", "inherit", false);
    assertFalse(detailRow(achieved, "Automation Usage").contains("🔥"));
    assertFalse(detailRow(unavailable, "Automation Usage").contains("🐢"));
    assertFalse(detailRow(achieved, "Automation Usage").contains("role=\"presentation\""));
  }

  @Test
  public void conditionallyRendersDefectGroupsBelowCriteria() {
    String template =
        """
        Set criteria: {{CRITERIA}}

        Click here to {{REPORT_LINK}}.
        """;
    OctaneGateReportSnapshot snapshot = snapshot(OctaneGateReportState.PASSED, "Gate passed.");

    String enabled =
        renderer.render(
            template,
            "Business Payments Secure Checkout",
            "FS_TRIBE_DOMAIN",
            snapshot,
            REPORT_URL,
            "octane-report-zone.png",
            OctaneReportTheme.LIGHT.name(),
            true);
    String disabled =
        renderer.render(
            template,
            "Business Payments Secure Checkout",
            "FS_TRIBE_DOMAIN",
            snapshot,
            REPORT_URL,
            "octane-report-zone.png",
            OctaneReportTheme.LIGHT.name(),
            false);

    assertTrue(enabled.contains("Defect groups: ( "));
    assertTrue(
        enabled.contains(
            "<strong>Major:</strong> Critical, Very High, High, Unspecified ; "
                + "<strong>Minor:</strong> Low, Medium"));
    assertTrue(
        enabled.indexOf("To get a green light for release") < enabled.indexOf("Defect groups:"));
    assertTrue(enabled.indexOf("Defect groups:") < enabled.indexOf("view the report output"));
    assertFalse(disabled.contains("Defect groups:"));
    assertFalse(disabled.contains("margin:8px 0 0;\">Defect groups"));
  }

  @Test
  public void defectGroupsFallbackListsIndependentSeveritiesWhenNoGroupsAreConfigured() {
    String html =
        renderer.render(
            "Set criteria: {{CRITERIA}}",
            "Business Payments Secure Checkout",
            "FS_TRIBE_DOMAIN",
            OctaneGateReportSnapshot.error("Polling error", "100% execution", "1", 30),
            REPORT_URL,
            "octane-report-zone.png",
            OctaneReportTheme.LIGHT.name(),
            true);

    assertTrue(
        html.contains(
            "Defect groups: ( Critical ; Very High ; High ; Medium ; Low ; Unspecified )"));
  }

  @Test
  public void defectStatusTableRendersUngroupedSeveritiesAsStandaloneColumns() {
    String html =
        renderer.render(
            """
            {{EXECUTION_DETAILS}}
            {{REPORT_SCREENSHOT}}
            """,
            "Business Payments Secure Checkout",
            "FS_TRIBE_DOMAIN",
            snapshot(OctaneGateReportState.PASSED, "Gate passed.", "Medium"),
            REPORT_URL,
            "octane-report-zone.png");

    String statusTable = emailTable(html, "defect-status");

    assertTrue(statusTable.contains(">Major</th>"));
    assertTrue(statusTable.contains(">Minor</th>"));
    assertTrue(statusTable.contains(">Low</th>"));
    assertTrue(statusTable.contains("aria-label=\"Low, Open: 1\""));
    assertTrue(statusTable.contains("aria-label=\"Low, Closed: 1\""));
    assertTrue(statusTable.contains("aria-label=\"Low, Total: 2\""));
  }

  private String renderExecutionDetails(
      OctaneGateReportSnapshot snapshot, OctaneReportTheme theme) {
    return renderer.render(
        """
        {{EXECUTION_DETAILS}}
        {{REPORT_SCREENSHOT}}
        """,
        "Business Payments Secure Checkout",
        "FS_TRIBE_DOMAIN",
        snapshot,
        REPORT_URL,
        "octane-report-zone.png",
        theme.name());
  }

  private String renderCustomizedEmail(
      OctaneGateReportSnapshot snapshot,
      String projectSummary,
      boolean includeProjectSummary,
      boolean printDefects,
      String defectFilter,
      int defectLimit) {
    return renderer.render(
        "{{CRITERIA}}\n{{EXECUTION_DETAILS}}\n{{REPORT_SCREENSHOT}}",
        "Business Payments Secure Checkout",
        "FS_TRIBE_DOMAIN",
        snapshot,
        REPORT_URL,
        "octane-report-zone.png",
        OctaneReportTheme.LIGHT.name(),
        false,
        projectSummary,
        includeProjectSummary,
        printDefects,
        defectFilter,
        defectLimit);
  }

  private String renderTesterExecutionEmail(
      OctaneGateReportSnapshot snapshot, boolean printTestersOnEmailBody) {
    return renderer.render(
        "{{EXECUTION_DETAILS}}\n{{REPORT_SCREENSHOT}}",
        "Project",
        "Domain",
        snapshot,
        REPORT_URL,
        "octane-report.png",
        OctaneReportTheme.LIGHT.name(),
        false,
        "",
        false,
        false,
        "",
        0,
        printTestersOnEmailBody);
  }

  private OctaneGateReportSnapshot testerSnapshot(int testerCount) {
    Map<String, List<String>> testerStatuses = new LinkedHashMap<>();
    for (int index = 1; index <= testerCount; index++) {
      testerStatuses.put(String.format("Tester %02d", index), List.of("passed"));
    }
    return testerSnapshot(testerStatuses);
  }

  private OctaneGateReportSnapshot testerSnapshot(Map<String, List<String>> testerStatuses) {
    List<RunRecord> runs = new ArrayList<>();
    int runIndex = 0;
    for (Map.Entry<String, List<String>> entry : testerStatuses.entrySet()) {
      for (String status : entry.getValue()) {
        runIndex++;
        runs.add(
            new RunRecord(
                "tester-run-" + runIndex, "Tester run " + runIndex, status, entry.getKey()));
      }
    }
    GateResult result =
        new GateResult(
            "tester-suite",
            "regressions.executionRate >= 0",
            false,
            false,
            GateMetrics.fromRuns(runs, classifier),
            runs,
            Map.of("tester-suite", runs),
            Map.of(),
            Instant.parse("2026-06-30T12:00:00Z"));
    return OctaneGateReportSnapshot.fromResult(
        OctaneGateReportState.POLLING, "Polling", result, classifier, 30);
  }

  private OctaneGateReportSnapshot testerAutomationSnapshot(int target) {
    List<RunRecord> runs =
        List.of(
            new RunRecord(
                "jane-automated-1",
                "Jane automated 1",
                "passed",
                "Jenkins Agent",
                "Jane.Smith@example.com",
                "jane-test-1",
                "Jane test 1",
                "",
                ""),
            new RunRecord(
                "jane-automated-2",
                "Jane automated 2",
                "passed",
                "Jenkins Agent",
                "Jane.Smith@example.com",
                "jane-test-2",
                "Jane test 2",
                "",
                ""),
            new RunRecord(
                "alex-manual-1",
                "Alex manual 1",
                "passed",
                "Alex Manual",
                "Alex.User@example.com",
                "alex-test-1",
                "Alex test 1",
                "",
                ""),
            new RunRecord(
                "alex-manual-2",
                "Alex manual 2",
                "failed",
                "Alex Manual",
                "Alex.User@example.com",
                "alex-test-2",
                "Alex test 2",
                "",
                ""));
    GateResult result =
        new GateResult(
            "tester-suite",
            "regressions.executionRate >= 0",
            false,
            false,
            GateMetrics.fromRuns(runs, classifier),
            runs,
            Map.of("tester-suite", runs),
            Map.of(),
            Instant.parse("2026-06-30T12:00:00Z"));
    OctaneGateReportSnapshot snapshot =
        OctaneGateReportSnapshot.fromResult(
            OctaneGateReportState.POLLING, "Polling", result, classifier, 30);
    return snapshot
        .withTestMetrics(snapshot.getTestMetrics().withAutomatedTestingTarget(target))
        .withCalculatedTestMetrics(null);
  }

  private String testerColumnTable(String html, String column) {
    String marker = "data-octane-email-column=\"" + column + "\"";
    int markerIndex = html.indexOf(marker);
    assertTrue(markerIndex >= 0, "Missing tester email column " + column);
    int tableStart = html.lastIndexOf("<table", markerIndex);
    int tableEnd = html.indexOf("</table>", markerIndex);
    assertTrue(tableEnd > markerIndex, "Missing tester email column close " + column);
    return html.substring(tableStart, tableEnd + "</table>".length());
  }

  private String testerRow(String table, String tester) {
    int testerIndex = table.indexOf(">" + tester + "</th>");
    assertTrue(testerIndex >= 0, "Missing tester row for " + tester);
    int rowStart = table.lastIndexOf("<tr", testerIndex);
    int rowEnd = table.indexOf("</tr>", testerIndex);
    return table.substring(rowStart, rowEnd + "</tr>".length());
  }

  private String testerTotalRow(String html) {
    int marker = html.indexOf("data-octane-tester-total=\"true\"");
    assertTrue(marker >= 0, "Missing tester total row");
    int rowStart = html.lastIndexOf("<tr", marker);
    int rowEnd = html.indexOf("</tr>", marker);
    return html.substring(rowStart, rowEnd + "</tr>".length());
  }

  private String testerAutomationCell(String row, String attribute) {
    int marker = row.indexOf(attribute + "=\"true\"");
    assertTrue(marker >= 0, "Missing tester automation cell " + attribute);
    int cellStart = row.lastIndexOf("<td", marker);
    int cellEnd = row.indexOf("</td>", marker);
    assertTrue(cellEnd > marker, "Missing tester automation cell bounds " + attribute);
    return row.substring(cellStart, cellEnd + "</td>".length());
  }

  private String emailValueCellText(String html) {
    Matcher matcher = Pattern.compile("<td[^>]*>([^<]*)</td>").matcher(html);
    assertTrue(matcher.find(), "Missing email value cell");
    return matcher.group(1);
  }

  private List<Integer> numericCells(String row) {
    List<Integer> values = new ArrayList<>();
    Matcher matcher = Pattern.compile(">(\\d+)</td>").matcher(row);
    while (matcher.find()) {
      values.add(Integer.parseInt(matcher.group(1)));
    }
    return values;
  }

  private void assertPassRateCell(
      String html, String backgroundColor, String fontColor, boolean expectsBgcolor) {
    String row = passRateRow(html);
    int valueCellStart = row.indexOf("</th><td");
    assertTrue(valueCellStart >= 0, "Pass Rate value cell should follow label cell");
    assertEquals(expectsBgcolor, row.indexOf("bgcolor=", valueCellStart) >= 0);
    if (expectsBgcolor) {
      assertTrue(row.contains("bgcolor=\"" + backgroundColor + "\""));
    }
    assertTrue(row.contains("background-color:" + backgroundColor + ";"));
    assertTrue(row.contains("color:" + fontColor + ";"));
    assertTrue(row.contains(">90%</td>"));
  }

  private void assertAutomationUsageCell(
      String html, String value, String backgroundColor, String fontColor, boolean expectsBgcolor) {
    String row = detailRow(html, "Automation Usage");
    assertEquals(expectsBgcolor, row.contains("bgcolor="));
    if (expectsBgcolor) {
      assertTrue(row.contains("bgcolor=\"" + backgroundColor + "\""));
    }
    assertTrue(row.contains("background-color:" + backgroundColor + ";"));
    assertTrue(row.contains("color:" + fontColor + ";"));
    assertTrue(row.contains(">" + value + "</td>"));
  }

  private String passRateRow(String html) {
    return detailRow(html, "Pass Rate");
  }

  private String detailRow(String html, String labelText) {
    int label = html.indexOf(labelText);
    assertTrue(label >= 0, "Missing " + labelText + " row");
    int start = html.lastIndexOf("<tr>", label);
    int end = html.indexOf("</tr>", label);
    assertTrue(start >= 0 && end > label, "Missing " + labelText + " row bounds");
    return html.substring(start, end + "</tr>".length());
  }

  private void assertReconciliationCounts(
      String table, int blocked, int failed, int expected, int actual) {
    assertTrue(table.contains(">Blocked Tests</th><td"));
    assertTrue(table.contains(">" + blocked + "</td></tr>"));
    assertTrue(table.contains(">Failed Tests</th><td"));
    assertTrue(table.contains(">" + failed + "</td></tr>"));
    assertTrue(table.contains(">Total Expected Defects</th><td"));
    assertTrue(table.contains(">" + expected + "</td></tr>"));
    assertTrue(table.contains(">Actual Open Defects</th><td"));
    assertTrue(table.contains(">" + actual + "</td></tr>"));
  }

  private void assertPairedCriteriaAndReconciliationTables(String html) {
    String marker = "data-octane-email-section=\"criteria-reconciliation\"";
    int markerIndex = html.indexOf(marker);
    assertTrue(markerIndex >= 0, "Missing criteria/reconciliation row");
    int rowStart = html.lastIndexOf("<table", markerIndex);
    int rowEnd = html.indexOf("</table></td></tr></table>", markerIndex);
    assertTrue(rowStart >= 0 && rowEnd > rowStart, "Missing criteria/reconciliation row bounds");
    String row = html.substring(rowStart, rowEnd);
    assertTrue(row.contains("data-octane-email-table=\"criteria-evaluation\""));
    assertTrue(row.contains("data-octane-email-table=\"defect-reconciliation\""));
    assertTrue(
        row.indexOf("data-octane-email-table=\"criteria-evaluation\"")
            < row.indexOf("data-octane-email-table=\"defect-reconciliation\""));
  }

  private OctaneGateReportSnapshot snapshot(OctaneGateReportState state, String message) {
    return snapshot(state, message, "Low, Medium");
  }

  private OctaneGateReportSnapshot snapshot(
      OctaneGateReportState state, String message, String minorTypes) {
    CriteriaEvaluation evaluation =
        CriteriaEvaluation.available(
            state == OctaneGateReportState.PASSED,
            List.of(
                new CriteriaComparisonEvaluation(
                    "regressions.executionRate", "==", 100, 100, true, true),
                new CriteriaComparisonEvaluation(
                    "regressions.passRate", ">=", 95, 92.5, true, false)));
    OctaneDefectGroup major = defectGroup("major", "Critical, Very High, High, Unspecified");
    OctaneDefectGroup minor = defectGroup("minor", minorTypes);
    List<DefectRecord> defects =
        List.of(
            defect("1", "Critical", "opened"),
            defect("2", "Very High", "opened"),
            defect("3", "High", "opened"),
            defect("4", "Medium", "opened"),
            defect("5", "Low", "opened"),
            defect("6", "", "opened"),
            defect("7", "High", "closed"),
            defect("8", "Low", "closed"));
    OctaneDefectSeveritySummary defectSummary = OctaneDefectSeveritySummary.fromDefects(defects);
    GateResult result =
        new GateResult(
            "1",
            "regressions.executionRate == 100 OR regressions.passRate >= 95",
            evaluation.isPassed(),
            true,
            new GateMetrics(10, 10, 9, 1, 0, 0),
            List.of(),
            Map.of(),
            Map.of(),
            OctaneRiskHeatMap.disabled(),
            new DefectCriteriaMetrics(defectSummary, List.of(major, minor)),
            defects,
            evaluation,
            Instant.parse("2026-06-30T12:00:00Z"));
    return OctaneGateReportSnapshot.fromResult(state, message, result, classifier, 30);
  }

  private OctaneGateReportSnapshot snapshotWithDefects(List<DefectRecord> defects) {
    GateResult result =
        new GateResult(
            "suite-1",
            "regressions.executionRate == 100",
            true,
            true,
            new GateMetrics(3, 3, 3, 0, 0, 0),
            List.of(),
            Map.of(),
            Map.of(),
            OctaneRiskHeatMap.disabled(),
            new DefectCriteriaMetrics(OctaneDefectSeveritySummary.fromDefects(defects), List.of()),
            defects,
            CriteriaEvaluation.available(true, List.of()),
            Instant.parse("2026-06-30T12:00:00Z"));
    return OctaneGateReportSnapshot.fromResult(
        OctaneGateReportState.PASSED, "Gate passed.", result, classifier, 30);
  }

  private OctaneGateReportSnapshot reconciliationSnapshot(
      OctaneGateReportState state, List<String> statuses, int defectsRaised) {
    return reconciliationSnapshot(state, statuses, defectsRaised, 0);
  }

  private OctaneGateReportSnapshot defectOverflowSnapshot() {
    OctaneDefectGroup major = defectGroup("major", "Critical, Very High, High, Unspecified");
    OctaneDefectGroup minor = defectGroup("minor", "Low, Medium");
    List<DefectRecord> defects =
        List.of(
            defect("1", "Critical", "opened"),
            defect("2", "Very High", "opened"),
            defect("3", "High", "opened"),
            defect("4", "Critical", "closed"),
            defect("5", "Medium", "opened"),
            defect("6", "Low", "opened"),
            defect("7", "Medium", "closed"),
            defect("8", "Low", "closed"),
            defect("9", "Unspecified", "closed"),
            defect("10", "Low", "closed"));
    CriteriaEvaluation evaluation = CriteriaEvaluation.available(true, List.of());
    GateResult result =
        new GateResult(
            "suite-1",
            "regressions.executionRate == 100",
            true,
            true,
            new GateMetrics(10, 10, 10, 0, 0, 0),
            List.of(),
            Map.of(),
            Map.of(),
            OctaneRiskHeatMap.disabled(),
            new DefectCriteriaMetrics(
                OctaneDefectSeveritySummary.fromDefects(defects), List.of(major, minor)),
            defects,
            evaluation,
            Instant.parse("2026-06-30T12:00:00Z"));
    return OctaneGateReportSnapshot.fromResult(
        OctaneGateReportState.PASSED, "Gate passed.", result, classifier, 30);
  }

  private OctaneGateReportSnapshot reconciliationSnapshot(
      OctaneGateReportState state, List<String> statuses, int openDefects, int closedDefects) {
    List<RunRecord> runs = new ArrayList<>();
    for (int index = 0; index < statuses.size(); index++) {
      runs.add(new RunRecord("run-" + index, "Run " + index, statuses.get(index), "Test Engineer"));
    }
    List<DefectRecord> defects = new ArrayList<>();
    for (int index = 0; index < openDefects; index++) {
      defects.add(defect("reconciliation-defect-" + index, "High", "opened"));
    }
    for (int index = 0; index < closedDefects; index++) {
      defects.add(defect("closed-reconciliation-defect-" + index, "High", "closed"));
    }
    CriteriaEvaluation evaluation =
        CriteriaEvaluation.available(
            state == OctaneGateReportState.PASSED,
            List.of(
                new CriteriaComparisonEvaluation(
                    "regressions.executionRate", "==", 100, 100, true, true)));
    GateResult result =
        new GateResult(
            "suite-1",
            "regressions.executionRate == 100",
            evaluation.isPassed(),
            true,
            GateMetrics.fromRuns(runs, classifier),
            runs,
            Map.of("suite-1", runs),
            Map.of(),
            OctaneRiskHeatMap.disabled(),
            new DefectCriteriaMetrics(OctaneDefectSeveritySummary.fromDefects(defects), List.of()),
            defects,
            evaluation,
            Instant.parse("2026-06-30T12:00:00Z"));
    return OctaneGateReportSnapshot.fromResult(
        state, "Reconciliation snapshot.", result, classifier, 30);
  }

  private OctaneGateReportSnapshot automationSnapshot(int automated, int manual, int target) {
    List<RunRecord> runs = new ArrayList<>();
    for (int index = 0; index < automated; index++) {
      runs.add(
          new RunRecord(
              "automated-" + index,
              "Automated " + index,
              "passed",
              "Jenkins Agent",
              "Assigned Tester",
              "test-a-" + index,
              "Test",
              "",
              ""));
    }
    for (int index = 0; index < manual; index++) {
      runs.add(
          new RunRecord(
              "manual-" + index,
              "Manual " + index,
              "passed",
              "Assigned Tester",
              "Assigned Tester",
              "test-m-" + index,
              "Test",
              "",
              ""));
    }
    GateResult result =
        new GateResult(
            "suite-1",
            "regressions.executionRate == 100",
            true,
            true,
            GateMetrics.fromRuns(runs, classifier),
            runs,
            Map.of("suite-1", runs),
            Map.of(),
            Instant.parse("2026-06-30T12:00:00Z"));
    OctaneGateReportSnapshot snapshot =
        OctaneGateReportSnapshot.fromResult(
            OctaneGateReportState.PASSED, "Gate passed.", result, classifier, 30);
    return snapshot
        .withTestMetrics(snapshot.getTestMetrics().withAutomatedTestingTarget(target))
        .withCalculatedTestMetrics(null);
  }

  private String renderDefinedScopeEmail(int count) {
    List<OctaneDefinedScope> scopes = new ArrayList<>();
    for (int index = 1; index <= count; index++) {
      scopes.add(new OctaneDefinedScope("Project " + index, "Owner " + index));
    }
    OctaneGateReportSnapshot snapshot =
        snapshot(OctaneGateReportState.PASSED, "Gate passed.").withDefinedScope(scopes);
    return renderer.render(
        "", "Project", "Domain", snapshot, REPORT_URL, "octane-report.png", "LIGHT");
  }

  private OctaneDefectGroup defectGroup(String name, String types) {
    OctaneDefectGroup group = new OctaneDefectGroup(name);
    group.setTypes(types);
    return group;
  }

  private DefectRecord defect(String id, String severity, String phase) {
    return new DefectRecord(id, "Defect " + id, severity, "", phase, "run", "test", "", "");
  }

  private int occurrences(String value, String expected) {
    int count = 0;
    int index = value.indexOf(expected);
    while (index >= 0) {
      count++;
      index = value.indexOf(expected, index + expected.length());
    }
    return count;
  }

  private String emailTable(String html, String tableName) {
    String marker = "data-octane-email-table=\"" + tableName + "\"";
    int start = html.indexOf(marker);
    assertTrue(start >= 0, "Missing email table " + tableName);
    int tableStart = html.lastIndexOf("<table", start);
    int tableEnd = html.indexOf("</table>", start);
    assertTrue(tableStart >= 0 && tableEnd > start, "Missing email table close for " + tableName);
    return html.substring(tableStart, tableEnd + "</table>".length());
  }
}
