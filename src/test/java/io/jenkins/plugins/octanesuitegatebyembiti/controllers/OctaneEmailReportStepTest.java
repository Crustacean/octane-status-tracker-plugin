package io.jenkins.plugins.octanesuitegatebyembiti.controllers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import hudson.AbortException;
import hudson.FilePath;
import hudson.model.Result;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import io.jenkins.plugins.octanesuitegatebyembiti.actions.OctaneGateReportAction;
import io.jenkins.plugins.octanesuitegatebyembiti.entities.RunRecord;
import io.jenkins.plugins.octanesuitegatebyembiti.models.GateMetrics;
import io.jenkins.plugins.octanesuitegatebyembiti.models.GateRequest;
import io.jenkins.plugins.octanesuitegatebyembiti.models.GateResult;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneGateReportSnapshot;
import io.jenkins.plugins.octanesuitegatebyembiti.models.StatusClassifier;
import io.jenkins.plugins.octanesuitegatebyembiti.services.OctaneEmailReportSender;
import io.jenkins.plugins.octanesuitegatebyembiti.services.OctaneReportScreenshot;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;

public class OctaneEmailReportStepTest {
  @Rule public JenkinsRule jenkins = new JenkinsRule();

  @After
  public void resetServices() {
    OctaneEmailReportStep.resetServicesForTesting();
  }

  @Test
  public void composesToCcAndBccRecipientsForMailer() {
    assertEquals(
        "qa@example.com,dev@example.com,cc:lead@example.com,bcc:audit@example.com",
        OctaneEmailReportStep.composeRecipients(
            "qa@example.com dev@example.com", "lead@example.com", "audit@example.com"));
  }

  @Test
  public void formatsEmailDateUsingEastAfricaTime() {
    assertEquals(
        "01.07.2026",
        OctaneEmailReportStep.formatEastAfricaDate(Instant.parse("2026-06-30T21:30:00Z")));
  }

  @Test
  public void rendersDurationInFinalEmailSubjectTokens() {
    assertEquals(
        "Job #42 Time 2 minutes | 01.07.2026",
        OctaneEmailReportStep.replaceRuntimeTokens(
            "Job #42 Time {{DURATION}} | {{EAT_DATE}}",
            "",
            "2 minutes",
            Instant.parse("2026-06-30T21:30:00Z")));
  }

  @Test
  public void rendersDurationAndRemainingTimeTokens() {
    assertEquals(
        "Elapsed 2 minutes; 3 minutes remaining; legacy 3 minutes remaining",
        OctaneEmailReportStep.replaceRuntimeTokens(
            "Elapsed {{DURATION}}; {{TIME_REMAINING}} remaining; legacy {{REMAINING_TIME}}",
            "3 minutes remaining",
            "2 minutes",
            Instant.parse("2026-06-30T21:30:00Z")));
  }

  @Test
  public void projectSummaryPolicyDistinguishesFinalAndIntervalEmails() {
    OctaneEmailReportStep finalStep = new OctaneEmailReportStep("qa@example.com");
    OctaneCronProgressEmailStep intervalStep =
        new OctaneCronProgressEmailStep("* * * * *", "qa@example.com");

    assertTrue(finalStep.toRequest().shouldIncludeProjectSummary());
    assertFalse(intervalStep.toRequest(true).shouldIncludeProjectSummary());
    intervalStep.setPrintProjectSummaryOnIntervalEmails(true);
    assertTrue(intervalStep.toRequest(true).shouldIncludeProjectSummary());
  }

  @Test
  public void missingReportActionCanWarnAndContinue() throws Exception {
    WorkflowJob job = jenkins.createProject(WorkflowJob.class);
    job.setDefinition(
        new CpsFlowDefinition(
            "node { octaneEmailReport(to: 'qa@example.com', onFailure: 'WARN') }", true));

    WorkflowRun run = jenkins.buildAndAssertSuccess(job);

    jenkins.assertLogContains("WARNING: Octane email report failed", run);
  }

  @Test
  public void missingReportActionCanMarkBuildUnstable() throws Exception {
    WorkflowJob job = jenkins.createProject(WorkflowJob.class);
    job.setDefinition(
        new CpsFlowDefinition(
            "node { octaneEmailReport(to: 'qa@example.com', onFailure: 'UNSTABLE') }", true));

    WorkflowRun run = job.scheduleBuild2(0).get();

    jenkins.assertBuildStatus(Result.UNSTABLE, run);
    jenkins.assertLogContains("Marking build UNSTABLE and continuing.", run);
  }

