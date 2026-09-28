package io.jenkins.plugins.octanesuitegatebyembiti.actions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import io.jenkins.plugins.octanesuitegatebyembiti.entities.RunRecord;
import io.jenkins.plugins.octanesuitegatebyembiti.models.GateMetrics;
import io.jenkins.plugins.octanesuitegatebyembiti.models.GateRequest;
import io.jenkins.plugins.octanesuitegatebyembiti.models.GateResult;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneDefectSeveritySummary;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneGateReportState;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneRiskHeatMap;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneRiskHeatMapNode;
import io.jenkins.plugins.octanesuitegatebyembiti.models.StatusClassifier;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.sf.json.JSONObject;
import org.htmlunit.Page;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlPage;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.kohsuke.stapler.interceptor.RequirePOST;

public class OctaneGateReportActionTest {
  @Rule public JenkinsRule jenkins = new JenkinsRule();

  @Test
  public void loadsUnderPublishedPluginIdentityWithExistingReportClass() {
    var plugin = jenkins.jenkins.getPluginManager().getPlugin("octane-status-tracker");
    assertNotNull(plugin);
    assertEquals("octane-status-tracker", plugin.getShortName());
    assertEquals("Octane Status Tracker", plugin.getDisplayName());
    assertTrue(plugin.isActive());
    assertEquals("octaneSuiteGateReport", OctaneGateReportAction.URL_NAME);
  }

  @Test
  public void manualExitEndpointRemainsPostOnly() throws Exception {
    assertNotNull(
        OctaneGateReportAction.class
            .getMethod("doExitOctaneAndContinue")
            .getAnnotation(RequirePOST.class));
  }

  @Test
  public void refreshesOnlyWhenRunningSnapshotIsOlderThanThreshold() throws Exception {
    FreeStyleProject project = jenkins.createFreeStyleProject();
    FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
    GateRequest request = new GateRequest("octane-prod", "4501");
    request.setDefinedScope("Payments - ada lovelace");
    OctaneGateReportAction action = OctaneGateReportAction.attachTo(build, request);
    Instant now = Instant.parse("2026-07-20T12:00:00Z");
    action.onPoll(result(now.minusSeconds(61L)), classifier());
    AtomicInteger refreshes = new AtomicInteger();
    action.setRefreshCallback(
        () -> {
          refreshes.incrementAndGet();
          action.onPoll(result(now), classifier());
          return true;
        });

    OctaneGateReportAction.RefreshResult refreshed =
        action.refreshIfStale(Duration.ofMinutes(1L), now);

    assertEquals(OctaneGateReportAction.RefreshStatus.REFRESHED, refreshed.status());
    assertEquals(Duration.ofSeconds(61L), refreshed.age());
    assertEquals(1, refreshes.get());
    assertEquals(now.toString(), action.getSnapshot().getUpdatedAt());
    assertEquals("Payments", action.getSnapshot().getDefinedScope().get(0).getProject());

    action.onPoll(result(now.minusSeconds(60L)), classifier());
    OctaneGateReportAction.RefreshResult fresh = action.refreshIfStale(Duration.ofMinutes(1L), now);
    assertEquals(OctaneGateReportAction.RefreshStatus.FRESH, fresh.status());
    assertEquals(1, refreshes.get());
  }

  @Test
  public void freshWaitingSnapshotForcesPollBeforeIntervalEmailRendering() throws Exception {
    FreeStyleProject project = jenkins.createFreeStyleProject();
    FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
    GateRequest request = new GateRequest("octane-prod", "4501");
    OctaneGateReportAction action = OctaneGateReportAction.attachTo(build, request);
    Instant now = Instant.parse("2026-07-20T12:00:00Z");
    AtomicInteger refreshes = new AtomicInteger();
    action.setRefreshCallback(
        () -> {
          refreshes.incrementAndGet();
          action.onPoll(result(now), classifier());
          return true;
        });

    assertFalse(action.getSnapshot().hasReportSections());
    OctaneGateReportAction.RefreshResult refreshed =
        action.refreshForEmail(Duration.ofMinutes(1L), now);

    assertEquals(OctaneGateReportAction.RefreshStatus.REFRESHED, refreshed.status());
    assertEquals(1, refreshes.get());
    assertTrue(action.getSnapshot().hasReportSections());
    assertEquals(
        OctaneGateReportAction.RefreshStatus.FRESH,
        action.refreshForEmail(Duration.ofMinutes(1L), now).status());
    assertEquals(1, refreshes.get());
  }

  @Test
  public void completedReportNeverStartsOutOfBandRefresh() throws Exception {
    FreeStyleProject project = jenkins.createFreeStyleProject();
    FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
    GateRequest request = new GateRequest("octane-prod", "4501");
    OctaneGateReportAction action = OctaneGateReportAction.attachTo(build, request);
    Instant now = Instant.parse("2026-07-20T12:00:00Z");
    action.onFinal(
        OctaneGateReportState.PASSED, "Complete", result(now.minusSeconds(600L)), classifier());

    OctaneGateReportAction.RefreshResult refreshResult =
        action.refreshIfStale(Duration.ofMinutes(1L), now);

    assertEquals(OctaneGateReportAction.RefreshStatus.NOT_BUILDING, refreshResult.status());
  }

  @Test
  public void persistsFirstOwnerAcrossPartialPollAndContinuouslyDiscoveredSuite() throws Exception {
    FreeStyleProject project = jenkins.createFreeStyleProject();
    FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
    GateRequest request = new GateRequest("octane-prod", "4501,4502");
    OctaneGateReportAction action = OctaneGateReportAction.attachTo(build, request);

    action.onPoll(
        attributionResult(
            Map.of("4501", List.of(new RunRecord("run-1", "One", "planned", "Ada Tester")))),
        classifier());
    action.onPoll(
        attributionResult(
            Map.of(
                "4501",
                List.of(new RunRecord("run-1", "One", "passed", "Unassigned (4501)")),
                "4502",
                List.of(new RunRecord("run-2", "Two", "planned", "ada tester")))),
        classifier());

    assertEquals(
        Map.of("4501", "Ada Tester", "4502", "ada tester"),
        action.getSnapshot().getSuiteAttributions());
    assertEquals(1, action.getSnapshot().getReportSections().get(0).getSuiteRuns().size());
    assertEquals(
        List.of("4501", "4502"),
        action.getSnapshot().getReportSections().get(0).getSuiteRuns().get(0).getSuiteRunIds());
    assertFalse(
        action.getSnapshot().getTesterPerformances().stream()
            .anyMatch(tester -> tester.getEmail().startsWith("Unassigned")));
  }

  @Test
  public void finalizingFreezesTimersAndPublishesOneReconciledTimestamp() throws Exception {
    FreeStyleProject project = jenkins.createFreeStyleProject();
    FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
    GateRequest request = new GateRequest("octane-prod", "4501");
    OctaneGateReportAction action = OctaneGateReportAction.attachTo(build, request);
    Instant finalPollCompletedAt = Instant.parse("2026-07-20T12:34:56Z");
    AtomicInteger refreshes = new AtomicInteger();
    action.setRefreshCallback(
        () -> {
          refreshes.incrementAndGet();
          return true;
        });
    action.onPoll(result(finalPollCompletedAt.minusSeconds(30L)), classifier());

    action.onFinalizing("Reconciling final ALM Octane data.");

    assertEquals(OctaneGateReportState.FINALIZING, action.getSnapshot().getState());
    assertEquals("Finalizing", action.getSnapshot().getJobStateLabel());
    assertTrue(action.getSnapshot().isBuilding());
    assertFalse(action.getSnapshot().isTimerActive());
    assertEquals("", action.getSnapshot().getReconciledAt());
    assertEquals(
        OctaneGateReportAction.RefreshStatus.FRESH,
        action.refreshIfStale(Duration.ZERO, Instant.now()).status());
    assertEquals(0, refreshes.get());
    HtmlPage finalizingPage =
        jenkins.createWebClient().getPage(build, OctaneGateReportAction.URL_NAME);
    HtmlElement finalizingDashboard = finalizingPage.getHtmlElementById("octane-dashboard");
    assertEquals("true", finalizingDashboard.getAttribute("data-report-finalizing"));
    assertTrue(finalizingPage.asNormalizedText().contains("Finalizing..."));
    assertTrue(
        finalizingPage.getByXPath("//*[@data-octane-timer and @data-active='false']").size() >= 2);

    action.onFinal(
        OctaneGateReportState.PASSED,
        "ALM Octane suite gate passed.",
        result(finalPollCompletedAt),
        classifier());

    assertFalse(action.getSnapshot().isBuilding());
    assertFalse(action.getSnapshot().isFinalizing());
    assertEquals(finalPollCompletedAt.toString(), action.getSnapshot().getReconciledAt());
    assertEquals("2026/07/20 15:34:56", action.getSnapshot().getReconciledAtDateTimeText());
  }

  @Test
  public void terminalReconciliationRetainsLastPopulatedHeatMapUntilAValidReplacementArrives()
      throws Exception {
    FreeStyleProject project = jenkins.createFreeStyleProject();
    FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
    GateRequest request = new GateRequest("octane-prod", "4501");
    request.setRiskHeatMap(true);
    OctaneGateReportAction action = OctaneGateReportAction.attachTo(build, request);
    Instant activePoll = Instant.parse("2026-07-20T12:00:00Z");
    Instant finalPoll = activePoll.plusSeconds(30L);

    action.onPoll(result(activePoll, populatedHeatMap(64)), classifier());
    action.onFinalizing("Reconciling final ALM Octane data.");
    action.onFinal(
        OctaneGateReportState.PASSED,
        "ALM Octane suite gate passed.",
        result(finalPoll, OctaneRiskHeatMap.empty("4001")),
        classifier());

    assertTrue(action.getSnapshot().getRiskHeatMap().isPopulatedData());
    assertEquals(64, action.getSnapshot().getRiskHeatMap().getRiskScore());
    assertEquals(finalPoll.toString(), action.getSnapshot().getUpdatedAt());
    assertTrue(
        action.getSnapshot().getRiskHeatMapHtml().contains("octane-risk-heat-map-panel-inner"));

    action.onFinal(
        OctaneGateReportState.PASSED,
        "ALM Octane suite gate passed.",
        result(finalPoll.plusSeconds(1L), populatedHeatMap(82)),
        classifier());

    assertEquals(82, action.getSnapshot().getRiskHeatMap().getRiskScore());
  }

