package io.jenkins.plugins.octanesuitegatebyembiti.models;

import java.io.Serializable;

/** Reconciles final failed and blocked test states against defects that remain open. */
public final class DefectLoggingCompliance implements Serializable {
  private static final long serialVersionUID = 1L;

  private final int blockedTests;
  private final int failedTests;
  private final int openDefects;
  private final int expectedDefects;
  private final int variance;
  private final double discrepancyPercentage;
  private final Status status;

  private DefectLoggingCompliance(
      int blockedTests,
      int failedTests,
      int openDefects,
      int expectedDefects,
      int variance,
      double discrepancyPercentage,
      Status status) {
    this.blockedTests = blockedTests;
    this.failedTests = failedTests;
    this.openDefects = openDefects;
    this.expectedDefects = expectedDefects;
    this.variance = variance;
    this.discrepancyPercentage = discrepancyPercentage;
    this.status = status;
  }

  public static DefectLoggingCompliance from(int blockedTests, int failedTests, int openDefects) {
    int safeBlockedTests = Math.max(0, blockedTests);
    int safeFailedTests = Math.max(0, failedTests);
    int safeOpenDefects = Math.max(0, openDefects);
    int expectedDefects = safeBlockedTests + safeFailedTests;
    int variance = safeOpenDefects - expectedDefects;
    Status status;
    if (variance == 0) {
      status = Status.TALLY;
    } else if (variance < 0) {
      status = Status.UNDER_REPORTED;
    } else {
      status = Status.SURPLUS;
    }
    double discrepancyPercentage =
        variance == 0
            ? 0.0
            : expectedDefects == 0 ? 100.0 : Math.abs(variance) * 100.0 / expectedDefects;
    return new DefectLoggingCompliance(
        safeBlockedTests,
        safeFailedTests,
        safeOpenDefects,
        expectedDefects,
        variance,
        discrepancyPercentage,
        status);
  }

  public int getBlockedTests() {
    return blockedTests;
  }

  public int getFailedTests() {
    return failedTests;
  }

  public int getOpenDefects() {
    return openDefects;
  }

  public int getExpectedDefects() {
    return expectedDefects;
  }

  public int getVariance() {
    return variance;
  }

  public double getDiscrepancyPercentage() {
    return discrepancyPercentage;
  }

  public Status getStatus() {
    return status;
  }

  public boolean isCompliant() {
    return status == Status.TALLY;
  }

  public boolean hasNoOpenDefectsExpected() {
    return expectedDefects == 0 && openDefects == 0;
  }

  public enum Status {
    TALLY("TALLY"),
    UNDER_REPORTED("UNDER-REPORTED"),
    SURPLUS("SURPLUS");

    private final String label;

    Status(String label) {
      this.label = label;
    }

    public String getLabel() {
      return label;
    }
  }
}
