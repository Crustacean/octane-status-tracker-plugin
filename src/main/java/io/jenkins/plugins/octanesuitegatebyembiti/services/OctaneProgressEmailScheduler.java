package io.jenkins.plugins.octanesuitegatebyembiti.services;

import java.lang.ref.WeakReference;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class OctaneProgressEmailScheduler {
  public static final int THREAD_COUNT = 4;
  public static final int MAX_ACTIVE_SCHEDULES = 1_024;
  public static final Duration MINIMUM_EMAIL_INTERVAL = Duration.ofMinutes(1L);
  static final Duration MAXIMUM_TRIGGER_LATENESS = Duration.ofHours(24L);

  private static final OctaneProgressEmailScheduler INSTANCE = createShared();

  private final ScheduledThreadPoolExecutor executor;
  private final Clock clock;
  private final Duration minimumInterval;
  private final Duration maximumLateness;
  private final int maximumSchedules;
  private final Map<String, ScheduledTask> registrations = new ConcurrentHashMap<>();
  private final Object registrationLock = new Object();

  private OctaneProgressEmailScheduler(
      ScheduledThreadPoolExecutor executor,
      Clock clock,
      Duration minimumInterval,
      Duration maximumLateness,
      int maximumSchedules) {
    this.executor = executor;
    this.clock = clock;
    this.minimumInterval = minimumInterval;
    this.maximumLateness = maximumLateness;
    this.maximumSchedules = maximumSchedules;
  }

  public static OctaneProgressEmailScheduler get() {
    return INSTANCE;
  }

  public Registration schedule(
      String registrationId, String ownerId, String cronExpression, Delivery delivery) {
    return schedule(registrationId, ownerId, new OctaneCronSchedule(cronExpression), delivery);
  }

  Registration schedule(
      String registrationId, String ownerId, Schedule schedule, Delivery delivery) {
    Objects.requireNonNull(registrationId, "registrationId");
    Objects.requireNonNull(ownerId, "ownerId");
    Objects.requireNonNull(schedule, "schedule");
    Objects.requireNonNull(delivery, "delivery");

    ScheduledTask task =
        new ScheduledTask(registrationId, ownerId, schedule, new WeakReference<>(delivery));
    synchronized (registrationLock) {
      ScheduledTask previous = registrations.remove(registrationId);
      if (previous != null) {
        previous.cancel();
      }
      if (registrations.size() >= maximumSchedules) {
        throw new RejectedExecutionException(
            "Octane progress email scheduler is at its "
                + maximumSchedules
                + " active schedule limit.");
      }
      registrations.put(registrationId, task);
    }
    try {
      task.scheduleNext(clock.instant());
    } catch (RuntimeException e) {
      remove(task);
      throw e;
    }
    return new Registration(this, registrationId, task.nextOccurrence());
  }

  public void cancelOwner(String ownerId) {
    List<ScheduledTask> matching = new ArrayList<>();
    for (ScheduledTask task : registrations.values()) {
      if (task.ownerId.equals(ownerId)) {
        matching.add(task);
      }
    }
    for (ScheduledTask task : matching) {
      task.cancel();
      remove(task);
    }
  }

  public int activeScheduleCount() {
    return registrations.size();
  }

  int largestPoolSize() {
    return executor.getLargestPoolSize();
  }

  int queuedTaskCount() {
    return executor.getQueue().size();
  }

  void shutdownForTests() {
    for (ScheduledTask task : List.copyOf(registrations.values())) {
      task.cancel();
    }
    registrations.clear();
    executor.shutdownNow();
  }

  static OctaneProgressEmailScheduler createForTests(
      int threads, int maximumSchedules, Duration minimumInterval, Duration maximumLateness) {
    return new OctaneProgressEmailScheduler(
        executor(threads, "octane-progress-email-test-"),
        Clock.systemUTC(),
        minimumInterval,
        maximumLateness,
        maximumSchedules);
  }

  private static OctaneProgressEmailScheduler createShared() {
    return new OctaneProgressEmailScheduler(
        executor(THREAD_COUNT, "octane-progress-email-"),
        Clock.systemDefaultZone(),
        MINIMUM_EMAIL_INTERVAL,
        MAXIMUM_TRIGGER_LATENESS,
        MAX_ACTIVE_SCHEDULES);
  }

  private static ScheduledThreadPoolExecutor executor(int threads, String namePrefix) {
    ScheduledThreadPoolExecutor executor =
        new ScheduledThreadPoolExecutor(threads, daemonThreadFactory(namePrefix));
    executor.setRemoveOnCancelPolicy(true);
    executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    executor.setKeepAliveTime(1L, TimeUnit.MINUTES);
    executor.allowCoreThreadTimeOut(true);
    return executor;
  }

  private static ThreadFactory daemonThreadFactory(String namePrefix) {
    AtomicInteger sequence = new AtomicInteger();
    return task -> {
      Thread thread = new Thread(task, namePrefix + sequence.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }

  private void cancel(String registrationId) {
    ScheduledTask task = registrations.remove(registrationId);
    if (task != null) {
      task.cancel();
    }
  }

  private void remove(ScheduledTask task) {
    registrations.remove(task.registrationId, task);
  }

  public interface Delivery {
    void send(Occurrence occurrence) throws Exception;

    default void skipped(Occurrence occurrence, Duration lateness) {}

    default void failed(Occurrence occurrence, Throwable failure) {}
  }

  interface Schedule {
    String expression();

    String description();

    Instant nextAfter(Instant after, Instant lastDelivery, Duration minimumInterval);
  }

  public record Occurrence(String expression, String description, Instant scheduledAt) {}

  public static final class Registration {
    private final OctaneProgressEmailScheduler scheduler;
    private final String registrationId;
    private final Occurrence nextOccurrence;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    private Registration(
        OctaneProgressEmailScheduler scheduler, String registrationId, Occurrence nextOccurrence) {
      this.scheduler = scheduler;
      this.registrationId = registrationId;
      this.nextOccurrence = nextOccurrence;
    }

    public Occurrence nextOccurrence() {
      return nextOccurrence;
    }

    public void cancel() {
      if (cancelled.compareAndSet(false, true)) {
        scheduler.cancel(registrationId);
      }
    }
  }

  private final class ScheduledTask {
    private final String registrationId;
    private final String ownerId;
    private final Schedule schedule;
    private final WeakReference<Delivery> delivery;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile ScheduledFuture<?> future;
    private volatile Instant lastDelivery;
    private volatile Instant nextScheduledAt;

    private ScheduledTask(
        String registrationId,
        String ownerId,
        Schedule schedule,
        WeakReference<Delivery> delivery) {
      this.registrationId = registrationId;
      this.ownerId = ownerId;
      this.schedule = schedule;
      this.delivery = delivery;
    }

    private synchronized void scheduleNext(Instant after) {
      if (cancelled.get()) {
        return;
      }
      Delivery currentDelivery = delivery.get();
      if (currentDelivery == null) {
        cancel();
        remove(this);
        return;
      }
      Instant next = schedule.nextAfter(after, lastDelivery, minimumInterval);
      nextScheduledAt = next;
      long delayMillis = Math.max(0L, Duration.between(clock.instant(), next).toMillis());
      future = executor.schedule(() -> fire(next), delayMillis, TimeUnit.MILLISECONDS);
    }

    private Occurrence nextOccurrence() {
      Instant scheduledAt = nextScheduledAt;
      if (scheduledAt == null) {
        throw new IllegalStateException("Progress email schedule has no next occurrence.");
      }
      return new Occurrence(schedule.expression(), schedule.description(), scheduledAt);
    }

    private void fire(Instant scheduledAt) {
      if (cancelled.get()) {
        remove(this);
        return;
      }
      Delivery currentDelivery = delivery.get();
      if (currentDelivery == null) {
        cancel();
        remove(this);
        return;
      }

      Instant started = clock.instant();
      Duration lateness = Duration.between(scheduledAt, started);
      Occurrence occurrence =
          new Occurrence(schedule.expression(), schedule.description(), scheduledAt);
      try {
        if (lateness.compareTo(maximumLateness) > 0) {
          currentDelivery.skipped(occurrence, lateness);
        } else {
          lastDelivery = scheduledAt;
          currentDelivery.send(occurrence);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        currentDelivery.failed(occurrence, e);
      } catch (Exception | LinkageError failure) {
        lastDelivery = scheduledAt;
        currentDelivery.failed(occurrence, failure);
      } finally {
        if (!cancelled.get()) {
          try {
            scheduleNext(clock.instant());
          } catch (RejectedExecutionException e) {
            cancel();
            remove(this);
          }
        }
      }
    }

    private synchronized void cancel() {
      cancelled.set(true);
      ScheduledFuture<?> scheduled = future;
      if (scheduled != null) {
        scheduled.cancel(true);
      }
    }
  }
}