  @Test
  public void attachesToBuildAndRendersReportPage() throws Exception {
    FreeStyleProject project = jenkins.createFreeStyleProject();
    FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
    GateRequest request = new GateRequest("octane-prod", "4501");
    request.setPollIntervalSeconds(15);
    request.setTimeoutMinutes(45);
    request.setBasePassrateFigure(70);
    request.setBaseExecutionFigure(90);
    request.setDefinedScope(
        "regressions: \"SMTSL, MMI\" - james, secure checkout - tom, mini apps");

    OctaneGateReportAction action = OctaneGateReportAction.attachTo(build, request);
    action.onFinal(
        OctaneGateReportState.PASSED,
        "ALM Octane suite gate passed.",
        result(),
        new StatusClassifier(
            StatusClassifier.DEFAULT_PASSED_STATUSES,
            StatusClassifier.DEFAULT_FAILED_STATUSES,
            StatusClassifier.DEFAULT_NEUTRAL_STATUSES,
            StatusClassifier.DEFAULT_RUNNING_STATUSES));

    assertSame(action, build.getAction(OctaneGateReportAction.class));
    assertNotNull(build.getAction(OctaneGateReportAction.class).getSnapshot());

    HtmlPage page = jenkins.createWebClient().getPage(build, OctaneGateReportAction.URL_NAME);
    String text = page.asNormalizedText();
    String xml = page.asXml();
    assertTrue(text.contains("Octane Gate Report"));
    assertTrue(text.contains("Passed"));
    assertTrue(text.contains("LAST UPDATED:"));
    assertTrue(text.contains("03:00:00"));
    assertFalse(text.contains("Last update (EAT)"));
    assertTrue(text.contains("REGRESSION Tests Status Distribution"));
    assertTrue(text.contains("Testing progress per Tester Suite Runs_REGRESSIONS"));
    assertTrue(text.contains("Testing Session Monitor"));
    assertFalse(text.contains("Testing Time Remaining"));
    assertTrue(text.contains("Test Insights"));
    assertTrue(text.contains("Current Job Analytics"));
    assertTrue(text.contains("Automation Usage"));
    assertTrue(text.contains("Success Rate"));
    assertTrue(text.contains("Execution Completion"));
    assertTrue(text.contains("Open Defects"));
    assertTrue(text.contains("Execution Activity Rings"));
    assertTrue(xml.contains("data-activity-inline-legend=\"true\""));
    assertFalse(text.contains("Target Achievement"));
    assertTrue(text.contains("Session Time Spent"));
    assertTrue(text.contains("Execution Progress"));
    assertTrue(text.contains("All Testcase completion"));
    assertTrue(text.contains("All Testcase Status"));
    assertTrue(text.contains("Testcases"));
    assertTrue(text.contains("Execution Pass Rate"));
    assertTrue(text.contains("Execution Defect Rate"));
    assertTrue(text.contains("Defect Arrival vs. Resolution Trend Analysis"));
    assertFalse(text.contains("Burn-down Chart"));
    assertTrue(text.contains("Testing Against Schedule"));
    assertTrue(text.contains("Total Test cases"));
    assertFalse(text.contains("Current Execution State"));
    assertTrue(text.contains("Execution per Sprint Parts"));
    assertTrue(text.contains("Tests Executed"));
    assertTrue(text.contains("Test Failure Analysis"));
    assertFalse(text.contains("Defect Root-Cause Breakdown"));
    assertTrue(text.contains("Defects"));
    assertTrue(text.contains("Testing Metrics"));
    assertTrue(text.contains("QA Health & Compliance"));
    assertTrue(
        xml.indexOf("id=\"octane-test-management-zone\"")
            < xml.indexOf("id=\"octane-report-zone\""));
    assertTrue(xml.contains("data-zone-key=\"test-management\""));
    assertTrue(xml.contains("data-card-key=\"test-management-burndown\""));
    assertTrue(xml.contains("data-card-key=\"test-management-current-state\""));
    assertTrue(xml.contains("data-card-key=\"test-management-failures\""));
    assertTrue(xml.contains("data-card-key=\"test-management-metrics\""));
    assertTrue(xml.contains("function installAnalyticsComponentSwap(root)"));
    assertTrue(xml.contains("data-card-key=\"test-management-defects\""));
    assertTrue(xml.contains("metricsRoot: managementMetricsRoot"));
    assertTrue(xml.contains("data-management-failure-switcher=\"true\""));
    assertTrue(xml.contains("data-management-defect-list=\"true\""));
    assertTrue(xml.contains("function updateTestManagement(payload)"));
    assertTrue(xml.contains("window.OctaneTestManagement.mount"));
    assertTrue(xml.contains("window.OctaneTestManagement.update"));
    assertFalse(text.contains("Defect Volumes"));
    assertTrue(text.contains("Volume"));
    assertTrue(text.contains("Density"));
    assertTrue(xml.contains("data-defect-target-view=\"volumes\""));
    assertTrue(xml.contains("data-defect-target-view=\"density\""));
    assertTrue(xml.contains("data-defect-title=\"Defect Density\""));
    assertTrue(xml.contains("data-defect-subtitle=\"Execution Defect Density\""));
    assertTrue(xml.contains("octane-defect-pane-label-full"));
    assertTrue(xml.contains("octane-defect-pane-label-short"));
    assertTrue(xml.contains(">V</span>"));
    assertTrue(xml.contains(">D</span>"));
    assertTrue(text.contains("Defects Raised"));
    assertTrue(text.contains("Defects"));
    assertTrue(xml.contains("octane-defect-density-axis-title"));
    assertTrue(xml.contains(">Defects / Test</div>"));
    assertTrue(text.contains("Open Defects"));
    assertTrue(xml.contains("data-defect-trend-open-total=\"true\""));
    assertTrue(text.contains("All Testcase Pass Rate (1 / 2)"));
    assertTrue(text.contains("Total test cases"));
    assertTrue(text.contains("Total Suiteruns: 1"));
    assertTrue(text.contains("Tester Details"));
    assertTrue(text.contains("Testers with LESS THAN 70% Pass Rate"));
    assertTrue(text.contains("Testers with LESS THAN 90% Execution"));
    assertTrue(text.contains("Testers with LESS THAN 70% Pass Rate (1)"));
    assertTrue(text.contains("Testers with LESS THAN 90% Execution (0)"));
    assertTrue(text.contains("Suiterun Pass Rate"));
    assertTrue(text.contains("Suiterun Execution"));
    assertTrue(text.contains("Everything Good!"));
    assertTrue(text.contains("Defined Scope"));
    assertFalse(text.contains("No defined scope!"));
    assertTrue(text.contains("regressions: SMTSL, MMI"));
    assertFalse(text.contains("regressions: \"SMTSL, MMI\""));
    assertTrue(text.contains("james"));
    assertTrue(text.contains("secure checkout"));
    assertTrue(text.contains("tom"));
    assertTrue(text.contains("mini apps"));
    assertTrue(xml.contains("<td class=\"octane-scope-owner\">-</td>"));
    assertTrue(xml.contains("data-defined-scope-rows=\"true\""));
    assertTrue(xml.contains("grid-template-columns: repeat(3, minmax(0, 1fr))"));
    assertTrue(xml.contains("function replaceDefinedScopeRows(scopes)"));
    assertTrue(
        xml.contains("owner.textContent = scope.owner || &quot;-&quot;")
            || xml.contains("owner.textContent = scope.owner || \"-\""));
    assertTrue(
        xml.indexOf("id=\"octane-execution-tracker-title\"")
            < xml.indexOf("id=\"octane-pass-rate-tracker-title\""));
    assertTrue(xml.contains("id=\"tester-details-zone\""));
    assertTrue(xml.contains("class=\"octane-tester-details-toggle\""));
    assertTrue(xml.contains("aria-expanded=\"true\""));
    assertTrue(xml.contains("max-height: calc(100vh - 2rem)"));
    assertTrue(xml.contains("scrollbar-color: #666 transparent"));
    assertTrue(xml.contains("function updateTesterDetails(payload)"));
    assertTrue(xml.contains("updateTesterDetails(payload)"));
    assertTrue(xml.contains("event.target.closest(\".octane-tester-details-toggle\")"));
    assertTrue(xml.contains("Ada Tester"));
    assertTrue(text.contains("ada tester"));
    assertFalse(text.contains("Total Testcases"));
    assertFalse(text.contains("Global + Critical execution"));
    assertFalse(text.contains("Execution 100.0%, pass"));
    assertFalse(text.contains("Suite runs: 4501"));
    assertTrue(xml.contains("--octane-status-passed: var(--octane-system-green)"));
    assertTrue(xml.contains("--octane-status-failed: var(--octane-system-red)"));
    assertTrue(xml.contains("--octane-status-blocked: var(--octane-system-orange)"));
    assertTrue(xml.contains("--octane-status-skipped: var(--octane-system-purple)"));
    assertTrue(xml.contains("--octane-status-no-run: var(--octane-system-gray)"));
    assertTrue(xml.contains("--octane-system-green: #34C759"));
    assertTrue(xml.contains("--octane-system-green: #30D158"));
    assertTrue(xml.contains("--octane-system-red: #FF3B30"));
    assertTrue(xml.contains("--octane-system-red: #FF453A"));
    assertTrue(xml.contains("var(--octane-status-passed)"));
    assertTrue(xml.contains("var(--octane-status-failed)"));
    assertTrue(xml.contains("octane-donut"));
    assertTrue(xml.contains("data-automation-usage-row=\"true\""));
    assertTrue(xml.contains("data-automation-percentage=\"0\""));
    assertTrue(xml.contains("data-automation-emoji="));
    assertTrue(xml.contains("🐢") || xml.contains("&#128034;"));
    assertTrue(xml.contains("octane-bar-popup-automation"));
    HtmlElement popup =
        page.getFirstByXPath(
            "//div[contains(concat(' ', normalize-space(@class), ' '), ' octane-bar-popup ')]");
    assertNotNull(popup);
    String popupXml = popup.asXml();
    int popupAutomation = popupXml.indexOf("octane-bar-popup-automation");
    int popupTotal = popupXml.indexOf("octane-bar-popup-total");
    assertTrue(popupAutomation >= 0 && popupAutomation < popupTotal);
    assertTrue(xml.contains("table-layout: auto"));
    assertTrue(xml.contains("octane-tester-rate-header"));
    assertTrue(xml.contains("container-name: tester-tracker"));
    assertTrue(xml.contains("text-wrap: nowrap"));
    assertTrue(xml.contains("white-space: nowrap"));
    assertTrue(xml.contains("octane-tester-responsive-prefix"));
    assertTrue(xml.contains("function updateTesterTrackerTitle(title, condition)"));
    assertTrue(xml.contains("octane-tester-email-column"));
    assertTrue(xml.contains("octane-tester-rate-column"));
    assertTrue(xml.contains("max-width: 100%"));
    assertTrue(xml.contains("octane-chart-inner octane-donut-graph"));
    assertTrue(xml.contains("octane-donut-layout"));
    assertTrue(xml.contains("gap: clamp(0.125rem, 0.75cqw, 0.375rem)"));
    assertTrue(xml.contains("octane-donut-center-value"));
    assertTrue(xml.contains("octane-donut-center-label"));
    assertTrue(xml.contains("octane-donut-legend"));
    assertTrue(xml.contains("octane-donut-legend-percentage"));
    assertFalse(xml.contains("octane-donut-label"));
    assertFalse(xml.contains("octane-donut-callout-line"));
    assertTrue(xml.contains("viewBox=\"3 3 94 94\"") || xml.contains("viewbox=\"3 3 94 94\""));
    assertFalse(xml.contains("max-width: 340px"));
    assertFalse(xml.contains("min-height: 294px"));
    assertTrue(xml.contains("padding: 5px"));
    assertTrue(xml.contains("height: calc(260px + var(--octane-axis-label-row))"));
    assertTrue(xml.contains("<path d=") || xml.contains("r=\"46\" fill="));
    assertTrue(xml.contains("r=\"37.36\""));
    assertTrue(xml.contains("max-height: none"));
    assertTrue(xml.contains("max-width: none"));
    assertFalse(xml.contains("max-height: 248.1804px"));
    assertFalse(xml.contains("max-width: 248.1804px"));
    assertTrue(text.contains("Total test cases"));
    assertTrue(text.contains("Total test cases: 2"));
    assertTrue(text.contains("50.00%"));
    String donutSegmentRule = cssRule(xml, ".octane-donut-segment");
    assertTrue(donutSegmentRule.contains("stroke: none"));
    assertFalse(donutSegmentRule.contains("stroke-width"));
    assertTrue(xml.contains("clamp(0.5rem, 2.2cqw, 1.5625rem)"));
    assertTrue(xml.contains("border-radius: 14px"));
    assertFalse(xml.contains("border-radius: 6px"));
    assertTrue(xml.contains("contain: layout style paint"));
    assertTrue(xml.contains("container-type: inline-size"));
    assertTrue(xml.contains("@supports not (font-size: 1cqw)"));
    assertTrue(xml.contains("font-size: clamp(0.72rem, 3.2cqw, 1.08rem)"));
    assertTrue(xml.contains("font-size: clamp(0.66rem, 2.6cqw, 1rem)"));
    assertTrue(xml.contains("font-size: clamp(0.9rem, 2.2cqw, 1.3rem)"));
    assertTrue(xml.contains("font-size: clamp(0.58rem, 2.35cqw, 0.9rem)"));
    assertTrue(
        xml.contains(".octane-chart-card[data-card-key=&quot;timer-poll&quot;]")
            || xml.contains(".octane-chart-card[data-card-key=\"timer-poll\"]"));
    assertTrue(xml.contains("display: flex"));
    assertTrue(xml.contains("flex-direction: column"));
    assertTrue(xml.contains(".octane-risk-heat-map-panel-inner"));
    assertTrue(xml.contains("align-content: stretch"));
    assertTrue(xml.contains("grid-template-columns: minmax(0, 1fr)"));
    assertTrue(xml.contains("grid-template-rows: minmax(0, 1fr) max-content"));
    assertTrue(xml.contains("gap: clamp(0.35rem, 1vh, 0.65rem)"));
    assertTrue(xml.contains(".octane-risk-heat-map-container"));
    assertTrue(xml.contains("container-type: size"));
    assertTrue(xml.contains("min-height: 0"));
    assertFalse(xml.contains("min-height: 180px"));
    assertTrue(xml.contains("overflow: hidden"));
    assertTrue(xml.contains(".octane-risk-issues-container"));
    assertTrue(xml.contains("align-self: end"));
    assertTrue(xml.contains("padding-top: clamp(0.2rem, 0.8vh, 0.55rem)"));
    assertTrue(xml.contains("padding-bottom: clamp(0.45rem, 0.8cqw, 0.7rem)"));
    assertTrue(xml.contains("height: 100%"));
    assertTrue(xml.contains("max-width: 220px"));
    assertTrue(xml.contains("height: min(100cqw, 100cqh, 220px)"));
    assertTrue(xml.contains("width: min(100%, 220px)"));
    assertTrue(xml.contains("width: min(100cqw, 100cqh, 220px)"));
    assertTrue(xml.contains("width: min(100cqw, 100cqh, 38vh, 38vw)"));
    assertTrue(xml.contains("width: min(100cqw, 100cqh, 76vh, 76vw)"));
    assertTrue(xml.contains("container-type: inline-size"));
    assertTrue(xml.contains("width: 75%"));
    assertTrue(
        xml.contains("font-family: Inter")
            && xml.contains("Segoe UI")
            && xml.contains("Arial, sans-serif"));
    assertTrue(xml.contains("font-size: 0.875em"));
    assertTrue(xml.contains("font-weight: 500"));
    assertTrue(xml.contains("line-height: 1.35"));
    assertTrue(xml.contains("min-height: clamp(2.58em, 3.4cqw, 4.25em)"));
    assertTrue(xml.contains("text-overflow: clip"));
    assertTrue(xml.contains("@container (max-width: 44rem)"));
    assertTrue(xml.contains(".octane-defect-severity-label"));
    assertTrue(xml.contains("display: none"));
    assertTrue(xml.contains("border-color: #4391F5"));
    assertTrue(xml.contains("font-size: 0"));
    assertTrue(xml.contains("height: 1.15rem"));
    assertTrue(xml.contains("letter-spacing: 0"));
    assertTrue(xml.contains("width: 1.15rem"));
    assertTrue(xml.contains("aria-label=\"Move widget\""));
    assertTrue(xml.contains("Use arrow keys to reorder"));
    assertTrue(xml.contains("octane-reorder-status"));
    assertTrue(xml.contains("aria-live=\"polite\""));
    assertTrue(xml.contains(".octane-card-tools:focus-visible"));
    assertTrue(xml.contains("outline: 3px solid #4391F5"));
    assertTrue(xml.contains("octane-grabber-icon"));
    assertTrue(xml.contains("<circle cx=\"12\" cy=\"5\" r=\"2\""));
    assertFalse(xml.contains(":::"));
    assertFalse(xml.contains(".octane-card-tools::before"));
    assertTrue(xml.contains("octane-card-actions"));
    assertTrue(xml.contains("octane-expand-toggle"));
    assertTrue(xml.contains("octane-icon-expand"));
    assertTrue(xml.contains("octane-icon-collapse"));
    assertTrue(xml.contains("data-target-view=\"metrics\""));
    assertTrue(xml.contains("data-target-view-label=\"test metrics\""));
    assertTrue(xml.contains("octane-icon-metrics"));
    assertEquals(
        3,
        page.getByXPath("//section[@data-card-key='timer-poll']" + "//*[@data-activity-ring]")
            .size());
    assertEquals(
        1,
        page.getByXPath(
                "//section[@data-card-key='timer-poll']"
                    + "//*[@data-activity-ring='execution' and @r='84']")
            .size());
    assertEquals(
        1,
        page.getByXPath(
                "//section[@data-card-key='timer-poll']"
                    + "//*[@data-activity-ring='pass' and @r='64.5']")
            .size());
    assertEquals(
        1,
        page.getByXPath(
                "//section[@data-card-key='timer-poll']"
                    + "//*[@data-activity-ring='automation' and @r='45']")
            .size());
    assertTrue(xml.contains("stroke-width: 16"));
    assertTrue(xml.contains("opacity: 0.2"));
    assertTrue(xml.contains("stroke: #FA114F"));
    assertTrue(xml.contains("stroke: #A6FF00"));
    assertTrue(xml.contains("stroke: #00FFF6"));
    assertTrue(xml.contains("class=\"octane-activity-side-legend\""));
    assertTrue(xml.contains("@container octane-activity-rings (min-width: 36rem)"));
    assertTrue(cssRule(xml, ".octane-activity-rings-layout").contains("position: relative"));
    assertTrue(cssRule(xml, ".octane-activity-subtitle").contains("white-space: nowrap"));
    assertTrue(cssRule(xml, ".octane-activity-subtitle").contains("text-overflow: ellipsis"));
    assertTrue(cssRule(xml, ".octane-activity-subtitle").contains("overflow: hidden"));
    assertTrue(xml.contains("octane-activity-legend-full"));
    assertTrue(xml.contains("octane-activity-legend-compact"));
    assertTrue(xml.contains("@container octane-activity-legend (max-width: 34rem)"));
    assertTrue(xml.contains("data-target-view=\"breakdown\""));
    assertTrue(xml.contains("data-target-view-label=\"status breakdown\""));
    assertTrue(xml.contains("octane-icon-breakdown"));
    assertTrue(xml.contains("data-target-view=\"metrics\""));
    assertTrue(xml.contains("data-target-view-label=\"QA health and compliance\""));
    assertTrue(xml.contains("octane-icon-defects"));
    assertTrue(xml.contains("octane-flip-face-defects"));
    assertTrue(xml.contains("octane-defect-face-header"));
    assertTrue(xml.contains("data-defect-view-title=\"true\""));
    assertTrue(xml.contains("data-defect-view-subtitle=\"true\""));
    assertTrue(xml.contains("octane-defect-main-view-toggle"));
    assertTrue(xml.contains("data-defect-analytics=\"true\""));
    assertTrue(xml.contains("data-active-defect-view=\"volumes\""));
    assertTrue(xml.contains("octane-defect-pane-switcher"));
    assertTrue(xml.contains("octane-defect-face-actions"));
    assertEquals(
        1,
        page.getByXPath(
                "//section[@data-card-key='test-management-defects']"
                    + "//div[contains(concat(' ', normalize-space(@class), ' '),"
                    + " ' octane-defect-face-header ')]"
                    + "/*[1][contains(concat(' ', normalize-space(@class), ' '),"
                    + " ' octane-defect-face-heading ')]")
            .size());
    assertEquals(
        1,
        page.getByXPath(
                "//section[@data-card-key='test-management-defects']"
                    + "//div[contains(concat(' ', normalize-space(@class), ' '),"
                    + " ' octane-defect-face-header ')]"
                    + "/*[2][contains(concat(' ', normalize-space(@class), ' '),"
                    + " ' octane-defect-pane-switcher ')]")
            .size());
    assertEquals(
        1,
        page.getByXPath(
                "//section[@data-card-key='test-management-defects']"
                    + "//div[contains(concat(' ', normalize-space(@class), ' '),"
                    + " ' octane-defect-face-header ')]"
                    + "/*[3][contains(concat(' ', normalize-space(@class), ' '),"
                    + " ' octane-defect-face-actions ')]")
            .size());
    assertEquals(
        0,
        page.getByXPath(
                "//section[@data-card-key='test-management-defects']"
                    + "//div[contains(concat(' ', normalize-space(@class), ' '),"
                    + " ' octane-defect-face-actions ')]"
                    + "/button[contains(concat(' ', normalize-space(@class), ' '),"
                    + " ' octane-view-toggle ')]")
            .size());
    assertEquals(
        1,
        page.getByXPath(
                "//section[@data-card-key='test-management-defects']"
                    + "//div[contains(concat(' ', normalize-space(@class), ' '),"
                    + " ' octane-defect-face-actions ')]"
                    + "/button[contains(concat(' ', normalize-space(@class), ' '),"
                    + " ' octane-expand-toggle ')]")
            .size());
    assertEquals(
        1,
        page.getByXPath(
                "//section[@data-card-key='test-management-defects']"
                    + "//div[contains(concat(' ', normalize-space(@class), ' '),"
                    + " ' octane-defect-face-actions ')]"
                    + "/button[contains(concat(' ', normalize-space(@class), ' '),"
                    + " ' octane-card-tools ')]")
            .size());
    assertEquals(
        1,
        page.getByXPath(
                "//section[@data-card-key='progress-pass-rate']"
                    + "//*[@data-card-view='metrics']"
                    + "//*[@data-management-metrics='true']")
            .size());
    assertEquals(
        0,
        page.getByXPath(
                "//section[@data-card-key='progress-pass-rate']"
                    + "/div[contains(concat(' ', normalize-space(@class), ' '),"
                    + " ' octane-card-actions ')]"
                    + "/div[contains(concat(' ', normalize-space(@class), ' '),"
                    + " ' octane-defect-pane-switcher ')]")
            .size());
    assertTrue(xml.contains("--octane-control-size: 1.15rem"));
    assertTrue(xml.contains("height: var(--octane-control-size, 1.15rem)"));
    assertTrue(
        xml.contains(
            ".octane-defect-face-header {\n          align-items: flex-start;\n"
                + "          display: flex;"));
    assertTrue(xml.contains("flex-wrap: nowrap"));
    assertTrue(xml.contains("border-radius: 9999px"));
    assertTrue(xml.contains("padding: 2px"));
    assertTrue(xml.contains("display: inline-flex"));
    assertTrue(xml.contains("justify-content: center"));
    assertTrue(xml.contains(".octane-defect-pane {\n          box-sizing: border-box;"));
    assertTrue(xml.contains("grid-template-rows: minmax(0, 1fr);\n          height: 100%;"));
    assertTrue(
        xml.contains(
            ".octane-defect-analytics {\n            grid-template-rows: minmax(0, 1fr);"));
    assertTrue(
        xml.contains(
            ".octane-chart-card[data-card-key=\"progress-pass-rate\"][data-active-view=\"defects\"]"));
    assertTrue(xml.contains("data-defect-target-view=\"volumes\""));
    assertTrue(xml.contains("data-defect-target-view=\"density\""));
    assertTrue(xml.contains("data-defect-pane=\"volumes\""));
    assertTrue(xml.contains("data-defect-pane=\"density\""));
    assertTrue(xml.contains("data-defect-trend-panel=\"true\""));
    assertTrue(xml.contains("octane-defect-trend-summary-card"));
    assertTrue(
        xml.contains(
            ".octane-defect-trend-summary-card {\n          align-content: start;\n"
                + "          background: transparent;\n          border: 0;"));
    assertTrue(xml.contains("padding: 2px 0"));
    assertTrue(xml.contains(".octane-defect-trend-value,\n        .octane-defect-density-value"));
    assertTrue(
        xml.contains(".octane-defect-trend-total-label,\n        .octane-defect-density-label"));
    assertTrue(xml.contains("octane-defect-trend-line-opened"));
    assertTrue(xml.contains("octane-defect-trend-line-closed"));
    assertTrue(xml.contains("octane-defect-trend-axis-title"));
    assertTrue(xml.contains("data-executed=\""));
    assertTrue(xml.contains("data-defect-density-panel=\"true\""));
    assertTrue(xml.contains("data-defect-density-raised-total=\"true\""));
    assertTrue(xml.contains("grid-template-columns: max-content max-content minmax(0, 1fr)"));
    assertTrue(xml.contains("octane-defect-density-svg"));
    assertTrue(xml.contains("octane-defect-density-area"));
    assertTrue(xml.contains("octane-defect-density-line"));
    assertTrue(xml.contains("fill: #3B82F6"));
    assertTrue(xml.contains("fill-opacity: 0.76"));
    assertTrue(xml.contains("stroke: #3B82F6"));
    assertTrue(xml.contains("stroke-width: 1px"));
    assertTrue(xml.contains("stroke-width=\"1\""));
    assertTrue(xml.contains("shape-rendering: geometricPrecision"));
    assertTrue(xml.contains("shape-rendering=\"geometricPrecision\""));
    assertTrue(xml.contains("octane-defect-density-axis-line-dotted"));
    assertTrue(xml.contains("data-defect-density-bucket=\"true\""));
    assertEquals(
        1,
        page.getByXPath(
                "//*[contains(concat(' ', normalize-space(@class), ' '),"
                    + " ' octane-defect-trend-axis-line ')]")
            .size());
    assertTrue(xml.contains("font-size: clamp(0.66rem, 2.1cqw, 0.75rem)"));
    assertTrue(xml.contains("octane-defect-trend-plot"));
    assertTrue(xml.contains("octane-defect-trend-y-labels"));
    assertTrue(xml.contains("octane-defect-trend-x-labels"));
    assertTrue(xml.contains("stroke-dasharray: 5 7"));
    assertTrue(xml.contains("stroke-width: clamp(2px, 0.28cqw, 4px)"));
    assertTrue(xml.contains("function niceDefectTrendScale"));
    assertTrue(xml.contains("var maximum = Math.ceil(safeMaximum) + 1"));
    assertTrue(
        xml.contains("for (var tick = 1; tick <= intervals; tick += 1)")
            || xml.contains("for (var tick = 1; tick &lt;= intervals; tick += 1)"));
    assertTrue(xml.contains("var yAxisValues = defectTrendYAxisValues(scale)"));
    assertTrue(xml.contains("function animateDefectTrend"));
    assertTrue(xml.contains("scheduleTimerFrame(animateDefectTrend)"));
    assertTrue(xml.contains("function updateDefectTrend"));
    assertTrue(xml.contains("updateDefectTrend(payload)"));
    assertTrue(xml.contains("function setDefectAnalyticsView"));
    assertTrue(xml.contains("data-defect-view-title"));
    assertTrue(xml.contains("selectedButton.getAttribute(\"data-defect-title\")"));
    assertTrue(xml.contains("function buildDefectDensityBuckets"));
    assertTrue(xml.contains("function renderDefectDensity"));
    assertTrue(xml.contains("Math.ceil(safeMaximum) + 1"));
    assertTrue(xml.contains("function densityXAxisIntervalCount"));
    assertTrue(xml.contains("function formatDensityClockOffset"));
    assertTrue(xml.contains("OCTANE_DEFECT_DENSITY_MATH_START"));
    assertTrue(xml.contains("OCTANE_DEFECT_DENSITY_MATH_END"));
    assertTrue(xml.contains("event.target.closest(\".octane-defect-pane-toggle\")"));
    assertTrue(xml.contains("octane-flip-face-breakdown"));
    assertTrue(xml.contains("data-execution-breakdown-panel=\"true\""));
    assertTrue(xml.contains("octane-execution-breakdown-content"));
    assertTrue(xml.contains("data-status-count=\"2\""));
    assertTrue(xml.contains("octane-execution-half-pie"));
    assertTrue(xml.contains("octane-execution-half-pie-segment"));
    assertTrue(
        xml.contains("viewBox=\"0 36 320 160\"") || xml.contains("viewbox=\"0 36 320 160\""));
    assertTrue(
        xml.contains("preserveAspectRatio=\"xMidYMid meet\"")
            || xml.contains("preserveaspectratio=\"xMidYMid meet\""));
    assertTrue(xml.contains("octane-execution-half-pie-total\" x=\"160\" y=\"146\""));
    assertTrue(xml.contains("octane-execution-half-pie-label\" x=\"160\" y=\"172\""));
    assertTrue(xml.contains("stroke-width: 24"));
    assertTrue(xml.contains("width: min(92cqw, var(--octane-execution-content-width), 80rem)"));
    assertTrue(xml.contains("grid-template-rows: minmax(0, 1fr) auto"));
    assertTrue(xml.contains("function fitExecutionBreakdown"));
    assertTrue(xml.contains("function executionBreakdownDimensions"));
    assertTrue(xml.contains("OCTANE_EXECUTION_BREAKDOWN_MATH_START"));
    assertTrue(xml.contains("new window.ResizeObserver(scheduleExecutionBreakdownScaling)"));
    assertTrue(xml.contains("font-size: clamp(0.62rem, min(1.8cqw, 2.4cqh), 1.3rem)"));
    assertTrue(xml.contains("font-size: clamp(1rem, 2cqw, 1.5rem)"));
    assertTrue(xml.contains("octane-flip-face-metrics"));
    assertTrue(xml.contains("data-test-metrics-panel=\"true\""));
    assertTrue(xml.contains("octane-test-metrics-grid"));
    assertTrue(xml.contains("octane-test-metric-card"));
    assertTrue(xml.contains("gap: clamp(0.3rem"));
    assertTrue(xml.contains("border-radius: 12px"));
    assertFalse(xml.contains(".octane-test-metric-card:nth-child(3)"));
    assertFalse(xml.contains(".octane-test-metric-card:nth-child(4)"));
    assertTrue(xml.contains("data-test-metric-key=\"automation-usage\""));
    assertTrue(xml.contains("data-test-metric-key=\"success-rate\""));
    assertTrue(xml.contains("data-test-metric-key=\"execution\""));
    assertTrue(xml.contains("data-test-metric-key=\"defects\""));
    assertTrue(xml.contains("octane-test-metric-automation-track"));
    assertTrue(xml.contains("octane-test-metric-gauge-fill"));
    assertTrue(xml.contains("octane-test-metric-progress"));
    assertTrue(xml.contains("data-test-metric-segments=\"true\""));
    assertTrue(xml.contains("fitTestMetricSegmentLabels"));
    assertTrue(xml.contains("new window.ResizeObserver"));
    assertTrue(xml.contains("octane-test-metric-trend-warning"));
    assertTrue(xml.contains("octane-zone-focus-toggle"));
    assertTrue(xml.contains("octane-icon-zone-expand"));
    assertFalse(xml.contains("octane-icon-zone-collapse"));
    assertTrue(xml.contains("Expand section"));
    assertFalse(xml.contains("Collapse section"));
    assertTrue(xml.contains("octane-zone-focused"));
    assertTrue(xml.contains("grid-template-columns: repeat(2, minmax(0, 1fr))"));
    assertTrue(xml.contains(".octane-timer-zone.octane-zone-focused"));
    assertTrue(xml.contains(".octane-report-zone.octane-zone-focused"));
    assertTrue(xml.contains(".octane-chart-card:nth-of-type(3)"));
    assertTrue(xml.contains(".octane-chart-card:nth-of-type(4)"));
    assertTrue(xml.contains("grid-column: 1"));
    assertTrue(xml.contains("grid-column: 2"));
    assertTrue(xml.contains("grid-template-columns: minmax(0, 1fr)"));
    assertTrue(xml.contains("data-zone-key=\"timers\""));
    assertTrue(xml.contains("data-zone-key=\"reports\""));
    assertTrue(xml.contains("aria-expanded=\"false\""));
    assertTrue(xml.contains("Expand widget"));
    assertTrue(xml.contains("octane-expanded-backdrop"));
    assertTrue(xml.contains(".octane-chart-card.octane-expanded"));
    assertTrue(xml.contains("data-octane-overlay-inert"));
    assertTrue(xml.contains("activateOverlayAccessibility"));
    assertTrue(xml.contains("releaseOverlayAccessibility"));
    assertTrue(xml.contains("document.addEventListener(\"focusin\""));
    assertTrue(xml.contains("inset: 1rem"));
    assertTrue(xml.contains("padding: 1rem"));
    assertTrue(xml.contains("height: auto"));
    assertTrue(xml.contains("max-width: none"));
    assertTrue(xml.contains("width: auto"));
    assertFalse(xml.contains("width: calc(100vw - 2rem)"));
    assertFalse(xml.contains(".octane-zone-focused .octane-zone-focus-toggle"));
    assertTrue(xml.contains("--octane-axis-label-row: clamp(1.05rem, 2vh, 1.7rem)"));
    assertTrue(xml.contains("grid-template-rows: minmax(0, 1fr) var(--octane-axis-label-row)"));
    assertTrue(xml.contains("font-size: 12px"));
    assertTrue(xml.contains("max-height: 100%"));
    assertTrue(xml.contains("resize: none"));
    assertTrue(xml.contains("max-height: min(76vh, 76vw)"));
    assertTrue(xml.contains("--octane-axis-label-row: 1.7rem"));
    assertTrue(xml.contains("data-card-key=\"timer-timeout\""));
    assertTrue(xml.contains("data-card-key=\"timer-poll\""));
    assertTrue(xml.contains("data-card-key=\"progress-execution\""));
    assertTrue(xml.contains("data-card-key=\"progress-pass-rate\""));
    assertTrue(xml.contains("data-card-key=\"distribution-regressions\""));
    assertTrue(xml.contains("data-card-key=\"bars-regressions\""));
    assertFalse(xml.contains("total runs"));
    assertFalse(xml.contains("grid-template-columns: minmax(180px, 240px) max-content"));
    assertTrue(xml.contains("display: inline-flex"));
    assertFalse(xml.contains("octane-legend-value"));
    assertTrue(xml.contains("octane-suite-chart-meta"));
    assertTrue(xml.contains("octane-bar-graph"));
    assertTrue(xml.contains("octane-chart-data-summary"));
    assertTrue(xml.contains("<caption>REGRESSION Tests Status Distribution</caption>"));
    assertTrue(
        xml.contains("<caption>Testing progress per Tester Suite Runs_REGRESSIONS</caption>"));
    assertTrue(xml.contains("octane-y-axis-label"));
    assertTrue(xml.contains(">Count<"));
    assertTrue(xml.contains("#827C7B"));
    assertTrue(xml.contains("background-image: radial-gradient"));
    assertTrue(xml.contains("rgba(33, 38, 45, 0.92)"));
    assertTrue(
        xml.contains("background-size: 0.55rem calc(100% / var(--octane-grid-line-count, 4))"));
    assertTrue(xml.contains("#30363D"));
    assertTrue(xml.contains("octane-grid-line-count"));
    assertFalse(xml.contains("border-left: 1px solid #576779"));
    assertFalse(xml.contains("#CCCCCC"));
    assertTrue(xml.contains("column-gap: 0.09rem"));
    assertTrue(xml.contains("grid-template-columns: 1.35rem max-content minmax(0, 1fr)"));
    assertTrue(xml.contains("grid-template-rows: 260px var(--octane-axis-label-row)"));
    assertTrue(xml.contains("octane-bar-plot"));
    assertTrue(xml.contains("octane-vertical-bars"));
    assertTrue(xml.contains("octane-vertical-bar-wrap"));
    assertTrue(xml.contains("octane-fluid-bars-dense"));
    assertTrue(xml.contains("octane-bar-overflow-indicator"));
    assertTrue(xml.contains("octane-bar-overflow-line"));
    assertTrue(xml.contains("octane-bar-overflow-count"));
    assertTrue(xml.contains("flex: 0 0 24px"));
    assertFalse(xml.contains("margin-inline-start: auto"));
    assertTrue(xml.contains("max-width: 24px"));
    assertTrue(xml.contains("width: 24px"));
    assertTrue(xml.contains("flex: 1 1 auto"));
    assertTrue(xml.contains("min-width: 8px !important"));
    assertTrue(xml.contains("max-width: 100px"));
    assertFalse(xml.contains("margin-right: 2px !important"));
    assertTrue(xml.contains("gap: var(--octane-bar-gap"));
    assertTrue(xml.contains("justify-content: center"));
    assertTrue(xml.contains("padding: 0"));
    assertTrue(xml.contains("var FLUID_BAR_MIN_WIDTH = 8"));
    assertTrue(xml.contains("var FLUID_BAR_MAX_WIDTH = 100"));
    assertTrue(xml.contains("var FLUID_BAR_MIN_GAP = 2"));
    assertTrue(xml.contains("var FLUID_BAR_MAX_GAP = 40"));
    assertTrue(xml.contains("var FLUID_BAR_OVERFLOW_WIDTH = 24"));
    assertTrue(xml.contains("function maxVisibleBarsForWidth(width)"));
    assertTrue(
        xml.contains("function fluidBarLayoutForWidth(width, visibleBarCount, hasOverflow)"));
    assertTrue(xml.contains("container.getBoundingClientRect().width"));
    assertTrue(xml.contains("--octane-bar-width"));
    assertTrue(xml.contains("--octane-bar-gap"));
    assertTrue(xml.contains("var allSuiteRuns = container.octaneAllSuiteRuns"));
    assertTrue(xml.contains("allSuiteRuns.length - maxVisibleBars"));
    assertTrue(
        xml.contains("count.textContent = &quot;+&quot; + hiddenCount")
            || xml.contains("count.textContent = \"+\" + hiddenCount"));
    assertTrue(xml.contains("new window.ResizeObserver(scheduleFluidBarCharts)"));
    assertTrue(xml.contains("initializeFluidBarCharts(updatedReportZone)"));
    assertFalse(xml.contains("octane-x-axis-labels"));
    assertFalse(xml.contains("octane-axis-label-column"));
    assertTrue(xml.contains("overflow-x: hidden"));
    assertTrue(
        xml.contains(
            ".octane-management-state-bars {\n"
                + "          --octane-management-bar-gap: clamp(2px, 1cqw, 40px);"));
    assertTrue(
        xml.contains(
            ".octane-management-failure-chart {\n"
                + "          --octane-management-bar-gap: clamp(2px, 1cqw, 40px);"));
    assertTrue(xml.contains("overflow-x: auto"));
    assertTrue(xml.contains("grid-template-rows: minmax(0, 1fr) var(--octane-axis-label-row)"));
    assertFalse(xml.contains(".octane-zone-focused .octane-suite-column"));
    assertFalse(xml.contains(".octane-chart-card.octane-expanded .octane-suite-column"));
    assertTrue(xml.contains("max-width: min(100%, 6.8rem)"));
    assertTrue(xml.contains("width: 100%"));
    assertTrue(
        xml.contains("font-family: Inter, &quot;Segoe UI&quot;, Arial, sans-serif")
            || xml.contains("font-family: Inter, \"Segoe UI\", Arial, sans-serif"));
    assertTrue(xml.contains("font-weight: 400"));
    assertTrue(xml.contains("color: #827C7B"));
    assertTrue(xml.contains("text-align: center"));
    assertTrue(xml.contains("transform: none"));
    assertTrue(xml.contains("rotate(-45deg)"));
    assertTrue(xml.contains("rotate(-90deg)"));
    assertTrue(xml.contains("transform-origin: right top"));
    assertTrue(xml.contains("octane-bar-popup"));
    assertTrue(xml.contains("id=\"octane-bar-popup-overlay\""));
    assertTrue(xml.contains("data-bar-key=\""));
    assertTrue(xml.contains("data-dominant-status-color=\""));
    assertTrue(xml.contains("data-dominant-status-label=\""));
    assertTrue(xml.contains("data-status-passed-count=\""));
    assertTrue(xml.contains("data-status-passed-color=\"#30D158\""));
    assertTrue(xml.contains("data-status-failed-color=\"#FF453A\""));
    assertTrue(xml.contains("data-status-blocked-color=\"#FF9F0A\""));
    assertTrue(xml.contains("data-status-skipped-color=\"#BF5AF2\""));
    assertTrue(xml.contains("data-status-running-color=\"#8E8E93\""));
    assertTrue(
        xml.contains(
            "border: 1px solid var(--octane-popup-border-color, var(--panel-border-color))"));
    assertTrue(xml.contains("0 0 0 1px var(--octane-popup-border-color, transparent)"));
    assertTrue(xml.contains("--octane-popup-border-color: #"));
    assertTrue(xml.contains("background: #30D158"));
    assertTrue(xml.contains("background: #FF453A"));
    assertTrue(xml.contains("min-width: min(10.92rem, calc(100vw - 1rem))"));
    assertTrue(xml.contains("position: fixed"));
    assertTrue(xml.contains("font-size: 0.644rem"));
    assertTrue(xml.contains("overflow-wrap: anywhere"));
    assertTrue(xml.contains("octane-bar-popup-name"));
    assertTrue(xml.contains("octane-bar-popup-row"));
    assertTrue(xml.contains("octane-bar-popup-total"));
    assertTrue(xml.contains("octane-bar-popup-visible"));
    assertTrue(xml.contains("octane-bar-popup-restoring"));
    assertTrue(xml.contains("positionBarPopup"));
    assertTrue(xml.contains("chooseBarPopupPlacement"));
    assertTrue(xml.contains("chartViewportRectangle"));
    assertTrue(xml.contains("pointerAnchorRectangle"));
    assertTrue(xml.contains("barRectanglesForColumn"));
    assertTrue(xml.contains("pointInsideBar"));
    assertFalse(xml.contains("pointInsideColumn"));
    assertTrue(xml.contains("document.body.appendChild(barPopupOverlay)"));
    assertTrue(xml.contains("var bar = event.target.closest(\".octane-vertical-bar\")"));
    assertTrue(xml.contains("event.relatedTarget && bar.contains(event.relatedTarget)"));
    assertTrue(xml.contains("positionBarPopup(barPopupOverlay, column, point, input)"));
    assertTrue(xml.contains("popup.setAttribute(\"data-placement\""));
    assertTrue(xml.contains("window.addEventListener(\"resize\", scheduleActiveBarPopupRefresh)"));
    assertTrue(
        xml.contains("barPopupViewportTimer = window.setTimeout(refreshActiveBarPopup, 100)"));
    assertFalse(xml.contains(".octane-suite-column:hover .octane-bar-popup"));
    assertFalse(xml.contains("transform: translate(-50%"));
    assertFalse(xml.contains("class=\"octane-total\""));
    assertTrue(xml.contains("octane-timer-donut"));
    assertTrue(xml.contains("viewBox=\"0 0 240 240\"") || xml.contains("viewbox=\"0 0 240 240\""));
    assertTrue(xml.contains("shape-rendering: geometricPrecision"));
    assertFalse(xml.contains("octane-timer-border"));
    assertFalse(xml.contains("octane-timer-progress-halo"));
    assertFalse(xml.contains("data-timer-progress-halo=\"true\""));
    assertFalse(xml.contains("octane-timer-extended-progress"));
    assertFalse(xml.contains("data-timer-extended-progress=\"true\""));
    assertFalse(xml.contains("M120 36 A84 84 0 1 0 120 204 A84 84 0 1 0 120 36"));
    assertEquals(
        1,
        page.getByXPath(
                "//section[@data-card-key='timer-timeout']"
                    + "//*[local-name()='circle' and @data-timer-progress='true'"
                    + " and @cx='120' and @cy='120' and @r='84']")
            .size());
    assertTrue(xml.contains("transform=\"rotate(-90 120 120)\""));
    assertTrue(xml.contains("stroke-dasharray=\"100 100\""));
    assertFalse(xml.contains("#881113"));
    assertTrue(xml.contains("data-total-seconds=\"2700\""));
    assertTrue(xml.contains("data-extended-total-seconds=\"0\""));
    assertTrue(xml.contains("data-extended-active=\"false\""));
    assertTrue(xml.contains("data-extended-time=\"false\""));
    assertTrue(xml.contains("data-manual-exit-requested=\"false\""));
    assertTrue(xml.contains("data-manual-exit-requested-at-millis=\"0\""));
    assertTrue(xml.contains("data-exit-extended-form=\"true\""));
    assertTrue(xml.contains("data-visible=\"false\""));
    assertTrue(xml.contains("Exit Octane and Continue"));
    assertTrue(xml.contains("exitOctaneAndContinue"));
    assertTrue(xml.contains("data-total-seconds=\"15\""));
    assertTrue(xml.contains("data-timer-value=\"true\""));
    assertTrue(xml.contains("data-timer-progress=\"true\""));
    assertFalse(xml.contains("data-timer-head=\"true\""));
    assertFalse(xml.contains("octane-timer-head-buffer"));
    assertFalse(xml.contains("data-timer-head-buffer=\"true\""));
    assertTrue(xml.contains("data-timer-tail-stop=\"true\""));
    assertTrue(xml.contains("data-timer-mid-stop=\"true\""));
    assertTrue(xml.contains("octane-timer-zone octane-card-zone"));
    assertTrue(xml.contains("octane-report-zone octane-card-zone"));
    assertTrue(xml.contains("border: 1px solid var(--background)"));
    assertFalse(xml.contains("http-equiv=\"refresh\""));
    assertTrue(xml.contains("data-snapshot-url=\"snapshot\""));
    assertTrue(xml.contains("data-current-updated-at=\"2026-05-15T00:00:00Z\""));
    assertTrue(xml.contains("data-report-status=\"true\""));
    assertTrue(xml.contains("data-report-state-label=\"true\""));
    assertTrue(xml.contains("data-report-updated-at=\"true\""));
    assertTrue(xml.contains("Passed"));
    assertTrue(xml.contains("LAST UPDATED: 2026/05/15 03:00:00"));
    assertTrue(xml.contains("Status Check In :"));
    assertTrue(xml.contains("Updating ... "));
    assertTrue(xml.contains("display: inline"));
    assertTrue(xml.contains("white-space: nowrap"));
    assertTrue(xml.contains("function renderReportStatus(now)"));
    assertTrue(xml.contains("function canApplySnapshotPayload(payload)"));
    assertTrue(
        xml.contains("incomingUpdatedAt &lt; renderedUpdatedAt")
            || xml.contains("incomingUpdatedAt < renderedUpdatedAt"));
    assertTrue(xml.contains("liveRefresh.finalizing ? 250"));
    assertFalse(xml.contains("function fetchRiskHeatMapSnapshot()"));
    assertTrue(xml.contains("setLiveUpdateStatus(\"...\", false)"));
    assertFalse(xml.contains(": \"Done.\""));
    assertFalse(xml.contains("Updating report..."));
    assertFalse(xml.contains("Report updated in "));
    assertFalse(xml.contains("Report finished."));
    assertTrue(xml.contains("id=\"octane-report-zone\""));
    assertFalse(xml.contains("#631919"));
    assertTrue(xml.contains("data-octane-progress=\"completion\""));
    assertTrue(
        xml.contains("data-progress-value=\"100.0\"")
            || xml.contains("data-progress-value=\"100\""));
    assertTrue(xml.contains("data-octane-progress=\"pass-rate\""));
    assertTrue(
        xml.contains("data-progress-value=\"50.0\"") || xml.contains("data-progress-value=\"50\""));
    assertTrue(xml.contains("data-execution-progress-circle=\"true\""));
    assertFalse(xml.contains("data-execution-progress-head=\"true\""));
    assertFalse(xml.contains("data-execution-progress-head-buffer=\"true\""));
    assertTrue(xml.contains("data-progress-circle=\"true\""));
    assertFalse(xml.contains("data-progress-head=\"true\""));
    assertFalse(xml.contains("data-progress-head-buffer=\"true\""));
    assertTrue(xml.contains("data-progress-value-text=\"true\""));
    assertTrue(xml.contains("octane-execution-progress-gradient"));
    assertTrue(xml.contains("octane-pass-rate-progress-gradient"));
    assertTrue(xml.contains("font-size: 2.6082rem"));
    assertTrue(xml.contains("font-size: 0.91875rem"));
    assertTrue(xml.contains("data-timer-value=\"true\" x=\"120\" y=\"118\""));
    assertTrue(xml.contains("data-timer-unit=\"true\" x=\"120\" y=\"132.4\""));
    assertTrue(xml.contains("data-timer-unit-compact=\"true\" x=\"120\" y=\"132.4\""));
    assertTrue(xml.contains("container-name: octane-timer-display"));
    String timerWrapRule = cssRule(xml, ".octane-timer-wrap");
    assertTrue(timerWrapRule.contains("container-type: size"));
    assertTrue(timerWrapRule.contains("flex: 1 1 auto"));
    assertTrue(timerWrapRule.contains("height: 100%"));
    assertTrue(timerWrapRule.contains("min-height: 0"));
    assertTrue(timerWrapRule.contains("min-width: 0"));
    assertTrue(timerWrapRule.contains("width: 100%"));
    String timerDonutRule = cssRule(xml, ".octane-timer-donut");
    assertTrue(timerDonutRule.contains("aspect-ratio: 1 / 1"));
    assertTrue(timerDonutRule.contains("height: min(100cqw, 100cqh, 220px)"));
    assertTrue(timerDonutRule.contains("max-height: 220px"));
    assertTrue(timerDonutRule.contains("max-width: 220px"));
    assertTrue(
        cssRule(xml, ".octane-zone-focused .octane-timer-donut")
            .contains("height: min(100cqw, 100cqh, 38vh, 38vw)"));
    assertTrue(
        cssRule(xml, ".octane-chart-card.octane-expanded .octane-timer-donut")
            .contains("height: min(100cqw, 100cqh, 76vh, 76vw)"));
    assertTrue(xml.contains("@container octane-timer-display (max-width: 18rem)"));
    assertTrue(xml.contains("@media (max-width: 480px)"));
    assertTrue(xml.contains("minutes + seconds"));
    assertTrue(xml.contains("m + s"));
    assertTrue(xml.contains("data-timeout-title=\"true\""));
    assertTrue(xml.contains("data-timeout-subtitle=\"true\""));
    assertTrue(xml.contains("testingTimeSpentMillis"));
    assertTrue(xml.contains("function fitTimerText"));
    assertTrue(xml.contains("116 / (valueLength * 0.56)"));
    assertTrue(xml.contains("function timerDisplayParts"));
    assertTrue(xml.contains("function timeoutCountdownRemainingMillis"));
    assertTrue(xml.contains("fullLabel = \"weeks + days\""));
    assertTrue(xml.contains("fullLabel = \"days + hours\""));
    assertTrue(xml.contains("fullLabel = \"hours + minutes\""));
    assertTrue(xml.contains("fullLabel = \"minutes + seconds\""));
    assertTrue(xml.contains("showSpent ? testingTimeSpentMillis(state, now) : trackRemaining"));
    assertTrue(xml.contains("Testing Session Monitor"));
    assertTrue(xml.contains("Session Time Spent"));
    assertTrue(xml.contains("stroke-width: 1"));
    assertTrue(xml.contains("stroke-width: 16"));
    assertTrue(xml.contains("opacity: 1"));
    assertTrue(xml.contains("TIMER_ACTIVE_OPACITY = \"1\""));
    assertTrue(xml.contains("remainingProgress <= 0 ? \"0\" : TIMER_ACTIVE_OPACITY"));
    assertFalse(xml.contains("class=\"octane-timer-head\""));
    assertFalse(xml.contains("data-timer-head=\"true\""));
    assertFalse(xml.contains("TIMER_HEAD_RADIUS"));
    assertFalse(xml.contains("state.head"));
    assertFalse(xml.contains("octane-timer-leading-shadow"));
    assertFalse(xml.contains("data-timer-leading-shadow=\"true\""));
    assertTrue(xml.contains("function timerTrackTotalMillis"));
    assertTrue(xml.contains("return state.totalMillis + state.extendedTotalMillis"));
    assertTrue(xml.contains("function timerTrackRemainingMillis"));
    assertTrue(xml.contains("trackRemaining / trackTotalMillis"));
    assertTrue(xml.contains("function timerColorProgress"));
    assertTrue(xml.contains("(testingTimeSpentMillis(state, now) / state.totalMillis) * 100"));
    assertFalse(xml.contains("function extendedProgressPercent"));
    assertTrue(xml.contains("style=\"height: 100.00%;\""));
    assertTrue(xml.contains("title=\"Ada Tester (suite runs: 4501)\""));
    assertTrue(xml.contains("--octane-color-good: var(--octane-system-green)"));
    assertTrue(xml.contains("--octane-color-warn: var(--octane-system-yellow)"));
    assertTrue(xml.contains("--octane-color-bad: var(--octane-system-red)"));
    assertTrue(xml.contains("--octane-color-neutral: var(--octane-system-blue)"));
    assertTrue(xml.contains("--octane-system-yellow: #FFCC00"));
    assertTrue(xml.contains("--octane-system-yellow: #FFD60A"));
    assertTrue(xml.contains("--octane-system-blue: #007AFF"));
    assertTrue(xml.contains("--octane-system-blue: #0A84FF"));
    assertTrue(xml.contains("@media (prefers-color-scheme: dark)"));
    assertTrue(xml.contains("html[data-theme=\"dark\"] .octane-dashboard"));
    assertTrue(xml.contains("html[data-theme=\"light\"] .octane-dashboard"));
    assertTrue(xml.contains("var COLOR_GOOD = \"--octane-color-good\""));
    assertTrue(xml.contains("var COLOR_WARN = \"--octane-color-warn\""));
    assertTrue(xml.contains("var COLOR_BAD = \"--octane-color-bad\""));
    assertTrue(xml.contains("var COLOR_NEUTRAL = \"--octane-color-neutral\""));
    assertTrue(xml.contains("PROGRESS_COLOR_PHASES"));
    assertTrue(xml.contains("passRate: ["));
    assertTrue(xml.contains("limit: 20"));
    assertTrue(xml.contains("limit: 49"));
    assertTrue(xml.contains("limit: 50"));
    assertTrue(xml.contains("limit: 79"));
    assertTrue(xml.contains("limit: 89"));
    assertTrue(xml.contains("token: COLOR_GOOD"));
    assertTrue(xml.contains("token: COLOR_WARN"));
    assertTrue(xml.contains("token: COLOR_BAD"));
    assertTrue(xml.contains("token: COLOR_NEUTRAL"));
    assertTrue(xml.contains("colorTokenValue(phases[index].token)"));
    assertFalse(xml.contains("#fb4b4b"));
    assertFalse(xml.contains("#e14343"));
    assertFalse(xml.contains("#ff7e5f"));
    assertFalse(xml.contains("#fff47f"));
    assertFalse(xml.contains("#4caf50"));
    assertFalse(xml.contains("#4bfb4b"));
    assertFalse(xml.contains("#3cc83c"));
    assertFalse(xml.contains("#757575"));
    assertFalse(xml.contains("#95b1c8"));
    assertFalse(xml.contains("COMPLETE_PROGRESS_COLORS"));
    assertFalse(xml.contains("#00e676"));
    assertFalse(xml.contains("#00b85e"));
    assertFalse(xml.contains("progress >= 100"));
    assertTrue(xml.contains("applyGradientStops"));
    assertTrue(xml.contains("progressKind === \"pass-rate\" ? \"passRate\" : progressKind"));
    assertFalse(xml.contains("progressCircle.setAttribute(\"stroke\", executionColor)"));
    assertFalse(xml.contains("progressHalo.setAttribute(\"stroke\", executionColor)"));
    assertTrue(xml.contains("requestAnimationFrame"));
    assertTrue(xml.contains("prefers-reduced-motion: reduce"));
    assertTrue(xml.contains("matchMedia(\"(prefers-reduced-motion: reduce)"));
    assertTrue(xml.contains("scheduleTimerFrame"));
    assertTrue(xml.contains("window.setTimeout(callback, 1000)"));
    assertTrue(xml.contains("moveCardWithKeyboard"));
    assertTrue(xml.contains("performance.now"));
    assertTrue(xml.contains("fetchSnapshot"));
    assertTrue(xml.contains("beginSnapshotRefresh"));
    assertTrue(xml.contains("retryDelayMillis: 500"));
    assertTrue(xml.contains("id=\"octane-connectivity-status\""));
    assertTrue(xml.contains("aria-live=\"polite\""));
    assertTrue(xml.contains("function snapshotRequestFailed"));
    assertTrue(xml.contains("maximumRetryDelayMillis: 15000"));
    assertTrue(xml.contains("The last good report remains visible."));
    assertTrue(xml.contains("document.hidden"));
    assertTrue(xml.contains("window.addEventListener(\"offline\""));
    assertTrue(xml.contains("window.addEventListener(\"online\""));
    assertFalse(xml.contains("updateRiskHeatMap({riskHeatMapHtml: \"\"})"));
    assertTrue(xml.contains("data-risk-heat-map-populated"));
    assertTrue(xml.contains("lastViableRiskHeatMapHtml"));
    assertTrue(xml.contains("terminalUpdate && payload.riskHeatMapPopulated !== true"));
    assertTrue(xml.contains("fetchSnapshot(true)"));
    assertTrue(xml.contains("createExpandButton"));
    assertTrue(xml.contains("createZoneFocusButton"));
    assertTrue(xml.contains("removeZoneFocusButton"));
    assertTrue(xml.contains("restoreZoneFocusButton"));
    assertTrue(xml.contains("decorateReportZone(updatedReportZone)"));
    assertTrue(xml.contains("decorateCardZones(updatedReportZone)"));
    assertTrue(xml.contains("focusZone"));
    assertTrue(xml.contains("focusedKey"));
    assertTrue(xml.contains("setZoneButtonState"));
    assertTrue(xml.contains("removeFocusedZone"));
    assertTrue(xml.contains("findZoneByKey"));
    assertTrue(xml.contains("findCardByKey"));
    assertTrue(xml.contains("function autoShowHeatMapOnCompletion"));
    assertTrue(xml.contains("function autoShowTestMetricsOnCompletion"));
    assertTrue(xml.contains("function autoShowExecutionBreakdownOnCompletion"));
    assertTrue(xml.contains("function autoShowQaHealthMetricsOnCompletion"));
    assertTrue(xml.contains("function completionReached"));
    assertTrue(xml.contains("function manualExitRequested"));
    assertTrue(xml.contains("function primaryTimeoutReached"));
    assertTrue(xml.contains("function totalTimeoutReached"));
    assertTrue(xml.contains("(timeoutSeconds + Math.max(0, timeoutExtendedSeconds)) * 1000"));
    assertTrue(xml.contains("manualExitRequested(payload) || totalTimeoutReached(payload)"));
    assertFalse(xml.contains("function initialAutoFlipBoundaryReached"));
    assertTrue(xml.contains("function autoShowCardViewOnce"));
    assertTrue(xml.contains("function runCompletionAutoFlips"));
    assertTrue(xml.contains("function hasAutoFlipped"));
    assertTrue(xml.contains("var completionAutoFlipState"));
    assertTrue(xml.contains("payload.manualExitRequested === true"));
    assertTrue(xml.contains("data-manual-exit-requested"));
    assertTrue(xml.contains("data-has-auto-flipped"));
    assertTrue(xml.contains("function updateTestMetrics"));
    assertTrue(xml.contains("payload.testMetricsHtml"));
    assertTrue(xml.contains("function updateExecutionStatusDistribution"));
    assertTrue(xml.contains("payload.executionStatusDistributionHtml"));
    assertTrue(xml.contains("autoShowCardViewOnce(payload, \"timer-timeout\", \"metrics\")"));
    assertTrue(
        xml.contains("autoShowCardViewOnce(payload, \"progress-execution\", \"breakdown\")"));
    assertTrue(xml.contains("autoShowCardViewOnce(payload, \"progress-pass-rate\", \"metrics\")"));
    assertTrue(xml.contains("runCompletionAutoFlips(currentReportPayload())"));
    assertTrue(xml.contains("runCompletionAutoFlips(payload)"));
    assertTrue(xml.contains("button.getAttribute(\"data-target-view\")"));
    assertTrue(xml.contains("button.getAttribute(\"data-target-view-label\")"));
    assertTrue(xml.contains("card.querySelector(\".octane-view-toggle\")"));
    assertTrue(xml.contains("payload.stateLabel === \"Timed out\""));
    assertTrue(xml.contains("timeoutSeconds * 1000"));
    assertTrue(xml.contains("function completionProgressReached"));
    assertTrue(xml.contains("isFinite(completionProgress) && completionProgress >= 100"));
    assertTrue(
        xml.contains(
            "return timedOut || primaryTimeoutReached(payload)"
                + " || completionProgressReached(payload)"));
    assertTrue(
        xml.contains(
            "return timedOut || manualExitRequested(payload)"
                + " || totalTimeoutReached(payload)"));
    assertTrue(xml.contains("setCardView(card, targetView)"));
    assertTrue(xml.contains("autoShowCardViewOnce(payload, \"timer-poll\", \"heatmap\")"));
    assertTrue(xml.contains("expandedKey"));
    assertTrue(xml.contains("expandedBackdrop.addEventListener(\"click\""));
    assertTrue(xml.contains("setExpandButtonState"));
    assertTrue(xml.contains("removeExpandedState"));
    assertTrue(xml.contains("event.key === \"Escape\""));
    assertTrue(xml.contains("event.target.closest(\".octane-expand-toggle\")"));
    assertTrue(xml.contains("event.target.closest(\".octane-zone-focus-toggle\")"));
    assertTrue(xml.contains("card.setAttribute(\"draggable\", \"false\")"));
    assertTrue(xml.contains("card.classList.contains(\"octane-expanded\")"));
    assertTrue(xml.contains("captureBarPopupRestoreState"));
    assertTrue(xml.contains("barPopupRefreshInProgress = true"));
    assertTrue(xml.contains("restoreBarPopupAfterRefresh(updatedReportZone"));
    assertTrue(xml.contains("barPopupOverlay.innerHTML = source.innerHTML"));
    assertTrue(xml.contains("applyBarPopupDominantColor"));
    assertTrue(xml.contains("statusMetricForColumn(column, \"failed\", \"Failed\")"));
    assertTrue(xml.contains("statusMetricForColumn(column, \"blocked\", \"Blocked\")"));
    assertTrue(xml.contains("statusMetricForColumn(column, \"passed\", \"Passed\")"));
    assertTrue(xml.contains("statusMetricForColumn(column, \"skipped\", \"Skipped\")"));
    assertTrue(xml.contains("statusMetricForColumn(column, \"running\", \"In Progress\")"));
    assertTrue(xml.contains("metrics[index].count > dominant.count"));
    assertTrue(xml.contains("data-status-\" + key + \"-count"));
    assertTrue(xml.contains("data-status-\" + key + \"-color"));
    assertTrue(
        xml.contains("barPopupOverlay.style.setProperty(\"--octane-popup-border-color\", color)"));
    assertTrue(
        xml.contains("barPopupOverlay.style.removeProperty(\"--octane-popup-border-color\")"));
    assertFalse(xml.contains("barPopupOverlay.style.borderColor = color"));
    assertFalse(xml.contains("barPopupOverlay.style.boxShadow ="));
    assertTrue(xml.contains("findColumnByKeys"));
    assertTrue(xml.contains("replaceWith(updatedReportZone)"));
    assertTrue(xml.contains("payload.passRateProgress"));
    assertTrue(xml.contains("payload.passRateLabel"));
    assertFalse(xml.contains("setInterval(updateTimers, 1000)"));
    assertFalse(xml.contains("transition: stroke-dasharray"));
    assertTrue(xml.contains("draggable=\"true\""));
    assertTrue(xml.contains("overflow-y: hidden"));
    assertTrue(xml.contains("text-overflow: ellipsis"));
    assertTrue(xml.contains(".octane-vertical-bars::before"));
    assertTrue(xml.contains("zoneForCard"));
    assertTrue(xml.contains("targetZone !== draggedZone"));
    assertBarPopupInteractions(page);
  }

