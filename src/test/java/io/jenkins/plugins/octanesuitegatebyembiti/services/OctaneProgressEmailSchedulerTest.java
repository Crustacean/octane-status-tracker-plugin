package io.jenkins.plugins.octanesuitegatebyembiti.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import org.junit.Test;

public class OctaneProgressEmailSchedulerTest {
  @Test
  public void coordinatesTwentyDenseStaleRefreshesWithoutStarvingDaemonPool() throws Exception {
    int jobs = 20;
    int suiteRunsPerJob = 500;
    OctaneProgressEmailScheduler scheduler =
        OctaneProgressEmailScheduler.createForTests(
            4, 64, Duration.ofMinutes(5L), Duration.ofSeconds(5L));
    CountDownLatch completed = new CountDownLatch(jobs);
    AtomicInteger activePolls = new AtomicInteger();
    AtomicInteger maximumActivePolls = new AtomicInteger();
    AtomicInteger pollCalls = new AtomicInteger();
    AtomicInteger processedSuiteRuns = new AtomicInteger();
    AtomicBoolean daemonWorkers = new AtomicBoolean(true);
    List<OctaneProgressEmailScheduler.Delivery> deliveries = new ArrayList<>();
    List<OctaneProgressEmailScheduler.Registration> registrations = new ArrayList<>();
    try {
      for (int job = 0; job < jobs; job++) {
        OctanePollRefreshCoordinator coordinator = new OctanePollRefreshCoordinator();
        OctaneProgressEmailScheduler.Delivery delivery =
            occurrence -> {
              daemonWorkers.compareAndSet(true, Thread.currentThread().isDaemon());
              OctanePollRefreshCoordinator.PollRequest refresh = coordinator.beginOrJoin();
              if (refresh.owner()) {
                OctaneGateExecutors.submitPoll(
                    () -> {
                      int active = activePolls.incrementAndGet();
                      maximumActivePolls.accumulateAndGet(active, Math::max);
                      pollCalls.incrementAndGet();
                      Throwable failure = null;
                      try {
                        for (int suiteRun = 0; suiteRun < suiteRunsPerJob; suiteRun++) {
                          processedSuiteRuns.incrementAndGet();
                        }
                        Thread.sleep(20L);
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        failure = e;
                      } finally {
                        activePolls.decrementAndGet();
                        coordinator.complete(failure);
                      }
                    });
              }
              try {
                refresh.completion().get(5L, TimeUnit.SECONDS);
              } finally {
                completed.countDown();
              }
            };
        deliveries.add(delivery);
        registrations.add(
            scheduler.schedule(
                "stale-job-" + job,
                "stale-build-" + job,
                new ImmediateThenDailySchedule(),
                delivery));
      }

      assertTrue(completed.await(10L, TimeUnit.SECONDS));
      assertTrue(daemonWorkers.get());
      assertEquals(jobs, pollCalls.get());
      assertEquals(jobs * suiteRunsPerJob, processedSuiteRuns.get());
      assertTrue(maximumActivePolls.get() <= 4);
      assertEquals(0, activePolls.get());
    } finally {
      for (OctaneProgressEmailScheduler.Registration registration : registrations) {
        registration.cancel();
      }
      scheduler.shutdownForTests();
    }
  }

  @Test
  public void productionSchedulerAllowsOneDeliveryPerCronMinute() {
    assertEquals(Duration.ofMinutes(1L), OctaneProgressEmailScheduler.MINIMUM_EMAIL_INTERVAL);
    assertTrue(OctaneProgressEmailScheduler.MAX_ACTIVE_SCHEDULES >= 500);
  }

