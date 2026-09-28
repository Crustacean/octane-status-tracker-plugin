package io.jenkins.plugins.octanesuitegatebyembiti.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import hudson.AbortException;
import hudson.util.Secret;
import io.jenkins.plugins.octanesuitegatebyembiti.models.GateRequest;
import io.jenkins.plugins.octanesuitegatebyembiti.repositories.OctaneClient;
import io.jenkins.plugins.octanesuitegatebyembiti.security.OctaneTestHttpsServer;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;
import jenkins.model.Jenkins;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

public class OctaneDynamicConnectionTest {
  @Rule public JenkinsRule jenkins = new JenkinsRule();

  private HttpServer server;
  private String baseUrl;

  @Before
  public void startServer() throws Exception {
    server = OctaneTestHttpsServer.create();
    server.createContext("/authentication/sign_out", exchange -> json(exchange, 200, "{}"));
    server.start();
    baseUrl = "https://127.0.0.1:" + server.getAddress().getPort();
  }

  @After
  public void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  public void dynamicConnectionAuthenticatesWithoutGlobalServerConfiguration() throws Exception {
    addCredentials("default_shared_space", "mapped-client", "mapped-secret");
    AtomicReference<String> authenticationBody = new AtomicReference<>();
    AtomicReference<String> contentType = new AtomicReference<>();
    server.createContext(
        "/authentication/sign_in",
        exchange -> {
          authenticationBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
          json(exchange, 200, "{}");
        });
    GateRequest request = new GateRequest("default_shared_space", "1196");
    request.setBaseUrl(baseUrl);
    request.setCredentialsId("default_shared_space");

    try (OctaneClient client = new OctaneGateRunner().createClient(request)) {
      client.authenticate();
    }

    assertEquals("application/json", contentType.get());
    assertTrue(authenticationBody.get().contains("\"client_id\":\"mapped-client\""));
    assertTrue(authenticationBody.get().contains("\"client_secret\":\"mapped-secret\""));
  }

  @Test
  public void dynamicConnectionUsesSharedApiCredentialWhenMappedCredentialIsMissing()
      throws Exception {
    addCredentials("octane-api-client", "shared-client", "shared-secret");
    AtomicReference<String> authenticationBody = new AtomicReference<>();
    server.createContext(
        "/authentication/sign_in",
        exchange -> {
          authenticationBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          json(exchange, 200, "{}");
        });
    GateRequest request = new GateRequest("default_shared_space", "1196");
    request.setBaseUrl(baseUrl);
    request.setCredentialsId("missing-space-credential");

    try (OctaneClient client = new OctaneGateRunner().createClient(request)) {
      client.authenticate();
    }

    assertTrue(authenticationBody.get().contains("\"client_id\":\"shared-client\""));
  }

  @Test
  public void dynamicConnectionPrefersSharedApiCredentialWhenBothArePresent() throws Exception {
    addCredentials("octane-api-client", "shared-client", "shared-secret");
    addCredentials("default_shared_space", "space-client", "space-secret");
    AtomicReference<String> authenticationBody = new AtomicReference<>();
    server.createContext(
        "/authentication/sign_in",
        exchange -> {
          authenticationBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          json(exchange, 200, "{}");
        });
    GateRequest request = new GateRequest("default_shared_space", "1196");
    request.setBaseUrl(baseUrl);
    request.setCredentialsId("default_shared_space");

    try (OctaneClient client = new OctaneGateRunner().createClient(request)) {
      client.authenticate();
    }

    assertTrue(authenticationBody.get().contains("\"client_id\":\"shared-client\""));
  }

  @Test
  public void partialDynamicConnectionReportsMissingMappingBaseUrl() throws Exception {
    GateRequest request = new GateRequest("Default Shared Space", "1196");
    request.setCredentialsId("default_shared_space");

    try {
      new OctaneGateRunner().createClient(request);
    } catch (AbortException failure) {
      assertEquals(
          "Base URL missing for space: Default Shared Space in octane_spaces_mapping.json",
          failure.getMessage());
      return;
    }
    throw new AssertionError("Expected the dynamic connection to reject a missing base URL.");
  }

  @Test
  public void credentialsStayEncryptedAtRestAndAreNotDecryptedByTheRunner() throws Exception {
    String password = "test-password-\"-not-for-disk";
    addCredentials("octane-api-client", "client", password);
    GateRequest request = new GateRequest("space", "1196");
    request.setBaseUrl(baseUrl);
    try (OctaneClient client = new OctaneGateRunner().createClient(request)) {
      Field secretField = OctaneClient.class.getDeclaredField("clientSecret");
      assertEquals(Secret.class, secretField.getType());
      secretField.setAccessible(true);
      Secret secret = (Secret) secretField.get(client);
      assertEquals(password, secret.getPlainText());
      assertEquals(password, Secret.fromString(secret.getEncryptedValue()).getPlainText());
      String persisted = Jenkins.XSTREAM2.toXML(secret);
      assertFalse(persisted.contains(password));
      assertEquals(password, ((Secret) Jenkins.XSTREAM2.fromXML(persisted)).getPlainText());
      assertFalse(
          Files.readString(jenkins.jenkins.getRootDir().toPath().resolve("credentials.xml"))
              .contains("test-password"));
    }
  }

  @Test
  public void runnerRejectsPlaintextBeforeResolvingOrSendingCredentials() {
    GateRequest request = new GateRequest("space", "1196");
    request.setBaseUrl("http://127.0.0.1:12345");
    AbortException failure =
        assertThrows(AbortException.class, () -> new OctaneGateRunner().createClient(request));
    assertTrue(failure.getMessage().contains("https://"));
  }

  private void addCredentials(String id, String username, String password) throws Exception {
    SystemCredentialsProvider.getInstance()
        .getCredentials()
        .add(
            new UsernamePasswordCredentialsImpl(
                CredentialsScope.GLOBAL, id, "Octane API test credential", username, password));
    SystemCredentialsProvider.getInstance().save();
  }

  private static void json(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