  @Test
  public void activeReportUsesSnapshotRefreshInsteadOfMetaRefresh() throws Exception {
    FreeStyleProject project = jenkins.createFreeStyleProject();
    FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
    GateRequest request = new GateRequest("octane-prod", "4501");
    request.setPollIntervalSeconds(15);

    OctaneGateReportAction.attachTo(build, request);

    HtmlPage page = jenkins.createWebClient().getPage(build, OctaneGateReportAction.URL_NAME);
    String xml = page.asXml();
    assertFalse(xml.contains("http-equiv=\"refresh\""));
    assertTrue(xml.contains("data-report-building=\"true\""));
    assertTrue(xml.contains("window.fetch(snapshotUrl"));
    assertTrue(xml.contains("payload.updatedAt !== currentUpdatedAt"));
    assertTrue(xml.contains("payload.building === false"));
  }

  @Test
  public void servesSnapshotJsonWithoutSecrets() throws Exception {
    FreeStyleProject project = jenkins.createFreeStyleProject();
    FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
    GateRequest request = new GateRequest("octane-prod", "4501");
    request.setPollIntervalSeconds(15);

    OctaneGateReportAction action = OctaneGateReportAction.attachTo(build, request);
    action.onFinal(
        OctaneGateReportState.PASSED,
        "ALM Octane suite gate passed.",
        result(),
        new StatusClassifier(
            StatusClassifier.DEFAULT_PASSED_STATUSES,
            StatusClassifier.DEFAULT_FAILED_STATUSES,
            StatusClassifier.DEFAULT_NEUTRAL_STATUSES,
            StatusClassifier.DEFAULT_RUNNING_STATUSES));

    Page jsonPage =
        jenkins
            .createWebClient()
            .getPage(
                jenkins
                    .getURL()
                    .toURI()
                    .resolve(build.getUrl() + OctaneGateReportAction.URL_NAME + "/snapshot")
                    .toURL());
    String json = jsonPage.getWebResponse().getContentAsString();
    JSONObject payload = JSONObject.fromObject(json);

    assertTrue(jsonPage.getWebResponse().getContentType().contains("application/json"));
    assertEquals("2026-05-15T00:00:00Z", payload.getString("updatedAt"));
    assertEquals("03:00:00", payload.getString("updatedAtText"));
    assertEquals("2026/05/15 03:00:00", payload.getString("updatedAtDateTimeText"));
    assertEquals("2026-05-15T00:00:00Z", payload.getString("reconciledAt"));
    assertEquals("2026/05/15 03:00:00", payload.getString("reconciledAtDateTimeText"));
    assertTrue(payload.containsKey("startedAt"));
    assertFalse(payload.getBoolean("building"));
    assertFalse(payload.getBoolean("finalizing"));
    assertFalse(payload.getBoolean("timerActive"));
    assertEquals("Passed", payload.getString("stateLabel"));
    assertEquals("Passed", payload.getString("jobStateLabel"));
    assertEquals("Ada Tester", payload.getJSONObject("suiteAttributions").getString("4501"));
    assertEquals(15, payload.getInt("refreshSeconds"));
    assertEquals(7200, payload.getInt("timeoutSeconds"));
    assertEquals(0, payload.getInt("timeoutExtendedSeconds"));
    assertFalse(payload.getBoolean("extendedTime"));
    assertFalse(payload.getBoolean("manualExitRequested"));
    assertFalse(payload.getBoolean("riskHeatMapPopulated"));
    assertEquals(0L, payload.getLong("manualExitRequestedAtMillis"));
    assertEquals(2, payload.getInt("executedTestCount"));
    assertEquals("100%", payload.getString("executionProgressText"));
    assertEquals(2, payload.getInt("resolvedTestCount"));
    assertEquals(100.0, payload.getDouble("completionProgress"), 0.001);
    assertEquals("100%", payload.getString("completionProgressText"));
    assertEquals(50.0, payload.getDouble("passRateProgress"), 0.001);
    assertEquals("50%", payload.getString("passRateProgressText"));
    assertEquals("All Testcase Pass Rate (1 / 2)", payload.getString("passRateLabel"));
    assertTrue(payload.containsKey("testerDetails"));
    assertEquals(95, payload.getJSONObject("testerDetails").getInt("basePassrateFigure"));
    assertEquals(100, payload.getJSONObject("testerDetails").getInt("baseExecutionFigure"));
    assertEquals(1, payload.getJSONObject("testerDetails").getJSONArray("passRateTesters").size());
    assertTrue(payload.getJSONObject("testerDetails").getJSONArray("definedScope").isEmpty());
    assertTrue(payload.getString("testMetricsHtml").contains("octane-test-metrics-grid"));
    assertTrue(
        payload.getString("executionStatusDistributionHtml").contains("octane-execution-half-pie"));
    assertTrue(
        payload.getString("executionStatusDistributionHtml").contains("x=\"160\" y=\"146\""));
    assertTrue(
        payload.getString("executionStatusDistributionHtml").contains("x=\"160\" y=\"172\""));
    assertEquals(4, payload.getJSONObject("testMetrics").getJSONArray("cards").size());
    assertEquals(2, payload.getJSONObject("testMetrics").getInt("automationTestTotal"));
    assertEquals(0, payload.getJSONObject("testMetrics").getInt("automationPercentage"));
    assertTrue(payload.getString("testMetricsHtml").contains("0/2 tests automated. Target 100%"));
    assertTrue(
        payload
            .getJSONObject("testMetrics")
            .getJSONArray("cards")
            .getJSONObject(0)
            .containsKey("sparklinePoints"));
    assertTrue(
        payload
            .getJSONObject("testMetrics")
            .getJSONArray("cards")
            .getJSONObject(1)
            .containsKey("progressPercent"));
    assertTrue(
        payload
            .getJSONObject("testMetrics")
            .getJSONArray("cards")
            .getJSONObject(3)
            .containsKey("segments"));
    assertTrue(payload.getJSONObject("defectTrend").getJSONArray("points").size() >= 1);
    assertEquals(0, payload.getJSONObject("defectTrend").getInt("openedTotal"));
    assertEquals("#ff6361", payload.getJSONObject("defectTrend").getString("openedColor"));
    assertEquals("#7BE5B3", payload.getJSONObject("defectTrend").getString("closedColor"));
    assertTrue(payload.getJSONObject("defectTrend").getJSONArray("densityBuckets").size() >= 1);
    assertTrue(payload.containsKey("testManagement"));
    assertEquals(
        0, payload.getJSONObject("testManagement").getJSONArray("failureCategories").size());
    assertEquals(4, payload.getJSONObject("testManagement").getJSONArray("metrics").size());
    assertTrue(payload.getJSONObject("testManagement").getJSONArray("points").size() >= 1);
    assertEquals(
        10, payload.getJSONObject("testManagement").getJSONArray("executionIntervals").size());
    assertTrue(
        payload
            .getJSONObject("defectTrend")
            .getJSONArray("points")
            .getJSONObject(0)
            .containsKey("executed"));
    assertTrue(payload.getString("reportZoneHtml").contains("id=\"octane-report-zone\""));
    assertFalse(payload.getString("reportZoneHtml").contains("id=\"octane-timer-zone\""));
    assertFalse(json.toLowerCase(Locale.ROOT).contains("client_id"));
    assertFalse(json.toLowerCase(Locale.ROOT).contains("client_secret"));
    assertFalse(json.toLowerCase(Locale.ROOT).contains("password"));
    assertFalse(json.toLowerCase(Locale.ROOT).contains("credentialsid"));
  }

