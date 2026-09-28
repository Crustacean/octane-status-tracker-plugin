package io.jenkins.plugins.octanesuitegatebyembiti.services;

import org.jenkinsci.plugins.workflow.steps.StepContext;

public interface OctaneEmailReportSender {
  default void validate(String recipients, String from, String replyTo, String subject)
      throws Exception {
    // Alternative senders may not require configuration preflight.
  }

  void send(
      StepContext context,
      String recipients,
      String from,
      String replyTo,
      String subject,
      String body,
      String attachmentsPattern,
      boolean important)
      throws Exception;
}
