package io.jenkins.plugins.octanesuitegatebyembiti.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import hudson.FilePath;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

public class OctaneEmailDeliveryCoordinatorTest {
  @Rule public JenkinsRule jenkins = new JenkinsRule();

  @Test
  public void serializesSameBuildWorkspaceAndReleasesRegistryEntry() throws Exception {
    FreeStyleProject project = jenkins.createFreeStyleProject();
    FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
    FilePath workspace = jenkins.jenkins.getWorkspaceFor(project);
    CountDownLatch attempted = new CountDownLatch(1);
    CountDownLatch acquired = new CountDownLatch(1);

    try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
      try (OctaneEmailDeliveryCoordinator.Lease ignored =
          OctaneEmailDeliveryCoordinator.acquire(build, workspace)) {
        Future<?> waiter =
            executor.submit(
                () -> {
                  attempted.countDown();
                  try (OctaneEmailDeliveryCoordinator.Lease second =
                      OctaneEmailDeliveryCoordinator.acquire(build, workspace)) {
                    acquired.countDown();
                  }
                  return null;
                });
        assertTrue(attempted.await(2, TimeUnit.SECONDS));
        assertFalse(acquired.await(150, TimeUnit.MILLISECONDS));
        assertFalse(waiter.isDone());
      }
      assertTrue(acquired.await(2, TimeUnit.SECONDS));
    }

    assertEquals(0, OctaneEmailDeliveryCoordinator.activeEntryCount());
  }

  @Test(timeout = 20_000L)
  public void serializesOneHundredSameBuildCapturesWithoutRegistryLeak() throws Exception {
    FreeStyleProject project = jenkins.createFreeStyleProject();
    FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
    FilePath workspace = jenkins.jenkins.getWorkspaceFor(project);
    AtomicInteger active = new AtomicInteger();
    AtomicInteger maximumActive = new AtomicInteger();
    List<Future<?>> captures = new java.util.ArrayList<>();

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int capture = 0; capture < 100; capture++) {
        captures.add(
            executor.submit(
                () -> {
                  try (OctaneEmailDeliveryCoordinator.Lease ignored =
                      OctaneEmailDeliveryCoordinator.acquire(build, workspace)) {
                    int current = active.incrementAndGet();
                    maximumActive.accumulateAndGet(current, Math::max);
                    try {
                      Thread.sleep(1L);
                    } finally {
                      active.decrementAndGet();
                    }
                  }
                  return null;
                }));
      }
      for (Future<?> capture : captures) {
        capture.get(10L, TimeUnit.SECONDS);
      }
    }

    assertEquals(1, maximumActive.get());
    assertEquals(0, active.get());
    assertEquals(0, OctaneEmailDeliveryCoordinator.activeEntryCount());
  }
}
