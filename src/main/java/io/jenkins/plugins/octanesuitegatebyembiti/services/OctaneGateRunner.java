package io.jenkins.plugins.octanesuitegatebyembiti.services;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import hudson.AbortException;
import hudson.model.TaskListener;
import hudson.security.ACL;
import io.jenkins.plugins.octanesuitegatebyembiti.configs.OctaneServerUrl;
import io.jenkins.plugins.octanesuitegatebyembiti.entities.DefectRecord;
import io.jenkins.plugins.octanesuitegatebyembiti.entities.RunRecord;
import io.jenkins.plugins.octanesuitegatebyembiti.listeners.OctaneGateLogListener;
import io.jenkins.plugins.octanesuitegatebyembiti.listeners.OctaneGateReportPublisher;
import io.jenkins.plugins.octanesuitegatebyembiti.models.CriteriaEvaluation;
import io.jenkins.plugins.octanesuitegatebyembiti.models.DefectCriteriaMetrics;
import io.jenkins.plugins.octanesuitegatebyembiti.models.GateMetrics;
import io.jenkins.plugins.octanesuitegatebyembiti.models.GateRequest;
import io.jenkins.plugins.octanesuitegatebyembiti.models.GateResult;
import io.jenkins.plugins.octanesuitegatebyembiti.models.GateScopeResult;
import io.jenkins.plugins.octanesuitegatebyembiti.models.MetricsContext;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneDefectGroup;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneDefectLedger;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneDefectSeveritySummary;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneGateReportState;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneGateScope;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneRiskHeatMap;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneRiskHeatMapBuilder;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneSuiteScopedDefects;
import io.jenkins.plugins.octanesuitegatebyembiti.models.StatusClassifier;
import io.jenkins.plugins.octanesuitegatebyembiti.models.SuiteRunSelector;
import io.jenkins.plugins.octanesuitegatebyembiti.repositories.OctaneClient;
import io.jenkins.plugins.octanesuitegatebyembiti.utils.Util;
import java.io.IOException;
import java.io.Serializable;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;

public class OctaneGateRunner {
  private static final Pattern OCTANE_NUMERIC_ID = Pattern.compile("[0-9]{1,18}");
  private static final String GLOBAL_API_CREDENTIALS_ID = "octane-api-client";
  private static final int MAX_SCOPES = 100;
  private static final int MAX_DEFECT_GROUPS = 100;
  private final Clock clock;
  private final OctaneGateLogListener logListener;

  public OctaneGateRunner() {
    this(Clock.systemUTC(), new OctaneGateLogListener());
  }

  OctaneGateRunner(Clock clock, OctaneGateLogListener logListener) {
    this.clock = clock;
    this.logListener = logListener;
  }

  public GateResult run(GateRequest request, TaskListener listener)
      throws IOException, InterruptedException {
    return run(request, listener, new OctaneGateReportPublisher() {});
  }

  public GateResult run(
      GateRequest request, TaskListener listener, OctaneGateReportPublisher reportPublisher)
      throws IOException, InterruptedException {
    PollingState state = new PollingState(clock.instant());
    try (PollingSession session = openSession(request, listener, reportPublisher, state)) {
      while (true) {
        PollOutcome outcome = session.pollOnce();
        if (outcome.isComplete()) {
          return outcome.getResult();
        }
        reportPublisher.awaitNextPollOrManualExit(outcome.getNextDelay());
      }
    }
  }

  public PollingSession openSession(
      GateRequest request,
      TaskListener listener,
      OctaneGateReportPublisher reportPublisher,
      PollingState state)
      throws IOException, InterruptedException {
    return new PollingSession(request, listener, reportPublisher, state);
  }

  public final class PollingSession implements AutoCloseable {
    private final GateRequest request;
    private final TaskListener listener;
    private final OctaneGateReportPublisher reportPublisher;
    private final PollingState state;
    private final String sharedSpaceId;
    private final String workspaceId;
    private final CriteriaExpression criteria;
    private final StatusClassifier classifier;
    private final Instant primaryDeadline;
    private final Instant extendedDeadline;
    private final boolean extendedTimeoutConfigured;
    private final SuiteRunPool regressionSuitePool;
    private final Map<String, SuiteRunPool> suiteScopePools;
    private final boolean regressionSelectionEnabled;
    private final OctaneClient client;

    private PollingSession(
        GateRequest request,
        TaskListener listener,
        OctaneGateReportPublisher reportPublisher,
        PollingState state)
        throws IOException, InterruptedException {
      validateRequest(request);
      this.request = request;
      this.listener = listener;
      this.reportPublisher = reportPublisher;
      this.state = state == null ? new PollingState(clock.instant()) : state;
      if (!this.state.isWaitingPublished()) {
        logListener.logConfiguredCriteria(listener, request.getCriteria());
      }
      try {
        criteria = CriteriaValidator.validate(request);
      } catch (CriteriaException exception) {
        logListener.logCriteriaError(listener, exception.getMessage());
        throw new AbortException(exception.getMessage());
      }
      if (!this.state.isWaitingPublished()) {
        logListener.logCriteriaVerified(listener);
      }
      sharedSpaceId = requiredWorkspaceValue("Shared space ID", request.getSharedSpaceId());
      workspaceId = requiredWorkspaceValue("Workspace ID", request.getWorkspaceId());
      classifier = request.createStatusClassifier();
      primaryDeadline =
          this.state.getStartedAt().plus(Duration.ofMinutes(request.getTimeoutMinutes()));
      extendedDeadline =
          primaryDeadline.plus(Duration.ofMinutes(request.getTimeoutMinutesExtended()));
      extendedTimeoutConfigured = request.getTimeoutMinutesExtended() > 0;
      regressionSuitePool = new SuiteRunPool("Regressions", request.getSuiteRunSelector());
      suiteScopePools = new LinkedHashMap<>();
      for (OctaneGateScope scope : request.getScopes()) {
        if (scope.isSuiteRunScope()) {
          suiteScopePools.put(
              scope.getName(),
              new SuiteRunPool(displayScopeName(scope.getName()), scope.getSuiteRunSelector()));
        }
      }
      regressionSelectionEnabled = regressionSelectionEnabled(request);
      if (!this.state.isWaitingPublished()) {
        logListener.logLookupContext(listener, sharedSpaceId, workspaceId);
      }
      client = createClient(request);
      boolean sessionReady = false;
      try {
        client.authenticate();
        preflightSuitePools();
        sessionReady = true;
      } finally {
        if (!sessionReady) {
          try {
            client.close();
          } catch (IOException ignored) {
            // Preserve the authentication or preflight failure that prevented the session.
          }
        }
      }
      List<String> suiteRunIds = currentRegressionSuiteRunIds();
      if (!this.state.isWaitingPublished()) {
        if (!regressionSelectionEnabled) {
          logListener.logRegressionEvaluationSkipped(listener);
        }
        logListener.logWaiting(listener, request, suiteRunIds, currentScopeSuiteRunIds());
        reportPublisher.onWaiting(request, suiteRunIds);
        this.state.setWaitingPublished(true);
      }
    }

