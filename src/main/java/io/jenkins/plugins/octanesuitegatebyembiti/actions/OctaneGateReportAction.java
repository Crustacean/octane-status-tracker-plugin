package io.jenkins.plugins.octanesuitegatebyembiti.actions;

import hudson.AbortException;
import hudson.model.Item;
import hudson.model.Run;
import io.jenkins.plugins.octanesuitegatebyembiti.listeners.OctaneGateReportPublisher;
import io.jenkins.plugins.octanesuitegatebyembiti.models.GateRequest;
import io.jenkins.plugins.octanesuitegatebyembiti.models.GateResult;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneDefectTrend;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneDefinedScope;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneGateReportSnapshot;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneGateReportState;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneReportArtifactMetadata;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneSuiteAttributions;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneTestManagementAnalytics;
import io.jenkins.plugins.octanesuitegatebyembiti.models.StatusClassifier;
import io.jenkins.plugins.octanesuitegatebyembiti.services.OctaneReportArtifactStore;
import io.jenkins.plugins.octanesuitegatebyembiti.services.OctaneReportZoneHtmlRenderer;
import io.jenkins.plugins.octanesuitegatebyembiti.utils.OctaneReportJson;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jenkins.model.Jenkins;
import jenkins.model.RunAction2;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.interceptor.RequirePOST;
import tools.jackson.databind.node.ObjectNode;

public class OctaneGateReportAction implements RunAction2, OctaneGateReportPublisher, Serializable {
  private static final long serialVersionUID = 1L;

  public static final String URL_NAME = "octaneSuiteGateReport";

  private transient Run<?, ?> run;
  // Kept under its historical field name so reports saved by older plugin versions still load.
  // New publications clear it before Run.save() and persist only artifactMetadata.
  private volatile OctaneGateReportSnapshot snapshot;
  private transient volatile OctaneGateReportSnapshot snapshotCache =
      OctaneGateReportSnapshot.empty();
  private volatile OctaneReportArtifactMetadata artifactMetadata =
      OctaneReportArtifactMetadata.empty();
  private volatile int refreshSeconds = GateRequest.DEFAULT_POLL_INTERVAL_SECONDS;
  private volatile int timeoutSeconds = GateRequest.DEFAULT_TIMEOUT_MINUTES * 60;
  private volatile int timeoutExtendedSeconds = GateRequest.DEFAULT_TIMEOUT_MINUTES_EXTENDED * 60;
  private volatile int basePassrateFigure = GateRequest.DEFAULT_BASE_PASSRATE_FIGURE;
  private volatile int baseExecutionFigure = GateRequest.DEFAULT_BASE_EXECUTION_FIGURE;
  private volatile int automatedTestingTarget = GateRequest.DEFAULT_AUTOMATED_TESTING_TARGET;
  private volatile int limitForMetricRunsInSuite =
      GateRequest.DEFAULT_LIMIT_FOR_METRIC_RUNS_IN_SUITE;
  private volatile List<OctaneDefinedScope> definedScope = List.of();
  private volatile String criticalGraphsTitle = "";
  private volatile String regressionGraphsTitle = "";
  private volatile String startedAt = Instant.now().toString();
  private volatile boolean manualExitRequested;
  private volatile long manualExitRequestedAtMillis;
  private transient volatile Object manualExitLock = new Object();
  private transient Runnable manualExitCallback;
  private transient volatile Object refreshLock = new Object();
  private transient RefreshCallback refreshCallback;
  private transient volatile Object snapshotLock = new Object();

  public static OctaneGateReportAction attachTo(Run<?, ?> run, GateRequest request) {
    OctaneGateReportAction action = run.getAction(OctaneGateReportAction.class);
    if (action == null) {
      action = new OctaneGateReportAction();
    }
    action.run = run;
    action.configureTimers(request);
    run.addOrReplaceAction(action);
    action.publishSnapshot(
        action.withPreviousCycleMetrics(
            OctaneGateReportSnapshot.waiting(request, action.refreshSeconds, action.startedAt)));
    return action;
  }

  @Override
  public String getIconFileName() {
    return "graph.gif";
  }

  @Override
  public String getDisplayName() {
    return "Octane Gate Report";
  }

  @Override
  public String getUrlName() {
    return URL_NAME;
  }

  @Override
  public void onAttached(Run<?, ?> run) {
    this.run = run;
  }

