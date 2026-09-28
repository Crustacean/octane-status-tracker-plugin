package io.jenkins.plugins.octanesuitegatebyembiti.models;

import io.jenkins.plugins.octanesuitegatebyembiti.entities.DefectRecord;
import io.jenkins.plugins.octanesuitegatebyembiti.entities.RunRecord;
import io.jenkins.plugins.octanesuitegatebyembiti.utils.Util;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class OctaneTestManagementAnalytics implements Serializable {
  private static final long serialVersionUID = 1L;
  private static final int MAX_POINTS = 2000;
  private static final int EXECUTION_INTERVAL_COUNT = 10;
  private static final int MAX_FAILURE_CLUSTERS = 8;
  private static final int TOP_TESTER_LIMIT = 5;
  private static final double ACTION_THRESHOLD_PERCENT = 70.0;
  private static final double CLUSTER_SIMILARITY_THRESHOLD = 0.24;
  private static final Pattern CLUSTER_TOKEN_PATTERN =
      Pattern.compile("[a-z0-9]+(?:[-_.][a-z0-9]+)*");
  private static final Set<String> CLUSTER_STOP_WORDS =
      Set.of(
          "a", "an", "and", "are", "at", "by", "defect", "error", "failed", "failure", "for",
          "from", "i", "in", "is", "issue", "of", "on", "or", "select", "test", "the", "to", "was",
          "when", "with");

  public static final String PLANNED_COLOR = "#8E8E93";
  public static final String IN_PROGRESS_COLOR = "#4391F5";
  public static final String SKIPPED_COLOR = "#BF5AF2";
  public static final String BLOCKED_COLOR = "#FF9F0A";
  public static final String FAILED_COLOR = "#FF453A";
  public static final String PASSED_COLOR = "#30D158";
  public static final String OPEN_COLOR = "#FF3B30";
  public static final String CLOSED_COLOR = "#34C759";

  private final String startedAt;
  private final long durationMillis;
  private final List<TimelinePoint> points;
  private final List<FailureCategory> failureCategories;
  private final List<TesterSummary> topVolumeTesters;
  private final List<TesterSummary> topDefectTesters;
  private final int totalDefects;
  private final int openDefects;
  private final int closedDefects;
  private final boolean defectCriteriaConfigured;
  private final int defectCriteriaPassed;
  private final int defectCriteriaTotal;
  private final int executionTarget;

  private OctaneTestManagementAnalytics(
      String startedAt,
      long durationMillis,
      List<TimelinePoint> points,
      List<FailureCategory> failureCategories,
      List<TesterSummary> topVolumeTesters,
      List<TesterSummary> topDefectTesters,
      int totalDefects,
      int openDefects,
      int closedDefects,
      boolean defectCriteriaConfigured,
      int defectCriteriaPassed,
      int defectCriteriaTotal,
      int executionTarget) {
    this.startedAt = Util.trimToEmpty(startedAt);
    this.durationMillis = Math.max(1L, durationMillis);
    this.points = points == null ? List.of() : List.copyOf(points);
    this.failureCategories =
        failureCategories == null ? emptyCategories() : List.copyOf(failureCategories);
    this.topVolumeTesters = topVolumeTesters == null ? List.of() : List.copyOf(topVolumeTesters);
    this.topDefectTesters = topDefectTesters == null ? List.of() : List.copyOf(topDefectTesters);
    this.totalDefects = Math.max(0, totalDefects);
    this.openDefects = Math.max(0, openDefects);
    this.closedDefects = Math.max(0, closedDefects);
    this.defectCriteriaConfigured = defectCriteriaConfigured;
    this.defectCriteriaPassed = Math.max(0, defectCriteriaPassed);
    this.defectCriteriaTotal = Math.max(0, defectCriteriaTotal);
    this.executionTarget = Math.min(100, Math.max(0, executionTarget));
  }

  public static OctaneTestManagementAnalytics empty(String startedAt, long durationMillis) {
    return new OctaneTestManagementAnalytics(
        startedAt,
        durationMillis,
        List.of(TimelinePoint.empty()),
        emptyCategories(),
        List.of(),
        List.of(),
        0,
        0,
        0,
        false,
        0,
        0,
        GateRequest.DEFAULT_BASE_EXECUTION_FIGURE);
  }

  public static OctaneTestManagementAnalytics fromResult(
      String startedAt,
      long durationMillis,
      GateResult result,
      StatusClassifier classifier,
      int executionTarget) {
    if (result == null) {
      return empty(startedAt, durationMillis).withExecutionTarget(executionTarget);
    }

    List<RunRecord> runs = uniqueProjectRuns(result);
    TimelinePoint point =
        TimelinePoint.fromRuns(elapsedMillis(startedAt, result.getPolledAt()), runs, classifier);
    List<DefectRecord> defects = dedupeDefects(result.getDefects());
    List<FailureCategory> categories =
        categorizeDefects(defects, result.getDefectMetrics().getConfiguredGroups());
    Map<String, TesterAccumulator> testerAccumulators = testerAccumulators(runs, classifier);
    applyTesterDefects(testerAccumulators, runs, defects);

    List<TesterSummary> volumeTesters =
        testerAccumulators.values().stream()
            .map(accumulator -> accumulator.toSummary())
            .sorted(
                Comparator.comparingInt((TesterSummary tester) -> tester.getTotal())
                    .reversed()
                    .thenComparing(tester -> tester.getName(), String.CASE_INSENSITIVE_ORDER))
            .limit(TOP_TESTER_LIMIT)
            .toList();
    List<TesterSummary> defectTesters =
        testerAccumulators.values().stream()
            .map(accumulator -> accumulator.toSummary())
            .filter(tester -> tester.getOpenDefects() > 0)
            .sorted(
                Comparator.comparingInt((TesterSummary tester) -> tester.getOpenDefects())
                    .reversed()
                    .thenComparing(tester -> tester.getName(), String.CASE_INSENSITIVE_ORDER))
            .limit(TOP_TESTER_LIMIT)
            .toList();

    int open = 0;
    for (DefectRecord defect : defects) {
      if (defect.isOpen()) {
        open++;
      }
    }
    int totalCriteria = 0;
    int passedCriteria = 0;
    for (CriteriaComparisonEvaluation comparison :
        result.getCriteriaEvaluation().getComparisons()) {
      if (comparison.getMetricReference().toLowerCase(Locale.ROOT).startsWith("defects.")) {
        totalCriteria++;
        if (comparison.isPassed()) {
          passedCriteria++;
        }
      }
    }

    return new OctaneTestManagementAnalytics(
        startedAt,
        durationMillis,
        List.of(point),
        categories,
        volumeTesters,
        defectTesters,
        defects.size(),
        open,
        Math.max(0, defects.size() - open),
        totalCriteria > 0,
        passedCriteria,
        totalCriteria,
        executionTarget);
  }

  public OctaneTestManagementAnalytics appendLatest(OctaneTestManagementAnalytics current) {
    if (current == null) {
      return this;
    }
    List<TimelinePoint> merged = new ArrayList<>(getPoints());
    TimelinePoint latest = current.latestPoint();
    if (!merged.isEmpty()
        && merged.get(merged.size() - 1).getElapsedMillis() == latest.getElapsedMillis()) {
      merged.set(merged.size() - 1, latest);
    } else {
      merged.add(latest);
    }
    while (merged.size() > MAX_POINTS) {
      merged.remove(1);
    }
    return new OctaneTestManagementAnalytics(
        current.getStartedAt(),
        current.getDurationMillis(),
        merged,
        current.getFailureCategories(),
        current.getTopVolumeTesters(),
        current.getTopDefectTesters(),
        current.getTotalDefects(),
        current.getOpenDefects(),
        current.getClosedDefects(),
        current.isDefectCriteriaConfigured(),
        current.getDefectCriteriaPassed(),
        current.getDefectCriteriaTotal(),
        current.getExecutionTarget());
  }

  public OctaneTestManagementAnalytics withExecutionTarget(int target) {
    return new OctaneTestManagementAnalytics(
        startedAt,
        durationMillis,
        points,
        failureCategories,
        topVolumeTesters,
        topDefectTesters,
        totalDefects,
        openDefects,
        closedDefects,
        defectCriteriaConfigured,
        defectCriteriaPassed,
        defectCriteriaTotal,
        target);
  }

  public String getStartedAt() {
    return startedAt;
  }

  public long getDurationMillis() {
    return durationMillis;
  }

  public List<TimelinePoint> getPoints() {
    return points == null || points.isEmpty() ? List.of(TimelinePoint.empty()) : points;
  }

  public List<FailureCategory> getFailureCategories() {
    return failureCategories == null ? emptyCategories() : failureCategories;
  }

  public List<TesterSummary> getTopVolumeTesters() {
    return topVolumeTesters == null ? List.of() : topVolumeTesters;
  }

  public List<TesterSummary> getTopDefectTesters() {
    return topDefectTesters == null ? List.of() : topDefectTesters;
  }

  public int getTotalDefects() {
    return totalDefects;
  }

  public int getOpenDefects() {
    return openDefects;
  }

  public int getClosedDefects() {
    return closedDefects;
  }

  public boolean isDefectCriteriaConfigured() {
    return defectCriteriaConfigured;
  }

  public int getDefectCriteriaPassed() {
    return defectCriteriaPassed;
  }

  public int getDefectCriteriaTotal() {
    return defectCriteriaTotal;
  }

  public int getExecutionTarget() {
    return executionTarget;
  }

  public boolean isDefectCompliant() {
    return getDefectLoggingCompliance().isCompliant();
  }

  public int getExpectedOpenDefects() {
    return getDefectLoggingCompliance().getExpectedDefects();
  }

  public int getOpenDefectVariance() {
    return getDefectLoggingCompliance().getVariance();
  }

  public DefectLoggingCompliance getDefectLoggingCompliance() {
    TimelinePoint latest = latestPoint();
    return DefectLoggingCompliance.from(latest.getBlocked(), latest.getFailed(), openDefects);
  }

  public List<MetricQuadrant> getMetricQuadrants() {
    int slowTesters = 0;
    for (TesterSummary tester : getTopVolumeTesters()) {
      if (tester.getExecutionRate() < executionTarget) {
        slowTesters++;
      }
    }
    int expectedOpenDefects = getExpectedOpenDefects();
    int topTesterOpenDefects =
        topDefectTesters.stream().mapToInt(tester -> tester.getOpenDefects()).sum();
    return List.of(
        defectComplianceMetric(),
        new MetricQuadrant(
            "open-closed",
            "Open vs Closed",
            openDefects + " open",
            closedDefects + " closed",
            openDefects == 0 && closedDefects == 0
                ? "neutral"
                : thresholdTone(openDefects, totalDefects),
            List.of()),
        new MetricQuadrant(
            "tester-volume",
            "Top Testers by Volume",
            topVolumeTesters.size() + " testers",
            slowTesters == 0
                ? "On pace against " + executionTarget + "% execution"
                : slowTesters + " below " + executionTarget + "% execution",
            topVolumeTesters.isEmpty()
                ? "neutral"
                : thresholdTone(slowTesters, topVolumeTesters.size()),
            testerItems(topVolumeTesters, false)),
        new MetricQuadrant(
            "tester-defects",
            "Top Testers by Open Defects",
            topTesterOpenDefects + " open",
            topDefectTesters.isEmpty() ? "No tester-linked open defects" : "Highest open workload",
            topDefectTesters.isEmpty()
                ? "neutral"
                : thresholdTone(topTesterOpenDefects, expectedOpenDefects),
            testerItems(topDefectTesters, true)));
  }

  private MetricQuadrant defectComplianceMetric() {
    DefectLoggingCompliance compliance = getDefectLoggingCompliance();
    int expected = compliance.getExpectedDefects();
    int variance = compliance.getVariance();
    String value;
    String tone;
    if (compliance.hasNoOpenDefectsExpected()) {
      value = "No open defects";
      tone = "neutral";
    } else if (compliance.isCompliant()) {
      value = "Compliant";
      tone = "good";
    } else if (compliance.getStatus() == DefectLoggingCompliance.Status.SURPLUS) {
      value = variance + " surplus";
      tone = thresholdTone(Math.abs(variance), Math.max(expected, openDefects));
    } else {
      value = Math.abs(variance) + " under-reported";
      tone = thresholdTone(Math.abs(variance), Math.max(expected, openDefects));
    }
    return new MetricQuadrant(
        "defect-compliance",
        "Defect Compliance",
        value,
        expected + " expected | " + openDefects + " open",
        tone,
        List.of());
  }

  private static String thresholdTone(int affected, int baseline) {
    if (affected <= 0) {
      return "good";
    }
    if (baseline <= 0) {
      return "bad";
    }
    double affectedPercent = affected * 100.0 / baseline;
    return affectedPercent > ACTION_THRESHOLD_PERCENT ? "bad" : "warning";
  }

  public Map<String, Object> toMap() {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("startedAt", startedAt);
    values.put("durationMillis", durationMillis);
    values.put("totalDefects", totalDefects);
    values.put("openDefects", openDefects);
    values.put("closedDefects", closedDefects);
    values.put("expectedOpenDefects", getExpectedOpenDefects());
    values.put("openDefectVariance", getOpenDefectVariance());
    values.put("executionTarget", executionTarget);
    values.put("defectCompliant", isDefectCompliant());
    values.put("colors", colors());
    values.put("points", getPoints().stream().map(point -> point.toMap()).toList());
    values.put(
        "executionIntervals",
        getExecutionIntervals().stream().map(interval -> interval.toMap()).toList());
    values.put(
        "failureCategories",
        getFailureCategories().stream().map(category -> category.toMap()).toList());
    values.put("metrics", getMetricQuadrants().stream().map(quadrant -> quadrant.toMap()).toList());
    return values;
  }

  private TimelinePoint latestPoint() {
    List<TimelinePoint> values = getPoints();
    return values.get(values.size() - 1);
  }

  private static long elapsedMillis(String startedAt, Instant polledAt) {
    try {
      return Math.max(0L, Duration.between(Instant.parse(startedAt), polledAt).toMillis());
    } catch (RuntimeException e) {
      return 0L;
    }
  }

  private static List<RunRecord> uniqueProjectRuns(GateResult result) {
    Map<String, RunRecord> values = new LinkedHashMap<>();
    addRuns(values, "regression", result.getRuns());
    for (Map.Entry<String, GateScopeResult> entry : result.getScopedResults().entrySet()) {
      if (entry.getValue().isActive()) {
        addRuns(values, "scope-" + entry.getKey(), entry.getValue().getRuns());
      }
    }
    return List.copyOf(values.values());
  }

  private static void addRuns(Map<String, RunRecord> values, String source, List<RunRecord> runs) {
    for (int index = 0; index < runs.size(); index++) {
      RunRecord run = runs.get(index);
      String key = Util.isBlank(run.getId()) ? source + "-anonymous-" + index : run.getId();
      values.putIfAbsent(key, run);
    }
  }

  private static List<DefectRecord> dedupeDefects(List<DefectRecord> defects) {
    Map<String, DefectRecord> values = new LinkedHashMap<>();
    if (defects != null) {
      for (int index = 0; index < defects.size(); index++) {
        DefectRecord defect = defects.get(index);
        if (defect == null) {
          continue;
        }
        String key = Util.isBlank(defect.getId()) ? "anonymous-" + index : defect.getId();
        values.put(key, defect);
      }
    }
    return List.copyOf(values.values());
  }

  private static List<FailureCategory> categorizeDefects(
      List<DefectRecord> defects, List<OctaneDefectGroup> configuredGroups) {
    if (defects.isEmpty()) {
      return List.of();
    }
    Map<String, SeverityPresentation> severityPresentations =
        severityPresentations(configuredGroups);
    return failureCategories(clusterDocuments(defectDocuments(defects)), severityPresentations);
  }

  private static List<DefectDocument> defectDocuments(List<DefectRecord> defects) {
    List<Map<String, Integer>> tokenCounts = new ArrayList<>();
    Map<String, Integer> documentFrequencies = new LinkedHashMap<>();
    for (DefectRecord defect : defects) {
      Map<String, Integer> counts = clusterTokenCounts(defect);
      tokenCounts.add(counts);
      for (String token : counts.keySet()) {
        documentFrequencies.put(token, documentFrequencies.getOrDefault(token, 0) + 1);
      }
    }

    List<DefectDocument> documents = new ArrayList<>();
    for (int index = 0; index < defects.size(); index++) {
      documents.add(
          new DefectDocument(
              defects.get(index),
              tfIdfVector(tokenCounts.get(index), documentFrequencies, defects.size())));
    }
    return documents;
  }

  private static List<DynamicCluster> clusterDocuments(List<DefectDocument> documents) {
    List<DynamicCluster> clusters = new ArrayList<>();
    for (DefectDocument document : documents) {
      ClusterMatch match = closestCluster(document, clusters);
      if (match.requiresNewCluster(clusters.size())) {
        DynamicCluster cluster = new DynamicCluster();
        cluster.add(document);
        clusters.add(cluster);
      } else {
        match.cluster().add(document);
      }
    }
    return clusters;
  }

  private static ClusterMatch closestCluster(
      DefectDocument document, List<DynamicCluster> clusters) {
    DynamicCluster closest = null;
    double closestSimilarity = -1.0;
    for (DynamicCluster cluster : clusters) {
      double similarity = cosineSimilarity(document.vector, cluster.centroid());
      if (similarity > closestSimilarity) {
        closest = cluster;
        closestSimilarity = similarity;
      }
    }
    return new ClusterMatch(closest, closestSimilarity);
  }

  private static List<FailureCategory> failureCategories(
      List<DynamicCluster> clusters, Map<String, SeverityPresentation> severityPresentations) {
    Map<String, Integer> keyOccurrences = new LinkedHashMap<>();
    List<FailureCategory> categories = new ArrayList<>();
    for (DynamicCluster cluster : clusters) {
      String label = cluster.label();
      String baseKey = categoryKey(label);
      int occurrence = keyOccurrences.getOrDefault(baseKey, 0) + 1;
      keyOccurrences.put(baseKey, occurrence);
      String key = occurrence == 1 ? baseKey : baseKey + "-" + occurrence;
      List<DefectDetail> details = new ArrayList<>();
      for (DefectDocument document : cluster.documents) {
        details.add(DefectDetail.fromDefect(document.defect, key, severityPresentations));
      }
      categories.add(new FailureCategory(key, label, details));
    }
    return List.copyOf(categories);
  }

  private static Map<String, SeverityPresentation> severityPresentations(
      List<OctaneDefectGroup> configuredGroups) {
    Map<String, SeverityPresentation> presentations = new LinkedHashMap<>();
    if (configuredGroups == null) {
      return presentations;
    }
    for (OctaneDefectGroup group : configuredGroups) {
      if (group == null) {
        continue;
      }
      String label = displayDefectGroupName(group.getName());
      if (label.isEmpty()) {
        continue;
      }
      String colorSeverity =
          group.getNormalizedTypes().stream()
              .map(type -> DefectDetail.canonicalSeverity(type))
              .min(Comparator.comparingInt(severity -> DefectDetail.severityRank(severity)))
              .orElse("Unspecified");
      int sortRank =
          group.getNormalizedTypes().stream()
              .map(type -> DefectDetail.canonicalSeverity(type))
              .mapToInt(severity -> DefectDetail.sortingSeverityRank(severity))
              .min()
              .orElse(DefectDetail.sortingSeverityRank("Unspecified"));
      SeverityPresentation presentation = new SeverityPresentation(label, colorSeverity, sortRank);
      for (String type : group.getNormalizedTypes()) {
        presentations.putIfAbsent(type, presentation);
      }
    }
    return presentations;
  }

  private static String displayDefectGroupName(String value) {
    String normalized =
        Util.trimToEmpty(value)
            .replaceAll("([a-z])([A-Z])", "$1 $2")
            .replace('-', ' ')
            .replace('_', ' ');
    if (normalized.isEmpty()) {
      return "";
    }
    List<String> words = new ArrayList<>();
    for (String word : normalized.split("\\s+")) {
      if (!word.isEmpty()) {
        words.add(
            word.substring(0, 1).toUpperCase(Locale.ROOT)
                + word.substring(1).toLowerCase(Locale.ROOT));
      }
    }
    return String.join(" ", words);
  }

  private static Map<String, Integer> clusterTokenCounts(DefectRecord defect) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    String signal = defect.getDisplayDescription().toLowerCase(Locale.ROOT);
    Matcher matcher = CLUSTER_TOKEN_PATTERN.matcher(signal);
    while (matcher.find()) {
      String token = normalizeClusterToken(matcher.group());
      if (!token.isEmpty() && !CLUSTER_STOP_WORDS.contains(token)) {
        counts.put(token, counts.getOrDefault(token, 0) + 1);
      }
    }
    if (counts.isEmpty()) {
      counts.put("other", 1);
    }
    return counts;
  }

  private static String normalizeClusterToken(String value) {
    String token = value.replace('.', '-').replace('_', '-');
    if (token.matches(".*\\d.*")) {
      return token;
    }
    if (token.endsWith("ies") && token.length() > 4) {
      return token.substring(0, token.length() - 3) + "y";
    }
    if (token.endsWith("ing") && token.length() > 5) {
      return token.substring(0, token.length() - 3);
    }
    if (token.endsWith("ed") && token.length() > 4) {
      return token.substring(0, token.length() - 2);
    }
    if (token.endsWith("s") && token.length() > 3) {
      return token.substring(0, token.length() - 1);
    }
    return token;
  }

  private static Map<String, Double> tfIdfVector(
      Map<String, Integer> counts, Map<String, Integer> documentFrequencies, int documentCount) {
    Map<String, Double> vector = new LinkedHashMap<>();
    int totalTerms = counts.values().stream().mapToInt(value -> value).sum();
    for (Map.Entry<String, Integer> entry : counts.entrySet()) {
      double termFrequency = entry.getValue() / (double) Math.max(1, totalTerms);
      double inverseDocumentFrequency =
          Math.log(
                  (documentCount + 1.0)
                      / (documentFrequencies.getOrDefault(entry.getKey(), 0) + 1.0))
              + 1.0;
      vector.put(entry.getKey(), termFrequency * inverseDocumentFrequency);
    }
    return vector;
  }

  private static double cosineSimilarity(Map<String, Double> left, Map<String, Double> right) {
    double dotProduct = 0.0;
    double leftMagnitude = 0.0;
    double rightMagnitude = 0.0;
    for (double weight : left.values()) {
      leftMagnitude += weight * weight;
    }
    for (Map.Entry<String, Double> entry : right.entrySet()) {
      double weight = entry.getValue();
      rightMagnitude += weight * weight;
      dotProduct += left.getOrDefault(entry.getKey(), 0.0) * weight;
    }
    if (leftMagnitude == 0.0 || rightMagnitude == 0.0) {
      return 0.0;
    }
    return dotProduct / (Math.sqrt(leftMagnitude) * Math.sqrt(rightMagnitude));
  }

  private static String categoryKey(String label) {
    String key =
        label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    return key.isEmpty() ? "other-failures" : key;
  }

  private static String displayClusterToken(String token) {
    if ("other".equals(token)) {
      return "Other";
    }
    StringBuilder label = new StringBuilder();
    for (String part : token.split("-")) {
      if (part.isEmpty()) {
        continue;
      }
      if (label.length() > 0) {
        label.append(' ');
      }
      label.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
    }
    return label.toString();
  }

  private static Map<String, TesterAccumulator> testerAccumulators(
      List<RunRecord> runs, StatusClassifier classifier) {
    Map<String, TesterAccumulator> testers = new LinkedHashMap<>();
    for (RunRecord run : runs) {
      String name = Util.isBlank(run.getSuiteOwnerName()) ? "Unassigned" : run.getSuiteOwnerName();
      String key = name.toLowerCase(Locale.ROOT);
      testers.computeIfAbsent(key, ignored -> new TesterAccumulator(name)).addRun(run, classifier);
    }
    return testers;
  }

  private static void applyTesterDefects(
      Map<String, TesterAccumulator> testers, List<RunRecord> runs, List<DefectRecord> defects) {
    Map<String, Set<String>> testerKeysByRun = new LinkedHashMap<>();
    Map<String, Set<String>> testerKeysByTest = new LinkedHashMap<>();
    for (RunRecord run : runs) {
      indexTesterRun(run, testerKeysByRun, testerKeysByTest);
    }
    for (DefectRecord defect : defects) {
      applyTesterDefect(
          testers, defect, matchingTesterKeys(testers, defect, testerKeysByRun, testerKeysByTest));
    }
  }

  private static void indexTesterRun(
      RunRecord run,
      Map<String, Set<String>> testerKeysByRun,
      Map<String, Set<String>> testerKeysByTest) {
    String name = Util.isBlank(run.getSuiteOwnerName()) ? "Unassigned" : run.getSuiteOwnerName();
    String testerKey = name.toLowerCase(Locale.ROOT);
    addTesterIndex(testerKeysByRun, run.getId(), testerKey);
    addTesterIndex(testerKeysByTest, run.getTestId(), testerKey);
  }

  private static void addTesterIndex(Map<String, Set<String>> index, String id, String testerKey) {
    if (!Util.isBlank(id)) {
      index.computeIfAbsent(id, ignored -> new LinkedHashSet<>()).add(testerKey);
    }
  }

  private static Set<String> matchingTesterKeys(
      Map<String, TesterAccumulator> testers,
      DefectRecord defect,
      Map<String, Set<String>> testerKeysByRun,
      Map<String, Set<String>> testerKeysByTest) {
    Set<String> matches =
        new LinkedHashSet<>(testerKeysByRun.getOrDefault(defect.getRunId(), Set.of()));
    if (matches.isEmpty()) {
      matches.addAll(testerKeysByTest.getOrDefault(defect.getTestId(), Set.of()));
    }
    if (matches.isEmpty() && testers.size() == 1) {
      matches.add(testers.keySet().iterator().next());
    }
    return matches;
  }

  private static void applyTesterDefect(
      Map<String, TesterAccumulator> testers, DefectRecord defect, Set<String> matchingTesterKeys) {
    for (String testerKey : matchingTesterKeys) {
      TesterAccumulator tester = testers.get(testerKey);
      if (tester != null) {
        tester.addDefect(defect);
      }
    }
  }

  private static List<Map<String, Object>> testerItems(
      List<TesterSummary> testers, boolean defects) {
    List<Map<String, Object>> values = new ArrayList<>();
    for (TesterSummary tester : testers) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("label", tester.getName());
      if (defects) {
        String openDefectsText = tester.getOpenDefects() + " open";
        item.put("value", openDefectsText);
        item.put("primaryValue", openDefectsText);
        item.put("secondaryValue", "");
      } else {
        String testCountText = tester.getTotal() + " tests";
        item.put("value", testCountText + " | " + tester.getExecutionRateText());
        item.put("primaryValue", testCountText);
        item.put("secondaryValue", tester.getExecutionRateText());
      }
      values.add(item);
    }
    return values;
  }

  private static Map<String, String> colors() {
    Map<String, String> values = new LinkedHashMap<>();
    values.put("planned", PLANNED_COLOR);
    values.put("inProgress", IN_PROGRESS_COLOR);
    values.put("skipped", SKIPPED_COLOR);
    values.put("blocked", BLOCKED_COLOR);
    values.put("failed", FAILED_COLOR);
    values.put("passed", PASSED_COLOR);
    values.put("executed", IN_PROGRESS_COLOR);
    values.put("open", OPEN_COLOR);
    values.put("closed", CLOSED_COLOR);
    return values;
  }

  private static List<FailureCategory> emptyCategories() {
    return List.of();
  }

  public List<ExecutionInterval> getExecutionIntervals() {
    List<TimelinePoint> sortedPoints = new ArrayList<>(getPoints());
    sortedPoints.sort(Comparator.comparingLong(point -> point.getElapsedMillis()));
    ExecutionIntervalAccumulator[] intervals =
        new ExecutionIntervalAccumulator[EXECUTION_INTERVAL_COUNT];
    for (int index = 0; index < intervals.length; index++) {
      intervals[index] = new ExecutionIntervalAccumulator();
    }

    int previousPassed = 0;
    int previousFailed = 0;
    int previousBlocked = 0;
    int previousSkipped = 0;
    for (TimelinePoint point : sortedPoints) {
      long elapsed = Math.min(durationMillis, Math.max(0L, point.getElapsedMillis()));
      int intervalIndex =
          elapsed <= 0L
              ? 0
              : (int)
                  Math.min(
                      EXECUTION_INTERVAL_COUNT - 1,
                      ((elapsed - 1L) * EXECUTION_INTERVAL_COUNT) / durationMillis);
      intervals[intervalIndex].passed += Math.max(0, point.getPassed() - previousPassed);
      intervals[intervalIndex].failed += Math.max(0, point.getFailed() - previousFailed);
      intervals[intervalIndex].blocked += Math.max(0, point.getBlocked() - previousBlocked);
      intervals[intervalIndex].skipped += Math.max(0, point.getSkipped() - previousSkipped);
      previousPassed = point.getPassed();
      previousFailed = point.getFailed();
      previousBlocked = point.getBlocked();
      previousSkipped = point.getSkipped();
    }

    List<ExecutionInterval> values = new ArrayList<>();
    for (int index = 0; index < intervals.length; index++) {
      long startMillis = durationMillis * index / EXECUTION_INTERVAL_COUNT;
      long endMillis = durationMillis * (index + 1L) / EXECUTION_INTERVAL_COUNT;
      values.add(intervals[index].toInterval(index, startMillis, endMillis));
    }
    return List.copyOf(values);
  }

  private static final class DefectDocument {
    private final DefectRecord defect;
    private final Map<String, Double> vector;

    private DefectDocument(DefectRecord defect, Map<String, Double> vector) {
      this.defect = defect;
      this.vector = Map.copyOf(vector);
    }
  }

  private record ClusterMatch(DynamicCluster cluster, double similarity) {
    private boolean requiresNewCluster(int clusterCount) {
      return cluster == null
          || (similarity < CLUSTER_SIMILARITY_THRESHOLD && clusterCount < MAX_FAILURE_CLUSTERS);
    }
  }

  private static final class DynamicCluster {
    private final List<DefectDocument> documents = new ArrayList<>();
    private final Map<String, Double> vectorTotals = new LinkedHashMap<>();

    private void add(DefectDocument document) {
      documents.add(document);
      for (Map.Entry<String, Double> entry : document.vector.entrySet()) {
        vectorTotals.put(
            entry.getKey(), vectorTotals.getOrDefault(entry.getKey(), 0.0) + entry.getValue());
      }
    }

    private Map<String, Double> centroid() {
      Map<String, Double> values = new LinkedHashMap<>();
      for (Map.Entry<String, Double> entry : vectorTotals.entrySet()) {
        values.put(entry.getKey(), entry.getValue() / Math.max(1, documents.size()));
      }
      return values;
    }

    private String label() {
      List<String> terms =
          vectorTotals.entrySet().stream()
              .sorted(
                  Map.Entry.<String, Double>comparingByValue()
                      .reversed()
                      .thenComparing(entry -> entry.getKey()))
              .limit(2)
              .map(entry -> entry.getKey())
              .toList();
      if (terms.isEmpty() || (terms.size() == 1 && "other".equals(terms.get(0)))) {
        return "Other Failures";
      }
      return terms.stream()
          .map(term -> displayClusterToken(term))
          .reduce((left, right) -> left + " " + right)
          .orElse("Other Failures");
    }
  }

  private static final class ExecutionIntervalAccumulator {
    private int passed;
    private int failed;
    private int blocked;
    private int skipped;

    private ExecutionInterval toInterval(int index, long startMillis, long endMillis) {
      return new ExecutionInterval(index, startMillis, endMillis, passed, failed, blocked, skipped);
    }
  }

  public static final class ExecutionInterval implements Serializable {
    private static final long serialVersionUID = 1L;

    private final int index;
    private final long startMillis;
    private final long endMillis;
    private final int passed;
    private final int failed;
    private final int blocked;
    private final int skipped;

    private ExecutionInterval(
        int index,
        long startMillis,
        long endMillis,
        int passed,
        int failed,
        int blocked,
        int skipped) {
      this.index = Math.max(0, index);
      this.startMillis = Math.max(0L, startMillis);
      this.endMillis = Math.max(this.startMillis, endMillis);
      this.passed = Math.max(0, passed);
      this.failed = Math.max(0, failed);
      this.blocked = Math.max(0, blocked);
      this.skipped = Math.max(0, skipped);
    }

    public int getIndex() {
      return index;
    }

    public long getStartMillis() {
      return startMillis;
    }

    public long getEndMillis() {
      return endMillis;
    }

    public int getPassed() {
      return passed;
    }

    public int getFailed() {
      return failed;
    }

    public int getBlocked() {
      return blocked;
    }

    public int getSkipped() {
      return skipped;
    }

    public int getTotal() {
      return passed + failed + blocked + skipped;
    }

    private Map<String, Object> toMap() {
      Map<String, Object> values = new LinkedHashMap<>();
      values.put("index", index);
      values.put("startMillis", startMillis);
      values.put("endMillis", endMillis);
      values.put("passed", passed);
      values.put("failed", failed);
      values.put("blocked", blocked);
      values.put("skipped", skipped);
      values.put("total", getTotal());
      return values;
    }
  }

  public static final class TimelinePoint implements Serializable {
    private static final long serialVersionUID = 1L;

    private final long elapsedMillis;
    private final int total;
    private final int planned;
    private final int inProgress;
    private final int skipped;
    private final int blocked;
    private final int failed;
    private final int passed;

    private TimelinePoint(
        long elapsedMillis,
        int total,
        int planned,
        int inProgress,
        int skipped,
        int blocked,
        int failed,
        int passed) {
      this.elapsedMillis = Math.max(0L, elapsedMillis);
      this.total = Math.max(0, total);
      this.planned = Math.max(0, planned);
      this.inProgress = Math.max(0, inProgress);
      this.skipped = Math.max(0, skipped);
      this.blocked = Math.max(0, blocked);
      this.failed = Math.max(0, failed);
      this.passed = Math.max(0, passed);
    }

    private static TimelinePoint empty() {
      return new TimelinePoint(0L, 0, 0, 0, 0, 0, 0, 0);
    }

    private static TimelinePoint fromRuns(
        long elapsedMillis, List<RunRecord> runs, StatusClassifier classifier) {
      int planned = 0;
      int inProgress = 0;
      int skipped = 0;
      int blocked = 0;
      int failed = 0;
      int passed = 0;
      for (RunRecord run : runs) {
        StatusClassifier.Outcome outcome = classifier.classify(run.getStatus());
        if (outcome == StatusClassifier.Outcome.PASSED) {
          passed++;
        } else if (outcome == StatusClassifier.Outcome.FAILED) {
          failed++;
        } else if (outcome == StatusClassifier.Outcome.BLOCKED) {
          blocked++;
        } else if (outcome == StatusClassifier.Outcome.NEUTRAL
            || outcome == StatusClassifier.Outcome.STOPPED) {
          skipped++;
        } else if (classifier.isActivelyRunning(run.getStatus())) {
          inProgress++;
        } else {
          planned++;
        }
      }
      return new TimelinePoint(
          elapsedMillis, runs.size(), planned, inProgress, skipped, blocked, failed, passed);
    }

    public long getElapsedMillis() {
      return elapsedMillis;
    }

    public int getTotal() {
      return total;
    }

    public int getPlanned() {
      return planned;
    }

    public int getInProgress() {
      return inProgress;
    }

    public int getSkipped() {
      return skipped;
    }

    public int getBlocked() {
      return blocked;
    }

    public int getFailed() {
      return failed;
    }

    public int getPassed() {
      return passed;
    }

    public int getExecuted() {
      return GateMetrics.executedCount(passed, failed, blocked);
    }

    private Map<String, Object> toMap() {
      Map<String, Object> values = new LinkedHashMap<>();
      values.put("elapsedMillis", elapsedMillis);
      values.put("total", total);
      values.put("planned", planned);
      values.put("inProgress", inProgress);
      values.put("skipped", skipped);
      values.put("blocked", blocked);
      values.put("failed", failed);
      values.put("passed", passed);
      values.put("executed", getExecuted());
      return values;
    }
  }

  public static final class FailureCategory implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String key;
    private final String label;
    private final List<DefectDetail> defects;

    private FailureCategory(String key, String label, List<DefectDetail> defects) {
      this.key = key;
      this.label = label;
      this.defects = List.copyOf(defects);
    }

    public String getKey() {
      return key;
    }

    public String getLabel() {
      return label;
    }

    public List<DefectDetail> getDefects() {
      return defects;
    }

    public int getOpenCount() {
      return (int) defects.stream().filter(defect -> defect.isOpen()).count();
    }

    public int getClosedCount() {
      return defects.size() - getOpenCount();
    }

    public String getHighestOpenSeverity() {
      return defects.stream()
          .filter(defect -> defect.isOpen())
          .map(defect -> defect.getSeverity())
          .min(Comparator.comparingInt(severity -> DefectDetail.severityRank(severity)))
          .orElse("Closed");
    }

    public String getOpenColor() {
      return getOpenCount() > 0 ? OPEN_COLOR : CLOSED_COLOR;
    }

    private Map<String, Object> toMap() {
      Map<String, Object> values = new LinkedHashMap<>();
      values.put("key", key);
      values.put("label", label);
      values.put("open", getOpenCount());
      values.put("closed", getClosedCount());
      values.put("highestOpenSeverity", getHighestOpenSeverity());
      values.put("openColor", getOpenColor());
      values.put("closedColor", CLOSED_COLOR);
      values.put("defects", defects.stream().map(defect -> defect.toMap()).toList());
      return values;
    }
  }

  private static final class SeverityPresentation {
    private final String label;
    private final String colorSeverity;
    private final int sortRank;

    private SeverityPresentation(String label, String colorSeverity, int sortRank) {
      this.label = label;
      this.colorSeverity = colorSeverity;
      this.sortRank = sortRank;
    }
  }

  public static final class DefectDetail implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String id;
    private final String description;
    private final String severity;
    private final String severityLabel;
    private final String severityColorKey;
    private final int severitySortRank;
    private final String status;
    private final String category;
    private final boolean open;

    private DefectDetail(
        String id,
        String description,
        String severity,
        String severityLabel,
        String severityColorKey,
        int severitySortRank,
        String status,
        String category,
        boolean open) {
      this.id = id;
      this.description = description;
      this.severity = severity;
      this.severityLabel = severityLabel;
      this.severityColorKey = severityColorKey;
      this.severitySortRank = severitySortRank;
      this.status = status;
      this.category = category;
      this.open = open;
    }

    private static DefectDetail fromDefect(
        DefectRecord defect,
        String category,
        Map<String, SeverityPresentation> severityPresentations) {
      boolean open = defect.isOpen();
      String severity = canonicalSeverity(defect.getSeverity());
      String normalizedSeverity = OctaneDefectSeveritySummary.normalizeOpenType(severity);
      SeverityPresentation presentation =
          severityPresentations.getOrDefault(
              normalizedSeverity,
              new SeverityPresentation(severity, severity, sortingSeverityRank(severity)));
      String displayDescription = defect.getDisplayDescription();
      return new DefectDetail(
          defect.getId(),
          Util.isBlank(displayDescription) ? "Defect " + defect.getId() : displayDescription,
          severity,
          presentation.label,
          presentation.colorSeverity,
          presentation.sortRank,
          open ? "Open" : "Closed",
          category,
          open);
    }

    public String getId() {
      return id;
    }

    public String getDescription() {
      return description;
    }

    public String getSeverity() {
      return severity;
    }

    public String getSeverityLabel() {
      return Util.isBlank(severityLabel) ? severity : severityLabel;
    }

    public String getSeverityColorKey() {
      return Util.isBlank(severityColorKey) ? severity : severityColorKey;
    }

    public int getSeveritySortRank() {
      return severitySortRank;
    }

    public String getStatus() {
      return status;
    }

    public String getSeverityColor() {
      return severityColor(getSeverityColorKey());
    }

    public String getSeverityTextColor() {
      return severityTextColor(severity);
    }

    public String getStatusColor() {
      return open ? OPEN_COLOR : CLOSED_COLOR;
    }

    public boolean isOpen() {
      return open;
    }

    private Map<String, Object> toMap() {
      Map<String, Object> values = new LinkedHashMap<>();
      values.put("id", id);
      values.put("description", description);
      values.put("severity", severity);
      values.put("severityLabel", getSeverityLabel());
      values.put("severityColorKey", getSeverityColorKey());
      values.put("severitySortRank", getSeveritySortRank());
      values.put("status", status);
      values.put("phase", status);
      values.put("category", category);
      values.put("open", open);
      values.put("severityColor", getSeverityColor());
      values.put("severityTextColor", getSeverityTextColor());
      values.put("statusColor", getStatusColor());
      values.put("statusTextColor", "#000000");
      return values;
    }

    private static String canonicalSeverity(String severity) {
      String normalized =
          Util.normalizeStatus(severity).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
      if (normalized.contains("critical")) {
        return "Critical";
      }
      if (normalized.contains("veryhigh")) {
        return "Very High";
      }
      if (normalized.contains("high")) {
        return "High";
      }
      if (normalized.contains("medium")) {
        return "Medium";
      }
      if (normalized.contains("low")) {
        return "Low";
      }
      return "Unspecified";
    }

    private static String severityColor(String severity) {
      switch (canonicalSeverity(severity)) {
        case "Critical":
          return "#FF3B30";
        case "Very High":
          return "#FFCC00";
        case "High":
          return "#FF9500";
        case "Medium":
          return "#AF52DE";
        case "Low":
          return "#5AC8FA";
        default:
          return "#8E8E93";
      }
    }

    private static String severityTextColor(String severity) {
      return "#000000";
    }

    private static int severityRank(String severity) {
      switch (canonicalSeverity(severity)) {
        case "Critical":
          return 1;
        case "Very High":
          return 2;
        case "High":
          return 3;
        case "Medium":
          return 4;
        case "Low":
          return 5;
        default:
          return 6;
      }
    }

    private static int sortingSeverityRank(String severity) {
      switch (canonicalSeverity(severity)) {
        case "Critical":
          return 1;
        case "Very High":
          return 2;
        case "High":
          return 3;
        case "Low":
          return 4;
        case "Medium":
          return 5;
        default:
          return 6;
      }
    }
  }

  public static final class TesterSummary implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String name;
    private final int total;
    private final int executed;
    private final int openDefects;

    private TesterSummary(String name, int total, int executed, int openDefects) {
      this.name = name;
      this.total = Math.max(0, total);
      this.executed = Math.max(0, executed);
      this.openDefects = Math.max(0, openDefects);
    }

    public String getName() {
      return name;
    }

    public int getTotal() {
      return total;
    }

    public int getOpenDefects() {
      return openDefects;
    }

    public double getExecutionRate() {
      return GateMetrics.executionRate(executed, total);
    }

    public String getExecutionRateText() {
      return Util.formatPercentage(getExecutionRate(), 0);
    }
  }

  public static final class MetricQuadrant implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String key;
    private final String title;
    private final String value;
    private final String detail;
    private final String tone;
    private final List<Map<String, Object>> items;

    private MetricQuadrant(
        String key,
        String title,
        String value,
        String detail,
        String tone,
        List<Map<String, Object>> items) {
      this.key = key;
      this.title = title;
      this.value = value;
      this.detail = detail;
      this.tone = tone;
      this.items = List.copyOf(items);
    }

    public String getKey() {
      return key;
    }

    public String getTitle() {
      return title;
    }

    public String getValue() {
      return value;
    }

    public String getDetail() {
      return detail;
    }

    public String getTone() {
      return tone;
    }

    public List<Map<String, Object>> getItems() {
      return items;
    }

    private Map<String, Object> toMap() {
      Map<String, Object> values = new LinkedHashMap<>();
      values.put("key", key);
      values.put("title", title);
      values.put("value", value);
      values.put("detail", detail);
      values.put("tone", tone);
      values.put("items", items);
      return values;
    }
  }

  private static final class TesterAccumulator {
    private final String name;
    private int total;
    private int executed;
    private final Set<String> openDefectIds = new LinkedHashSet<>();

    private TesterAccumulator(String name) {
      this.name = name;
    }

    private void addRun(RunRecord run, StatusClassifier classifier) {
      total++;
      StatusClassifier.Outcome outcome = classifier.classify(run.getStatus());
      if (outcome == StatusClassifier.Outcome.PASSED
          || outcome == StatusClassifier.Outcome.FAILED
          || outcome == StatusClassifier.Outcome.BLOCKED) {
        executed++;
      }
    }

    private void addDefect(DefectRecord defect) {
      if (defect.isOpen()) {
        String key = defect.getId();
        if (Util.isBlank(key)) {
          key = defect.getRunId() + "|" + defect.getTestId() + "|" + defect.getName();
        }
        openDefectIds.add(key);
      }
    }

    private TesterSummary toSummary() {
      return new TesterSummary(name, total, executed, openDefectIds.size());
    }
  }
}