    public PollOutcome pollOnce() throws IOException, InterruptedException {
      logManualExitFinalizingIfNeeded();
      PollOutcome stoppingOutcome = finishAtExistingStopBoundary(clock.instant());
      if (stoppingOutcome != null) {
        return stoppingOutcome;
      }

      CurrentSuiteRuns currentSuiteRuns = refreshSuitePools();
      GateResult result =
          poll(
              client,
              request,
              currentSuiteRuns.regressionSuiteRuns,
              currentSuiteRuns.scopeSuiteRuns,
              regressionSelectionEnabled,
              currentSuiteRuns.awaitingSuiteDiscovery,
              sharedSpaceId,
              workspaceId,
              criteria,
              classifier,
              listener,
              state.getDefectLedger());
      logAppliedCriteriaIfChanged(result);
      logListener.logPollResult(listener, result);
      publishPollResult(reportPublisher, result, classifier, state.isExtendedTimeActive());

      PollOutcome outcome = finishWithoutExtendedTimeout(result);
      if (outcome != null) {
        return outcome;
      }

      Instant now = clock.instant();
      outcome = finishPrimaryTimeoutWhenRequired(now);
      if (outcome != null) {
        return outcome;
      }
      transitionAtPrimaryDeadline(result, now);

      outcome = finishExtendedTimeWhenRequired(now);
      if (outcome != null) {
        return outcome;
      }

      Duration delay =
          waitDuration(request, state.isExtendedTimeActive() ? extendedDeadline : primaryDeadline);
      return PollOutcome.continueAfter(delay);
    }

    private PollOutcome finishAtExistingStopBoundary(Instant now)
        throws IOException, InterruptedException, GateFailedException {
      if (state.isExtendedTimeActive()) {
        boolean manualExitRequested = reportPublisher.isManualExitRequested();
        if (manualExitRequested || !now.isBefore(extendedDeadline)) {
          logManualExitFinalizingIfNeeded();
          GateResult finalResult =
              reconcileFinalResult(
                  manualExitRequested
                      ? "Exit Octane and Continue requested. Reconciling final ALM Octane data."
                      : "Extended timeout elapsed. Reconciling final ALM Octane data.");
          return PollOutcome.complete(
              finishExtendedGate(
                  request,
                  listener,
                  reportPublisher,
                  finalResult,
                  classifier,
                  manualExitRequested));
        }
      }
      if (!state.isExtendedTimeActive()
          && !extendedTimeoutConfigured
          && !now.isBefore(primaryDeadline)) {
        return finishPrimaryTimeout();
      }
      return null;
    }

    private PollOutcome finishWithoutExtendedTimeout(GateResult result)
        throws IOException, InterruptedException, GateFailedException {
      if (!isReadyToFinalizeWithoutExtendedTimeout(result, extendedTimeoutConfigured)) {
        return null;
      }

      GateResult finalResult =
          reconcileFinalResult(
              "Execution reached a terminal state. Reconciling final ALM Octane data.");
      if (finalResult.isPassed()) {
        return PollOutcome.complete(passGate(listener, reportPublisher, finalResult, classifier));
      }
      if (finalResult.isTerminal()) {
        String message = "ALM Octane suite gate failed.";
        reportPublisher.onFinal(failureState(request), message, finalResult, classifier);
        throw new GateFailedException(message, finalResult);
      }
      publishPollResult(reportPublisher, finalResult, classifier, false);
      return null;
    }

    private PollOutcome finishPrimaryTimeoutWhenRequired(Instant now)
        throws IOException, InterruptedException, GateFailedException {
      if (state.isExtendedTimeActive()
          || extendedTimeoutConfigured
          || now.isBefore(primaryDeadline)) {
        return null;
      }
      return finishPrimaryTimeout();
    }

    private PollOutcome finishPrimaryTimeout()
        throws IOException, InterruptedException, GateFailedException {
      GateResult finalResult =
          reconcileFinalResult("Primary timeout elapsed. Reconciling final ALM Octane data.");
      if (finalResult.isPassed()) {
        return PollOutcome.complete(passGate(listener, reportPublisher, finalResult, classifier));
      }
      String message = "Timed out waiting for ALM Octane suite gate.";
      reportPublisher.onFinal(timeoutState(request), message, finalResult, classifier);
      throw new GateFailedException(message, finalResult);
    }

    private void transitionAtPrimaryDeadline(GateResult result, Instant now) {
      if (state.isExtendedTimeActive() || now.isBefore(primaryDeadline)) {
        return;
      }
      state.setExtendedTimeActive(true);
      logListener.logExtendedTimeStarted(listener, request.getTimeoutMinutesExtended());
      reportPublisher.onExtendedTime(result, classifier);
    }

    private PollOutcome finishExtendedTimeWhenRequired(Instant now)
        throws IOException, InterruptedException, GateFailedException {
      if (!state.isExtendedTimeActive()) {
        return null;
      }
      boolean manualExitRequested = reportPublisher.isManualExitRequested();
      if (!manualExitRequested && now.isBefore(extendedDeadline)) {
        return null;
      }
      logManualExitFinalizingIfNeeded();
      GateResult finalResult =
          reconcileFinalResult(
              manualExitRequested
                  ? "Exit Octane and Continue requested. Reconciling final ALM Octane data."
                  : "Extended timeout elapsed. Reconciling final ALM Octane data.");
      return PollOutcome.complete(
          finishExtendedGate(
              request, listener, reportPublisher, finalResult, classifier, manualExitRequested));
    }

    private void logManualExitFinalizingIfNeeded() {
      if (state.isExtendedTimeActive()
          && reportPublisher.isManualExitRequested()
          && state.markManualExitFinalizingLogged()) {
        logListener.logManualExitRequested(listener);
      }
    }

    private GateResult reconcileFinalResult(String message)
        throws IOException, InterruptedException {
      reportPublisher.onFinalizing(message);
      logListener.logFinalRefresh(listener);
      CurrentSuiteRuns currentSuiteRuns = refreshSuitePools();
      GateResult finalResult =
          poll(
              client,
              request,
              currentSuiteRuns.regressionSuiteRuns,
              currentSuiteRuns.scopeSuiteRuns,
              regressionSelectionEnabled,
              currentSuiteRuns.awaitingSuiteDiscovery,
              sharedSpaceId,
              workspaceId,
              criteria,
              classifier,
              listener,
              state.getDefectLedger());
      logAppliedCriteriaIfChanged(finalResult);
      logListener.logPollResult(listener, finalResult);
      logListener.logFinalReconciliationCompleted(listener, finalResult.getPolledAt());
      return finalResult;
    }

