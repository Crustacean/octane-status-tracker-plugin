package io.jenkins.plugins.octanesuitegatebyembiti.services;

import io.jenkins.plugins.octanesuitegatebyembiti.models.CriteriaComparisonEvaluation;
import io.jenkins.plugins.octanesuitegatebyembiti.models.GateMetrics;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneGateReportSection;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneGateReportSnapshot;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneGateStatusCount;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneGateSuiteRunChart;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class OctaneReportDataMapper {
  public static final int SCHEMA_VERSION = 1;

  public ReportData map(OctaneGateReportSnapshot snapshot) {
    OctaneGateReportSnapshot safeSnapshot =
        snapshot == null ? OctaneGateReportSnapshot.empty() : snapshot;
    List<Map<String, Object>> sectionIndexes = new ArrayList<>();
    List<Map<String, Object>> sections = new ArrayList<>();
    int sectionNumber = 0;
    for (OctaneGateReportSection section : safeSnapshot.getReportSections()) {
      String id = Integer.toString(sectionNumber++);
      Map<String, Object> index = sectionIndex(id, section);
      sectionIndexes.add(index);
      Map<String, Object> sectionData = new LinkedHashMap<>(index);
      sectionData.put("bars", section.getSuiteRuns().stream().map(this::barData).toList());
      sections.add(sectionData);
    }

    Map<String, Object> index = rootData(safeSnapshot);
    index.put("sections", sectionIndexes);
    Map<String, Object> complete = new LinkedHashMap<>(index);
    complete.put("suiteAttributions", safeSnapshot.getSuiteAttributions());
    complete.put("riskHeatMap", safeSnapshot.getRiskHeatMap().toMap());
    complete.put("sections", sections);
    return new ReportData(index, complete, sections);
  }

  private Map<String, Object> rootData(OctaneGateReportSnapshot snapshot) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("schemaVersion", SCHEMA_VERSION);
    root.put("state", snapshot.getState().name());
    root.put("stateLabel", snapshot.getStateLabel());
    root.put("message", snapshot.getMessage());
    root.put("building", snapshot.isBuilding());
    root.put("extendedTime", snapshot.isExtendedTime());
    root.put("startedAt", snapshot.getStartedAt());
    root.put("updatedAt", snapshot.getUpdatedAt());
    root.put("updatedAtText", snapshot.getUpdatedAtText());
    root.put("refreshSeconds", snapshot.getRefreshSeconds());
    root.put("timeoutSeconds", snapshot.getTimeoutSeconds());
    root.put("timeoutExtendedSeconds", snapshot.getTimeoutExtendedSeconds());
    root.put("criteria", criteriaData(snapshot));
    root.put("summary", summaryData(snapshot));
    root.put("riskHeatMap", snapshot.getRiskHeatMap().toSummaryMap());
    root.put("testMetrics", snapshot.getTestMetrics().toMap());
    root.put("defectTrend", snapshot.getDefectTrend().toMap());
    root.put("defectMetrics", snapshot.getDefectMetrics().toMap());
    root.put("testerDetails", snapshot.getTesterDetails());
    return root;
  }

  private Map<String, Object> criteriaData(OctaneGateReportSnapshot snapshot) {
    Map<String, Object> criteria = new LinkedHashMap<>();
    criteria.put("expression", snapshot.getCriteria());
    criteria.put("available", snapshot.getCriteriaEvaluation().isAvailable());
    criteria.put("passed", snapshot.getCriteriaEvaluation().isPassed());
    List<Map<String, Object>> comparisons = new ArrayList<>();
    for (CriteriaComparisonEvaluation comparison :
        snapshot.getCriteriaEvaluation().getComparisons()) {
      comparisons.add(comparison.toMap());
    }
    criteria.put("rows", comparisons);
    return criteria;
  }

  private Map<String, Object> summaryData(OctaneGateReportSnapshot snapshot) {
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("total", snapshot.getProjectTestTotal());
    summary.put("executed", snapshot.getExecutedTestCount());
    summary.put("resolved", snapshot.getResolvedTestCount());
    summary.put("passed", snapshot.getPassedTestCount());
    summary.put("executionProgress", snapshot.getExecutionProgress());
    summary.put("completionProgress", snapshot.getCompletionProgress());
    summary.put("passRate", snapshot.getPassRateProgress());
    summary.put("openDefects", snapshot.getOpenDefectCount());
    return summary;
  }

  private Map<String, Object> sectionIndex(String id, OctaneGateReportSection section) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("id", id);
    value.put("name", section.getName());
    value.put("source", section.getSource());
    value.put("distributionTitle", section.getStatusDistributionTitle());
    value.put("barChartTitle", section.getSuiteRunChartTitle());
    value.put("suiteRunCount", section.getSuiteRunCount());
    value.put("barCount", section.getSuiteRuns().size());
    value.put("xAxis", section.getXAxis());
    value.put("yAxis", section.getYAxis());
    value.put("tooltipsEnabled", section.isTooltipsEnabled());
    value.put("maxTotal", section.getMaxSuiteRunTotal());
    value.put("gridLineCount", section.getYAxisGridLineCount());
    value.put("yAxisTicks", section.getYAxisTicks());
    value.put("executedTestCount", section.getExecutedTestCount());
    value.put("automationPercentage", section.getAutomationPercentage());
    value.put("automationPercentageLabel", section.getAutomationPercentageText());
    value.put("automationEmoji", section.getAutomationEmoji());
    value.put("metrics", metricsData(section.getMetrics()));
    value.put("totals", section.getTotals().stream().map(this::statusData).toList());
    return value;
  }

  private Map<String, Object> metricsData(GateMetrics metrics) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("total", metrics.getTotal());
    value.put("executed", metrics.getExecuted());
    value.put("resolved", metrics.getResolved());
    value.put("passed", metrics.getPassed());
    value.put("failed", metrics.getFailed());
    value.put("skipped", metrics.getSkipped());
    value.put("running", metrics.getRunning());
    value.put("inProgress", metrics.getRunning());
    value.put("executionRate", metrics.getExecutionRate());
    value.put("completionRate", metrics.getCompletionRate());
    value.put("passRate", metrics.getPassRate());
    return value;
  }

  private Map<String, Object> barData(OctaneGateSuiteRunChart bar) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("id", bar.getSuiteRunId());
    value.put("name", bar.getDisplayName());
    value.put("axisLabel", bar.getAxisLabel());
    value.put("title", bar.getTitle());
    value.put("suiteRunIds", bar.getSuiteRunIds());
    value.put("total", bar.getTotal());
    value.put("automationPercentage", bar.getAutomationPercentage());
    value.put("automationEmoji", bar.getAutomationEmoji());
    value.put("dominantStatusColor", bar.getDominantStatusColor());
    value.put("dominantStatusLabel", bar.getDominantStatusLabel());
    value.put("inProgress", bar.getInProgressCount());
    value.put("statuses", bar.getStatuses().stream().map(this::statusData).toList());
    return value;
  }

  private Map<String, Object> statusData(OctaneGateStatusCount status) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("key", status.getDataKey());
    value.put("label", status.getLabel());
    value.put("count", status.getCount());
    value.put("percentage", status.getPercentage());
    value.put("percentageLabel", status.getPercentageLabel());
    value.put("color", status.getColor());
    value.put("tooltipColor", status.getTooltipColor());
    return value;
  }

  public record ReportData(
      Map<String, Object> index,
      Map<String, Object> complete,
      List<Map<String, Object>> sections) {}
}