  @Override
  public void onLoad(Run<?, ?> run) {
    this.run = run;
  }

  @Override
  public synchronized void onWaiting(GateRequest request, List<String> suiteRunIds) {
    publishSnapshot(
        withPreviousCycleMetrics(
            OctaneGateReportSnapshot.waiting(request, refreshSeconds, startedAt)));
  }

  @Override
  public synchronized void onPoll(GateResult result, StatusClassifier classifier) {
    publishSnapshot(
        withLiveReportData(
            attributedSnapshot(
                OctaneGateReportState.POLLING,
                "Polling ALM Octane suite runs.",
                result,
                classifier)));
  }

  @Override
  public synchronized void onExtendedTime(GateResult result, StatusClassifier classifier) {
    publishSnapshot(
        withLiveReportData(
            attributedSnapshot(
                OctaneGateReportState.EXTENDED_TIME,
                "Extended Octane polling time is active.",
                result,
                classifier)));
  }

  @Override
  public synchronized void onFinalizing(String message) {
    OctaneGateReportSnapshot current = currentSnapshot();
    if (current == null) {
      return;
    }
    publishSnapshot(
        current.withState(
            OctaneGateReportState.FINALIZING, defaultMessage(message), current.getUpdatedAt()));
  }

  @Override
  public synchronized void onFinal(
      OctaneGateReportState state, String message, GateResult result, StatusClassifier classifier) {
    publishSnapshot(withLiveReportData(attributedSnapshot(state, message, result, classifier)));
  }

  @Override
  public synchronized void onError(String message, GateRequest request) {
    OctaneGateReportSnapshot current = currentSnapshot();
    if (current != null && current.hasSections()) {
      publishSnapshot(
          current.withState(
              OctaneGateReportState.ERROR, defaultMessage(message), Instant.now().toString()));
      return;
    }
    OctaneGateReportSnapshot errorSnapshot =
        OctaneGateReportSnapshot.error(
            defaultMessage(message),
            request.getCriteria(),
            request.getSuiteRunId(),
            refreshSeconds,
            timeoutSeconds,
            timeoutExtendedSeconds,
            startedAt,
            request.isRiskHeatMap());
    if (current != null) {
      errorSnapshot = errorSnapshot.withDefectTrend(current.getDefectTrend());
    }
    publishSnapshot(withPreviousCycleMetrics(errorSnapshot));
  }

  public OctaneGateReportSnapshot getSnapshot() {
    OctaneGateReportSnapshot current = currentSnapshot();
    return current == null ? OctaneGateReportSnapshot.empty() : current;
  }

  public OctaneGateReportSnapshot awaitReconciledSnapshot() throws InterruptedException {
    synchronized (snapshotLock()) {
      OctaneGateReportSnapshot current = currentSnapshot();
      while (current != null && current.isFinalizing()) {
        snapshotLock().wait();
        current = currentSnapshot();
      }
      return current == null ? OctaneGateReportSnapshot.empty() : current;
    }
  }

  public int getRefreshSeconds() {
    return getSnapshot().getRefreshSeconds();
  }

  public boolean isAutoRefresh() {
    return getSnapshot().isBuilding();
  }

  public String getReportDataChecksum() {
    return artifactMetadata == null ? "" : artifactMetadata.getChecksum();
  }

  public String getTestManagementJson() {
    return OctaneReportJson.writeString(getSnapshot().getTestManagement().toMap());
  }

  public int getReportDataSchemaVersion() {
    return artifactMetadata == null ? 0 : artifactMetadata.getSchemaVersion();
  }

  public String getReportUrl() {
    if (run == null) {
      return URL_NAME + "/";
    }
    String rootUrl = Jenkins.get().getRootUrl();
    String buildPath = run.getUrl() + URL_NAME + "/";
    return rootUrl == null ? buildPath : rootUrl + buildPath;
  }