    private void logAppliedCriteriaIfChanged(GateResult result) {
      if (state.shouldLogAppliedCriteria(result.getCriteria())) {
        logListener.logActiveAppliedCriteria(listener, result.getCriteria());
      }
    }

    private void preflightSuitePools() throws IOException, InterruptedException {
      SuiteRunDiscoveryCycle discoveryCycle = new SuiteRunDiscoveryCycle();
      if (regressionSelectionEnabled) {
        regressionSuitePool.preflight(discoveryCycle);
      }
      for (SuiteRunPool pool : suiteScopePools.values()) {
        pool.preflight(discoveryCycle);
      }
    }

    private CurrentSuiteRuns refreshSuitePools() throws IOException, InterruptedException {
      SuiteRunDiscoveryCycle discoveryCycle = new SuiteRunDiscoveryCycle();
      Map<String, List<RunRecord>> regressionSuiteRuns =
          regressionSelectionEnabled ? regressionSuitePool.refresh(discoveryCycle) : Map.of();
      Map<String, Map<String, List<RunRecord>>> scopeSuiteRuns = new LinkedHashMap<>();
      for (Map.Entry<String, SuiteRunPool> entry : suiteScopePools.entrySet()) {
        scopeSuiteRuns.put(entry.getKey(), entry.getValue().refresh(discoveryCycle));
      }

      Set<String> criticalIds = currentCriticalSuiteRunIds(scopeSuiteRuns);
      Map<String, List<RunRecord>> effectiveRegressionRuns = new LinkedHashMap<>();
      if (regressionSelectionEnabled) {
        for (Map.Entry<String, List<RunRecord>> entry : regressionSuiteRuns.entrySet()) {
          if (!criticalIds.contains(entry.getKey())) {
            effectiveRegressionRuns.put(entry.getKey(), entry.getValue());
          }
        }
      }

      boolean configuredSuiteBucket =
          regressionSelectionEnabled && regressionSuitePool.isConfigured();
      boolean activeSuiteBucket = !effectiveRegressionRuns.isEmpty();
      for (Map.Entry<String, SuiteRunPool> entry : suiteScopePools.entrySet()) {
        SuiteRunPool pool = entry.getValue();
        configuredSuiteBucket |= pool.isConfigured();
        Map<String, List<RunRecord>> currentRuns = scopeSuiteRuns.get(entry.getKey());
        activeSuiteBucket |= currentRuns != null && !currentRuns.isEmpty();
      }
      boolean awaiting = configuredSuiteBucket && !activeSuiteBucket;
      return new CurrentSuiteRuns(effectiveRegressionRuns, scopeSuiteRuns, awaiting);
    }

    private List<String> currentRegressionSuiteRunIds() {
      Set<String> criticalIds = currentCriticalSuiteRunIds(Map.of());
      if (!regressionSelectionEnabled) {
        return List.of();
      }
      return regressionSuitePool.getActiveIds().stream()
          .filter(id -> !criticalIds.contains(id))
          .toList();
    }

    private Map<String, List<String>> currentScopeSuiteRunIds() {
      Map<String, List<String>> values = new LinkedHashMap<>();
      for (Map.Entry<String, SuiteRunPool> entry : suiteScopePools.entrySet()) {
        values.put(entry.getKey(), entry.getValue().getActiveIds());
      }
      return values;
    }

    private Set<String> currentCriticalSuiteRunIds(
        Map<String, Map<String, List<RunRecord>>> refreshedScopeRuns) {
      Set<String> ids = new LinkedHashSet<>();
      for (OctaneGateScope scope : request.getScopes()) {
        if (!"critical".equalsIgnoreCase(scope.getName()) || !scope.isSuiteRunScope()) {
          continue;
        }
        Map<String, List<RunRecord>> refreshed = refreshedScopeRuns.get(scope.getName());
        if (refreshed != null) {
          ids.addAll(refreshed.keySet());
        } else {
          SuiteRunPool pool = suiteScopePools.get(scope.getName());
          if (pool != null) {
            ids.addAll(pool.getActiveIds());
          }
        }
      }
      return ids;
    }

    private final class SuiteRunPool {
      private final String label;
      private final SuiteRunSelector selector;
      private final LinkedHashSet<String> activeIds = new LinkedHashSet<>();
      private boolean initialized;

      private SuiteRunPool(String label, SuiteRunSelector selector) {
        this.label = label;
        this.selector = selector;
      }

      private void preflight(SuiteRunDiscoveryCycle discoveryCycle)
          throws IOException, InterruptedException {
        if (!selector.isConfigured()) {
          initialized = true;
          return;
        }
        List<String> candidates = candidateIds(discoveryCycle);
        Map<String, List<RunRecord>> available;
        if (selector.isDynamic()) {
          logListener.logDynamicSuiteSelector(
              listener, label, selector.getReleaseName(), selector.getSprintName());
          available = client.fetchAvailableSuiteChildRuns(sharedSpaceId, workspaceId, candidates);
        } else {
          available = client.fetchSuiteChildRuns(sharedSpaceId, workspaceId, candidates);
        }
        reconcile(available.keySet());
        initialized = true;
        if (selector.isDynamic() && activeIds.isEmpty()) {
          logListener.logNoDynamicSuiteRuns(
              listener, label, selector.getReleaseName(), selector.getSprintName());
        }
      }

      private Map<String, List<RunRecord>> refresh(SuiteRunDiscoveryCycle discoveryCycle)
          throws IOException, InterruptedException {
        if (!selector.isConfigured()) {
          return Map.of();
        }
        List<String> candidates = candidateIds(discoveryCycle);
        Map<String, List<RunRecord>> available =
            client.fetchAvailableSuiteChildRuns(sharedSpaceId, workspaceId, candidates);
        reconcile(available.keySet());
        return available;
      }

      private List<String> candidateIds(SuiteRunDiscoveryCycle discoveryCycle)
          throws IOException, InterruptedException {
        List<String> ids =
            discoveryCycle.resolve(
                selector,
                dynamicSelector ->
                    client.fetchSuiteRunIdsByReleaseAndSprint(
                        sharedSpaceId,
                        workspaceId,
                        dynamicSelector.getReleaseName(),
                        dynamicSelector.getSprintName()));
        if (ids.size() > GateRequest.MAX_SUITE_RUN_IDS) {
          throw new AbortException(
              label
                  + " dynamic selection returned more than "
                  + GateRequest.MAX_SUITE_RUN_IDS
                  + " suite runs.");
        }
        return ids;
      }

