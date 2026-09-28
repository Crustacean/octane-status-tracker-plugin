package io.jenkins.plugins.octanesuitegatebyembiti.services;

import java.util.concurrent.CompletableFuture;

/** Coordinates normal and email-triggered refreshes for one running gate. */
public final class OctanePollRefreshCoordinator {
  private CompletableFuture<Void> activePoll;

  public synchronized PollRequest beginOrJoin() {
    if (activePoll != null && !activePoll.isDone()) {
      return new PollRequest(false, activePoll);
    }
    activePoll = new CompletableFuture<>();
    return new PollRequest(true, activePoll);
  }

  public synchronized boolean isRunning() {
    return activePoll != null && !activePoll.isDone();
  }

  public void complete(Throwable failure) {
    CompletableFuture<Void> completion;
    synchronized (this) {
      completion = activePoll;
      activePoll = null;
    }
    if (completion == null || completion.isDone()) {
      return;
    }
    if (failure == null) {
      completion.complete(null);
    } else {
      completion.completeExceptionally(failure);
    }
  }

  public record PollRequest(boolean owner, CompletableFuture<Void> completion) {}
}