  public void doSnapshot(StaplerRequest2 request, StaplerResponse2 response) throws IOException {
    setJsonSecurityHeaders(request, response);
    checkReadPermission();
    String etag = currentEtag();
    if (etagMatches(request, etag)) {
      response.setStatus(304);
      return;
    }
    OctaneGateReportSnapshot current = currentSnapshot();
    OctaneGateReportSnapshot safeSnapshot =
        current == null ? OctaneGateReportSnapshot.empty() : current;
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("updatedAt", safeSnapshot.getUpdatedAt());
    payload.put("updatedAtText", safeSnapshot.getUpdatedAtText());
    payload.put("updatedAtDateTimeText", safeSnapshot.getUpdatedAtDateTimeText());
    payload.put("reconciledAt", safeSnapshot.getReconciledAt());
    payload.put("reconciledAtDateTimeText", safeSnapshot.getReconciledAtDateTimeText());
    payload.put("startedAt", safeSnapshot.getStartedAt());
    payload.put("building", safeSnapshot.isBuilding());
    payload.put("finalizing", safeSnapshot.isFinalizing());
    payload.put("timerActive", safeSnapshot.isTimerActive());
    payload.put("stateLabel", safeSnapshot.getStateLabel());
    payload.put("jobStateLabel", safeSnapshot.getJobStateLabel());
    payload.put("message", safeSnapshot.getMessage());
    payload.put("executionProgress", safeSnapshot.getExecutionProgress());
    payload.put("executionProgressText", safeSnapshot.getExecutionProgressText());
    payload.put("executedTestCount", safeSnapshot.getExecutedTestCount());
    payload.put("completionProgress", safeSnapshot.getCompletionProgress());
    payload.put("completionProgressText", safeSnapshot.getCompletionProgressText());
    payload.put("resolvedTestCount", safeSnapshot.getResolvedTestCount());
    payload.put(
        "executionStatusDistributionHtml", safeSnapshot.getExecutionStatusDistributionHtml());
    payload.put("passRateProgress", safeSnapshot.getPassRateProgress());
    payload.put("passRateProgressText", safeSnapshot.getPassRateProgressText());
    payload.put("passRateLabel", safeSnapshot.getPassRateLabel());
    payload.put("testMetricsHtml", safeSnapshot.getTestMetricsHtml());
    payload.put("testMetrics", safeSnapshot.getTestMetrics().toMap());
    payload.put("defectTrend", safeSnapshot.getDefectTrend().toMap());
    payload.put("testManagement", safeSnapshot.getTestManagement().toMap());
    payload.put("refreshSeconds", safeSnapshot.getRefreshSeconds());
    payload.put("timeoutSeconds", safeSnapshot.getTimeoutSeconds());
    payload.put("timeoutExtendedSeconds", safeSnapshot.getTimeoutExtendedSeconds());
    payload.put("extendedTime", safeSnapshot.isExtendedTime());
    payload.put("manualExitRequested", isManualExitRequested());
    payload.put("manualExitRequestedAtMillis", getManualExitRequestedAtMillis());
    payload.put("riskHeatMapEnabled", safeSnapshot.isRiskHeatMapEnabled());
    payload.put("riskHeatMapPopulated", safeSnapshot.getRiskHeatMap().isPopulatedData());
    payload.put("riskHeatMapHtml", safeSnapshot.getRiskHeatMapHtml());
    payload.put(
        "riskHeatMap",
        safeSnapshot.isClientRenderedReport()
            ? safeSnapshot.getRiskHeatMap().toSummaryMap()
            : safeSnapshot.getRiskHeatMap().toMap());
    payload.put("suiteAttributions", safeSnapshot.getSuiteAttributions());
    payload.put("testerDetails", safeSnapshot.getTesterDetails());
    payload.put("reportZoneDeferred", safeSnapshot.isClientRenderedReport());
    payload.put("reportDataUrl", "data");
    payload.put("reportDataChecksum", getReportDataChecksum());
    payload.put(
        "reportZoneHtml",
        safeSnapshot.isClientRenderedReport()
            ? ""
            : new OctaneReportZoneHtmlRenderer().renderZone(safeSnapshot));

    setDataHeaders(response, etag, safeSnapshot.isBuilding());
    response.setContentType("application/json;charset=UTF-8");
    OctaneReportJson.writeTo(response.getWriter(), payload);
  }

