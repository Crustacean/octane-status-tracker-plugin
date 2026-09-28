package io.jenkins.plugins.octanesuitegatebyembiti.models;

import io.jenkins.plugins.octanesuitegatebyembiti.entities.DefectRecord;
import io.jenkins.plugins.octanesuitegatebyembiti.entities.RunRecord;
import io.jenkins.plugins.octanesuitegatebyembiti.utils.Util;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class OctaneRiskHeatMapBuilder {

  public OctaneRiskHeatMap build(
      String workspaceId,
      Map<String, List<RunRecord>> suiteRuns,
      List<DefectRecord> defects,
      StatusClassifier classifier) {
    if (suiteRuns == null || suiteRuns.isEmpty()) {
      return OctaneRiskHeatMap.empty(workspaceId);
    }

    Map<String, List<DefectRecord>> defectsByRunId = indexDefectsByRunId(defects);
    Map<String, List<DefectRecord>> defectsByTestId = indexDefectsByTestId(defects);
    OctaneDefectSeveritySummary defectSeveritySummary =
        OctaneDefectSeveritySummary.fromDefects(defects);
    NodeAccumulator root = new NodeAccumulator("root", "Risk Heat Map");
    int fetchedDefectCount = defects == null ? 0 : defects.size();
    DefectCounts counts = new DefectCounts();
    Set<String> processedDefectIds = new LinkedHashSet<>();

    addSuiteRuns(
        workspaceId,
        suiteRuns,
        classifier,
        defectsByRunId,
        defectsByTestId,
        root,
        processedDefectIds,
        counts);
    addUnlinkedDefects(workspaceId, defects, root, processedDefectIds, counts);

    if (root.children.isEmpty()) {
      return OctaneRiskHeatMap.empty(workspaceId);
    }
    return OctaneRiskHeatMap.of(
        root.toNode(),
        fetchedDefectCount,
        counts.linked,
        counts.unlinkedOpen,
        counts.ignoredClosed,
        defectSeveritySummary);
  }

  private void addSuiteRuns(
      String workspaceId,
      Map<String, List<RunRecord>> suiteRuns,
      StatusClassifier classifier,
      Map<String, List<DefectRecord>> defectsByRunId,
      Map<String, List<DefectRecord>> defectsByTestId,
      NodeAccumulator root,
      Set<String> processedDefectIds,
      DefectCounts counts) {
    for (Map.Entry<String, List<RunRecord>> suiteEntry : suiteRuns.entrySet()) {
      for (RunRecord run : suiteEntry.getValue()) {
        NodeAccumulator test =
            addRunNode(
                workspaceId,
                suiteEntry.getKey(),
                run,
                classifier,
                defectsByRunId,
                defectsByTestId,
                root);
        addLinkedDefects(run, test, defectsByRunId, defectsByTestId, processedDefectIds, counts);
      }
    }
  }

  private NodeAccumulator addRunNode(
      String workspaceId,
      String suiteRunId,
      RunRecord run,
      StatusClassifier classifier,
      Map<String, List<DefectRecord>> defectsByRunId,
      Map<String, List<DefectRecord>> defectsByTestId,
      NodeAccumulator root) {
    String projectLabel = projectLabel(workspaceId, run, defectsByRunId, defectsByTestId);
    NodeAccumulator project = root.child("project", projectLabel);
    NodeAccumulator suite = project.child("suite", "Suite " + suiteRunId);
    NodeAccumulator runner = suite.child("runner", assignedUserLabel(run));
    NodeAccumulator test = runner.child("test", testLabel(run));
    test.addStatus(classifier.classify(run.getStatus()));
    return test;
  }

  private void addLinkedDefects(
      RunRecord run,
      NodeAccumulator test,
      Map<String, List<DefectRecord>> defectsByRunId,
      Map<String, List<DefectRecord>> defectsByTestId,
      Set<String> processedDefectIds,
      DefectCounts counts) {
    Set<String> linkedDefectIds = new LinkedHashSet<>();
    for (DefectRecord defect : linkedDefects(run, defectsByRunId, defectsByTestId)) {
      if (!linkedDefectIds.add(defect.getId()) || !processedDefectIds.add(defect.getId())) {
        continue;
      }
      if (!defect.isOpen()) {
        counts.ignoredClosed++;
        continue;
      }
      counts.linked++;
      test.child("defect", defectLabel(defect)).addDefect(defect);
    }
  }

  private void addUnlinkedDefects(
      String workspaceId,
      List<DefectRecord> defects,
      NodeAccumulator root,
      Set<String> processedDefectIds,
      DefectCounts counts) {
    if (defects == null) {
      return;
    }
    for (DefectRecord defect : defects) {
      if (!processedDefectIds.add(defect.getId())) {
        continue;
      }
      if (!defect.isOpen()) {
        counts.ignoredClosed++;
        continue;
      }
      counts.unlinkedOpen++;
      addUnlinkedDefect(workspaceId, defect, root);
    }
  }

  private void addUnlinkedDefect(String workspaceId, DefectRecord defect, NodeAccumulator root) {
    NodeAccumulator project = root.child("project", fallbackProjectLabel(workspaceId, defect));
    NodeAccumulator suite = project.child("suite", "Linked defects without run metadata");
    NodeAccumulator runner = suite.child("runner", "Unassigned");
    NodeAccumulator test = runner.child("test", "Unlinked defect records");
    test.child("defect", defectLabel(defect)).addDefect(defect);
  }

  private Map<String, List<DefectRecord>> indexDefectsByRunId(List<DefectRecord> defects) {
    Map<String, List<DefectRecord>> values = new LinkedHashMap<>();
    if (defects == null) {
      return values;
    }
    for (DefectRecord defect : defects) {
      if (!Util.isBlank(defect.getRunId())) {
        values.computeIfAbsent(defect.getRunId(), ignored -> new ArrayList<>()).add(defect);
      }
    }
    return values;
  }

  private Map<String, List<DefectRecord>> indexDefectsByTestId(List<DefectRecord> defects) {
    Map<String, List<DefectRecord>> values = new LinkedHashMap<>();
    if (defects == null) {
      return values;
    }
    for (DefectRecord defect : defects) {
      if (!Util.isBlank(defect.getTestId())) {
        values.computeIfAbsent(defect.getTestId(), ignored -> new ArrayList<>()).add(defect);
      }
    }
    return values;
  }

  private List<DefectRecord> linkedDefects(
      RunRecord run,
      Map<String, List<DefectRecord>> defectsByRunId,
      Map<String, List<DefectRecord>> defectsByTestId) {
    List<DefectRecord> values = new ArrayList<>();
    values.addAll(defectsByRunId.getOrDefault(run.getId(), List.of()));
    if (!Util.isBlank(run.getTestId())) {
      values.addAll(defectsByTestId.getOrDefault(run.getTestId(), List.of()));
    }
    return values;
  }

  private String projectLabel(
      String workspaceId,
      RunRecord run,
      Map<String, List<DefectRecord>> defectsByRunId,
      Map<String, List<DefectRecord>> defectsByTestId) {
    if (!Util.isBlank(run.getProjectName())) {
      return run.getProjectName();
    }
    for (DefectRecord defect : linkedDefects(run, defectsByRunId, defectsByTestId)) {
      if (!Util.isBlank(defect.getProjectName())) {
        return defect.getProjectName();
      }
    }
    return Util.isBlank(workspaceId) ? "Workspace" : "Workspace " + workspaceId;
  }

  private String fallbackProjectLabel(String workspaceId, DefectRecord defect) {
    if (!Util.isBlank(defect.getProjectName())) {
      return defect.getProjectName();
    }
    return Util.isBlank(workspaceId) ? "Workspace" : "Workspace " + workspaceId;
  }

  private static final class DefectCounts {
    private int linked;
    private int unlinkedOpen;
    private int ignoredClosed;
  }

  private String assignedUserLabel(RunRecord run) {
    if (!Util.isBlank(run.getSuiteOwnerName())) {
      return run.getSuiteOwnerName();
    }
    return "Unassigned";
  }

  private String testLabel(RunRecord run) {
    if (!Util.isBlank(run.getTestName())) {
      return run.getTestName();
    }
    if (!Util.isBlank(run.getName())) {
      return run.getName();
    }
    return "Run " + run.getId();
  }

  private String defectLabel(DefectRecord defect) {
    if (!Util.isBlank(defect.getName())) {
      return defect.getName();
    }
    return "Defect " + defect.getId();
  }

  private int statusRisk(StatusClassifier.Outcome outcome) {
    if (outcome == StatusClassifier.Outcome.FAILED) {
      return 78;
    }
    if (outcome == StatusClassifier.Outcome.BLOCKED) {
      return 72;
    }
    if (outcome == StatusClassifier.Outcome.RUNNING) {
      return 20;
    }
    if (outcome == StatusClassifier.Outcome.NEUTRAL
        || outcome == StatusClassifier.Outcome.STOPPED) {
      return 12;
    }
    return 0;
  }

  private int defectRisk(DefectRecord defect) {
    String signal =
        (defect.getSeverity() + " " + defect.getPriority()).toLowerCase(java.util.Locale.ENGLISH);
    if (signal.contains("critical") || signal.contains("blocker") || signal.contains("urgent")) {
      return 95;
    }
    if (signal.contains("very high") || signal.contains("high")) {
      return 80;
    }
    if (signal.contains("medium") || signal.contains("major")) {
      return 58;
    }
    if (signal.contains("low") || signal.contains("minor")) {
      return 35;
    }
    return 45;
  }

  private final class NodeAccumulator {
    private final String type;
    private final String label;
    private final Map<String, NodeAccumulator> children = new LinkedHashMap<>();
    private final List<StatusClassifier.Outcome> statuses = new ArrayList<>();
    private final List<DefectRecord> defects = new ArrayList<>();

    private NodeAccumulator(String type, String label) {
      this.type = type;
      this.label = label;
    }

    private NodeAccumulator child(String type, String label) {
      String key = type + ":" + label;
      return children.computeIfAbsent(key, ignored -> new NodeAccumulator(type, label));
    }

    private void addStatus(StatusClassifier.Outcome outcome) {
      statuses.add(outcome);
    }

    private void addDefect(DefectRecord defect) {
      defects.add(defect);
    }

    private OctaneRiskHeatMapNode toNode() {
      List<OctaneRiskHeatMapNode> childNodes = new ArrayList<>();
      for (NodeAccumulator child : children.values()) {
        childNodes.add(child.toNode());
      }

      int count = statuses.size() + sumCounts(childNodes);
      int defectCount = defects.size() + sumDefectCounts(childNodes);
      int riskScore = rollupRisk(childNodes);
      for (StatusClassifier.Outcome outcome : statuses) {
        riskScore = Math.max(riskScore, statusRisk(outcome));
      }
      for (DefectRecord defect : defects) {
        riskScore = Math.max(riskScore, defectRisk(defect));
      }
      return new OctaneRiskHeatMapNode(type, label, riskScore, count, defectCount, childNodes);
    }

    private int rollupRisk(Collection<OctaneRiskHeatMapNode> childNodes) {
      if (childNodes.isEmpty()) {
        return 0;
      }
      int totalWeight = 0;
      int weightedRisk = 0;
      int maxRisk = 0;
      for (OctaneRiskHeatMapNode child : childNodes) {
        int weight = child.getWeight();
        totalWeight += weight;
        weightedRisk += child.getRiskScore() * weight;
        maxRisk = Math.max(maxRisk, child.getRiskScore());
      }
      int averageRisk = totalWeight == 0 ? 0 : Math.round((float) weightedRisk / totalWeight);
      return Math.max(averageRisk, Math.round(maxRisk * 0.75f));
    }

    private int sumCounts(Collection<OctaneRiskHeatMapNode> childNodes) {
      int total = 0;
      for (OctaneRiskHeatMapNode child : childNodes) {
        total += child.getCount();
      }
      return total;
    }

    private int sumDefectCounts(Collection<OctaneRiskHeatMapNode> childNodes) {
      int total = 0;
      for (OctaneRiskHeatMapNode child : childNodes) {
        total += child.getDefectCount();
      }
      return total;
    }
  }
}
