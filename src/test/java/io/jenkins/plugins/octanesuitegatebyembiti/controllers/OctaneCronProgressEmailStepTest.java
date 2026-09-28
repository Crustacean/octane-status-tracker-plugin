package io.jenkins.plugins.octanesuitegatebyembiti.controllers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.model.Result;
import io.jenkins.plugins.octanesuitegatebyembiti.entities.RunRecord;
import io.jenkins.plugins.octanesuitegatebyembiti.models.GateMetrics;
import io.jenkins.plugins.octanesuitegatebyembiti.models.GateRequest;
import io.jenkins.plugins.octanesuitegatebyembiti.models.GateResult;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneGateReportSnapshot;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneGateReportState;
import io.jenkins.plugins.octanesuitegatebyembiti.models.StatusClassifier;
import io.jenkins.plugins.octanesuitegatebyembiti.services.OctaneProgressEmailScheduler;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

public class OctaneCronProgressEmailStepTest {
  @Rule public JenkinsRule jenkins = new JenkinsRule();

  @After
  public void resetServices() {
    OctaneEmailReportStep.resetServicesForTesting();
  }

  @Test
  public void readsProgressEmailStalenessThresholdFromEnvironment() throws Exception {
    assertEquals(
        Duration.ofMinutes(1L), OctaneCronProgressEmailStep.stalenessThreshold(new EnvVars()));
    EnvVars configured = new EnvVars();
    configured.put(OctaneCronProgressEmailStep.STALENESS_THRESHOLD_ENV, "7");
    assertEquals(
        Duration.ofMinutes(7L), OctaneCronProgressEmailStep.stalenessThreshold(configured));

    configured.put(OctaneCronProgressEmailStep.STALENESS_THRESHOLD_ENV, "-1");
    try {
      OctaneCronProgressEmailStep.stalenessThreshold(configured);
      fail("Expected a negative staleness threshold to be rejected.");
    } catch (AbortException expected) {
      assertTrue(expected.getMessage().contains("whole number of zero or greater"));
    }
  }

  @Test
  public void normalizesProgressEmailIntervalTimeoutFlag() throws Exception {
    EnvVars configured = new EnvVars();
    assertFalse(OctaneCronProgressEmailStep.intervalTimeoutEnabled(configured));

    configured.put(OctaneCronProgressEmailStep.INTERVAL_TIMEOUT_ENV, " TRUE ");
    assertTrue(OctaneCronProgressEmailStep.intervalTimeoutEnabled(configured));
    configured.put(OctaneCronProgressEmailStep.INTERVAL_TIMEOUT_ENV, "1");
    assertTrue(OctaneCronProgressEmailStep.intervalTimeoutEnabled(configured));
    configured.put(OctaneCronProgressEmailStep.INTERVAL_TIMEOUT_ENV, " False ");
    assertFalse(OctaneCronProgressEmailStep.intervalTimeoutEnabled(configured));
    configured.put(OctaneCronProgressEmailStep.INTERVAL_TIMEOUT_ENV, "0");
    assertFalse(OctaneCronProgressEmailStep.intervalTimeoutEnabled(configured));

    configured.put(OctaneCronProgressEmailStep.INTERVAL_TIMEOUT_ENV, "sometimes");
    try {
      OctaneCronProgressEmailStep.intervalTimeoutEnabled(configured);
      fail("Expected an unsupported interval timeout value to be rejected.");
    } catch (AbortException expected) {
      assertTrue(expected.getMessage().contains("true, false, 1, or 0"));
    }
  }

  @Test
  public void firstEmailAlwaysSendsAndSubsequentTicksHonorTimeoutFlag() {
    assertFalse(OctaneCronProgressEmailStep.progressEmailsEnabled(""));
    assertFalse(OctaneCronProgressEmailStep.progressEmailsEnabled("  "));
    assertTrue(OctaneCronProgressEmailStep.progressEmailsEnabled("* * * * *"));
    assertTrue(OctaneCronProgressEmailStep.shouldSendProgressEmail(null, 0.0, true));
    assertFalse(OctaneCronProgressEmailStep.shouldSendProgressEmail(40.0, 40.0, true));
    assertTrue(OctaneCronProgressEmailStep.shouldSendProgressEmail(40.0, 41.0, true));
    assertTrue(OctaneCronProgressEmailStep.shouldSendProgressEmail(40.0, 40.0, false));
    assertEquals(
        "Skipping scheduled Octane progress email because execution progress remains at 40.00% "
            + "and PROGRESS_EMAIL_INTERVAL_TIMEOUT is enabled.",
        OctaneCronProgressEmailStep.stagnantProgressMessage(40.0));
  }

