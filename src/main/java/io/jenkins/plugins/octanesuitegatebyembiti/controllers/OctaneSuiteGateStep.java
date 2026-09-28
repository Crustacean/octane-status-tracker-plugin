package io.jenkins.plugins.octanesuitegatebyembiti.controllers;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.FormValidation;
import io.jenkins.plugins.octanesuitegatebyembiti.actions.OctaneGateReportAction;
import io.jenkins.plugins.octanesuitegatebyembiti.configs.OctaneServerUrl;
import io.jenkins.plugins.octanesuitegatebyembiti.listeners.OctaneGateLogListener;
import io.jenkins.plugins.octanesuitegatebyembiti.models.GateRequest;
import io.jenkins.plugins.octanesuitegatebyembiti.models.GateResult;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneDefectGroup;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneGateScope;
import io.jenkins.plugins.octanesuitegatebyembiti.models.StatusClassifier;
import io.jenkins.plugins.octanesuitegatebyembiti.models.SuiteRunSelector;
import io.jenkins.plugins.octanesuitegatebyembiti.services.CriteriaException;
import io.jenkins.plugins.octanesuitegatebyembiti.services.CriteriaExpression;
import io.jenkins.plugins.octanesuitegatebyembiti.services.GateFailedException;
import io.jenkins.plugins.octanesuitegatebyembiti.services.OctaneGateExecutors;
import io.jenkins.plugins.octanesuitegatebyembiti.services.OctaneGateRunner;
import io.jenkins.plugins.octanesuitegatebyembiti.services.OctanePollRefreshCoordinator;
import io.jenkins.plugins.octanesuitegatebyembiti.utils.OctaneQueryValidator;
import io.jenkins.plugins.octanesuitegatebyembiti.utils.Util;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import jenkins.util.Timer;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

public class OctaneSuiteGateStep extends Step {
  private final String serverId;
  private final String suiteRunId;
  private String baseUrl = "";
  private String credentialsId = "";
  private String sharedSpaceId = "";
  private String workspaceId = "";
  private String criteria = GateRequest.DEFAULT_CRITERIA;
  private List<OctaneGateScope> scopes = new ArrayList<>();
  private List<OctaneDefectGroup> defectGroups = new ArrayList<>();
  private int pollIntervalSeconds = GateRequest.DEFAULT_POLL_INTERVAL_SECONDS;
  private int timeoutMinutes = GateRequest.DEFAULT_TIMEOUT_MINUTES;
  private int timeoutMinutesExtended = GateRequest.DEFAULT_TIMEOUT_MINUTES_EXTENDED;
  private int basePassrateFigure = GateRequest.DEFAULT_BASE_PASSRATE_FIGURE;
  private int baseExecutionFigure = GateRequest.DEFAULT_BASE_EXECUTION_FIGURE;
  private int limitForMetricRunsInSuite = GateRequest.DEFAULT_LIMIT_FOR_METRIC_RUNS_IN_SUITE;
  private boolean markUnstable;
  private boolean riskHeatMap;
  private String riskHeatMapDefectQuery = "";
  private int riskHeatMapMaxDefects = GateRequest.DEFAULT_RISK_HEAT_MAP_MAX_DEFECTS;
  private String passedStatuses = StatusClassifier.DEFAULT_PASSED_STATUSES;
  private String failedStatuses = StatusClassifier.DEFAULT_FAILED_STATUSES;
  private String neutralStatuses = StatusClassifier.DEFAULT_NEUTRAL_STATUSES;
  private String runningStatuses = StatusClassifier.DEFAULT_RUNNING_STATUSES;

  @DataBoundConstructor
  public OctaneSuiteGateStep(String serverId, String suiteRunId) {
    this.serverId = Util.trimToEmpty(serverId);
    this.suiteRunId = Util.trimToEmpty(suiteRunId);
  }

  public String getServerId() {
    return serverId;
  }

  public String getSuiteRunId() {
    return suiteRunId;
  }

  public String getBaseUrl() {
    return baseUrl;
  }

  @DataBoundSetter
  public void setBaseUrl(String baseUrl) {
    this.baseUrl = Util.trimToEmpty(baseUrl);
  }

  public String getCredentialsId() {
    return credentialsId;
  }

  @DataBoundSetter
  public void setCredentialsId(String credentialsId) {
    this.credentialsId = Util.trimToEmpty(credentialsId);
  }

  public String getSharedSpaceId() {
    return sharedSpaceId;
  }

