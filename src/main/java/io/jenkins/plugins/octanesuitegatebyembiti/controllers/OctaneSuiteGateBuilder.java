package io.jenkins.plugins.octanesuitegatebyembiti.controllers;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import io.jenkins.plugins.octanesuitegatebyembiti.actions.OctaneGateReportAction;
import io.jenkins.plugins.octanesuitegatebyembiti.listeners.OctaneGateLogListener;
import io.jenkins.plugins.octanesuitegatebyembiti.models.GateRequest;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneDefectGroup;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneGateScope;
import io.jenkins.plugins.octanesuitegatebyembiti.services.GateFailedException;
import io.jenkins.plugins.octanesuitegatebyembiti.services.OctaneGateRunner;
import io.jenkins.plugins.octanesuitegatebyembiti.services.OctaneSpaceMappingResolver;
import io.jenkins.plugins.octanesuitegatebyembiti.utils.Util;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import jenkins.tasks.SimpleBuildStep;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

public class OctaneSuiteGateBuilder extends Builder implements SimpleBuildStep {
  private final OctaneSuiteGateStep delegate;
  private String spacesMappingFile = OctaneSpaceMappingResolver.DEFAULT_MAPPING_FILE;
  private String sharedSpaceName = "";
  private String workspaceName = "";

  @DataBoundConstructor
  public OctaneSuiteGateBuilder(String suiteRunId) {
    delegate = new OctaneSuiteGateStep("", suiteRunId);
  }

  public String getSuiteRunId() {
    return delegate.getSuiteRunId();
  }

  public String getSpacesMappingFile() {
    return spacesMappingFile;
  }

  @DataBoundSetter
  public void setSpacesMappingFile(String spacesMappingFile) {
    String value = Util.trimToEmpty(spacesMappingFile);
    this.spacesMappingFile =
        value.isEmpty() ? OctaneSpaceMappingResolver.DEFAULT_MAPPING_FILE : value;
  }

  public String getSharedSpaceName() {
    return sharedSpaceName;
  }

  @DataBoundSetter
  public void setSharedSpaceName(String sharedSpaceName) {
    this.sharedSpaceName = Util.trimToEmpty(sharedSpaceName);
  }

  public String getWorkspaceName() {
    return workspaceName;
  }

  @DataBoundSetter
  public void setWorkspaceName(String workspaceName) {
    this.workspaceName = Util.trimToEmpty(workspaceName);
  }

  public String getSharedSpaceId() {
    return delegate.getSharedSpaceId();
  }

  @DataBoundSetter
  public void setSharedSpaceId(String sharedSpaceId) {
    delegate.setSharedSpaceId(sharedSpaceId);
  }

  public String getWorkspaceId() {
    return delegate.getWorkspaceId();
  }

  @DataBoundSetter
  public void setWorkspaceId(String workspaceId) {
    delegate.setWorkspaceId(workspaceId);
  }

  public String getCriteria() {
    return delegate.getCriteria();
  }

  @DataBoundSetter
  public void setCriteria(String criteria) {
    delegate.setCriteria(criteria);
  }

  public List<OctaneGateScope> getScopes() {
    return Collections.unmodifiableList(new ArrayList<>(delegate.getScopes()));
  }

  @DataBoundSetter
  public void setScopes(List<OctaneGateScope> scopes) {
    delegate.setScopes(scopes);
  }

  public List<OctaneDefectGroup> getDefectGroups() {
    return Collections.unmodifiableList(new ArrayList<>(delegate.getDefectGroups()));
  }

  @DataBoundSetter
  public void setDefectGroups(List<OctaneDefectGroup> defectGroups) {
    delegate.setDefectGroups(defectGroups);
  }

  public int getPollIntervalSeconds() {
    return delegate.getPollIntervalSeconds();
  }

  @DataBoundSetter
  public void setPollIntervalSeconds(int pollIntervalSeconds) {
    delegate.setPollIntervalSeconds(pollIntervalSeconds);
  }

  public int getTimeoutMinutes() {
    return delegate.getTimeoutMinutes();
  }

  @DataBoundSetter
  public void setTimeoutMinutes(int timeoutMinutes) {
    delegate.setTimeoutMinutes(timeoutMinutes);
  }

  public int getTimeoutMinutesExtended() {
    return delegate.getTimeoutMinutesExtended();
  }

