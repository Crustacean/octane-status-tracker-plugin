package io.jenkins.plugins.octanesuitegatebyembiti.controllers;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.ArtifactArchiver;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import io.jenkins.plugins.octanesuitegatebyembiti.actions.OctaneGateReportAction;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneEmailFailureMode;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneGateReportSnapshot;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneReportTheme;
import io.jenkins.plugins.octanesuitegatebyembiti.services.HeadlessBrowserReportScreenshotService;
import io.jenkins.plugins.octanesuitegatebyembiti.services.JenkinsMailerOctaneReportSender;
import io.jenkins.plugins.octanesuitegatebyembiti.services.OctaneEmailBodyRenderer;
import io.jenkins.plugins.octanesuitegatebyembiti.services.OctaneEmailDeliveryCoordinator;
import io.jenkins.plugins.octanesuitegatebyembiti.services.OctaneEmailReportSender;
import io.jenkins.plugins.octanesuitegatebyembiti.services.OctaneReportScreenshot;
import io.jenkins.plugins.octanesuitegatebyembiti.services.OctaneReportScreenshotService;
import io.jenkins.plugins.octanesuitegatebyembiti.utils.Util;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

public class OctaneEmailReportStep extends AbstractOctaneEmailStep {
  static final int DEFAULT_VIEWPORT_WIDTH = 1400;
  public static final int MAX_VIEWPORT_WIDTH = 3840;
  private static final DateTimeFormatter EAST_AFRICA_DATE_FORMATTER =
      DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(ZoneId.of("Africa/Nairobi"));

  private static OctaneReportScreenshotService screenshotService =
      new HeadlessBrowserReportScreenshotService();
  private static OctaneEmailReportSender emailSender = new JenkinsMailerOctaneReportSender();

  @DataBoundConstructor
  public OctaneEmailReportStep(String to) {
    super(to, OctaneEmailFailureMode.UNSTABLE, true);
  }

  @DataBoundSetter
  @Override
  public void setViewportWidth(int viewportWidth) {
    super.setViewportWidth(Math.min(MAX_VIEWPORT_WIDTH, viewportWidth));
  }

  @Override
  public StepExecution start(StepContext context) {
    return new Execution(toRequest(), context);
  }

  static void setServicesForTesting(
      OctaneReportScreenshotService testScreenshotService,
      OctaneEmailReportSender testEmailSender) {
    screenshotService = testScreenshotService;
    emailSender = testEmailSender;
  }

  static void resetServicesForTesting() {
    screenshotService = new HeadlessBrowserReportScreenshotService();
    emailSender = new JenkinsMailerOctaneReportSender();
  }

  static DeliveryResult executeRequest(
      EmailRequest request,
      StepContext context,
      OctaneGateReportSnapshot reconciledSnapshot,
      DeliveryDecision deliveryDecision)
      throws Exception {
    return new Execution(request, context).deliver(reconciledSnapshot, deliveryDecision);
  }

  @FunctionalInterface
  interface DeliveryDecision {
    boolean shouldSend(OctaneGateReportSnapshot snapshot);
  }

  enum DeliveryResult {
    SENT,
    SKIPPED,
    FAILED
  }

  static String composeRecipients(String to, String cc, String bcc) {
    List<String> recipients = new ArrayList<>();
    addRecipients(recipients, "", to);
    addRecipients(recipients, "cc", cc);
    addRecipients(recipients, "bcc", bcc);
    return String.join(",", recipients);
  }

  private static void addRecipients(List<String> recipients, String prefix, String value) {
    String trimmed = Util.trimToEmpty(value);
    if (trimmed.isEmpty()) {
      return;
    }
    for (String recipient : trimmed.split("[,;\\s]+")) {
      String cleanRecipient = Util.trimToEmpty(recipient);
      if (cleanRecipient.isEmpty()) {
        continue;
      }
      recipients.add(prefix.isEmpty() ? cleanRecipient : prefix + ":" + cleanRecipient);
    }
  }

  static final class EmailRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String to;
    private final String cc;
    private final String bcc;
    private final String subject;
    private final String body;
    private final String projectName;
    private final String domainName;
    private final String from;
    private final String replyTo;
    private final String onFailure;
    private final String browserPath;
    private final String theme;
    private final int viewportWidth;
    private final boolean archiveScreenshot;
    private final boolean printDefectGroups;
    private final String projectSummary;
    private final boolean printProjectSummaryOnIntervalEmails;
    private final boolean printTestersOnEmailBody;
    private final boolean printDefectsOnEmailBody;
    private final String printDefectsOnEmailBodyFilter;
    private final int printDefectsOnEmailBodyLimit;
    private final boolean intervalEmail;
    private final boolean important;