  @DataBoundSetter
  public void setSharedSpaceId(String sharedSpaceId) {
    this.sharedSpaceId = Util.trimToEmpty(sharedSpaceId);
  }

  public String getWorkspaceId() {
    return workspaceId;
  }

  @DataBoundSetter
  public void setWorkspaceId(String workspaceId) {
    this.workspaceId = Util.trimToEmpty(workspaceId);
  }

  public String getCriteria() {
    return criteria;
  }

  @DataBoundSetter
  public void setCriteria(String criteria) {
    String trimmed = Util.trimToEmpty(criteria);
    this.criteria = trimmed.isEmpty() ? GateRequest.DEFAULT_CRITERIA : trimmed;
  }

  public List<OctaneGateScope> getScopes() {
    return Collections.unmodifiableList(scopes);
  }

  @DataBoundSetter
  public void setScopes(List<OctaneGateScope> scopes) {
    this.scopes = scopes == null ? new ArrayList<>() : new ArrayList<>(scopes);
  }

  public List<OctaneDefectGroup> getDefectGroups() {
    return defectGroups == null ? List.of() : Collections.unmodifiableList(defectGroups);
  }

  @DataBoundSetter
  public void setDefectGroups(List<OctaneDefectGroup> defectGroups) {
    this.defectGroups = defectGroups == null ? new ArrayList<>() : new ArrayList<>(defectGroups);
  }

  public int getPollIntervalSeconds() {
    return pollIntervalSeconds;
  }

  @DataBoundSetter
  public void setPollIntervalSeconds(int pollIntervalSeconds) {
    this.pollIntervalSeconds = Math.max(1, pollIntervalSeconds);
  }

  public int getTimeoutMinutes() {
    return timeoutMinutes;
  }

  @DataBoundSetter
  public void setTimeoutMinutes(int timeoutMinutes) {
    this.timeoutMinutes = Math.max(1, timeoutMinutes);
  }

  public int getTimeoutMinutesExtended() {
    return timeoutMinutesExtended;
  }

  @DataBoundSetter
  public void setTimeoutMinutesExtended(int timeoutMinutesExtended) {
    this.timeoutMinutesExtended = Math.max(0, timeoutMinutesExtended);
  }

  public int getBasePassrateFigure() {
    return basePassrateFigure;
  }

  @DataBoundSetter
  public void setBasePassrateFigure(int basePassrateFigure) {
    this.basePassrateFigure = percentageThreshold(basePassrateFigure);
  }

  public int getBaseExecutionFigure() {
    return baseExecutionFigure;
  }

  @DataBoundSetter
  public void setBaseExecutionFigure(int baseExecutionFigure) {
    this.baseExecutionFigure = percentageThreshold(baseExecutionFigure);
  }

  public int getLimitForMetricRunsInSuite() {
    return limitForMetricRunsInSuite;
  }

  @DataBoundSetter
  public void setLimitForMetricRunsInSuite(int limitForMetricRunsInSuite) {
    this.limitForMetricRunsInSuite = Math.max(1, limitForMetricRunsInSuite);
  }

  public boolean isMarkUnstable() {
    return markUnstable;
  }

  @DataBoundSetter
  public void setMarkUnstable(boolean markUnstable) {
    this.markUnstable = markUnstable;
  }

  public boolean isRiskHeatMap() {
    return riskHeatMap;
  }

  @DataBoundSetter
  public void setRiskHeatMap(boolean riskHeatMap) {
    this.riskHeatMap = riskHeatMap;
  }

  public String getRiskHeatMapDefectQuery() {
    return riskHeatMapDefectQuery;
  }

  @DataBoundSetter
  public void setRiskHeatMapDefectQuery(String riskHeatMapDefectQuery) {
    this.riskHeatMapDefectQuery =
        OctaneQueryValidator.normalize(riskHeatMapDefectQuery, "Risk heat map defect query");
  }

  public int getRiskHeatMapMaxDefects() {
    return riskHeatMapMaxDefects;
  }

  @DataBoundSetter
  public void setRiskHeatMapMaxDefects(int riskHeatMapMaxDefects) {
    this.riskHeatMapMaxDefects = Math.max(1, riskHeatMapMaxDefects);
  }

  public String getPassedStatuses() {
    return passedStatuses;
  }

  @DataBoundSetter
  public void setPassedStatuses(String passedStatuses) {
    this.passedStatuses = defaultIfBlank(passedStatuses, StatusClassifier.DEFAULT_PASSED_STATUSES);
  }

