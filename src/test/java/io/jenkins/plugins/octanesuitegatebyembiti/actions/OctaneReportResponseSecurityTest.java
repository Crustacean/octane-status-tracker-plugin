package io.jenkins.plugins.octanesuitegatebyembiti.actions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;

public class OctaneReportResponseSecurityTest {
  @Test
  public void setsJsonIsolationAndHstsOnlyForSecureRequests() {
    for (boolean secure : new boolean[] {true, false}) {
      Map<String, String> headers = new HashMap<>();
      StaplerRequest2 request =
          (StaplerRequest2)
              Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {StaplerRequest2.class},
                  (proxy, method, arguments) -> {
                    if (method.getName().equals("isSecure")) {
                      return secure;
                    }
                    // A caller-provided forwarding header must not bypass the container's TLS
                    // decision.
                    if (method.getName().equals("getHeader")) {
                      return "https";
                    }
                    throw new AssertionError(method.getName());
                  });
      StaplerResponse2 response =
          (StaplerResponse2)
              Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {StaplerResponse2.class},
                  (proxy, method, arguments) -> {
                    if (method.getName().equals("setHeader")) {
                      headers.put((String) arguments[0], (String) arguments[1]);
                    } else if (method.getName().equals("setContentType")) {
                      headers.put("Content-Type", (String) arguments[0]);
                    } else {
                      throw new AssertionError(method.getName());
                    }
                    return null;
                  });

      OctaneGateReportAction.setJsonSecurityHeaders(request, response);

      assertEquals("application/json;charset=UTF-8", headers.get("Content-Type"));
      assertEquals("nosniff", headers.get("X-Content-Type-Options"));
      assertEquals(
          "default-src 'none'; frame-ancestors 'none'; sandbox",
          headers.get("Content-Security-Policy"));
      if (secure) {
        assertEquals(
            "max-age=31536000; includeSubDomains", headers.get("Strict-Transport-Security"));
      } else {
        assertFalse(headers.containsKey("Strict-Transport-Security"));
      }
    }
  }
}
