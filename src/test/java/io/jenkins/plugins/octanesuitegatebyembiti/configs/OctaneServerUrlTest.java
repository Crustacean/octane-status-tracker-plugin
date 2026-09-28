package io.jenkins.plugins.octanesuitegatebyembiti.configs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import org.junit.jupiter.api.Test;

class OctaneServerUrlTest {
  @Test
  void rejectsPlaintextCredentialTransportIncludingLoopbackAndDowngrades() {
    for (String url : new String[] {"http://octane.example.test", "http://127.0.0.1:8080"}) {
      assertThrows(IllegalArgumentException.class, () -> OctaneServerUrl.normalize(url));
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            OctaneServerUrl.requireAllowedRequest(
                "https://octane.example.test", URI.create("http://octane.example.test/api")));
  }

  @Test
  void normalizesConfiguredOctaneUrl() {
    assertEquals(
        "https://octane.example.test/api",
        OctaneServerUrl.normalize(" https://octane.example.test/api/ "));
  }

  @Test
  void rejectsEmbeddedCredentialsQueriesAndFragments() {
    assertThrows(
        IllegalArgumentException.class,
        () -> OctaneServerUrl.normalize("https://user:secret@octane.example.test"));
    assertThrows(
        IllegalArgumentException.class,
        () -> OctaneServerUrl.normalize("https://octane.example.test?target=internal"));
    assertThrows(
        IllegalArgumentException.class,
        () -> OctaneServerUrl.normalize("https://octane.example.test/#fragment"));
  }

  @Test
  void rejectsNonHttpAndHostlessUrls() {
    assertThrows(
        IllegalArgumentException.class, () -> OctaneServerUrl.normalize("file:///etc/passwd"));
    assertThrows(IllegalArgumentException.class, () -> OctaneServerUrl.normalize("https:///api"));
  }

  @Test
  void permitsOnlyConfiguredOriginAndBasePath() {
    String baseUrl = "https://octane.example.test:8443/octane";

    assertEquals(
        URI.create("https://octane.example.test:8443/octane/api/shared_spaces/1"),
        OctaneServerUrl.requireAllowedRequest(
            baseUrl, URI.create("https://octane.example.test:8443/octane/api/shared_spaces/1")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            OctaneServerUrl.requireAllowedRequest(
                baseUrl, URI.create("https://octane.example.test:8444/octane/api")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            OctaneServerUrl.requireAllowedRequest(
                baseUrl, URI.create("https://internal.example.test/octane/api")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            OctaneServerUrl.requireAllowedRequest(
                baseUrl, URI.create("https://octane.example.test:8443/admin")));
  }

  @Test
  void permitsApiPathsWhenConfiguredAtServerRoot() {
    assertEquals(
        URI.create("https://octane.example.test/api/shared_spaces/1"),
        OctaneServerUrl.requireAllowedRequest(
            "https://octane.example.test",
            URI.create("https://octane.example.test/api/shared_spaces/1")));
  }
}
