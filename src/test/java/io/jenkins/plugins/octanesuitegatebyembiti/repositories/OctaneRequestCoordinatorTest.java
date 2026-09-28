package io.jenkins.plugins.octanesuitegatebyembiti.repositories;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class OctaneRequestCoordinatorTest {
  private static final String LIMIT_PROPERTY =
      "io.jenkins.plugins.octanesuitegate.maxRequestsPerServer";

  @Before
  public void resetBeforeTest() {
    OctaneRequestCoordinator.resetForTests();
  }

  @After
  public void resetAfterTest() {
    System.clearProperty(LIMIT_PROPERTY);
    OctaneRequestCoordinator.resetForTests();
  }

  @Test(timeout = 15_000L)
  public void capsConcurrentRequestsPerServerAndReleasesEveryPermit() throws Exception {
    System.setProperty(LIMIT_PROPERTY, "2");
    CountDownLatch firstWave = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger inFlight = new AtomicInteger();
    AtomicInteger maximumInFlight = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    try (ExecutorService serverExecutor = Executors.newVirtualThreadPerTaskExecutor();
        ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
      server.setExecutor(serverExecutor);
      server.createContext(
          "/octane",
          exchange -> {
            int current = inFlight.incrementAndGet();
            maximumInFlight.accumulateAndGet(current, Math::max);
            firstWave.countDown();
            try {
              boolean released;
              try {
                released = release.await(10L, TimeUnit.SECONDS);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Test server interrupted.", e);
              }
              if (!released) {
                throw new IllegalStateException("Test server was not released.");
              }
              byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
              exchange.sendResponseHeaders(200, body.length);
              exchange.getResponseBody().write(body);
            } finally {
              inFlight.decrementAndGet();
              exchange.close();
            }
          });
      server.start();
      URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/octane");
      HttpClient client = HttpClient.newHttpClient();
      HttpRequest request = HttpRequest.newBuilder(endpoint).GET().build();
      List<Future<HttpResponse<String>>> responses = new ArrayList<>();
      for (int index = 0; index < 6; index++) {
        responses.add(
            callers.submit(
                () ->
                    OctaneRequestCoordinator.send(
                        "server-a",
                        client,
                        request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))));
      }

      assertTrue(firstWave.await(5L, TimeUnit.SECONDS));
      Thread.sleep(100L);
      assertEquals(2, maximumInFlight.get());
      release.countDown();
      for (Future<HttpResponse<String>> response : responses) {
        assertEquals(200, response.get(5L, TimeUnit.SECONDS).statusCode());
      }

      OctaneRequestCoordinator.Metrics metrics = OctaneRequestCoordinator.metrics("server-a");
      assertEquals(6L, metrics.requests());
      assertEquals(0, metrics.inFlight());
      assertEquals(2, metrics.maximumInFlight());
    } finally {
      release.countDown();
      server.stop(0);
    }
  }

  @Test(timeout = 30_000L)
  public void coordinatesFiveHundredParallelJobsWithBoundedLatencyAndNoPermitLeak()
      throws Exception {
    int jobs = 500;
    System.setProperty(LIMIT_PROPERTY, "8");
    AtomicInteger inFlight = new AtomicInteger();
    AtomicInteger maximumInFlight = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    List<Long> latenciesNanos = Collections.synchronizedList(new ArrayList<>());
    try (ExecutorService serverExecutor = Executors.newVirtualThreadPerTaskExecutor();
        ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
      server.setExecutor(serverExecutor);
      server.createContext(
          "/octane",
          exchange -> {
            int current = inFlight.incrementAndGet();
            maximumInFlight.accumulateAndGet(current, Math::max);
            try {
              byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
              exchange.sendResponseHeaders(200, body.length);
              exchange.getResponseBody().write(body);
            } finally {
              inFlight.decrementAndGet();
              exchange.close();
            }
          });
      server.start();
      URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/octane");
      HttpClient client = HttpClient.newHttpClient();
      HttpRequest request = HttpRequest.newBuilder(endpoint).GET().build();
      List<Future<HttpResponse<String>>> responses = new ArrayList<>();
      long waveStarted = System.nanoTime();
      for (int index = 0; index < jobs; index++) {
        responses.add(
            callers.submit(
                () -> {
                  long started = System.nanoTime();
                  try {
                    return OctaneRequestCoordinator.send(
                        "enterprise-server",
                        client,
                        request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                  } finally {
                    latenciesNanos.add(System.nanoTime() - started);
                  }
                }));
      }
      for (Future<HttpResponse<String>> response : responses) {
        assertEquals(200, response.get(20L, TimeUnit.SECONDS).statusCode());
      }
      long elapsedNanos = System.nanoTime() - waveStarted;

      OctaneRequestCoordinator.Metrics metrics =
          OctaneRequestCoordinator.metrics("enterprise-server");
      assertEquals(jobs, metrics.requests());
      assertEquals(0, metrics.inFlight());
      assertTrue(metrics.maximumInFlight() <= 8);
      assertTrue(maximumInFlight.get() <= 8);
      assertEquals(jobs, latenciesNanos.size());
      List<Long> sorted = new ArrayList<>(latenciesNanos);
      Collections.sort(sorted);
      long p95Millis = TimeUnit.NANOSECONDS.toMillis(sorted.get((int) (jobs * 0.95) - 1));
      long p99Millis = TimeUnit.NANOSECONDS.toMillis(sorted.get((int) (jobs * 0.99) - 1));
      double seconds = Math.max(0.001, elapsedNanos / 1_000_000_000.0);
      double requestsPerSecond = jobs / seconds;
      System.out.printf(
          "ENTERPRISE_POLL_BENCHMARK jobs=%d rps=%.2f p95_ms=%d p99_ms=%d max_in_flight=%d%n",
          jobs, requestsPerSecond, p95Millis, p99Millis, metrics.maximumInFlight());
    } finally {
      server.stop(0);
    }
  }
}
