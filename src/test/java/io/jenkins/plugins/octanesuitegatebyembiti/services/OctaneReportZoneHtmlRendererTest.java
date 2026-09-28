package io.jenkins.plugins.octanesuitegatebyembiti.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.jenkins.plugins.octanesuitegatebyembiti.entities.RunRecord;
import io.jenkins.plugins.octanesuitegatebyembiti.models.GateMetrics;
import io.jenkins.plugins.octanesuitegatebyembiti.models.GateResult;
import io.jenkins.plugins.octanesuitegatebyembiti.models.GateScopeResult;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneGateReportSnapshot;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneGateReportState;
import io.jenkins.plugins.octanesuitegatebyembiti.models.StatusClassifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class OctaneReportZoneHtmlRendererTest {
  private final StatusClassifier classifier =
      new StatusClassifier(
          StatusClassifier.DEFAULT_PASSED_STATUSES,
          StatusClassifier.DEFAULT_FAILED_STATUSES,
          StatusClassifier.DEFAULT_NEUTRAL_STATUSES,
          StatusClassifier.DEFAULT_RUNNING_STATUSES);

  @Test
  public void rendersOnlyReportZoneForEmailScreenshot() {
    OctaneGateReportSnapshot snapshot =
        OctaneGateReportSnapshot.fromResult(
            OctaneGateReportState.PASSED, "Passed", result(), classifier, 30);

    String html = new OctaneReportZoneHtmlRenderer().render(snapshot);

    assertTrue(html.contains("id=\"octane-report-zone\""));
    assertTrue(html.contains("REGRESSION Tests Status Distribution"));
    assertTrue(html.contains("CRITICAL Tests Status Distribution"));
    assertTrue(html.contains("Testing progress per Tester Suite Runs_REGRESSIONS"));
    assertTrue(html.contains("Testing progress per Tester Suite Runs_CRITICAL"));
    assertTrue(html.contains(">3</text>"));
    assertTrue(html.contains(">Total test cases</text>"));
    assertTrue(html.contains("Total Suiteruns: 2"));
    assertTrue(html.contains("Ada Tester"));
    assertTrue(html.contains("ada tester"));
    assertTrue(html.contains("Ben Tester"));
    assertTrue(html.contains("ben tester"));
    assertTrue(html.contains("suite runs: 4501"));
    assertFalse(html.contains("Total Testcases"));
    assertTrue(html.contains("<html data-octane-theme=\"light\">"));
    assertTrue(html.contains("<meta name=\"color-scheme\" content=\"light\""));
    assertTrue(html.contains("color-scheme: light"));
    assertTrue(html.contains("--octane-page-background: #f5f7fb"));
    assertTrue(html.contains("border: 1px solid var(--octane-page-background)"));
    assertTrue(html.matches("(?s).*body \\{[^}]*margin: 0;[^}]*padding: 0;[^}]*}.*"));
    assertTrue(
        html.matches("(?s).*\\.octane-report-zone \\{[^}]*gap: 16px;[^}]*padding: 16px;[^}]*}.*"));
    assertFalse(html.contains("min-height: 100vh"));
    assertTrue(html.contains("data-octane-capture-height"));
    assertTrue(html.contains("reportZone.getBoundingClientRect().bottom"));
    assertTrue(html.contains("aria-label=\"Move widget\""));
    assertTrue(html.contains("octane-grabber-icon"));
    assertTrue(html.contains("Use arrow keys to reorder"));
    assertTrue(html.contains("data-card-key=\"distribution-regressions\""));
    assertTrue(html.contains("data-card-key=\"bars-regressions\""));
    assertTrue(html.contains("data-card-key=\"distribution-critical\""));
    assertTrue(html.contains("data-card-key=\"bars-critical\""));
    assertFalse(html.contains("octane-card-actions"));
    assertFalse(html.contains("octane-expand-toggle"));
    assertFalse(html.contains("octane-zone-focus-toggle"));
    assertFalse(html.contains("octane-icon-expand"));
    assertFalse(html.contains("octane-icon-collapse"));
    assertFalse(html.contains("octane-icon-zone-expand"));
    assertFalse(html.contains("octane-icon-zone-collapse"));
    assertFalse(html.contains("octane-expanded"));
    assertFalse(html.contains("octane-zone-focused"));
    assertTrue(html.contains("--octane-status-passed: #34C759"));
    assertTrue(html.contains("--octane-status-failed: #FF3B30"));
    assertTrue(html.contains("--octane-status-blocked: #FF9500"));
    assertTrue(html.contains("--octane-status-skipped: #AF52DE"));
    assertTrue(html.contains("--octane-status-no-run: #8E8E93"));
    assertTrue(html.contains("var(--octane-status-passed)"));
    assertTrue(html.contains("var(--octane-status-failed)"));
    assertTrue(html.contains("var(--octane-status-no-run)"));
    assertFalse(html.contains("#009900"));
    assertFalse(html.contains("#631919"));
    assertFalse(html.contains("#ffb74d"));
    assertFalse(html.contains("#808080"));
    assertTrue(html.contains("octane-donut"));
    assertTrue(html.contains("octane-chart-inner octane-donut-graph"));
    assertTrue(html.contains("octane-donut-layout"));
    assertTrue(html.contains("gap: clamp(2px, 0.75cqw, 6px)"));
    assertTrue(html.contains("octane-donut-center-value"));
    assertTrue(html.contains("octane-donut-center-label"));
    assertTrue(html.contains("octane-donut-legend"));
    assertTrue(html.contains("octane-donut-legend-percentage"));
    assertEquals(2, occurrences(html, "data-automation-usage-row=\"true\""));
    assertTrue(html.contains(">🐢</span><span>Automation Usage</span>"));
    assertTrue(html.contains(">0%</td>"));
    assertFalse(html.contains("octane-donut-label"));
    assertFalse(html.contains("octane-donut-callout-line"));
    assertTrue(html.contains("viewBox=\"3 3 94 94\""));
    assertFalse(html.contains("max-width: 340px"));
    assertFalse(html.contains("min-height: 294px"));
    assertTrue(html.contains("padding: 5px"));
    assertTrue(html.contains("height: calc(260px + var(--octane-axis-label-row))"));
    assertTrue(html.contains("r=\"46\" fill="));
    assertTrue(html.contains("r=\"37.36\""));
    assertTrue(html.contains("aspect-ratio: 1 / 1"));
    assertTrue(html.contains("container-type: size"));
    assertTrue(html.contains("height: min(100cqw, 100cqh)"));
    assertTrue(html.contains("width: min(100cqw, 100cqh)"));
    assertTrue(html.contains("max-height: none"));
    assertTrue(html.contains("max-width: none"));
    assertFalse(html.contains("max-height: 248.1804px"));
    assertFalse(html.contains("max-width: 248.1804px"));
    assertTrue(html.contains(">Total test cases</text>"));
    assertTrue(html.contains("Total test cases: 3"));
    assertTrue(html.contains("padding-inline: 0 clamp(8px, 2.2cqw, 25px)"));
    assertFalse(html.contains("octane-legend-value"));
    assertTrue(html.contains("octane-suite-chart-meta"));
    assertTrue(html.contains("octane-bar-graph"));
    assertTrue(html.contains("octane-y-axis-label"));
    assertTrue(html.contains(">Count<"));
    assertTrue(html.contains("#827C7B"));
    assertTrue(html.contains("background-image: radial-gradient"));
    assertTrue(html.contains("rgba(33, 38, 45, 0.92)"));
    assertTrue(html.contains("background-size: 9px calc(100% / var(--octane-grid-line-count, 4))"));
    assertTrue(html.contains("#30363D"));
    assertTrue(html.contains("octane-grid-line-count"));
    assertFalse(html.contains("border-left: 1px solid #576779"));
    assertFalse(html.contains("#CCCCCC"));
    assertTrue(html.contains("column-gap: 1px"));
    assertTrue(html.contains("grid-template-columns: 22px max-content minmax(0, 1fr)"));
    assertTrue(html.contains("--octane-axis-label-row: 27px"));
    assertTrue(html.contains("grid-template-rows: 260px var(--octane-axis-label-row)"));
    assertTrue(html.contains("octane-bar-plot"));
    assertTrue(html.contains("octane-vertical-bars"));
    assertTrue(html.contains("octane-vertical-bar-wrap"));
    assertFalse(html.contains("octane-x-axis-labels"));
    assertFalse(html.contains("octane-axis-label-column"));
    assertTrue(html.contains("overflow-x: hidden"));
    assertTrue(html.contains("grid-template-rows: minmax(0, 1fr) var(--octane-axis-label-row)"));
    assertTrue(html.contains("flex-basis: var(--octane-bar-width"));
    assertTrue(html.contains("max-width: 100px"));
    assertTrue(html.contains("min-width: 8px !important"));
    assertTrue(html.contains("width: 100%"));
    assertTrue(html.contains("font-family: Inter, \"Segoe UI\", Arial, sans-serif"));
    assertTrue(html.contains("font-size: 12px"));
    assertTrue(html.contains("font-weight: 400"));
    assertTrue(html.contains("text-align: center"));
    assertTrue(html.contains("transform: none"));
    assertFalse(html.contains("rotate(-45deg)"));
    assertTrue(html.contains("octane-bar-popup"));
    assertTrue(html.contains("data-card-key=\"bars-regressions\""));
    assertTrue(html.contains("data-bar-key=\""));
    assertTrue(html.contains("data-dominant-status-color=\""));
    assertTrue(html.contains("data-dominant-status-label=\""));
    assertTrue(
        html.contains("border: 1px solid var(--octane-popup-border-color, var(--octane-border))"));
    assertTrue(html.contains("0 0 0 1px var(--octane-popup-border-color, transparent)"));
    assertTrue(html.contains("style=\"--octane-popup-border-color: #30D158;\""));
    assertTrue(html.contains("style=\"--octane-popup-border-color: #FF453A;\""));
    assertTrue(html.contains("data-status-passed-count=\""));
    assertTrue(html.contains("data-status-passed-color=\"#30D158\""));
    assertTrue(html.contains("data-status-failed-color=\"#FF453A\""));
    assertTrue(html.contains("data-status-blocked-color=\"#FF9F0A\""));
    assertTrue(html.contains("data-status-skipped-color=\"#BF5AF2\""));
    assertTrue(html.contains("data-status-running-color=\"#8E8E93\""));
    assertTrue(html.contains("data-automation-percentage=\"0\""));
    assertTrue(html.contains("data-automation-emoji=\"🐢\""));
    assertTrue(html.contains("style=\"background: #30D158;\""));
    assertTrue(html.contains("style=\"background: #FF453A;\""));
    assertTrue(html.contains("min-width: 175px"));
    assertTrue(html.contains("font-size: 10.35px"));
    assertTrue(html.contains("overflow-wrap: anywhere"));
    assertTrue(html.contains("octane-bar-popup-name"));
    assertTrue(html.contains("octane-bar-popup-row"));
    assertTrue(html.contains("octane-bar-popup-total"));
    assertTrue(html.contains("octane-bar-popup-automation"));
    int firstPopup = html.indexOf("class=\"octane-bar-popup\"");
    int firstTotal = html.indexOf("class=\"octane-bar-popup-total\"", firstPopup);
    int firstAutomation = html.indexOf("class=\"octane-bar-popup-automation\"", firstPopup);
    assertTrue(firstAutomation < firstTotal);
    assertFalse(html.contains("class=\"octane-bar-overflow-indicator\""));
    assertFalse(html.contains("class=\"octane-total\""));
    assertFalse(html.contains("id=\"octane-timer-zone\""));
    assertFalse(html.contains("Testing Time Remaining"));
    assertFalse(html.contains("Status Check"));
    assertFalse(html.contains("Time to next Poll"));
    assertFalse(html.contains("Execution Progress"));
  }

  @Test
  public void rendersCustomMainGraphTitlesAndPreservesDefaultFallbacks() {
    OctaneGateReportSnapshot baseSnapshot =
        OctaneGateReportSnapshot.fromResult(
            OctaneGateReportState.PASSED, "Passed", result(), classifier, 30);
    OctaneGateReportSnapshot customSnapshot =
        baseSnapshot.withGraphTitles("RELEASE REGRESSION", "RELEASE BLOCKERS");

    String customHtml = new OctaneReportZoneHtmlRenderer().render(customSnapshot);
    String defaultHtml = new OctaneReportZoneHtmlRenderer().render(baseSnapshot);

    assertTrue(customHtml.contains("RELEASE REGRESSION Status Distribution"));
    assertTrue(customHtml.contains("RELEASE REGRESSION Suiterun Distribution"));
    assertTrue(customHtml.contains("RELEASE BLOCKERS Status Distribution"));
    assertTrue(customHtml.contains("RELEASE BLOCKERS Suiterun Distribution"));
    assertFalse(customHtml.contains("REGRESSION Tests Status Distribution"));
    assertFalse(customHtml.contains("CRITICAL Tests Status Distribution"));
    assertTrue(defaultHtml.contains("REGRESSION Tests Status Distribution"));
    assertTrue(defaultHtml.contains("Testing progress per Tester Suite Runs_CRITICAL"));
  }

  @Test
  public void rendersThinDistributionSlicesWithTabularLegendAndNoCallouts() {
    OctaneGateReportSnapshot snapshot = thinSliceSnapshot();

    String html = new OctaneReportZoneHtmlRenderer().render(snapshot);

    assertEquals(5, occurrences(html, "class=\"octane-donut-segment\""));
    assertFalse(html.contains("octane-donut-callout-line"));
    assertFalse(html.contains("data-label-mode"));
    assertTrue(html.contains("class=\"octane-donut-center-value\""));
    assertTrue(html.contains("class=\"octane-donut-center-label\""));
    assertTrue(html.contains("class=\"octane-donut-legend\""));
    assertTrue(html.contains("90.00%"));
    assertTrue(html.contains("1.00%"));
    assertTrue(html.contains(".octane-donut-segment {"));
    assertTrue(html.contains(".octane-donut-segment {\n  stroke: none;"));
    assertFalse(
        html.contains(".octane-donut-segment {\n" + "  stroke: var(--octane-card-background)"));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void rendersOctaneSixteenNotCompletedAsAnActiveStatusWithZeroSkippedTests() {
    List<RunRecord> runs = new ArrayList<>();
    for (int index = 0; index < 4; index++) {
      runs.add(new RunRecord("passed-" + index, "Passed " + index, "passed", "Ada Tester"));
    }
    runs.add(
        new RunRecord(
            "active-1", "Active", "list_node.run_native_status.not_completed", "Ada Tester"));
    GateMetrics metrics = GateMetrics.fromRuns(runs, classifier);
    GateResult result =
        new GateResult(
            "active-suite",
            "regressions.completionRate == 100",
            false,
            false,
            metrics,
            runs,
            Map.of("active-suite", runs),
            Map.of(),
            Instant.parse("2026-08-26T08:00:00Z"));
    OctaneGateReportSnapshot snapshot =
        OctaneGateReportSnapshot.fromResult(
            OctaneGateReportState.POLLING, "Polling", result, classifier, 30);

    String html = new OctaneReportZoneHtmlRenderer().renderZone(snapshot);
    OctaneReportDataMapper.ReportData data = new OctaneReportDataMapper().map(snapshot);
    List<Map<String, Object>> bars = (List<Map<String, Object>>) data.sections().get(0).get("bars");
    List<Map<String, Object>> statuses = (List<Map<String, Object>>) bars.get(0).get("statuses");
    Map<String, Object> inProgress =
        statuses.stream()
            .filter(status -> "running".equals(status.get("key")))
            .findFirst()
            .orElseThrow();

    assertEquals(4, metrics.getPassed());
    assertEquals(1, metrics.getRunning());
    assertEquals(0, metrics.getSkipped());
    assertEquals(80.0, metrics.getCompletionRate(), 0.000001);
    assertFalse(metrics.isTerminal());
    assertEquals(1, bars.get(0).get("inProgress"));
    assertEquals("In Progress", inProgress.get("label"));
    assertEquals(1, inProgress.get("count"));
    assertTrue(html.contains("data-status-skipped-count=\"0\""));
    assertTrue(html.contains("data-status-running-count=\"1\""));
    assertTrue(html.contains("data-status-in-progress-count=\"1\""));
    assertTrue(html.contains("data-status-in-progress-label=\"In Progress\""));
    assertTrue(html.contains(">In Progress</span>"));
  }

  @Test
  public void omitsDonutAutomationLegendUntilTheSectionHasExecutedTests() {
    RunRecord planned = new RunRecord("planned", "Planned", "planned", "Ada Tester");
    GateResult result =
        new GateResult(
            "planned-suite",
            "100% execution",
            false,
            false,
            new GateMetrics(1, 0, 0, 0, 0, 1),
            List.of(planned),
            Map.of("planned-suite", List.of(planned)),
            Map.of(),
            Instant.parse("2026-08-04T10:00:00Z"));
    OctaneGateReportSnapshot snapshot =
        OctaneGateReportSnapshot.fromResult(
            OctaneGateReportState.POLLING, "Polling", result, classifier, 30);

    String html = new OctaneReportZoneHtmlRenderer().render(snapshot);

    assertFalse(html.contains("data-automation-usage-row=\"true\""));
    assertTrue(html.contains("octane-bar-popup-automation"));
  }

  @Test
  public void truncatesDenseEmailChartsWithoutTruncatingLiveRefreshData() {
    OctaneGateReportSnapshot snapshot = denseSnapshot(205);
    OctaneReportZoneHtmlRenderer renderer = new OctaneReportZoneHtmlRenderer();

    String narrowEmailHtml = renderer.render(snapshot, "LIGHT", 600);
    String wideEmailHtml = renderer.render(snapshot, "LIGHT", 1400);
    String liveHtml = renderer.renderZone(snapshot);

    assertEquals(41, occurrences(narrowEmailHtml, "class=\"octane-suite-column\""));
    assertTrue(narrowEmailHtml.contains("class=\"octane-bar-overflow-indicator\""));
    assertTrue(narrowEmailHtml.contains("data-hidden-count=\"164\""));
    assertTrue(narrowEmailHtml.contains("class=\"octane-bar-overflow-line\""));
    assertTrue(narrowEmailHtml.contains("class=\"octane-bar-overflow-count\">+164"));
    assertFalse(narrowEmailHtml.contains("more..."));
    assertTrue(narrowEmailHtml.contains("border-bottom: 2px dashed #666"));
    assertTrue(narrowEmailHtml.contains("flex: 0 0 24px"));
    assertFalse(narrowEmailHtml.contains("margin-inline-start: auto"));
    assertTrue(narrowEmailHtml.contains("max-width: 24px"));
    assertTrue(narrowEmailHtml.contains("min-width: 24px"));
    assertTrue(narrowEmailHtml.contains("width: 24px"));
    assertTrue(narrowEmailHtml.contains("flex: 1 1 auto"));
    assertTrue(narrowEmailHtml.contains("min-width: 8px !important"));
    assertTrue(narrowEmailHtml.contains("max-width: 100px"));
    assertFalse(narrowEmailHtml.contains("margin-right: 2px !important"));
    assertTrue(narrowEmailHtml.contains("gap: var(--octane-bar-gap"));
    assertTrue(narrowEmailHtml.contains("--octane-bar-width: 8.024px"));
    assertTrue(narrowEmailHtml.contains("--octane-bar-gap: 2.024px"));
    assertTrue(narrowEmailHtml.contains("padding: 0"));
    assertTrue(narrowEmailHtml.contains("class=\"octane-vertical-bars octane-fluid-bars-dense\""));
    assertTrue(narrowEmailHtml.contains("Total Suiteruns: 205"));

    assertEquals(53, occurrences(wideEmailHtml, "class=\"octane-suite-column\""));
    assertTrue(wideEmailHtml.contains("data-hidden-count=\"152\""));
    assertTrue(wideEmailHtml.contains("class=\"octane-bar-overflow-count\">+152"));
    assertTrue(wideEmailHtml.contains("--octane-bar-width: 8.066px"));
    assertTrue(wideEmailHtml.contains("--octane-bar-gap: 2.066px"));

    assertEquals(205, occurrences(liveHtml, "class=\"octane-suite-column\""));
    assertFalse(liveHtml.contains("octane-bar-overflow-indicator"));
    assertFalse(liveHtml.contains("--octane-bar-width:"));
    assertTrue(liveHtml.contains("Tester 205"));
  }

  @Test
  public void keepsLargestBarsOnTheRightWhenEmailWidthTruncatesSmallerBars() {
    OctaneGateReportSnapshot snapshot = variedBarSnapshot();

    String html = new OctaneReportZoneHtmlRenderer().render(snapshot, "LIGHT", 320);

    assertFalse(html.contains("data-bar-name=\"Tester 1\""));
    assertTrue(html.contains("data-bar-name=\"Tester 8\""));
    assertTrue(html.contains("data-bar-name=\"Tester 20\""));
    assertTrue(
        html.indexOf("data-bar-name=\"Tester 8\"") < html.indexOf("data-bar-name=\"Tester 20\""));
    assertTrue(html.contains("data-hidden-count=\"7\""));
  }

  @Test
  public void rendersDenseTestPayloadAsStatusBarsWithoutTooltips() {
    OctaneGateReportSnapshot snapshot = adaptiveStatusSnapshot(350, 250);

    String html = new OctaneReportZoneHtmlRenderer().renderZone(snapshot);

    assertTrue(html.contains("data-x-axis=\"Status\""));
    assertTrue(html.contains("data-y-axis=\"Count\""));
    assertTrue(html.contains("data-tooltips-enabled=\"false\""));
    assertEquals(5, occurrences(html, "class=\"octane-suite-column\""));
    assertFalse(html.contains("class=\"octane-bar-popup\""));
    assertFalse(html.contains("tabindex=\"0\""));
  }

  @Test
  public void calculatesVisibleBarsFromViewportWidth() {
    assertEquals(1, OctaneReportZoneHtmlRenderer.maxVisibleBars(0));
    assertEquals(1, OctaneReportZoneHtmlRenderer.maxVisibleBars(24));
    assertEquals(57, OctaneReportZoneHtmlRenderer.maxVisibleBars(600));
    assertEquals(77, OctaneReportZoneHtmlRenderer.maxVisibleBars(800));
    assertEquals(137, OctaneReportZoneHtmlRenderer.maxVisibleBars(1400));
  }

  @Test
  public void calculatesCenteredBarWidthsAndGapsWithinBounds() {
    OctaneReportZoneHtmlRenderer.BarLayout dense =
        OctaneReportZoneHtmlRenderer.calculateBarLayout(600, 57, true);
    OctaneReportZoneHtmlRenderer.BarLayout balanced =
        OctaneReportZoneHtmlRenderer.calculateBarLayout(600, 10, false);
    OctaneReportZoneHtmlRenderer.BarLayout spacious =
        OctaneReportZoneHtmlRenderer.calculateBarLayout(600, 5, false);
    OctaneReportZoneHtmlRenderer.BarLayout capped =
        OctaneReportZoneHtmlRenderer.calculateBarLayout(660, 5, false);

    assertEquals(8.053, dense.barWidth(), 0.001);
    assertEquals(2.053, dense.gap(), 0.001);
    assertEquals(34.421, balanced.barWidth(), 0.001);
    assertEquals(28.421, balanced.gap(), 0.001);
    assertEquals(88.0, spacious.barWidth(), 0.001);
    assertEquals(40.0, spacious.gap(), 0.001);
    assertEquals(100.0, capped.barWidth(), 0.001);
    assertEquals(40.0, capped.gap(), 0.001);
  }

  @Test
  public void calculatesEmailBarCapacityFromTheRenderedChartWidth() {
    assertEquals(436, OctaneReportZoneHtmlRenderer.emailBarChartWidth(600));
    assertEquals(636, OctaneReportZoneHtmlRenderer.emailBarChartWidth(800));
    assertEquals(461, OctaneReportZoneHtmlRenderer.emailBarChartWidth(1200));
    assertEquals(561, OctaneReportZoneHtmlRenderer.emailBarChartWidth(1400));
  }

  @Test
  public void rendersPartialReportZoneForLiveRefresh() {
    OctaneGateReportSnapshot snapshot =
        OctaneGateReportSnapshot.fromResult(
            OctaneGateReportState.PASSED, "Passed", result(), classifier, 30);

    String html = new OctaneReportZoneHtmlRenderer().renderZone(snapshot);

    assertTrue(html.contains("id=\"octane-report-zone\""));
    assertTrue(html.contains("draggable=\"true\""));
    assertTrue(html.contains("data-card-key=\"distribution-regressions\""));
    assertTrue(html.contains("data-card-key=\"bars-regressions\""));
    assertFalse(html.contains("octane-expand-toggle"));
    assertFalse(html.contains("octane-zone-focus-toggle"));
    assertTrue(html.contains("REGRESSION Tests Status Distribution"));
    assertFalse(html.contains("<html>"));
    assertFalse(html.contains("<body>"));
    assertFalse(html.contains("id=\"octane-timer-zone\""));
  }

  @Test
  public void rendersExplicitDarkScreenshotThemeWithStatusTokens() {
    OctaneGateReportSnapshot snapshot =
        OctaneGateReportSnapshot.fromResult(
            OctaneGateReportState.PASSED, "Passed", result(), classifier, 30);

    String html = new OctaneReportZoneHtmlRenderer().render(snapshot, "dark");

    assertTrue(html.contains("<html data-octane-theme=\"dark\">"));
    assertTrue(html.contains("<meta name=\"color-scheme\" content=\"dark\""));
    assertTrue(html.contains("color-scheme: dark"));
    assertTrue(html.contains("oklch(0.17 0.01 265 / 1)"));
    assertTrue(html.contains("--octane-card-background: #1b1e24"));
    assertTrue(html.contains("--octane-text: #f3f6fb"));
    assertTrue(html.contains("--octane-status-passed: #30D158"));
    assertTrue(html.contains("--octane-status-failed: #FF453A"));
    assertTrue(html.contains("--octane-status-blocked: #FF9F0A"));
    assertTrue(html.contains("--octane-status-skipped: #BF5AF2"));
    assertTrue(html.contains("--octane-status-no-run: #8E8E93"));
    assertTrue(html.contains("var(--octane-status-passed)"));
    assertTrue(html.contains("var(--octane-status-failed)"));
    assertTrue(html.contains("var(--octane-status-no-run)"));
  }

  @Test
  public void rendersSystemScreenshotThemeWithPreferenceMediaQuery() {
    String html =
        new OctaneReportZoneHtmlRenderer().render(OctaneGateReportSnapshot.empty(), "SYSTEM");

    assertTrue(html.contains("<html data-octane-theme=\"system\">"));
    assertTrue(html.contains("<meta name=\"color-scheme\" content=\"light dark\""));
    assertTrue(html.contains("@media (prefers-color-scheme: dark)"));
  }

  @Test
  public void escapesDynamicReportValues() {
    GateResult result =
        new GateResult(
            "4501",
            "100% pass",
            true,
            true,
            new GateMetrics(1, 1, 1, 0, 0, 0),
            List.of(new RunRecord("1", "one", "passed")),
            Map.of("<4501>", List.of(new RunRecord("1", "one", "passed"))),
            Map.of(),
            Instant.parse("2026-05-15T00:00:00Z"));
    OctaneGateReportSnapshot snapshot =
        OctaneGateReportSnapshot.fromResult(
            OctaneGateReportState.PASSED, "Passed", result, classifier, 30);

    String html = new OctaneReportZoneHtmlRenderer().render(snapshot);

    assertTrue(html.contains("&lt;4501&gt;"));
    assertFalse(html.contains("><4501><"));
  }

  @Test
  public void skipsEmptySectionsInReportZone() {
    Map<String, List<RunRecord>> criticalSuiteRuns = new LinkedHashMap<>();
    criticalSuiteRuns.put("4502", List.of(new RunRecord("1", "critical one", "passed")));
    GateResult result =
        new GateResult(
            "",
            "critical.passRate == 100",
            true,
            true,
            new GateMetrics(0, 0, 0, 0, 0, 0),
            List.of(),
            Map.of(),
            Map.of(
                "critical",
                new GateScopeResult(
                    "critical",
                    "",
                    List.of(),
                    "4502",
                    List.of("4502"),
                    new GateMetrics(1, 1, 1, 0, 0, 0),
                    criticalSuiteRuns.get("4502"),
                    criticalSuiteRuns)),
            Instant.parse("2026-05-15T00:00:00Z"));
    OctaneGateReportSnapshot snapshot =
        OctaneGateReportSnapshot.fromResult(
            OctaneGateReportState.PASSED, "Passed", result, classifier, 30);

    String html = new OctaneReportZoneHtmlRenderer().renderZone(snapshot);

    assertTrue(snapshot.isCriticalOnlyReport());
    assertEquals(1, snapshot.getSections().size());
    assertTrue(html.contains("octane-report-zone-critical-only"));
    assertFalse(html.contains("REGRESSION Tests Status Distribution</h2>"));
    assertFalse(html.contains("Testing progress per Tester Suite Runs_REGRESSIONS</h2>"));
    assertTrue(html.contains("CRITICAL Tests Status Distribution"));
    assertTrue(html.contains("Testing progress per Tester Suite Runs_CRITICAL"));
    assertFalse(html.contains("No run results have been returned yet."));
  }

  @Test
  public void skipsEmptyCriticalSectionInReportZone() {
    Map<String, List<RunRecord>> regressionSuiteRuns = new LinkedHashMap<>();
    regressionSuiteRuns.put("4501", List.of(new RunRecord("1", "one", "passed")));
    GateResult result =
        new GateResult(
            "4501",
            "100% pass",
            true,
            true,
            new GateMetrics(1, 1, 1, 0, 0, 0),
            regressionSuiteRuns.get("4501"),
            regressionSuiteRuns,
            Map.of(
                "critical",
                new GateScopeResult(
                    "critical",
                    "",
                    List.of(),
                    "9999",
                    List.of("9999"),
                    new GateMetrics(0, 0, 0, 0, 0, 0),
                    List.of(),
                    Map.of("9999", List.of()))),
            Instant.parse("2026-05-15T00:00:00Z"));
    OctaneGateReportSnapshot snapshot =
        OctaneGateReportSnapshot.fromResult(
            OctaneGateReportState.PASSED, "Passed", result, classifier, 30);

    String html = new OctaneReportZoneHtmlRenderer().renderZone(snapshot);

    assertTrue(html.contains("REGRESSION Tests Status Distribution</h2>"));
    assertTrue(html.contains("Testing progress per Tester Suite Runs_REGRESSIONS</h2>"));
    assertFalse(html.contains("CRITICAL Tests Status Distribution"));
    assertFalse(html.contains("Testing progress per Tester Suite Runs_CRITICAL"));
    assertFalse(html.contains("No run results have been returned yet."));
  }

  private GateResult result() {
    Map<String, List<RunRecord>> regressionSuiteRuns = new LinkedHashMap<>();
    regressionSuiteRuns.put(
        "4501",
        List.of(
            new RunRecord("1", "one", "passed", "Ada Tester"),
            new RunRecord("2", "two", "failed", "Ada Tester")));
    regressionSuiteRuns.put("4502", List.of(new RunRecord("3", "three", "planned", "Ben Tester")));

    Map<String, List<RunRecord>> criticalSuiteRuns = new LinkedHashMap<>();
    criticalSuiteRuns.put("4502", List.of(new RunRecord("3", "three", "passed", "Ben Tester")));

    return new GateResult(
        "4501,4502",
        "critical.passRate == 100",
        true,
        true,
        new GateMetrics(3, 2, 1, 1, 0, 1),
        List.of(
            new RunRecord("1", "one", "passed", "Ada Tester"),
            new RunRecord("2", "two", "failed", "Ada Tester"),
            new RunRecord("3", "three", "planned", "Ben Tester")),
        regressionSuiteRuns,
        Map.of(
            "critical",
            new GateScopeResult(
                "critical",
                "",
                List.of(),
                "4502",
                List.of("4502"),
                new GateMetrics(1, 1, 1, 0, 0, 0),
                List.of(new RunRecord("3", "three", "passed", "Ben Tester")),
                criticalSuiteRuns)),
        Instant.parse("2026-05-15T00:00:00Z"));
  }

  private OctaneGateReportSnapshot denseSnapshot(int barCount) {
    Map<String, List<RunRecord>> suiteRuns = new LinkedHashMap<>();
    List<RunRecord> runs = new ArrayList<>();
    for (int index = 1; index <= barCount; index++) {
      String runId = Integer.toString(index);
      String suiteRunId = Integer.toString(5000 + index);
      RunRecord run = new RunRecord(runId, "run " + index, "passed", "Tester " + index);
      runs.add(run);
      suiteRuns.put(suiteRunId, List.of(run));
    }
    GateResult result =
        new GateResult(
            String.join(",", suiteRuns.keySet()),
            "regressions.passRate == 100",
            true,
            true,
            new GateMetrics(barCount, barCount, barCount, 0, 0, 0),
            runs,
            suiteRuns,
            Map.of(),
            Instant.parse("2026-05-15T00:00:00Z"));
    return OctaneGateReportSnapshot.fromResult(
        OctaneGateReportState.PASSED, "Passed", result, classifier, 30);
  }

  private OctaneGateReportSnapshot variedBarSnapshot() {
    Map<String, List<RunRecord>> suiteRuns = new LinkedHashMap<>();
    List<RunRecord> allRuns = new ArrayList<>();
    for (int tester = 1; tester <= 20; tester++) {
      List<RunRecord> testerRuns = new ArrayList<>();
      for (int run = 0; run < tester; run++) {
        RunRecord record =
            new RunRecord(
                "tester-" + tester + "-run-" + run, "Run " + run, "passed", "Tester " + tester);
        testerRuns.add(record);
        allRuns.add(record);
      }
      suiteRuns.put("suite-" + tester, List.copyOf(testerRuns));
    }
    GateResult result =
        new GateResult(
            String.join(",", suiteRuns.keySet()),
            "regressions.passRate == 100",
            true,
            true,
            GateMetrics.fromRuns(allRuns, classifier),
            allRuns,
            suiteRuns,
            Map.of(),
            Instant.parse("2026-05-15T00:00:00Z"));
    return OctaneGateReportSnapshot.fromResult(
        OctaneGateReportState.PASSED, "Passed", result, classifier, 30);
  }

  private OctaneGateReportSnapshot adaptiveStatusSnapshot(int testCount, int limit) {
    List<String> statuses = List.of("passed", "failed", "blocked", "skipped", "planned");
    List<RunRecord> runs = new ArrayList<>();
    for (int index = 0; index < testCount; index++) {
      runs.add(
          new RunRecord(
              "run-" + index,
              "Run " + index,
              statuses.get(index % statuses.size()),
              "Tester " + index));
    }
    GateResult result =
        new GateResult(
            "dense-suite",
            "regressions.executionRate >= 0",
            false,
            false,
            GateMetrics.fromRuns(runs, classifier),
            runs,
            Map.of("dense-suite", runs),
            Map.of(),
            Instant.parse("2026-05-15T00:00:00Z"));
    return OctaneGateReportSnapshot.fromResult(
        OctaneGateReportState.POLLING,
        "Polling",
        result,
        classifier,
        30,
        7_200,
        0,
        "2026-05-15T00:00:00Z",
        limit);
  }

  private OctaneGateReportSnapshot thinSliceSnapshot() {
    List<RunRecord> runs = new ArrayList<>();
    addRuns(runs, "passed", 90);
    addRuns(runs, "failed", 4);
    addRuns(runs, "blocked", 3);
    addRuns(runs, "skipped", 2);
    addRuns(runs, "running", 1);
    GateResult result =
        new GateResult(
            "thin-slices",
            "regressions.executionRate >= 90",
            true,
            true,
            new GateMetrics(100, 99, 90, 7, 2, 1),
            runs,
            Map.of("thin-slices", runs),
            Map.of(),
            Instant.parse("2026-05-15T00:00:00Z"));
    return OctaneGateReportSnapshot.fromResult(
        OctaneGateReportState.PASSED, "Passed", result, classifier, 30);
  }

  private void addRuns(List<RunRecord> runs, String status, int count) {
    int start = runs.size();
    for (int index = 0; index < count; index++) {
      String id = Integer.toString(start + index + 1);
      runs.add(new RunRecord(id, status + " " + id, status, "Tester"));
    }
  }

  private int occurrences(String value, String needle) {
    int count = 0;
    int offset = 0;
    while ((offset = value.indexOf(needle, offset)) >= 0) {
      count++;
      offset += needle.length();
    }
    return count;
  }
}
