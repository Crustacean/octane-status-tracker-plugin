package io.jenkins.plugins.octanesuitegatebyembiti.services;

import hudson.AbortException;
import hudson.FilePath;
import hudson.model.TaskListener;
import hudson.tasks.Mailer;
import hudson.tasks.SMTPAuthentication;
import io.jenkins.plugins.octanesuitegatebyembiti.utils.Util;
import jakarta.activation.DataHandler;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.Properties;
import jenkins.model.JenkinsLocationConfiguration;
import org.jenkinsci.plugins.workflow.steps.StepContext;

public class JenkinsMailerOctaneReportSender implements OctaneEmailReportSender {
  private static final int MAX_INLINE_IMAGE_BYTES = 25 * 1024 * 1024;

  @Override
  public void validate(String recipients, String from, String replyTo, String subject)
      throws AbortException {
    Mailer.DescriptorImpl descriptor = Mailer.descriptor();
    parseRecipients(recipients);
    resolveSender(
        from, smtpUsername(descriptor), JenkinsLocationConfiguration.get().getAdminAddress());
    String effectiveReplyTo = firstNonBlank(replyTo, descriptor.getReplyToAddress());
    if (!effectiveReplyTo.isEmpty()) {
      validatedAddresses(effectiveReplyTo, "replyTo");
    }
    safeSubject(subject);
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
      boolean important)
      throws Exception {
    Mailer.DescriptorImpl descriptor = Mailer.descriptor();
    TaskListener listener = context.get(TaskListener.class);
    FilePath workspace = context.get(FilePath.class);
    if (listener == null) {
      throw new AbortException("Jenkins task listener is unavailable for octaneEmailReport.");
    }
    if (workspace == null) {
      throw new AbortException("A Jenkins workspace is required to send the Octane report.");
    }

    ParsedRecipients parsedRecipients = parseRecipients(recipients);
    String effectiveFrom =
        resolveSender(
            from, smtpUsername(descriptor), JenkinsLocationConfiguration.get().getAdminAddress());
    Session session = descriptor.createSession();
    logConfiguration(listener, descriptor, session, effectiveFrom);

    MimeMessage message = new MimeMessage(session);
    message.setFrom(validatedAddress(effectiveFrom, "from"));
    setRecipients(message, parsedRecipients);
    String effectiveReplyTo = firstNonBlank(replyTo, descriptor.getReplyToAddress());
    if (!effectiveReplyTo.isEmpty()) {
      message.setReplyTo(validatedAddresses(effectiveReplyTo, "replyTo"));
    }
    message.setSubject(safeSubject(subject), StandardCharsets.UTF_8.name());
    message.setSentDate(new Date());
    message.setContent(relatedContent(body, workspace, safeAttachmentPath(attachmentsPattern)));
    applyPriorityHeaders(message, important);
    message.saveChanges();
    Transport.send(message);
  }

  private void logConfiguration(
      TaskListener listener,
      Mailer.DescriptorImpl descriptor,
      Session session,
      String effectiveFrom) {
    Properties properties = session.getProperties();
    boolean sslEnabled =
        Boolean.parseBoolean(
            defaultValue(
                firstNonBlank(
                    properties.getProperty("mail.smtp.ssl.enable"),
                    properties.getProperty("mail.smtps.ssl.enable")),
                "false"));
    String host =
        defaultValue(
            firstNonBlank(
                properties.getProperty("mail.smtp.host"),
                properties.getProperty("mail.smtps.host")),
            defaultValue(descriptor.getSmtpHost(), "localhost/default"));
    String port =
        defaultValue(
            firstNonBlank(
                properties.getProperty("mail.smtp.port"),
                properties.getProperty("mail.smtps.port")),
            sslEnabled ? "465" : "25");
    listener
        .getLogger()
        .println(
            "Jenkins Mailer SMTP configuration: host="
                + host
                + ", port="
                + port
                + ", SSL="
                + sslEnabled
                + ", from="
                + effectiveFrom
                + ".");
  }

  private String smtpUsername(Mailer.DescriptorImpl descriptor) {
    SMTPAuthentication authentication = descriptor.getAuthentication();
    return authentication == null ? "" : authentication.getUsername();
  }

