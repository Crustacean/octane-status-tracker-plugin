package io.jenkins.plugins.octanesuitegatebyembiti.actions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import hudson.security.ACL;
import io.jenkins.plugins.octanesuitegatebyembiti.models.GateRequest;
import io.jenkins.plugins.octanesuitegatebyembiti.services.OctaneScaleTestFixture;
import io.jenkins.plugins.octanesuitegatebyembiti.utils.OctaneReportJson;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jenkins.model.Jenkins;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;

public class OctaneReportEndpointSecurityTest {
  @Rule public JenkinsRule jenkins = new JenkinsRule();

  @Test
  public void staplerEndpointsProtectJsonScriptsErrorsAndConditionalResponses() throws Exception {
    var build = jenkins.buildAndAssertSuccess(jenkins.createFreeStyleProject());
    var legacy = new OctaneGateReportAction();
    legacy.onAttached(build);
    for (boolean secure : new boolean[] {true, false}) {
      var missing = new Response();
      legacy.doData(request(secure, null), missing.proxy, null, 0, 80);
      assertEquals(404, missing.status);
      assertHeaders(missing, secure, true);
    }
    var action = OctaneGateReportAction.attachTo(build, new GateRequest("octane", "suite-0"));
    action.onPoll(OctaneScaleTestFixture.result(0, 1, 1), OctaneScaleTestFixture.classifier());

    for (boolean secure : new boolean[] {true, false}) {
      for (String section : new String[] {null, "0", "invalid", "-1", "1"}) {
        var response = new Response();
        action.doData(request(secure, null), response.proxy, section, 0, 80);
        assertHeaders(response, secure, true);
        int expected =
            section == null || "0".equals(section) ? 200 : "invalid".equals(section) ? 400 : 404;
        assertEquals(expected, response.status);
        if (expected == 200) {
          assertTrue(OctaneReportJson.readObject(response.bytes()).isObject());
          var unchanged = new Response();
          assertNotNull(response.headers.get("ETag"));
          action.doData(
              request(secure, response.headers.get("ETag")), unchanged.proxy, section, 0, 80);
          assertEquals(304, unchanged.status);
          assertEquals(0, unchanged.bytes().length);
          assertHeaders(unchanged, secure, true);
        }
      }
      var snapshot = new Response();
      action.doSnapshot(request(secure, null), snapshot.proxy);
      assertHeaders(snapshot, secure, true);
      assertTrue(OctaneReportJson.readObject(snapshot.bytes()).isObject());
      var unchanged = new Response();
      action.doSnapshot(request(secure, snapshot.headers.get("ETag")), unchanged.proxy);
      assertEquals(304, unchanged.status);
      assertHeaders(unchanged, secure, true);

      var scaleScript = new Response();
      action.doScaleReportScript(request(secure, null), scaleScript.proxy);
      var managementScript = new Response();
      action.doTestManagementScript(request(secure, null), managementScript.proxy);
      for (var script : List.of(scaleScript, managementScript)) {
        assertHeaders(script, secure, false);
        assertEquals("text/javascript;charset=UTF-8", script.headers.get("Content-Type"));
        assertTrue(script.output.size() > 0);
      }
    }
  }

  @Test
  public void deniedReadsKeepSecurityHeadersWithoutLeakingDataOrScripts() throws Exception {
    var build = jenkins.buildAndAssertSuccess(jenkins.createFreeStyleProject());
    var action = OctaneGateReportAction.attachTo(build, new GateRequest("octane", "suite-0"));
    jenkins.jenkins.setSecurityRealm(jenkins.createDummySecurityRealm());
    jenkins.jenkins.setAuthorizationStrategy(
        new MockAuthorizationStrategy().grant(Jenkins.READ).everywhere().toEveryone());
    try (var context = ACL.as2(Jenkins.ANONYMOUS2)) {
      for (String endpoint :
          List.of("data", "snapshot", "scaleReportScript", "testManagementScript")) {
        var response = new Response();
        assertThrows(
            org.springframework.security.access.AccessDeniedException.class,
            () -> {
              switch (endpoint) {
                case "data" -> action.doData(request(true, null), response.proxy, null, 0, 80);
                case "snapshot" -> action.doSnapshot(request(true, null), response.proxy);
                case "scaleReportScript" ->
                    action.doScaleReportScript(request(true, null), response.proxy);
                case "testManagementScript" ->
                    action.doTestManagementScript(request(true, null), response.proxy);
                default -> throw new AssertionError(endpoint);
              }
            });
        assertEquals(0, response.bytes().length);
        assertEquals(0, response.output.size());
        assertHeaders(response, true, !endpoint.endsWith("Script"));
      }
    }
  }

