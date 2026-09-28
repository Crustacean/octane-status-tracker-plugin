package io.jenkins.plugins.octanesuitegatebyembiti.models;

import io.jenkins.plugins.octanesuitegatebyembiti.utils.Util;
import java.io.Serializable;
import java.util.HashSet;
import java.util.Set;

public class StatusClassifier implements Serializable {
  private static final long serialVersionUID = 1L;

  public static final String DEFAULT_PASSED_STATUSES = "passed,list_node.run_native_status.passed";
  public static final String DEFAULT_FAILED_STATUSES =
      "failed,blocked,error,list_node.run_native_status.failed";
  public static final String DEFAULT_NEUTRAL_STATUSES =
      "skipped,list_node.run_native_status.skipped";
  public static final String DEFAULT_RUNNING_STATUSES =
      "planned,in_progress,queued,running,list_node.run_native_status.planned,"
          + "list_node.run_native_status.in_progress,list_node.run_status.planned,"
          + "list_node.run_status.in_progress";

  private static final Set<String> MANDATORY_ACTIVE_STATUS_NAMES =
      Set.of("in_progress", "not_completed", "queued", "running");
  private static final Set<String> DEFAULT_PASSED_STATUS_SET =
      Set.copyOf(Util.splitCsv(DEFAULT_PASSED_STATUSES));
  private static final Set<String> DEFAULT_FAILED_STATUS_SET =
      Set.copyOf(Util.splitCsv(DEFAULT_FAILED_STATUSES));
  private static final Set<String> DEFAULT_NEUTRAL_STATUS_SET =
      Set.copyOf(Util.splitCsv(DEFAULT_NEUTRAL_STATUSES));
  private static final Set<String> DEFAULT_RUNNING_STATUS_SET =
      Set.copyOf(Util.splitCsv(DEFAULT_RUNNING_STATUSES));

  private final Set<String> passedStatuses;
  private final Set<String> failedStatuses;
  private final Set<String> neutralStatuses;
  private final Set<String> runningStatuses;

  public StatusClassifier(
      String passedStatuses,
      String failedStatuses,
      String neutralStatuses,
      String runningStatuses) {
    this.passedStatuses = new HashSet<>(Util.splitCsv(passedStatuses));
    this.failedStatuses = new HashSet<>(Util.splitCsv(failedStatuses));
    this.neutralStatuses = new HashSet<>(Util.splitCsv(neutralStatuses));
    this.runningStatuses = new HashSet<>(Util.splitCsv(runningStatuses));
  }

  Outcome classify(String status) {
    String normalized = Util.normalizeStatus(status);
    if (isMandatoryActiveStatus(normalized)) {
      return Outcome.RUNNING;
    }
    Outcome configured = configuredOutcome(normalized);
    if (configured != null) {
      return configured;
    }
    Outcome defaultOutcome = defaultOutcome(normalized);
    return defaultOutcome == null ? inferredOutcome(normalized) : defaultOutcome;
  }

  boolean isActivelyRunning(String status) {
    String normalized = Util.normalizeStatus(status);
    if (isMandatoryActiveStatus(normalized)) {
      return true;
    }
    return classify(normalized) == Outcome.RUNNING && !"planned".equals(statusName(normalized));
  }

  private Outcome defaultOutcome(String normalized) {
    if (matches(DEFAULT_PASSED_STATUS_SET, normalized)) {
      return Outcome.PASSED;
    }
    if (matches(DEFAULT_FAILED_STATUS_SET, normalized)) {
      return isBlocked(normalized) ? Outcome.BLOCKED : Outcome.FAILED;
    }
    if (matches(DEFAULT_NEUTRAL_STATUS_SET, normalized)) {
      return Outcome.NEUTRAL;
    }
    if ("stopped".equals(statusName(normalized))) {
      return Outcome.STOPPED;
    }
    return matches(DEFAULT_RUNNING_STATUS_SET, normalized) ? Outcome.RUNNING : null;
  }

  private Outcome configuredOutcome(String normalized) {
    if (matches(passedStatuses, normalized)) {
      return Outcome.PASSED;
    }
    if (matches(neutralStatuses, normalized)) {
      return Outcome.NEUTRAL;
    }
    if (matches(failedStatuses, normalized)) {
      return isBlocked(normalized) ? Outcome.BLOCKED : Outcome.FAILED;
    }
    return matches(runningStatuses, normalized) ? Outcome.RUNNING : null;
  }

  private Outcome inferredOutcome(String normalized) {
    if (normalized.contains("pass")) {
      return Outcome.PASSED;
    }
    if (normalized.contains("fail") || normalized.contains("error")) {
      return Outcome.FAILED;
    }
    return normalized.contains("skip") ? Outcome.NEUTRAL : Outcome.RUNNING;
  }

  private static boolean matches(Set<String> candidates, String normalizedStatus) {
    if (candidates.contains(normalizedStatus)) {
      return true;
    }
    int dot = normalizedStatus.lastIndexOf('.');
    return dot >= 0 && candidates.contains(normalizedStatus.substring(dot + 1));
  }

  private static String statusName(String normalizedStatus) {
    int dot = normalizedStatus.lastIndexOf('.');
    return dot >= 0 ? normalizedStatus.substring(dot + 1) : normalizedStatus;
  }

  private boolean isBlocked(String normalizedStatus) {
    return normalizedStatus.contains("blocked");
  }

  private static boolean isMandatoryActiveStatus(String normalizedStatus) {
    return MANDATORY_ACTIVE_STATUS_NAMES.contains(statusName(normalizedStatus));
  }

  enum Outcome {
    PASSED,
    FAILED,
    BLOCKED,
    NEUTRAL,
    STOPPED,
    RUNNING
  }
}
