package io.jenkins.plugins.octanesuitegatebyembiti.models;

public enum OctaneGateReportState {
  WAITING("Waiting"),
  POLLING("Polling"),
  EXTENDED_TIME("Extended time"),
  FINALIZING("Finalizing"),
  PASSED("Passed"),
  FAILED("Failed"),
  UNSTABLE("Unstable"),
  TIMED_OUT("Timed out"),
  ERROR("Error");

  private final String label;

  OctaneGateReportState(String label) {
    this.label = label;
  }

  public String getLabel() {
    return label;
  }

  public boolean isBuilding() {
    return this == WAITING || this == POLLING || this == EXTENDED_TIME || this == FINALIZING;
  }
}
