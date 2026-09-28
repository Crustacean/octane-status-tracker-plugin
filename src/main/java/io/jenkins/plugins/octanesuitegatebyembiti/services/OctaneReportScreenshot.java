package io.jenkins.plugins.octanesuitegatebyembiti.services;

import hudson.FilePath;

public class OctaneReportScreenshot {
  private final FilePath htmlFile;
  private final FilePath screenshotFile;
  private final String attachmentPattern;

  public OctaneReportScreenshot(
      FilePath htmlFile, FilePath screenshotFile, String attachmentPattern) {
    this.htmlFile = htmlFile;
    this.screenshotFile = screenshotFile;
    this.attachmentPattern = attachmentPattern;
  }

  public FilePath getHtmlFile() {
    return htmlFile;
  }

  public FilePath getScreenshotFile() {
    return screenshotFile;
  }

  public String getAttachmentPattern() {
    return attachmentPattern;
  }
}