  public void doData(
      StaplerRequest2 request,
      StaplerResponse2 response,
      @QueryParameter String section,
      @QueryParameter int cursor,
      @QueryParameter int limit)
      throws IOException {
    setJsonSecurityHeaders(request, response);
    checkReadPermission();
    OctaneReportArtifactMetadata metadata = artifactMetadata;
    if (metadata == null || !metadata.isAvailable()) {
      response.sendError(404, "Octane report data is not available for this build.");
      return;
    }
    String etag = dataEtag(section, cursor, limit);
    if (etagMatches(request, etag)) {
      setDataHeaders(response, etag, metadata.isBuilding());
      response.setStatus(304);
      return;
    }
    ObjectNode body;
    OctaneReportArtifactStore store = new OctaneReportArtifactStore();
    if (section == null || section.isBlank()) {
      body = store.readIndex(run, metadata);
    } else {
      int sectionNumber;
      try {
        sectionNumber = Integer.parseInt(section);
      } catch (NumberFormatException e) {
        response.sendError(400, "The Octane report section must be numeric.");
        return;
      }
      if (sectionNumber < 0 || sectionNumber >= metadata.getSectionCount()) {
        response.sendError(404, "The requested Octane report section does not exist.");
        return;
      }
      body = store.readSectionPage(run, metadata, sectionNumber, cursor, limit <= 0 ? 80 : limit);
    }
    setDataHeaders(response, etag, metadata.isBuilding());
    response.setContentType("application/json;charset=UTF-8");
    OctaneReportJson.writeTo(response.getWriter(), body);
  }

  public void doScaleReportScript(StaplerRequest2 request, StaplerResponse2 response)
      throws IOException {
    setResponseSecurityHeaders(request, response);
    checkReadPermission();
    try (InputStream script =
        OctaneGateReportAction.class.getResourceAsStream("/js/octane-scale-report.js")) {
      if (script == null) {
        response.sendError(404, "The Octane scale report renderer is unavailable.");
        return;
      }
      response.setContentType("text/javascript;charset=UTF-8");
      response.setHeader("Cache-Control", "private, max-age=31536000, immutable");
      response.setHeader("X-Content-Type-Options", "nosniff");
      script.transferTo(response.getOutputStream());
    }
  }

  public void doTestManagementScript(StaplerRequest2 request, StaplerResponse2 response)
      throws IOException {
    setResponseSecurityHeaders(request, response);
    checkReadPermission();
    try (InputStream script =
        OctaneGateReportAction.class.getResourceAsStream("/js/octane-test-management.js")) {
      if (script == null) {
        response.sendError(404, "The Octane test-management renderer is unavailable.");
        return;
      }
      response.setContentType("text/javascript;charset=UTF-8");
      response.setHeader("Cache-Control", "private, max-age=31536000, immutable");
      response.setHeader("X-Content-Type-Options", "nosniff");
      script.transferTo(response.getOutputStream());
    }
  }

  @RequirePOST
  public HttpResponse doExitOctaneAndContinue() {
    requireRun().getACL().checkPermission(Run.UPDATE);
    Runnable callback = null;
    synchronized (manualExitLock()) {
      OctaneGateReportSnapshot current = currentSnapshot();
      if (current != null && current.isExtendedTime() && !manualExitRequested) {
        manualExitRequested = true;
        manualExitRequestedAtMillis = System.currentTimeMillis();
        manualExitLock().notifyAll();
        callback = manualExitCallback;
      }
    }
    saveRun();
    if (callback != null) {
      callback.run();
    }
    return HttpResponses.redirectToDot();
  }

  public void setManualExitCallback(Runnable callback) {
    synchronized (manualExitLock()) {
      manualExitCallback = callback;
    }
  }

  public void clearManualExitCallback(Runnable callback) {
    synchronized (manualExitLock()) {
      if (manualExitCallback == callback) {
        manualExitCallback = null;
      }
    }
  }

  public void setRefreshCallback(RefreshCallback callback) {
    synchronized (refreshLock()) {
      refreshCallback = callback;
    }
  }

  public void clearRefreshCallback(RefreshCallback callback) {
    synchronized (refreshLock()) {
      if (refreshCallback == callback) {
        refreshCallback = null;
      }
    }
  }

  public RefreshResult refreshIfStale(Duration threshold, Instant now) throws Exception {
    return refreshReport(threshold, now, false);
  }

  public RefreshResult refreshForEmail(Duration threshold, Instant now) throws Exception {
    return refreshReport(threshold, now, true);
  }

