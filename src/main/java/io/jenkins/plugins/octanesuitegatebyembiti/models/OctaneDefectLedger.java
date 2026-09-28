package io.jenkins.plugins.octanesuitegatebyembiti.models;

import io.jenkins.plugins.octanesuitegatebyembiti.entities.DefectRecord;
import io.jenkins.plugins.octanesuitegatebyembiti.entities.RunRecord;
import io.jenkins.plugins.octanesuitegatebyembiti.utils.Util;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class OctaneDefectLedger implements Serializable {
  private static final long serialVersionUID = 1L;
  public static final int MAXIMUM_DEFECTS = GateRequest.MAX_RISK_HEAT_MAP_DEFECTS;
  private final Map<String, DefectRecord> defectsById = new LinkedHashMap<>();

  public void merge(Collection<DefectRecord> defects) {
    if (defects == null || defects.isEmpty()) {
      return;
    }
    for (DefectRecord defect : defects) {
      if (defect == null || Util.isBlank(defect.getId())) {
        continue;
      }
      if (defectsById.containsKey(defect.getId()) || defectsById.size() < MAXIMUM_DEFECTS) {
        defectsById.put(
            defect.getId(), defect.withFallbackRelations(defectsById.get(defect.getId())));
      }
    }
  }

  public void retainLinkedTo(
      Collection<RunRecord> activeRuns, Collection<DefectRecord> currentlyLinkedDefects) {
    Set<String> activeRunIds = new LinkedHashSet<>();
    Set<String> activeTestIds = new LinkedHashSet<>();
    if (activeRuns != null) {
      for (RunRecord run : activeRuns) {
        if (run == null) {
          continue;
        }
        if (!Util.isBlank(run.getId())) {
          activeRunIds.add(run.getId());
        }
        if (!Util.isBlank(run.getTestId())) {
          activeTestIds.add(run.getTestId());
        }
      }
    }

    Set<String> currentlyLinkedIds = new LinkedHashSet<>();
    if (currentlyLinkedDefects != null) {
      for (DefectRecord defect : currentlyLinkedDefects) {
        if (defect != null && !Util.isBlank(defect.getId())) {
          currentlyLinkedIds.add(defect.getId());
        }
      }
    }

    defectsById
        .entrySet()
        .removeIf(
            entry -> {
              DefectRecord defect = entry.getValue();
              return !currentlyLinkedIds.contains(entry.getKey())
                  && !activeRunIds.contains(defect.getRunId())
                  && !activeTestIds.contains(defect.getTestId());
            });
  }

  public boolean isEmpty() {
    return defectsById.isEmpty();
  }

  public boolean isAtCapacity() {
    return defectsById.size() >= MAXIMUM_DEFECTS;
  }

  public List<String> getDefectIds() {
    return new ArrayList<>(defectsById.keySet());
  }

  public List<DefectRecord> getDefects() {
    return new ArrayList<>(defectsById.values());
  }
}
