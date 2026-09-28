package io.jenkins.plugins.octanesuitegatebyembiti.models;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.jenkins.plugins.octanesuitegatebyembiti.entities.DefectRecord;
import java.util.List;
import org.junit.Test;

public class OctaneDefectSeveritySummaryTest {

  @Test
  public void categorizesOpenDefectsBySeverityAndClosedDefectsSeparately() {
    OctaneDefectSeveritySummary summary =
        OctaneDefectSeveritySummary.fromDefects(
            List.of(
                defect("1", "Critical", "", "opened"),
                defect("2", "Very High", "", "opened"),
                defect("3", "High", "", "opened"),
                defect("4", "Medium", "", "opened"),
                defect("5", "Low", "", "opened"),
                defect("6", "", "", "opened"),
                defect("7", "High", "", "closed")));

    assertTrue(summary.isVisible());
    assertEquals(1, summary.getCritical());
    assertEquals(1, summary.getVeryHigh());
    assertEquals(1, summary.getHigh());
    assertEquals(1, summary.getMedium());
    assertEquals(1, summary.getLow());
    assertEquals(1, summary.getUnspecified());
    assertEquals(1, summary.getClosed());
    assertEquals(1, summary.getClosedCount("High"));
    assertEquals(2, summary.getTotalCount("High"));
    assertEquals("#9D1D34", summary.getBuckets().get(0).getColor());
    assertEquals("#D1334C", summary.getBuckets().get(1).getColor());
    assertEquals("#ED8D25", summary.getBuckets().get(2).getColor());
    assertEquals("#FFD700", summary.getBuckets().get(3).getColor());
    assertEquals("#ACAF4B", summary.getBuckets().get(4).getColor());
    assertEquals("#D4D59F", summary.getBuckets().get(5).getColor());
    assertEquals("#5A5B5B", summary.getBuckets().get(6).getColor());
  }

  @Test
  public void hidesWhenNoDefectsWereFetched() {
    OctaneDefectSeveritySummary summary = OctaneDefectSeveritySummary.empty();

    assertFalse(summary.isVisible());
    assertEquals("#AEAFB1", summary.getBuckets().get(0).getColor());
  }

  @Test
  public void marksClosedBucketGreenWhenEveryRaisedDefectIsClosed() {
    OctaneDefectSeveritySummary summary =
        OctaneDefectSeveritySummary.fromDefects(
            List.of(defect("1", "Critical", "", "fixed"), defect("2", "Low", "", "closed")));

    assertTrue(summary.isVisible());
    assertTrue(summary.isAllClosed());
    assertEquals(0, summary.getOpenTotal());
    assertEquals(2, summary.getClosed());
    assertEquals(1, summary.getClosedCount("Critical"));
    assertEquals(1, summary.getClosedCount("Low"));
    assertEquals("#7BE5B3", summary.getBuckets().get(6).getColor());
  }

  private DefectRecord defect(String id, String severity, String priority, String phase) {
    return new DefectRecord(id, "Defect " + id, severity, priority, phase, "run", "test", "", "");
  }
}
