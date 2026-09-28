package io.jenkins.plugins.octanesuitegatebyembiti.repositories;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import hudson.AbortException;
import hudson.util.Secret;
import io.jenkins.plugins.octanesuitegatebyembiti.entities.DefectRecord;
import io.jenkins.plugins.octanesuitegatebyembiti.entities.RunRecord;
import io.jenkins.plugins.octanesuitegatebyembiti.models.GateMetrics;
import io.jenkins.plugins.octanesuitegatebyembiti.models.GateResult;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneGateReportSnapshot;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneGateReportState;
import io.jenkins.plugins.octanesuitegatebyembiti.models.StatusClassifier;
import io.jenkins.plugins.octanesuitegatebyembiti.security.OctaneTestHttpsServer;
import io.jenkins.plugins.octanesuitegatebyembiti.services.OctaneReportZoneHtmlRenderer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class OctaneClientTest {
  private HttpServer server;
  private String baseUrl;
  private ExecutorService serverExecutor;

  @Before
  public void startServer() throws Exception {
    server = OctaneTestHttpsServer.create();
    serverExecutor = Executors.newCachedThreadPool();
    server.setExecutor(serverExecutor);
    baseUrl = "https://127.0.0.1:" + server.getAddress().getPort();
    OctaneRequestCoordinator.resetForTests();
    OctaneSuiteTopologyCache.resetForTests();
    server.start();
  }

  @After
  public void stopServer() {
    if (server != null) {
      server.stop(0);
    }
    if (serverExecutor != null) {
      serverExecutor.shutdownNow();
    }
  }

  @Test
  public void acceptsOnlySecretTypedCredentialInputs() {
    for (var constructor : OctaneClient.class.getDeclaredConstructors()) {
      Class<?>[] parameters = constructor.getParameterTypes();
      assertEquals(Secret.class, parameters[parameters.length - 1]);
    }
  }

  @Test
  public void refusesPlaintextAndRedirectsWithoutForwardingCredentials() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new OctaneClient(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "client",
                Secret.fromString("secret")));
    try (java.net.http.HttpClient redirecting =
        java.net.http.HttpClient.newBuilder()
            .followRedirects(java.net.http.HttpClient.Redirect.ALWAYS)
            .build()) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new OctaneClient(redirecting, baseUrl, "client", Secret.fromString("secret")));
    }
    HttpServer plaintext = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    AtomicInteger leakedRequests = new AtomicInteger();
    plaintext.createContext(
        "/",
        exchange -> {
          leakedRequests.incrementAndGet();
          json(exchange, 200, "{}");
        });
    plaintext.start();
    server.createContext(
        "/authentication/sign_in",
        exchange -> {
          exchange
              .getResponseHeaders()
              .set("Location", "http://127.0.0.1:" + plaintext.getAddress().getPort() + "/");
          exchange.sendResponseHeaders(307, -1);
          exchange.close();
        });
    try (OctaneClient client = new OctaneClient(baseUrl, "client", Secret.fromString("secret"))) {
      assertThrows(AbortException.class, client::authenticate);
      assertEquals(0, leakedRequests.get());
    } finally {
      plaintext.stop(0);
    }
  }

  @Test
  public void logoutSendsCookiesOnlyOverTlsAndNeverFollowsADowngrade() throws Exception {
    HttpServer plaintext = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    AtomicInteger leakedRequests = new AtomicInteger();
    AtomicInteger secureLogouts = new AtomicInteger();
    AtomicBoolean cookieReceivedSecurely = new AtomicBoolean();
    plaintext.createContext(
        "/",
        exchange -> {
          leakedRequests.incrementAndGet();
          json(exchange, 200, "{}");
        });
    plaintext.start();
    server.createContext("/authentication/sign_in", exchange -> json(exchange, 200, "{}"));
    server.createContext(
        "/authentication/sign_out",
        exchange -> {
          secureLogouts.incrementAndGet();
          cookieReceivedSecurely.set(
              exchange instanceof com.sun.net.httpserver.HttpsExchange
                  && "POST".equals(exchange.getRequestMethod())
                  && "LWSSO_COOKIE_KEY=test"
                      .equals(exchange.getRequestHeaders().getFirst("Cookie")));
          exchange
              .getResponseHeaders()
              .set("Location", "http://127.0.0.1:" + plaintext.getAddress().getPort() + "/");
          exchange.sendResponseHeaders(307, -1);
          exchange.close();
        });
    try {
      try (OctaneClient client = new OctaneClient(baseUrl, "client", Secret.fromString("secret"))) {
        client.authenticate();
        client.close();
        var cookie = OctaneClient.class.getDeclaredField("cookieHeader");
        cookie.setAccessible(true);
        org.junit.Assert.assertNull(cookie.get(client));
      }
      assertEquals(1, secureLogouts.get());
      assertTrue(cookieReceivedSecurely.get());
      assertEquals(0, leakedRequests.get());
    } finally {
      plaintext.stop(0);
    }
  }

  @Test
  public void failedLogoutDropsTheRetainedCookieAndRepeatedCloseDoesNotRetry() throws Exception {
    server.createContext("/authentication/sign_in", exchange -> json(exchange, 200, "{}"));
    try (OctaneClient client = new OctaneClient(baseUrl, "client", Secret.fromString("secret"))) {
      client.authenticate();
      server.stop(0);

      assertThrows(IOException.class, client::close);

      var cookie = OctaneClient.class.getDeclaredField("cookieHeader");
      cookie.setAccessible(true);
      org.junit.Assert.assertNull(cookie.get(client));
      client.close();
    }
  }

  @Test
  public void authenticatesAndFetchesSuiteChildRuns() throws Exception {
    server.createContext("/authentication/sign_in", exchange -> json(exchange, 200, "{}"));
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/runs",
        exchange -> {
          String query = exchange.getRequestURI().getRawQuery();
          assertTrue(exchange.getRequestHeaders().getFirst("Cookie").contains("LWSSO_COOKIE_KEY"));
          if (query.contains("limit=1")) {
            assertTrue(
                URLDecoder.decode(query, StandardCharsets.UTF_8)
                    .contains("default_run_by{id,name}"));
            json(
                exchange,
                200,
                "{\"data\":[{\"id\":\"55\",\"name\":\"suite\","
                    + "\"default_run_by\":{\"data\":[{\"name\":\"Ada Owner\"}]},"
                    + "\"runs_in_suite\":["
                    + "{\"id\":\"101\"},{\"id\":\"102\"}]}]}");
          } else {
            String decodedQuery = URLDecoder.decode(query, StandardCharsets.UTF_8);
            assertTrue(decodedQuery.contains("run_by{id,name}"));
            if (decodedQuery.contains("assigned_to")) {
              json(exchange, 400, "{\"error\":\"Unknown run field assigned_to\"}");
              return;
            }
            assertFalse(decodedQuery.contains("assigned_to"));
            json(
                exchange,
                200,
                "{\"data\":["
                    + "{\"id\":\"101\",\"name\":\"one\","
                    + "\"native_status\":{\"logical_name\":\"passed\"},"
                    + "\"run_by\":{\"name\":\"qa-Jenkins-agent\"}},"
                    + "{\"id\":\"102\",\"name\":\"two\","
                    + "\"native_status\":{\"logical_name\":\"failed\"},"
                    + "\"run_by\":{\"name\":\"Ben Tester\"}}"
                    + "]}");
          }
        });
    server.createContext("/authentication/sign_out", exchange -> json(exchange, 200, "{}"));

    try (OctaneClient client = new OctaneClient(baseUrl, "client", Secret.fromString("secret"))) {
      client.authenticate();
      List<RunRecord> records = client.fetchSuiteChildRuns("1001", "2002", "55");

      assertEquals(2, records.size());
      assertEquals("101", records.get(0).getId());
      assertEquals("passed", records.get(0).getStatus());
      assertEquals("qa-Jenkins-agent", records.get(0).getRunByName());
      assertEquals("Ada Owner", records.get(0).getAssignedToName());
      assertEquals("failed", records.get(1).getStatus());
      assertEquals("Ben Tester", records.get(1).getRunByName());
      assertEquals("Ada Owner", records.get(1).getAssignedToName());
    }
  }

  @Test
  public void preservesInProgressNativeStatusAcrossTheApiBoundary() throws Exception {
    server.createContext("/authentication/sign_in", exchange -> json(exchange, 200, "{}"));
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/runs",
        exchange -> {
          String query = exchange.getRequestURI().getRawQuery();
          if (query.contains("limit=1")) {
            json(
                exchange,
                200,
                "{\"data\":[{\"id\":\"55\",\"runs_in_suite\":[{\"id\":\"101\"},{\"id\":\"102\"}]}]}");
            return;
          }
          json(
              exchange,
              200,
              "{\"data\":["
                  + "{\"id\":\"101\","
                  + "\"native_status\":{\"logical_name\":\"list_node.run_status.in_progress\"},"
                  + "\"status\":{\"logical_name\":\"list_node.run_status.skipped\"}},"
                  + "{\"id\":\"102\","
                  + "\"status\":{\"logical_name\":\"list_node.run_status.in_progress\"}}]} ");
        });
    server.createContext("/authentication/sign_out", exchange -> json(exchange, 200, "{}"));

    try (OctaneClient client = new OctaneClient(baseUrl, "client", Secret.fromString("secret"))) {
      client.authenticate();
      List<RunRecord> records = client.fetchSuiteChildRuns("1001", "2002", "55");

      assertEquals("list_node.run_status.in_progress", records.get(0).getStatus());
      assertEquals("list_node.run_status.in_progress", records.get(1).getStatus());
      GateMetrics metrics = GateMetrics.fromRuns(records, defaultClassifier());
      assertEquals(2, metrics.getRunning());
      assertEquals(0, metrics.getSkipped());
      assertEquals(0.0, metrics.getCompletionRate(), 0.000001);
    }
  }

  @Test
  public void preservesOctaneSixteenManualRunNotCompletedAsActiveAcrossTheApiBoundary()
      throws Exception {
    server.createContext("/authentication/sign_in", exchange -> json(exchange, 200, "{}"));
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/runs",
        exchange -> {
          String query = exchange.getRequestURI().getRawQuery();
          if (query.contains("limit=1")) {
            json(
                exchange,
                200,
                "{\"data\":[{\"id\":\"55\",\"runs_in_suite\":["
                    + "{\"id\":\"101\"},{\"id\":\"102\"},{\"id\":\"103\"},"
                    + "{\"id\":\"104\"},{\"id\":\"105\"}]}]}");
            return;
          }
          json(
              exchange,
              200,
              "{\"data\":["
                  + "{\"id\":\"101\",\"native_status\":{\"logical_name\":\"passed\"}},"
                  + "{\"id\":\"102\",\"native_status\":{\"logical_name\":\"passed\"}},"
                  + "{\"id\":\"103\",\"native_status\":{\"logical_name\":\"passed\"}},"
                  + "{\"id\":\"104\",\"native_status\":{\"logical_name\":\"passed\"}},"
                  + "{\"id\":\"105\",\"native_status\":{\"logical_name\":"
                  + "\"list_node.run_native_status.not_completed\"},"
                  + "\"status\":{\"logical_name\":\"list_node.run_status.skipped\"}}]}");
        });
    server.createContext("/authentication/sign_out", exchange -> json(exchange, 200, "{}"));

    try (OctaneClient client = new OctaneClient(baseUrl, "client", Secret.fromString("secret"))) {
      client.authenticate();
      List<RunRecord> records = client.fetchSuiteChildRuns("1001", "2002", "55");

      assertEquals("list_node.run_native_status.not_completed", records.get(4).getStatus());
      GateMetrics metrics = GateMetrics.fromRuns(records, defaultClassifier());
      assertEquals(4, metrics.getExecuted());
      assertEquals(0, metrics.getSkipped());
      assertEquals(1, metrics.getRunning());
      assertEquals(80.0, metrics.getCompletionRate(), 0.000001);
      assertFalse(metrics.isTerminal());
    }
  }

  @Test
  public void keepsDefaultRunByAsSingleUiAndEmailOwnerForAutomatedChildren() throws Exception {
    server.createContext("/authentication/sign_in", exchange -> json(exchange, 200, "{}"));
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/runs",
        exchange -> {
          if (idsFromQuery(exchange).contains("101")) {
            json(
                exchange,
                200,
                "{\"data\":["
                    + "{\"id\":\"101\",\"name\":\"one\","
                    + "\"native_status\":{\"logical_name\":\"passed\"},"
                    + "\"run_by\":{\"name\":\"Jenkins Agent\"},"
                    + "\"assigned_to\":{\"name\":\"Child Assignment\"},"
                    + "\"test\":{\"id\":\"test-1\",\"owner\":{\"name\":\"Other User\"}}},"
                    + "{\"id\":\"102\",\"name\":\"two\","
                    + "\"native_status\":{\"logical_name\":\"passed\"},"
                    + "\"run_by\":null,"
                    + "\"native_tester\":{\"name\":\"Jenkins Agent\"}}]} ");
          } else {
            json(
                exchange,
                200,
                "{\"data\":[{\"id\":\"55\","
                    + "\"default_run_by\":{\"name\":\"Jane Doe\"},"
                    + "\"runs_in_suite\":[{\"id\":\"101\"},{\"id\":\"102\"}]}]}");
          }
        });
    server.createContext("/authentication/sign_out", exchange -> json(exchange, 200, "{}"));

    try (OctaneClient client = new OctaneClient(baseUrl, "client", Secret.fromString("secret"))) {
      client.authenticate();
      List<RunRecord> records = client.fetchSuiteChildRuns("1001", "2002", List.of("55")).get("55");

      assertEquals(
          List.of("Jane Doe", "Jane Doe"),
          records.stream().map(record -> record.getSuiteOwnerName()).toList());
      assertEquals(
          List.of("Jenkins Agent", "Jenkins Agent"),
          records.stream().map(record -> record.getExecutionActorName()).toList());
      assertSingleOwnerAcrossReportSurfaces(records, "Jane Doe");
    }
  }

  @Test
  public void usesParentAssignedToAliasWhenDefaultRunByIsUnavailable() throws Exception {
    AtomicInteger assignedToQueries = new AtomicInteger();
    server.createContext("/authentication/sign_in", exchange -> json(exchange, 200, "{}"));
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/runs",
        exchange -> {
          if (idsFromQuery(exchange).contains("101")) {
            json(
                exchange,
                200,
                "{\"data\":["
                    + "{\"id\":\"101\",\"native_status\":{\"logical_name\":\"passed\"},"
                    + "\"run_by\":{\"name\":\"Jenkins Agent\"}},"
                    + "{\"id\":\"102\",\"native_status\":{\"logical_name\":\"passed\"},"
                    + "\"native_tester\":{\"name\":\"Jenkins Agent\"}}]}");
            return;
          }
          String query =
              URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
          if (query.contains("default_run_by")) {
            json(exchange, 400, "{\"error\":\"Unknown run field default_run_by\"}");
            return;
          }
          if (query.contains("assigned_to")) {
            assignedToQueries.incrementAndGet();
          }
          json(
              exchange,
              200,
              "{\"data\":[{\"id\":\"55\","
                  + "\"assigned_to\":{\"email\":\"jane.doe@example.com\"},"
                  + "\"owner\":{\"email\":\"fallback.owner@example.com\"},"
                  + "\"runs_in_suite\":[{\"id\":\"101\"},{\"id\":\"102\"}]}]}");
        });
    server.createContext("/authentication/sign_out", exchange -> json(exchange, 200, "{}"));

    try (OctaneClient client = new OctaneClient(baseUrl, "client", Secret.fromString("secret"))) {
      client.authenticate();
      List<RunRecord> records = client.fetchSuiteChildRuns("1001", "2002", List.of("55")).get("55");

      assertTrue(assignedToQueries.get() > 0);
      assertEquals(
          List.of("jane.doe@example.com", "jane.doe@example.com"),
          records.stream().map(record -> record.getSuiteOwnerName()).toList());
      assertEquals(
          List.of("Jenkins Agent", "Jenkins Agent"),
          records.stream().map(record -> record.getExecutionActorName()).toList());
      assertSingleOwnerAcrossReportSurfaces(records, "jane.doe@example.com");
    }
  }

  @Test
  public void usesDedicatedParentAssigneeAliasWhenBatchPayloadOmitsAssignment() throws Exception {
    server.createContext("/authentication/sign_in", exchange -> json(exchange, 200, "{}"));
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/runs",
        exchange -> {
          if (idsFromQuery(exchange).contains("101")) {
            json(
                exchange,
                200,
                "{\"data\":[{\"id\":\"101\","
                    + "\"native_status\":{\"logical_name\":\"passed\"},"
                    + "\"run_by\":{\"name\":\"Jenkins Agent\"}}]}");
          } else {
            // Some Octane versions silently omit unsupported relationship fields instead of 400.
            json(
                exchange, 200, "{\"data\":[{\"id\":\"55\",\"runs_in_suite\":[{\"id\":\"101\"}]}]}");
          }
        });
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/suite_runs/55",
        exchange -> {
          String query =
              URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
          String assignee =
              query.contains("assignee{id,name}")
                  ? ",\"assignee\":{\"name\":\"Jane Dedicated\"}"
                  : "";
          json(exchange, 200, "{\"id\":\"55\"" + assignee + "}");
        });
    server.createContext("/authentication/sign_out", exchange -> json(exchange, 200, "{}"));

    try (OctaneClient client = new OctaneClient(baseUrl, "client", Secret.fromString("secret"))) {
      client.authenticate();
      List<RunRecord> records = client.fetchSuiteChildRuns("1001", "2002", List.of("55")).get("55");

      assertEquals(1, records.size());
      assertEquals("Jane Dedicated", records.get(0).getSuiteOwnerName());
      assertEquals("Jenkins Agent", records.get(0).getExecutionActorName());
    }
  }

  @Test
  public void resolvesDefaultRunByFromRelatedTestSuiteForAutomatedChildren() throws Exception {
    AtomicInteger genericOwnerQueries = new AtomicInteger();
    server.createContext("/authentication/sign_in", exchange -> json(exchange, 200, "{}"));
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/runs",
        exchange -> {
          if (idsFromQuery(exchange).contains("101")) {
            json(
                exchange,
                200,
                "{\"data\":[{\"id\":\"101\",\"name\":\"automated child\","
                    + "\"native_status\":{\"logical_name\":\"passed\"},"
                    + "\"run_by\":{\"name\":\"Jenkins Agent\"}}]}");
            return;
          }
          json(
              exchange,
              200,
              "{\"data\":[{\"id\":\"55\",\"name\":\"suite run\","
                  + "\"test\":{\"id\":\"suite-test-900\",\"name\":\"Regression Suite\"},"
                  + "\"runs_in_suite\":[{\"id\":\"101\"}]}]}");
        });
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/suite_runs/55",
        exchange -> {
          String query =
              URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
          if (query.contains("owner{id,name}")) {
            genericOwnerQueries.incrementAndGet();
            json(
                exchange,
                200,
                "{\"id\":\"55\",\"owner\":{\"email\":\"generic.owner@example.com\"}}");
          } else if (query.contains("test{id,name}")) {
            json(
                exchange,
                200,
                "{\"id\":\"55\",\"test\":{\"id\":\"suite-test-900\","
                    + "\"name\":\"Regression Suite\"}}");
          } else {
            json(exchange, 200, "{\"id\":\"55\"}");
          }
        });
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/tests/suite-test-900",
        exchange -> {
          String query =
              URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
          String assignment =
              query.contains("default_run_by{id,name}")
                  ? ",\"default_run_by\":{\"name\":\"ada@example.com\"}"
                  : "";
          json(exchange, 200, "{\"id\":\"suite-test-900\"" + assignment + "}");
        });
    server.createContext("/authentication/sign_out", exchange -> json(exchange, 200, "{}"));

    try (OctaneClient client = new OctaneClient(baseUrl, "client", Secret.fromString("secret"))) {
      client.authenticate();
      List<RunRecord> records = client.fetchSuiteChildRuns("1001", "2002", List.of("55")).get("55");

      assertEquals(1, records.size());
      assertEquals("ada@example.com", records.get(0).getSuiteOwnerName());
      assertEquals("Jenkins Agent", records.get(0).getExecutionActorName());
      assertEquals(0, genericOwnerQueries.get());
      assertSingleOwnerAcrossReportSurfaces(records, "ada@example.com");
    }
  }

  @Test
  public void batchSuitePollingUsesParentOwnerWhenDefaultRunByIsUnavailable() throws Exception {
    server.createContext("/authentication/sign_in", exchange -> json(exchange, 200, "{}"));
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/runs",
        exchange -> {
          boolean childRuns = idsFromQuery(exchange).contains("101");
          String query =
              URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
          if (!childRuns && query.contains("default_run_by")) {
            json(exchange, 400, "{\"error\":\"Unknown run field default_run_by\"}");
          } else if (childRuns) {
            json(
                exchange,
                200,
                "{\"data\":["
                    + "{\"id\":\"101\",\"native_status\":{\"logical_name\":\"passed\"},"
                    + "\"run_by\":{\"name\":\"Jenkins Agent\"}},"
                    + "{\"id\":\"102\",\"native_status\":{\"logical_name\":\"failed\"},"
                    + "\"run_by\":{\"name\":\"Default Manual Runner\"}}]} ");
          } else {
            json(
                exchange,
                200,
                "{\"data\":[{\"id\":\"55\","
                    + "\"owner\":{\"data\":[{\"name\":\"Suite Owner\"}]},"
                    + "\"runs_in_suite\":[{\"id\":\"101\"},{\"id\":\"102\"}]}]}");
          }
        });
    server.createContext("/authentication/sign_out", exchange -> json(exchange, 200, "{}"));

    try (OctaneClient client = new OctaneClient(baseUrl, "client", Secret.fromString("secret"))) {
      client.authenticate();
      List<RunRecord> records = client.fetchSuiteChildRuns("1001", "2002", List.of("55")).get("55");

      assertEquals(
          List.of("Jenkins Agent", "Default Manual Runner"),
          records.stream().map(record -> record.getRunByName()).toList());
      assertEquals(
          List.of("Suite Owner", "Suite Owner"),
          records.stream().map(record -> record.getAssignedToName()).toList());
    }
  }

  @Test
  public void ignoresParentSuiteTestOwnerWhenDirectAssignmentIsNotReturned() throws Exception {
    server.createContext("/authentication/sign_in", exchange -> json(exchange, 200, "{}"));
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/runs",
        exchange -> {
          if (idsFromQuery(exchange).contains("101")) {
            json(
                exchange,
                200,
                "{\"data\":["
                    + "{\"id\":\"101\",\"native_status\":{\"logical_name\":\"passed\"},"
                    + "\"run_by\":{\"name\":\"Jenkins Agent\"},"
                    + "\"test\":{\"id\":\"test-1\",\"owner\":{\"data\":[{"
                    + "\"email\":\"wrong-child@example.com\"}]}}},"
                    + "{\"id\":\"102\",\"native_status\":{\"logical_name\":\"failed\"},"
                    + "\"run_by\":{\"name\":\"Default Manual Runner\"},"
                    + "\"test\":{\"id\":\"test-2\",\"owner\":{\"data\":[{"
                    + "\"email\":\"another-child@example.com\"}]}}}]} ");
          } else {
            json(
                exchange,
                200,
                "{\"data\":[{\"id\":\"55\","
                    + "\"runs_in_suite\":[{\"id\":\"101\"},{\"id\":\"102\"}]}]}");
          }
        });
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/suite_runs/55",
        exchange -> {
          String query =
              URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
          String owner =
              query.contains("test{id,name,owner{id,name}}")
                  ? ",\"test\":{\"id\":\"suite-test\","
                      + "\"owner\":{\"email\":\"ada@example.com\"}}"
                  : "";
          json(exchange, 200, "{\"id\":\"55\"" + owner + "}");
        });
    server.createContext("/authentication/sign_out", exchange -> json(exchange, 200, "{}"));

    try (OctaneClient client = new OctaneClient(baseUrl, "client", Secret.fromString("secret"))) {
      client.authenticate();
      List<RunRecord> records = client.fetchSuiteChildRuns("1001", "2002", List.of("55")).get("55");

      assertEquals(
          List.of("Unassigned (55)", "Unassigned (55)"),
          records.stream().map(record -> record.getAssignedToName()).toList());
      assertEquals(
          List.of("Jenkins Agent", "Default Manual Runner"),
          records.stream().map(record -> record.getRunByName()).toList());
    }
  }

  @Test
  public void usesParentDefaultRunByAsSingleGroupingKey() throws Exception {
    server.createContext("/authentication/sign_in", exchange -> json(exchange, 200, "{}"));
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/runs",
        exchange -> {
          if (idsFromQuery(exchange).contains("101")) {
            json(
                exchange,
                200,
                "{\"data\":["
                    + "{\"id\":\"101\",\"native_status\":{\"logical_name\":\"passed\"},"
                    + "\"run_by\":{\"name\":\"Jenkins Agent\"},"
                    + "\"test\":{\"id\":\"test-1\",\"owner\":{\"name\":\"Ada Owner\"}}},"
                    + "{\"id\":\"102\",\"native_status\":{\"logical_name\":\"failed\"},"
                    + "\"run_by\":{\"name\":\"Default Manual Runner\"},"
                    + "\"test\":{\"id\":\"test-2\"}}]} ");
          } else {
            json(
                exchange,
                200,
                "{\"data\":[{\"id\":\"55\","
                    + "\"default_run_by\":{\"email\":\"default.runner@example.com\"},"
                    + "\"run_by\":{\"email\":\"parent.execution@example.com\"},"
                    + "\"assigned_to\":{\"email\":\"suite.owner@example.com\"},"
                    + "\"runs_in_suite\":[{\"id\":\"101\"},{\"id\":\"102\"}]}]}");
          }
        });
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/suite_runs/55",
        exchange -> json(exchange, 200, "{\"id\":\"55\"}"));
    server.createContext("/authentication/sign_out", exchange -> json(exchange, 200, "{}"));

    try (OctaneClient client = new OctaneClient(baseUrl, "client", Secret.fromString("secret"))) {
      client.authenticate();
      List<RunRecord> records = client.fetchSuiteChildRuns("1001", "2002", List.of("55")).get("55");

      assertEquals(
          List.of("default.runner@example.com", "default.runner@example.com"),
          records.stream().map(record -> record.getAssignedToName()).toList());
      assertEquals(
          List.of("Jenkins Agent", "Default Manual Runner"),
          records.stream().map(record -> record.getRunByName()).toList());
    }
  }

  @Test
  public void preservesParentDefaultRunByWhenAutomatedChildRunByChanges() throws Exception {
    AtomicInteger childPolls = new AtomicInteger();
    server.createContext("/authentication/sign_in", exchange -> json(exchange, 200, "{}"));
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/runs",
        exchange -> {
          if (idsFromQuery(exchange).contains("101")) {
            int childPoll = childPolls.getAndIncrement();
            String runBy =
                childPoll == 0
                    ? ",\"run_by\":{\"name\":\"Manual Runner\"}"
                    : childPoll == 1 ? "" : ",\"run_by\":{\"name\":\"Jenkins Agent\"}";
            json(
                exchange,
                200,
                "{\"data\":[{\"id\":\"101\",\"native_status\":{\"logical_name\":\"passed\"}"
                    + runBy
                    + "}]}");
          } else {
            json(
                exchange,
                200,
                "{\"data\":[{\"id\":\"55\","
                    + "\"default_run_by\":{\"name\":\"ada@example.com\"},"
                    + "\"runs_in_suite\":[{\"id\":\"101\"}]}]}");
          }
        });
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/suite_runs/55",
        exchange -> json(exchange, 200, "{\"id\":\"55\"}"));
    server.createContext("/authentication/sign_out", exchange -> json(exchange, 200, "{}"));

    try (OctaneClient client = new OctaneClient(baseUrl, "client", Secret.fromString("secret"))) {
      client.authenticate();

      RunRecord planned =
          client.fetchSuiteChildRuns("1001", "2002", List.of("55")).get("55").get(0);
      RunRecord pickedUp =
          client.fetchSuiteChildRuns("1001", "2002", List.of("55")).get("55").get(0);
      RunRecord automated =
          client.fetchSuiteChildRuns("1001", "2002", List.of("55")).get("55").get(0);

      assertEquals("Manual Runner", planned.getRunByName());
      assertEquals("ada@example.com", planned.getAssignedToName());
      assertEquals("", pickedUp.getRunByName());
      assertEquals("ada@example.com", pickedUp.getAssignedToName());
      assertEquals("Jenkins Agent", automated.getRunByName());
      assertEquals("ada@example.com", automated.getAssignedToName());
    }
  }

  @Test
  public void ignoresChildAssignmentWhenParentAttributionIsUnavailable() throws Exception {
    AtomicInteger childPolls = new AtomicInteger();
    server.createContext("/authentication/sign_in", exchange -> json(exchange, 200, "{}"));
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/runs",
        exchange -> {
          if (idsFromQuery(exchange).contains("101")) {
            String testOwner =
                childPolls.getAndIncrement() == 0
                    ? ",\"test\":{\"id\":\"test-1\",\"owner\":{\"email\":\"ada@example.com\"}}"
                    : ",\"test\":{\"id\":\"test-1\"}";
            json(
                exchange,
                200,
                "{\"data\":[{\"id\":\"101\",\"native_status\":{\"logical_name\":\"running\"},"
                    + "\"run_by\":{\"name\":\"Jenkins Agent\"}"
                    + testOwner
                    + "}]}");
          } else {
            json(
                exchange, 200, "{\"data\":[{\"id\":\"55\",\"runs_in_suite\":[{\"id\":\"101\"}]}]}");
          }
        });
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/suite_runs/55",
        exchange -> json(exchange, 200, "{\"id\":\"55\"}"));
    server.createContext("/authentication/sign_out", exchange -> json(exchange, 200, "{}"));

    try (OctaneClient client = new OctaneClient(baseUrl, "client", Secret.fromString("secret"))) {
      client.authenticate();

      RunRecord assigned =
          client.fetchSuiteChildRuns("1001", "2002", List.of("55")).get("55").get(0);
      RunRecord automated =
          client.fetchSuiteChildRuns("1001", "2002", List.of("55")).get("55").get(0);

      assertEquals("Unassigned (55)", assigned.getAssignedToName());
      assertEquals("Unassigned (55)", automated.getAssignedToName());
      assertEquals("Jenkins Agent", automated.getRunByName());
    }
  }

  @Test
  public void locksInitialParentAssignmentAcrossParentAndChildStateChanges() throws Exception {
    AtomicInteger parentPolls = new AtomicInteger();
    server.createContext("/authentication/sign_in", exchange -> json(exchange, 200, "{}"));
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/runs",
        exchange -> {
          if (idsFromQuery(exchange).contains("101")) {
            json(
                exchange,
                200,
                "{\"data\":[{\"id\":\"101\","
                    + "\"native_status\":{\"logical_name\":\"running\"}}]}");
          } else {
            String owner =
                parentPolls.getAndIncrement() == 0 ? "ada@example.com" : "bob@example.com";
            json(
                exchange,
                200,
                "{\"data\":[{\"id\":\"56\",\"owner\":{\"email\":\""
                    + owner
                    + "\"},\"runs_in_suite\":[{\"id\":\"101\"}]}]}");
          }
        });
    server.createContext("/authentication/sign_out", exchange -> json(exchange, 200, "{}"));

    try (OctaneClient client = new OctaneClient(baseUrl, "client", Secret.fromString("secret"))) {
      client.authenticate();

      RunRecord firstPoll = client.fetchSuiteChildRuns("1001", "2002", "56").get(0);
      RunRecord secondPoll = client.fetchSuiteChildRuns("1001", "2002", "56").get(0);

      assertEquals("ada@example.com", firstPoll.getAssignedToName());
      assertEquals("ada@example.com", secondPoll.getAssignedToName());
    }
  }

  @Test
  public void doesNotLockUnassignedBeforeParentAssignmentBecomesAvailable() throws Exception {
    AtomicInteger parentPolls = new AtomicInteger();
    server.createContext("/authentication/sign_in", exchange -> json(exchange, 200, "{}"));
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/runs",
        exchange -> {
          if (idsFromQuery(exchange).contains("101")) {
            json(
                exchange,
                200,
                "{\"data\":[{\"id\":\"101\",\"native_status\":{\"logical_name\":\"running\"},"
                    + "\"run_by\":{\"name\":\"Jenkins Agent\"}}]}");
          } else {
            String assignment =
                parentPolls.getAndIncrement() == 0
                    ? ""
                    : ",\"default_run_by\":{\"name\":\"ada@example.com\"}";
            json(
                exchange,
                200,
                "{\"data\":[{\"id\":\"57\""
                    + assignment
                    + ",\"runs_in_suite\":[{\"id\":\"101\"}]}]}");
          }
        });
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/suite_runs/57",
        exchange -> json(exchange, 200, "{\"id\":\"57\"}"));
    server.createContext("/authentication/sign_out", exchange -> json(exchange, 200, "{}"));

    try (OctaneClient client = new OctaneClient(baseUrl, "client", Secret.fromString("secret"))) {
      client.authenticate();

      RunRecord unresolved = client.fetchSuiteChildRuns("1001", "2002", "57").get(0);
      RunRecord assigned = client.fetchSuiteChildRuns("1001", "2002", "57").get(0);

      assertEquals("Unassigned (57)", unresolved.getAssignedToName());
      assertEquals("ada@example.com", assigned.getAssignedToName());
    }
  }

  @Test
  public void rechecksBlankCachedTopologyUntilDefaultRunByBecomesAvailable() throws Exception {
    AtomicInteger dedicatedOwnerQueries = new AtomicInteger();
    server.createContext("/authentication/sign_in", exchange -> json(exchange, 200, "{}"));
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/runs",
        exchange -> {
          if (idsFromQuery(exchange).contains("101")) {
            json(
                exchange,
                200,
                "{\"data\":[{\"id\":\"101\",\"native_status\":{\"logical_name\":\"running\"},"
                    + "\"run_by\":{\"name\":\"Jenkins Agent\"}}]}");
          } else {
            json(
                exchange, 200, "{\"data\":[{\"id\":\"58\",\"runs_in_suite\":[{\"id\":\"101\"}]}]}");
          }
        });
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/suite_runs/58",
        exchange -> {
          String query =
              URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
          String assignment =
              query.contains("default_run_by") && dedicatedOwnerQueries.incrementAndGet() > 1
                  ? ",\"default_run_by\":{\"name\":\"ada@example.com\"}"
                  : "";
          json(exchange, 200, "{\"id\":\"58\"" + assignment + "}");
        });
    server.createContext("/authentication/sign_out", exchange -> json(exchange, 200, "{}"));

    try (OctaneClient client = new OctaneClient(baseUrl, "client", Secret.fromString("secret"))) {
      client.authenticate();

      RunRecord unresolved =
          client.fetchSuiteChildRuns("1001", "2002", List.of("58")).get("58").get(0);
      RunRecord assigned =
          client.fetchSuiteChildRuns("1001", "2002", List.of("58")).get("58").get(0);

      assertEquals("Unassigned (58)", unresolved.getSuiteOwnerName());
      assertEquals("ada@example.com", assigned.getSuiteOwnerName());
      assertEquals("Jenkins Agent", assigned.getExecutionActorName());
      assertEquals(2, dedicatedOwnerQueries.get());
    }
  }

  @Test
  public void usesHumanParentRunByAsLegacyDefaultRunBy() throws Exception {
    server.createContext("/authentication/sign_in", exchange -> json(exchange, 200, "{}"));
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/runs",
        exchange -> {
          String query =
              URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
          if (query.contains("default_run_by")) {
            json(exchange, 400, "{\"error\":\"Unknown run field default_run_by\"}");
          } else if (idsFromQuery(exchange).contains("101")) {
            json(
                exchange,
                200,
                "{\"data\":[{\"id\":\"101\",\"name\":\"child\","
                    + "\"native_status\":{\"logical_name\":\"passed\"},"
                    + "\"run_by\":{\"name\":\"Jenkins Agent\"}}]}");
          } else {
            json(
                exchange,
                200,
                "{\"data\":[{\"id\":\"55\",\"name\":\"suite\","
                    + "\"run_by\":{\"name\":\"Parent Owner\"},"
                    + "\"runs_in_suite\":[{\"id\":\"101\"}]}]}");
          }
        });
    server.createContext("/authentication/sign_out", exchange -> json(exchange, 200, "{}"));

    try (OctaneClient client = new OctaneClient(baseUrl, "client", Secret.fromString("secret"))) {
      client.authenticate();
      List<RunRecord> records = client.fetchSuiteChildRuns("1001", "2002", "55");

      assertEquals(1, records.size());
      assertEquals("Jenkins Agent", records.get(0).getRunByName());
      assertEquals("Parent Owner", records.get(0).getAssignedToName());
      assertSingleOwnerAcrossReportSurfaces(records, "Parent Owner");
    }
  }

  @Test
  public void resolvesLegacyDefaultRunByFromDedicatedParentRunByQuery() throws Exception {
    server.createContext("/authentication/sign_in", exchange -> json(exchange, 200, "{}"));
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/runs",
        exchange -> {
          if (idsFromQuery(exchange).contains("101")) {
            json(
                exchange,
                200,
                "{\"data\":[{\"id\":\"101\",\"native_status\":{\"logical_name\":\"passed\"},"
                    + "\"run_by\":{\"name\":\"Jenkins Agent\"}}]}");
          } else {
            // Some Octane versions silently omit unsupported parent relationships.
            json(
                exchange, 200, "{\"data\":[{\"id\":\"55\",\"runs_in_suite\":[{\"id\":\"101\"}]}]}");
          }
        });
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/suite_runs/55",
        exchange -> {
          String query =
              URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
          String parentRunBy =
              query.contains("run_by{id,name}") && !query.contains("default_run_by")
                  ? ",\"run_by\":{\"email\":\"human.owner@example.com\"}"
                  : "";
          json(exchange, 200, "{\"id\":\"55\"" + parentRunBy + "}");
        });
    server.createContext("/authentication/sign_out", exchange -> json(exchange, 200, "{}"));

    try (OctaneClient client = new OctaneClient(baseUrl, "client", Secret.fromString("secret"))) {
      client.authenticate();
      List<RunRecord> records = client.fetchSuiteChildRuns("1001", "2002", List.of("55")).get("55");

      assertEquals(1, records.size());
      assertEquals("human.owner@example.com", records.get(0).getSuiteOwnerName());
      assertEquals("Jenkins Agent", records.get(0).getExecutionActorName());
      assertSingleOwnerAcrossReportSurfaces(records, "human.owner@example.com");
    }
  }

  @Test
  public void ignoresSystemParentRunByAndFallsBackToParentOwner() throws Exception {
    server.createContext("/authentication/sign_in", exchange -> json(exchange, 200, "{}"));
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/runs",
        exchange -> {
          String query =
              URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
          if (query.contains("default_run_by")) {
            json(exchange, 400, "{\"error\":\"Unknown run field default_run_by\"}");
          } else if (idsFromQuery(exchange).contains("101")) {
            json(
                exchange,
                200,
                "{\"data\":[{\"id\":\"101\",\"name\":\"child\","
                    + "\"native_status\":{\"logical_name\":\"passed\"},"
                    + "\"run_by\":{\"name\":\"Jenkins Agent\"}}]}");
          } else {
            json(
                exchange,
                200,
                "{\"data\":[{\"id\":\"55\",\"name\":\"suite\","
                    + "\"run_by\":{\"name\":\"Jenkins Agent\"},"
                    + "\"owner\":{\"email\":\"human.owner@example.com\"},"
                    + "\"runs_in_suite\":[{\"id\":\"101\"}]}]}");
          }
        });
    server.createContext("/authentication/sign_out", exchange -> json(exchange, 200, "{}"));

    try (OctaneClient client = new OctaneClient(baseUrl, "client", Secret.fromString("secret"))) {
      client.authenticate();
      List<RunRecord> records = client.fetchSuiteChildRuns("1001", "2002", "55");

      assertEquals(1, records.size());
      assertEquals("Jenkins Agent", records.get(0).getExecutionActorName());
      assertEquals("human.owner@example.com", records.get(0).getSuiteOwnerName());
    }
  }

  @Test
  public void discoversSuiteRunsByReleaseAndSprintWithEscapedNames() throws Exception {
    AtomicInteger requests = new AtomicInteger();
    server.createContext("/authentication/sign_in", exchange -> json(exchange, 200, "{}"));
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/runs",
        exchange -> {
          requests.incrementAndGet();
          String query =
              URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
          assertTrue(query.contains("fields=id"));
          assertTrue(query.contains("test EQ {subtype EQ ^test_suite^}"));
          assertTrue(query.contains("release EQ {name EQ ^Release \\^2.4^}"));
          assertTrue(query.contains("sprint EQ {name EQ ^Sprint 3^}"));
          json(exchange, 200, "{\"data\":[{\"id\":\"55\"},{\"id\":\"56\"}]}");
        });
    server.createContext("/authentication/sign_out", exchange -> json(exchange, 200, "{}"));

    try (OctaneClient client = new OctaneClient(baseUrl, "client", Secret.fromString("secret"))) {
      client.authenticate();

      assertEquals(
          List.of("55", "56"),
          client.fetchSuiteRunIdsByReleaseAndSprint("1001", "2002", "Release ^2.4", "Sprint 3"));
      assertEquals(1, requests.get());
    }
  }

  @Test
  public void discoversAllSuiteRunsByReleaseWithoutAddingASprintFilter() throws Exception {
    AtomicInteger requests = new AtomicInteger();
    server.createContext("/authentication/sign_in", exchange -> json(exchange, 200, "{}"));
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/runs",
        exchange -> {
          requests.incrementAndGet();
          String query =
              URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
          assertTrue(query.contains("fields=id"));
          assertTrue(query.contains("test EQ {subtype EQ ^test_suite^}"));
          assertTrue(query.contains("release EQ {name EQ ^Kanban Release 2.4^}"));
          assertFalse(query.contains("sprint EQ"));
          json(exchange, 200, "{\"data\":[{\"id\":\"55\"},{\"id\":\"56\"},{\"id\":\"57\"}]}");
        });
    server.createContext("/authentication/sign_out", exchange -> json(exchange, 200, "{}"));

    try (OctaneClient client = new OctaneClient(baseUrl, "client", Secret.fromString("secret"))) {
      client.authenticate();

      assertEquals(
          List.of("55", "56", "57"),
          client.fetchSuiteRunIdsByReleaseAndSprint("1001", "2002", "Kanban Release 2.4", null));
      assertEquals(1, requests.get());
    }
  }

  @Test
  public void releaseOnlyDiscoveryFallbackAlsoOmitsTheSprintFilter() throws Exception {
    AtomicInteger runsRequests = new AtomicInteger();
    AtomicInteger suiteRunRequests = new AtomicInteger();
    server.createContext("/authentication/sign_in", exchange -> json(exchange, 200, "{}"));
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/runs",
        exchange -> {
          runsRequests.incrementAndGet();
          String query =
              URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
          assertTrue(query.contains("release EQ {name EQ ^Kanban Release^}"));
          assertFalse(query.contains("sprint EQ"));
          json(exchange, 400, "{\"description\":\"Unsupported runs relationship\"}");
        });
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/suite_runs",
        exchange -> {
          suiteRunRequests.incrementAndGet();
          String query =
              URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
          assertTrue(query.contains("release EQ {name EQ ^Kanban Release^}"));
          assertFalse(query.contains("test EQ"));
          assertFalse(query.contains("sprint EQ"));
          json(exchange, 200, "{\"data\":[{\"id\":\"58\"}]}");
        });
    server.createContext("/authentication/sign_out", exchange -> json(exchange, 200, "{}"));

    try (OctaneClient client = new OctaneClient(baseUrl, "client", Secret.fromString("secret"))) {
      client.authenticate();

      assertEquals(
          List.of("58"),
          client.fetchSuiteRunIdsByReleaseAndSprint("1001", "2002", "Kanban Release", ""));
      assertEquals(1, runsRequests.get());
      assertEquals(1, suiteRunRequests.get());
    }
  }

  @Test
  public void tolerantSuitePollingOmitsConfirmedMissingRuns() throws Exception {
    server.createContext("/authentication/sign_in", exchange -> json(exchange, 200, "{}"));
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/runs",
        exchange -> {
          String query =
              URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
          if (query.contains("id EQ 55") && query.contains("id EQ 99")) {
            json(
                exchange,
                200,
                "{\"data\":[{\"id\":\"55\",\"name\":\"suite\","
                    + "\"native_status\":{\"logical_name\":\"passed\"}}]}");
          } else {
            json(
                exchange,
                200,
                "{\"data\":[{\"id\":\"55\",\"name\":\"suite\","
                    + "\"native_status\":{\"logical_name\":\"passed\"}}]}");
          }
        });
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/suite_runs/99",
        exchange -> json(exchange, 404, "{\"description\":\"HTTP 404 Not Found\"}"));
    server.createContext("/authentication/sign_out", exchange -> json(exchange, 200, "{}"));

    try (OctaneClient client = new OctaneClient(baseUrl, "client", Secret.fromString("secret"))) {
      client.authenticate();

      Map<String, List<RunRecord>> runs =
          client.fetchAvailableSuiteChildRuns("1001", "2002", List.of("55", "99"));

      assertEquals(List.of("55"), List.copyOf(runs.keySet()));
      assertEquals(1, runs.get("55").size());
    }
  }

  @Test
  public void repeatedDiscoveryAllowsSuitePoolTotalsToRiseAndFall() throws Exception {
    AtomicInteger discoveryPoll = new AtomicInteger();
    server.createContext("/authentication/sign_in", exchange -> json(exchange, 200, "{}"));
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/runs",
        exchange -> {
          String query =
              URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
          if (query.contains("release EQ")) {
            int poll = discoveryPoll.incrementAndGet();
            if (poll == 1) {
              json(exchange, 200, "{\"data\":[{\"id\":\"55\"}]}");
            } else if (poll == 2) {
              json(exchange, 200, "{\"data\":[{\"id\":\"55\"},{\"id\":\"56\"}]}");
            } else {
              json(exchange, 200, "{\"data\":[{\"id\":\"56\"}]}");
            }
            return;
          }
          List<String> ids = idsFromQuery(exchange);
          StringBuilder body = new StringBuilder("{\"data\":[");
          for (int index = 0; index < ids.size(); index++) {
            if (index > 0) {
              body.append(',');
            }
            body.append("{\"id\":\"")
                .append(ids.get(index))
                .append("\",\"native_status\":{\"logical_name\":\"planned\"}}");
          }
          json(exchange, 200, body.append("]}").toString());
        });
    server.createContext("/authentication/sign_out", exchange -> json(exchange, 200, "{}"));

    try (OctaneClient client = new OctaneClient(baseUrl, "client", Secret.fromString("secret"))) {
      client.authenticate();

      assertEquals(List.of("55"), discoverAvailableIds(client));
      assertEquals(List.of("55", "56"), discoverAvailableIds(client));
      assertEquals(List.of("56"), discoverAvailableIds(client));
    }
  }

  @Test
  public void releaseDiscoveryFindsNewSuitesAfterTheSelectionWasEmpty() throws Exception {
    AtomicInteger discoveryPoll = new AtomicInteger();
    server.createContext("/authentication/sign_in", exchange -> json(exchange, 200, "{}"));
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/runs",
        exchange -> {
          String query =
              URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
          if (query.contains("release EQ")) {
            int poll = discoveryPoll.incrementAndGet();
            if (poll == 1) {
              json(exchange, 200, "{\"data\":[{\"id\":\"55\"}]}");
            } else if (poll == 2) {
              json(exchange, 200, "{\"data\":[]}");
            } else {
              json(exchange, 200, "{\"data\":[{\"id\":\"56\"}]}");
            }
            return;
          }
          List<String> ids = idsFromQuery(exchange);
          StringBuilder body = new StringBuilder("{\"data\":[");
          for (int index = 0; index < ids.size(); index++) {
            if (index > 0) {
              body.append(',');
            }
            body.append("{\"id\":\"")
                .append(ids.get(index))
                .append("\",\"native_status\":{\"logical_name\":\"planned\"}}");
          }
          json(exchange, 200, body.append("]}").toString());
        });
    server.createContext("/authentication/sign_out", exchange -> json(exchange, 200, "{}"));

    try (OctaneClient client = new OctaneClient(baseUrl, "client", Secret.fromString("secret"))) {
      client.authenticate();

      assertEquals(List.of("55"), discoverAvailableIds(client));
      assertEquals(List.of(), discoverAvailableIds(client));
      assertEquals(List.of("56"), discoverAvailableIds(client));
    }
  }

  @Test
  public void rejectsUnsafeEntityIdsBeforeBuildingOctaneQueries() throws Exception {
    server.createContext("/authentication/sign_in", exchange -> json(exchange, 200, "{}"));
    server.createContext("/authentication/sign_out", exchange -> json(exchange, 200, "{}"));

    try (OctaneClient client = new OctaneClient(baseUrl, "client", Secret.fromString("secret"))) {
      client.authenticate();
      try {
        client.fetchSuiteChildRuns("1001", "2002", "55) OR (id GT 0");
      } catch (IllegalArgumentException e) {
        assertTrue(e.getMessage().contains("unsafe entity ID"));
        return;
      }
    }
    throw new AssertionError("Expected an unsafe entity ID to be rejected.");
  }

  @Test
  public void stopsPaginationWhenAFullPageMakesNoRequestedIdProgress() throws Exception {
    AtomicInteger requests = new AtomicInteger();
    server.createContext("/authentication/sign_in", exchange -> json(exchange, 200, "{}"));
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/runs",
        exchange -> {
          requests.incrementAndGet();
          StringBuilder body = new StringBuilder("{\"data\":[");
          for (int index = 0; index < 200; index++) {
            if (index > 0) {
              body.append(',');
            }
            body.append("{\"id\":\"unrelated-")
                .append(index)
                .append("\",\"native_status\":{\"logical_name\":\"passed\"}}");
          }
          body.append("]}");
          json(exchange, 200, body.toString());
        });
    server.createContext("/authentication/sign_out", exchange -> json(exchange, 200, "{}"));

    try (OctaneClient client = new OctaneClient(baseUrl, "client", Secret.fromString("secret"))) {
      client.authenticate();
      assertTrue(client.fetchScopedRuns("1001", "2002", List.of("101"), "").isEmpty());
      assertEquals(1, requests.get());
    }
  }

  @Test
  public void boundsJsonResponseBodies() throws Exception {
    server.createContext(
        "/authentication/sign_in",
        exchange -> json(exchange, 200, "x".repeat(OctaneClient.MAX_JSON_RESPONSE_BYTES + 1)));

    try (OctaneClient client = new OctaneClient(baseUrl, "client", Secret.fromString("secret"))) {
      try {
        client.authenticate();
      } catch (IOException e) {
        assertTrue(e.getMessage().contains("byte safety limit"));
        return;
      }
    }
    throw new AssertionError("Expected an oversized response body to be rejected.");
  }

  @Test
  public void redactsSensitiveValuesFromOctaneFailureMessages() throws Exception {
    server.createContext(
        "/authentication/sign_in",
        exchange ->
            json(
                exchange,
                401,
                "{\"client_secret\":\"server-echoed-secret\",\"message\":\"denied\"}"));

    try (OctaneClient client =
        new OctaneClient(baseUrl, "client", Secret.fromString("request-secret"))) {
      try {
        client.authenticate();
      } catch (AbortException e) {
        assertFalse(e.getMessage().contains("server-echoed-secret"));
        assertTrue(e.getMessage().contains("Authentication response body omitted"));
        return;
      }
    }
    throw new AssertionError("Expected authentication to fail.");
  }

  @Test
  public void masksKnownCredentialsAndCookiesEvenWhenEchoedUnderUnrelatedFields() throws Exception {
    String password = "test-secret-with-\"-quote";
    String cookie = "test-session-token";
    server.createContext(
        "/authentication/sign_in",
        exchange -> {
          exchange.getResponseHeaders().set("Set-Cookie", "session=" + cookie + "; Path=/");
          json(exchange, 200, "{}");
        });
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/",
        exchange ->
            json(
                exchange,
                403,
                new tools.jackson.databind.ObjectMapper()
                    .writeValueAsString(Map.of("message", password + " " + cookie))));
    server.createContext("/authentication/sign_out", exchange -> json(exchange, 200, "{}"));
    try (OctaneClient client = new OctaneClient(baseUrl, "client", Secret.fromString(password))) {
      client.authenticate();
      var field = OctaneClient.class.getDeclaredField("cookieHeader");
      assertEquals(hudson.util.Secret.class, field.getType());
      field.setAccessible(true);
      hudson.util.Secret storedCookie = (hudson.util.Secret) field.get(client);
      assertFalse(storedCookie.getEncryptedValue().contains(cookie));
      AbortException failure =
          assertThrows(
              AbortException.class, () -> client.fetchSuiteChildRuns("1001", "2002", "55"));
      assertFalse(failure.getMessage().contains("test-secret"));
      assertFalse(failure.getMessage().contains(cookie));
      assertTrue(failure.getMessage(), failure.getMessage().contains("***"));
    }
  }

  @Test
  public void malformedResponsesCannotLeakCredentialsThroughParserExceptionCauses()
      throws Exception {
    String password = "ReflectedSecretNotValidJson";
    server.createContext("/authentication/sign_in", exchange -> json(exchange, 200, "{}"));
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/defects",
        exchange -> json(exchange, 200, password));
    server.createContext("/authentication/sign_out", exchange -> json(exchange, 200, "{}"));
    try (OctaneClient client = new OctaneClient(baseUrl, "client", Secret.fromString(password))) {
      client.authenticate();
      IOException failure =
          assertThrows(
              IOException.class, () -> client.fetchDefectsByIds("1001", "2002", List.of("123"), 1));
      StringWriter stack = new StringWriter();
      failure.printStackTrace(new PrintWriter(stack));
      assertFalse(stack.toString().contains(password));
      assertTrue(failure.getMessage().contains("malformed JSON"));
      assertTrue(failure.getMessage().contains("***"));
    }
  }

  @Test
  public void reAuthenticatesOnceAfterUnauthorizedResponse() throws Exception {
    AtomicInteger authCount = new AtomicInteger();
    AtomicInteger runCount = new AtomicInteger();
    server.createContext(
        "/authentication/sign_in",
        exchange -> {
          authCount.incrementAndGet();
          json(exchange, 200, "{}");
        });
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/runs",
        exchange -> {
          int count = runCount.incrementAndGet();
          if (count == 1) {
            json(exchange, 401, "{\"error\":\"expired\"}");
          } else {
            json(
                exchange,
                200,
                "{\"data\":[{\"id\":\"55\",\"name\":\"suite\","
                    + "\"native_status\":{\"logical_name\":\"passed\"}}]}");
          }
        });
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/suite_runs/55",
        exchange ->
            json(
                exchange,
                200,
                "{\"id\":\"55\",\"name\":\"suite\","
                    + "\"native_status\":{\"logical_name\":\"passed\"}}"));
    server.createContext("/authentication/sign_out", exchange -> json(exchange, 200, "{}"));

    try (OctaneClient client = new OctaneClient(baseUrl, "client", Secret.fromString("secret"))) {
      client.authenticate();
      List<RunRecord> records = client.fetchSuiteChildRuns("1001", "2002", "55");

      assertEquals(2, authCount.get());
      assertEquals(1, records.size());
      assertEquals("passed", records.get(0).getStatus());
    }
  }

  @Test
  public void testsWorkspaceAccessWithAuthenticatedCookie() throws Exception {
    server.createContext("/authentication/sign_in", exchange -> json(exchange, 200, "{}"));
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/runs",
        exchange -> {
          assertEquals("fields=id&limit=1", exchange.getRequestURI().getRawQuery());
          assertTrue(exchange.getRequestHeaders().getFirst("Cookie").contains("LWSSO_COOKIE_KEY"));
          assertEquals("true", exchange.getRequestHeaders().getFirst("ALM-OCTANE-TECH-PREVIEW"));
          json(exchange, 200, "{\"data\":[]}");
        });
    server.createContext("/authentication/sign_out", exchange -> json(exchange, 200, "{}"));

    try (OctaneClient client = new OctaneClient(baseUrl, "client", Secret.fromString("secret"))) {
      client.authenticate();

      assertEquals(200, client.testWorkspaceAccess("1001", "2002"));
    }
  }

  @Test
  public void fallsBackToPluralSuiteRunsEndpointWithTechnicalPreviewHeader() throws Exception {
    server.createContext("/authentication/sign_in", exchange -> json(exchange, 200, "{}"));
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/runs",
        exchange -> json(exchange, 400, "{\"error\":\"unsupported runs query\"}"));
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/suite_runs/55",
        exchange -> {
          assertEquals("true", exchange.getRequestHeaders().getFirst("ALM-OCTANE-TECH-PREVIEW"));
          json(
              exchange,
              200,
              "{\"id\":\"55\",\"name\":\"suite\","
                  + "\"native_status\":{\"logical_name\":\"passed\"}}");
        });
    server.createContext("/authentication/sign_out", exchange -> json(exchange, 200, "{}"));

    try (OctaneClient client = new OctaneClient(baseUrl, "client", Secret.fromString("secret"))) {
      client.authenticate();
      List<RunRecord> records = client.fetchSuiteChildRuns("1001", "2002", "55");

      assertEquals(1, records.size());
      assertEquals("55", records.get(0).getId());
      assertEquals("passed", records.get(0).getStatus());
    }
  }

  @Test
  public void reportsSuiteRunLookupRequestDetails() throws Exception {
    server.createContext("/authentication/sign_in", exchange -> json(exchange, 200, "{}"));
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/runs",
        exchange -> json(exchange, 400, "{\"error\":\"bad runs query\"}"));
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/suite_runs/55",
        exchange -> json(exchange, 400, "{\"error\":\"bad suite run\"}"));
    server.createContext("/authentication/sign_out", exchange -> json(exchange, 200, "{}"));

    try (OctaneClient client = new OctaneClient(baseUrl, "client", Secret.fromString("secret"))) {
      client.authenticate();

      try {
        client.fetchSuiteChildRuns("1001", "2002", "55");
      } catch (AbortException e) {
        String message = e.getMessage();
        assertTrue(message.contains("Runs collection lookup failed"));
        assertTrue(message.contains("/api/shared_spaces/1001/workspaces/2002/runs?"));
        assertTrue(message.contains("bad runs query"));
        assertTrue(message.contains("/api/shared_spaces/1001/workspaces/2002/suite_runs/55?"));
        assertTrue(message.contains("bad suite run"));
        return;
      }
    }
    throw new AssertionError("Expected suite run lookup to fail.");
  }

  @Test
  public void reportsFriendlyMissingSuiteRunMessageForWorkspaceMismatch() throws Exception {
    server.createContext("/authentication/sign_in", exchange -> json(exchange, 200, "{}"));
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/runs",
        exchange -> json(exchange, 200, "{\"data\":[]}"));
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/suite_runs/55",
        exchange -> json(exchange, 404, "{\"description\":\"HTTP 404 Not Found\"}"));
    server.createContext("/authentication/sign_out", exchange -> json(exchange, 200, "{}"));

    try (OctaneClient client = new OctaneClient(baseUrl, "client", Secret.fromString("secret"))) {
      client.authenticate();

      try {
        client.fetchSuiteChildRuns("1001", "2002", "55");
      } catch (AbortException e) {
        String message = e.getMessage();
        assertTrue(message.contains("suite run 55"));
        assertTrue(message.contains("shared space 1001 / workspace 2002"));
        assertTrue(message.contains("match the Octane workspace"));
        assertFalse(message.contains("/api/shared_spaces/1001/workspaces/2002"));
        assertFalse(message.contains("Response body"));
        return;
      }
    }
    throw new AssertionError("Expected suite run lookup to fail.");
  }

  @Test
  public void doesNotMisreportFallbackFailureAsMissingBecauseRunsEndpointWasNotFound()
      throws Exception {
    server.createContext("/authentication/sign_in", exchange -> json(exchange, 200, "{}"));
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/runs",
        exchange -> json(exchange, 404, "{\"error\":\"runs endpoint unavailable\"}"));
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/suite_runs/55",
        exchange -> json(exchange, 400, "{\"error\":\"unsupported suite fields\"}"));
    server.createContext("/authentication/sign_out", exchange -> json(exchange, 200, "{}"));

    try (OctaneClient client = new OctaneClient(baseUrl, "client", Secret.fromString("secret"))) {
      client.authenticate();

      try {
        client.fetchSuiteChildRuns("1001", "2002", "55");
      } catch (AbortException e) {
        assertTrue(e.getMessage().contains("Runs collection lookup failed"));
        assertTrue(e.getMessage().contains("runs endpoint unavailable"));
        assertTrue(e.getMessage().contains("unsupported suite fields"));
        return;
      }
    }
    throw new AssertionError("Expected suite run lookup to fail.");
  }

  @Test
  public void topologyCacheKeepsIdenticalSuiteIdsIsolatedByWorkspace() throws Exception {
    AtomicInteger workspaceOneRequests = new AtomicInteger();
    AtomicInteger workspaceTwoRequests = new AtomicInteger();
    server.createContext("/authentication/sign_in", exchange -> json(exchange, 200, "{}"));
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2001/runs",
        exchange -> {
          workspaceOneRequests.incrementAndGet();
          if (idsFromQuery(exchange).contains("run-1")) {
            json(
                exchange,
                200,
                "{\"data\":[{\"id\":\"run-1\","
                    + "\"native_status\":{\"logical_name\":\"passed\"}}]}");
          } else {
            json(
                exchange,
                200,
                "{\"data\":[{\"id\":\"55\",\"owner\":{\"name\":\"Owner One\"},"
                    + "\"runs_in_suite\":[{\"id\":\"run-1\"}]}]}");
          }
        });
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/runs",
        exchange -> {
          workspaceTwoRequests.incrementAndGet();
          if (idsFromQuery(exchange).contains("run-2")) {
            json(
                exchange,
                200,
                "{\"data\":[{\"id\":\"run-2\","
                    + "\"native_status\":{\"logical_name\":\"passed\"}}]}");
          } else {
            json(
                exchange,
                200,
                "{\"data\":[{\"id\":\"55\",\"owner\":{\"name\":\"Owner Two\"},"
                    + "\"runs_in_suite\":[{\"id\":\"run-2\"}]}]}");
          }
        });
    server.createContext("/authentication/sign_out", exchange -> json(exchange, 200, "{}"));

    try (OctaneClient client = new OctaneClient(baseUrl, "client", Secret.fromString("secret"))) {
      client.authenticate();

      Map<String, List<RunRecord>> first =
          client.fetchSuiteChildRuns("1001", "2001", List.of("55"));
      Map<String, List<RunRecord>> second =
          client.fetchSuiteChildRuns("1001", "2002", List.of("55"));

      assertEquals("run-1", first.get("55").get(0).getId());
      assertEquals("run-2", second.get("55").get(0).getId());
      assertEquals("Owner One", first.get("55").get(0).getAssignedToName());
      assertEquals("Owner Two", second.get("55").get(0).getAssignedToName());
      assertTrue(workspaceOneRequests.get() > 0);
      assertTrue(workspaceTwoRequests.get() > 0);
    }
  }

  @Test
  public void passesMultipleProductAreaIdsThroughScopeQuery() throws Exception {
    server.createContext("/authentication/sign_in", exchange -> json(exchange, 200, "{}"));
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/runs",
        exchange -> {
          String query =
              URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
          assertTrue(query.contains("(id EQ 101||id EQ 102)"));
          assertTrue(query.contains("test={((product_areas={id=1004||id=1005}))}"));
          json(exchange, 200, "{\"data\":[]}");
        });
    server.createContext("/authentication/sign_out", exchange -> json(exchange, 200, "{}"));

    try (OctaneClient client = new OctaneClient(baseUrl, "client", Secret.fromString("secret"))) {
      client.authenticate();

      List<RunRecord> records =
          client.fetchScopedRuns(
              "1001", "2002", List.of("101", "102"), "test={((product_areas={id=1004||id=1005}))}");

      assertEquals(0, records.size());
    }
  }

  @Test
  public void fetchesKnownDefectsByIdForLedgerRefresh() throws Exception {
    server.createContext("/authentication/sign_in", exchange -> json(exchange, 200, "{}"));
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/defects",
        exchange -> {
          String query =
              URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
          assertTrue(query.contains("id EQ 901||id EQ 902"));
          assertTrue(query.contains("description"));
          json(
              exchange,
              200,
              "{\"data\":["
                  + "{\"id\":\"901\",\"name\":\"closed later\","
                  + "\"severity\":{\"logical_name\":\"high\"},"
                  + "\"phase\":{\"logical_name\":\"closed\"}},"
                  + "{\"id\":\"902\",\"name\":\"DEF-902\","
                  + "\"description\":\"still open\","
                  + "\"severity\":{\"logical_name\":\"critical\"},"
                  + "\"phase\":{\"logical_name\":\"opened\"}}"
                  + "]}");
        });
    server.createContext("/authentication/sign_out", exchange -> json(exchange, 200, "{}"));

    try (OctaneClient client = new OctaneClient(baseUrl, "client", Secret.fromString("secret"))) {
      client.authenticate();
      List<DefectRecord> records =
          client.fetchDefectsByIds("1001", "2002", List.of("901", "902"), 1000);

      assertEquals(2, records.size());
      assertEquals("901", records.get(0).getId());
      assertFalse(records.get(0).isOpen());
      assertEquals("902", records.get(1).getId());
      assertTrue(records.get(1).isOpen());
      assertEquals("DEF-902:\nstill open", records.get(1).getDisplayDescription());
    }
  }

  @Test
  public void reusesCompatibleDefectFieldProfileForLaterRequests() throws Exception {
    AtomicInteger extendedFieldRequests = new AtomicInteger();
    AtomicInteger compatibleFieldRequests = new AtomicInteger();
    server.createContext("/authentication/sign_in", exchange -> json(exchange, 200, "{}"));
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/defects",
        exchange -> {
          String query =
              URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
          if (query.contains("detected_in_run{id,name}")) {
            extendedFieldRequests.incrementAndGet();
            json(exchange, 400, "{\"error\":\"platform.unknown_field\"}");
            return;
          }
          compatibleFieldRequests.incrementAndGet();
          String id = query.contains("id EQ 902") ? "902" : "901";
          json(
              exchange,
              200,
              "{\"data\":[{\"id\":\"" + id + "\",\"phase\":{\"logical_name\":\"opened\"}}]}");
        });
    server.createContext("/authentication/sign_out", exchange -> json(exchange, 200, "{}"));

    try (OctaneClient client = new OctaneClient(baseUrl, "client", Secret.fromString("secret"))) {
      client.authenticate();

      assertEquals(1, client.fetchDefectsByIds("1001", "2002", List.of("901"), 10).size());
      assertEquals(1, client.fetchDefectsByIds("1001", "2002", List.of("902"), 10).size());

      assertEquals(1, extendedFieldRequests.get());
      assertEquals(2, compatibleFieldRequests.get());
    }
  }

  @Test
  public void reusesCompatibleSuiteFieldProfileAcrossTopologyBatches() throws Exception {
    AtomicInteger incompatibleFieldRequests = new AtomicInteger();
    AtomicInteger compatibleFieldRequests = new AtomicInteger();
    server.createContext("/authentication/sign_in", exchange -> json(exchange, 200, "{}"));
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/runs",
        exchange -> {
          List<String> ids = idsFromQuery(exchange);
          String query =
              URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
          if (ids.stream().allMatch(id -> id.startsWith("suite-"))) {
            if (query.contains("default_run_by")) {
              incompatibleFieldRequests.incrementAndGet();
              json(exchange, 400, "{\"error\":\"platform.unknown_field\"}");
              return;
            }
            if (query.contains("assigned_to")) {
              compatibleFieldRequests.incrementAndGet();
            }
            String suiteId = ids.get(0);
            String suffix = suiteId.substring("suite-".length());
            json(
                exchange,
                200,
                "{\"data\":[{\"id\":\""
                    + suiteId
                    + "\",\"assigned_to\":{\"name\":\"Ada Owner\"},"
                    + "\"runs_in_suite\":[{\"id\":\"run-"
                    + suffix
                    + "\"}]}]}");
            return;
          }
          String runId = ids.get(0);
          json(
              exchange,
              200,
              "{\"data\":[{\"id\":\""
                  + runId
                  + "\",\"native_status\":{\"logical_name\":\"passed\"}}]}");
        });
    server.createContext("/authentication/sign_out", exchange -> json(exchange, 200, "{}"));

    try (OctaneClient client = new OctaneClient(baseUrl, "client", Secret.fromString("secret"))) {
      client.authenticate();

      Map<String, List<RunRecord>> first =
          client.fetchSuiteChildRuns("1001", "2002", List.of("suite-1"));
      Map<String, List<RunRecord>> second =
          client.fetchSuiteChildRuns("1001", "2002", List.of("suite-2"));

      assertEquals("Ada Owner", first.get("suite-1").get(0).getSuiteOwnerName());
      assertEquals("Ada Owner", second.get("suite-2").get(0).getSuiteOwnerName());
      assertEquals(1, incompatibleFieldRequests.get());
      assertEquals(2, compatibleFieldRequests.get());
    }
  }

  @Test
  public void ignoresOnlyUnsupportedDefectRelationsAndKeepsSupportedResults() throws Exception {
    server.createContext("/authentication/sign_in", exchange -> json(exchange, 200, "{}"));
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/defects",
        exchange -> {
          String query =
              URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
          if (query.contains("run EQ")) {
            json(
                exchange,
                200,
                "{\"data\":[{\"id\":\"901\",\"name\":\"linked defect\","
                    + "\"severity\":{\"logical_name\":\"high\"},"
                    + "\"phase\":{\"logical_name\":\"opened\"}}]}");
          } else {
            json(exchange, 400, "{\"error\":\"platform.unknown_field\"}");
          }
        });
    server.createContext("/authentication/sign_out", exchange -> json(exchange, 200, "{}"));
    Map<String, List<RunRecord>> suiteRuns =
        Map.of(
            "suite-1",
            List.of(new RunRecord("run-1", "child", "failed", "Tester", "test-1", "Test", "", "")));

    try (OctaneClient client = new OctaneClient(baseUrl, "client", Secret.fromString("secret"))) {
      client.authenticate();
      List<DefectRecord> defects = client.fetchLinkedDefects("1001", "2002", suiteRuns, "", 100);

      assertEquals(1, defects.size());
      assertEquals("901", defects.get(0).getId());
    }
  }

  @Test
  public void propagatesDefectQueryFailuresInsteadOfReturningPartialData() throws Exception {
    server.createContext("/authentication/sign_in", exchange -> json(exchange, 200, "{}"));
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/defects",
        exchange -> json(exchange, 400, "{\"error\":\"query failed\"}"));
    server.createContext("/authentication/sign_out", exchange -> json(exchange, 200, "{}"));
    Map<String, List<RunRecord>> suiteRuns =
        Map.of(
            "suite-1",
            List.of(new RunRecord("run-1", "child", "failed", "Tester", "test-1", "Test", "", "")));

    try (OctaneClient client = new OctaneClient(baseUrl, "client", Secret.fromString("secret"))) {
      client.authenticate();
      try {
        client.fetchLinkedDefects("1001", "2002", suiteRuns, "", 100);
      } catch (IOException e) {
        assertTrue(e.getMessage().contains("HTTP 400"));
        assertTrue(e.getMessage().contains("query failed"));
        return;
      }
    }
    throw new AssertionError("Expected the failed defect query to fail the poll.");
  }

  @Test
  public void bulkFetchesFiveHundredSuiteTopologiesWithBoundedFanoutAndCacheReuse()
      throws Exception {
    AtomicInteger topologyRequests = new AtomicInteger();
    AtomicInteger childRequests = new AtomicInteger();
    server.createContext("/authentication/sign_in", exchange -> json(exchange, 200, "{}"));
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/runs",
        exchange -> {
          List<String> ids = idsFromQuery(exchange);
          boolean topology = !ids.isEmpty() && ids.get(0).startsWith("suite-");
          if (topology) {
            topologyRequests.incrementAndGet();
          } else {
            childRequests.incrementAndGet();
          }
          StringBuilder body = new StringBuilder("{\"data\":[");
          for (int index = 0; index < ids.size(); index++) {
            if (index > 0) {
              body.append(',');
            }
            String id = ids.get(index);
            body.append("{\"id\":\"").append(id).append("\",\"name\":\"").append(id).append('"');
            if (topology) {
              // Keep this topology-cache test independent of per-suite owner recovery and TTL.
              body.append(",\"default_run_by\":{\"name\":\"Assigned Tester\"}");
              body.append(",\"runs_in_suite\":[{\"id\":\"run-")
                  .append(id.substring("suite-".length()))
                  .append("\"}]");
            } else {
              body.append(",\"native_status\":{\"logical_name\":\"passed\"}");
            }
            body.append('}');
          }
          body.append("]}");
          json(exchange, 200, body.toString());
        });
    server.createContext("/authentication/sign_out", exchange -> json(exchange, 200, "{}"));
    List<String> suiteIds = new ArrayList<>();
    for (int index = 0; index < 500; index++) {
      suiteIds.add("suite-" + index);
    }

    try (OctaneClient client = new OctaneClient(baseUrl, "client", Secret.fromString("secret"))) {
      client.authenticate();
      Map<String, List<RunRecord>> first = client.fetchSuiteChildRuns("1001", "2002", suiteIds);
      int topologyAfterFirstFetch = topologyRequests.get();
      Map<String, List<RunRecord>> second = client.fetchSuiteChildRuns("1001", "2002", suiteIds);

      assertEquals(500, first.size());
      assertEquals("run-499", first.get("suite-499").get(0).getId());
      assertEquals(500, second.size());
      assertEquals(13, topologyAfterFirstFetch);
      assertEquals(topologyAfterFirstFetch, topologyRequests.get());
      assertEquals(26, childRequests.get());
      assertTrue(topologyRequests.get() + childRequests.get() <= 39);
      assertTrue(OctaneSuiteTopologyCache.metrics().hits() >= 500L);
    }
  }

  @Test
  public void negotiatesChildRunFieldProfileOnceAcrossParallelChunks() throws Exception {
    AtomicInteger richChildRequests = new AtomicInteger();
    AtomicInteger safeChildRequests = new AtomicInteger();
    server.createContext("/authentication/sign_in", exchange -> json(exchange, 200, "{}"));
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/runs",
        exchange -> {
          List<String> ids = idsFromQuery(exchange);
          String query =
              URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
          if (ids.equals(List.of("suite-1"))) {
            StringBuilder body =
                new StringBuilder(
                    "{\"data\":[{\"id\":\"suite-1\","
                        + "\"default_run_by\":{\"name\":\"Ada Owner\"},"
                        + "\"runs_in_suite\":[");
            for (int index = 0; index < 81; index++) {
              if (index > 0) {
                body.append(',');
              }
              body.append("{\"id\":\"run-").append(index).append("\"}");
            }
            json(exchange, 200, body.append("]}]} ").toString());
            return;
          }
          if (query.contains("native_tester")) {
            richChildRequests.incrementAndGet();
            json(exchange, 400, "{\"error\":\"platform.unknown_field\"}");
            return;
          }
          safeChildRequests.incrementAndGet();
          StringBuilder body = new StringBuilder("{\"data\":[");
          for (int index = 0; index < ids.size(); index++) {
            if (index > 0) {
              body.append(',');
            }
            body.append("{\"id\":\"")
                .append(ids.get(index))
                .append("\",\"native_status\":{\"logical_name\":\"passed\"}}");
          }
          json(exchange, 200, body.append("]}").toString());
        });
    server.createContext("/authentication/sign_out", exchange -> json(exchange, 200, "{}"));

    try (OctaneClient client = new OctaneClient(baseUrl, "client", Secret.fromString("secret"))) {
      client.authenticate();

      Map<String, List<RunRecord>> records =
          client.fetchSuiteChildRuns("1001", "2002", List.of("suite-1"));

      assertEquals(81, records.get("suite-1").size());
      assertEquals(1, richChildRequests.get());
      assertEquals(3, safeChildRequests.get());
    }
  }

  @Test
  public void cutsFiveHundredSuiteFiftyChildRunFanoutByMoreThanHalf() throws Exception {
    AtomicInteger topologyRequests = new AtomicInteger();
    AtomicInteger childRequests = new AtomicInteger();
    server.createContext("/authentication/sign_in", exchange -> json(exchange, 200, "{}"));
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/runs",
        exchange -> {
          try {
            Thread.sleep(2L);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          List<String> ids = idsFromQuery(exchange);
          boolean topology = !ids.isEmpty() && ids.get(0).startsWith("suite-");
          if (topology) {
            topologyRequests.incrementAndGet();
          } else {
            childRequests.incrementAndGet();
          }
          StringBuilder body = new StringBuilder("{\"data\":[");
          for (int index = 0; index < ids.size(); index++) {
            if (index > 0) {
              body.append(',');
            }
            String id = ids.get(index);
            body.append("{\"id\":\"").append(id).append("\",\"name\":\"").append(id).append('"');
            if (topology) {
              body.append(",\"runs_in_suite\":[");
              for (int child = 0; child < 50; child++) {
                if (child > 0) {
                  body.append(',');
                }
                body.append("{\"id\":\"run-")
                    .append(id.substring("suite-".length()))
                    .append('-')
                    .append(child)
                    .append("\"}");
              }
              body.append(']');
            } else {
              body.append(",\"native_status\":{\"logical_name\":\"passed\"}");
            }
            body.append('}');
          }
          body.append("]}");
          json(exchange, 200, body.toString());
        });
    server.createContext("/authentication/sign_out", exchange -> json(exchange, 200, "{}"));
    List<String> suiteIds = new ArrayList<>();
    for (int index = 0; index < 500; index++) {
      suiteIds.add("suite-" + index);
    }

    try (OctaneClient client = new OctaneClient(baseUrl, "client", Secret.fromString("secret"))) {
      client.authenticate();
      Map<String, List<RunRecord>> runs = client.fetchSuiteChildRuns("1001", "2002", suiteIds);

      assertEquals(500, runs.size());
      assertEquals(50, runs.get("suite-499").size());
      assertEquals(13, topologyRequests.get());
      assertEquals(625, childRequests.get());
      int legacySuiteAndChildRequests = 1_500;
      int currentSuiteAndChildRequests = topologyRequests.get() + childRequests.get();
      assertTrue(currentSuiteAndChildRequests * 2 < legacySuiteAndChildRequests);
      System.out.printf(
          "Octane request acceptance: suites=500 childRunsPerSuite=50 legacyRequests=%d "
              + "currentRequests=%d%n",
          legacySuiteAndChildRequests, currentSuiteAndChildRequests);
    }
  }

  @Test(timeout = 10_000L)
  public void prefetchesOnlySelectedSuiteAndPollsThreeThousandChildrenInParallel()
      throws Exception {
    int childCount = 3_000;
    AtomicInteger topologyRequests = new AtomicInteger();
    AtomicInteger childRequests = new AtomicInteger();
    AtomicInteger childRequestsInFlight = new AtomicInteger();
    AtomicInteger maximumChildRequestsInFlight = new AtomicInteger();
    AtomicBoolean invalidTopologyScope = new AtomicBoolean();
    AtomicBoolean nonSparseChildFields = new AtomicBoolean();
    Set<String> requestedChildIds = ConcurrentHashMap.newKeySet();
    server.createContext("/authentication/sign_in", exchange -> json(exchange, 200, "{}"));
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/runs",
        exchange -> {
          List<String> ids = idsFromQuery(exchange);
          boolean topology = ids.contains("suite-current");
          if (topology) {
            topologyRequests.incrementAndGet();
            if (!ids.equals(List.of("suite-current"))) {
              invalidTopologyScope.set(true);
            }
            StringBuilder body =
                new StringBuilder(
                    "{\"data\":[{\"id\":\"suite-current\","
                        + "\"default_run_by\":{\"name\":\"Scale Owner\"},"
                        + "\"runs_in_suite\":[");
            for (int index = 0; index < childCount; index++) {
              if (index > 0) {
                body.append(',');
              }
              body.append("{\"id\":\"run-").append(index).append("\"}");
            }
            body.append("]}]}");
            json(exchange, 200, body.toString());
            return;
          }

          childRequests.incrementAndGet();
          int inFlight = childRequestsInFlight.incrementAndGet();
          maximumChildRequestsInFlight.accumulateAndGet(inFlight, Math::max);
          try {
            String decodedQuery =
                URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
            if (!decodedQuery.contains("fields=id,name,native_status")
                || !decodedQuery.contains("status{logical_name,name}")
                || !decodedQuery.contains("run_by{id,name}")
                || !decodedQuery.contains("subtype")
                || decodedQuery.contains("runs_in_suite")) {
              nonSparseChildFields.set(true);
            }
            requestedChildIds.addAll(ids);
            try {
              Thread.sleep(30L);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            StringBuilder body = new StringBuilder("{\"data\":[");
            for (int index = 0; index < ids.size(); index++) {
              if (index > 0) {
                body.append(',');
              }
              body.append("{\"id\":\"")
                  .append(ids.get(index))
                  .append("\",\"native_status\":{\"logical_name\":\"passed\"},")
                  .append("\"run_by\":{\"name\":\"Jenkins Agent\"},")
                  .append("\"subtype\":\"manual_run\"}");
            }
            body.append("]}");
            json(exchange, 200, body.toString());
          } finally {
            childRequestsInFlight.decrementAndGet();
          }
        });
    server.createContext("/authentication/sign_out", exchange -> json(exchange, 200, "{}"));

    long startedAt = System.nanoTime();
    try (OctaneClient client = new OctaneClient(baseUrl, "client", Secret.fromString("secret"))) {
      client.authenticate();
      Map<String, List<RunRecord>> result =
          client.fetchSuiteChildRuns("1001", "2002", List.of("suite-current"));
      long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

      assertEquals(1, topologyRequests.get());
      assertEquals(75, childRequests.get());
      assertFalse(invalidTopologyScope.get());
      assertFalse(nonSparseChildFields.get());
      assertEquals(childCount, result.get("suite-current").size());
      assertEquals(childCount, requestedChildIds.size());
      assertEquals(expectedChildIds(childCount), requestedChildIds);
      assertTrue(maximumChildRequestsInFlight.get() > 1);
      assertTrue(
          maximumChildRequestsInFlight.get() <= OctaneRequestCoordinator.DEFAULT_MAX_IN_FLIGHT);
      assertTrue("3,000-run poll should finish well below two minutes", elapsedMillis < 5_000L);
      System.out.printf(
          "Octane 3000-run acceptance: childRequests=%d maxInFlight=%d elapsedMs=%d%n",
          childRequests.get(), maximumChildRequestsInFlight.get(), elapsedMillis);
    }
  }

  @Test
  public void capsTwentyConcurrentRequestsAtEightPerServer() throws Exception {
    AtomicInteger workspaceRequests = new AtomicInteger();
    AtomicInteger signOutRequests = new AtomicInteger();
    server.createContext(
        "/api/shared_spaces/1001/workspaces/2002/runs",
        exchange -> {
          workspaceRequests.incrementAndGet();
          try {
            Thread.sleep(100L);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          json(exchange, 200, "{\"data\":[]}");
        });
    server.createContext(
        "/authentication/sign_out",
        exchange -> {
          signOutRequests.incrementAndGet();
          json(exchange, 200, "{}");
        });
    OctaneRequestCoordinator.resetForTests();
    List<Future<Integer>> futures = new ArrayList<>();
    try (ExecutorService clients = Executors.newFixedThreadPool(20)) {
      for (int index = 0; index < 20; index++) {
        futures.add(
            clients.submit(
                () -> {
                  try (OctaneClient client =
                      new OctaneClient(baseUrl, "client", Secret.fromString("secret"))) {
                    return client.testWorkspaceAccess("1001", "2002");
                  }
                }));
      }
      for (Future<Integer> future : futures) {
        assertEquals(200, future.get(10, TimeUnit.SECONDS).intValue());
      }
    }

    OctaneRequestCoordinator.Metrics metrics = OctaneRequestCoordinator.metrics(baseUrl);
    assertEquals(20, workspaceRequests.get());
    assertEquals(20, signOutRequests.get());
    assertEquals(40L, metrics.requests());
    assertTrue(metrics.maximumInFlight() > 1);
    assertTrue(metrics.maximumInFlight() <= OctaneRequestCoordinator.DEFAULT_MAX_IN_FLIGHT);
    assertEquals(0, metrics.inFlight());
    System.out.printf(
        "Octane concurrency acceptance: requests=%d maxInFlight=%d%n",
        metrics.requests(), metrics.maximumInFlight());
  }

  private void assertSingleOwnerAcrossReportSurfaces(
      List<RunRecord> records, String expectedOwner) {
    StatusClassifier classifier = defaultClassifier();
    GateResult result =
        new GateResult(
            "55",
            "regressions.passRate == 100",
            true,
            true,
            GateMetrics.fromRuns(records, classifier),
            records,
            Map.of("55", records),
            Map.of(),
            Instant.parse("2026-08-03T12:00:00Z"));
    OctaneGateReportSnapshot snapshot =
        OctaneGateReportSnapshot.fromResult(
            OctaneGateReportState.PASSED, "Passed", result, classifier, 30);

    assertEquals(1, snapshot.getSections().get(0).getSuiteRuns().size());
    assertEquals(
        expectedOwner, snapshot.getSections().get(0).getSuiteRuns().get(0).getDisplayName());
    assertEquals(100, snapshot.getTestMetrics().getAutomationPercentage());
    assertEquals(1, snapshot.getTesterPerformances().size());
    assertEquals(expectedOwner, snapshot.getTesterPerformances().get(0).getEmail());

    String emailReportZone = new OctaneReportZoneHtmlRenderer().renderZone(snapshot);
    assertTrue(emailReportZone.contains(expectedOwner));
    if (!expectedOwner.startsWith("Unassigned")) {
      assertFalse(emailReportZone.contains("Unassigned"));
    }
  }

  private Set<String> expectedChildIds(int count) {
    Set<String> ids = new HashSet<>();
    for (int index = 0; index < count; index++) {
      ids.add("run-" + index);
    }
    return ids;
  }

  private StatusClassifier defaultClassifier() {
    return new StatusClassifier(
        StatusClassifier.DEFAULT_PASSED_STATUSES,
        StatusClassifier.DEFAULT_FAILED_STATUSES,
        StatusClassifier.DEFAULT_NEUTRAL_STATUSES,
        StatusClassifier.DEFAULT_RUNNING_STATUSES);
  }

  private List<String> idsFromQuery(HttpExchange exchange) {
    String query =
        URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
    Matcher matcher = Pattern.compile("id EQ ([^|;)\\\"]+)").matcher(query);
    List<String> ids = new ArrayList<>();
    while (matcher.find()) {
      ids.add(matcher.group(1).trim());
    }
    return ids;
  }

  private List<String> discoverAvailableIds(OctaneClient client) throws Exception {
    List<String> discovered =
        client.fetchSuiteRunIdsByReleaseAndSprint("1001", "2002", "Release 2.4", "Sprint 3");
    return List.copyOf(client.fetchAvailableSuiteChildRuns("1001", "2002", discovered).keySet());
  }

  private void json(HttpExchange exchange, int status, String body) throws IOException {
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.getResponseHeaders().add("Set-Cookie", "LWSSO_COOKIE_KEY=test; Path=/");
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream stream = exchange.getResponseBody()) {
      stream.write(bytes);
    }
  }
}