      private void reconcile(Set<String> availableIds) {
        LinkedHashSet<String> available = new LinkedHashSet<>(availableIds);
        if (initialized) {
          List<String> added = available.stream().filter(id -> !activeIds.contains(id)).toList();
          List<String> removed = activeIds.stream().filter(id -> !available.contains(id)).toList();
          if (!added.isEmpty()) {
            logListener.logSuiteRunsAdded(listener, label, added);
          }
          if (!removed.isEmpty()) {
            logListener.logSuiteRunsRemoved(listener, label, removed);
          }
        }
        activeIds.clear();
        activeIds.addAll(available);
      }

      private boolean isConfigured() {
        return selector.isConfigured();
      }

      private List<String> getActiveIds() {
        return List.copyOf(activeIds);
      }
    }

    private record CurrentSuiteRuns(
        Map<String, List<RunRecord>> regressionSuiteRuns,
        Map<String, Map<String, List<RunRecord>>> scopeSuiteRuns,
        boolean awaitingSuiteDiscovery) {}

    @Override
    public void close() throws IOException {
      client.close();
    }
  }

  static boolean isReadyToFinalizeWithoutExtendedTimeout(
      GateResult result, boolean extendedTimeoutConfigured) {
    return !extendedTimeoutConfigured && result != null && result.isTerminal();
  }

  public static final class PollingState implements Serializable {
    private static final long serialVersionUID = 1L;

    private final Instant startedAt;
    private final OctaneDefectLedger defectLedger = new OctaneDefectLedger();
    private boolean extendedTimeActive;
    private boolean manualExitFinalizingLogged;
    private boolean waitingPublished;
    private String lastLoggedCriteria;

    public PollingState(Instant startedAt) {
      this(startedAt, false);
    }

    public PollingState(Instant startedAt, boolean waitingPublished) {
      this.startedAt = startedAt == null ? Instant.now() : startedAt;
      this.waitingPublished = waitingPublished;
    }

    public Instant getStartedAt() {
      return startedAt;
    }

    public OctaneDefectLedger getDefectLedger() {
      return defectLedger;
    }

    public boolean isExtendedTimeActive() {
      return extendedTimeActive;
    }

    private void setExtendedTimeActive(boolean extendedTimeActive) {
      this.extendedTimeActive = extendedTimeActive;
    }

    private synchronized boolean markManualExitFinalizingLogged() {
      if (manualExitFinalizingLogged) {
        return false;
      }
      manualExitFinalizingLogged = true;
      return true;
    }

    public boolean isWaitingPublished() {
      return waitingPublished;
    }

    private void setWaitingPublished(boolean waitingPublished) {
      this.waitingPublished = waitingPublished;
    }

    synchronized boolean shouldLogAppliedCriteria(String activeCriteria) {
      String current = Util.trimToEmpty(activeCriteria);
      if (Objects.equals(lastLoggedCriteria, current)) {
        return false;
      }
      lastLoggedCriteria = current;
      return true;
    }
  }

  public static final class PollOutcome {
    private final GateResult result;
    private final Duration nextDelay;
    private final boolean complete;

    private PollOutcome(GateResult result, Duration nextDelay, boolean complete) {
      this.result = result;
      this.nextDelay = nextDelay;
      this.complete = complete;
    }

    private static PollOutcome complete(GateResult result) {
      return new PollOutcome(result, Duration.ZERO, true);
    }

    private static PollOutcome continueAfter(Duration delay) {
      return new PollOutcome(null, delay == null ? Duration.ZERO : delay, false);
    }

    public GateResult getResult() {
      return result;
    }

    public Duration getNextDelay() {
      return nextDelay;
    }

    public boolean isComplete() {
      return complete;
    }
  }

  private void publishPollResult(
      OctaneGateReportPublisher reportPublisher,
      GateResult result,
      StatusClassifier classifier,
      boolean extendedTimeActive) {
    if (extendedTimeActive) {
      reportPublisher.onExtendedTime(result, classifier);
    } else {
      reportPublisher.onPoll(result, classifier);
    }
  }

  private GateResult passGate(
      TaskListener listener,
      OctaneGateReportPublisher reportPublisher,
      GateResult result,
      StatusClassifier classifier) {
    logListener.logPassed(listener);
    reportPublisher.onFinal(
        OctaneGateReportState.PASSED, "ALM Octane suite gate passed.", result, classifier);
    return result;
  }

  private GateResult finishExtendedGate(
      GateRequest request,
      TaskListener listener,
      OctaneGateReportPublisher reportPublisher,
      GateResult result,
      StatusClassifier classifier,
      boolean manualExitRequested)
      throws GateFailedException {
    if (!manualExitRequested) {
      logListener.logExtendedTimeExpired(listener);
    }
    if (result.isPassed()) {
      return passGate(listener, reportPublisher, result, classifier);
    }

    String message =
        manualExitRequested
            ? "Exit Octane and Continue requested before criteria passed."
            : "Extended timeout elapsed before the ALM Octane suite gate passed.";
    OctaneGateReportState state =
        manualExitRequested ? failureState(request) : timeoutState(request);
    reportPublisher.onFinal(state, message, result, classifier);
    throw new GateFailedException(message, result);
  }

  private Duration waitDuration(GateRequest request, Instant deadline) {
    Duration pollInterval = Duration.ofSeconds(request.getPollIntervalSeconds());
    Duration remaining = Duration.between(clock.instant(), deadline);
    if (remaining.isZero() || remaining.isNegative()) {
      return Duration.ZERO;
    }
    return remaining.compareTo(pollInterval) < 0 ? remaining : pollInterval;
  }

  GateResult refreshPassedResult(
      OctaneClient client,
      GateResult previousResult,
      GateRequest request,
      List<String> suiteRunIds,
      String sharedSpaceId,
      String workspaceId,
      CriteriaExpression criteria,
      StatusClassifier classifier,
      TaskListener listener,
      OctaneGateReportPublisher reportPublisher)
      throws InterruptedException {
    return refreshPassedResult(
        client,
        previousResult,
        request,
        suiteRunIds,
        sharedSpaceId,
        workspaceId,
        criteria,
        classifier,
        listener,
        reportPublisher,
        new OctaneDefectLedger());
  }