  public String getFailedStatuses() {
    return failedStatuses;
  }

  @DataBoundSetter
  public void setFailedStatuses(String failedStatuses) {
    this.failedStatuses = defaultIfBlank(failedStatuses, StatusClassifier.DEFAULT_FAILED_STATUSES);
  }

  public String getNeutralStatuses() {
    return neutralStatuses;
  }

  @DataBoundSetter
  public void setNeutralStatuses(String neutralStatuses) {
    this.neutralStatuses =
        defaultIfBlank(neutralStatuses, StatusClassifier.DEFAULT_NEUTRAL_STATUSES);
  }

  public String getRunningStatuses() {
    return runningStatuses;
  }

  @DataBoundSetter
  public void setRunningStatuses(String runningStatuses) {
    this.runningStatuses =
        defaultIfBlank(runningStatuses, StatusClassifier.DEFAULT_RUNNING_STATUSES);
  }

  @Override
  public StepExecution start(StepContext context) {
    return new Execution(toRequest(), context);
  }

  GateRequest toRequest() {
    GateRequest request = new GateRequest(serverId, suiteRunId);
    request.setBaseUrl(baseUrl);
    request.setCredentialsId(credentialsId);
    request.setSharedSpaceId(sharedSpaceId);
    request.setWorkspaceId(workspaceId);
    request.setCriteria(criteria);
    request.setScopes(scopes);
    request.setDefectGroups(getDefectGroups());
    request.setPollIntervalSeconds(pollIntervalSeconds);
    request.setTimeoutMinutes(timeoutMinutes);
    request.setTimeoutMinutesExtended(timeoutMinutesExtended);
    request.setBasePassrateFigure(basePassrateFigure);
    request.setBaseExecutionFigure(baseExecutionFigure);
    request.setLimitForMetricRunsInSuite(limitForMetricRunsInSuite);
    request.setMarkUnstable(markUnstable);
    request.setRiskHeatMap(riskHeatMap);
    request.setRiskHeatMapDefectQuery(riskHeatMapDefectQuery);
    request.setRiskHeatMapMaxDefects(riskHeatMapMaxDefects);
    request.setPassedStatuses(passedStatuses);
    request.setFailedStatuses(failedStatuses);
    request.setNeutralStatuses(neutralStatuses);
    request.setRunningStatuses(runningStatuses);
    return request;
  }

  private String defaultIfBlank(String value, String defaultValue) {
    String trimmed = Util.trimToEmpty(value);
    return trimmed.isEmpty() ? defaultValue : trimmed;
  }

  private int percentageThreshold(int value) {
    return Math.min(100, Math.max(0, value));
  }

  static int automatedTestingTarget(EnvVars environment) throws AbortException {
    String variableName = GateRequest.AUTOMATED_TESTING_TARGET_ENV;
    String rawValue = environmentValue(environment, variableName);
    if (isUndefinedEnvironmentValue(rawValue)) {
      variableName = GateRequest.GLOBAL_AUTOMATED_TESTING_TARGET_ENV;
      rawValue = environmentValue(environment, variableName);
    }
    if (isUndefinedEnvironmentValue(rawValue)) {
      return GateRequest.DEFAULT_AUTOMATED_TESTING_TARGET;
    }
    try {
      int target = Integer.parseInt(rawValue);
      if (target < 1) {
        throw new NumberFormatException();
      }
      return Math.min(100, target);
    } catch (NumberFormatException e) {
      throw new AbortException(variableName + " must be a positive whole number.");
    }
  }

  static String definedScope(EnvVars environment) {
    return environmentValue(environment, GateRequest.DEFINED_SCOPE_ENV);
  }

  private static String environmentValue(EnvVars environment, String variableName) {
    return environment == null ? "" : Util.trimToEmpty(environment.get(variableName));
  }

  private static boolean isUndefinedEnvironmentValue(String value) {
    String normalized = Util.trimToEmpty(value);
    return normalized.isEmpty()
        || "null".equalsIgnoreCase(normalized)
        || "undefined".equalsIgnoreCase(normalized);
  }

  private static class Execution extends StepExecution {
    private static final long serialVersionUID = 1L;

