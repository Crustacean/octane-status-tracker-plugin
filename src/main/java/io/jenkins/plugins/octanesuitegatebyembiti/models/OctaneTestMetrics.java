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
import java.util.Set;

public class OctaneTestMetrics implements Serializable {
  private static final long serialVersionUID = 1L;

  private static final String NO_BASELINE = "No previous cycle";
  private static final String NEUTRAL = "neutral";
  private static final String POSITIVE = "positive";
  private static final String NEGATIVE = "negative";
  private static final String WARNING = "warning";
  private static final double ACTION_DEGRADATION_PERCENT = 30.0;
  private final List<OctaneTestMetricCard> cards;
  private final int automatedTestCount;
  private final int manualTestCount;
  private final int automatedTestingTarget;

  private OctaneTestMetrics(
      List<OctaneTestMetricCard> cards,
      int automatedTestCount,
      int manualTestCount,
      int automatedTestingTarget) {
    this.cards = List.copyOf(cards);
    this.automatedTestCount = Math.max(0, automatedTestCount);
    this.manualTestCount = Math.max(0, manualTestCount);
    this.automatedTestingTarget = percentageTarget(automatedTestingTarget);
  }

  public static OctaneTestMetrics empty() {
    return new OctaneTestMetrics(
        List.of(
            card(
                "automation-usage",
                "Automation Usage",
                "0%",
                "0/0 tests automated. Target 100%",
                "Waiting for run data",
                NEUTRAL,
                "automation"),
            card(
                "success-rate",
                "Success Rate",
                "0.0%",
                "0 / 0 passed",
                NO_BASELINE,
                NEUTRAL,
                "chart"),
            card(
                "execution",
                "Execution Completion",
                "0.0%",
                "0 / 0 executed",
                NO_BASELINE,
                NEUTRAL,
                "activity"),
            card(
                "defects",
                "Open Defects",
                "N/A",
                "Risk heat map unavailable",
                NO_BASELINE,
                NEUTRAL,
                "defect")),
        0,
        0,
        GateRequest.DEFAULT_AUTOMATED_TESTING_TARGET);
  }

  public static OctaneTestMetrics fromResult(GateResult result) {
    if (result == null) {
      return empty();
    }
    Map<String, RunRecord> uniqueRuns = new LinkedHashMap<>();
    collectRuns(uniqueRuns, result.getRuns());
    for (List<RunRecord> suiteRuns : result.getSuiteRuns().values()) {
      collectRuns(uniqueRuns, suiteRuns);
    }
    for (GateScopeResult scope : result.getScopedResults().values()) {
      if (!scope.isActive()) {
        continue;
      }
      collectRuns(uniqueRuns, scope.getRuns());
      for (List<RunRecord> suiteRuns : scope.getSuiteRuns().values()) {
        collectRuns(uniqueRuns, suiteRuns);
      }
    }

    OctaneAutomationUsage automationUsage =
        OctaneAutomationUsage.fromRuns(List.copyOf(uniqueRuns.values()));
    return new OctaneTestMetrics(
        List.of(),
        automationUsage.getAutomatedCount(),
        automationUsage.getManualCount(),
        GateRequest.DEFAULT_AUTOMATED_TESTING_TARGET);
  }

  public static OctaneTestMetrics fromSnapshots(
      OctaneGateReportSnapshot current, OctaneGateReportSnapshot previous) {
    if (current == null) {
      return empty();
    }

    OctaneTestMetrics usage = current.getTestMetrics();
    OctaneTestMetrics previousUsage = previous == null ? null : previous.getTestMetrics();
    List<OctaneTestMetricCard> cards = new ArrayList<>();
    cards.add(automationUsage(usage, previousUsage));
    cards.add(successRate(current, previous));
    cards.add(executionCompletion(current, previous));
    cards.add(openDefects(current, previous));
    return new OctaneTestMetrics(
        cards,
        usage.getAutomatedTestCount(),
        usage.getManualTestCount(),
        usage.getAutomatedTestingTarget());
  }

  public List<OctaneTestMetricCard> getCards() {
    return cards;
  }

  public int getAutomatedTestCount() {
    return automatedTestCount;
  }

  public int getManualTestCount() {
    return manualTestCount;
  }

  public int getAutomationTestTotal() {
    return automatedTestCount + manualTestCount;
  }

  public int getAutomationPercentage() {
    int total = getAutomationTestTotal();
    return total == 0 ? 0 : (int) Math.round(automatedTestCount * 100.0 / total);
  }

