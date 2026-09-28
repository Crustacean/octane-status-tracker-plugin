package io.jenkins.plugins.octanesuitegatebyembiti.actions;

import hudson.Extension;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jenkins.util.HttpServletFilter;

/** Covers report views, scripts, redirects and errors before Stapler dispatch. */
@Extension
public final class OctaneReportSecurityHeaders implements HttpServletFilter {
  static final String HSTS_POLICY = "max-age=31536000; includeSubDomains";
  private static final String PLUGIN_PATH = "/plugin/octane-status-tracker/";

  @Override
  public boolean handle(HttpServletRequest request, HttpServletResponse response) {
    String path = request.getPathInfo();
    if (path != null
        && (path.contains("/" + OctaneGateReportAction.URL_NAME + "/")
            || path.endsWith("/" + OctaneGateReportAction.URL_NAME)
            || path.startsWith(PLUGIN_PATH)
            || (path.startsWith("/static/") && path.contains(PLUGIN_PATH)))) {
      apply(request, response);
    }
    return false;
  }

  static void apply(HttpServletRequest request, HttpServletResponse response) {
    response.setHeader("X-Content-Type-Options", "nosniff");
    // Only the container's trusted TLS decision is authoritative, not arbitrary request headers.
    if (request != null && request.isSecure()) {
      response.setHeader("Strict-Transport-Security", HSTS_POLICY);
    }
  }
}
