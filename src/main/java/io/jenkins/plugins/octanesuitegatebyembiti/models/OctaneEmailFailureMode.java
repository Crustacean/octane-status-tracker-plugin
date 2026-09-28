package io.jenkins.plugins.octanesuitegatebyembiti.models;

import io.jenkins.plugins.octanesuitegatebyembiti.utils.Util;

public enum OctaneEmailFailureMode {
  UNSTABLE,
  FAILURE,
  WARN;

  public static OctaneEmailFailureMode from(String value) {
    String normalized = Util.trimToEmpty(value);
    if (normalized.isEmpty()) {
      return UNSTABLE;
    }
    for (OctaneEmailFailureMode mode : values()) {
      if (mode.name().equalsIgnoreCase(normalized)) {
        return mode;
      }
    }
    throw new IllegalArgumentException("onFailure must be one of UNSTABLE, FAILURE, or WARN.");
  }

  public static String normalize(String value) {
    return from(value).name();
  }
}
