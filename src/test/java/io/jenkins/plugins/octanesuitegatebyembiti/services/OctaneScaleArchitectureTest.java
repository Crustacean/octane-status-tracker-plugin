package io.jenkins.plugins.octanesuitegatebyembiti.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.jenkins.plugins.octanesuitegatebyembiti.models.GateResult;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneGateReportSnapshot;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import tools.jackson.databind.ObjectMapper;

public class OctaneScaleArchitectureTest {
  private static final int JOBS = 30;
  private static final int SUITES_PER_JOB = 500;
  private static final int CHILD_RUNS_PER_SUITE = 50;

  @Test(timeout = 60_000L)
  public void mapsMoreThanSevenHundredSuitesWithOneHundredFiftyChildrenToBoundedArtifacts()
      throws Exception {
    int suites = 701;
    int childrenPerSuite = 150;
    GateResult result = OctaneScaleTestFixture.result(100, suites, childrenPerSuite);
    OctaneGateReportSnapshot snapshot = OctaneScaleTestFixture.snapshot(result);
    OctaneReportDataMapper.ReportData data = new OctaneReportDataMapper().map(snapshot);
    ObjectMapper mapper = new ObjectMapper();
    int indexBytes = mapper.writeValueAsBytes(data.index()).length;
    int completeBytes = mapper.writeValueAsBytes(data.complete()).length;

    assertEquals(105_150, result.getRuns().size());
    assertEquals(
        5,
        data.sections().stream().mapToInt(section -> ((List<?>) section.get("bars")).size()).sum());
    assertEquals("Status", data.sections().get(0).get("xAxis"));
    assertEquals(false, data.sections().get(0).get("tooltipsEnabled"));
    assertTrue(data.sections().get(0).containsKey("automationPercentage"));
    assertTrue(data.sections().get(0).containsKey("automationPercentageLabel"));
    assertTrue(!data.index().containsKey("suiteAttributions"));
    assertEquals(suites, ((java.util.Map<?, ?>) data.complete().get("suiteAttributions")).size());
    @SuppressWarnings("unchecked")
    List<java.util.Map<String, Object>> bars =
        (List<java.util.Map<String, Object>>) data.sections().get(0).get("bars");
    assertTrue(bars.get(0).containsKey("automationPercentage"));
    assertTrue("initial index must stay below 250 KB", indexBytes < 250_000);
    assertTrue("complete JSON must stay below 5 MB", completeBytes < 5_000_000);
    assertEquals(true, result.toPipelineMap().get("detailsTruncated"));
  }

  @Test(timeout = 60_000L)
  public void mapsThirtyConcurrentDenseJobsToBoundedClientArtifacts() throws Exception {
    CountDownLatch ready = new CountDownLatch(JOBS);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Measurement>> futures = new ArrayList<>();
    Instant started = Instant.now();
    AtomicLong peakHeapBytes = new AtomicLong(currentHeapBytes());
    AtomicLong maximumIndexBytes = new AtomicLong();
    AtomicLong maximumCompleteBytes = new AtomicLong();
    AtomicBoolean sampling = new AtomicBoolean(true);
    Thread sampler =
        Thread.ofPlatform()
            .daemon(true)
            .name("octane-scale-heap-sampler")
            .start(
                () -> {
                  while (sampling.get()) {
                    peakHeapBytes.accumulateAndGet(currentHeapBytes(), Math::max);
                    try {
                      Thread.sleep(2L);
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                      return;
                    }
                  }
                });
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int job = 0; job < JOBS; job++) {
        int jobNumber = job;
        futures.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  GateResult result =
                      OctaneScaleTestFixture.result(
                          jobNumber, SUITES_PER_JOB, CHILD_RUNS_PER_SUITE);
                  OctaneGateReportSnapshot snapshot = OctaneScaleTestFixture.snapshot(result);
                  OctaneReportDataMapper.ReportData data =
                      new OctaneReportDataMapper().map(snapshot);
                  ObjectMapper mapper = new ObjectMapper();
                  return new Measurement(
                      snapshot.isClientRenderedReport(),
                      mapper.writeValueAsBytes(data.index()).length,
                      mapper.writeValueAsBytes(data.complete()).length,
                      snapshot.getDefectMetrics().getTotalDefectsRaised(),
                      result.getRuns().size(),
                      data.sections().stream()
                          .mapToInt(section -> ((List<?>) section.get("bars")).size())
                          .sum());
                }));
      }
      assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
      start.countDown();
      for (Future<Measurement> future : futures) {
        Measurement measurement = future.get();
        maximumIndexBytes.accumulateAndGet(measurement.indexBytes(), Math::max);
        maximumCompleteBytes.accumulateAndGet(measurement.completeBytes(), Math::max);
        assertTrue(!measurement.clientRendered());
        assertTrue("initial index must stay below 250 KB", measurement.indexBytes() < 250_000);
        assertTrue("complete JSON must stay below 5 MB", measurement.completeBytes() < 5_000_000);
        assertEquals(OctaneScaleTestFixture.DEFECTS_PER_JOB, measurement.defectCount());
        assertEquals(SUITES_PER_JOB * CHILD_RUNS_PER_SUITE, measurement.childRunCount());
        assertEquals(5, measurement.barCount());
      }
    } finally {
      sampling.set(false);
      sampler.join(5_000L);
    }
    Duration elapsed = Duration.between(started, Instant.now());
    assertTrue(elapsed.compareTo(Duration.ofSeconds(60)) < 0);
    System.out.printf(
        "Octane scale acceptance: jobs=%d suites/job=%d child-runs/job=%d defects/job=%d "
            + "elapsedMs=%d peakHeapBytes=%d maxIndexBytes=%d maxCompleteBytes=%d%n",
        JOBS,
        SUITES_PER_JOB,
        SUITES_PER_JOB * CHILD_RUNS_PER_SUITE,
        OctaneScaleTestFixture.DEFECTS_PER_JOB,
        elapsed.toMillis(),
        peakHeapBytes.get(),
        maximumIndexBytes.get(),
        maximumCompleteBytes.get());
  }

  private long currentHeapBytes() {
    return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
  }

  private record Measurement(
      boolean clientRendered,
      int indexBytes,
      int completeBytes,
      int defectCount,
      int childRunCount,
      int barCount) {}
}