  GateResult refreshPassedResult(
      OctaneClient client,
      GateResult previousResult,
      GateRequest request,
      List<String> suiteRunIds,
      String sharedSpaceId,
      String workspaceId,
      CriteriaExpression criteria,
      StatusClassifier classifier,
      TaskListener listener,
      OctaneGateReportPublisher reportPublisher,
      OctaneDefectLedger defectLedger)
      throws InterruptedException {
    logListener.logFinalRefresh(listener);
    try {
      GateResult refreshedResult =
          poll(
              client,
              request,
              suiteRunIds,
              sharedSpaceId,
              workspaceId,
              criteria,
              classifier,
              listener,
              defectLedger);
      logListener.logPollResult(listener, refreshedResult);
      logListener.logFinalReconciliationCompleted(listener, refreshedResult.getPolledAt());
      reportPublisher.onPoll(refreshedResult, classifier);
      return refreshedResult;
    } catch (IOException e) {
      logListener.logFinalRefreshSkipped(listener, e);
      return previousResult;
    }
  }

  private GateResult poll(
      OctaneClient client,
      GateRequest request,
      List<String> suiteRunIds,
      String sharedSpaceId,
      String workspaceId,
      CriteriaExpression criteria,
      StatusClassifier classifier,
      TaskListener listener,
      OctaneDefectLedger defectLedger)
      throws IOException, InterruptedException {
    Map<String, List<RunRecord>> suiteRuns =
        fetchSuiteChildRuns(client, sharedSpaceId, workspaceId, suiteRunIds);
    return poll(
        client,
        request,
        suiteRuns,
        Map.of(),
        !suiteRunIds.isEmpty(),
        false,
        sharedSpaceId,
        workspaceId,
        criteria,
        classifier,
        listener,
        defectLedger);
  }

  GateResult poll(
      OctaneClient client,
      GateRequest request,
      Map<String, List<RunRecord>> suiteRuns,
      Map<String, Map<String, List<RunRecord>>> suiteScopeRuns,
      boolean regressionSelectionEnabled,
      boolean awaitingSuiteDiscovery,
      String sharedSpaceId,
      String workspaceId,
      CriteriaExpression criteria,
      StatusClassifier classifier,
      TaskListener listener,
      OctaneDefectLedger defectLedger)
      throws IOException, InterruptedException {
    List<RunRecord> childRuns = flattenAndDedupeRuns(suiteRuns);
    GateMetrics regressionMetrics = GateMetrics.fromRuns(childRuns, classifier);
    List<String> childRunIds = runIds(childRuns);
    boolean regressionEvaluationEnabled = regressionSelectionEnabled && !suiteRuns.isEmpty();
    Set<String> inactiveMetricNamespaces = new LinkedHashSet<>();
    if (!regressionEvaluationEnabled) {
      inactiveMetricNamespaces.add("regressions");
    }

    Map<String, GateMetrics> scopedMetrics = new LinkedHashMap<>();
    Map<String, GateScopeResult> scopedResults = new LinkedHashMap<>();
    for (OctaneGateScope scope : request.getScopes()) {
      Map<String, List<RunRecord>> currentScopeSuiteRuns = suiteScopeRuns.get(scope.getName());
      if (scope.isSuiteRunScope()
          && currentScopeSuiteRuns != null
          && currentScopeSuiteRuns.isEmpty()) {
        inactiveMetricNamespaces.add(scope.getName());
        scopedResults.put(scope.getName(), GateScopeResult.inactiveSuiteRunScope(scope.getName()));
        continue;
      }
      GateScopeResult scopeResult =
          pollScope(
              client,
              sharedSpaceId,
              workspaceId,
              childRunIds,
              suiteRuns,
              currentScopeSuiteRuns,
              classifier,
              scope);
      scopedMetrics.put(scope.getName(), scopeResult.getMetrics());
      scopedResults.put(scope.getName(), scopeResult);
    }

    List<RunRecord> totalRuns = new ArrayList<>(childRuns);
    for (GateScopeResult scopeResult : scopedResults.values()) {
      if (scopeResult.isActive()) {
        totalRuns.addAll(scopeResult.getRuns());
      }
    }
    GateMetrics totalMetrics = GateMetrics.fromRuns(dedupeRuns(totalRuns), classifier);

    boolean defectCriteriaRequired = criteria.usesMetricNamespace("defects");
    DefectPollResult defectPollResult =
        pollDefects(
            client,
            request,
            sharedSpaceId,
            workspaceId,
            heatMapSuiteRuns(suiteRuns, scopedResults),
            classifier,
            listener,
            defectLedger,
            defectCriteriaRequired);
    DefectCriteriaMetrics defectMetrics =
        new DefectCriteriaMetrics(defectPollResult.severitySummary, request.getDefectGroups());
    MetricsContext metricsContext =
        new MetricsContext(regressionMetrics, scopedMetrics, defectMetrics, totalMetrics);
    String effectiveCriteria =
        inactiveMetricNamespaces.isEmpty()
            ? request.getCriteria()
            : criteria.effectiveExpression(metricsContext, inactiveMetricNamespaces);
    CriteriaEvaluation criteriaEvaluation =
        inactiveMetricNamespaces.isEmpty()
            ? criteria.evaluateDetailed(metricsContext)
            : criteria.evaluateAppliedDetailed(metricsContext, inactiveMetricNamespaces);
    boolean passed = !awaitingSuiteDiscovery && criteriaEvaluation.isPassed();
    boolean terminal =
        !awaitingSuiteDiscovery && (!regressionEvaluationEnabled || regressionMetrics.isTerminal());
    for (GateMetrics scopedMetric : scopedMetrics.values()) {
      if (!Objects.requireNonNull(scopedMetric).isTerminal()) {
        terminal = false;
        break;
      }
    }
    return new GateResult(
        String.join(",", suiteRuns.keySet()),
        effectiveCriteria,
        passed,
        terminal,
        regressionMetrics,
        childRuns,
        suiteRuns,
        scopedResults,
        defectPollResult.reportHeatMap,
        defectMetrics,
        defectPollResult.defects,
        criteriaEvaluation,
        clock.instant());
  }

  private Map<String, List<RunRecord>> heatMapSuiteRuns(
      Map<String, List<RunRecord>> regressionSuiteRuns,
      Map<String, GateScopeResult> scopedResults) {
    Map<String, List<RunRecord>> values = new LinkedHashMap<>(regressionSuiteRuns);
    for (GateScopeResult scopeResult : scopedResults.values()) {
      if (!scopeResult.isSuiteRunScope()) {
        continue;
      }
      for (Map.Entry<String, List<RunRecord>> entry : scopeResult.getSuiteRuns().entrySet()) {
        values.putIfAbsent(entry.getKey(), entry.getValue());
      }
    }
    return values;
  }

