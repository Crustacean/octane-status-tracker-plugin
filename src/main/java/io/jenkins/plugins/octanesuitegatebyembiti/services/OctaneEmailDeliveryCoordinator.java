package io.jenkins.plugins.octanesuitegatebyembiti.services;

import hudson.FilePath;
import hudson.model.Run;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/** Serializes screenshot creation and SMTP attachment consumption for the same build workspace. */
public final class OctaneEmailDeliveryCoordinator {
  private static final ConcurrentHashMap<String, Entry> ENTRIES = new ConcurrentHashMap<>();

  private OctaneEmailDeliveryCoordinator() {}

  public static Lease acquire(Run<?, ?> run, FilePath workspace) throws InterruptedException {
    String key = run.getExternalizableId() + '\u0000' + workspace.getRemote();
    Entry entry =
        ENTRIES.compute(
            key,
            (ignored, current) -> {
              Entry selected = current == null ? new Entry() : current;
              selected.references.incrementAndGet();
              return selected;
            });
    boolean locked = false;
    try {
      entry.lock.lockInterruptibly();
      locked = true;
      return new Lease(key, entry);
    } finally {
      if (!locked) {
        releaseReference(key, entry);
      }
    }
  }

  static int activeEntryCount() {
    return ENTRIES.size();
  }

  private static void releaseReference(String key, Entry entry) {
    if (entry.references.decrementAndGet() == 0) {
      ENTRIES.remove(key, entry);
    }
  }

  private static final class Entry {
    private final ReentrantLock lock = new ReentrantLock(true);
    private final AtomicInteger references = new AtomicInteger();
  }

  public static final class Lease implements AutoCloseable {
    private final String key;
    private final Entry entry;
    private boolean closed;

    private Lease(String key, Entry entry) {
      this.key = key;
      this.entry = entry;
    }

    @Override
    public void close() {
      if (closed) {
        return;
      }
      closed = true;
      entry.lock.unlock();
      releaseReference(key, entry);
    }
  }
}