    EmailRequest(
        String to,
        String cc,
        String bcc,
        String subject,
        String body,
        String projectName,
        String domainName,
        String from,
        String replyTo,
        String onFailure,
        String browserPath,
        String theme,
        int viewportWidth,
        boolean archiveScreenshot,
        boolean printDefectGroups,
        String projectSummary,
        boolean printProjectSummaryOnIntervalEmails,
        boolean printTestersOnEmailBody,
        boolean printDefectsOnEmailBody,
        String printDefectsOnEmailBodyFilter,
        int printDefectsOnEmailBodyLimit,
        boolean intervalEmail,
        boolean important) {
      this.to = to;
      this.cc = cc;
      this.bcc = bcc;
      this.subject = subject;
      this.body = body;
      this.projectName = projectName;
      this.domainName = domainName;
      this.from = from;
      this.replyTo = replyTo;
      this.onFailure = onFailure;
      this.browserPath = browserPath;
      this.theme = theme;
      this.viewportWidth = viewportWidth;
      this.archiveScreenshot = archiveScreenshot;
      this.printDefectGroups = printDefectGroups;
      this.projectSummary = projectSummary;
      this.printProjectSummaryOnIntervalEmails = printProjectSummaryOnIntervalEmails;
      this.printTestersOnEmailBody = printTestersOnEmailBody;
      this.printDefectsOnEmailBody = printDefectsOnEmailBody;
      this.printDefectsOnEmailBodyFilter = printDefectsOnEmailBodyFilter;
      this.printDefectsOnEmailBodyLimit = printDefectsOnEmailBodyLimit;
      this.intervalEmail = intervalEmail;
      this.important = important;
    }