  private DefectPollResult pollDefects(
      OctaneClient client,
      GateRequest request,
      String sharedSpaceId,
      String workspaceId,
      Map<String, List<RunRecord>> suiteRuns,
      StatusClassifier classifier,
      TaskListener listener,
      OctaneDefectLedger defectLedger,
      boolean defectCriteriaRequired)
      throws IOException, InterruptedException {
    if (!request.isRiskHeatMap() && !defectCriteriaRequired) {
      return DefectPollResult.empty();
    }
    try {
      List<DefectRecord> defects =
          client.fetchLinkedDefects(
              sharedSpaceId,
              workspaceId,
              suiteRuns,
              request.getRiskHeatMapDefectQuery(),
              request.getRiskHeatMapMaxDefects());
      defectLedger.retainLinkedTo(flattenAndDedupeRuns(suiteRuns), defects);
      defectLedger.merge(defects);
      if (defectLedger.isAtCapacity()) {
        listener
            .getLogger()
            .println(
                "Octane defect history reached its safety limit of "
                    + OctaneDefectLedger.MAXIMUM_DEFECTS
                    + " unique defects; existing defect states will continue to refresh.");
      }
      refreshKnownDefects(
          client,
          sharedSpaceId,
          workspaceId,
          request.getRiskHeatMapMaxDefects(),
          defectLedger,
          defectCriteriaRequired);
      List<DefectRecord> scopedDefects =
          OctaneSuiteScopedDefects.select(suiteRuns, defectLedger.getDefects());
      OctaneRiskHeatMap heatMap =
          new OctaneRiskHeatMapBuilder().build(workspaceId, suiteRuns, scopedDefects, classifier);
      if (request.isRiskHeatMap()) {
        logRiskHeatMapSummary(listener, heatMap);
      }
      return new DefectPollResult(
          request.isRiskHeatMap() ? heatMap : OctaneRiskHeatMap.disabled(),
          heatMap.getDefectSeveritySummary(),
          scopedDefects);
    } catch (IOException e) {
      if (defectCriteriaRequired) {
        listener
            .getLogger()
            .println("Octane defect criteria data unavailable: " + Util.forLog(e.getMessage()));
        throw new AbortException(
            "Defect criteria could not be evaluated because current ALM Octane defect data is unavailable.");
      }
      listener
          .getLogger()
          .println("Octane risk heat map unavailable: " + Util.forLog(e.getMessage()));
      List<DefectRecord> scopedDefects =
          OctaneSuiteScopedDefects.select(suiteRuns, defectLedger.getDefects());
      return new DefectPollResult(
          OctaneRiskHeatMap.unavailable("Risk heat map unavailable: " + e.getMessage()),
          OctaneDefectSeveritySummary.empty(),
          scopedDefects);
    }
  }

  private void refreshKnownDefects(
      OctaneClient client,
      String sharedSpaceId,
      String workspaceId,
      int maxDefects,
      OctaneDefectLedger defectLedger,
      boolean defectCriteriaRequired)
      throws IOException, InterruptedException {
    if (defectLedger.isEmpty()) {
      return;
    }
    try {
      defectLedger.merge(
          client.fetchDefectsByIds(
              sharedSpaceId, workspaceId, defectLedger.getDefectIds(), maxDefects));
    } catch (IOException e) {
      if (defectCriteriaRequired) {
        throw e;
      }
      // Keep the last known defect states rather than making the whole report unavailable.
    }
  }

  private void logRiskHeatMapSummary(TaskListener listener, OctaneRiskHeatMap heatMap) {
    listener
        .getLogger()
        .println(
            "Octane risk heat map: risk "
                + heatMap.getRiskScore()
                + ", defects fetched "
                + heatMap.getFetchedDefectCount()
                + ", linked "
                + heatMap.getLinkedDefectCount()
                + ", unlinked "
                + heatMap.getUnlinkedOpenDefectCount()
                + ", ignored closed "
                + heatMap.getIgnoredClosedDefectCount()
                + ".");
  }

  private GateScopeResult pollScope(
      OctaneClient client,
      String sharedSpaceId,
      String workspaceId,
      List<String> childRunIds,
      Map<String, List<RunRecord>> regressionSuiteRuns,
      Map<String, List<RunRecord>> resolvedScopeSuiteRuns,
      StatusClassifier classifier,
      OctaneGateScope scope)
      throws IOException, InterruptedException {
    if (scope.isSuiteRunScope()) {
      Map<String, List<RunRecord>> scopeSuiteRuns =
          resolvedScopeSuiteRuns == null
              ? fetchSuiteChildRuns(client, sharedSpaceId, workspaceId, scope.getSuiteRunIds())
              : resolvedScopeSuiteRuns;
      List<RunRecord> scopeRuns = flattenAndDedupeRuns(scopeSuiteRuns);
      GateMetrics metrics = GateMetrics.fromRuns(scopeRuns, classifier);
      return new GateScopeResult(
          scope.getName(),
          "",
          List.of(),
          String.join(",", scopeSuiteRuns.keySet()),
          List.copyOf(scopeSuiteRuns.keySet()),
          metrics,
          scopeRuns,
          scopeSuiteRuns);
    }

    List<RunRecord> scopedRuns;
    try {
      scopedRuns =
          client.fetchScopedRuns(sharedSpaceId, workspaceId, childRunIds, scope.getQuery());
    } catch (IOException e) {
      throw new AbortException(
          "ALM Octane scope '"
              + scope.getName()
              + "' query failed: "
              + scope.getQuery()
              + ". "
              + scopeQueryHint(scope)
              + e.getMessage());
    }
    scopedRuns = applySuiteOwnership(regressionSuiteRuns, scopedRuns);
    GateMetrics metrics = GateMetrics.fromRuns(scopedRuns, classifier);
    return new GateScopeResult(
        scope.getName(),
        scope.getQuery(),
        scope.getReferencedIds(),
        "",
        List.of(),
        metrics,
        scopedRuns,
        groupScopedRunsBySuiteRun(regressionSuiteRuns, scopedRuns));
  }

  private List<RunRecord> applySuiteOwnership(
      Map<String, List<RunRecord>> suiteRuns, List<RunRecord> scopedRuns) {
    Map<String, String> suiteOwnersByRunId = new LinkedHashMap<>();
    for (List<RunRecord> runs : suiteRuns.values()) {
      for (RunRecord run : runs) {
        suiteOwnersByRunId.putIfAbsent(run.getId(), run.getSuiteOwnerName());
      }
    }
    return scopedRuns.stream()
        .map(
            run ->
                run.withSuiteOwnerName(
                    suiteOwnersByRunId.getOrDefault(run.getId(), run.getSuiteOwnerName())))
        .toList();
  }