  @Test
  public void missingReportActionCanFailBuild() throws Exception {
    WorkflowJob job = jenkins.createProject(WorkflowJob.class);
    job.setDefinition(
        new CpsFlowDefinition(
            "node { octaneEmailReport(to: 'qa@example.com', onFailure: 'FAILURE') }", true));

    WorkflowRun run = job.scheduleBuild2(0).get();

    jenkins.assertBuildStatus(Result.FAILURE, run);
    jenkins.assertLogContains("Octane email report failed", run);
  }

  @Test
  public void invalidFailureModeFailsClearly() throws Exception {
    WorkflowJob job = jenkins.createProject(WorkflowJob.class);
    job.setDefinition(
        new CpsFlowDefinition(
            "node { octaneEmailReport(to: 'qa@example.com', onFailure: 'BROKEN') }", true));

    WorkflowRun run = job.scheduleBuild2(0).get();

    jenkins.assertBuildStatus(Result.FAILURE, run);
    String log = JenkinsRule.getLog(run);
    assertTrue(log.contains("onFailure must be one of UNSTABLE, FAILURE, or WARN."));
  }

  @Test
  public void senderPreflightFailureSkipsScreenshotAndSmtpWork() throws Exception {
    AtomicBoolean screenshotRequested = new AtomicBoolean();
    AtomicBoolean smtpRequested = new AtomicBoolean();
    OctaneEmailReportStep.setServicesForTesting(
        (snapshot, workspace, envVars, launcher, listener, browserPath, viewportWidth, theme) -> {
          screenshotRequested.set(true);
          return null;
        },
        new OctaneEmailReportSender() {
          @Override
          public void validate(String recipients, String from, String replyTo, String subject)
              throws Exception {
            throw new AbortException("recipient preflight rejected the email");
          }

          @Override
          public void send(
              StepContext context,
              String recipients,
              String from,
              String replyTo,
              String subject,
              String body,
              String attachmentsPattern,
              boolean important) {
            smtpRequested.set(true);
          }
        });

    WorkflowJob job = jenkins.createProject(WorkflowJob.class, "email-preflight-rejected");
    job.setDefinition(
        new CpsFlowDefinition(
            "node { octaneEmailReport(to: 'bad address', onFailure: 'WARN') }", true));

    WorkflowRun run = jenkins.buildAndAssertSuccess(job);

    jenkins.assertLogContains("recipient preflight rejected the email", run);
    assertFalse(screenshotRequested.get());
    assertFalse(smtpRequested.get());
  }

