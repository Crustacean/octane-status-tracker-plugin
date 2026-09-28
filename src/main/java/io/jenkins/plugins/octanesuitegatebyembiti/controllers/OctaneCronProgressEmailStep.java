package io.jenkins.plugins.octanesuitegatebyembiti.controllers;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.jenkins.plugins.octanesuitegatebyembiti.actions.OctaneGateReportAction;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneEmailFailureMode;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneGateReportSnapshot;
import io.jenkins.plugins.octanesuitegatebyembiti.services.OctaneProgressEmailScheduler;
import io.jenkins.plugins.octanesuitegatebyembiti.utils.Util;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.workflow.steps.BodyExecution;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.DataBoundConstructor;

public class OctaneCronProgressEmailStep extends AbstractOctaneEmailStep {
  static final String STALENESS_THRESHOLD_ENV = "PROGRESS_EMAIL_STALENESS_THRESHOLD_MINUTES";
  static final String INTERVAL_TIMEOUT_ENV = "PROGRESS_EMAIL_INTERVAL_TIMEOUT";
  static final Duration DEFAULT_STALENESS_THRESHOLD = Duration.ofMinutes(1L);
  static final long FINAL_EMAIL_YIELD_SECONDS = 60L;

  private final String cron;

  @DataBoundConstructor
  public OctaneCronProgressEmailStep(String cron, String to) {
    super(to, OctaneEmailFailureMode.WARN, false);
    this.cron = Util.trimToEmpty(cron);
  }

  public String getCron() {
    return cron;
  }

  @Override
  public StepExecution start(StepContext context) {
    return new Execution(cron, toRequest(true), context);
  }

  static Duration stalenessThreshold(EnvVars envVars) throws AbortException {
    String configured =
        envVars == null ? "" : Util.trimToEmpty(envVars.get(STALENESS_THRESHOLD_ENV));
    if (configured.isEmpty()) {
      return DEFAULT_STALENESS_THRESHOLD;
    }
    try {
      long minutes = Long.parseLong(configured);
      if (minutes < 0L) {
        throw new NumberFormatException("negative threshold");
      }
      return Duration.ofMinutes(minutes);
    } catch (ArithmeticException | NumberFormatException e) {
      AbortException failure =
          new AbortException(
              STALENESS_THRESHOLD_ENV + " must be a whole number of zero or greater.");
      failure.initCause(e);
      throw failure;
    }
  }

  static boolean intervalTimeoutEnabled(EnvVars envVars) throws AbortException {
    String configured =
        envVars == null
            ? ""
            : Util.trimToEmpty(envVars.get(INTERVAL_TIMEOUT_ENV)).toLowerCase(Locale.ROOT);
    return switch (configured) {
      case "", "false", "0" -> false;
      case "true", "1" -> true;
      default -> throw new AbortException(INTERVAL_TIMEOUT_ENV + " must be true, false, 1, or 0.");
    };
  }

  static boolean progressEmailsEnabled(String cron) {
    return !Util.trimToEmpty(cron).isEmpty();
  }

  static boolean shouldSendProgressEmail(
      Double lastExecutionProgress, double currentProgress, boolean intervalTimeoutEnabled) {
    return lastExecutionProgress == null
        || currentProgress > lastExecutionProgress
        || !intervalTimeoutEnabled;
  }

  static String stagnantProgressMessage(double progress) {
    return "Skipping scheduled Octane progress email because execution progress remains at "
        + String.format(Locale.ROOT, "%.2f%%", progress)
        + " and "
        + INTERVAL_TIMEOUT_ENV
        + " is enabled.";
  }

  static boolean hasRenderableReportData(OctaneGateReportSnapshot snapshot) {
    return snapshot != null && snapshot.hasReportSections();
  }

  static long activeTimeRemainingSeconds(
      OctaneGateReportSnapshot snapshot, Instant cronEvaluationTime) {
    if (snapshot == null) {
      return Long.MAX_VALUE;
    }
    if (!snapshot.isBuilding() || snapshot.isFinalizing()) {
      return 0L;
    }
    if (snapshot.getTimeoutExtendedSeconds() <= 0 && snapshot.getCompletionProgress() >= 100.0) {
      return 0L;
    }

    try {
      Instant evaluationTime = cronEvaluationTime == null ? Instant.now() : cronEvaluationTime;
      long totalExecutionSeconds =
          (long) snapshot.getTimeoutSeconds() + snapshot.getTimeoutExtendedSeconds();
      Instant forcedClosureAt =
          Instant.parse(snapshot.getStartedAt()).plusSeconds(totalExecutionSeconds);
      if (!forcedClosureAt.isAfter(evaluationTime)) {
        return 0L;
      }
      Duration remaining = Duration.between(evaluationTime, forcedClosureAt);
      return remaining.getSeconds() + (remaining.getNano() == 0 ? 0L : 1L);
    } catch (RuntimeException ignored) {
      return Long.MAX_VALUE;
    }
  }

