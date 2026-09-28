package io.jenkins.plugins.octanesuitegatebyembiti.models;

import hudson.Extension;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import io.jenkins.plugins.octanesuitegatebyembiti.utils.OctaneQueryValidator;
import io.jenkins.plugins.octanesuitegatebyembiti.utils.Util;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

public class OctaneGateScope implements Describable<OctaneGateScope>, Serializable {
  private static final long serialVersionUID = 1L;
  private static final Pattern ID_CONDITION =
      Pattern.compile("(?i)\\bid\\s*(?:=|EQ)\\s*([A-Za-z0-9_.:-]+)");

  private final String name;
  private String query = "";
  private String suiteRunId = "";

  @DataBoundConstructor
  public OctaneGateScope(String name) {
    this.name = Util.trimToEmpty(name);
  }

  public OctaneGateScope(String name, String query) {
    this(name);
    setQuery(query);
  }

  public String getName() {
    return name;
  }

  public String getQuery() {
    return query;
  }

  @DataBoundSetter
  public void setQuery(String query) {
    this.query = OctaneQueryValidator.normalize(query, "Scope query");
  }

  public String getSuiteRunId() {
    return suiteRunId;
  }

  @DataBoundSetter
  public void setSuiteRunId(String suiteRunId) {
    this.suiteRunId = Util.trimToEmpty(suiteRunId);
  }

  public List<String> getSuiteRunIds() {
    return getSuiteRunSelector().getExplicitIds();
  }

  public SuiteRunSelector getSuiteRunSelector() {
    return SuiteRunSelector.parse(suiteRunId);
  }

  public boolean isQueryScope() {
    return !Util.isBlank(query);
  }

  public boolean isSuiteRunScope() {
    return !Util.isBlank(suiteRunId);
  }

  public List<String> getReferencedIds() {
    Set<String> ids = new LinkedHashSet<>();
    Matcher matcher = ID_CONDITION.matcher(query);
    while (matcher.find()) {
      ids.add(matcher.group(1));
    }
    return new ArrayList<>(ids);
  }

  @Extension
  @Symbol("octaneGateScope")
  public static class DescriptorImpl extends Descriptor<OctaneGateScope> {
    @Override
    public String getDisplayName() {
      return "Octane gate scope";
    }

    public FormValidation doCheckName(@QueryParameter String value) {
      if (Util.isBlank(value)) {
        return FormValidation.error("Scope name is required.");
      }
      return FormValidation.ok();
    }

    public FormValidation doCheckSuiteRunId(
        @QueryParameter String value, @QueryParameter String query) {
      return checkScopeSource(value, query);
    }

    public FormValidation doCheckQuery(
        @QueryParameter String value, @QueryParameter String suiteRunId) {
      return checkScopeSource(suiteRunId, value);
    }

    private FormValidation checkScopeSource(String suiteRunId, String query) {
      boolean hasSuiteRunId = !Util.isBlank(suiteRunId);
      boolean hasQuery = !Util.isBlank(query);
      if (!hasSuiteRunId && !hasQuery) {
        return FormValidation.error("Either suite run ID(s) or an Octane query is required.");
      }
      if (hasSuiteRunId && hasQuery) {
        return FormValidation.error("Use either suite run ID(s) or an Octane query, not both.");
      }
      if (hasSuiteRunId) {
        try {
          SuiteRunSelector.parse(suiteRunId);
        } catch (IllegalArgumentException e) {
          return FormValidation.error(e.getMessage());
        }
      } else {
        try {
          OctaneQueryValidator.normalize(query, "Scope query");
        } catch (IllegalArgumentException e) {
          return FormValidation.error(e.getMessage());
        }
      }
      return FormValidation.ok();
    }
  }
}