  public String getAutomationPercentageText() {
    return getAutomationPercentage() + "%";
  }

  public String getAutomationEmoji() {
    return OctaneAutomationUsage.emojiForPercentage(getAutomationPercentage());
  }

  public int getAutomatedTestingTarget() {
    return percentageTarget(automatedTestingTarget);
  }

  public String getAutomationTone() {
    return automationTargetTone(
        getAutomationPercentage(), getAutomationTestTotal(), getAutomatedTestingTarget());
  }

  public static String automationTargetTone(
      int automationPercentage, int automationTestTotal, int automatedTestingTarget) {
    if (automationTestTotal <= 0) {
      return NEUTRAL;
    }
    int difference = automationPercentage - percentageTarget(automatedTestingTarget);
    if (difference >= 0) {
      return POSITIVE;
    }
    int deficit = Math.abs(difference);
    return deficit <= 10 ? WARNING : NEGATIVE;
  }

  public OctaneTestMetrics withAutomatedTestingTarget(int target) {
    return new OctaneTestMetrics(cards, automatedTestCount, manualTestCount, target);
  }

  public Map<String, Object> toMap() {
    Map<String, Object> values = new LinkedHashMap<>();
    List<Map<String, Object>> cardValues = new ArrayList<>();
    for (OctaneTestMetricCard card : cards) {
      cardValues.add(card.toMap());
    }
    values.put("cards", cardValues);
    values.put("automatedTestCount", automatedTestCount);
    values.put("manualTestCount", manualTestCount);
    values.put("automationTestTotal", getAutomationTestTotal());
    values.put("automationPercentage", getAutomationPercentage());
    values.put("automatedTestingTarget", getAutomatedTestingTarget());
    return values;
  }

  private static OctaneTestMetricCard automationUsage(
      OctaneTestMetrics usage, OctaneTestMetrics previousUsage) {
    Trend trend = automationCycleTrend(usage, previousUsage);
    return card(
        "automation-usage",
        "Automation Usage",
        usage.getAutomationPercentageText(),
        usage.getAutomatedTestCount()
            + "/"
            + usage.getAutomationTestTotal()
            + " tests automated. Target "
            + usage.getAutomatedTestingTarget()
            + "%",
        trend.text,
        trend.tone,
        "automation",
        usage.getAutomationPercentage(),
        "",
        automationSegments(usage));
  }

  private static OctaneTestMetricCard successRate(
      OctaneGateReportSnapshot current, OctaneGateReportSnapshot previous) {
    int executed = current.getExecutedTestCount();
    double currentRate = current.getPassRateProgress();
    Double previousRate =
        previous == null || previous.getExecutedTestCount() == 0
            ? null
            : previous.getPassRateProgress();
    Trend trend = percentTrend(currentRate, previousRate, true);
    return card(
        "success-rate",
        "Success Rate",
        Util.formatPercentage(currentRate, 1),
        current.getPassedTestCount() + " / " + executed + " passed",
        trend.text,
        trend.tone,
        "chart",
        currentRate,
        "",
        List.of());
  }

  private static OctaneTestMetricCard executionCompletion(
      OctaneGateReportSnapshot current, OctaneGateReportSnapshot previous) {
    int total = current.getProjectTestTotal();
    double currentRate = current.getExecutionProgress();
    Double previousRate =
        previous == null || previous.getProjectTestTotal() == 0
            ? null
            : previous.getExecutionProgress();
    Trend trend = percentTrend(currentRate, previousRate, true);
    return card(
        "execution",
        "Execution Completion",
        Util.formatPercentage(currentRate, 1),
        current.getExecutedTestCount() + " / " + total + " executed",
        trend.text,
        trend.tone,
        "activity",
        currentRate,
        "",
        List.of());
  }

  private static OctaneTestMetricCard openDefects(
      OctaneGateReportSnapshot current, OctaneGateReportSnapshot previous) {
    int total = current.getProjectTestTotal();
    if (!current.hasDefectMetrics()) {
      return card(
          "defects",
          "Open Defects",
          "N/A",
          "Risk heat map unavailable",
          NO_BASELINE,
          NEUTRAL,
          "defect");
    }
    int currentOpenDefects = current.getOpenDefectCount();
    Integer previousOpenDefects =
        previous == null || !previous.hasDefectMetrics() ? null : previous.getOpenDefectCount();
    double perHundred = total == 0 ? 0.0 : currentOpenDefects * 100.0 / total;
    Trend trend = countTrend(currentOpenDefects, previousOpenDefects, false);
    return card(
        "defects",
        "Open Defects",
        currentOpenDefects + " open",
        String.format(Locale.ROOT, "%.1f per 100 tests", perHundred),
        trend.text,
        trend.tone,
        "defect",
        0.0,
        "",
        defectSegments(current));
  }