  static String resolveSender(String configuredFrom, String smtpUsername, String defaultFrom)
      throws AbortException {
    String explicitSender = Util.trimToEmpty(configuredFrom);
    if (!explicitSender.isEmpty()) {
      if (!isUsableSender(explicitSender)) {
        throw new AbortException(
            "octaneEmailReport 'from' is not a valid sender address: " + explicitSender);
      }
      return explicitSender;
    }

    String authenticatedSender = Util.trimToEmpty(smtpUsername);
    if (isUsableSender(authenticatedSender)) {
      return authenticatedSender;
    }

    String jenkinsDefaultSender = Util.trimToEmpty(defaultFrom);
    if (isUsableSender(jenkinsDefaultSender)) {
      return jenkinsDefaultSender;
    }

    throw new AbortException(
        "No valid email sender is configured. Set 'from' on octaneEmailReport or configure the "
            + "SMTP username/default From address under E-mail Notification. Jenkins' "
            + "placeholder nobody@nowhere cannot be used for delivery.");
  }

  private static boolean isUsableSender(String candidate) {
    if (Util.trimToEmpty(candidate).isEmpty()) {
      return false;
    }
    try {
      InternetAddress address = new InternetAddress(candidate, true);
      address.validate();
      String mailbox = Util.trimToEmpty(address.getAddress());
      return mailbox.contains("@") && !mailbox.equalsIgnoreCase("nobody@nowhere");
    } catch (AddressException e) {
      return false;
    }
  }

  private String defaultValue(String value, String fallback) {
    String normalized = Util.trimToEmpty(value);
    return normalized.isEmpty() ? fallback : normalized;
  }

  private String firstNonBlank(String... values) {
    for (String value : values) {
      String normalized = Util.trimToEmpty(value);
      if (!normalized.isEmpty()) {
        return normalized;
      }
    }
    return "";
  }

  static ParsedRecipients parseRecipients(String recipients) throws AbortException {
    ArrayList<InternetAddress> to = new ArrayList<>();
    ArrayList<InternetAddress> cc = new ArrayList<>();
    ArrayList<InternetAddress> bcc = new ArrayList<>();

    for (String rawRecipient : Util.trimToEmpty(recipients).split(",")) {
      String recipient = Util.trimToEmpty(rawRecipient);
      if (recipient.isEmpty()) {
        continue;
      }
      ArrayList<InternetAddress> destination = to;
      if (recipient.regionMatches(true, 0, "cc:", 0, 3)) {
        destination = cc;
        recipient = recipient.substring(3);
      } else if (recipient.regionMatches(true, 0, "bcc:", 0, 4)) {
        destination = bcc;
        recipient = recipient.substring(4);
      }
      destination.add(validatedAddress(recipient, "recipient"));
    }

    if (to.isEmpty() && cc.isEmpty() && bcc.isEmpty()) {
      throw new AbortException("At least one valid email recipient is required.");
    }
    return new ParsedRecipients(
        to.toArray(InternetAddress[]::new),
        cc.toArray(InternetAddress[]::new),
        bcc.toArray(InternetAddress[]::new));
  }

  static String safeAttachmentPath(String attachmentPath) throws AbortException {
    String normalized = Util.trimToEmpty(attachmentPath).replace('\\', '/');
    if (isUnsafeAttachmentPath(normalized)) {
      throw new AbortException("The Octane report screenshot path is invalid.");
    }
    for (String segment : normalized.split("/")) {
      if (isUnsafeAttachmentSegment(segment)) {
        throw new AbortException("The Octane report screenshot path is invalid.");
      }
    }
    return normalized;
  }

  private static boolean isUnsafeAttachmentPath(String path) {
    return path.isEmpty()
        || path.startsWith("/")
        || path.matches("^[A-Za-z]:/.*")
        || path.contains("*")
        || path.contains("?")
        || path.contains("[")
        || path.contains("{");
  }

  private static boolean isUnsafeAttachmentSegment(String segment) {
    return segment.isEmpty() || ".".equals(segment) || "..".equals(segment);
  }

  private static String safeSubject(String subject) throws AbortException {
    String normalized = Util.trimToEmpty(subject);
    if (normalized.contains("\r") || normalized.contains("\n")) {
      throw new AbortException("The Octane report email subject contains an invalid line break.");
    }
    return normalized;
  }