  @Test
  public void extendedTimeReportShowsManualExitControl() throws Exception {
    FreeStyleProject project = jenkins.createFreeStyleProject();
    FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
    GateRequest request = new GateRequest("octane-prod", "4501");
    request.setTimeoutMinutes(45);
    request.setTimeoutMinutesExtended(10);

    OctaneGateReportAction action = OctaneGateReportAction.attachTo(build, request);
    action.onExtendedTime(
        result(),
        new StatusClassifier(
            StatusClassifier.DEFAULT_PASSED_STATUSES,
            StatusClassifier.DEFAULT_FAILED_STATUSES,
            StatusClassifier.DEFAULT_NEUTRAL_STATUSES,
            StatusClassifier.DEFAULT_RUNNING_STATUSES));

    HtmlPage page = jenkins.createWebClient().getPage(build, OctaneGateReportAction.URL_NAME);
    String xml = page.asXml();

    assertTrue(xml.contains("data-job-state-label=\"In Progress\""));
    assertTrue(xml.contains("data-extended-time=\"true\""));
    assertTrue(xml.contains("data-extended-total-seconds=\"600\""));
    assertTrue(xml.contains("data-extended-active=\"true\""));
    assertFalse(xml.contains("data-timer-head=\"true\""));
    assertFalse(xml.contains("data-timer-leading-shadow=\"true\""));
    assertFalse(xml.contains("data-timer-extended-progress=\"true\""));
    assertTrue(xml.contains("stroke-dasharray=\"100 100\""));
    assertTrue(xml.contains("data-visible=\"true\""));
    assertTrue(xml.contains("Exit Octane and Continue"));
    assertFalse(xml.contains("Extended time is active"));
    assertFalse(xml.contains("The latest Octane data will still be checked before continuing"));
  }

