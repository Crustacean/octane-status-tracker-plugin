package io.jenkins.plugins.octanesuitegatebyembiti.models;

import hudson.Extension;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import io.jenkins.plugins.octanesuitegatebyembiti.utils.Util;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

public class OctaneDefectGroup implements Describable<OctaneDefectGroup>, Serializable {
  private static final long serialVersionUID = 1L;
  private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_-]*");
  private static final Set<String> RESERVED_NAMES = Set.of("open", "total");

  private final String name;
  private String types = "";

  @DataBoundConstructor
  public OctaneDefectGroup(String name) {
    this.name = Util.trimToEmpty(name);
  }

  public String getName() {
    return name;
  }

  public String getTypes() {
    return types;
  }

  @DataBoundSetter
  public void setTypes(String types) {
    this.types = Util.trimToEmpty(types);
  }

  public List<String> getNormalizedTypes() {
    Set<String> normalizedTypes = new LinkedHashSet<>();
    for (String value : types.split("[,;\\n]+")) {
      String normalized = OctaneDefectSeveritySummary.normalizeOpenType(value);
      if (!normalized.isEmpty()) {
        normalizedTypes.add(normalized);
      }
    }
    return new ArrayList<>(normalizedTypes);
  }

  public String getValidationError() {
    String nameError = validationErrorForName();
    if (!nameError.isEmpty()) {
      return nameError;
    }
    if (types.isEmpty()) {
      return "At least one defect type is required for group '" + name + "'.";
    }
    String typeError = validationErrorForTypes();
    if (!typeError.isEmpty()) {
      return typeError;
    }
    return getNormalizedTypes().isEmpty()
        ? "At least one supported defect type is required for group '" + name + "'."
        : "";
  }

  private String validationErrorForName() {
    if (name.isEmpty()) {
      return "Defect group name is required.";
    }
    if (!NAME_PATTERN.matcher(name).matches()) {
      return "Defect group name must start with a letter or underscore and contain only letters, numbers, underscores, or hyphens.";
    }
    String normalizedName = normalizeName(name);
    boolean reserved =
        !OctaneDefectSeveritySummary.normalizeOpenType(name).isEmpty()
            || RESERVED_NAMES.contains(normalizedName)
            || normalizedName.endsWith("count");
    return reserved
        ? "Defect group name '" + name + "' conflicts with a built-in defect metric."
        : "";
  }

  private String validationErrorForTypes() {
    for (String value : types.split("[,;\\n]+")) {
      String trimmed = Util.trimToEmpty(value);
      if (!trimmed.isEmpty() && OctaneDefectSeveritySummary.normalizeOpenType(trimmed).isEmpty()) {
        return "Unknown defect type '"
            + trimmed
            + "'. Use Critical, Very High, High, Medium, Low, or Unspecified.";
      }
    }
    return "";
  }

  public static String normalizeName(String value) {
    return Util.trimToEmpty(value).toLowerCase(Locale.ROOT);
  }

  @Extension
  @Symbol("octaneDefectGroup")
  public static class DescriptorImpl extends Descriptor<OctaneDefectGroup> {
    @Override
    public String getDisplayName() {
      return "Octane defect group";
    }

    public FormValidation doCheckName(@QueryParameter String value) {
      OctaneDefectGroup group = new OctaneDefectGroup(value);
      group.setTypes("Critical");
      String error = group.getValidationError();
      return error.isEmpty() ? FormValidation.ok() : FormValidation.error(error);
    }

    public FormValidation doCheckTypes(@QueryParameter String value, @QueryParameter String name) {
      OctaneDefectGroup group = new OctaneDefectGroup(name);
      group.setTypes(value);
      String error = group.getValidationError();
      return error.isEmpty() ? FormValidation.ok() : FormValidation.error(error);
    }
  }
}
