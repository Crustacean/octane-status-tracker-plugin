package io.jenkins.plugins.octanesuitegatebyembiti.repositories;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

final class OctaneRequestCoordinator {
  static final int DEFAULT_MAX_IN_FLIGHT = 8;

  private static final ConcurrentMap<String, ServerState> SERVERS = new ConcurrentHashMap<>();

  private OctaneRequestCoordinator() {}

  static <T> HttpResponse<T> send(
      String serverKey,
      HttpClient client,
      HttpRequest request,
      HttpResponse.BodyHandler<T> bodyHandler)
      throws IOException, InterruptedException {
    ServerState state = SERVERS.computeIfAbsent(serverKey, ignored -> new ServerState(limit()));
    state.permits.acquire();
    int current = state.inFlight.incrementAndGet();
    state.maximumInFlight.accumulateAndGet(current, Math::max);
    state.requests.incrementAndGet();
    long started = System.nanoTime();
    CompletableFuture<HttpResponse<T>> response = client.sendAsync(request, bodyHandler);
    try {
      return await(response);
    } finally {
      state.totalNanos.addAndGet(System.nanoTime() - started);
      state.inFlight.decrementAndGet();
      state.permits.release();
    }
  }

  private static <T> HttpResponse<T> await(CompletableFuture<HttpResponse<T>> response)
      throws IOException, InterruptedException {
    try {
      return response.get();
    } catch (InterruptedException e) {
      response.cancel(true);
      throw e;
    } catch (ExecutionException e) {
      throw mappedFailure(e.getCause());
    }
  }

  private static IOException mappedFailure(Throwable cause) {
    if (cause instanceof IOException ioException) {
      return ioException;
    }
    if (cause instanceof RuntimeException runtimeException) {
      throw runtimeException;
    }
    if (cause instanceof Error error) {
      throw error;
    }
    return new IOException("ALM Octane asynchronous request failed.", cause);
  }

  static Metrics metrics(String serverKey) {
    ServerState state = SERVERS.get(serverKey);
    if (state == null) {
      return new Metrics(0L, 0, 0, 0L);
    }
    return new Metrics(
        state.requests.get(),
        state.inFlight.get(),
        state.maximumInFlight.get(),
        state.totalNanos.get());
  }

  static void resetForTests() {
    SERVERS.clear();
  }

  private static int limit() {
    return Math.max(
        1,
        Math.min(
            64,
            Integer.getInteger(
                "io.jenkins.plugins.octanesuitegate.maxRequestsPerServer", DEFAULT_MAX_IN_FLIGHT)));
  }

  record Metrics(long requests, int inFlight, int maximumInFlight, long totalNanos) {}

  private static final class ServerState {
    private final Semaphore permits;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maximumInFlight = new AtomicInteger();
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong totalNanos = new AtomicLong();

    private ServerState(int limit) {
      permits = new Semaphore(limit, true);
    }
  }
}