  @Test
  public void waitingSnapshotCannotTriggerFirstIntervalEmail() {
    OctaneGateReportSnapshot waiting =
        OctaneGateReportSnapshot.waiting(
            new GateRequest("octane-prod", "4501"), 30, "2026-07-20T12:00:00Z");

    assertFalse(OctaneCronProgressEmailStep.hasRenderableReportData(null));
    assertFalse(OctaneCronProgressEmailStep.hasRenderableReportData(waiting));
  }

  @Test
  public void sixMinuteExtensionSendsFiveCronEmailsAndYieldsTheSixth() {
    Instant startedAt = Instant.parse("2026-07-20T09:52:00Z");
    int primaryTimeoutSeconds = 120;
    int extendedTimeoutSeconds = 360;
    OctaneGateReportSnapshot snapshot =
        snapshot(
            OctaneGateReportState.EXTENDED_TIME,
            startedAt,
            primaryTimeoutSeconds,
            extendedTimeoutSeconds);
    Instant extendedStartedAt = startedAt.plusSeconds(primaryTimeoutSeconds);
    AtomicInteger sendMailCalls = new AtomicInteger();
    Runnable sendMail = sendMailCalls::incrementAndGet;

    for (int cronTick = 0; cronTick < 6; cronTick++) {
      OctaneProgressEmailScheduler.Occurrence occurrence =
          new OctaneProgressEmailScheduler.Occurrence(
              "* * * * 1-5",
              "Every minute on every day-of-week from Monday through Friday.",
              extendedStartedAt.plus(Duration.ofMinutes(cronTick)));
      long expectedRemainingSeconds = extendedTimeoutSeconds - (cronTick * 60L);
      assertEquals(
          expectedRemainingSeconds,
          OctaneCronProgressEmailStep.activeTimeRemainingSeconds(
              snapshot, occurrence.scheduledAt()));
      if (!OctaneCronProgressEmailStep.shouldYieldToFinalEmail(
          snapshot, occurrence.scheduledAt())) {
        sendMail.run();
      }
    }

    assertEquals(100.0, snapshot.getExecutionProgress(), 0.0);
    assertEquals(5, sendMailCalls.get());
    assertEquals(
        "Interval email suppressed. Run closure is imminent (time remaining: 60s). "
            + "Yielding to Final Email.",
        OctaneCronProgressEmailStep.closureImminentMessage(60L));
  }

  @Test
  public void totalExecutionWindowIncludesPrimaryAndExtendedTimeouts() {
    Instant startedAt = Instant.parse("2026-07-20T09:52:00Z");
    OctaneGateReportSnapshot snapshot =
        snapshot(OctaneGateReportState.POLLING, startedAt, 120, 360);
    Instant forcedClosureAt = startedAt.plusSeconds(480L);

    assertEquals(
        420L,
        OctaneCronProgressEmailStep.activeTimeRemainingSeconds(
            snapshot, startedAt.plusSeconds(60L)));
    assertFalse(
        OctaneCronProgressEmailStep.shouldYieldToFinalEmail(snapshot, startedAt.plusSeconds(60L)));
    assertEquals(
        61L,
        OctaneCronProgressEmailStep.activeTimeRemainingSeconds(
            snapshot, forcedClosureAt.minusSeconds(60L).minusMillis(1L)));
    assertEquals(
        60L,
        OctaneCronProgressEmailStep.activeTimeRemainingSeconds(
            snapshot, forcedClosureAt.minusSeconds(60L)));
  }

  @Test
  public void completedExecutionWithoutExtensionYieldsImmediately() {
    Instant startedAt = Instant.parse("2026-07-20T09:52:00Z");
    OctaneGateReportSnapshot snapshot =
        snapshot(OctaneGateReportState.POLLING, startedAt, 7_200, 0, "skipped");

    assertEquals(0.0, snapshot.getExecutionProgress(), 0.0);
    assertEquals(100.0, snapshot.getCompletionProgress(), 0.0);
    assertEquals(
        0L,
        OctaneCronProgressEmailStep.activeTimeRemainingSeconds(
            snapshot, startedAt.plusSeconds(60L)));
    assertTrue(
        OctaneCronProgressEmailStep.shouldYieldToFinalEmail(snapshot, startedAt.plusSeconds(60L)));
  }

