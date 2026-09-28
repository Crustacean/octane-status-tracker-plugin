package io.jenkins.plugins.octanesuitegatebyembiti.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class OctanePollRefreshCoordinatorTest {
  @Test(timeout = 15_000L)
  public void joinsEmailRefreshToOneSlowActivePoll() throws Exception {
    OctanePollRefreshCoordinator coordinator = new OctanePollRefreshCoordinator();
    CountDownLatch pollStarted = new CountDownLatch(1);
    CountDownLatch releaseSlowPoll = new CountDownLatch(1);
    AtomicInteger apiCalls = new AtomicInteger();

    OctanePollRefreshCoordinator.PollRequest standardPoll = coordinator.beginOrJoin();
    assertTrue(standardPoll.owner());
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<?> worker =
          executor.submit(
              () -> {
                apiCalls.incrementAndGet();
                pollStarted.countDown();
                try {
                  if (!releaseSlowPoll.await(12L, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Mock Octane poll did not receive release.");
                  }
                  coordinator.complete(null);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  coordinator.complete(e);
                }
              });

      assertTrue(pollStarted.await(2L, TimeUnit.SECONDS));
      OctanePollRefreshCoordinator.PollRequest emailRefresh = coordinator.beginOrJoin();
      assertFalse(emailRefresh.owner());
      assertSame(standardPoll.completion(), emailRefresh.completion());

      releaseSlowPoll.countDown();
      standardPoll.completion().get(2L, TimeUnit.SECONDS);
      emailRefresh.completion().get(2L, TimeUnit.SECONDS);
      worker.get(2L, TimeUnit.SECONDS);
    } finally {
      releaseSlowPoll.countDown();
    }

    assertFalse(coordinator.isRunning());
    assertEquals(1, apiCalls.get());
  }

  @Test
  public void transitionsFromCompletedPollToNewOwnerAndIgnoresLateCompletion() {
    OctanePollRefreshCoordinator coordinator = new OctanePollRefreshCoordinator();

    OctanePollRefreshCoordinator.PollRequest first = coordinator.beginOrJoin();
    coordinator.complete(null);
    coordinator.complete(new IllegalStateException("late completion"));
    OctanePollRefreshCoordinator.PollRequest second = coordinator.beginOrJoin();

    assertTrue(first.owner());
    assertTrue(first.completion().isDone());
    assertFalse(first.completion().isCompletedExceptionally());
    assertTrue(second.owner());
    assertFalse(second.completion().isDone());
    coordinator.complete(null);
    assertFalse(coordinator.isRunning());
  }

  @Test
  public void propagatesFailureToEveryJoinedWaiter() throws Exception {
    OctanePollRefreshCoordinator coordinator = new OctanePollRefreshCoordinator();
    OctanePollRefreshCoordinator.PollRequest owner = coordinator.beginOrJoin();
    OctanePollRefreshCoordinator.PollRequest waiter = coordinator.beginOrJoin();

    coordinator.complete(new IllegalStateException("poll failed"));

    assertFutureFailure(owner, "poll failed");
    assertFutureFailure(waiter, "poll failed");
    assertFalse(coordinator.isRunning());
  }

  private void assertFutureFailure(
      OctanePollRefreshCoordinator.PollRequest request, String expectedMessage) throws Exception {
    try {
      request.completion().get(2L, TimeUnit.SECONDS);
      throw new AssertionError("Expected the coordinated poll to fail.");
    } catch (ExecutionException e) {
      assertEquals(expectedMessage, e.getCause().getMessage());
    }
  }
}
