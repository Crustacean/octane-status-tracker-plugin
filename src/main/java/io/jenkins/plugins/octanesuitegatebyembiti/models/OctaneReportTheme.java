package io.jenkins.plugins.octanesuitegatebyembiti.models;

import io.jenkins.plugins.octanesuitegatebyembiti.utils.Util;
import java.util.Locale;

public enum OctaneReportTheme {
  LIGHT,
  DARK,
  SYSTEM;

  public static OctaneReportTheme from(String value) {
    String normalized = Util.trimToEmpty(value).toUpperCase(Locale.ENGLISH);
    if (normalized.isEmpty()) {
      return LIGHT;
    }
    try {
      return valueOf(normalized);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("theme must be one of LIGHT, DARK, or SYSTEM.", e);
    }
  }

  public static String normalize(String value) {
    return from(value).name();
  }

  public String getHtmlValue() {
    return name().toLowerCase(Locale.ENGLISH);
  }

  public String getColorSchemeContent() {
    return this == SYSTEM ? "light dark" : getHtmlValue();
  }
}
