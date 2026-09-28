package io.jenkins.plugins.octanesuitegatebyembiti.entities;

import io.jenkins.plugins.octanesuitegatebyembiti.utils.DefectDescriptionFormatter;
import io.jenkins.plugins.octanesuitegatebyembiti.utils.Util;
import java.io.Serializable;

public class DefectRecord implements Serializable {
  private static final long serialVersionUID = 1L;

  private final String id;
  private final String name;
  private final String description;
  private final String severity;
  private final String priority;
  private final String phase;
  private final String runId;
  private final String testId;
  private final String projectId;
  private final String projectName;

  public DefectRecord(
      String id,
      String name,
      String severity,
      String priority,
      String phase,
      String runId,
      String testId,
      String projectId,
      String projectName) {
    this(id, name, "", severity, priority, phase, runId, testId, projectId, projectName);
  }

  public DefectRecord(
      String id,
      String name,
      String description,
      String severity,
      String priority,
      String phase,
      String runId,
      String testId,
      String projectId,
      String projectName) {
    this.id = Util.trimToEmpty(id);
    this.name = Util.trimToEmpty(name);
    this.description = DefectDescriptionFormatter.toPlainText(description);
    this.severity = Util.trimToEmpty(severity);
    this.priority = Util.trimToEmpty(priority);
    this.phase = Util.trimToEmpty(phase);
    this.runId = Util.trimToEmpty(runId);
    this.testId = Util.trimToEmpty(testId);
    this.projectId = Util.trimToEmpty(projectId);
    this.projectName = Util.trimToEmpty(projectName);
  }

  public String getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public String getDescription() {
    return DefectDescriptionFormatter.toPlainText(description);
  }

  public String getDisplayDescription() {
    String normalizedName = Util.trimToEmpty(name);
    String normalizedDescription = getDescription();
    if (!normalizedName.isEmpty() && !normalizedDescription.isEmpty()) {
      return normalizedName + ":\n" + normalizedDescription;
    }
    return normalizedName.isEmpty() ? normalizedDescription : normalizedName;
  }

  public String getSeverity() {
    return severity;
  }

  public String getPriority() {
    return priority;
  }

  public String getPhase() {
    return phase;
  }

  public String getRunId() {
    return runId;
  }

  public String getTestId() {
    return testId;
  }

  public String getProjectId() {
    return projectId;
  }

  public String getProjectName() {
    return projectName;
  }

  public boolean isOpen() {
    String normalizedPhase = Util.normalizeStatus(phase);
    return !normalizedPhase.contains("closed")
        && !normalizedPhase.contains("fixed")
        && !normalizedPhase.contains("done")
        && !normalizedPhase.contains("resolved")
        && !normalizedPhase.contains("rejected");
  }

  public DefectRecord withFallbackRelations(DefectRecord fallback) {
    if (fallback == null) {
      return this;
    }
    return new DefectRecord(
        id,
        Util.isBlank(name) ? fallback.name : name,
        Util.isBlank(description) ? fallback.description : description,
        severity,
        priority,
        phase,
        Util.isBlank(runId) ? fallback.runId : runId,
        Util.isBlank(testId) ? fallback.testId : testId,
        Util.isBlank(projectId) ? fallback.projectId : projectId,
        Util.isBlank(projectName) ? fallback.projectName : projectName);
  }
}