  @Test
  public void rejectsMalformedStoredDataBeforeWritingAnyResponseBody() throws Exception {
    var build = jenkins.buildAndAssertSuccess(jenkins.createFreeStyleProject());
    var action = OctaneGateReportAction.attachTo(build, new GateRequest("octane", "suite-0"));
    action.onPoll(OctaneScaleTestFixture.result(0, 1, 1), OctaneScaleTestFixture.classifier());
    var directory =
        build
            .getRootDir()
            .toPath()
            .resolve("octane-suite-gate")
            .resolve(action.getReportDataChecksum());
    for (String section : new String[] {null, "0"}) {
      for (String invalid : List.of("<script>alert(1)</script>", "{} {}", "[]", "null")) {
        Files.writeString(
            directory.resolve(section == null ? "octane-index.json" : "section-0.json"), invalid);
        var response = new Response();
        assertThrows(
            IOException.class,
            () -> action.doData(request(true, null), response.proxy, section, 0, 80));
        assertEquals(0, response.bytes().length);
        assertHeaders(response, true, true);
      }
    }
  }

  private static void assertHeaders(Response response, boolean secure, boolean json) {
    assertEquals("nosniff", response.headers.get("X-Content-Type-Options"));
    assertEquals(
        secure ? OctaneReportSecurityHeaders.HSTS_POLICY : null,
        response.headers.get("Strict-Transport-Security"));
    if (json) {
      assertEquals("application/json;charset=UTF-8", response.headers.get("Content-Type"));
      assertEquals(
          "default-src 'none'; frame-ancestors 'none'; sandbox",
          response.headers.get("Content-Security-Policy"));
      assertFalse(new String(response.bytes(), StandardCharsets.UTF_8).contains("<"));
    } else {
      assertNull(response.headers.get("Content-Security-Policy"));
    }
  }

  private static StaplerRequest2 request(boolean secure, String etag) {
    return (StaplerRequest2)
        Proxy.newProxyInstance(
            StaplerRequest2.class.getClassLoader(),
            new Class<?>[] {StaplerRequest2.class},
            (proxy, method, arguments) ->
                switch (method.getName()) {
                  case "isSecure" -> secure;
                  case "getHeader" -> "If-None-Match".equals(arguments[0]) ? etag : "https";
                  default -> throw new AssertionError(method.getName());
                });
  }

  private static final class Response {
    private final Map<String, String> headers = new HashMap<>();
    private final StringWriter body = new StringWriter();
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private int status = 200;
    private final StaplerResponse2 proxy =
        (StaplerResponse2)
            Proxy.newProxyInstance(
                StaplerResponse2.class.getClassLoader(),
                new Class<?>[] {StaplerResponse2.class},
                (proxy, method, arguments) -> {
                  switch (method.getName()) {
                    case "setHeader" -> headers.put((String) arguments[0], (String) arguments[1]);
                    case "setContentType" -> headers.put("Content-Type", (String) arguments[0]);
                    case "setStatus", "sendError" -> status = (Integer) arguments[0];
                    case "getWriter" -> {
                      assertEquals("nosniff", headers.get("X-Content-Type-Options"));
                      return new PrintWriter(body);
                    }
                    case "getOutputStream" -> {
                      assertTrue(
                          "Only packaged JavaScript may bypass JSON serialization",
                          headers.get("Content-Type").startsWith("text/javascript"));
                      return new ServletOutputStream() {
                        @Override
                        public void write(int value) {
                          output.write(value);
                        }

                        @Override
                        public boolean isReady() {
                          return true;
                        }

                        @Override
                        public void setWriteListener(WriteListener listener) {
                          throw new AssertionError("Unexpected async write");
                        }
                      };
                    }
                    default -> throw new AssertionError(method.getName());
                  }
                  return null;
                });

    private byte[] bytes() {
      return body.toString().getBytes(StandardCharsets.UTF_8);
    }
  }
}