  @DataBoundSetter
  public void setTimeoutMinutesExtended(int timeoutMinutesExtended) {
    delegate.setTimeoutMinutesExtended(timeoutMinutesExtended);
  }

  public int getBasePassrateFigure() {
    return delegate.getBasePassrateFigure();
  }

  @DataBoundSetter
  public void setBasePassrateFigure(int basePassrateFigure) {
    delegate.setBasePassrateFigure(basePassrateFigure);
  }

  public int getBaseExecutionFigure() {
    return delegate.getBaseExecutionFigure();
  }

  @DataBoundSetter
  public void setBaseExecutionFigure(int baseExecutionFigure) {
    delegate.setBaseExecutionFigure(baseExecutionFigure);
  }

  public int getLimitForMetricRunsInSuite() {
    return delegate.getLimitForMetricRunsInSuite();
  }

  @DataBoundSetter
  public void setLimitForMetricRunsInSuite(int limitForMetricRunsInSuite) {
    delegate.setLimitForMetricRunsInSuite(limitForMetricRunsInSuite);
  }

  public boolean isMarkUnstable() {
    return delegate.isMarkUnstable();
  }

  @DataBoundSetter
  public void setMarkUnstable(boolean markUnstable) {
    delegate.setMarkUnstable(markUnstable);
  }

  public boolean isRiskHeatMap() {
    return delegate.isRiskHeatMap();
  }

  @DataBoundSetter
  public void setRiskHeatMap(boolean riskHeatMap) {
    delegate.setRiskHeatMap(riskHeatMap);
  }

  public String getRiskHeatMapDefectQuery() {
    return delegate.getRiskHeatMapDefectQuery();
  }

  @DataBoundSetter
  public void setRiskHeatMapDefectQuery(String riskHeatMapDefectQuery) {
    delegate.setRiskHeatMapDefectQuery(riskHeatMapDefectQuery);
  }

  public int getRiskHeatMapMaxDefects() {
    return delegate.getRiskHeatMapMaxDefects();
  }

  @DataBoundSetter
  public void setRiskHeatMapMaxDefects(int riskHeatMapMaxDefects) {
    delegate.setRiskHeatMapMaxDefects(riskHeatMapMaxDefects);
  }

  public String getPassedStatuses() {
    return delegate.getPassedStatuses();
  }

  @DataBoundSetter
  public void setPassedStatuses(String passedStatuses) {
    delegate.setPassedStatuses(passedStatuses);
  }

  public String getFailedStatuses() {
    return delegate.getFailedStatuses();
  }

  @DataBoundSetter
  public void setFailedStatuses(String failedStatuses) {
    delegate.setFailedStatuses(failedStatuses);
  }

  public String getNeutralStatuses() {
    return delegate.getNeutralStatuses();
  }

  @DataBoundSetter
  public void setNeutralStatuses(String neutralStatuses) {
    delegate.setNeutralStatuses(neutralStatuses);
  }

  public String getRunningStatuses() {
    return delegate.getRunningStatuses();
  }

  @DataBoundSetter
  public void setRunningStatuses(String runningStatuses) {
    delegate.setRunningStatuses(runningStatuses);
  }

  @Override
  public void perform(
      Run<?, ?> run,
      FilePath workspace,
      EnvVars environment,
      Launcher launcher,
      TaskListener listener)
      throws InterruptedException, IOException {
    GateRequest request = createRequest(workspace, environment, listener);
    OctaneGateReportAction reportAction = OctaneGateReportAction.attachTo(run, request);
    new OctaneGateLogListener().logReportLink(listener, reportAction.getReportUrl());
    try {
      new OctaneGateRunner().run(request, listener, reportAction);
    } catch (GateFailedException e) {
      if (delegate.isMarkUnstable()) {
        run.setResult(Result.UNSTABLE);
        listener.getLogger().println(Util.forLog(e.getMessage()));
      } else {
        throw e;
      }
    } catch (IOException | InterruptedException e) {
      reportAction.onError(e.getMessage(), request);
      throw e;
    } catch (RuntimeException e) {
      reportAction.onError(e.getMessage(), request);
      throw e;
    }
  }