  private RefreshResult refreshReport(
      Duration threshold, Instant now, boolean requireReportSections) throws Exception {
    Duration effectiveThreshold = nonNegative(threshold);
    Instant effectiveNow = now == null ? Instant.now() : now;
    OctaneGateReportSnapshot current = getSnapshot();
    Duration age = snapshotAge(current, effectiveNow, effectiveThreshold);
    if (current.isFinalizing()) {
      return new RefreshResult(RefreshStatus.FRESH, age);
    }
    if (!current.isBuilding()) {
      return new RefreshResult(RefreshStatus.NOT_BUILDING, age);
    }
    if ((!requireReportSections || current.hasReportSections())
        && age.compareTo(effectiveThreshold) <= 0) {
      return new RefreshResult(RefreshStatus.FRESH, age);
    }

    RefreshCallback callback;
    synchronized (refreshLock()) {
      callback = refreshCallback;
    }
    if (callback == null) {
      throw new AbortException(
          "Octane progress data could not be refreshed because the active gate poller is "
              + "unavailable.");
    }
    boolean started = callback.refreshAndWait();
    return new RefreshResult(started ? RefreshStatus.REFRESHED : RefreshStatus.JOINED, age);
  }

  @Override
  public synchronized boolean isManualExitRequested() {
    return manualExitRequested;
  }

  public long getManualExitRequestedAtMillis() {
    return manualExitRequestedAtMillis;
  }

  public boolean isManualExitPending() {
    OctaneGateReportSnapshot current = currentSnapshot();
    return manualExitRequested && current != null && current.isBuilding();
  }

  public boolean isExtendedExitAvailable() {
    OctaneGateReportSnapshot current = currentSnapshot();
    return !manualExitRequested
        && current != null
        && current.isBuilding()
        && current.isExtendedTime();
  }

  public boolean isExtendedExitControlVisible() {
    return isExtendedExitAvailable() || isManualExitPending();
  }

  @Override
  public boolean awaitNextPollOrManualExit(Duration duration) throws InterruptedException {
    long timeoutMillis = Math.max(0L, duration.toMillis());
    long deadline = System.currentTimeMillis() + timeoutMillis;
    synchronized (manualExitLock()) {
      while (!manualExitRequested && timeoutMillis > 0L) {
        manualExitLock().wait(timeoutMillis);
        timeoutMillis = deadline - System.currentTimeMillis();
      }
      return manualExitRequested;
    }
  }

  private boolean saveRun() {
    if (run == null) {
      return false;
    }
    try {
      run.save();
      return true;
    } catch (IOException ignored) {
      // Jenkins can still render the in-memory report for the current build.
      return false;
    }
  }

  private void publishSnapshot(OctaneGateReportSnapshot nextSnapshot) {
    OctaneReportArtifactMetadata previousMetadata = artifactMetadata;
    OctaneGateReportSnapshot publishedSnapshot =
        nextSnapshot == null ? OctaneGateReportSnapshot.empty() : nextSnapshot;
    if (run == null) {
      synchronized (snapshotLock()) {
        snapshotCache = publishedSnapshot;
        snapshot = null;
        snapshotLock().notifyAll();
      }
      return;
    }
    try {
      OctaneReportArtifactMetadata nextMetadata =
          new OctaneReportArtifactStore().publish(run, publishedSnapshot);
      synchronized (snapshotLock()) {
        snapshotCache = publishedSnapshot;
        snapshot = null;
        artifactMetadata = nextMetadata;
      }
      boolean saved = saveRun();
      if (saved
          && previousMetadata != null
          && previousMetadata.isAvailable()
          && !previousMetadata.getChecksum().equals(nextMetadata.getChecksum())) {
        new OctaneReportArtifactStore().deleteGeneration(run, previousMetadata);
      }
      if (saved && nextMetadata.isClientRendered()) {
        synchronized (snapshotLock()) {
          snapshotCache = null;
        }
      }
    } catch (IOException ignored) {
      // Keep the in-memory report and the last complete artifact generation.
      synchronized (snapshotLock()) {
        snapshotCache = publishedSnapshot;
        snapshot = null;
      }
      saveRun();
    } finally {
      synchronized (snapshotLock()) {
        snapshotLock().notifyAll();
      }
    }
  }

  private OctaneGateReportSnapshot currentSnapshot() {
    synchronized (snapshotLock()) {
      if (snapshotCache != null) {
        return snapshotCache;
      }
      if (snapshot != null) {
        return snapshot;
      }
      if (run == null || artifactMetadata == null || !artifactMetadata.isAvailable()) {
        return null;
      }
      try {
        OctaneGateReportSnapshot loaded =
            new OctaneReportArtifactStore().loadSnapshot(run, artifactMetadata);
        if (!artifactMetadata.isClientRendered()) {
          snapshotCache = loaded;
        }
        return loaded;
      } catch (IOException ignored) {
        snapshotCache = null;
      }
      return null;
    }
  }

