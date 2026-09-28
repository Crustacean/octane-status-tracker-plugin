package io.jenkins.plugins.octanesuitegatebyembiti.models;

import io.jenkins.plugins.octanesuitegatebyembiti.utils.OctaneQueryValidator;
import io.jenkins.plugins.octanesuitegatebyembiti.utils.Util;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class GateRequest implements Serializable {
  private static final long serialVersionUID = 1L;

  public static final int DEFAULT_POLL_INTERVAL_SECONDS = 30;
  public static final int DEFAULT_TIMEOUT_MINUTES = 120;
  public static final int DEFAULT_TIMEOUT_MINUTES_EXTENDED = 0;
  public static final int DEFAULT_RISK_HEAT_MAP_MAX_DEFECTS = 1000;
  public static final int DEFAULT_BASE_PASSRATE_FIGURE = 95;
  public static final int DEFAULT_BASE_EXECUTION_FIGURE = 100;
  public static final int DEFAULT_AUTOMATED_TESTING_TARGET = 100;
  public static final int DEFAULT_LIMIT_FOR_METRIC_RUNS_IN_SUITE = 250;
  public static final String AUTOMATED_TESTING_TARGET_ENV = "AUTOMATED_TESTING_TARGET";
  public static final String GLOBAL_AUTOMATED_TESTING_TARGET_ENV =
      "global_automated_testing_target";
  public static final String DEFINED_SCOPE_ENV = "OCTANE_DEFINED_SCOPE";
  public static final String CRITICAL_GRAPHS_TITLE_ENV = "OCTANE_CRITICAL_GRAPHS_TITLE";
  public static final String REGRESSION_GRAPHS_TITLE_ENV = "OCTANE_REGRESSION_GRAPHS_TITLE";
  public static final String DEFAULT_CRITERIA = "100% execution AND 100% pass";
  public static final int MAX_POLL_INTERVAL_SECONDS = 3600;
  public static final int MAX_TIMEOUT_MINUTES = 10_080;
  public static final int MAX_RISK_HEAT_MAP_DEFECTS = 10_000;
  public static final int MAX_SUITE_RUN_IDS = 1_000;

  private String serverId;
  private final String suiteRunId;
  private String baseUrl = "";
  private String credentialsId = "";
  private String sharedSpaceId = "";
  private String workspaceId = "";
  private String criteria = DEFAULT_CRITERIA;
  private List<OctaneGateScope> scopes = new ArrayList<>();
  private List<OctaneDefectGroup> defectGroups = new ArrayList<>();
  private int pollIntervalSeconds = DEFAULT_POLL_INTERVAL_SECONDS;
  private int timeoutMinutes = DEFAULT_TIMEOUT_MINUTES;
  private int timeoutMinutesExtended = DEFAULT_TIMEOUT_MINUTES_EXTENDED;
  private int basePassrateFigure = DEFAULT_BASE_PASSRATE_FIGURE;
  private int baseExecutionFigure = DEFAULT_BASE_EXECUTION_FIGURE;
  private int automatedTestingTarget = DEFAULT_AUTOMATED_TESTING_TARGET;
  private int limitForMetricRunsInSuite = DEFAULT_LIMIT_FOR_METRIC_RUNS_IN_SUITE;
  private List<OctaneDefinedScope> definedScope = new ArrayList<>();
  private String criticalGraphsTitle = "";
  private String regressionGraphsTitle = "";
  private boolean markUnstable;
  private boolean riskHeatMap;
  private String riskHeatMapDefectQuery = "";
  private int riskHeatMapMaxDefects = DEFAULT_RISK_HEAT_MAP_MAX_DEFECTS;
  private String passedStatuses = StatusClassifier.DEFAULT_PASSED_STATUSES;
  private String failedStatuses = StatusClassifier.DEFAULT_FAILED_STATUSES;
  private String neutralStatuses = StatusClassifier.DEFAULT_NEUTRAL_STATUSES;
  private String runningStatuses = StatusClassifier.DEFAULT_RUNNING_STATUSES;

  public GateRequest(String serverId, String suiteRunId) {
    this.serverId = Util.trimToEmpty(serverId);
    this.suiteRunId = Util.trimToEmpty(suiteRunId);
  }

  public String getServerId() {
    return serverId;
  }

  public void setServerId(String serverId) {
    this.serverId = Util.trimToEmpty(serverId);
  }

  public String getSuiteRunId() {
    return suiteRunId;
  }

  public String getBaseUrl() {
    return Util.trimToEmpty(baseUrl);
  }

  public void setBaseUrl(String baseUrl) {
    this.baseUrl = Util.trimToEmpty(baseUrl);
  }

  public String getCredentialsId() {
    return Util.trimToEmpty(credentialsId);
  }

  public void setCredentialsId(String credentialsId) {
    this.credentialsId = Util.trimToEmpty(credentialsId);
  }

  public List<String> getSuiteRunIds() {
    return getSuiteRunSelector().getExplicitIds();
  }

  public SuiteRunSelector getSuiteRunSelector() {
    return SuiteRunSelector.parse(suiteRunId);
  }

  public String getSharedSpaceId() {
    return sharedSpaceId;
  }

  public void setSharedSpaceId(String sharedSpaceId) {
    this.sharedSpaceId = Util.trimToEmpty(sharedSpaceId);
  }

  public String getWorkspaceId() {
    return workspaceId;
  }

  public void setWorkspaceId(String workspaceId) {
    this.workspaceId = Util.trimToEmpty(workspaceId);
  }

  public String getCriteria() {
    return criteria;
  }

  public void setCriteria(String criteria) {
    String trimmed = Util.trimToEmpty(criteria);
    this.criteria = trimmed.isEmpty() ? DEFAULT_CRITERIA : trimmed;
  }

  public List<OctaneGateScope> getScopes() {
    return Collections.unmodifiableList(scopes);
  }

  public void setScopes(List<OctaneGateScope> scopes) {
    this.scopes = scopes == null ? new ArrayList<>() : new ArrayList<>(scopes);
  }

  public List<OctaneDefectGroup> getDefectGroups() {
    return defectGroups == null ? List.of() : Collections.unmodifiableList(defectGroups);
  }

  public void setDefectGroups(List<OctaneDefectGroup> defectGroups) {
    this.defectGroups = defectGroups == null ? new ArrayList<>() : new ArrayList<>(defectGroups);
  }

  public int getPollIntervalSeconds() {
    return pollIntervalSeconds;
  }

  public void setPollIntervalSeconds(int pollIntervalSeconds) {
    this.pollIntervalSeconds =
        Math.min(MAX_POLL_INTERVAL_SECONDS, Math.max(1, pollIntervalSeconds));
  }

  public int getTimeoutMinutes() {
    return timeoutMinutes;
  }

  public void setTimeoutMinutes(int timeoutMinutes) {
    this.timeoutMinutes = Math.min(MAX_TIMEOUT_MINUTES, Math.max(1, timeoutMinutes));
  }

  public int getTimeoutMinutesExtended() {
    return timeoutMinutesExtended;
  }

  public void setTimeoutMinutesExtended(int timeoutMinutesExtended) {
    this.timeoutMinutesExtended =
        Math.min(MAX_TIMEOUT_MINUTES, Math.max(0, timeoutMinutesExtended));
  }

  public int getBasePassrateFigure() {
    return basePassrateFigure;
  }

  public void setBasePassrateFigure(int basePassrateFigure) {
    this.basePassrateFigure = percentageThreshold(basePassrateFigure);
  }

  public int getBaseExecutionFigure() {
    return baseExecutionFigure;
  }

  public void setBaseExecutionFigure(int baseExecutionFigure) {
    this.baseExecutionFigure = percentageThreshold(baseExecutionFigure);
  }

  public int getAutomatedTestingTarget() {
    return automatedTestingTarget <= 0 ? DEFAULT_AUTOMATED_TESTING_TARGET : automatedTestingTarget;
  }

  public void setAutomatedTestingTarget(int automatedTestingTarget) {
    this.automatedTestingTarget = Math.min(100, Math.max(1, automatedTestingTarget));
  }

  public int getLimitForMetricRunsInSuite() {
    return Math.max(1, limitForMetricRunsInSuite);
  }

  public void setLimitForMetricRunsInSuite(int limitForMetricRunsInSuite) {
    this.limitForMetricRunsInSuite = Math.max(1, limitForMetricRunsInSuite);
  }

  public List<OctaneDefinedScope> getDefinedScope() {
    return definedScope == null ? List.of() : Collections.unmodifiableList(definedScope);
  }

  public void setDefinedScope(String definedScope) {
    this.definedScope = new ArrayList<>(OctaneDefinedScope.parse(definedScope));
  }

  public String getCriticalGraphsTitle() {
    return criticalGraphsTitle;
  }

  public void setCriticalGraphsTitle(String criticalGraphsTitle) {
    this.criticalGraphsTitle = normalizeGraphTitle(criticalGraphsTitle);
  }

  public String getRegressionGraphsTitle() {
    return regressionGraphsTitle;
  }

  public void setRegressionGraphsTitle(String regressionGraphsTitle) {
    this.regressionGraphsTitle = normalizeGraphTitle(regressionGraphsTitle);
  }

  public boolean isMarkUnstable() {
    return markUnstable;
  }

  public void setMarkUnstable(boolean markUnstable) {
    this.markUnstable = markUnstable;
  }

  public boolean isRiskHeatMap() {
    return riskHeatMap;
  }

  public void setRiskHeatMap(boolean riskHeatMap) {
    this.riskHeatMap = riskHeatMap;
  }

  public String getRiskHeatMapDefectQuery() {
    return riskHeatMapDefectQuery;
  }

  public void setRiskHeatMapDefectQuery(String riskHeatMapDefectQuery) {
    this.riskHeatMapDefectQuery =
        OctaneQueryValidator.normalize(riskHeatMapDefectQuery, "Risk heat map defect query");
  }

  public int getRiskHeatMapMaxDefects() {
    return riskHeatMapMaxDefects;
  }

  public void setRiskHeatMapMaxDefects(int riskHeatMapMaxDefects) {
    this.riskHeatMapMaxDefects =
        Math.min(MAX_RISK_HEAT_MAP_DEFECTS, Math.max(1, riskHeatMapMaxDefects));
  }

  public String getPassedStatuses() {
    return passedStatuses;
  }

  public void setPassedStatuses(String passedStatuses) {
    this.passedStatuses = defaultIfBlank(passedStatuses, StatusClassifier.DEFAULT_PASSED_STATUSES);
  }

  public String getFailedStatuses() {
    return failedStatuses;
  }

  public void setFailedStatuses(String failedStatuses) {
    this.failedStatuses = defaultIfBlank(failedStatuses, StatusClassifier.DEFAULT_FAILED_STATUSES);
  }

  public String getNeutralStatuses() {
    return neutralStatuses;
  }

  public void setNeutralStatuses(String neutralStatuses) {
    this.neutralStatuses =
        defaultIfBlank(neutralStatuses, StatusClassifier.DEFAULT_NEUTRAL_STATUSES);
  }

  public String getRunningStatuses() {
    return runningStatuses;
  }

  public void setRunningStatuses(String runningStatuses) {
    this.runningStatuses =
        defaultIfBlank(runningStatuses, StatusClassifier.DEFAULT_RUNNING_STATUSES);
  }

  public StatusClassifier createStatusClassifier() {
    return new StatusClassifier(passedStatuses, failedStatuses, neutralStatuses, runningStatuses);
  }

  private String defaultIfBlank(String value, String defaultValue) {
    String trimmed = Util.trimToEmpty(value);
    return trimmed.isEmpty() ? defaultValue : trimmed;
  }

  private int percentageThreshold(int value) {
    return Math.min(100, Math.max(0, value));
  }

  private String normalizeGraphTitle(String value) {
    String normalized = Util.trimToEmpty(value);
    if (normalized.isEmpty()
        || "null".equalsIgnoreCase(normalized)
        || "undefined".equalsIgnoreCase(normalized)) {
      return "";
    }
    return normalized.toUpperCase(Locale.ENGLISH);
  }
}