  @Test
  public void manualExitFreezesAtFirstRequestAndRendersPendingFinalization() throws Exception {
    FreeStyleProject project = jenkins.createFreeStyleProject();
    FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
    GateRequest request = new GateRequest("octane-prod", "4501");
    request.setTimeoutMinutesExtended(10);
    OctaneGateReportAction action = OctaneGateReportAction.attachTo(build, request);
    action.onExtendedTime(result(), classifier());
    AtomicInteger callbacks = new AtomicInteger();
    action.setManualExitCallback(callbacks::incrementAndGet);
    long beforeRequest = System.currentTimeMillis();

    action.doExitOctaneAndContinue();

    long requestedAt = action.getManualExitRequestedAtMillis();
    assertTrue(action.isManualExitRequested());
    assertTrue(action.isManualExitPending());
    assertFalse(action.isExtendedExitAvailable());
    assertTrue(requestedAt >= beforeRequest);
    assertTrue(requestedAt <= System.currentTimeMillis());
    assertEquals(1, callbacks.get());

    action.doExitOctaneAndContinue();
    assertEquals(requestedAt, action.getManualExitRequestedAtMillis());
    assertEquals(1, callbacks.get());

    HtmlPage page = jenkins.createWebClient().getPage(build, OctaneGateReportAction.URL_NAME);
    HtmlElement control = page.getFirstByXPath("//*[@data-exit-extended-form='true']");
    HtmlElement command = page.getFirstByXPath("//*[@data-exit-extended-command='true']");
    HtmlElement status = page.getFirstByXPath("//*[@data-exit-finalizing-status='true']");
    HtmlElement dashboard = page.getHtmlElementById("octane-dashboard");
    assertEquals("true", control.getAttribute("data-visible"));
    assertEquals("false", command.getAttribute("data-visible"));
    assertEquals("true", status.getAttribute("data-visible"));
    assertTrue(status.asNormalizedText().contains("Finalizing..."));
    assertEquals(
        String.valueOf(requestedAt),
        dashboard.getAttribute("data-manual-exit-requested-at-millis"));

    Page jsonPage =
        jenkins
            .createWebClient()
            .getPage(
                jenkins
                    .getURL()
                    .toURI()
                    .resolve(build.getUrl() + OctaneGateReportAction.URL_NAME + "/snapshot")
                    .toURL());
    JSONObject payload = JSONObject.fromObject(jsonPage.getWebResponse().getContentAsString());
    assertTrue(payload.getBoolean("manualExitRequested"));
    assertEquals(requestedAt, payload.getLong("manualExitRequestedAtMillis"));
  }

