package io.jenkins.plugins.octanesuitegatebyembiti.configs;

import io.jenkins.plugins.octanesuitegatebyembiti.utils.Util;
import java.net.URI;
import java.net.URISyntaxException;

/** Validates the mapped ALM Octane network boundary. */
public final class OctaneServerUrl {
  private OctaneServerUrl() {}

  public static String normalize(String value) {
    String candidate = Util.trimTrailingSlash(Util.trimToEmpty(value));
    if (candidate.isEmpty()) {
      throw new IllegalArgumentException("Base URL is required.");
    }
    URI uri = parse(candidate);
    validateScheme(uri);
    validateAuthority(uri);
    validateBaseComponents(uri);
    return candidate;
  }

  private static URI parse(String candidate) {
    try {
      return new URI(candidate);
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("Base URL is not a valid URI.", e);
    }
  }

  private static void validateScheme(URI uri) {
    String scheme = uri.getScheme();
    if (!"https".equalsIgnoreCase(scheme)) {
      throw new IllegalArgumentException(
          "Base URL must use https:// to protect Octane credentials.");
    }
  }

  private static void validateAuthority(URI uri) {
    if (Util.isBlank(uri.getHost())) {
      throw new IllegalArgumentException("Base URL must include a host.");
    }
    if (uri.getUserInfo() != null) {
      throw new IllegalArgumentException("Base URL must not contain embedded credentials.");
    }
  }

  private static void validateBaseComponents(URI uri) {
    if (uri.getQuery() != null || uri.getFragment() != null) {
      throw new IllegalArgumentException("Base URL must not contain a query or fragment.");
    }
  }

  public static URI requireAllowedRequest(String configuredBaseUrl, URI requestUri) {
    if (requestUri == null || !"https".equalsIgnoreCase(requestUri.getScheme())) {
      throw new IllegalArgumentException("ALM Octane requests must use HTTPS.");
    }
    URI baseUri = URI.create(normalize(configuredBaseUrl));
    if (!hasConfiguredOrigin(baseUri, requestUri)) {
      throw new IllegalArgumentException("ALM Octane request must use the mapped server origin.");
    }
    if (!hasConfiguredBasePath(baseUri, requestUri)) {
      throw new IllegalArgumentException(
          "ALM Octane request must stay under the mapped base path.");
    }
    if (hasForbiddenRequestComponents(requestUri)) {
      throw new IllegalArgumentException("ALM Octane request URI contains forbidden components.");
    }
    return requestUri;
  }

  private static boolean hasConfiguredOrigin(URI baseUri, URI requestUri) {
    return requestUri != null
        && baseUri.getScheme().equalsIgnoreCase(requestUri.getScheme())
        && baseUri.getHost().equalsIgnoreCase(requestUri.getHost())
        && effectivePort(baseUri) == effectivePort(requestUri);
  }

  private static boolean hasConfiguredBasePath(URI baseUri, URI requestUri) {
    String basePath = normalizedPath(baseUri.getPath());
    String requestPath = normalizedPath(requestUri.getPath());
    return "/".equals(basePath)
        || requestPath.equals(basePath)
        || requestPath.startsWith(basePath + "/");
  }

  private static boolean hasForbiddenRequestComponents(URI requestUri) {
    return requestUri.getUserInfo() != null || requestUri.getFragment() != null;
  }

  private static int effectivePort(URI uri) {
    if (uri.getPort() >= 0) {
      return uri.getPort();
    }
    return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
  }

  private static String normalizedPath(String value) {
    String path = value == null || value.isEmpty() ? "/" : value;
    while (path.length() > 1 && path.endsWith("/")) {
      path = path.substring(0, path.length() - 1);
    }
    return path;
  }
}
