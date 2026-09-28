package io.jenkins.plugins.octanesuitegatebyembiti.services;

import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public class OctaneGateExecutorsTest {
  @Test
  public void runsPollWorkOnVirtualThreads() throws Exception {
    CountDownLatch completed = new CountDownLatch(1);
    AtomicBoolean virtual = new AtomicBoolean();

    OctaneGateExecutors.submitPoll(
        () -> {
          virtual.set(Thread.currentThread().isVirtual());
          completed.countDown();
        });

    assertTrue(completed.await(5, TimeUnit.SECONDS));
    assertTrue(virtual.get());
  }
}