  private static InternetAddress validatedAddress(String address, String field)
      throws AbortException {
    InternetAddress[] addresses = validatedAddresses(address, field);
    if (addresses.length != 1) {
      throw new AbortException(
          "octaneEmailReport '" + field + "' must contain exactly one email address.");
    }
    return addresses[0];
  }

  private static InternetAddress[] validatedAddresses(String addresses, String field)
      throws AbortException {
    try {
      InternetAddress[] parsed = InternetAddress.parse(Util.trimToEmpty(addresses), true);
      if (parsed.length == 0) {
        throw new AddressException("No address");
      }
      for (InternetAddress address : parsed) {
        address.validate();
      }
      return parsed;
    } catch (AddressException e) {
      throw new AbortException(
          "octaneEmailReport '" + field + "' contains an invalid email address.");
    }
  }

  private void setRecipients(MimeMessage message, ParsedRecipients recipients)
      throws MessagingException {
    if (recipients.to().length > 0) {
      message.setRecipients(Message.RecipientType.TO, recipients.to());
    }
    if (recipients.cc().length > 0) {
      message.setRecipients(Message.RecipientType.CC, recipients.cc());
    }
    if (recipients.bcc().length > 0) {
      message.setRecipients(Message.RecipientType.BCC, recipients.bcc());
    }
  }

  /**
   * Uses the three compatibility headers expected by Outlook/mobile, modern webmail, and legacy
   * Unix/Linux mail clients respectively.
   */
  static void applyPriorityHeaders(MimeMessage message, boolean important)
      throws MessagingException {
    if (!important) {
      return;
    }
    message.addHeader("X-Priority", "1");
    message.addHeader("Priority", "Urgent");
    message.addHeader("Importance", "High");
  }

  private MimeMultipart relatedContent(String html, FilePath workspace, String attachmentPath)
      throws IOException, InterruptedException, MessagingException {
    MimeMultipart related = new MimeMultipart("related");
    MimeBodyPart htmlPart = new MimeBodyPart();
    htmlPart.setContent(
        Util.trimToEmpty(html), "text/html; charset=" + StandardCharsets.UTF_8.name());
    related.addBodyPart(htmlPart);

    FilePath imageFile = workspace.child(attachmentPath);
    if (!imageFile.exists() || imageFile.isDirectory()) {
      throw new AbortException("Octane report screenshot was not found: " + attachmentPath);
    }
    byte[] image = readBounded(imageFile);
    String fileName = imageFile.getName();
    if (!fileName.matches("[A-Za-z0-9._-]+")) {
      throw new AbortException("The Octane report screenshot filename is invalid.");
    }

    MimeBodyPart imagePart = new MimeBodyPart();
    imagePart.setDataHandler(
        new DataHandler(new ByteArrayDataSource(image, imageMimeType(fileName))));
    imagePart.setFileName(fileName);
    imagePart.setDisposition(Part.INLINE);
    imagePart.setHeader("Content-ID", "<" + fileName + ">");
    related.addBodyPart(imagePart);
    return related;
  }

  private byte[] readBounded(FilePath imageFile) throws IOException, InterruptedException {
    long declaredLength = imageFile.length();
    if (declaredLength > MAX_INLINE_IMAGE_BYTES) {
      throw new AbortException("Octane report screenshot exceeds the 25 MiB inline-image limit.");
    }
    try (InputStream input = imageFile.read();
        ByteArrayOutputStream output =
            new ByteArrayOutputStream((int) Math.min(Math.max(0L, declaredLength), 8192L))) {
      byte[] buffer = new byte[8192];
      int total = 0;
      int read;
      while ((read = input.read(buffer)) >= 0) {
        total += read;
        if (total > MAX_INLINE_IMAGE_BYTES) {
          throw new AbortException(
              "Octane report screenshot exceeds the 25 MiB inline-image limit.");
        }
        output.write(buffer, 0, read);
      }
      return output.toByteArray();
    }
  }

  private String imageMimeType(String fileName) {
    String normalized = fileName.toLowerCase(Locale.ROOT);
    if (normalized.endsWith(".png")) {
      return "image/png";
    }
    if (normalized.endsWith(".webp")) {
      return "image/webp";
    }
    if (normalized.endsWith(".jpg") || normalized.endsWith(".jpeg")) {
      return "image/jpeg";
    }
    return "application/octet-stream";
  }

  record ParsedRecipients(InternetAddress[] to, InternetAddress[] cc, InternetAddress[] bcc) {}
}
