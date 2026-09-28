package io.jenkins.plugins.octanesuitegatebyembiti.models;

import io.jenkins.plugins.octanesuitegatebyembiti.entities.RunRecord;
import io.jenkins.plugins.octanesuitegatebyembiti.utils.Util;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class OctaneGateReportSection implements Serializable {
  private static final long serialVersionUID = 1L;
  public static final String STATUS_DISTRIBUTION_TITLE_APPEND = " Status Distribution";
  public static final String SUITE_RUN_DISTRIBUTION_TITLE_APPEND = " Suiterun Distribution";

  private final String name;
  private final String source;
  private final List<String> suiteRunIds;
  private final GateMetrics metrics;
  private final List<OctaneGateStatusCount> totals;
  private final List<OctaneGatePieSlice> pieSlices;
  private final List<OctaneGateSuiteRunChart> suiteRuns;
  private final OctaneAutomationUsage automationUsage;
  private final String customGraphsTitle;
  private final String xAxis;
  private final String yAxis;

  private OctaneGateReportSection(
      String name,
      String source,
      List<String> suiteRunIds,
      GateMetrics metrics,
      List<OctaneGateStatusCount> totals,
      List<OctaneGateSuiteRunChart> suiteRuns,
      OctaneAutomationUsage automationUsage,
      String customGraphsTitle,
      String xAxis,
      String yAxis) {
    this.name = name;
    this.source = source;
    this.suiteRunIds = List.copyOf(suiteRunIds);
    this.metrics = metrics;
    this.totals = List.copyOf(totals);
    this.pieSlices = buildPieSlices(totals);
    this.suiteRuns = List.copyOf(suiteRuns);
    this.automationUsage =
        automationUsage == null ? OctaneAutomationUsage.empty() : automationUsage;
    this.customGraphsTitle = Util.trimToEmpty(customGraphsTitle);
    this.xAxis = Util.trimToEmpty(xAxis);
    this.yAxis = Util.trimToEmpty(yAxis);
  }

  public static OctaneGateReportSection regressions(
      GateResult result, StatusClassifier classifier) {
    return regressions(result, classifier, GateRequest.DEFAULT_LIMIT_FOR_METRIC_RUNS_IN_SUITE);
  }

  public static OctaneGateReportSection regressions(
      GateResult result, StatusClassifier classifier, int metricRunsInSuiteLimit) {
    return fromSuiteRuns(
        "Regressions suite runs",
        "regressions",
        result.getSuiteRunId(),
        result.getMetrics(),
        result.getSuiteRuns(),
        result.getRuns(),
        classifier,
        metricRunsInSuiteLimit);
  }

  public static OctaneGateReportSection scoped(
      GateScopeResult scopeResult, StatusClassifier classifier) {
    return scoped(scopeResult, classifier, GateRequest.DEFAULT_LIMIT_FOR_METRIC_RUNS_IN_SUITE);
  }

  public static OctaneGateReportSection scoped(
      GateScopeResult scopeResult, StatusClassifier classifier, int metricRunsInSuiteLimit) {
    String label =
        displayScopeName(scopeResult.getName())
            + (scopeResult.isSuiteRunScope() ? " suite runs" : " query scope");
    String suiteRunId = scopeResult.isSuiteRunScope() ? scopeResult.getSuiteRunId() : "";
    return fromSuiteRuns(
        label,
        scopeResult.getName(),
        suiteRunId,
        scopeResult.getMetrics(),
        scopeResult.getSuiteRuns(),
        scopeResult.getRuns(),
        classifier,
        metricRunsInSuiteLimit);
  }

  private static OctaneGateReportSection fromSuiteRuns(
      String name,
      String source,
      String suiteRunId,
      GateMetrics metrics,
      Map<String, List<RunRecord>> suiteRuns,
      List<RunRecord> fallbackRuns,
      StatusClassifier classifier,
      int metricRunsInSuiteLimit) {
    Map<String, List<RunRecord>> chartSuiteRuns = new LinkedHashMap<>(suiteRuns);
    if (chartSuiteRuns.isEmpty() && !fallbackRuns.isEmpty()) {
      chartSuiteRuns.put("matched-runs", fallbackRuns);
    }

    List<RunRecord> reportRuns = runsForTotals(chartSuiteRuns, fallbackRuns);
    List<OctaneGateStatusCount> totals =
        reportRuns.isEmpty() ? totalsFromMetrics(metrics) : totalsFromRuns(reportRuns, classifier);
    boolean groupByStatus = reportRuns.size() > Math.max(1, metricRunsInSuiteLimit);
    List<OctaneGateSuiteRunChart> suiteRunCharts =
        groupByStatus
            ? groupSuiteRunsByStatus(totals)
            : groupSuiteRunsByRunBy(chartSuiteRuns).stream()
                .map(group -> group.toChart(classifier))
                .sorted(chartOrder())
                .toList();
    int maxSuiteRunTotal = maxSuiteRunTotal(suiteRunCharts);
    List<OctaneGateSuiteRunChart> scaledSuiteRunCharts =
        suiteRunCharts.stream().map(chart -> chart.scaledAgainst(maxSuiteRunTotal)).toList();
    return new OctaneGateReportSection(
        name,
        source,
        Util.splitIdList(suiteRunId),
        metrics,
        totals,
        scaledSuiteRunCharts,
        OctaneAutomationUsage.fromRuns(reportRuns),
        "",
        groupByStatus ? "Status" : "Tester",
        "Count");
  }

  public String getName() {
    return name;
  }

  public String getSource() {
    return source;
  }

  public List<String> getSuiteRunIds() {
    return suiteRunIds;
  }

  public GateMetrics getMetrics() {
    return metrics;
  }

  public List<OctaneGateStatusCount> getTotals() {
    return totals;
  }

  public List<OctaneGatePieSlice> getPieSlices() {
    return pieSlices;
  }

  public List<OctaneGateSuiteRunChart> getSuiteRuns() {
    return suiteRuns;
  }

  public String getXAxis() {
    return Util.isBlank(xAxis) ? "Tester" : xAxis;
  }

  public String getYAxis() {
    return Util.isBlank(yAxis) ? "Count" : yAxis;
  }

  public boolean isStatusGrouped() {
    return "status".equalsIgnoreCase(getXAxis());
  }

  public boolean isTooltipsEnabled() {
    return !isStatusGrouped();
  }

  public int getExecutedTestCount() {
    int executed = 0;
    for (OctaneGateStatusCount status : totals) {
      if (status.getBucket().isExecuted()) {
        executed += status.getCount();
      }
    }
    return executed;
  }

  public int getAutomationPercentage() {
    return getAutomationUsage().getPercentage();
  }

  public String getAutomationPercentageText() {
    return getAutomationUsage().getPercentageText();
  }

  public String getAutomationEmoji() {
    return getAutomationUsage().getEmoji();
  }

  private OctaneAutomationUsage getAutomationUsage() {
    return automationUsage == null ? OctaneAutomationUsage.empty() : automationUsage;
  }

  public int getSuiteRunCount() {
    if (!suiteRunIds.isEmpty()) {
      return suiteRunIds.size();
    }
    return suiteRuns.size();
  }

  public int getMaxSuiteRunTotal() {
    return maxSuiteRunTotal(suiteRuns);
  }

  public List<Integer> getYAxisTicks() {
    int max = getMaxSuiteRunTotal();
    if (max <= 0) {
      return List.of(0);
    }
    if (max <= 8) {
      List<Integer> ticks = new ArrayList<>();
      for (int value = max; value >= 0; value--) {
        ticks.add(value);
      }
      return ticks;
    }

    List<Integer> ticks = new ArrayList<>();
    Set<Integer> seen = new LinkedHashSet<>();
    for (int index = 4; index >= 0; index--) {
      int value = Math.round(max * index / 4.0f);
      if (seen.add(value)) {
        ticks.add(value);
      }
    }
    return ticks;
  }

  public int getYAxisGridLineCount() {
    return Math.max(1, getYAxisTicks().size() - 1);
  }

  public boolean isEmpty() {
    return metrics.getTotal() == 0;
  }

  public boolean isNoRuns() {
    return isEmpty();
  }

  public String getSuiteRunLabel() {
    if (suiteRunIds.isEmpty()) {
      return "<none>";
    }
    return String.join(", ", suiteRunIds);
  }

  public String getStatusDistributionTitle() {
    if (!Util.trimToEmpty(customGraphsTitle).isEmpty()) {
      return customGraphsTitle + STATUS_DISTRIBUTION_TITLE_APPEND;
    }
    if ("regressions".equalsIgnoreCase(source) || "global".equalsIgnoreCase(source)) {
      return "REGRESSION Tests Status Distribution";
    }
    if ("critical".equalsIgnoreCase(source)) {
      return "CRITICAL Tests Status Distribution";
    }
    return name + " status distribution";
  }

  public String getSuiteRunChartTitle() {
    if (isStatusGrouped()) {
      if (!Util.trimToEmpty(customGraphsTitle).isEmpty()) {
        return customGraphsTitle + " Testing progress by Status";
      }
      return name + " testing progress by Status";
    }
    if (!Util.trimToEmpty(customGraphsTitle).isEmpty()) {
      return customGraphsTitle + SUITE_RUN_DISTRIBUTION_TITLE_APPEND;
    }
    if ("regressions".equalsIgnoreCase(source) || "global".equalsIgnoreCase(source)) {
      return "Testing progress per Tester Suite Runs_REGRESSIONS";
    }
    if ("critical".equalsIgnoreCase(source)) {
      return "Testing progress per Tester Suite Runs_CRITICAL";
    }
    return name + " by suite run";
  }

  public OctaneGateReportSection withGraphTitles(
      String regressionGraphsTitle, String criticalGraphsTitle) {
    String title = "";
    if ("regressions".equalsIgnoreCase(source) || "global".equalsIgnoreCase(source)) {
      title = regressionGraphsTitle;
    } else if ("critical".equalsIgnoreCase(source)) {
      title = criticalGraphsTitle;
    }
    return new OctaneGateReportSection(
        name,
        source,
        suiteRunIds,
        metrics,
        totals,
        suiteRuns,
        automationUsage,
        title,
        getXAxis(),
        getYAxis());
  }

  private static List<OctaneGateStatusCount> totalsFromMetrics(GateMetrics metrics) {
    Map<OctaneGateStatusBucket, Integer> counts = OctaneGateSuiteRunChart.emptyCounts();
    counts.put(OctaneGateStatusBucket.PASSED, metrics.getPassed());
    counts.put(OctaneGateStatusBucket.FAILED, metrics.getFailed());
    counts.put(OctaneGateStatusBucket.SKIPPED, metrics.getSkipped());
    counts.put(OctaneGateStatusBucket.RUNNING, metrics.getRunning());
    return OctaneGateSuiteRunChart.toStatusCounts(counts, metrics.getTotal());
  }

  private static List<RunRecord> runsForTotals(
      Map<String, List<RunRecord>> suiteRuns, List<RunRecord> fallbackRuns) {
    if (!fallbackRuns.isEmpty()) {
      return fallbackRuns;
    }
    List<RunRecord> runs = new ArrayList<>();
    for (List<RunRecord> suiteRun : suiteRuns.values()) {
      runs.addAll(Objects.requireNonNull(suiteRun));
    }
    return List.copyOf(runs);
  }

  private static List<OctaneGateStatusCount> totalsFromRuns(
      List<RunRecord> runs, StatusClassifier classifier) {
    Map<OctaneGateStatusBucket, Integer> counts = OctaneGateSuiteRunChart.emptyCounts();
    for (RunRecord run : runs) {
      OctaneGateStatusBucket bucket =
          OctaneGateStatusBucket.fromOutcome(classifier.classify(run.getStatus()));
      counts.put(bucket, counts.get(bucket) + 1);
    }
    return OctaneGateSuiteRunChart.toStatusCounts(counts, runs.size());
  }

  private static List<RunByGroup> groupSuiteRunsByRunBy(Map<String, List<RunRecord>> suiteRuns) {
    Map<String, RunByGroup> groups = new LinkedHashMap<>();
    for (Map.Entry<String, List<RunRecord>> entry : suiteRuns.entrySet()) {
      if (entry.getValue().isEmpty()) {
        String label = TesterIdentityResolver.displayName(entry.getKey());
        groups.putIfAbsent(TesterIdentityResolver.key(label), new RunByGroup(label));
        groups.get(TesterIdentityResolver.key(label)).addSuiteRunId(entry.getKey());
        continue;
      }
      for (RunRecord run : entry.getValue()) {
        String label = assignedUserLabel(run);
        String key = TesterIdentityResolver.key(label);
        groups.putIfAbsent(key, new RunByGroup(label));
        groups.get(key).addSuiteRunId(entry.getKey());
        groups.get(key).addRun(run);
      }
    }
    return List.copyOf(groups.values());
  }

  private static String assignedUserLabel(RunRecord run) {
    return TesterIdentityResolver.displayName(run.getSuiteOwnerName());
  }

  private static List<OctaneGateSuiteRunChart> groupSuiteRunsByStatus(
      List<OctaneGateStatusCount> totals) {
    return totals.stream()
        .filter(status -> status.getCount() > 0)
        .map(OctaneGateSuiteRunChart::fromStatus)
        .sorted(chartOrder())
        .toList();
  }

  private static Comparator<OctaneGateSuiteRunChart> chartOrder() {
    return Comparator.comparingInt((OctaneGateSuiteRunChart chart) -> chart.getTotal())
        .thenComparing(OctaneGateReportSection::suiteRunSortKey);
  }

  private static List<OctaneGatePieSlice> buildPieSlices(List<OctaneGateStatusCount> totals) {
    int total = 0;
    for (OctaneGateStatusCount status : totals) {
      total += Objects.requireNonNull(status).getCount();
    }
    if (total == 0) {
      return List.of();
    }

    double angle = -90.0;
    ArrayList<OctaneGatePieSlice> slices = new ArrayList<>();
    for (OctaneGateStatusCount status : totals) {
      OctaneGateStatusCount nonNullStatus = Objects.requireNonNull(status);
      if (nonNullStatus.getCount() == 0) {
        continue;
      }
      double nextAngle = angle + 360.0 * nonNullStatus.getCount() / total;
      slices.add(new OctaneGatePieSlice(nonNullStatus, angle, nextAngle));
      angle = nextAngle;
    }
    return List.copyOf(slices);
  }

  private static int maxSuiteRunTotal(List<OctaneGateSuiteRunChart> suiteRunCharts) {
    int max = 0;
    for (OctaneGateSuiteRunChart chart : suiteRunCharts) {
      max = Math.max(max, Objects.requireNonNull(chart).getTotal());
    }
    return max;
  }

  private static String suiteRunSortKey(OctaneGateSuiteRunChart chart) {
    return String.join(",", chart.getSuiteRunIds()) + ":" + chart.getSuiteRunId();
  }

  private static String displayScopeName(String scopeName) {
    if (scopeName == null || scopeName.isBlank()) {
      return "Scope";
    }
    return scopeName.substring(0, 1).toUpperCase(Locale.ROOT) + scopeName.substring(1);
  }

  private static class RunByGroup {
    private final String displayName;
    private final Set<String> suiteRunIds = new LinkedHashSet<>();
    private final List<RunRecord> runs = new ArrayList<>();

    private RunByGroup(String displayName) {
      this.displayName = displayName;
    }

    private void addSuiteRunId(String suiteRunId) {
      if (!Util.isBlank(suiteRunId) && !"matched-runs".equals(suiteRunId)) {
        suiteRunIds.add(suiteRunId);
      }
    }

    private void addRun(RunRecord run) {
      runs.add(run);
    }

    private OctaneGateSuiteRunChart toChart(StatusClassifier classifier) {
      return OctaneGateSuiteRunChart.fromRunByGroup(
          displayName, List.copyOf(suiteRunIds), runs, classifier);
    }
  }
}