  @Test(timeout = 30_000L)
  public void dispatchesFiveHundredRegistrationsOnceWithoutDropsDuplicatesOrQueueLeaks()
      throws Exception {
    int jobs = 500;
    OctaneProgressEmailScheduler scheduler =
        OctaneProgressEmailScheduler.createForTests(
            OctaneProgressEmailScheduler.THREAD_COUNT,
            600,
            Duration.ofMinutes(1L),
            Duration.ofMinutes(5L));
    CountDownLatch completed = new CountDownLatch(jobs);
    AtomicIntegerArray deliveriesByJob = new AtomicIntegerArray(jobs);
    List<OctaneProgressEmailScheduler.Delivery> deliveries = new ArrayList<>();
    List<OctaneProgressEmailScheduler.Registration> registrations = new ArrayList<>();
    long started = System.nanoTime();
    try {
      for (int job = 0; job < jobs; job++) {
        int jobIndex = job;
        OctaneProgressEmailScheduler.Delivery delivery =
            occurrence -> {
              deliveriesByJob.incrementAndGet(jobIndex);
              completed.countDown();
            };
        deliveries.add(delivery);
        registrations.add(
            scheduler.schedule(
                "enterprise-email-" + job,
                "enterprise-build-" + job,
                new ImmediateThenDailySchedule(),
                delivery));
      }

      assertTrue(completed.await(20L, TimeUnit.SECONDS));
      assertEquals(jobs, scheduler.activeScheduleCount());
      assertTrue(scheduler.largestPoolSize() <= OctaneProgressEmailScheduler.THREAD_COUNT);
      for (int job = 0; job < jobs; job++) {
        assertEquals("delivery count for job " + job, 1, deliveriesByJob.get(job));
      }
      long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
      System.out.printf(
          "ENTERPRISE_EMAIL_BENCHMARK jobs=%d elapsed_ms=%d max_threads=%d deliveries=%d%n",
          jobs, elapsedMillis, scheduler.largestPoolSize(), jobs);
    } finally {
      for (OctaneProgressEmailScheduler.Registration registration : registrations) {
        registration.cancel();
      }
      assertEquals(0, scheduler.activeScheduleCount());
      assertEquals(0, scheduler.queuedTaskCount());
      scheduler.shutdownForTests();
    }
  }

  @Test(timeout = 15_000L)
  public void cancellationCannotRaceWithPostDeliveryRescheduling() throws Exception {
    OctaneProgressEmailScheduler scheduler =
        OctaneProgressEmailScheduler.createForTests(4, 64, Duration.ZERO, Duration.ofMinutes(5L));
    try {
      for (int cycle = 0; cycle < 50; cycle++) {
        int jobs = 16;
        CountDownLatch completed = new CountDownLatch(jobs);
        List<OctaneProgressEmailScheduler.Delivery> deliveries = new ArrayList<>(jobs);
        List<OctaneProgressEmailScheduler.Registration> registrations = new ArrayList<>(jobs);
        for (int job = 0; job < jobs; job++) {
          OctaneProgressEmailScheduler.Delivery delivery = occurrence -> completed.countDown();
          deliveries.add(delivery);
          registrations.add(
              scheduler.schedule(
                  "cancel-race-" + cycle + "-" + job,
                  "cancel-race-build-" + cycle,
                  new ImmediateThenDailySchedule(),
                  delivery));
        }

        assertTrue(completed.await(2L, TimeUnit.SECONDS));
        for (OctaneProgressEmailScheduler.Registration registration : registrations) {
          registration.cancel();
        }
        assertEquals(0, scheduler.activeScheduleCount());
        assertEquals(0, scheduler.queuedTaskCount());
      }
    } finally {
      scheduler.shutdownForTests();
    }
  }

  @Test
  public void boundsTwentyOverlappingPipelineSchedulesToFourThreads() throws Exception {
    OctaneProgressEmailScheduler scheduler =
        OctaneProgressEmailScheduler.createForTests(
            4, 64, Duration.ofMinutes(5L), Duration.ofSeconds(5L));
    CountDownLatch fourStarted = new CountDownLatch(4);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch completed = new CountDownLatch(20);
    AtomicInteger active = new AtomicInteger();
    AtomicInteger maximumActive = new AtomicInteger();
    List<OctaneProgressEmailScheduler.Delivery> deliveries = new ArrayList<>();
    List<OctaneProgressEmailScheduler.Registration> registrations = new ArrayList<>();
    try {
      for (int pipeline = 0; pipeline < 20; pipeline++) {
        OctaneProgressEmailScheduler.Delivery delivery =
            occurrence -> {
              int current = active.incrementAndGet();
              maximumActive.accumulateAndGet(current, Math::max);
              fourStarted.countDown();
              try {
                assertTrue(release.await(5L, TimeUnit.SECONDS));
              } finally {
                active.decrementAndGet();
                completed.countDown();
              }
            };
        deliveries.add(delivery);
        registrations.add(
            scheduler.schedule(
                "pipeline-" + pipeline,
                "build-" + pipeline,
                new ImmediateThenDailySchedule(),
                delivery));
      }

      assertTrue(fourStarted.await(5L, TimeUnit.SECONDS));
      assertEquals(20, scheduler.activeScheduleCount());
      assertTrue(scheduler.queuedTaskCount() <= 16);
      assertTrue(scheduler.largestPoolSize() <= 4);
      release.countDown();
      assertTrue(completed.await(10L, TimeUnit.SECONDS));
      assertTrue(maximumActive.get() <= 4);
    } finally {
      release.countDown();
      for (OctaneProgressEmailScheduler.Registration registration : registrations) {
        registration.cancel();
      }
      scheduler.shutdownForTests();
    }
  }

