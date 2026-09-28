package io.jenkins.plugins.octanesuitegatebyembiti.models;

import static org.junit.Assert.assertEquals;

import io.jenkins.plugins.octanesuitegatebyembiti.entities.DefectRecord;
import io.jenkins.plugins.octanesuitegatebyembiti.entities.RunRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class OctaneSuiteScopedDefectsTest {
  @Test
  public void isolatesAndDeduplicatesRegressionAndCriticalDefects() {
    Map<String, List<RunRecord>> suiteRuns =
        Map.of(
            "regression-suite", List.of(run("regression-run")),
            "critical-suite", List.of(run("critical-run")));
    List<DefectRecord> defects = new ArrayList<>();
    defects.add(defect("regression-1", "regression-run"));
    defects.add(defect("regression-2", "regression-run"));
    defects.add(defect("regression-3", "regression-run"));
    defects.add(defect("critical-1", "critical-run"));
    defects.add(defect("critical-2", "critical-run"));
    for (int index = 1; index <= 5; index++) {
      defects.add(defect("unrelated-" + index, "other-run-" + index));
    }
    defects.add(defect("critical-2", "critical-run"));

    List<DefectRecord> selected = OctaneSuiteScopedDefects.select(suiteRuns, defects);

    assertEquals(5, selected.size());
    assertEquals(
        List.of("regression-1", "regression-2", "regression-3", "critical-1", "critical-2"),
        selected.stream().map(defect -> defect.getId()).toList());
  }

  @Test
  public void includesDefectsLinkedDirectlyToASelectedParentSuiteRun() {
    List<DefectRecord> selected =
        OctaneSuiteScopedDefects.select(
            Map.of("suite-100", List.of()), List.of(defect("defect-1", "suite-100")));

    assertEquals(List.of("defect-1"), selected.stream().map(defect -> defect.getId()).toList());
  }

  @Test
  public void excludesTestOnlyLinksThatCannotProveSuiteRunMembership() {
    RunRecord run =
        new RunRecord("run-1", "Run 1", "failed", "Tester", "shared-test", "Test", "", "");
    DefectRecord historical =
        new DefectRecord(
            "historical", "Old defect", "High", "", "opened", "", "shared-test", "", "");

    assertEquals(
        List.of(),
        OctaneSuiteScopedDefects.select(Map.of("suite-1", List.of(run)), List.of(historical)));
  }

  private RunRecord run(String id) {
    return new RunRecord(id, "Run " + id, "failed", "Tester", "test-" + id, "Test", "", "");
  }

  private DefectRecord defect(String id, String runId) {
    return new DefectRecord(id, "Defect " + id, "High", "", "opened", runId, "", "", "");
  }
}