  static boolean shouldYieldToFinalEmail(
      OctaneGateReportSnapshot snapshot, Instant cronEvaluationTime) {
    return activeTimeRemainingSeconds(snapshot, cronEvaluationTime) <= FINAL_EMAIL_YIELD_SECONDS;
  }

  static String closureImminentMessage(long remainingSeconds) {
    return "Interval email suppressed. Run closure is imminent (time remaining: "
        + remainingSeconds
        + "s). Yielding to Final Email.";
  }

  private static final class Execution extends StepExecution
      implements OctaneProgressEmailScheduler.Delivery {
    private static final long serialVersionUID = 1L;
    private static final DateTimeFormatter AUDIT_TIME_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final String cron;
    private final OctaneEmailReportStep.EmailRequest emailRequest;
    private final String registrationId = UUID.randomUUID().toString();
    private transient volatile OctaneProgressEmailScheduler.Registration registration;
    private transient volatile BodyExecution bodyExecution;
    private volatile Double lastExecutionProgress;
    private boolean intervalTimeoutEnabled;
    private volatile boolean completed;

    private Execution(
        String cron, OctaneEmailReportStep.EmailRequest emailRequest, StepContext context) {
      super(context);
      this.cron = cron;
      this.emailRequest = emailRequest;
    }

    @Override
    public boolean start() throws Exception {
      registerIfEnabled();
      try {
        bodyExecution =
            getContext()
                .newBodyInvoker()
                .withDisplayName("Octane gate with cron progress email")
                .withCallback(new Callback(this))
                .start();
      } catch (RuntimeException | Error failure) {
        cancelRegistration();
        throw failure;
      }
      return false;
    }

    @Override
    public void onResume() {
      if (completed || !progressEmailsEnabled(cron)) {
        return;
      }
      try {
        register();
      } catch (Exception e) {
        completed = true;
        getContext().onFailure(e);
      }
    }

    @Override
    public void stop(Throwable cause) {
      completed = true;
      cancelRegistration();
      BodyExecution body = bodyExecution;
      if (body != null) {
        body.cancel(cause);
      }
    }

    @Override
    public void send(OctaneProgressEmailScheduler.Occurrence occurrence) throws Exception {
      if (completed) {
        return;
      }
      if (!progressEmailsEnabled(cron)) {
        log(progressEmailsDisabledMessage());
        return;
      }
      Run<?, ?> run = getContext().get(Run.class);
      OctaneGateReportAction currentAction = run.getAction(OctaneGateReportAction.class);
      if (suppressForTimeoutCollision(
          currentAction == null ? null : currentAction.getSnapshot(),
          collisionEvaluationTime(occurrence))) {
        return;
      }
      auditSchedule(occurrence);
      OctaneGateReportAction deliveryAction = refreshReportForDelivery(currentAction);
      OctaneGateReportSnapshot deliverySnapshot =
          deliveryAction == null ? null : deliveryAction.awaitReconciledSnapshot();
      if (suppressForTimeoutCollision(deliverySnapshot, Instant.now())) {
        return;
      }
      if (deliverySnapshot == null || !deliverySnapshot.hasReportSections()) {
        log(
            "Deferring scheduled Octane progress email until a completed poll produces "
                + "renderable report data.");
        return;
      }

      double currentProgress = deliverySnapshot.getExecutionProgress();
      if (!shouldSendProgressEmail(
          lastExecutionProgress, currentProgress, intervalTimeoutEnabled)) {
        log(stagnantProgressMessage(currentProgress));
        return;
      }

      OctaneEmailReportStep.DeliveryResult deliveryResult =
          OctaneEmailReportStep.executeRequest(
              emailRequest,
              getContext(),
              deliverySnapshot,
              snapshot -> {
                if (suppressForTimeoutCollision(snapshot, Instant.now())) {
                  return false;
                }
                if (!hasRenderableReportData(snapshot)) {
                  log(
                      "Deferring scheduled Octane progress email because the rendered snapshot "
                          + "contains no report data.");
                  return false;
                }
                return true;
              });
      if (deliveryResult == OctaneEmailReportStep.DeliveryResult.SENT) {
        lastExecutionProgress = currentProgress;
      }
    }

    private Instant collisionEvaluationTime(OctaneProgressEmailScheduler.Occurrence occurrence) {
      Instant now = Instant.now();
      Instant scheduledAt = occurrence.scheduledAt();
      return scheduledAt != null && scheduledAt.isAfter(now) ? scheduledAt : now;
    }

    private boolean suppressForTimeoutCollision(
        OctaneGateReportSnapshot snapshot, Instant evaluationTime) {
      long remainingSeconds = activeTimeRemainingSeconds(snapshot, evaluationTime);
      if (remainingSeconds > FINAL_EMAIL_YIELD_SECONDS) {
        return false;
      }
      log(closureImminentMessage(remainingSeconds));
      return true;
    }

    private OctaneGateReportAction refreshReportForDelivery(OctaneGateReportAction action)
        throws Exception {
      if (action == null) {
        return null;
      }
      TaskListener listener = getContext().get(TaskListener.class);
      Duration threshold = stalenessThreshold(getContext().get(EnvVars.class));
      OctaneGateReportAction.RefreshResult result =
          action.refreshForEmail(threshold, Instant.now());
      long ageSeconds = Math.max(0L, result.age().toSeconds());
      switch (result.status()) {
        case FRESH ->
            listener
                .getLogger()
                .println(
                    "Octane progress data is fresh (age "
                        + ageSeconds
                        + "s; threshold "
                        + threshold.toSeconds()
                        + "s). Preparing scheduled email artifacts from the current snapshot.");
        case REFRESHED ->
            listener
                .getLogger()
                .println(
                    "Stale Octane progress data (age "
                        + ageSeconds
                        + "s) was refreshed before the scheduled email.");
        case JOINED ->
            listener
                .getLogger()
                .println(
                    "Stale Octane progress data (age "
                        + ageSeconds
                        + "s) joined the active poll before the scheduled email.");
        case NOT_BUILDING ->
            listener
                .getLogger()
                .println("Octane gate is already complete; using its final report snapshot.");
      }
      return action;
    }

    private void auditSchedule(OctaneProgressEmailScheduler.Occurrence occurrence)
        throws Exception {
      TaskListener listener = getContext().get(TaskListener.class);
      listener
          .getLogger()
          .println(
              "Cron job time: "
                  + occurrence.expression()
                  + " will run \""
                  + occurrence.description()
                  + "\"");
      listener
          .getLogger()
          .println("next at " + AUDIT_TIME_FORMATTER.format(occurrence.scheduledAt()));
    }

    @Override
    public void skipped(OctaneProgressEmailScheduler.Occurrence occurrence, Duration lateness) {
      log(
          "WARNING: Skipped delayed Octane progress email for cron "
              + occurrence.expression()
              + " because scheduler capacity delayed it by "
              + lateness.toSeconds()
              + " seconds.");
    }

    @Override
    public void failed(OctaneProgressEmailScheduler.Occurrence occurrence, Throwable failure) {
      log(
          "WARNING: Scheduled Octane progress email for cron "
              + occurrence.expression()
              + " failed: "
              + defaultMessage(failure));
    }

    private void registerIfEnabled() throws Exception {
      if (!progressEmailsEnabled(cron)) {
        TaskListener listener = getContext().get(TaskListener.class);
        listener.getLogger().println(progressEmailsDisabledMessage());
        return;
      }
      intervalTimeoutEnabled =
          OctaneCronProgressEmailStep.intervalTimeoutEnabled(getContext().get(EnvVars.class));
      register();
    }

    private String progressEmailsDisabledMessage() {
      return "Periodic Octane progress emails are disabled because "
          + "PROGRESS_EMAIL_INTERVAL_CRONJOB is blank.";
    }

    private void register() throws Exception {
      Run<?, ?> run = getContext().get(Run.class);
      try {
        registration =
            OctaneProgressEmailScheduler.get()
                .schedule(registrationId, run.getExternalizableId(), cron, this);
        auditSchedule(registration.nextOccurrence());
      } catch (IllegalArgumentException | RejectedExecutionException e) {
        AbortException failure =
            new AbortException("Unable to schedule Octane progress emails: " + e.getMessage());
        failure.initCause(e);
        throw failure;
      }
    }

    private void completeSuccess(Object result) {
      completed = true;
      cancelRegistration();
      getContext().onSuccess(result);
    }

    private void completeFailure(Throwable failure) {
      completed = true;
      cancelRegistration();
      getContext().onFailure(failure);
    }

    private void cancelRegistration() {
      OctaneProgressEmailScheduler.Registration current = registration;
      registration = null;
      if (current != null) {
        current.cancel();
      }
    }

    private void log(String message) {
      try {
        TaskListener listener = getContext().get(TaskListener.class);
        if (listener != null) {
          listener.getLogger().println(message);
        }
      } catch (Exception ignored) {
        // The Pipeline context may already be gone after a hard controller shutdown.
      }
    }

    private String defaultMessage(Throwable failure) {
      String message = failure.getMessage();
      return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
  }

  private static final class Callback extends BodyExecutionCallback {
    private static final long serialVersionUID = 1L;
    private final Execution execution;

    private Callback(Execution execution) {
      this.execution = execution;
    }

    @Override
    public void onSuccess(StepContext context, Object result) {
      execution.completeSuccess(result);
    }

    @Override
    public void onFailure(StepContext context, Throwable failure) {
      execution.completeFailure(failure);
    }
  }

  @Extension
  @Symbol("octaneCronProgressEmail")
  public static final class DescriptorImpl extends StepDescriptor {
    @Override
    public String getFunctionName() {
      return "octaneCronProgressEmail";
    }

    @NonNull
    @Override
    public String getDisplayName() {
      return "Schedule cron-based Octane progress emails";
    }

    @Override
    public boolean takesImplicitBlockArgument() {
      return true;
    }

    @Override
    public Set<? extends Class<?>> getRequiredContext() {
      return Set.of(Run.class, FilePath.class, Launcher.class, TaskListener.class, EnvVars.class);
    }
  }
}
