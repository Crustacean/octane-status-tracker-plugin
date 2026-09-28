package io.jenkins.plugins.octanesuitegatebyembiti.repositories;

import java.io.IOException;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;

final class OctaneSuiteTopologyCache {
  static final int MAXIMUM_ENTRIES = 20_000;
  static final Duration ACTIVE_TTL = Duration.ofSeconds(30);
  private static final int CACHE_WRITE_BATCH_SIZE = 64;

  private static final Map<Key, Entry> CACHE = new LinkedHashMap<>(256, 0.75f, true);
  private static final ConcurrentMap<Key, CompletableFuture<Topology>> IN_FLIGHT =
      new ConcurrentHashMap<>();
  private static final AtomicLong HITS = new AtomicLong();
  private static final AtomicLong MISSES = new AtomicLong();
  private static final AtomicLong EVICTIONS = new AtomicLong();

  private OctaneSuiteTopologyCache() {}

  static Map<String, Topology> getAll(
      String namespace, Collection<String> suiteRunIds, Loader loader)
      throws IOException, InterruptedException {
    return getAll(namespace, suiteRunIds, loader, System.nanoTime());
  }

  static Map<String, Topology> getAll(
      String namespace, Collection<String> suiteRunIds, Loader loader, long now)
      throws IOException, InterruptedException {
    Map<String, Topology> result = new LinkedHashMap<>();
    Map<Key, CompletableFuture<Topology>> pending = new LinkedHashMap<>();
    Map<Key, CompletableFuture<Topology>> owned = new LinkedHashMap<>();
    for (String suiteRunId : suiteRunIds) {
      Key key = new Key(namespace, suiteRunId);
      Entry cached = cachedEntry(key, now);
      if (cached != null) {
        HITS.incrementAndGet();
        result.put(suiteRunId, cached.topology);
        continue;
      }
      MISSES.incrementAndGet();
      CompletableFuture<Topology> candidate = new CompletableFuture<>();
      CompletableFuture<Topology> existing = IN_FLIGHT.putIfAbsent(key, candidate);
      CompletableFuture<Topology> future = existing == null ? candidate : existing;
      pending.put(key, future);
      if (existing == null) {
        Entry refreshed = cachedEntry(key, System.nanoTime());
        if (refreshed == null) {
          owned.put(key, candidate);
        } else {
          candidate.complete(refreshed.topology);
          IN_FLIGHT.remove(key, candidate);
        }
      }
    }

    if (!owned.isEmpty()) {
      loadOwned(loader, owned, now);
    }
    for (Map.Entry<Key, CompletableFuture<Topology>> entry : pending.entrySet()) {
      result.put(entry.getKey().suiteRunId, await(entry.getValue()));
    }
    return result;
  }

  static Metrics metrics() {
    synchronized (CACHE) {
      return new Metrics(HITS.get(), MISSES.get(), EVICTIONS.get(), CACHE.size());
    }
  }

  static void resetForTests() {
    synchronized (CACHE) {
      CACHE.clear();
    }
    IN_FLIGHT.clear();
    HITS.set(0L);
    MISSES.set(0L);
    EVICTIONS.set(0L);
  }

  private static void evictToBound() {
    while (CACHE.size() > MAXIMUM_ENTRIES) {
      var iterator = CACHE.entrySet().iterator();
      iterator.next();
      iterator.remove();
      EVICTIONS.incrementAndGet();
    }
  }

  private static Entry cachedEntry(Key key, long now) {
    synchronized (CACHE) {
      Entry entry = CACHE.get(key);
      if (entry != null && entry.expiresAtNanos > now) {
        return entry;
      }
      if (entry != null) {
        CACHE.remove(key);
      }
      return null;
    }
  }

  private static void loadOwned(
      Loader loader, Map<Key, CompletableFuture<Topology>> owned, long loadedAt)
      throws IOException, InterruptedException {
    List<String> ids = owned.keySet().stream().map(key -> key.suiteRunId()).toList();
    try {
      Map<String, Topology> loaded = loader.load(ids);
      long expiry = loadedAt + ACTIVE_TTL.toNanos();
      List<Map.Entry<Key, Entry>> cacheWrites = new java.util.ArrayList<>();
      for (Map.Entry<Key, CompletableFuture<Topology>> entry : owned.entrySet()) {
        String suiteRunId = entry.getKey().suiteRunId;
        if (loaded.containsKey(suiteRunId)) {
          cacheWrites.add(Map.entry(entry.getKey(), new Entry(loaded.get(suiteRunId), expiry)));
        }
      }
      cacheInBoundedBatches(cacheWrites);
      for (Map.Entry<Key, CompletableFuture<Topology>> entry : owned.entrySet()) {
        entry.getValue().complete(loaded.getOrDefault(entry.getKey().suiteRunId, Topology.empty()));
      }
    } catch (IOException | InterruptedException | RuntimeException | Error failure) {
      owned.values().forEach(future -> future.completeExceptionally(failure));
      throw failure;
    } finally {
      owned.forEach((key, future) -> IN_FLIGHT.remove(key, future));
    }
  }

  private static void cacheInBoundedBatches(List<Map.Entry<Key, Entry>> writes) {
    for (int start = 0; start < writes.size(); start += CACHE_WRITE_BATCH_SIZE) {
      int end = Math.min(start + CACHE_WRITE_BATCH_SIZE, writes.size());
      synchronized (CACHE) {
        for (int index = start; index < end; index++) {
          Map.Entry<Key, Entry> write = writes.get(index);
          CACHE.put(write.getKey(), write.getValue());
        }
        evictToBound();
      }
    }
  }

  private static Topology await(CompletableFuture<Topology> future)
      throws IOException, InterruptedException {
    try {
      return future.get();
    } catch (ExecutionException e) {
      return rethrowLoadFailure(e.getCause());
    }
  }

  private static Topology rethrowLoadFailure(Throwable cause)
      throws IOException, InterruptedException {
    if (cause instanceof IOException ioException) {
      throw ioException;
    }
    if (cause instanceof InterruptedException interruptedException) {
      throw interruptedException;
    }
    if (cause instanceof RuntimeException runtimeException) {
      throw runtimeException;
    }
    if (cause instanceof Error error) {
      throw error;
    }
    throw new IOException("Unable to load ALM Octane suite topology.", cause);
  }

  interface Loader {
    Map<String, Topology> load(List<String> suiteRunIds) throws IOException, InterruptedException;
  }

  record Topology(
      List<String> runIds, String suiteOwnerName, String testSuiteId, String fallbackOwnerName) {
    Topology {
      runIds = runIds == null ? List.of() : List.copyOf(runIds);
      suiteOwnerName = suiteOwnerName == null ? "" : suiteOwnerName.trim();
      testSuiteId = testSuiteId == null ? "" : testSuiteId.trim();
      fallbackOwnerName = fallbackOwnerName == null ? "" : fallbackOwnerName.trim();
    }

    Topology(List<String> runIds, String suiteOwnerName) {
      this(runIds, suiteOwnerName, "", "");
    }

    static Topology empty() {
      return new Topology(List.of(), "", "", "");
    }
  }

  record Metrics(long hits, long misses, long evictions, int size) {}

  private record Key(String namespace, String suiteRunId) {}

  private record Entry(Topology topology, long expiresAtNanos) {}
}
