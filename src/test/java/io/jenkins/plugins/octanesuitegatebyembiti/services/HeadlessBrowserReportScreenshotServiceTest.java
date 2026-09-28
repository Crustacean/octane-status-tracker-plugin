package io.jenkins.plugins.octanesuitegatebyembiti.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Test;

public class HeadlessBrowserReportScreenshotServiceTest {
  @Test
  public void convertsRemotePathsToFileUrls() throws IOException {
    HeadlessBrowserReportScreenshotService service = new HeadlessBrowserReportScreenshotService();

    assertEquals(
        "file:///tmp/octane%20report/report.html",
        service.toFileUrl("/tmp/octane report/report.html"));
    assertEquals(
        "file:///C:/jenkins/workspace/report.html",
        service.toFileUrl("C:\\jenkins\\workspace\\report.html"));
  }

  @Test
  public void exposesStableWorkspacePaths() {
    assertEquals(
        ".octane-suite-gate/report-email/octane-report-zone.webp",
        HeadlessBrowserReportScreenshotService.ATTACHMENT_PATTERN);
    assertTrue(HeadlessBrowserReportScreenshotService.ATTACHMENT_PATTERN.endsWith(".webp"));
  }

  @Test
  public void validatesBrowserWithAHeadlessProbeInsteadOfVersionCommand() {
    HeadlessBrowserReportScreenshotService service = new HeadlessBrowserReportScreenshotService();

    List<String> command = service.browserProbeCommand("chrome.exe", "C:\\jenkins\\chrome-profile");

    assertEquals("chrome.exe", command.get(0));
    assertTrue(command.contains("--headless"));
    assertTrue(command.contains("--dump-dom"));
    assertTrue(command.contains("about:blank"));
    assertTrue(command.contains("--user-data-dir=C:\\jenkins\\chrome-profile"));
    assertFalse(command.contains("--version"));
    assertTrue(HeadlessBrowserReportScreenshotService.BROWSER_PROBE_TIMEOUT_SECONDS > 0);
  }

  @Test
  public void capturesWithAnIsolatedProfileAndBoundedRenderingBudget() {
    HeadlessBrowserReportScreenshotService service = new HeadlessBrowserReportScreenshotService();

    List<String> command =
        service.screenshotCommand(
            "chrome.exe",
            "C:\\jenkins\\chrome-profile",
            "C:\\jenkins\\report.webp",
            "file:///C:/jenkins/report.html",
            1400,
            800);

    assertTrue(command.contains("--user-data-dir=C:\\jenkins\\chrome-profile"));
    assertTrue(command.contains("--virtual-time-budget=3000"));
    assertTrue(command.contains("--force-device-scale-factor=2"));
    assertTrue(command.contains("--window-size=1400,800"));
    assertTrue(command.contains("--screenshot=C:\\jenkins\\report.webp"));
    assertTrue(HeadlessBrowserReportScreenshotService.SCREENSHOT_TIMEOUT_SECONDS > 0);
  }

  @Test
  public void measuresRenderedReportHeightAtTheRequestedWidth() {
    HeadlessBrowserReportScreenshotService service = new HeadlessBrowserReportScreenshotService();

    List<String> command =
        service.measurementCommand(
            "chrome.exe",
            "C:\\jenkins\\chrome-profile",
            "file:///C:/jenkins/report.html",
            1400,
            880);

    assertTrue(command.contains("--user-data-dir=C:\\jenkins\\chrome-profile"));
    assertTrue(command.contains("--virtual-time-budget=3000"));
    assertTrue(command.contains("--force-device-scale-factor=2"));
    assertTrue(command.contains("--window-size=1400,880"));
    assertTrue(command.contains("--dump-dom"));
    assertEquals("file:///C:/jenkins/report.html", command.get(command.size() - 1));
  }

  @Test
  public void usesMeasuredHeightWhenValidAndFallsBackForInvalidDom() {
    HeadlessBrowserReportScreenshotService service = new HeadlessBrowserReportScreenshotService();

    assertEquals(
        742,
        service.renderedHeightFromDom(
            "<html data-octane-capture-height=\"742\"><body></body></html>", 880));
    assertEquals(880, service.renderedHeightFromDom("<html><body></body></html>", 880));
    assertEquals(
        880,
        service.renderedHeightFromDom(
            "<html data-octane-capture-height=\"0\"><body></body></html>", 880));
    assertEquals(
        880,
        service.renderedHeightFromDom(
            "<html data-octane-capture-height=\"99999\"><body></body></html>", 880));
  }

  @Test
  public void validatesWebpFileSignature() throws Exception {
    HeadlessBrowserReportScreenshotService service = new HeadlessBrowserReportScreenshotService();
    Path webp = Files.createTempFile("octane-report", ".webp");
    Path png = Files.createTempFile("octane-report", ".png");
    try {
      Files.write(webp, new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'});
      Files.write(png, new byte[] {(byte) 0x89, 'P', 'N', 'G'});

      assertTrue(service.hasWebpSignature(new hudson.FilePath(webp.toFile())));
      assertFalse(service.hasWebpSignature(new hudson.FilePath(png.toFile())));
    } finally {
      Files.deleteIfExists(webp);
      Files.deleteIfExists(png);
    }
  }

  @Test
  public void expandsScreenshotHeightWhenCardsStackAtNarrowWidths() {
    assertEquals(
        1640, HeadlessBrowserReportScreenshotService.estimateViewportHeightForCards(4, 600));
    assertEquals(
        880, HeadlessBrowserReportScreenshotService.estimateViewportHeightForCards(4, 1400));
    assertEquals(
        HeadlessBrowserReportScreenshotService.MAX_SCREENSHOT_HEIGHT,
        HeadlessBrowserReportScreenshotService.estimateViewportHeightForCards(10_000, 1400));
  }

  @Test
  public void cropsSingleSectionCaptureToItsActiveCardRow() {
    assertEquals(
        500,
        HeadlessBrowserReportScreenshotService.estimateSingleSectionViewportHeightForCards(
            2, 1400));
    assertEquals(
        880,
        HeadlessBrowserReportScreenshotService.estimateSingleSectionViewportHeightForCards(2, 600));
  }
}
