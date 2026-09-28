package io.jenkins.plugins.octanesuitegatebyembiti.models;

import io.jenkins.plugins.octanesuitegatebyembiti.entities.DefectRecord;
import io.jenkins.plugins.octanesuitegatebyembiti.entities.RunRecord;
import io.jenkins.plugins.octanesuitegatebyembiti.utils.Util;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Selects the canonical defect pool for the suite runs participating in the current gate. */
public final class OctaneSuiteScopedDefects {
  private OctaneSuiteScopedDefects() {}

  public static List<DefectRecord> select(
      Map<String, List<RunRecord>> suiteRuns, Collection<DefectRecord> defects) {
    if (suiteRuns == null || suiteRuns.isEmpty() || defects == null || defects.isEmpty()) {
      return List.of();
    }

    Set<String> scopedRunIds = scopedRunIds(suiteRuns);
    Map<String, DefectRecord> selectedById = new LinkedHashMap<>();
    int anonymousIndex = 0;
    for (DefectRecord defect : defects) {
      if (defect == null || !scopedRunIds.contains(defect.getRunId())) {
        continue;
      }
      String key = Util.isBlank(defect.getId()) ? "anonymous-" + anonymousIndex++ : defect.getId();
      selectedById.put(key, defect);
    }
    return List.copyOf(selectedById.values());
  }

  private static Set<String> scopedRunIds(Map<String, List<RunRecord>> suiteRuns) {
    Set<String> values = new LinkedHashSet<>();
    for (Map.Entry<String, List<RunRecord>> entry : suiteRuns.entrySet()) {
      if (!Util.isBlank(entry.getKey())) {
        values.add(entry.getKey());
      }
      if (entry.getValue() == null) {
        continue;
      }
      for (RunRecord run : entry.getValue()) {
        if (run != null && !Util.isBlank(run.getId())) {
          values.add(run.getId());
        }
      }
    }
    return values;
  }
}