  private void assertBarPopupInteractions(HtmlPage page) {
    HtmlElement firstBar =
        page.getFirstByXPath(
            "//div[contains(concat(' ', normalize-space(@class), ' '),"
                + " ' octane-vertical-bar ')]");
    HtmlElement overlay = page.getHtmlElementById("octane-bar-popup-overlay");
    HtmlElement reportZone = page.getHtmlElementById("octane-report-zone");
    HtmlElement card =
        firstBar.getFirstByXPath(
            "ancestor::section[contains(concat(' ', normalize-space(@class), ' '),"
                + " ' octane-chart-card ')][1]");

    assertPopupHidesForEveryExitDirection(firstBar, overlay, "normal");

    reportZone.setAttribute("class", reportZone.getAttribute("class") + " octane-zone-focused");
    assertPopupHidesForEveryExitDirection(firstBar, overlay, "group-focused");
    reportZone.setAttribute(
        "class", reportZone.getAttribute("class").replace(" octane-zone-focused", ""));

    card.setAttribute("class", card.getAttribute("class") + " octane-expanded");
    assertPopupHidesForEveryExitDirection(firstBar, overlay, "individual-focused");
    card.setAttribute("class", card.getAttribute("class").replace(" octane-expanded", ""));

    page.executeJavaScript(
        "var first = document.querySelector('.octane-suite-column');"
            + " var adjacent = first.cloneNode(true);"
            + " adjacent.setAttribute('data-bar-key', 'adjacent-bar');"
            + " adjacent.querySelector('.octane-bar-popup-name').textContent = 'Adjacent Tester';"
            + " first.parentNode.appendChild(adjacent);");
    List<HtmlElement> bars =
        page.getByXPath(
            "//div[contains(concat(' ', normalize-space(@class), ' '),"
                + " ' octane-vertical-bar ')]");
    bars.get(0).mouseMove();
    String firstPopup = overlay.asNormalizedText();
    bars.get(1).mouseMove();
    assertTrue(overlay.asNormalizedText().contains("Adjacent Tester"));
    assertFalse(overlay.asNormalizedText().equals(firstPopup));
    bars.get(1).mouseOut();
    assertEquals("true", overlay.getAttribute("aria-hidden"));
  }