    boolean shouldIncludeProjectSummary() {
      return !intervalEmail || printProjectSummaryOnIntervalEmails;
    }
  }

  private static class Execution extends SynchronousNonBlockingStepExecution<Void> {
    private static final long serialVersionUID = 1L;

    private final EmailRequest request;

    Execution(EmailRequest request, StepContext context) {
      super(context);
      this.request = request;
    }

    @Override
    protected Void run() throws Exception {
      deliver(null, null);
      return null;
    }

    private DeliveryResult deliver(
        OctaneGateReportSnapshot reconciledSnapshot, DeliveryDecision deliveryDecision)
        throws Exception {
      OctaneEmailFailureMode failureMode = OctaneEmailFailureMode.from(request.onFailure);
      Run<?, ?> run = getContext().get(Run.class);
      TaskListener listener = getContext().get(TaskListener.class);
      try {
        return sendReport(run, listener, reconciledSnapshot, deliveryDecision);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw e;
      } catch (Exception e) {
        handleFailure(run, listener, failureMode, e);
        return DeliveryResult.FAILED;
      }
    }

    private DeliveryResult sendReport(
        Run<?, ?> run,
        TaskListener listener,
        OctaneGateReportSnapshot reconciledSnapshot,
        DeliveryDecision deliveryDecision)
        throws Exception {
      OctaneGateReportAction action = run.getAction(OctaneGateReportAction.class);
      if (action == null) {
        throw new AbortException(
            "Octane Gate Report is not available. Run octaneSuiteGate before octaneEmailReport.");
      }
      FilePath workspace = getContext().get(FilePath.class);
      String recipients = composeRecipients(request.to, request.cc, request.bcc);
      if (recipients.isBlank()) {
        throw new AbortException("At least one recipient is required.");
      }
      emailSender.validate(
          recipients, request.from, request.replyTo, effectiveSubject(run, request.subject));
      try (OctaneEmailDeliveryCoordinator.Lease ignored =
          OctaneEmailDeliveryCoordinator.acquire(run, workspace)) {
        return sendReportLocked(
            run, listener, workspace, action, recipients, reconciledSnapshot, deliveryDecision);
      }
    }

    private DeliveryResult sendReportLocked(
        Run<?, ?> run,
        TaskListener listener,
        FilePath workspace,
        OctaneGateReportAction action,
        String recipients,
        OctaneGateReportSnapshot reconciledSnapshot,
        DeliveryDecision deliveryDecision)
        throws Exception {
      Launcher launcher = getContext().get(Launcher.class);
      EnvVars envVars = envVars();
      OctaneGateReportSnapshot reportSnapshot =
          reconciledSnapshot == null ? action.awaitReconciledSnapshot() : reconciledSnapshot;
      if (deliveryDecision != null && !deliveryDecision.shouldSend(reportSnapshot)) {
        return DeliveryResult.SKIPPED;
      }

      OctaneReportScreenshot screenshot =
          screenshotService.capture(
              reportSnapshot,
              workspace,
              envVars,
              launcher,
              listener,
              request.browserPath,
              request.viewportWidth,
              request.theme);
      if (request.archiveScreenshot) {
        listener.getLogger().println("Archiving Octane report-zone screenshot.");
        archiveScreenshot(run, workspace, envVars, launcher, listener, screenshot);
        listener.getLogger().println("Octane report-zone screenshot archived successfully.");
      }

      Instant renderedAt = Instant.now();
      String remainingTime = remainingTime(reportSnapshot, renderedAt);
      String totalTime = reportSnapshot.getTestingTimeSpentText();
      String subject =
          replaceRuntimeTokens(
              effectiveSubject(run, request.subject), remainingTime, totalTime, renderedAt);
      String body =
          new OctaneEmailBodyRenderer()
              .render(
                  replaceRuntimeTokens(request.body, remainingTime, totalTime, renderedAt),
                  effectiveProjectName(run, request.projectName),
                  request.domainName,
                  reportSnapshot,
                  action.getReportUrl(),
                  screenshot.getScreenshotFile().getName(),
                  request.theme,
                  request.printDefectGroups,
                  request.projectSummary,
                  request.shouldIncludeProjectSummary(),
                  request.printDefectsOnEmailBody,
                  request.printDefectsOnEmailBodyFilter,
                  request.printDefectsOnEmailBodyLimit,
                  request.printTestersOnEmailBody);
      listener.getLogger().println("Sending Octane report email through Jenkins Mailer.");
      emailSender.send(
          getContext(),
          recipients,
          request.from,
          request.replyTo,
          subject,
          body,
          screenshot.getAttachmentPattern(),
          request.important);
      listener
          .getLogger()
          .println(
              "Jenkins Mailer completed the SMTP handoff for "
                  + visibleRecipients(recipients)
                  + ". Inbox placement is controlled by the receiving mail service.");
      return DeliveryResult.SENT;
    }

    private EnvVars envVars() throws InterruptedException {
      try {
        EnvVars envVars = getContext().get(EnvVars.class);
        return envVars == null ? new EnvVars() : envVars;
      } catch (java.io.IOException e) {
        return new EnvVars();
      }
    }

    private void archiveScreenshot(
        Run<?, ?> run,
        FilePath workspace,
        EnvVars envVars,
        Launcher launcher,
        TaskListener listener,
        OctaneReportScreenshot screenshot)
        throws java.io.IOException, InterruptedException {
      ArtifactArchiver archiver = new ArtifactArchiver(screenshot.getAttachmentPattern());
      archiver.perform(run, workspace, envVars, launcher, listener);
    }

    private String effectiveSubject(Run<?, ?> run, String configuredSubject) {
      String trimmed = Util.trimToEmpty(configuredSubject);
      if (!trimmed.isEmpty()) {
        return trimmed;
      }
      return "Octane Gate Report - "
          + run.getParent().getFullName()
          + " Job #"
          + run.getNumber()
          + " Time {{DURATION}}";
    }

    private String effectiveProjectName(Run<?, ?> run, String configuredProjectName) {
      String trimmed = Util.trimToEmpty(configuredProjectName);
      return trimmed.isEmpty() ? run.getParent().getDisplayName() : trimmed;
    }

    private String visibleRecipients(String recipients) {
      return recipients.replaceAll("bcc:[^,]+", "bcc:***");
    }

    private String remainingTime(
        io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneGateReportSnapshot snapshot,
        Instant now) {
      if (snapshot == null) {
        return "remaining time unavailable";
      }
      try {
        Instant startedAt = Instant.parse(snapshot.getStartedAt());
        long totalSeconds =
            (long) snapshot.getTimeoutSeconds() + snapshot.getTimeoutExtendedSeconds();
        long elapsedSeconds = Math.max(0L, Duration.between(startedAt, now).getSeconds());
        long remainingMinutes = (Math.max(0L, totalSeconds - elapsedSeconds) + 59L) / 60L;
        return formatRemainingTime(remainingMinutes);
      } catch (RuntimeException e) {
        return "remaining time unavailable";
      }
    }

    private String formatRemainingTime(long totalMinutes) {
      long remainderMinutes = Math.max(0L, totalMinutes);
      long weeks = remainderMinutes / 10_080L;
      remainderMinutes %= 10_080L;
      long days = remainderMinutes / 1_440L;
      remainderMinutes %= 1_440L;
      long hours = remainderMinutes / 60L;
      long minutes = remainderMinutes % 60L;

      List<String> parts = new ArrayList<>();
      appendTimePart(parts, weeks, "week");
      appendTimePart(parts, days, "day");
      appendTimePart(parts, hours, "hour");
      appendTimePart(parts, minutes, "minute");
      return parts.isEmpty() ? "no time remaining" : String.join(" ", parts) + " remaining";
    }

    private void appendTimePart(List<String> parts, long value, String unit) {
      if (value > 0L) {
        parts.add(value + " " + unit + (value == 1L ? "" : "s"));
      }
    }

    private void handleFailure(
        Run<?, ?> run,
        TaskListener listener,
        OctaneEmailFailureMode failureMode,
        Exception exception)
        throws AbortException {
      String message = "Octane email report failed: " + defaultMessage(exception);
      if (failureMode == OctaneEmailFailureMode.WARN) {
        listener.getLogger().println("WARNING: " + message);
        return;
      }
      if (failureMode == OctaneEmailFailureMode.UNSTABLE) {
        run.setResult(Result.UNSTABLE);
        listener.getLogger().println(message + " Marking build UNSTABLE and continuing.");
        return;
      }
      AbortException abortException = new AbortException(message);
      abortException.initCause(exception);
      throw abortException;
    }

    private String defaultMessage(Exception exception) {
      String message = exception.getMessage();
      if (message == null || message.isBlank()) {
        return exception.getClass().getSimpleName();
      }
      return message;
    }
  }

  static String replaceRuntimeTokens(
      String value, String remainingTime, String totalTime, Instant renderedAt) {
    String timeRemaining = timeRemainingValue(remainingTime);
    return Util.trimToEmpty(value)
        .replace("{{REMAINING_TIME}}", Util.trimToEmpty(remainingTime))
        .replace("{{TIME_REMAINING}}", timeRemaining)
        .replace("{{DURATION}}", Util.trimToEmpty(totalTime))
        .replace("{{EAT_DATE}}", formatEastAfricaDate(renderedAt));
  }

  private static String timeRemainingValue(String remainingTime) {
    String value = Util.trimToEmpty(remainingTime);
    if (value.endsWith(" remaining")) {
      return value.substring(0, value.length() - " remaining".length());
    }
    return value;
  }

  static String formatEastAfricaDate(Instant instant) {
    return EAST_AFRICA_DATE_FORMATTER.format(instant);
  }

  @Extension
  @Symbol("octaneEmailReport")
  public static class DescriptorImpl extends StepDescriptor {
    @Override
    public String getFunctionName() {
      return "octaneEmailReport";
    }

    @NonNull
    @Override
    public String getDisplayName() {
      return "Email Octane Gate Report";
    }

    @Override
    public Set<? extends Class<?>> getRequiredContext() {
      return Set.of(Run.class, FilePath.class, Launcher.class, TaskListener.class);
    }

    public ListBoxModel doFillOnFailureItems() {
      ListBoxModel model = new ListBoxModel();
      model.add("Mark build unstable and continue", OctaneEmailFailureMode.UNSTABLE.name());
      model.add("Fail the build", OctaneEmailFailureMode.FAILURE.name());
      model.add("Warn and continue", OctaneEmailFailureMode.WARN.name());
      return model;
    }

    public ListBoxModel doFillThemeItems() {
      ListBoxModel model = new ListBoxModel();
      model.add("Light", OctaneReportTheme.LIGHT.name());
      model.add("Dark", OctaneReportTheme.DARK.name());
      model.add("Agent system preference", OctaneReportTheme.SYSTEM.name());
      return model;
    }

    public FormValidation doCheckTo(@QueryParameter String value) {
      return checkOptionalRecipients("To", value);
    }

    public FormValidation doCheckCc(@QueryParameter String value) {
      return checkOptionalRecipients("Cc", value);
    }

    public FormValidation doCheckBcc(@QueryParameter String value) {
      return checkOptionalRecipients("Bcc", value);
    }

    public FormValidation doCheckFrom(@QueryParameter String value) {
      return checkOptionalRecipients("From", value);
    }

    public FormValidation doCheckReplyTo(@QueryParameter String value) {
      return checkOptionalRecipients("Reply-To", value);
    }

    public FormValidation doCheckOnFailure(@QueryParameter String value) {
      try {
        OctaneEmailFailureMode.from(value);
        return FormValidation.ok();
      } catch (IllegalArgumentException e) {
        return FormValidation.error(e.getMessage());
      }
    }

    public FormValidation doCheckTheme(@QueryParameter String value) {
      try {
        OctaneReportTheme.from(value);
        return FormValidation.ok();
      } catch (IllegalArgumentException e) {
        return FormValidation.error(e.getMessage());
      }
    }

    public FormValidation doCheckViewportWidth(@QueryParameter String value) {
      try {
        int width = Integer.parseInt(value);
        if (width < 320 || width > MAX_VIEWPORT_WIDTH) {
          return FormValidation.error(
              "Viewport width must be between 320 and " + MAX_VIEWPORT_WIDTH + ".");
        }
        return FormValidation.ok();
      } catch (NumberFormatException e) {
        return FormValidation.error("Viewport width must be a number.");
      }
    }

    private FormValidation checkOptionalRecipients(String label, String value) {
      if (value != null && value.contains("\n")) {
        return FormValidation.error(
            label + " recipients must be comma, semicolon, or space separated.");
      }
      return FormValidation.ok();
    }
  }
}
