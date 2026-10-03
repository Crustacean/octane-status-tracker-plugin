package io.jenkins.plugins.octanesuitegatebyembiti.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Item;
import hudson.model.Run;
import hudson.security.csrf.DefaultCrumbIssuer;
import io.jenkins.plugins.octanesuitegatebyembiti.actions.OctaneGateReportAction;
import io.jenkins.plugins.octanesuitegatebyembiti.controllers.OctaneSuiteGateStep;
import io.jenkins.plugins.octanesuitegatebyembiti.models.GateRequest;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneGateStatusBucket;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneTestMetricCard;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneTestMetricSegment;
import io.jenkins.plugins.octanesuitegatebyembiti.services.OctaneScaleTestFixture;
import io.jenkins.plugins.octanesuitegatebyembiti.utils.OctaneReportJson;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class HostingSecurityReviewTest {
  private JenkinsRule jenkins;
  private static final String PACKAGE = "io.jenkins.plugins.octanesuitegatebyembiti.";

  @BeforeEach
  void setUp(JenkinsRule rule) {
    jenkins = rule;
    jenkins.jenkins.setSecurityRealm(jenkins.createDummySecurityRealm());
    jenkins.jenkins.setAuthorizationStrategy(
        new MockAuthorizationStrategy()
            .grant(Jenkins.READ)
            .everywhere()
            .to("reader", "operator", "outsider")
            .grant(Item.READ)
            .everywhere()
            .to("reader", "operator")
            .grant(Run.UPDATE)
            .everywhere()
            .to("operator"));
    jenkins.jenkins.setCrumbIssuer(new DefaultCrumbIssuer());
  }

  @Test
  void localValidatorsAndFixedOptionsRemainAccessibleWithoutConfigurePermission() throws Exception {
    try (var client = client("outsider")) {
      for (String descriptor : List.of("OctaneSuiteGateStep", "OctaneSuiteGateBuilder")) {
        for (String[] entry :
            new String[][] {
              {"SuiteRunId", "1196,1200"},
              {"SharedSpaceId", "1001"},
              {"WorkspaceId", "1002"},
              {"Criteria", "total.completionRate >= 100"},
              {"PollIntervalSeconds", "1"},
              {"TimeoutMinutes", "5"},
              {"TimeoutMinutesExtended", ""},
              {"BasePassrateFigure", "70"},
              {"BaseExecutionFigure", "100"},
              {"RiskHeatMapMaxDefects", "100"}
            }) {
          assertValid(client, "controllers." + descriptor, entry[0], entry[1]);
        }
      }
      for (String[] entry :
          new String[][] {
            {"ServerId", "example"},
            {"BaseUrl", "https://no-network.invalid/octane"},
            {"CredentialsId", "nonexistent-credential"}
          }) {
        assertValid(client, "controllers.OctaneSuiteGateStep", entry[0], entry[1]);
      }
      for (String[] entry :
          new String[][] {
            {"SpacesMappingFile", "nonexistent/mapping.json"},
            {"SharedSpaceName", "Unknown space"},
            {"WorkspaceName", "Unknown workspace"}
          }) {
        assertValid(client, "controllers.OctaneSuiteGateBuilder", entry[0], entry[1]);
      }
      for (String field : List.of("To", "Cc", "Bcc", "From", "ReplyTo")) {
        assertValid(client, "controllers.OctaneEmailReportStep", field, "tester@example.invalid");
      }
      for (String[] entry :
          new String[][] {{"OnFailure", "WARN"}, {"Theme", "DARK"}, {"ViewportWidth", "1200"}}) {
        assertValid(client, "controllers.OctaneEmailReportStep", entry[0], entry[1]);
      }
      for (String[] entry : new String[][] {{"OnFailure", "UNSTABLE"}, {"Theme", "SYSTEM"}}) {
        var options =
            descriptorGet(
                client, "controllers.OctaneEmailReportStep", "fill" + entry[0] + "Items", "");
        assertEquals(200, options.getStatusCode());
        assertTrue(options.getContentAsString().contains(entry[1]));
      }
      assertValid(client, "models.OctaneGateScope", "Name", "regressions");
      assertValid(client, "models.OctaneGateScope", "SuiteRunId", "1196");
      assertValid(client, "models.OctaneGateScope", "Query", "id EQ 1196");
      assertValid(client, "models.OctaneDefectGroup", "Name", "major");
      var types =
          descriptorGet(
              client, "models.OctaneDefectGroup", "checkTypes", "value=Critical%2CHigh&name=major");
      assertEquals(200, types.getStatusCode());
      assertFalse(types.getContentAsString().contains("class=error"));

      var invalidCriteria =
          descriptorGet(
              client,
              "controllers.OctaneSuiteGateStep",
              "checkCriteria",
              "value=" + "(".repeat(100) + "true" + ")".repeat(100));
      assertEquals(200, invalidCriteria.getStatusCode());
      assertTrue(invalidCriteria.getContentAsString().contains("error"));
      var invalidPath =
          descriptorGet(
              client,
              "controllers.OctaneSuiteGateBuilder",
              "checkSpacesMappingFile",
              "value=../config.xml");
      assertEquals(200, invalidPath.getStatusCode());
      assertTrue(invalidPath.getContentAsString().contains("workspace-relative"));
    }
  }

  @Test
  void dashboardGetsRequireJobReadAndNeverTriggerPollingOrPersistChanges() throws Exception {
    var build = jenkins.buildAndAssertSuccess(jenkins.createFreeStyleProject());
    var action = OctaneGateReportAction.attachTo(build, new GateRequest("octane", "1196"));
    action.onPoll(OctaneScaleTestFixture.result(0, 1, 1), OctaneScaleTestFixture.classifier());
    var polls = new AtomicInteger();
    action.setRefreshCallback(
        () -> {
          polls.incrementAndGet();
          return true;
        });
    var config = build.getRootDir().toPath().resolve("build.xml");
    String before = Files.readString(config);
    String base = build.getUrl() + OctaneGateReportAction.URL_NAME + "/";
    try (var reader = client("reader");
        var outsider = client("outsider");
        var anonymous = client(null)) {
      for (String endpoint :
          List.of("snapshot", "data", "scaleReportScript", "testManagementScript")) {
        var response = request(reader, base + endpoint, HttpMethod.GET, false);
        assertEquals(200, response.getStatusCode(), endpoint);
        assertEquals("nosniff", response.getResponseHeaderValue("X-Content-Type-Options"));
        assertFalse(response.getContentAsString().isBlank());
        if (endpoint.endsWith("Script")) {
          assertTrue(response.getContentType().contains("javascript"));
        } else {
          assertTrue(
              OctaneReportJson.readObject(
                      response.getContentAsString().getBytes(StandardCharsets.UTF_8))
                  .isObject());
        }
        for (var denied : List.of(outsider, anonymous)) {
          int status = request(denied, base + endpoint, HttpMethod.GET, false).getStatusCode();
          assertTrue(status == 403 || status == 404, endpoint + " returned " + status);
        }
      }
    }
    assertEquals(0, polls.get());
    assertFalse(action.isManualExitRequested());
    assertEquals(before, Files.readString(config));
  }

  @Test
  void manualExitRequiresPostCrumbAndRunUpdatePermission() throws Exception {
    var build = jenkins.buildAndAssertSuccess(jenkins.createFreeStyleProject());
    var gate = new GateRequest("octane", "1196");
    gate.setTimeoutMinutesExtended(3);
    var action = OctaneGateReportAction.attachTo(build, gate);
    action.onExtendedTime(
        OctaneScaleTestFixture.result(0, 1, 1), OctaneScaleTestFixture.classifier());
    var exits = new AtomicInteger();
    action.setManualExitCallback(exits::incrementAndGet);
    String path = build.getUrl() + OctaneGateReportAction.URL_NAME + "/exitOctaneAndContinue";
    try (var reader = client("reader");
        var operator = client("operator")) {
      assertEquals(405, request(operator, path, HttpMethod.GET, false).getStatusCode());
      assertEquals(403, request(operator, path, HttpMethod.POST, false).getStatusCode());
      assertEquals(403, request(reader, path, HttpMethod.POST, true).getStatusCode());
      assertFalse(action.isManualExitRequested());
      assertEquals(0, exits.get());
      assertEquals(302, request(operator, path, HttpMethod.POST, true).getStatusCode());
      assertTrue(action.isManualExitRequested());
      assertEquals(1, exits.get());
    }
  }

  @Test
  void nonSecretStatusAndPresentationKeysKeepTheirSerializationContract() {
    var gate = new GateRequest("octane", "1196");
    gate.setPassedStatuses("Passed,Successful");
    var restored = (GateRequest) Jenkins.XSTREAM2.fromXML(Jenkins.XSTREAM2.toXML(gate));
    assertEquals("Passed,Successful", restored.getPassedStatuses());
    var step = new OctaneSuiteGateStep("octane", "1196");
    step.setPassedStatuses("Passed,Successful");
    var restoredStep = (OctaneSuiteGateStep) Jenkins.XSTREAM2.fromXML(Jenkins.XSTREAM2.toXML(step));
    assertEquals("Passed,Successful", restoredStep.getPassedStatuses());
    var segment = new OctaneTestMetricSegment("High", "High", 1, 100, "high", 1);
    var card =
        new OctaneTestMetricCard(
            "open-defects", "Open defects", "1", "", "", "", "", 100, "", List.of(segment));
    assertEquals("open-defects", card.toMap().get("key"));
    assertEquals("high", segment.toMap().get("severityKey"));
    assertEquals("passed", OctaneGateStatusBucket.PASSED.getDataKey());
  }

  private JenkinsRule.WebClient client(String user) throws Exception {
    var client = jenkins.createWebClient().withJavaScriptEnabled(false);
    if (user != null) {
      client.login(user);
    }
    return client.withRedirectEnabled(false).withThrowExceptionOnFailingStatusCode(false);
  }

  private WebResponse request(
      JenkinsRule.WebClient client, String path, HttpMethod method, boolean crumb)
      throws Exception {
    var request = new WebRequest(jenkins.getURL().toURI().resolve(path).toURL(), method);
    if (crumb) {
      // DefaultCrumbIssuer binds crumbs to this user's HTTP session, not the test thread.
      var response = request(client, "crumbIssuer/api/json", HttpMethod.GET, false);
      assertEquals(200, response.getStatusCode());
      var token =
          OctaneReportJson.readObject(
              response.getContentAsString().getBytes(StandardCharsets.UTF_8));
      assertFalse(token.path("crumb").asString().isBlank());
      request.setAdditionalHeader(
          token.path("crumbRequestField").asString(), token.path("crumb").asString());
    }
    return client.loadWebResponse(request);
  }

  private WebResponse descriptorGet(
      JenkinsRule.WebClient client, String descriptor, String method, String query)
      throws Exception {
    return request(
        client,
        "descriptorByName/" + PACKAGE + descriptor + "/" + method + "?" + query,
        HttpMethod.GET,
        false);
  }

  private void assertValid(
      JenkinsRule.WebClient client, String descriptor, String field, String value)
      throws Exception {
    var response =
        descriptorGet(
            client,
            descriptor,
            "check" + field,
            "value=" + URLEncoder.encode(value, StandardCharsets.UTF_8));
    assertEquals(200, response.getStatusCode(), descriptor + "." + field);
    assertFalse(response.getContentAsString().contains("error"), response.getContentAsString());
    assertNotNull(response.getContentType());
  }
}
