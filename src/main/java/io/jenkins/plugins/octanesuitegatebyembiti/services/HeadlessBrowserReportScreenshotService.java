package io.jenkins.plugins.octanesuitegatebyembiti.services;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Proc;
import hudson.model.TaskListener;
import io.jenkins.plugins.octanesuitegatebyembiti.controllers.OctaneEmailReportStep;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneGateReportSnapshot;
import io.jenkins.plugins.octanesuitegatebyembiti.utils.Util;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HeadlessBrowserReportScreenshotService implements OctaneReportScreenshotService {
  public static final String REPORT_EMAIL_DIR = ".octane-suite-gate/report-email";
  public static final String SCREENSHOT_FILE_NAME = "octane-report-zone.webp";
  public static final String HTML_FILE_NAME = "octane-report-zone.html";
  public static final String ATTACHMENT_PATTERN = REPORT_EMAIL_DIR + "/" + SCREENSHOT_FILE_NAME;
  static final int BROWSER_PROBE_TIMEOUT_SECONDS = 15;
  static final int SCREENSHOT_TIMEOUT_SECONDS = 60;
  static final int MAX_SCREENSHOT_HEIGHT = 16_384;
  private static final int MAX_BROWSER_PROBE_OUTPUT_BYTES = 64 * 1024;
  private static final Pattern CAPTURE_HEIGHT_PATTERN =
      Pattern.compile("data-octane-capture-height=[\\\"'](\\d{1,5})[\\\"']");

  private static final List<String> BROWSER_CANDIDATES =
      List.of("chromium", "chromium-browser", "google-chrome", "google-chrome-stable");

  private final OctaneReportZoneHtmlRenderer renderer;

  public HeadlessBrowserReportScreenshotService() {
    this(new OctaneReportZoneHtmlRenderer());
  }

  HeadlessBrowserReportScreenshotService(OctaneReportZoneHtmlRenderer renderer) {
    this.renderer = renderer;
  }

  @Override
  public OctaneReportScreenshot capture(
      OctaneGateReportSnapshot snapshot,
      FilePath workspace,
      EnvVars envVars,
      Launcher launcher,
      TaskListener listener,
      String browserPath,
      int viewportWidth,
      String theme)
      throws IOException, InterruptedException {
    FilePath outputDirectory = workspace.child(REPORT_EMAIL_DIR);
    outputDirectory.mkdirs();
    FilePath htmlFile = outputDirectory.child(HTML_FILE_NAME);
    FilePath screenshotFile = outputDirectory.child(SCREENSHOT_FILE_NAME);

    int width = Math.min(OctaneEmailReportStep.MAX_VIEWPORT_WIDTH, Math.max(320, viewportWidth));
    htmlFile.write(renderer.render(snapshot, theme, width), StandardCharsets.UTF_8.name());

    FilePath browserProfileDirectory = outputDirectory.child("chrome-profile");
    if (browserProfileDirectory.exists()) {
      browserProfileDirectory.deleteRecursive();
    }
    browserProfileDirectory.mkdirs();
    listener.getLogger().println("Preparing headless browser for Octane report capture.");
    String browser =
        resolveBrowser(
            browserPath, envVars, launcher, listener, browserProfileDirectory.getRemote());

    String reportUrl = toFileUrl(htmlFile.getRemote());
    int estimatedHeight = estimateViewportHeight(snapshot, width);
    int height =
        measureRenderedHeight(
            browser,
            browserProfileDirectory.getRemote(),
            reportUrl,
            width,
            estimatedHeight,
            outputDirectory,
            launcher,
            listener);
    List<String> command =
        screenshotCommand(
            browser,
            browserProfileDirectory.getRemote(),
            screenshotFile.getRemote(),
            reportUrl,
            width,
            height);

    listener
        .getLogger()
        .println(
            "Capturing Octane report-zone screenshot (timeout "
                + SCREENSHOT_TIMEOUT_SECONDS
                + " seconds).");
    Proc process =
        launcher
            .launch()
            .cmds(command)
            .pwd(outputDirectory)
            .stdout(listener)
            .stderr(listener.getLogger())
            .start();
    int exitCode =
        joinWithTimeout(
            process, SCREENSHOT_TIMEOUT_SECONDS, listener, "Headless browser screenshot capture");
    if (exitCode != 0) {
      throw new AbortException("Headless browser exited with status " + exitCode + ".");
    }
    if (!screenshotFile.exists() || screenshotFile.length() == 0) {
      throw new AbortException("Headless browser did not create " + SCREENSHOT_FILE_NAME + ".");
    }
    if (!hasWebpSignature(screenshotFile)) {
      throw new AbortException(
          "Headless browser did not encode " + SCREENSHOT_FILE_NAME + " as WebP.");
    }
    listener.getLogger().println("Octane report-zone screenshot captured successfully.");
    return new OctaneReportScreenshot(htmlFile, screenshotFile, ATTACHMENT_PATTERN);
  }

  String resolveBrowser(
      String configuredBrowserPath,
      EnvVars envVars,
      Launcher launcher,
      TaskListener listener,
      String profileDirectory)
      throws IOException, InterruptedException {
    String configured = normalizeBrowserPath(configuredBrowserPath);
    if (!configured.isEmpty()) {
      return validateConfiguredBrowser(configured, profileDirectory, launcher, listener);
    }

    String envBrowser = envVars == null ? "" : normalizeBrowserPath(envVars.get("CHROME_BIN"));
    if (!envBrowser.isEmpty()
        && probeBrowser(envBrowser, profileDirectory, launcher).successful()) {
      return envBrowser;
    }

    for (String candidate : BROWSER_CANDIDATES) {
      if (probeBrowser(candidate, profileDirectory, launcher).successful()) {
        return candidate;
      }
    }
    throw new AbortException(
        "Chrome or Chromium was not found on this Jenkins agent. "
            + "Install it or pass browserPath to octaneEmailReport.");
  }

  private String validateConfiguredBrowser(
      String configured, String profileDirectory, Launcher launcher, TaskListener listener)
      throws IOException, InterruptedException {
    BrowserProbeResult result = probeBrowser(configured, profileDirectory, launcher);
    if (result.successful()) {
      listener.getLogger().println("Configured headless browser validated successfully.");
      return configured;
    }
    if (result.timedOut()) {
      throw new AbortException(
          "Configured browserPath did not complete a headless startup check within "
              + BROWSER_PROBE_TIMEOUT_SECONDS
              + " seconds. Verify that the path exists on the executing Jenkins agent and that "
              + "the Jenkins service account can run Chrome or Chromium.");
    }
    throw new AbortException(
        "Configured browserPath could not run in headless mode (exit "
            + result.exitCode()
            + ")."
            + formattedProbeOutput(result.output()));
  }

  String toFileUrl(String remotePath) throws IOException {
    String normalized = remotePath.replace('\\', '/');
    if (!normalized.startsWith("/")) {
      normalized = "/" + normalized;
    }
    try {
      return "file://" + new URI(null, null, normalized, null).toASCIIString();
    } catch (URISyntaxException e) {
      throw new IOException("Unable to build file URL for " + remotePath, e);
    }
  }

  List<String> browserProbeCommand(String browser, String profileDirectory) {
    return List.of(
        browser,
        "--headless",
        "--disable-gpu",
        "--disable-background-networking",
        "--disable-extensions",
        "--no-first-run",
        "--no-default-browser-check",
        "--user-data-dir=" + profileDirectory,
        "--dump-dom",
        "about:blank");
  }

  List<String> screenshotCommand(
      String browser,
      String profileDirectory,
      String screenshotPath,
      String reportUrl,
      int width,
      int height) {
    List<String> command = new ArrayList<>();
    command.add(browser);
    command.add("--headless");
    command.add("--disable-gpu");
    command.add("--disable-background-networking");
    command.add("--disable-dev-shm-usage");
    command.add("--disable-extensions");
    command.add("--hide-scrollbars");
    command.add("--no-first-run");
    command.add("--no-default-browser-check");
    command.add("--user-data-dir=" + profileDirectory);
    command.add("--virtual-time-budget=3000");
    command.add("--force-device-scale-factor=2");
    command.add("--window-size=" + width + "," + height);
    command.add("--screenshot=" + screenshotPath);
    command.add(reportUrl);
    return command;
  }

  List<String> measurementCommand(
      String browser, String profileDirectory, String reportUrl, int width, int estimatedHeight) {
    List<String> command = new ArrayList<>();
    command.add(browser);
    command.add("--headless");
    command.add("--disable-gpu");
    command.add("--disable-background-networking");
    command.add("--disable-dev-shm-usage");
    command.add("--disable-extensions");
    command.add("--hide-scrollbars");
    command.add("--no-first-run");
    command.add("--no-default-browser-check");
    command.add("--user-data-dir=" + profileDirectory);
    command.add("--virtual-time-budget=3000");
    command.add("--force-device-scale-factor=2");
    command.add("--window-size=" + width + "," + estimatedHeight);
    command.add("--dump-dom");
    command.add(reportUrl);
    return command;
  }

  int renderedHeightFromDom(String renderedDom, int fallbackHeight) {
    Matcher matcher = CAPTURE_HEIGHT_PATTERN.matcher(Util.trimToEmpty(renderedDom));
    if (!matcher.find()) {
      return fallbackHeight;
    }
    try {
      int measuredHeight = Integer.parseInt(matcher.group(1));
      return measuredHeight > 0 && measuredHeight <= MAX_SCREENSHOT_HEIGHT
          ? measuredHeight
          : fallbackHeight;
    } catch (NumberFormatException e) {
      return fallbackHeight;
    }
  }

  boolean hasWebpSignature(FilePath screenshotFile) throws IOException, InterruptedException {
    try (InputStream input = screenshotFile.read()) {
      byte[] header = input.readNBytes(12);
      return header.length == 12
          && header[0] == 'R'
          && header[1] == 'I'
          && header[2] == 'F'
          && header[3] == 'F'
          && header[8] == 'W'
          && header[9] == 'E'
          && header[10] == 'B'
          && header[11] == 'P';
    }
  }

  private BrowserProbeResult probeBrowser(
      String browser, String profileDirectory, Launcher launcher) throws InterruptedException {
    ByteArrayOutputStream output = new BoundedByteArrayOutputStream(MAX_BROWSER_PROBE_OUTPUT_BYTES);
    try {
      Proc process =
          launcher
              .launch()
              .cmds(browserProbeCommand(browser, profileDirectory))
              .quiet(true)
              .stdout(output)
              .stderr(output)
              .start();
      long startedAt = System.nanoTime();
      int exitCode =
          process.joinWithTimeout(
              BROWSER_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS, TaskListener.NULL);
      boolean timedOut =
          exitCode != 0
              && System.nanoTime() - startedAt
                  >= TimeUnit.SECONDS.toNanos(BROWSER_PROBE_TIMEOUT_SECONDS);
      return new BrowserProbeResult(
          exitCode == 0, timedOut, exitCode, output.toString(StandardCharsets.UTF_8));
    } catch (IOException e) {
      return new BrowserProbeResult(false, false, -1, e.getMessage());
    }
  }

  private int measureRenderedHeight(
      String browser,
      String profileDirectory,
      String reportUrl,
      int width,
      int estimatedHeight,
      FilePath outputDirectory,
      Launcher launcher,
      TaskListener listener)
      throws InterruptedException {
    ByteArrayOutputStream output = new BoundedByteArrayOutputStream(MAX_BROWSER_PROBE_OUTPUT_BYTES);
    ByteArrayOutputStream errorOutput =
        new BoundedByteArrayOutputStream(MAX_BROWSER_PROBE_OUTPUT_BYTES);
    try {
      Proc process =
          launcher
              .launch()
              .cmds(
                  measurementCommand(browser, profileDirectory, reportUrl, width, estimatedHeight))
              .pwd(outputDirectory)
              .quiet(true)
              .stdout(output)
              .stderr(errorOutput)
              .start();
      int exitCode =
          process.joinWithTimeout(
              BROWSER_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS, TaskListener.NULL);
      if (exitCode != 0) {
        listener
            .getLogger()
            .println(
                "Unable to measure the rendered Octane report height; using the safe estimate."
                    + formattedProbeOutput(errorOutput.toString(StandardCharsets.UTF_8)));
        return estimatedHeight;
      }
      int measuredHeight =
          renderedHeightFromDom(output.toString(StandardCharsets.UTF_8), estimatedHeight);
      if (measuredHeight == estimatedHeight
          && !CAPTURE_HEIGHT_PATTERN.matcher(output.toString(StandardCharsets.UTF_8)).find()) {
        listener
            .getLogger()
            .println("Rendered Octane report height was unavailable; using the safe estimate.");
      } else {
        listener
            .getLogger()
            .println("Measured Octane report capture height: " + measuredHeight + "px.");
      }
      return measuredHeight;
    } catch (IOException e) {
      listener
          .getLogger()
          .println("Unable to measure the rendered Octane report height; using the safe estimate.");
      return estimatedHeight;
    }
  }

  private int joinWithTimeout(
      Proc process, int timeoutSeconds, TaskListener listener, String operation)
      throws IOException, InterruptedException {
    long startedAt = System.nanoTime();
    int exitCode = process.joinWithTimeout(timeoutSeconds, TimeUnit.SECONDS, listener);
    boolean timedOut =
        exitCode != 0 && System.nanoTime() - startedAt >= TimeUnit.SECONDS.toNanos(timeoutSeconds);
    if (timedOut) {
      throw new AbortException(operation + " timed out after " + timeoutSeconds + " seconds.");
    }
    return exitCode;
  }

  private String normalizeBrowserPath(String value) {
    String normalized = Util.trimToEmpty(value);
    if (normalized.length() >= 2
        && ((normalized.startsWith("\"") && normalized.endsWith("\""))
            || (normalized.startsWith("'") && normalized.endsWith("'")))) {
      return normalized.substring(1, normalized.length() - 1);
    }
    return normalized;
  }

  private String formattedProbeOutput(String output) {
    String normalized = Util.trimToEmpty(output).replaceAll("\\s+", " ");
    if (normalized.isEmpty()) {
      return "";
    }
    int maximumLength = 300;
    String concise =
        normalized.length() <= maximumLength
            ? normalized
            : normalized.substring(0, maximumLength) + "...";
    return " Browser output: " + concise;
  }

  static int estimateViewportHeight(OctaneGateReportSnapshot snapshot, int viewportWidth) {
    int cardCount = snapshot.hasReportSections() ? snapshot.getReportSections().size() * 2 : 1;
    if (snapshot.isSingleSectionReport()) {
      return estimateSingleSectionViewportHeightForCards(cardCount, viewportWidth);
    }
    return estimateViewportHeightForCards(cardCount, viewportWidth);
  }

  static int estimateSingleSectionViewportHeightForCards(int cardCount, int viewportWidth) {
    return estimateViewportHeightForCards(cardCount, viewportWidth, 420);
  }

  static int estimateViewportHeightForCards(int cardCount, int viewportWidth) {
    return estimateViewportHeightForCards(cardCount, viewportWidth, 800);
  }

  private static int estimateViewportHeightForCards(
      int cardCount, int viewportWidth, int minimumHeight) {
    int columns =
        viewportWidth <= OctaneReportZoneHtmlRenderer.EMAIL_SINGLE_COLUMN_BREAKPOINT_PX ? 1 : 2;
    int rows = Math.max(1, (cardCount + columns - 1) / columns);
    return Math.min(MAX_SCREENSHOT_HEIGHT, Math.max(minimumHeight, 120 + rows * 380));
  }

  private record BrowserProbeResult(
      boolean successful, boolean timedOut, int exitCode, String output) {}
}