  GateRequest createRequest(FilePath workspace, EnvVars environment, TaskListener listener)
      throws IOException, InterruptedException {
    GateRequest request = delegate.toRequest();
    OctaneSpaceMappingResolver.ResolvedConnection connection =
        new OctaneSpaceMappingResolver()
            .resolve(workspace, spacesMappingFile, sharedSpaceName, workspaceName);
    request.setServerId(connection.serverId());
    request.setBaseUrl(connection.baseUrl());
    request.setCredentialsId(connection.credentialsId());
    request.setSharedSpaceId(connection.sharedSpaceId());
    request.setWorkspaceId(connection.workspaceId());
    request.setAutomatedTestingTarget(OctaneSuiteGateStep.automatedTestingTarget(environment));
    request.setDefinedScope(OctaneSuiteGateStep.definedScope(environment));
    if (connection.insecureTransport()) {
      listener.getLogger().println("Applied URL is insecure. Move to HTTPS for better security.");
    }
    listener
        .getLogger()
        .println(
            "Resolved shared space '"
                + Util.forLog(sharedSpaceName)
                + "' to '"
                + Util.forLog(connection.sharedSpaceName())
                + "' ("
                + connection.sharedSpaceId()
                + ") and workspace '"
                + Util.forLog(workspaceName)
                + "' to '"
                + Util.forLog(connection.workspaceName())
                + "' ("
                + connection.workspaceId()
                + ") from "
                + Util.forLog(spacesMappingFile)
                + ".");
    return request;
  }

  @Extension
  @Symbol("octaneSuiteGateBuilder")
  public static class DescriptorImpl extends BuildStepDescriptor<Builder> {
    @Override
    @SuppressWarnings("rawtypes")
    public boolean isApplicable(Class<? extends AbstractProject> jobType) {
      return true;
    }

    @Override
    public String getDisplayName() {
      return "ALM Octane Suite Gate";
    }

    public FormValidation doCheckSpacesMappingFile(@QueryParameter String value) {
      try {
        OctaneSpaceMappingResolver.normalizeMappingFile(value);
        return FormValidation.ok();
      } catch (AbortException e) {
        return FormValidation.error(e.getMessage());
      }
    }

    public FormValidation doCheckSharedSpaceName(@QueryParameter String value) {
      return checkRequiredMappingSelector("Shared space", value);
    }

    public FormValidation doCheckWorkspaceName(@QueryParameter String value) {
      return checkRequiredMappingSelector("Workspace", value);
    }

    public FormValidation doCheckSuiteRunId(@QueryParameter String value) {
      return new OctaneSuiteGateStep.DescriptorImpl().doCheckSuiteRunId(value);
    }

    public FormValidation doCheckSharedSpaceId(@QueryParameter String value) {
      return new OctaneSuiteGateStep.DescriptorImpl().doCheckSharedSpaceId(value);
    }

    public FormValidation doCheckWorkspaceId(@QueryParameter String value) {
      return new OctaneSuiteGateStep.DescriptorImpl().doCheckWorkspaceId(value);
    }

    public FormValidation doCheckCriteria(@QueryParameter String value) {
      return new OctaneSuiteGateStep.DescriptorImpl().doCheckCriteria(value);
    }

    public FormValidation doCheckPollIntervalSeconds(@QueryParameter String value) {
      return new OctaneSuiteGateStep.DescriptorImpl().doCheckPollIntervalSeconds(value);
    }

    public FormValidation doCheckTimeoutMinutes(@QueryParameter String value) {
      return new OctaneSuiteGateStep.DescriptorImpl().doCheckTimeoutMinutes(value);
    }

    public FormValidation doCheckTimeoutMinutesExtended(@QueryParameter String value) {
      return new OctaneSuiteGateStep.DescriptorImpl().doCheckTimeoutMinutesExtended(value);
    }

    public FormValidation doCheckBasePassrateFigure(@QueryParameter String value) {
      return new OctaneSuiteGateStep.DescriptorImpl().doCheckBasePassrateFigure(value);
    }

    public FormValidation doCheckBaseExecutionFigure(@QueryParameter String value) {
      return new OctaneSuiteGateStep.DescriptorImpl().doCheckBaseExecutionFigure(value);
    }

    public FormValidation doCheckRiskHeatMapMaxDefects(@QueryParameter String value) {
      return new OctaneSuiteGateStep.DescriptorImpl().doCheckRiskHeatMapMaxDefects(value);
    }

    private FormValidation checkRequiredMappingSelector(String label, String value) {
      return Util.isBlank(value)
          ? FormValidation.error(label + " name or ID is required.")
          : FormValidation.ok();
    }
  }
}
