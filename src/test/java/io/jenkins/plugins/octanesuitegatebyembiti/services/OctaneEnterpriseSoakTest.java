package io.jenkins.plugins.octanesuitegatebyembiti.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assume;
import org.junit.Test;

/** Opt-in endurance test for production-like progress-email scheduler pressure. */
public class OctaneEnterpriseSoakTest {
  private static final String ENABLED_PROPERTY = "octane.enterpriseSoak.enabled";
  private static final String DURATION_MINUTES_PROPERTY = "octane.enterpriseSoak.durationMinutes";
  private static final String JOBS_PROPERTY = "octane.enterpriseSoak.jobs";

  @Test
  public void holdsSchedulerResourcesStableForConfiguredSoakWindow() throws Exception {
    Assume.assumeTrue(
        "Enable with -D" + ENABLED_PROPERTY + "=true", Boolean.getBoolean(ENABLED_PROPERTY));

    int jobs = Math.max(1, Integer.getInteger(JOBS_PROPERTY, 500));
    long durationMinutes = Math.max(1L, Long.getLong(DURATION_MINUTES_PROPERTY, 24L * 60L));
    Instant deadline = Instant.now().plus(Duration.ofMinutes(durationMinutes));
    MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    int baselineThreads = threads.getThreadCount();
    long peakHeapBytes = memory.getHeapMemoryUsage().getUsed();
    long cycles = 0L;
    long deliveries = 0L;

    OctaneProgressEmailScheduler scheduler =
        OctaneProgressEmailScheduler.createForTests(
            OctaneProgressEmailScheduler.THREAD_COUNT,
            OctaneProgressEmailScheduler.MAX_ACTIVE_SCHEDULES,
            Duration.ZERO,
            Duration.ofMinutes(5L));
    try {
      while (Instant.now().isBefore(deadline)) {
        deliveries += runCycle(scheduler, jobs);
        cycles++;
        peakHeapBytes = Math.max(peakHeapBytes, memory.getHeapMemoryUsage().getUsed());
        assertNull("Deadlocked JVM threads detected", threads.findDeadlockedThreads());
        assertTrue(
            "Scheduler worker count escaped its bounded pool",
            threads.getThreadCount()
                <= baselineThreads + OctaneProgressEmailScheduler.THREAD_COUNT + 8);
      }
    } finally {
      scheduler.shutdownForTests();
    }

    assertEquals(0, scheduler.activeScheduleCount());
    assertEquals(0, scheduler.queuedTaskCount());
    System.out.printf(
        "ENTERPRISE_SOAK_BENCHMARK duration_minutes=%d jobs_per_cycle=%d cycles=%d "
            + "deliveries=%d peak_heap_bytes=%d baseline_threads=%d final_threads=%d%n",
        durationMinutes,
        jobs,
        cycles,
        deliveries,
        peakHeapBytes,
        baselineThreads,
        threads.getThreadCount());
  }

  private long runCycle(OctaneProgressEmailScheduler scheduler, int jobs) throws Exception {
    CountDownLatch completed = new CountDownLatch(jobs);
    AtomicInteger deliveries = new AtomicInteger();
    List<OctaneProgressEmailScheduler.Delivery> strongReferences = new ArrayList<>(jobs);
    List<OctaneProgressEmailScheduler.Registration> registrations = new ArrayList<>(jobs);
    for (int job = 0; job < jobs; job++) {
      OctaneProgressEmailScheduler.Delivery delivery =
          occurrence -> {
            deliveries.incrementAndGet();
            completed.countDown();
          };
      strongReferences.add(delivery);
      registrations.add(
          scheduler.schedule(
              "soak-" + job, "soak-build-" + job, new ImmediateThenDailySchedule(), delivery));
    }
    try {
      assertTrue("Soak delivery cycle timed out", completed.await(60L, TimeUnit.SECONDS));
      assertEquals(jobs, deliveries.get());
      return deliveries.get();
    } finally {
      for (OctaneProgressEmailScheduler.Registration registration : registrations) {
        registration.cancel();
      }
      assertEquals(0, scheduler.activeScheduleCount());
      assertEquals(0, scheduler.queuedTaskCount());
    }
  }

  private static final class ImmediateThenDailySchedule
      implements OctaneProgressEmailScheduler.Schedule {
    @Override
    public String expression() {
      return "* * * * *";
    }

    @Override
    public String description() {
      return "Every minute.";
    }

    @Override
    public Instant nextAfter(Instant after, Instant lastDelivery, Duration minimumInterval) {
      return lastDelivery == null ? after.plusMillis(1L) : after.plus(Duration.ofDays(1L));
    }
  }
}