  private static Trend automationCycleTrend(
      OctaneTestMetrics usage, OctaneTestMetrics previousUsage) {
    int total = usage.getAutomationTestTotal();
    if (total == 0) {
      return Trend.neutral("Waiting for run data");
    }
    String targetTone = usage.getAutomationTone();
    if (previousUsage == null || previousUsage.getAutomationTestTotal() == 0) {
      return Trend.of(NO_BASELINE, targetTone);
    }
    int delta = usage.getAutomationPercentage() - previousUsage.getAutomationPercentage();
    if (delta == 0) {
      return Trend.of("No change from last cycle", targetTone);
    }
    return Trend.of((delta > 0 ? "+" : "") + delta + "% from last cycle", targetTone);
  }

  private static List<OctaneTestMetricSegment> automationSegments(OctaneTestMetrics usage) {
    int total = usage.getAutomationTestTotal();
    if (total == 0) {
      return List.of();
    }
    List<OctaneTestMetricSegment> segments = new ArrayList<>();
    if (usage.getAutomatedTestCount() > 0) {
      segments.add(
          new OctaneTestMetricSegment(
              "🔥 Automated",
              "🔥",
              usage.getAutomatedTestCount(),
              usage.getAutomatedTestCount() * 100.0 / total,
              "automated",
              0));
    }
    if (usage.getManualTestCount() > 0) {
      segments.add(
          new OctaneTestMetricSegment(
              "🐢 Manual",
              "🐢",
              usage.getManualTestCount(),
              usage.getManualTestCount() * 100.0 / total,
              "manual",
              1));
    }
    return List.copyOf(segments);
  }

  private static void collectRuns(Map<String, RunRecord> uniqueRuns, List<RunRecord> runs) {
    for (RunRecord run : runs) {
      if (run == null) {
        continue;
      }
      String id = run.getId();
      String key =
          id.isBlank()
              ? String.join(
                  "\u0000",
                  run.getName(),
                  run.getTestId(),
                  run.getStatus(),
                  run.getExecutionActorName())
              : id;
      uniqueRuns.putIfAbsent(key, run);
    }
  }

  private static int percentageTarget(int value) {
    if (value <= 0) {
      return GateRequest.DEFAULT_AUTOMATED_TESTING_TARGET;
    }
    return Math.min(100, value);
  }

  private static OctaneTestMetricCard card(
      String key,
      String title,
      String value,
      String detail,
      String trendText,
      String trendTone,
      String icon) {
    return card(key, title, value, detail, trendText, trendTone, icon, 0.0, "", List.of());
  }

  private static OctaneTestMetricCard card(
      String key,
      String title,
      String value,
      String detail,
      String trendText,
      String trendTone,
      String icon,
      double progressPercent,
      String sparklinePoints,
      List<OctaneTestMetricSegment> segments) {
    return new OctaneTestMetricCard(
        key,
        title,
        value,
        detail,
        trendText,
        trendTone,
        icon,
        progressPercent,
        sparklinePoints,
        segments);
  }

  private static Trend percentTrend(double current, Double previous, boolean higherIsBetter) {
    if (previous == null) {
      return Trend.neutral(NO_BASELINE);
    }
    double delta = current - previous;
    if (Math.abs(delta) < 0.05) {
      return Trend.neutral("No change from last cycle");
    }
    String sign = delta > 0 ? "+" : "";
    String text =
        sign + Util.formatPercentage(delta, 1) + (delta > 0 ? " improvement" : " from last cycle");
    return Trend.of(text, trendTone(current, previous, higherIsBetter));
  }

  private static Trend countTrend(int current, Integer previous, boolean higherIsBetter) {
    if (previous == null) {
      return Trend.neutral(NO_BASELINE);
    }
    int delta = current - previous;
    if (delta == 0) {
      return Trend.neutral("No change from last cycle");
    }
    String sign = delta > 0 ? "+" : "";
    return Trend.of(
        sign + delta + " from last cycle",
        trendTone(current, previous.doubleValue(), higherIsBetter));
  }