    private final GateRequest request;
    private OctaneGateRunner.PollingState pollingState;
    private boolean completed;
    private boolean reportLinkLogged;
    private boolean environmentConfigured;
    private transient Future<?> pollFuture;
    private transient ScheduledFuture<?> wakeupFuture;
    private transient OctaneGateRunner.PollingSession pollingSession;
    private transient OctaneGateReportAction reportAction;
    private transient Runnable manualExitCallback;
    private transient OctaneGateReportAction.RefreshCallback refreshCallback;
    private transient OctanePollRefreshCoordinator refreshCoordinator;

    Execution(GateRequest request, StepContext context) {
      super(context);
      this.request = request;
    }

    @Override
    public boolean start() {
      schedule(Duration.ZERO);
      return false;
    }

    @Override
    public void onResume() {
      schedule(Duration.ZERO);
    }

    @Override
    public void stop(@NonNull Throwable cause) {
      if (!markCompleted()) {
        return;
      }
      cancelPendingWork();
      closeSession();
      clearManualExitCallback();
      clearRefreshCallback();
      getContext().onFailure(cause);
    }

    private synchronized void schedule(Duration delay) {
      if (completed) {
        return;
      }
      if (wakeupFuture != null) {
        wakeupFuture.cancel(false);
      }
      long delayMillis = Math.max(0L, delay == null ? 0L : delay.toMillis());
      wakeupFuture = Timer.get().schedule(this::startPoll, delayMillis, TimeUnit.MILLISECONDS);
    }

    private void startPoll() {
      try {
        startOrJoinPoll(false);
      } catch (RuntimeException failure) {
        completeWithFailure(failure);
      }
    }

    private OctanePollRefreshCoordinator.PollRequest startOrJoinPoll(boolean immediate) {
      synchronized (this) {
        if (completed) {
          return null;
        }
        OctanePollRefreshCoordinator.PollRequest poll = refreshCoordinator().beginOrJoin();
        if (!poll.owner()) {
          return poll;
        }
        if (wakeupFuture != null) {
          if (immediate) {
            wakeupFuture.cancel(false);
          }
          wakeupFuture = null;
        }
        try {
          pollFuture = OctaneGateExecutors.submitPoll(this::runSinglePoll);
          return poll;
        } catch (RuntimeException failure) {
          refreshCoordinator().complete(failure);
          throw failure;
        }
      }
    }

    private void runSinglePoll() {
      if (isCompleted()) {
        finishPoll();
        return;
      }
      try {
        StepContext context = getContext();
        configureEnvironment(context);
        Run<?, ?> run = context.get(Run.class);
        TaskListener listener = context.get(TaskListener.class);
        ensureReportAction(run, listener);
        ensurePollingSession(listener);
        OctaneGateRunner.PollOutcome outcome = pollingSession.pollOnce();
        if (outcome.isComplete()) {
          completeSuccessfully(outcome.getResult());
        } else {
          finishPollAndSchedule(outcome.getNextDelay());
        }
      } catch (GateFailedException e) {
        finishPoll();
        handleGateFailure(e);
      } catch (Throwable t) {
        if (!isCompleted() && reportAction != null) {
          reportAction.onError(t.getMessage(), request);
        }
        finishPoll(t);
        completeWithFailure(t);
      }
    }

    private void configureEnvironment(StepContext context)
        throws IOException, InterruptedException {
      if (environmentConfigured) {
        return;
      }
      EnvVars environment = context.get(EnvVars.class);
      request.setAutomatedTestingTarget(OctaneSuiteGateStep.automatedTestingTarget(environment));
      request.setDefinedScope(OctaneSuiteGateStep.definedScope(environment));
      request.setCriticalGraphsTitle(
          environmentValue(environment, GateRequest.CRITICAL_GRAPHS_TITLE_ENV));
      request.setRegressionGraphsTitle(
          environmentValue(environment, GateRequest.REGRESSION_GRAPHS_TITLE_ENV));
      environmentConfigured = true;
    }

    private void ensureReportAction(Run<?, ?> run, TaskListener listener) {
      if (reportAction == null) {
        reportAction = run.getAction(OctaneGateReportAction.class);
      }
      if (reportAction == null) {
        reportAction = OctaneGateReportAction.attachTo(run, request);
      }
      if (!reportLinkLogged) {
        new OctaneGateLogListener().logReportLink(listener, reportAction.getReportUrl());
        reportLinkLogged = true;
      }
      if (manualExitCallback == null) {
        manualExitCallback = this::wakeForManualExit;
      }
      reportAction.setManualExitCallback(manualExitCallback);
      if (refreshCallback == null) {
        refreshCallback = this::refreshAndWait;
      }
      reportAction.setRefreshCallback(refreshCallback);
      if (pollingState == null) {
        pollingState =
            new OctaneGateRunner.PollingState(startedAt(reportAction.getSnapshot().getStartedAt()));
      }
    }