  private void checkReadPermission() {
    requireRun().getACL().checkPermission(Item.READ);
  }

  private Run<?, ?> requireRun() {
    Run<?, ?> currentRun = run;
    if (currentRun == null) {
      Jenkins.get().checkPermission(Jenkins.ADMINISTER);
      throw new IllegalStateException("Octane report is not attached to a Jenkins build.");
    }
    return currentRun;
  }

  private String currentEtag() {
    String checksum = getReportDataChecksum();
    if (checksum.isBlank()) {
      checksum = getSnapshot().getUpdatedAt();
    }
    return '"' + checksum + (manualExitRequested ? "-exit" : "") + '"';
  }

  private String dataEtag(String section, int cursor, int limit) {
    String suffix =
        section == null || section.isBlank()
            ? "-index"
            : "-section-" + section + "-" + Math.max(0, cursor) + "-" + Math.max(1, limit);
    return '"' + getReportDataChecksum() + suffix + '"';
  }

  private boolean etagMatches(StaplerRequest2 request, String etag) {
    String supplied = request == null ? null : request.getHeader("If-None-Match");
    return supplied != null && supplied.equals(etag);
  }

  private void setDataHeaders(StaplerResponse2 response, String etag, boolean building) {
    response.setHeader("ETag", etag);
    response.setHeader("Cache-Control", "private, no-cache" + (building ? ", no-store" : ""));
    response.setHeader("X-Content-Type-Options", "nosniff");
  }

  static void setJsonSecurityHeaders(StaplerRequest2 request, StaplerResponse2 response) {
    response.setContentType("application/json;charset=UTF-8");
    setResponseSecurityHeaders(request, response);
    response.setHeader(
        "Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'; sandbox");
  }

  private static void setResponseSecurityHeaders(
      StaplerRequest2 request, StaplerResponse2 response) {
    response.setHeader("X-Content-Type-Options", "nosniff");
    if (request != null && request.isSecure()) {
      response.setHeader("Strict-Transport-Security", OctaneReportSecurityHeaders.HSTS_POLICY);
    }
  }

  private void configureTimers(GateRequest request) {
    refreshSeconds = request.getPollIntervalSeconds();
    timeoutSeconds = Math.max(1, request.getTimeoutMinutes()) * 60;
    timeoutExtendedSeconds = Math.max(0, request.getTimeoutMinutesExtended()) * 60;
    basePassrateFigure = request.getBasePassrateFigure();
    baseExecutionFigure = request.getBaseExecutionFigure();
    automatedTestingTarget = request.getAutomatedTestingTarget();
    limitForMetricRunsInSuite = request.getLimitForMetricRunsInSuite();
    definedScope = List.copyOf(request.getDefinedScope());
    criticalGraphsTitle = request.getCriticalGraphsTitle();
    regressionGraphsTitle = request.getRegressionGraphsTitle();
    startedAt = Instant.now().toString();
    manualExitRequested = false;
    manualExitRequestedAtMillis = 0L;
  }

  private Object manualExitLock() {
    Object current = manualExitLock;
    if (current == null) {
      synchronized (this) {
        current = manualExitLock;
        if (current == null) {
          current = new Object();
          manualExitLock = current;
        }
      }
    }
    return current;
  }

  private Object refreshLock() {
    Object current = refreshLock;
    if (current == null) {
      synchronized (this) {
        current = refreshLock;
        if (current == null) {
          current = new Object();
          refreshLock = current;
        }
      }
    }
    return current;
  }

  private Object snapshotLock() {
    Object current = snapshotLock;
    if (current == null) {
      synchronized (this) {
        current = snapshotLock;
        if (current == null) {
          current = new Object();
          snapshotLock = current;
        }
      }
    }
    return current;
  }

  private void readObject(ObjectInputStream input) throws IOException, ClassNotFoundException {
    input.defaultReadObject();
    snapshotCache = null;
    manualExitLock = new Object();
    manualExitCallback = null;
    refreshLock = new Object();
    refreshCallback = null;
    snapshotLock = new Object();
  }