  private static String trendTone(double current, double previous, boolean higherIsBetter) {
    double delta = current - previous;
    boolean good = higherIsBetter ? delta > 0 : delta < 0;
    if (good) {
      return POSITIVE;
    }
    double degradationPercent =
        previous == 0.0 ? 100.0 : Math.abs(delta) * 100.0 / Math.abs(previous);
    return degradationPercent > ACTION_DEGRADATION_PERCENT ? NEGATIVE : WARNING;
  }

  private static List<OctaneTestMetricSegment> defectSegments(OctaneGateReportSnapshot snapshot) {
    OctaneDefectSeveritySummary summary = snapshot.getRiskHeatMap().getDefectSeveritySummary();
    int total = summary.getOpenTotal();
    if (total <= 0) {
      return List.of();
    }

    List<SegmentDefinition> definitions = new ArrayList<>();
    Set<String> claimedTypes = new LinkedHashSet<>();
    for (OctaneDefectGroup group : snapshot.getDefectMetrics().getConfiguredGroups()) {
      List<String> groupTypes =
          group.getNormalizedTypes().stream().filter(type -> !claimedTypes.contains(type)).toList();
      if (groupTypes.isEmpty()) {
        continue;
      }
      claimedTypes.addAll(groupTypes);
      definitions.add(
          new SegmentDefinition(
              displayName(group.getName()), groupTypes, highestSeverity(groupTypes)));
    }
    for (String type : OctaneDefectSeveritySummary.getOpenTypes()) {
      String normalized = OctaneDefectSeveritySummary.normalizeOpenType(type);
      if (!claimedTypes.contains(normalized)) {
        definitions.add(
            new SegmentDefinition(
                displayName(type), List.of(normalized), severityRank(normalized)));
      }
    }

    List<OctaneTestMetricSegment> segments = new ArrayList<>();
    for (SegmentDefinition definition : definitions) {
      int count = definition.types.stream().mapToInt(summary::getOpenCount).sum();
      if (count <= 0) {
        continue;
      }
      String label = definition.label + " (" + count + ")";
      String shortLabel = firstLetter(definition.label) + " (" + count + ")";
      segments.add(
          new OctaneTestMetricSegment(
              label,
              shortLabel,
              count,
              count * 100.0 / total,
              severityKey(definition.rank),
              definition.rank));
    }
    segments.sort(
        Comparator.comparingInt((OctaneTestMetricSegment segment) -> segment.getSeverityRank())
            .thenComparing(
                (OctaneTestMetricSegment segment) -> segment.getLabel(),
                String.CASE_INSENSITIVE_ORDER));
    return List.copyOf(segments);
  }

  private static int highestSeverity(List<String> types) {
    return types.stream().mapToInt(OctaneTestMetrics::severityRank).min().orElse(5);
  }

  private static int severityRank(String type) {
    switch (OctaneDefectSeveritySummary.normalizeOpenType(type)) {
      case "critical":
        return 0;
      case "veryhigh":
        return 1;
      case "high":
        return 2;
      case "medium":
        return 3;
      case "low":
        return 4;
      default:
        return 5;
    }
  }

  private static String severityKey(int rank) {
    switch (rank) {
      case 0:
        return "critical";
      case 1:
        return "very-high";
      case 2:
        return "high";
      case 3:
        return "medium";
      case 4:
        return "low";
      default:
        return "unspecified";
    }
  }

  private static String displayName(String value) {
    String normalized =
        value == null
            ? ""
            : value.replaceAll("([a-z])([A-Z])", "$1 $2").replace('-', ' ').replace('_', ' ');
    List<String> words = new ArrayList<>();
    for (String word : normalized.trim().split("\\s+")) {
      if (!word.isEmpty()) {
        words.add(
            word.substring(0, 1).toUpperCase(Locale.ROOT)
                + word.substring(1).toLowerCase(Locale.ROOT));
      }
    }
    return String.join(" ", words);
  }

  private static String firstLetter(String value) {
    return value == null || value.isEmpty() ? "?" : value.substring(0, 1).toUpperCase(Locale.ROOT);
  }

  private static class Trend {
    private final String text;
    private final String tone;

    private Trend(String text, String tone) {
      this.text = text;
      this.tone = tone;
    }

    private static Trend of(String text, String tone) {
      return new Trend(text, tone);
    }

    private static Trend neutral(String text) {
      return new Trend(text, NEUTRAL);
    }
  }

  private static class SegmentDefinition {
    private final String label;
    private final List<String> types;
    private final int rank;

    private SegmentDefinition(String label, List<String> types, int rank) {
      this.label = label;
      this.types = List.copyOf(types);
      this.rank = rank;
    }
  }
}
