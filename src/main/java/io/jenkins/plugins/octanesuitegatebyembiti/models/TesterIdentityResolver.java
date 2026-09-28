package io.jenkins.plugins.octanesuitegatebyembiti.models;

import io.jenkins.plugins.octanesuitegatebyembiti.utils.Util;
import java.util.Locale;
import java.util.regex.Pattern;

/** Normalizes tester identities before chart and email aggregation. */
final class TesterIdentityResolver {
  static final String UNASSIGNED = "Unassigned";

  private static final Pattern NUMBERED_UNASSIGNED =
      Pattern.compile("(?i)^unassigned\\s*\\(\\s*\\d+\\s*\\)$");

  private TesterIdentityResolver() {}

  static String displayName(String value) {
    String normalized = Util.trimToEmpty(value);
    if (normalized.isEmpty()
        || UNASSIGNED.equalsIgnoreCase(normalized)
        || NUMBERED_UNASSIGNED.matcher(normalized).matches()) {
      return UNASSIGNED;
    }
    return normalized;
  }

  static String key(String value) {
    return displayName(value).toLowerCase(Locale.ROOT);
  }
}
