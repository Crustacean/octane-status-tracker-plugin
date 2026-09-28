package io.jenkins.plugins.octanesuitegatebyembiti.services;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.jenkins.plugins.octanesuitegatebyembiti.entities.DefectRecord;
import io.jenkins.plugins.octanesuitegatebyembiti.entities.RunRecord;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneRiskHeatMap;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneRiskHeatMapBuilder;
import io.jenkins.plugins.octanesuitegatebyembiti.models.StatusClassifier;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class OctaneRiskHeatMapRendererTest {

  private final StatusClassifier classifier =
      new StatusClassifier(
          StatusClassifier.DEFAULT_PASSED_STATUSES,
          StatusClassifier.DEFAULT_FAILED_STATUSES,
          StatusClassifier.DEFAULT_NEUTRAL_STATUSES,
          StatusClassifier.DEFAULT_RUNNING_STATUSES);

  @Test
  public void rendersSeverityBarInsteadOfLinkedDefectDiagnostics() {
    OctaneRiskHeatMap heatMap =
        new OctaneRiskHeatMapBuilder()
            .build(
                "4001",
                Map.of("4501", List.of(new RunRecord("run-1", "one", "failed"))),
                List.of(
                    defect("1", "Critical", "", "opened"),
                    defect("2", "High", "", "opened"),
                    defect("3", "Low", "", "closed")),
                classifier);

    String html = new OctaneRiskHeatMapRenderer().render(heatMap, true, "17:53:39");

    assertTrue(html.contains("octane-risk-heat-map-container"));
    assertTrue(html.contains("octane-risk-issues-container"));
    assertTrue(html.contains("octane-defect-severity-tracker"));
    assertTrue(html.contains("octane-defect-severity-bar"));
    assertTrue(html.contains("octane-defect-severity-label\">Critical</span>"));
    assertTrue(html.contains("octane-defect-severity-label\">High</span>"));
    assertTrue(html.contains("octane-defect-severity-label\">Closed</span>"));
    assertTrue(html.contains("TOTAL ISSUES: 2"));
    assertTrue(html.contains("LAST UPDATED: JUST NOW"));
    assertFalse(html.contains("title=\"Critical severity\""));
    assertFalse(html.contains("title=\"High severity\""));
    assertFalse(html.contains("title=\"Closed Issues\""));
    assertTrue(html.contains("#9D1D34"));
    assertTrue(html.contains("#ED8D25"));
    assertTrue(html.contains("#5A5B5B"));
    assertTrue(html.contains("background:#9D1D34;color:#ffffff"));
    assertTrue(html.contains("background:#ED8D25;color:#ffffff"));
    assertTrue(html.contains("background:#5A5B5B;color:#ffffff"));
    assertFalse(html.contains("Defects: "));
    assertFalse(html.contains(" linked"));
    assertFalse(html.contains(" unlinked"));
  }

  @Test
  public void rendersDarkTextForLightSeveritySegments() {
    OctaneRiskHeatMap heatMap =
        new OctaneRiskHeatMapBuilder()
            .build(
                "4001",
                Map.of("4501", List.of(new RunRecord("run-1", "one", "failed"))),
                List.of(
                    defect("1", "Medium", "", "opened"),
                    defect("2", "Low", "", "opened"),
                    defect("3", "", "", "opened")),
                classifier);

    String html = new OctaneRiskHeatMapRenderer().render(heatMap, true, "17:53:39");

    assertTrue(html.contains("background:#FFD700;color:#000000"));
    assertTrue(html.contains("background:#ACAF4B;color:#000000"));
    assertTrue(html.contains("background:#D4D59F;color:#000000"));
  }

  @Test
  public void rendersDarkTextForClosedSegmentWhenAllIssuesAreClosed() {
    OctaneRiskHeatMap heatMap =
        new OctaneRiskHeatMapBuilder()
            .build(
                "4001",
                Map.of("4501", List.of(new RunRecord("run-1", "one", "failed"))),
                List.of(defect("1", "Critical", "", "closed"), defect("2", "Low", "", "fixed")),
                classifier);

    String html = new OctaneRiskHeatMapRenderer().render(heatMap, false, "17:53:39");

    assertTrue(html.contains("background:#7BE5B3;color:#000000"));
    assertTrue(html.contains("TOTAL ISSUES: 0"));
  }

  @Test
  public void rendersExactIssuePollTimeAfterBuildCompletes() {
    OctaneRiskHeatMap heatMap =
        new OctaneRiskHeatMapBuilder()
            .build(
                "4001",
                Map.of("4501", List.of(new RunRecord("run-1", "one", "failed"))),
                List.of(defect("1", "Critical", "", "opened")),
                classifier);

    String html = new OctaneRiskHeatMapRenderer().render(heatMap, false, "2026/07/20 17:53:39");

    assertTrue(html.contains("LAST UPDATED: 2026/07/20 17:53:39"));
    assertFalse(html.contains("LAST UPDATED: JUST NOW"));
  }

  @Test
  public void hidesSeverityBarWhenEveryDefectCountIsZero() {
    OctaneRiskHeatMap heatMap =
        new OctaneRiskHeatMapBuilder()
            .build(
                "4001",
                Map.of("4501", List.of(new RunRecord("run-1", "one", "passed"))),
                List.of(),
                classifier);

    String html = new OctaneRiskHeatMapRenderer().render(heatMap);

    assertFalse(html.contains("octane-defect-severity-bar"));
  }

  private DefectRecord defect(String id, String severity, String priority, String phase) {
    return new DefectRecord(id, "Defect " + id, severity, priority, phase, "run-1", "", "", "");
  }
}
