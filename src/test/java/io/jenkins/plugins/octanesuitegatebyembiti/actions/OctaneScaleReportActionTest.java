package io.jenkins.plugins.octanesuitegatebyembiti.actions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import io.jenkins.plugins.octanesuitegatebyembiti.entities.DefectRecord;
import io.jenkins.plugins.octanesuitegatebyembiti.entities.RunRecord;
import io.jenkins.plugins.octanesuitegatebyembiti.models.DefectCriteriaMetrics;
import io.jenkins.plugins.octanesuitegatebyembiti.models.GateMetrics;
import io.jenkins.plugins.octanesuitegatebyembiti.models.GateRequest;
import io.jenkins.plugins.octanesuitegatebyembiti.models.GateResult;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneDefectSeveritySummary;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneGateReportSnapshot;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneGateReportState;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneRiskHeatMap;
import io.jenkins.plugins.octanesuitegatebyembiti.models.StatusClassifier;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.html.HtmlPage;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

public class OctaneScaleReportActionTest {
  @Rule public JenkinsRule jenkins = new JenkinsRule();

  @Test
  public void denseReportUsesSmallMetadataEtagAndInlineStatusAggregation() throws Exception {
    assertTrue(
        jenkins.jenkins.getExtensionList(jenkins.util.HttpServletFilter.class).stream()
            .anyMatch(OctaneReportSecurityHeaders.class::isInstance));
    FreeStyleProject project = jenkins.createFreeStyleProject();
    FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
    GateRequest request = new GateRequest("octane-prod", "suite-0");
    OctaneGateReportAction action = OctaneGateReportAction.attachTo(build, request);
    int suiteCount = 701;
    action.onPoll(ScaleReportFixture.result(0, suiteCount, 1), ScaleReportFixture.classifier());

    long buildXmlBytes = Files.size(build.getRootDir().toPath().resolve("build.xml"));
    assertTrue(buildXmlBytes < 100_000L);
    assertFalse(
        Files.readString(build.getRootDir().toPath().resolve("build.xml")).contains("<snapshot"));
    assertNotNull(action.getReportDataChecksum());
    assertFalse(action.getReportDataChecksum().isBlank());

    URL reportUrl =
        jenkins
            .getURL()
            .toURI()
            .resolve(build.getUrl() + OctaneGateReportAction.URL_NAME + "/")
            .toURL();
    HtmlPage report = jenkins.createWebClient().getPage(reportUrl);
    String reportXml = report.asXml();
    assertTrue(reportXml.contains("data-client-rendered=\"false\""));
    assertFalse(reportXml.contains("Loading report data"));
    assertTrue(reportXml.contains("data-x-axis=\"Status\""));
    assertTrue(reportXml.contains("data-tooltips-enabled=\"false\""));
    long domNodes = Pattern.compile("<[A-Za-z][^!?/]*?").matcher(reportXml).results().count();
    assertTrue("initial DOM should remain below 5,000 nodes", domNodes < 5_000L);

    URI reportUri = reportUrl.toURI();
    URL snapshotUrl = reportUri.resolve("snapshot").toURL();
    Page snapshot = jenkins.createWebClient().getPage(snapshotUrl);
    assertJsonSecurityHeaders(snapshot);
    String snapshotBody = snapshot.getWebResponse().getContentAsString();
    assertFalse(snapshotBody.contains("<"));
    assertFalse(snapshotBody.contains(">"));
    JsonNode decodedSnapshot = new ObjectMapper().readTree(snapshotBody);
    assertTrue(decodedSnapshot.path("reportZoneHtml").asText().contains("<section"));
    String snapshotEtag = snapshot.getWebResponse().getResponseHeaderValue("ETag");
    assertNotNull(snapshotEtag);
    assertTrue(snapshot.getWebResponse().getContentLength() < 250_000L);

    WebRequest unchangedRequest = new WebRequest(snapshotUrl);
    unchangedRequest.setAdditionalHeader("If-None-Match", snapshotEtag);
    Page unchanged = jenkins.createWebClient().getPage(unchangedRequest);
    assertEquals(304, unchanged.getWebResponse().getStatusCode());
    assertJsonSecurityHeaders(unchanged);

    URL dataUrl = reportUri.resolve("data").toURL();
    Page index = jenkins.createWebClient().getPage(dataUrl);
    assertJsonSecurityHeaders(index);
    long indexBytes = index.getWebResponse().getContentLength();
    assertTrue(indexBytes < 250_000L);
    JsonNode indexData = new ObjectMapper().readTree(index.getWebResponse().getContentAsString());
    JsonNode firstSection = indexData.path("sections").path(0);
    assertEquals(1, firstSection.path("barCount").asInt());
    assertEquals("Status", firstSection.path("xAxis").asText());
    String dataEtag = index.getWebResponse().getResponseHeaderValue("ETag");
    assertNotNull(dataEtag);
    WebRequest unchangedDataRequest = new WebRequest(dataUrl);
    unchangedDataRequest.setAdditionalHeader("If-None-Match", dataEtag);
    Page unchangedData = jenkins.createWebClient().getPage(unchangedDataRequest);
    assertEquals(304, unchangedData.getWebResponse().getStatusCode());
    assertJsonSecurityHeaders(unchangedData);

    for (String scriptName : List.of("scaleReportScript", "testManagementScript")) {
      Page script = jenkins.createWebClient().getPage(reportUri.resolve(scriptName).toURL());
      assertEquals(
          "nosniff", script.getWebResponse().getResponseHeaderValue("X-Content-Type-Options"));
      assertTrue(script.getWebResponse().getContentType().contains("javascript"));
      assertFalse(
          String.valueOf(script.getWebResponse().getResponseHeaderValue("Content-Security-Policy"))
              .contains("default-src 'none'"));
    }

    URL sectionUrl = reportUri.resolve("data?section=0&cursor=0&limit=80").toURL();
    Page section = jenkins.createWebClient().getPage(sectionUrl);
    assertJsonSecurityHeaders(section);
    JsonNode sectionData =
        new ObjectMapper().readTree(section.getWebResponse().getContentAsString());
    assertEquals(1, sectionData.path("totalBars").asInt());
    assertEquals(-1, sectionData.path("nextCursor").asInt());

    int cursor = 0;
    int pages = 0;
    while (cursor >= 0) {
      URL pageUrl = reportUri.resolve("data?section=0&cursor=" + cursor + "&limit=80").toURL();
      JsonNode pageData =
          new ObjectMapper()
              .readTree(
                  jenkins.createWebClient().getPage(pageUrl).getWebResponse().getContentAsString());
      assertEquals(cursor, pageData.path("cursor").asInt());
      cursor = pageData.path("nextCursor").asInt();
      pages++;
    }
    assertEquals(1, pages);
    System.out.printf(
        "Octane report acceptance: buildXmlBytes=%d initialDomNodes=%d indexBytes=%d%n",
        buildXmlBytes, domNodes, indexBytes);
  }