  private OctaneGateReportState failureState(GateRequest request) {
    return request.isMarkUnstable() ? OctaneGateReportState.UNSTABLE : OctaneGateReportState.FAILED;
  }

  private OctaneGateReportState timeoutState(GateRequest request) {
    return request.isMarkUnstable()
        ? OctaneGateReportState.UNSTABLE
        : OctaneGateReportState.TIMED_OUT;
  }

  private Map<String, List<RunRecord>> fetchSuiteChildRuns(
      OctaneClient client, String sharedSpaceId, String workspaceId, List<String> suiteRunIds)
      throws IOException, InterruptedException {
    return client.fetchSuiteChildRuns(sharedSpaceId, workspaceId, suiteRunIds);
  }

  private List<RunRecord> flattenAndDedupeRuns(Map<String, List<RunRecord>> suiteRuns) {
    List<RunRecord> runs = new ArrayList<>();
    for (List<RunRecord> records : suiteRuns.values()) {
      runs.addAll(records);
    }
    return dedupeRuns(runs);
  }

  private List<RunRecord> dedupeRuns(List<RunRecord> runs) {
    Map<String, RunRecord> recordsById = new LinkedHashMap<>();
    for (RunRecord record : runs) {
      recordsById.putIfAbsent(record.getId(), record);
    }
    return new ArrayList<>(recordsById.values());
  }

  private Map<String, List<RunRecord>> groupScopedRunsBySuiteRun(
      Map<String, List<RunRecord>> suiteRuns, List<RunRecord> scopedRuns) {
    Set<String> scopedRunIds = new LinkedHashSet<>(runIds(scopedRuns));
    Map<String, List<RunRecord>> groupedRuns = new LinkedHashMap<>();
    for (Map.Entry<String, List<RunRecord>> entry : suiteRuns.entrySet()) {
      List<RunRecord> matchingRuns =
          entry.getValue().stream().filter(run -> scopedRunIds.contains(run.getId())).toList();
      if (!matchingRuns.isEmpty()) {
        groupedRuns.put(entry.getKey(), matchingRuns);
      }
    }
    return groupedRuns;
  }

  private List<String> runIds(List<RunRecord> runs) {
    List<String> ids = new ArrayList<>(runs.size());
    for (RunRecord run : runs) {
      ids.add(Objects.requireNonNull(run).getId());
    }
    return List.copyOf(ids);
  }

  private String scopeQueryHint(OctaneGateScope scope) {
    String query = scope.getQuery().toLowerCase(Locale.ROOT);
    if (query.contains("product_area") && !query.contains("product_areas")) {
      return "Use product_areas for Octane test product-area filters. ";
    }
    return "";
  }

  static List<String> regressionSuiteRunIdsForCriteria(GateRequest request) {
    Set<String> criticalSuiteRunIds = criticalSuiteRunIds(request);
    return request.getSuiteRunIds().stream()
        .filter(suiteRunId -> !criticalSuiteRunIds.contains(suiteRunId))
        .toList();
  }

  static boolean regressionSelectionEnabled(GateRequest request) {
    SuiteRunSelector regressionSelector = request.getSuiteRunSelector();
    if (!regressionSelector.isConfigured()) {
      return false;
    }
    for (OctaneGateScope scope : request.getScopes()) {
      if ("critical".equalsIgnoreCase(scope.getName())
          && scope.isSuiteRunScope()
          && regressionSelector.equals(scope.getSuiteRunSelector())) {
        return false;
      }
    }
    return regressionSelector.isDynamic() || !regressionSuiteRunIdsForCriteria(request).isEmpty();
  }

  @FunctionalInterface
  interface DynamicSuiteRunDiscovery {
    List<String> discover(SuiteRunSelector selector) throws IOException, InterruptedException;
  }

  /** Shares identical dynamic selector results within one polling cycle only. */
  static final class SuiteRunDiscoveryCycle {
    private final Map<SuiteRunSelector, List<String>> resolvedDynamicSelectors =
        new LinkedHashMap<>();

    List<String> resolve(SuiteRunSelector selector, DynamicSuiteRunDiscovery discovery)
        throws IOException, InterruptedException {
      if (!selector.isDynamic()) {
        return selector.getExplicitIds();
      }
      List<String> resolved = resolvedDynamicSelectors.get(selector);
      if (resolved != null) {
        return resolved;
      }
      List<String> discovered = List.copyOf(discovery.discover(selector));
      resolvedDynamicSelectors.put(selector, discovered);
      return discovered;
    }
  }

  private static Set<String> criticalSuiteRunIds(GateRequest request) {
    Set<String> criticalSuiteRunIds = new LinkedHashSet<>();
    for (OctaneGateScope scope : request.getScopes()) {
      if ("critical".equalsIgnoreCase(scope.getName()) && scope.isSuiteRunScope()) {
        criticalSuiteRunIds.addAll(scope.getSuiteRunIds());
      }
    }
    return criticalSuiteRunIds;
  }

  private void validateRequest(GateRequest request) throws AbortException {
    if (Util.isBlank(request.getServerId())) {
      throw new AbortException("Octane server ID is required.");
    }
    SuiteRunSelector requestSelector =
        validatedSelector("Suite run selection", request.getSuiteRunId());
    List<String> requestedSuiteRunIds = requestSelector.getExplicitIds();
    validateSuiteRunSources(request);
    if (requestedSuiteRunIds.size() > GateRequest.MAX_SUITE_RUN_IDS) {
      throw new AbortException(
          "At most " + GateRequest.MAX_SUITE_RUN_IDS + " Octane suite run IDs are supported.");
    }
    validateNumericId("Shared space ID", request.getSharedSpaceId());
    validateNumericId("Workspace ID", request.getWorkspaceId());
    if (request.getScopes().size() > MAX_SCOPES) {
      throw new AbortException("At most " + MAX_SCOPES + " Octane scopes are supported.");
    }
    if (request.getDefectGroups().size() > MAX_DEFECT_GROUPS) {
      throw new AbortException(
          "At most " + MAX_DEFECT_GROUPS + " Octane defect groups are supported.");
    }
    for (OctaneGateScope scope : request.getScopes()) {
      validateScope(scope);
    }
    validateDefectGroups(request.getDefectGroups());
  }

  static void validateSuiteRunSources(GateRequest request) throws AbortException {
    boolean primaryConfigured = request.getSuiteRunSelector().isConfigured();
    boolean criticalConfigured =
        request.getScopes().stream()
            .anyMatch(
                scope -> "critical".equalsIgnoreCase(scope.getName()) && scope.isSuiteRunScope());
    if (!primaryConfigured && !criticalConfigured) {
      throw new AbortException(
          "A critical Octane suite run selection is required when the regression selection is empty.");
    }
  }