  @Test
  public void ongoingIntervalCalculationSendsRenderedReport() throws Exception {
    AtomicReference<String> sentRecipients = new AtomicReference<>();
    AtomicReference<String> sentSubject = new AtomicReference<>();
    AtomicReference<String> sentBody = new AtomicReference<>();
    AtomicReference<String> sentAttachment = new AtomicReference<>();
    AtomicBoolean sentImportant = new AtomicBoolean();
    AtomicReference<OctaneGateReportSnapshot> screenshotSnapshot = new AtomicReference<>();
    List<String> deliveryEvents = new CopyOnWriteArrayList<>();

    OctaneEmailReportStep.setServicesForTesting(
        (snapshot, workspace, envVars, launcher, listener, browserPath, viewportWidth, theme) -> {
          deliveryEvents.add("artifacts-rendered");
          screenshotSnapshot.set(snapshot);
          WorkflowJob currentJob =
              jenkins.jenkins.getItemByFullName("interval-email-success", WorkflowJob.class);
          OctaneGateReportAction currentAction =
              currentJob.getLastBuild().getAction(OctaneGateReportAction.class);
          currentAction.onError(
              "A newer action snapshot must not leak into this email.",
              new GateRequest("octane-prod", "4501"));
          FilePath reportDirectory = workspace.child("interval-email-test");
          reportDirectory.mkdirs();
          FilePath htmlFile = reportDirectory.child("report.html");
          FilePath screenshotFile = reportDirectory.child("report.png");
          htmlFile.write("<html><body>Report</body></html>", "UTF-8");
          screenshotFile.write("screenshot", "UTF-8");
          return new OctaneReportScreenshot(
              htmlFile, screenshotFile, "interval-email-test/report.png");
        },
        (context, recipients, from, replyTo, subject, body, attachmentsPattern, important) -> {
          deliveryEvents.add("smtp");
          sentRecipients.set(recipients);
          sentSubject.set(subject);
          sentBody.set(body);
          sentAttachment.set(attachmentsPattern);
          sentImportant.set(important);
        });

    WorkflowJob job = jenkins.createProject(WorkflowJob.class, "interval-email-success");
    job.setDefinition(
        new CpsFlowDefinition(
            """
            node {
              long gateDeadlineMillis = System.currentTimeMillis() + 120000L
              long nowMillis = System.currentTimeMillis()
              long remainingMillis = gateDeadlineMillis - nowMillis
              long remainingSeconds = (remainingMillis + 999L) / 1000L
              long remainingMinutes = (remainingSeconds + 59L) / 60L
              octaneEmailReport(
                  to: 'qa@example.com',
                  subject: 'Interval {{EAT_DATE}} EAT {{REMAINING_TIME}}',
                  body: 'State {{GATE_RESULT}} with execution {{EXECUTIONRATE}}, pass {{PASSRATE}}, '
                      + '{{TIME_REMAINING}} remaining; updated {{LAST_UPDATE}} '
                      + '{{EXECUTION_DETAILS}} {{REPORT_SCREENSHOT}}',
                  onFailure: 'FAILURE',
                  theme: 'DARK',
                  printTestersOnEmailBody: true,
                  important: true,
                  archiveScreenshot: false)
            }
            """,
            true));

    WorkflowRun run = jenkins.buildAndAssertSuccess(job);

    assertEquals("qa@example.com", sentRecipients.get());
    assertTrue(sentSubject.get().startsWith("Interval "));
    assertTrue(sentSubject.get().matches("Interval \\d{2}\\.\\d{2}\\.\\d{4} EAT .+"));
    assertTrue(sentSubject.get().endsWith("remaining"));
    assertFalse(sentSubject.get().contains("{{EAT_DATE}}"));
    assertFalse(sentSubject.get().contains("{{REMAINING_TIME}}"));
    assertTrue(sentBody.get().contains("color:#FF9F0A;font-weight:700;\">ONGOING"));
    assertTrue(sentBody.get().contains("execution 100.00%, pass 50.00%,"));
    assertTrue(sentBody.get().contains("remaining; updated"));
    assertFalse(sentBody.get().contains("{{TIME_REMAINING}}"));
    assertTrue(sentBody.get().contains("src=\"cid:report.png\""));
    assertFalse(sentBody.get().contains("{{LAST_UPDATE}}"));
    assertFalse(sentBody.get().contains("{{EXECUTIONRATE}}"));
    assertFalse(sentBody.get().contains("{{PASSRATE}}"));
    assertTrue(sentBody.get().contains("Execution Status per tester"));
    assertTrue(sentBody.get().contains("data-octane-email-table=\"tester-execution\""));
    assertTrue(sentBody.get().contains(">ada tester</th>"));
    assertEquals("interval-email-test/report.png", sentAttachment.get());
    assertTrue(sentImportant.get());
    assertNotNull(screenshotSnapshot.get());
    assertTrue(screenshotSnapshot.get().isBuilding());
    assertTrue(screenshotSnapshot.get().hasReportSections());
    assertEquals(List.of("artifacts-rendered", "smtp"), deliveryEvents);
    assertFalse(sentBody.get().contains("A newer action snapshot must not leak"));
    jenkins.assertLogContains("Jenkins Mailer completed the SMTP handoff", run);
  }

  @TestExtension
  public static class AttachIntervalReportAction extends RunListener<WorkflowRun> {
    @Override
    public void onStarted(WorkflowRun run, TaskListener listener) {
      if ("interval-email-success".equals(run.getParent().getName())
          || "email-preflight-rejected".equals(run.getParent().getName())) {
        OctaneGateReportAction action =
            OctaneGateReportAction.attachTo(run, new GateRequest("octane-prod", "4501"));
        List<RunRecord> records =
            List.of(
                new RunRecord("1", "Checkout", "passed", "Ada Tester"),
                new RunRecord("2", "Refund", "failed", "Ada Tester"));
        action.onPoll(
            new GateResult(
                "4501",
                "regressions.executionRate >= 0",
                false,
                false,
                GateMetrics.fromRuns(records, classifier()),
                records,
                Map.of("4501", records),
                Map.of(),
                Instant.parse("2026-07-20T12:00:00Z")),
            classifier());
      }
    }

    private static StatusClassifier classifier() {
      return new StatusClassifier(
          StatusClassifier.DEFAULT_PASSED_STATUSES,
          StatusClassifier.DEFAULT_FAILED_STATUSES,
          StatusClassifier.DEFAULT_NEUTRAL_STATUSES,
          StatusClassifier.DEFAULT_RUNNING_STATUSES);
    }
  }
}