  @Test
  public void persistedAttackStringsStayEncodedAtTheHttpResponseBoundary() throws Exception {
    FreeStyleBuild build = jenkins.buildAndAssertSuccess(jenkins.createFreeStyleProject());
    OctaneGateReportAction action =
        OctaneGateReportAction.attachTo(build, new GateRequest("octane-prod", "suite-0"));
    action.onPoll(ScaleReportFixture.result(0, 1, 1), ScaleReportFixture.classifier());
    var directory =
        build
            .getRootDir()
            .toPath()
            .resolve("octane-suite-gate")
            .resolve(action.getReportDataChecksum());
    String attack = "</script><img src=x onerror=alert('stored')>&\u00e9\uD83D\uDE00";
    String artifact =
        new ObjectMapper()
            .writeValueAsString(Map.of("name", attack, "bars", List.of(Map.of("name", attack))));
    Files.writeString(directory.resolve("octane-index.json"), artifact);
    Files.writeString(directory.resolve("section-0.json"), artifact);
    URI report =
        jenkins.getURL().toURI().resolve(build.getUrl() + OctaneGateReportAction.URL_NAME + "/");
    for (String resource : List.of("data", "data?section=0&cursor=0&limit=80")) {
      Page page = jenkins.createWebClient().getPage(report.resolve(resource).toURL());
      assertJsonSecurityHeaders(page);
      assertEquals("application/json", page.getWebResponse().getContentType());
      String body = page.getWebResponse().getContentAsString();
      assertFalse(body.contains("<"));
      assertFalse(body.contains(">"));
      assertFalse(body.contains("&"));
      assertEquals(attack, new ObjectMapper().readTree(body).path("name").asText());
    }
  }