    private void ensurePollingSession(TaskListener listener)
        throws IOException, InterruptedException {
      if (pollingSession == null) {
        pollingSession =
            new OctaneGateRunner().openSession(request, listener, reportAction, pollingState);
      }
    }

    private Instant startedAt(String value) {
      try {
        return Instant.parse(value);
      } catch (RuntimeException ignored) {
        return Instant.now();
      }
    }

    private void wakeForManualExit() {
      synchronized (this) {
        if (completed || refreshCoordinator().isRunning()) {
          return;
        }
      }
      schedule(Duration.ZERO);
    }

    private boolean refreshAndWait() throws Exception {
      OctanePollRefreshCoordinator.PollRequest poll = startOrJoinPoll(true);
      if (poll == null) {
        return false;
      }
      try {
        poll.completion().get();
      } catch (ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof Exception exception) {
          throw exception;
        }
        if (cause instanceof Error error) {
          throw error;
        }
        throw new IllegalStateException("Octane refresh poll failed.", cause);
      }
      return poll.owner();
    }

    private void finishPollAndSchedule(Duration delay) {
      finishPoll();
      schedule(delay);
    }

    private synchronized void finishPoll() {
      pollFuture = null;
      refreshCoordinator().complete(null);
    }

    private synchronized void finishPoll(Throwable failure) {
      pollFuture = null;
      refreshCoordinator().complete(failure);
    }

    private void completeSuccessfully(GateResult result) {
      finishPoll();
      if (!markCompleted()) {
        return;
      }
      closeSession();
      clearManualExitCallback();
      clearRefreshCallback();
      getContext().onSuccess(result.toPipelineMap());
    }

    private void completeWithFailure(Throwable failure) {
      if (!markCompleted()) {
        return;
      }
      cancelPendingWork();
      closeSession();
      clearManualExitCallback();
      clearRefreshCallback();
      getContext().onFailure(failure);
    }

    private synchronized boolean markCompleted() {
      if (completed) {
        return false;
      }
      completed = true;
      return true;
    }

    private synchronized boolean isCompleted() {
      return completed;
    }

    private synchronized void cancelPendingWork() {
      if (wakeupFuture != null) {
        wakeupFuture.cancel(false);
        wakeupFuture = null;
      }
      if (pollFuture != null) {
        pollFuture.cancel(true);
        pollFuture = null;
      }
      refreshCoordinator().complete(new InterruptedException("Octane poll was cancelled."));
    }

    private void closeSession() {
      OctaneGateRunner.PollingSession session = pollingSession;
      pollingSession = null;
      if (session == null) {
        return;
      }
      try {
        session.close();
      } catch (IOException ignored) {
        // The gate is already terminal; sign-out failure must not mask its result.
      }
    }

    private void clearManualExitCallback() {
      OctaneGateReportAction action = reportAction;
      Runnable callback = manualExitCallback;
      if (action != null && callback != null) {
        action.clearManualExitCallback(callback);
      }
      manualExitCallback = null;
    }

    private void clearRefreshCallback() {
      OctaneGateReportAction action = reportAction;
      OctaneGateReportAction.RefreshCallback callback = refreshCallback;
      if (action != null && callback != null) {
        action.clearRefreshCallback(callback);
      }
      refreshCallback = null;
    }

    private OctanePollRefreshCoordinator refreshCoordinator() {
      if (refreshCoordinator == null) {
        refreshCoordinator = new OctanePollRefreshCoordinator();
      }
      return refreshCoordinator;
    }

