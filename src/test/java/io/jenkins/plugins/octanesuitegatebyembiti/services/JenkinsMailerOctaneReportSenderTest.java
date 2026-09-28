package io.jenkins.plugins.octanesuitegatebyembiti.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.AbortException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class JenkinsMailerOctaneReportSenderTest {
  @Test
  void prefersExplicitSender() throws Exception {
    assertEquals(
        "Octane Reports <reports@example.com>",
        JenkinsMailerOctaneReportSender.resolveSender(
            "Octane Reports <reports@example.com>",
            "smtp@example.com",
            "Jenkins <jenkins@example.com>"));
  }

  @Test
  void fallsBackToAuthenticatedSmtpIdentityBeforeJenkinsDefault() throws Exception {
    assertEquals(
        "smtp@example.com",
        JenkinsMailerOctaneReportSender.resolveSender(
            "", "smtp@example.com", "address not configured yet <nobody@nowhere>"));
  }

  @Test
  void rejectsJenkinsPlaceholderSender() {
    AbortException exception =
        assertThrows(
            AbortException.class,
            () ->
                JenkinsMailerOctaneReportSender.resolveSender(
                    "", "", "address not configured yet <nobody@nowhere>"));

    assertTrue(exception.getMessage().contains("nobody@nowhere"));
  }

  @Test
  void preservesToCcAndBccRecipientKinds() throws Exception {
    JenkinsMailerOctaneReportSender.ParsedRecipients recipients =
        JenkinsMailerOctaneReportSender.parseRecipients(
            "qa@example.com,cc:lead@example.com,bcc:audit@example.com");

    assertEquals("qa@example.com", recipients.to()[0].getAddress());
    assertEquals("lead@example.com", recipients.cc()[0].getAddress());
    assertEquals("audit@example.com", recipients.bcc()[0].getAddress());
  }

  @Test
  void rejectsInvalidRecipients() {
    assertThrows(
        AbortException.class,
        () -> JenkinsMailerOctaneReportSender.parseRecipients("not-an-address"));
  }

  @Test
  void rejectsAttachmentPathTraversalAndPatterns() {
    assertThrows(
        AbortException.class,
        () -> JenkinsMailerOctaneReportSender.safeAttachmentPath("../report.png"));
    assertThrows(
        AbortException.class,
        () -> JenkinsMailerOctaneReportSender.safeAttachmentPath("reports/*.png"));
  }

  @Test
  void attachesAllProviderPriorityHeadersToOutboundMimeMessage() throws Exception {
    MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));

    JenkinsMailerOctaneReportSender.applyPriorityHeaders(message, true);
    message.saveChanges();

    assertEquals("1", message.getHeader("X-Priority", null));
    assertEquals("Urgent", message.getHeader("Priority", null));
    assertEquals("High", message.getHeader("Importance", null));
  }

  @Test
  void leavesPriorityHeadersAbsentForStandardMessages() throws Exception {
    MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));

    JenkinsMailerOctaneReportSender.applyPriorityHeaders(message, false);
    message.saveChanges();

    assertNull(message.getHeader("X-Priority"));
    assertNull(message.getHeader("Priority"));
    assertNull(message.getHeader("Importance"));
  }
}