  @Test
  public void blankCronDisablesEmailAndRunsBody() throws Exception {
    AtomicInteger screenshots = new AtomicInteger();
    AtomicInteger messages = new AtomicInteger();
    OctaneEmailReportStep.setServicesForTesting(
        (snapshot, workspace, envVars, launcher, listener, browserPath, viewportWidth, theme) -> {
          screenshots.incrementAndGet();
          return null;
        },
        (context, recipients, from, replyTo, subject, body, attachmentsPattern, important) ->
            messages.incrementAndGet());
    WorkflowJob job = jenkins.createProject(WorkflowJob.class);
    job.setDefinition(
        new CpsFlowDefinition(
            "node { withEnv(['PROGRESS_EMAIL_INTERVAL_TIMEOUT=invalid']) { "
                + "octaneCronProgressEmail(cron: '', to: 'qa@example.com') { } } }",
            true));

    WorkflowRun run = jenkins.buildAndAssertSuccess(job);

    jenkins.assertLogContains("PROGRESS_EMAIL_INTERVAL_CRONJOB is blank", run);
    assertEquals(0, OctaneProgressEmailScheduler.get().activeScheduleCount());
    assertEquals(0, screenshots.get());
    assertEquals(0, messages.get());
  }

  @Test
  public void invalidCronFailsBeforeRunningBody() throws Exception {
    WorkflowJob job = jenkins.createProject(WorkflowJob.class);
    job.setDefinition(
        new CpsFlowDefinition(
            "node { octaneCronProgressEmail(cron: 'not cron', to: 'qa@example.com') { } }", true));

    WorkflowRun run = job.scheduleBuild2(0).get();

    jenkins.assertBuildStatus(Result.FAILURE, run);
    jenkins.assertLogContains("Unable to schedule Octane progress emails", run);
    assertEquals(0, OctaneProgressEmailScheduler.get().activeScheduleCount());
  }

  @Test
  public void completedBodyImmediatelyCancelsFutureCronTrigger() throws Exception {
    WorkflowJob job = jenkins.createProject(WorkflowJob.class);
    job.setDefinition(
        new CpsFlowDefinition(
            "node { octaneCronProgressEmail(cron: '0 0 1 1 *', to: 'qa@example.com') { } }", true));

    WorkflowRun run = jenkins.buildAndAssertSuccess(job);

    jenkins.assertLogContains("Cron job time: 0 0 1 1 * will run \"According to cron fields:", run);
    jenkins.assertLogContains("next at ", run);
    assertEquals(0, OctaneProgressEmailScheduler.get().activeScheduleCount());
  }

  private OctaneGateReportSnapshot snapshot(
      OctaneGateReportState state,
      Instant startedAt,
      int primaryTimeoutSeconds,
      int extendedTimeoutSeconds) {
    return snapshot(state, startedAt, primaryTimeoutSeconds, extendedTimeoutSeconds, "passed");
  }

  private OctaneGateReportSnapshot snapshot(
      OctaneGateReportState state,
      Instant startedAt,
      int primaryTimeoutSeconds,
      int extendedTimeoutSeconds,
      String status) {
    List<RunRecord> runs = List.of(new RunRecord("1", "Checkout", status, "Ada Tester"));
    GateResult result =
        new GateResult(
            "4501",
            "regressions.executionRate == 100",
            true,
            true,
            GateMetrics.fromRuns(runs, classifier()),
            runs,
            Map.of("4501", runs),
            Map.of(),
            startedAt);
    return OctaneGateReportSnapshot.fromResult(
        state,
        "Polling ALM Octane suite runs.",
        result,
        classifier(),
        30,
        primaryTimeoutSeconds,
        extendedTimeoutSeconds,
        startedAt.toString());
  }

  private StatusClassifier classifier() {
    return new StatusClassifier(
        StatusClassifier.DEFAULT_PASSED_STATUSES,
        StatusClassifier.DEFAULT_FAILED_STATUSES,
        StatusClassifier.DEFAULT_NEUTRAL_STATUSES,
        StatusClassifier.DEFAULT_RUNNING_STATUSES);
  }
}