  private void assertPopupHidesForEveryExitDirection(
      HtmlElement bar, HtmlElement overlay, String mode) {
    for (String direction : List.of("left", "right", "top", "bottom")) {
      bar.mouseMove();
      assertEquals(mode + " " + direction, "false", overlay.getAttribute("aria-hidden"));
      assertTrue(overlay.getAttribute("class").contains("octane-bar-popup-visible"));
      bar.mouseOut();
      assertEquals(mode + " " + direction, "true", overlay.getAttribute("aria-hidden"));
      assertFalse(overlay.getAttribute("class").contains("octane-bar-popup-visible"));
    }
  }

  private GateResult result() {
    return result(Instant.parse("2026-05-15T00:00:00Z"));
  }

  private static String cssRule(String html, String selector) {
    Matcher matcher =
        Pattern.compile("(?m)^\\s*" + Pattern.quote(selector) + "\\s*\\{(?<declarations>[^}]*)}")
            .matcher(html);
    assertTrue("Missing CSS rule for " + selector, matcher.find());
    return matcher.group("declarations");
  }

  private GateResult result(Instant polledAt) {
    return new GateResult(
        "4501",
        "100% pass",
        true,
        true,
        new GateMetrics(2, 2, 1, 1, 0, 0),
        List.of(
            new RunRecord("1", "one", "passed", "Ada Tester"),
            new RunRecord("2", "two", "failed", "Ada Tester")),
        Map.of(
            "4501",
            List.of(
                new RunRecord("1", "one", "passed", "Ada Tester"),
                new RunRecord("2", "two", "failed", "Ada Tester"))),
        Map.of(),
        polledAt);
  }