  @Test
  public void readsLegacyInlineSnapshotWhenArtifactMetadataIsAbsent() throws Exception {
    FreeStyleProject project = jenkins.createFreeStyleProject();
    FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
    OctaneGateReportAction action = new OctaneGateReportAction();
    Field legacySnapshot = OctaneGateReportAction.class.getDeclaredField("snapshot");
    legacySnapshot.setAccessible(true);
    legacySnapshot.set(action, ScaleReportFixture.snapshot(0, 1, 1));
    Field snapshotCache = OctaneGateReportAction.class.getDeclaredField("snapshotCache");
    snapshotCache.setAccessible(true);
    snapshotCache.set(action, null);
    action.onAttached(build);

    assertEquals("2026-07-16T12:00:00Z", action.getSnapshot().getUpdatedAt());
  }

  private static void assertJsonSecurityHeaders(Page page) {
    assertEquals("nosniff", page.getWebResponse().getResponseHeaderValue("X-Content-Type-Options"));
    assertEquals(
        "default-src 'none'; frame-ancestors 'none'; sandbox",
        page.getWebResponse().getResponseHeaderValue("Content-Security-Policy"));
  }

  private static final class ScaleReportFixture {
    private static final List<String> STATUSES =
        List.of("passed", "failed", "blocked", "skipped", "planned");
    private static final List<String> SEVERITIES =
        List.of("critical", "very high", "high", "medium", "low", "unspecified");

    private ScaleReportFixture() {}

    private static OctaneGateReportSnapshot snapshot(
        int job, int suiteCount, int childRunsPerSuite) {
      return snapshot(result(job, suiteCount, childRunsPerSuite));
    }

    private static OctaneGateReportSnapshot snapshot(GateResult result) {
      return OctaneGateReportSnapshot.fromResult(
          OctaneGateReportState.POLLING,
          "Scale test polling.",
          result,
          classifier(),
          30,
          7200,
          1800,
          "2026-07-16T10:00:00Z");
    }

    private static GateResult result(int job, int suiteCount, int childRunsPerSuite) {
      Map<String, List<RunRecord>> suiteRuns = new LinkedHashMap<>();
      List<RunRecord> allRuns = new ArrayList<>(suiteCount * childRunsPerSuite);
      List<String> suiteIds = new ArrayList<>(suiteCount);
      for (int suite = 0; suite < suiteCount; suite++) {
        String suiteId = "job-" + job + "-suite-" + suite;
        suiteIds.add(suiteId);
        List<RunRecord> children = new ArrayList<>(childRunsPerSuite);
        for (int child = 0; child < childRunsPerSuite; child++) {
          String status = STATUSES.get(child % STATUSES.size());
          RunRecord run =
              new RunRecord(
                  suiteId + "-run-" + child,
                  "Run " + child,
                  status,
                  "tester-" + suite + "@example.test",
                  "test-" + suite + "-" + child,
                  "Test " + child,
                  "project-" + job,
                  "Scale project " + job);
          children.add(run);
          allRuns.add(run);
        }
        suiteRuns.put(suiteId, List.copyOf(children));
      }
      StatusClassifier classifier = classifier();
      GateMetrics metrics = GateMetrics.fromRuns(allRuns, classifier);
      List<DefectRecord> defects = new ArrayList<>(1000);
      for (int defect = 0; defect < 1000; defect++) {
        RunRecord linkedRun = allRuns.get(defect % allRuns.size());
        defects.add(
            new DefectRecord(
                "job-" + job + "-defect-" + defect,
                "Scale defect " + defect,
                SEVERITIES.get(defect % SEVERITIES.size()),
                "",
                defect % 5 == 0 ? "closed" : "opened",
                linkedRun.getId(),
                linkedRun.getTestId(),
                "project-" + job,
                "Scale project " + job));
      }
      DefectCriteriaMetrics defectMetrics =
          new DefectCriteriaMetrics(OctaneDefectSeveritySummary.fromDefects(defects), List.of());
      Instant polledAt = Instant.parse("2026-07-16T12:00:00Z").plusSeconds(job);
      return new GateResult(
          String.join(",", suiteIds),
          "executionRate >= 0",
          false,
          metrics.isTerminal(),
          metrics,
          allRuns,
          suiteRuns,
          Map.of(),
          OctaneRiskHeatMap.disabled(),
          defectMetrics,
          polledAt);
    }

    private static StatusClassifier classifier() {
      return new StatusClassifier(
          StatusClassifier.DEFAULT_PASSED_STATUSES,
          StatusClassifier.DEFAULT_FAILED_STATUSES,
          StatusClassifier.DEFAULT_NEUTRAL_STATUSES,
          StatusClassifier.DEFAULT_RUNNING_STATUSES);
    }
  }
}
