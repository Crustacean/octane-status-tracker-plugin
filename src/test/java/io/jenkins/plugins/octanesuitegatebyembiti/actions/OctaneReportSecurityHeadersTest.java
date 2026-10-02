package io.jenkins.plugins.octanesuitegatebyembiti.actions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class OctaneReportSecurityHeadersTest {
  @Test
  public void coversEveryReportRouteWithoutChangingDispatchOrTrustingForwardingHeaders() {
    for (String path :
        List.of(
            "/adjuncts/abc/css/octane-dashboard.css",
            "/adjuncts/abc/js/octane-dashboard.js",
            "/job/demo/1/octaneSuiteGateReport",
            "/job/demo/1/octaneSuiteGateReport/",
            "/job/demo/1/octaneSuiteGateReport/data",
            "/job/demo/1/octaneSuiteGateReport/snapshot",
            "/job/demo/1/octaneSuiteGateReport/scaleReportScript",
            "/job/demo/1/octaneSuiteGateReport/testManagementScript",
            "/job/demo/1/octaneSuiteGateReport/exitOctaneAndContinue",
            "/job/demo/1/octaneSuiteGateReport/missing",
            "/plugin/octane-status-tracker/js/octane-scale-report.js",
            "/static/abc/plugin/octane-status-tracker/js/octane-scale-report.js")) {
      for (boolean secure : new boolean[] {true, false}) {
        Map<String, String> headers = filter(path, secure);
        assertEquals("nosniff", headers.get("X-Content-Type-Options"), path);
        assertEquals(
            secure ? "max-age=31536000; includeSubDomains" : null,
            headers.get("Strict-Transport-Security"),
            path);
        assertNull(headers.get("Content-Type"));
        assertNull(headers.get("Content-Security-Policy"));
      }
    }
  }

  @Test
  public void doesNotChangeOtherPluginsOrJenkinsPages() {
    for (String path :
        List.of(
            "/",
            "/login",
            "/job/demo/",
            "/octaneSuiteGateReportOther/data",
            "/adjuncts/abc/js/other.js",
            "/adjuncts/abc/js/octane-dashboard.js/other",
            "/plugin/other-plugin/js/script.js",
            "/plugin/octane-status-tracker-other/js/script.js",
            "/static/abc/plugin/other-plugin/js/script.js")) {
      assertEquals(Map.of(), filter(path, true));
    }
    assertEquals(Map.of(), filter(null, true));
  }

  private Map<String, String> filter(String path, boolean secure) {
    Map<String, String> headers = new HashMap<>();
    HttpServletRequest request =
        (HttpServletRequest)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {HttpServletRequest.class},
                (proxy, method, arguments) ->
                    switch (method.getName()) {
                      case "getPathInfo" -> path;
                      case "isSecure" -> secure;
                      case "getHeader" -> "https";
                      default -> throw new AssertionError(method.getName());
                    });
    HttpServletResponse response =
        (HttpServletResponse)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {HttpServletResponse.class},
                (proxy, method, arguments) -> {
                  if (!method.getName().equals("setHeader")) {
                    throw new AssertionError(method.getName());
                  }
                  headers.put((String) arguments[0], (String) arguments[1]);
                  return null;
                });
    assertFalse(new OctaneReportSecurityHeaders().handle(request, response));
    return headers;
  }
}