  private void validateDefectGroups(List<OctaneDefectGroup> defectGroups) throws AbortException {
    Set<String> names = new LinkedHashSet<>();
    for (OctaneDefectGroup group : defectGroups) {
      if (group == null) {
        throw new AbortException("Defect group configuration cannot be empty.");
      }
      String validationError = group.getValidationError();
      if (!validationError.isEmpty()) {
        throw new AbortException(validationError);
      }
      String normalizedName = OctaneDefectGroup.normalizeName(group.getName());
      if (!names.add(normalizedName)) {
        throw new AbortException(
            "Defect group names must be unique regardless of letter case: " + group.getName());
      }
    }
  }

  private void validateScope(OctaneGateScope scope) throws AbortException {
    validateScopeName(scope);
    validateScopeMode(scope);
    SuiteRunSelector selector =
        scope.isSuiteRunScope()
            ? validatedSelector("Suite run selection", scope.getSuiteRunId())
            : SuiteRunSelector.parse("");
    validateScopeSuiteRunLimit(scope, selector);
  }

  private void validateScopeName(OctaneGateScope scope) throws AbortException {
    if (scope == null || Util.isBlank(scope.getName())) {
      throw new AbortException("Octane scope name is required.");
    }
  }

  private void validateScopeMode(OctaneGateScope scope) throws AbortException {
    boolean suiteRunScope = scope.isSuiteRunScope();
    boolean queryScope = scope.isQueryScope();
    if (!suiteRunScope && !queryScope) {
      throw new AbortException(
          "Octane scope '" + scope.getName() + "' must define suite run ID(s) or an Octane query.");
    }
    if (suiteRunScope && queryScope) {
      throw new AbortException(
          "Octane scope '"
              + scope.getName()
              + "' must define either suite run ID(s) or an Octane query, not both.");
    }
  }

  private void validateScopeSuiteRunLimit(OctaneGateScope scope, SuiteRunSelector selector)
      throws AbortException {
    if (selector.getExplicitIds().size() > GateRequest.MAX_SUITE_RUN_IDS) {
      throw new AbortException(
          "Octane scope '"
              + scope.getName()
              + "' exceeds the "
              + GateRequest.MAX_SUITE_RUN_IDS
              + " suite run ID limit.");
    }
  }

  private SuiteRunSelector validatedSelector(String label, String value) throws AbortException {
    try {
      return SuiteRunSelector.parse(value);
    } catch (IllegalArgumentException e) {
      throw new AbortException(label + " is invalid: " + e.getMessage());
    }
  }

  private String displayScopeName(String scopeName) {
    String name = Util.trimToEmpty(scopeName);
    if (name.isEmpty()) {
      return "Scope";
    }
    return name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
  }

  private void validateNumericId(String label, String value) throws AbortException {
    String id = Util.trimToEmpty(value);
    if (id.isEmpty()) {
      return;
    }
    if (!OCTANE_NUMERIC_ID.matcher(id).matches()) {
      throw new AbortException(label + " must contain 1 to 18 digits.");
    }
  }

  OctaneClient createClient(GateRequest request) throws AbortException {
    ResolvedConnection connection = resolveConnection(request);
    StandardUsernamePasswordCredentials credentials = connection.credentials();
    return OctaneClient.withCredentials(
        connection.baseUrl(), credentials.getUsername(), credentials.getPassword());
  }

  private ResolvedConnection resolveConnection(GateRequest request) throws AbortException {
    String spaceName = Util.trimToEmpty(request.getServerId());
    String baseUrl = Util.trimToEmpty(request.getBaseUrl());
    if (baseUrl.isEmpty()) {
      throw new AbortException(
          "Base URL missing for space: "
              + (spaceName.isEmpty() ? "<unknown>" : spaceName)
              + " in octane_spaces_mapping.json");
    }
    try {
      baseUrl = OctaneServerUrl.normalize(baseUrl);
    } catch (IllegalArgumentException e) {
      throw new AbortException(
          "Base URL for space '"
              + (spaceName.isEmpty() ? "<unknown>" : spaceName)
              + "' is invalid: "
              + e.getMessage());
    }
    return new ResolvedConnection(baseUrl, resolveDynamicCredentials(request.getCredentialsId()));
  }

  private StandardUsernamePasswordCredentials resolveDynamicCredentials(String credentialsId)
      throws AbortException {
    LinkedHashSet<String> candidates = new LinkedHashSet<>();
    candidates.add(GLOBAL_API_CREDENTIALS_ID);
    String selectedCredentialsId = Util.trimToEmpty(credentialsId);
    if (!selectedCredentialsId.isEmpty()) {
      candidates.add(selectedCredentialsId);
    }
    for (String candidate : candidates) {
      StandardUsernamePasswordCredentials credentials = findCredentials(candidate);
      if (credentials != null) {
        return credentials;
      }
    }
    throw new AbortException(
        "ALM Octane API key credentials were not found. Tried Jenkins credential IDs: "
            + String.join(", ", candidates));
  }

  private StandardUsernamePasswordCredentials findCredentials(String credentialsId) {
    return CredentialsMatchers.firstOrNull(
        CredentialsProvider.lookupCredentialsInItemGroup(
            StandardUsernamePasswordCredentials.class, Jenkins.get(), ACL.SYSTEM2, List.of()),
        CredentialsMatchers.withId(credentialsId));
  }

  private record ResolvedConnection(
      String baseUrl, StandardUsernamePasswordCredentials credentials) {}

  private String requiredWorkspaceValue(String label, String value) throws AbortException {
    String chosen = Util.trimToEmpty(value);
    if (chosen.isEmpty()) {
      throw new AbortException(
          "Shared space ID and workspace ID must be provided in the Jenkins job configuration.");
    }
    try {
      Long.parseLong(chosen);
    } catch (NumberFormatException e) {
      throw new AbortException(label + " must be numeric.");
    }
    return chosen;
  }

  private static class DefectPollResult {
    private final OctaneRiskHeatMap reportHeatMap;
    private final OctaneDefectSeveritySummary severitySummary;
    private final List<DefectRecord> defects;

    private DefectPollResult(
        OctaneRiskHeatMap reportHeatMap,
        OctaneDefectSeveritySummary severitySummary,
        List<DefectRecord> defects) {
      this.reportHeatMap = reportHeatMap;
      this.severitySummary = severitySummary;
      this.defects = defects == null ? List.of() : List.copyOf(defects);
    }

    private static DefectPollResult empty() {
      return new DefectPollResult(
          OctaneRiskHeatMap.disabled(), OctaneDefectSeveritySummary.empty(), List.of());
    }
  }
}