  private Duration nonNegative(Duration duration) {
    if (duration == null || duration.isNegative()) {
      return Duration.ZERO;
    }
    return duration;
  }

  private Duration snapshotAge(
      OctaneGateReportSnapshot current, Instant now, Duration effectiveThreshold) {
    try {
      Duration age = Duration.between(Instant.parse(current.getUpdatedAt()), now);
      return age.isNegative() ? Duration.ZERO : age;
    } catch (RuntimeException ignored) {
      return effectiveThreshold.plusSeconds(1L);
    }
  }

  @FunctionalInterface
  public interface RefreshCallback {
    boolean refreshAndWait() throws Exception;
  }

  public enum RefreshStatus {
    FRESH,
    REFRESHED,
    JOINED,
    NOT_BUILDING
  }

  public record RefreshResult(RefreshStatus status, Duration age) {}

  private OctaneGateReportSnapshot attributedSnapshot(
      OctaneGateReportState state, String message, GateResult result, StatusClassifier classifier) {
    OctaneGateReportSnapshot previous = currentSnapshot();
    Map<String, String> persisted = previous == null ? Map.of() : previous.getSuiteAttributions();
    Map<String, String> merged = OctaneSuiteAttributions.mergeFirstValid(persisted, result);
    GateResult attributed = OctaneSuiteAttributions.apply(result, merged);
    return OctaneGateReportSnapshot.fromResult(
            state,
            message,
            attributed,
            classifier,
            refreshSeconds,
            timeoutSeconds,
            timeoutExtendedSeconds,
            startedAt,
            effectiveMetricRunsInSuiteLimit())
        .withSuiteAttributions(merged);
  }

  private OctaneGateReportSnapshot withPreviousCycleMetrics(OctaneGateReportSnapshot current) {
    return current
        .withGraphTitles(regressionGraphsTitle, criticalGraphsTitle)
        .withDefinedScope(definedScope == null ? List.of() : definedScope)
        .withTesterThresholds(basePassrateFigure, baseExecutionFigure)
        .withTestMetrics(
            current.getTestMetrics().withAutomatedTestingTarget(automatedTestingTarget))
        .withCalculatedTestMetrics(previousCompletedSnapshot());
  }

  private int effectiveMetricRunsInSuiteLimit() {
    return limitForMetricRunsInSuite <= 0
        ? GateRequest.DEFAULT_LIMIT_FOR_METRIC_RUNS_IN_SUITE
        : limitForMetricRunsInSuite;
  }

  private OctaneGateReportSnapshot withLiveReportData(OctaneGateReportSnapshot current) {
    OctaneDefectTrend trend = current.getDefectTrend();
    OctaneTestManagementAnalytics testManagement = current.getTestManagement();
    OctaneGateReportSnapshot previous = currentSnapshot();
    if (previous != null) {
      boolean retainPreviousHeatMap =
          !current.isBuilding()
              && !current.getRiskHeatMap().isPopulatedData()
              && previous.getRiskHeatMap().isPopulatedData();
      if (retainPreviousHeatMap) {
        current = current.withRiskHeatMap(previous.getRiskHeatMap());
        trend = previous.getDefectTrend();
      } else {
        trend =
            previous
                .getDefectTrend()
                .append(
                    current.getUpdatedAt(),
                    current.getTestManagement().getTotalDefects(),
                    current.getTestManagement().getClosedDefects(),
                    current.getExecutedTestCount());
      }
      testManagement = previous.getTestManagement().appendLatest(testManagement);
    }
    return withPreviousCycleMetrics(
        current.withDefectTrend(trend).withTestManagement(testManagement));
  }

  private OctaneGateReportSnapshot previousCompletedSnapshot() {
    if (run == null) {
      return null;
    }
    Run<?, ?> previous = run.getPreviousCompletedBuild();
    while (previous != null) {
      OctaneGateReportAction action = previous.getAction(OctaneGateReportAction.class);
      OctaneGateReportSnapshot previousSnapshot = action == null ? null : action.getSnapshot();
      if (previousSnapshot != null && !previousSnapshot.isBuilding()) {
        return previousSnapshot;
      }
      previous = previous.getPreviousCompletedBuild();
    }
    return null;
  }

  private String defaultMessage(String message) {
    if (message == null || message.isBlank()) {
      return "ALM Octane suite gate stopped before a result was available.";
    }
    return message;
  }
}