  @Test
  public void rejectsSchedulesBeyondConfiguredBound() {
    OctaneProgressEmailScheduler scheduler =
        OctaneProgressEmailScheduler.createForTests(
            1, 2, Duration.ofMinutes(5L), Duration.ofSeconds(5L));
    OctaneProgressEmailScheduler.Delivery first = occurrence -> {};
    OctaneProgressEmailScheduler.Delivery second = occurrence -> {};
    OctaneProgressEmailScheduler.Delivery rejected = occurrence -> {};
    try {
      scheduler.schedule("one", "build-one", new ImmediateThenDailySchedule(), first);
      scheduler.schedule("two", "build-two", new ImmediateThenDailySchedule(), second);
      try {
        scheduler.schedule("three", "build-three", new ImmediateThenDailySchedule(), rejected);
        fail("Expected the bounded scheduler to reject the third registration.");
      } catch (RejectedExecutionException expected) {
        assertTrue(expected.getMessage().contains("active schedule limit"));
      }
    } finally {
      scheduler.shutdownForTests();
    }
  }

  @Test
  public void registersThirtyAggressiveCronPipelinesWithoutExecutorGrowth() {
    OctaneProgressEmailScheduler scheduler =
        OctaneProgressEmailScheduler.createForTests(
            4, 64, Duration.ofMinutes(5L), Duration.ofSeconds(5L));
    List<OctaneProgressEmailScheduler.Delivery> deliveries = new ArrayList<>();
    List<OctaneProgressEmailScheduler.Registration> registrations = new ArrayList<>();
    try {
      for (int pipeline = 0; pipeline < 30; pipeline++) {
        OctaneProgressEmailScheduler.Delivery delivery = occurrence -> {};
        deliveries.add(delivery);
        OctaneProgressEmailScheduler.Registration registration =
            scheduler.schedule(
                "aggressive-pipeline-" + pipeline,
                "aggressive-build-" + pipeline,
                "* * * * *",
                delivery);
        assertNotNull(registration.nextOccurrence());
        registrations.add(registration);
      }

      assertEquals(30, scheduler.activeScheduleCount());
      assertEquals(30, scheduler.queuedTaskCount());
      assertTrue(scheduler.largestPoolSize() <= 4);
    } finally {
      for (OctaneProgressEmailScheduler.Registration registration : registrations) {
        registration.cancel();
      }
      assertEquals(0, scheduler.activeScheduleCount());
      assertEquals(0, scheduler.queuedTaskCount());
      scheduler.shutdownForTests();
    }
  }

  @Test
  public void cancelOwnerRemovesEveryTimerForCompletedRun() {
    OctaneProgressEmailScheduler scheduler =
        OctaneProgressEmailScheduler.createForTests(
            1, 10, Duration.ofMinutes(5L), Duration.ofSeconds(5L));
    OctaneProgressEmailScheduler.Delivery first = occurrence -> {};
    OctaneProgressEmailScheduler.Delivery second = occurrence -> {};
    try {
      scheduler.schedule("one", "same-build", new ImmediateThenDailySchedule(), first);
      scheduler.schedule("two", "same-build", new ImmediateThenDailySchedule(), second);
      assertEquals(2, scheduler.activeScheduleCount());

      scheduler.cancelOwner("same-build");

      assertEquals(0, scheduler.activeScheduleCount());
    } finally {
      scheduler.shutdownForTests();
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
      return lastDelivery == null ? after.plusMillis(25L) : after.plus(Duration.ofDays(1L));
    }
  }
}
