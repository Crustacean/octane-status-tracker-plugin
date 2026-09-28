package io.jenkins.plugins.octanesuitegatebyembiti.services;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Runs blocking Octane I/O on virtual threads so Jenkins scheduler workers remain available. */
public final class OctaneGateExecutors {
  private static final ExecutorService POLL_EXECUTOR =
      Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("octane-gate-poll-", 0).factory());

  private OctaneGateExecutors() {}

  public static Future<?> submitPoll(Runnable task) {
    return POLL_EXECUTOR.submit(task);
  }
}
