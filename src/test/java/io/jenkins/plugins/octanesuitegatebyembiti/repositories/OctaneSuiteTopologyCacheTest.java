package io.jenkins.plugins.octanesuitegatebyembiti.repositories;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.jenkins.plugins.octanesuitegatebyembiti.repositories.OctaneSuiteTopologyCache.Topology;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class OctaneSuiteTopologyCacheTest {
  @Before
  public void resetBeforeTest() {
    OctaneSuiteTopologyCache.resetForTests();
  }

  @After
  public void resetAfterTest() {
    OctaneSuiteTopologyCache.resetForTests();
  }

  @Test
  public void coalescesConcurrentLoadsForTheSameSuites() throws Exception {
    AtomicInteger loads = new AtomicInteger();
    CountDownLatch loaderStarted = new CountDownLatch(1);
    CountDownLatch releaseLoader = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<Map<String, Topology>> first =
          executor.submit(
              () ->
                  OctaneSuiteTopologyCache.getAll(
                      "server/workspace",
                      List.of("suite-1"),
                      ids -> {
                        loads.incrementAndGet();
                        loaderStarted.countDown();
                        assertTrue(releaseLoader.await(5, TimeUnit.SECONDS));
                        return Map.of("suite-1", topology("run-1"));
                      }));
      assertTrue(loaderStarted.await(5, TimeUnit.SECONDS));
      Future<Map<String, Topology>> second =
          executor.submit(
              () ->
                  OctaneSuiteTopologyCache.getAll(
                      "server/workspace",
                      List.of("suite-1"),
                      ids -> {
                        loads.incrementAndGet();
                        return Map.of("suite-1", topology("duplicate"));
                      }));

      releaseLoader.countDown();

      assertEquals(List.of("run-1"), first.get(5, TimeUnit.SECONDS).get("suite-1").runIds());
      assertEquals(List.of("run-1"), second.get(5, TimeUnit.SECONDS).get("suite-1").runIds());
      assertEquals("Owner", second.get(5, TimeUnit.SECONDS).get("suite-1").suiteOwnerName());
      assertEquals(1, loads.get());
    }
  }

  @Test
  public void allowsDisjointSuiteLoadsInTheSameWorkspaceToRunConcurrently() throws Exception {
    CountDownLatch bothLoadersStarted = new CountDownLatch(2);
    CountDownLatch releaseLoaders = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<Map<String, Topology>> first =
          executor.submit(
              () -> loadAfterBarrier("suite-1", "run-1", bothLoadersStarted, releaseLoaders));
      Future<Map<String, Topology>> second =
          executor.submit(
              () -> loadAfterBarrier("suite-2", "run-2", bothLoadersStarted, releaseLoaders));

      assertTrue(bothLoadersStarted.await(5, TimeUnit.SECONDS));
      releaseLoaders.countDown();

      assertEquals(List.of("run-1"), first.get(5, TimeUnit.SECONDS).get("suite-1").runIds());
      assertEquals(List.of("run-2"), second.get(5, TimeUnit.SECONDS).get("suite-2").runIds());
    }
  }

  @Test
  public void expiresAtTheThirtySecondBoundaryWithoutServingStaleTopology() throws Exception {
    AtomicInteger loads = new AtomicInteger();
    long loadedAt = 1_000L;

    Map<String, Topology> initial =
        OctaneSuiteTopologyCache.getAll(
            "server/workspace",
            List.of("suite-1"),
            ids -> {
              loads.incrementAndGet();
              return Map.of("suite-1", topology("run-1"));
            },
            loadedAt);
    Map<String, Topology> beforeExpiry =
        OctaneSuiteTopologyCache.getAll(
            "server/workspace",
            List.of("suite-1"),
            ids -> {
              throw new AssertionError("A topology entry before its TTL must be reused.");
            },
            loadedAt + OctaneSuiteTopologyCache.ACTIVE_TTL.toNanos() - 1L);
    Map<String, Topology> atExpiry =
        OctaneSuiteTopologyCache.getAll(
            "server/workspace",
            List.of("suite-1"),
            ids -> {
              loads.incrementAndGet();
              return Map.of("suite-1", topology("run-2"));
            },
            loadedAt + OctaneSuiteTopologyCache.ACTIVE_TTL.toNanos());

    assertEquals(List.of("run-1"), initial.get("suite-1").runIds());
    assertEquals(List.of("run-1"), beforeExpiry.get("suite-1").runIds());
    assertEquals(List.of("run-2"), atExpiry.get("suite-1").runIds());
    assertEquals(2, loads.get());
    assertEquals(1L, OctaneSuiteTopologyCache.metrics().hits());
    assertEquals(2L, OctaneSuiteTopologyCache.metrics().misses());
  }

  @Test
  public void missingSuiteTopologyCanBeRepopulatedOnTheNextPoll() throws Exception {
    AtomicInteger loads = new AtomicInteger();

    Map<String, Topology> missing =
        OctaneSuiteTopologyCache.getAll(
            "server/workspace",
            List.of("suite-1"),
            ids -> {
              loads.incrementAndGet();
              return Map.of();
            },
            1_000L);
    Map<String, Topology> repopulated =
        OctaneSuiteTopologyCache.getAll(
            "server/workspace",
            List.of("suite-1"),
            ids -> {
              loads.incrementAndGet();
              return Map.of("suite-1", topology("run-2"));
            },
            1_001L);

    assertEquals(List.of(), missing.get("suite-1").runIds());
    assertEquals(List.of("run-2"), repopulated.get("suite-1").runIds());
    assertEquals(2, loads.get());
    assertEquals(0L, OctaneSuiteTopologyCache.metrics().hits());
    assertEquals(2L, OctaneSuiteTopologyCache.metrics().misses());
  }

  @Test
  public void isolatesIdenticalSuiteIdsByServerWorkspaceNamespace() throws Exception {
    AtomicInteger loads = new AtomicInteger();

    Map<String, Topology> first =
        OctaneSuiteTopologyCache.getAll(
            "server-a/workspace-1",
            List.of("suite-1"),
            ids -> {
              loads.incrementAndGet();
              return Map.of("suite-1", topology("run-a"));
            },
            1_000L);
    Map<String, Topology> second =
        OctaneSuiteTopologyCache.getAll(
            "server-b/workspace-1",
            List.of("suite-1"),
            ids -> {
              loads.incrementAndGet();
              return Map.of("suite-1", topology("run-b"));
            },
            1_000L);

    assertEquals(List.of("run-a"), first.get("suite-1").runIds());
    assertEquals(List.of("run-b"), second.get("suite-1").runIds());
    assertEquals(2, loads.get());
  }

  @Test
  public void evictsOnlyOverflowingEntriesWhenLargeLoadsExceedTheBound() throws Exception {
    int requestedEntries = OctaneSuiteTopologyCache.MAXIMUM_ENTRIES + 65;
    List<String> suiteIds =
        java.util.stream.IntStream.range(0, requestedEntries)
            .mapToObj(index -> "suite-" + index)
            .toList();

    OctaneSuiteTopologyCache.getAll(
        "server/workspace",
        suiteIds,
        ids -> {
          Map<String, Topology> loaded = new LinkedHashMap<>();
          ids.forEach(id -> loaded.put(id, topology("run-" + id)));
          return loaded;
        },
        1_000L);

    assertEquals(
        OctaneSuiteTopologyCache.MAXIMUM_ENTRIES, OctaneSuiteTopologyCache.metrics().size());
    assertEquals(65L, OctaneSuiteTopologyCache.metrics().evictions());
  }

  private Map<String, Topology> loadAfterBarrier(
      String suiteId,
      String runId,
      CountDownLatch bothLoadersStarted,
      CountDownLatch releaseLoaders)
      throws Exception {
    return OctaneSuiteTopologyCache.getAll(
        "server/workspace",
        List.of(suiteId),
        ids -> {
          bothLoadersStarted.countDown();
          assertTrue(releaseLoaders.await(5, TimeUnit.SECONDS));
          return Map.of(suiteId, topology(runId));
        });
  }

  private Topology topology(String runId) {
    return new Topology(List.of(runId), "Owner");
  }
}