  private GateResult result(Instant polledAt, OctaneRiskHeatMap riskHeatMap) {
    GateResult base = result(polledAt);
    return new GateResult(
        base.getSuiteRunId(),
        base.getCriteria(),
        base.isPassed(),
        base.isTerminal(),
        base.getMetrics(),
        base.getRuns(),
        base.getSuiteRuns(),
        base.getScopedResults(),
        riskHeatMap,
        polledAt);
  }

  private GateResult attributionResult(Map<String, List<RunRecord>> suiteRuns) {
    Map<String, List<RunRecord>> orderedSuiteRuns = new LinkedHashMap<>();
    suiteRuns.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(entry -> orderedSuiteRuns.put(entry.getKey(), entry.getValue()));
    List<RunRecord> runs =
        orderedSuiteRuns.values().stream()
            .flatMap((List<RunRecord> suiteRunRecords) -> suiteRunRecords.stream())
            .toList();
    return new GateResult(
        String.join(",", orderedSuiteRuns.keySet()),
        "regressions.executionRate >= 0",
        false,
        false,
        GateMetrics.fromRuns(runs, classifier()),
        runs,
        orderedSuiteRuns,
        Map.of(),
        Instant.parse("2026-08-11T12:00:00Z"));
  }

  private OctaneRiskHeatMap populatedHeatMap(int riskScore) {
    OctaneRiskHeatMapNode child =
        new OctaneRiskHeatMapNode("test", "Checkout test", riskScore, 1, 1, List.of());
    OctaneRiskHeatMapNode root =
        new OctaneRiskHeatMapNode("root", "Risk Heat Map", riskScore, 1, 1, List.of(child));
    return OctaneRiskHeatMap.of(root, 1, 1, 0, 0, OctaneDefectSeveritySummary.empty());
  }

  private StatusClassifier classifier() {
    return new StatusClassifier(
        StatusClassifier.DEFAULT_PASSED_STATUSES,
        StatusClassifier.DEFAULT_FAILED_STATUSES,
        StatusClassifier.DEFAULT_NEUTRAL_STATUSES,
        StatusClassifier.DEFAULT_RUNNING_STATUSES);
  }
}