    private void handleGateFailure(GateFailedException exception) {
      try {
        if (request.isMarkUnstable()) {
          Run<?, ?> run = getContext().get(Run.class);
          TaskListener listener = getContext().get(TaskListener.class);
          run.setResult(Result.UNSTABLE);
          listener.getLogger().println(Util.forLog(exception.getMessage()));
          completeSuccessfully(exception.getResult());
        } else {
          completeWithFailure(exception);
        }
      } catch (IOException | InterruptedException e) {
        completeWithFailure(e);
      }
    }
  }

  @Extension
  @Symbol("octaneSuiteGate")
  public static class DescriptorImpl extends StepDescriptor {
    @Override
    public String getFunctionName() {
      return "octaneSuiteGate";
    }

    @NonNull
    @Override
    public String getDisplayName() {
      return "ALM Octane Suite Gate";
    }

    @Override
    public Set<? extends Class<?>> getRequiredContext() {
      return Set.of(EnvVars.class, Run.class, TaskListener.class);
    }

    public FormValidation doCheckServerId(@QueryParameter String value) {
      if (Util.isBlank(value)) {
        return FormValidation.error("Server ID is required.");
      }
      return FormValidation.ok();
    }

    public FormValidation doCheckBaseUrl(@QueryParameter String value) {
      try {
        OctaneServerUrl.normalize(value);
        return FormValidation.ok();
      } catch (IllegalArgumentException e) {
        return FormValidation.error(e.getMessage());
      }
    }

    public FormValidation doCheckCredentialsId(@QueryParameter String value) {
      if (Util.isBlank(value)) {
        return FormValidation.error("API credential ID is required.");
      }
      return FormValidation.ok();
    }

    public FormValidation doCheckSuiteRunId(@QueryParameter String value) {
      try {
        SuiteRunSelector selector = SuiteRunSelector.parse(value);
        if (selector.getExplicitIds().size() > GateRequest.MAX_SUITE_RUN_IDS) {
          return FormValidation.error(
              "At most " + GateRequest.MAX_SUITE_RUN_IDS + " suite run IDs are supported.");
        }
      } catch (IllegalArgumentException e) {
        return FormValidation.error(e.getMessage());
      }
      return FormValidation.ok();
    }

    public FormValidation doCheckSharedSpaceId(@QueryParameter String value) {
      return checkRequiredNumber("Shared space ID", value);
    }

    public FormValidation doCheckWorkspaceId(@QueryParameter String value) {
      return checkRequiredNumber("Workspace ID", value);
    }

    public FormValidation doCheckCriteria(@QueryParameter String value) {
      try {
        CriteriaExpression.parse(Util.isBlank(value) ? GateRequest.DEFAULT_CRITERIA : value);
        return FormValidation.ok();
      } catch (CriteriaException e) {
        return FormValidation.error(e.getMessage());
      }
    }

    public FormValidation doCheckPollIntervalSeconds(@QueryParameter String value) {
      return checkBoundedInteger("Poll interval", value, 1, GateRequest.MAX_POLL_INTERVAL_SECONDS);
    }

    public FormValidation doCheckTimeoutMinutes(@QueryParameter String value) {
      return checkBoundedInteger("Timeout", value, 1, GateRequest.MAX_TIMEOUT_MINUTES);
    }

    public FormValidation doCheckTimeoutMinutesExtended(@QueryParameter String value) {
      if (Util.isBlank(value)) {
        return FormValidation.ok();
      }
      return checkBoundedInteger("Extended timeout", value, 0, GateRequest.MAX_TIMEOUT_MINUTES);
    }

    public FormValidation doCheckBasePassrateFigure(@QueryParameter String value) {
      return checkPercentage("Base pass rate", value);
    }

    public FormValidation doCheckBaseExecutionFigure(@QueryParameter String value) {
      return checkPercentage("Base execution", value);
    }

    public FormValidation doCheckRiskHeatMapMaxDefects(@QueryParameter String value) {
      return checkBoundedInteger(
          "Risk heat map max defects", value, 1, GateRequest.MAX_RISK_HEAT_MAP_DEFECTS);
    }

    private FormValidation checkRequiredNumber(String label, String value) {
      if (Util.isBlank(value)) {
        return FormValidation.error(label + " is required.");
      }
      if (value.matches("[0-9]{1,18}")) {
        return FormValidation.ok();
      }
      return FormValidation.error(label + " must contain 1 to 18 digits.");
    }

    private FormValidation checkBoundedInteger(
        String label, String value, int minimum, int maximum) {
      try {
        int parsed = Integer.parseInt(value);
        if (parsed < minimum || parsed > maximum) {
          return FormValidation.error(
              label + " must be between " + minimum + " and " + maximum + ".");
        }
        return FormValidation.ok();
      } catch (NumberFormatException e) {
        return FormValidation.error(label + " must be a number.");
      }
    }

    private FormValidation checkPercentage(String label, String value) {
      try {
        int percentage = Integer.parseInt(value);
        if (percentage < 0 || percentage > 100) {
          return FormValidation.error(label + " must be between 0 and 100.");
        }
        return FormValidation.ok();
      } catch (NumberFormatException e) {
        return FormValidation.error(label + " must be a whole number.");
      }
    }
  }
}
