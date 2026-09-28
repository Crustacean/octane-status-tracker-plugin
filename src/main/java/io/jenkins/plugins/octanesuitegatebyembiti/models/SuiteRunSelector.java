package io.jenkins.plugins.octanesuitegatebyembiti.models;

import io.jenkins.plugins.octanesuitegatebyembiti.utils.Util;
import java.io.Serializable;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Identifies suite runs explicitly by ID or dynamically by release and optional sprint. */
public final class SuiteRunSelector implements Serializable {
  private static final long serialVersionUID = 1L;
  private static final Pattern NUMERIC_ID = Pattern.compile("[0-9]{1,18}");
  private static final int MAX_NAME_LENGTH = 255;

  public enum Mode {
    EMPTY,
    EXPLICIT_IDS,
    RELEASE,
    RELEASE_SPRINT
  }

  private final String source;
  private final Mode mode;
  private final List<String> explicitIds;
  private final String releaseName;
  private final String sprintName;

  private SuiteRunSelector(
      String source, Mode mode, List<String> explicitIds, String releaseName, String sprintName) {
    this.source = source;
    this.mode = mode;
    this.explicitIds = List.copyOf(explicitIds);
    this.releaseName = releaseName;
    this.sprintName = sprintName;
  }

  public static SuiteRunSelector parse(String value) {
    String source = Util.trimToEmpty(value);
    if (source.isEmpty()) {
      return new SuiteRunSelector("", Mode.EMPTY, List.of(), "", "");
    }

    List<String> ids = Util.splitIdList(source);
    if (!ids.isEmpty() && ids.stream().allMatch(SuiteRunSelector::isNumericId)) {
      return new SuiteRunSelector(source, Mode.EXPLICIT_IDS, ids, "", "");
    }

    String[] commaValues = source.split(",", -1);
    if (commaValues.length == 1) {
      validateName("Release name", source);
      return new SuiteRunSelector(source, Mode.RELEASE, List.of(), source, "");
    }
    if (commaValues.length == 2) {
      String first = commaValues[0].trim();
      String second = commaValues[1].trim();
      validateName("Release name", first);
      validateName("Sprint name", second);
      return new SuiteRunSelector(source, Mode.RELEASE_SPRINT, List.of(), first, second);
    }

    throw new IllegalArgumentException(
        "Use suite run IDs, 'Release Name', or exactly 'Release Name, Sprint Name'.");
  }

  public String getSource() {
    return source;
  }

  public Mode getMode() {
    return mode;
  }

  public List<String> getExplicitIds() {
    return explicitIds;
  }

  public String getReleaseName() {
    return releaseName;
  }

  public String getSprintName() {
    return sprintName;
  }

  public boolean isConfigured() {
    return mode != Mode.EMPTY;
  }

  public boolean isDynamic() {
    return mode == Mode.RELEASE || mode == Mode.RELEASE_SPRINT;
  }

  public String describe() {
    if (mode == Mode.RELEASE) {
      return "release '" + releaseName + "'";
    }
    if (mode == Mode.RELEASE_SPRINT) {
      return "release '" + releaseName + "', sprint '" + sprintName + "'";
    }
    if (mode == Mode.EXPLICIT_IDS) {
      return String.join(", ", explicitIds);
    }
    return "<none>";
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof SuiteRunSelector selector)) {
      return false;
    }
    return mode == selector.mode
        && explicitIds.equals(selector.explicitIds)
        && releaseName.equals(selector.releaseName)
        && sprintName.equals(selector.sprintName);
  }

  @Override
  public int hashCode() {
    return Objects.hash(mode, explicitIds, releaseName, sprintName);
  }

  private static boolean isNumericId(String value) {
    return NUMERIC_ID.matcher(value).matches();
  }

  private static void validateName(String label, String value) {
    if (value.isEmpty()) {
      throw new IllegalArgumentException(label + " is required.");
    }
    if (value.length() > MAX_NAME_LENGTH) {
      throw new IllegalArgumentException(
          label + " must contain at most " + MAX_NAME_LENGTH + " characters.");
    }
    if (value.indexOf('*') >= 0) {
      throw new IllegalArgumentException(label + " cannot contain wildcard characters.");
    }
    for (int index = 0; index < value.length(); index++) {
      if (Character.isISOControl(value.charAt(index))) {
        throw new IllegalArgumentException(label + " cannot contain control characters.");
      }
    }
  }
}
