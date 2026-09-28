package io.jenkins.plugins.octanesuitegatebyembiti.services;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.TaskListener;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneGateReportSnapshot;
import java.io.IOException;

public interface OctaneReportScreenshotService {
  OctaneReportScreenshot capture(
      OctaneGateReportSnapshot snapshot,
      FilePath workspace,
      EnvVars envVars,
      Launcher launcher,
      TaskListener listener,
      String browserPath,
      int viewportWidth,
      String theme)
      throws IOException, InterruptedException;
}
