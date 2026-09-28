package io.jenkins.plugins.octanesuitegatebyembiti.utils;

/** Bounds explicit ALM Octane query fragments without changing their supported grammar. */
public final class OctaneQueryValidator {
  public static final int MAX_QUERY_LENGTH = 4_096;

  private OctaneQueryValidator() {}

  public static String normalize(String value, String label) {
    String query = Util.trimToEmpty(value);
    if (query.length() > MAX_QUERY_LENGTH) {
      throw new IllegalArgumentException(
          label + " must contain at most " + MAX_QUERY_LENGTH + " characters.");
    }
    for (int index = 0; index < query.length(); index++) {
      if (Character.isISOControl(query.charAt(index))) {
        throw new IllegalArgumentException(label + " must not contain control characters.");
      }
    }
    return query;
  }
}
