package io.jenkins.plugins.octanesuitegatebyembiti.models;

import static org.junit.Assert.assertEquals;

import io.jenkins.plugins.octanesuitegatebyembiti.entities.DefectRecord;
import io.jenkins.plugins.octanesuitegatebyembiti.services.CriteriaException;
import java.util.List;
import org.junit.Test;

public class DefectCriteriaMetricsTest {
  @Test
  public void resolvesCaseInsensitiveGroupsIndividualTypesAndCounts() {
    DefectCriteriaMetrics metrics = metrics();

    assertEquals(10, metrics.getTotalDefectsRaised());
    assertEquals(40.0, metrics.value("major"), 0.000001);
    assertEquals(40.0, metrics.value("MAJOR"), 0.000001);
    assertEquals(4.0, metrics.value("majorCount"), 0.000001);
    assertEquals(10.0, metrics.value("Unspecified"), 0.000001);
    assertEquals(1.0, metrics.value("UNSPECIFIEDcount"), 0.000001);
    assertEquals(10.0, metrics.value("Very_High"), 0.000001);
    assertEquals(20.0, metrics.value("minor"), 0.000001);
    assertEquals(60.0, metrics.value("open"), 0.000001);
    assertEquals(true, metrics.isTypeInGroup("MAJOR", "Very_High"));
    assertEquals(true, metrics.isTypeInGroup("minor", "LOW"));
    assertEquals(false, metrics.isTypeInGroup("minor", "Critical"));
  }

  @Test
  public void groupAndIndividualMetricsRemainIndependentWithoutInternalDuplicates() {
    OctaneDefectGroup major = group("major", "Critical, Unspecified, unspecified, CRITICAL");
    OctaneDefectSeveritySummary summary =
        OctaneDefectSeveritySummary.fromDefects(
            List.of(
                defect("1", "Critical"),
                defect("2", "Unspecified"),
                defect("3", "Unspecified"),
                closedDefect("4", "High"),
                closedDefect("5", "High"),
                closedDefect("6", "High"),
                closedDefect("7", "High"),
                closedDefect("8", "High"),
                closedDefect("9", "High"),
                closedDefect("10", "High")));
    DefectCriteriaMetrics metrics = new DefectCriteriaMetrics(summary, List.of(major));

    assertEquals(3.0, metrics.value("majorCount"), 0.000001);
    assertEquals(2.0, metrics.value("UnspecifiedCount"), 0.000001);
    assertEquals(30.0, metrics.value("major"), 0.000001);
    assertEquals(20.0, metrics.value("Unspecified"), 0.000001);
  }

  @Test
  public void closingDefectsReducesOpenCompositionWithoutChangingTotalRaised() {
    OctaneDefectGroup major = group("major", "Critical");
    DefectCriteriaMetrics beforeClosure =
        new DefectCriteriaMetrics(
            OctaneDefectSeveritySummary.fromDefects(
                List.of(defect("1", "Critical"), defect("2", "Low"))),
            List.of(major));
    DefectCriteriaMetrics afterClosure =
        new DefectCriteriaMetrics(
            OctaneDefectSeveritySummary.fromDefects(
                List.of(closedDefect("1", "Critical"), defect("2", "Low"))),
            List.of(major));

    assertEquals(2, beforeClosure.getTotalDefectsRaised());
    assertEquals(50.0, beforeClosure.value("major"), 0.000001);
    assertEquals(2, afterClosure.getTotalDefectsRaised());
    assertEquals(0.0, afterClosure.value("major"), 0.000001);
    assertEquals(0.0, afterClosure.value("majorCount"), 0.000001);
  }

  @Test
  public void noDefectsProducesZeroRatesAndCounts() {
    DefectCriteriaMetrics metrics =
        new DefectCriteriaMetrics(
            OctaneDefectSeveritySummary.empty(), List.of(group("major", "Critical")));

    assertEquals(0.0, metrics.value("major"), 0.000001);
    assertEquals(0.0, metrics.value("majorCount"), 0.000001);
  }

  @Test(expected = CriteriaException.class)
  public void rejectsUnknownDefectMetric() {
    metrics().value("notConfigured");
  }

  private DefectCriteriaMetrics metrics() {
    OctaneDefectSeveritySummary summary =
        OctaneDefectSeveritySummary.fromDefects(
            List.of(
                defect("1", "Critical"),
                defect("2", "Very High"),
                defect("3", "High"),
                defect("4", "Medium"),
                defect("5", "Low"),
                defect("6", ""),
                closedDefect("7", "Critical"),
                closedDefect("8", "High"),
                closedDefect("9", "Medium"),
                closedDefect("10", "Low")));
    return new DefectCriteriaMetrics(
        summary,
        List.of(
            group("major", "Critical, Very High, High, Unspecified"),
            group("minor", "low, MEDIUM")));
  }

  private OctaneDefectGroup group(String name, String types) {
    OctaneDefectGroup group = new OctaneDefectGroup(name);
    group.setTypes(types);
    return group;
  }

  private DefectRecord defect(String id, String severity) {
    return new DefectRecord(id, "Defect " + id, severity, "", "opened", "run", "test", "", "");
  }

  private DefectRecord closedDefect(String id, String severity) {
    return new DefectRecord(id, "Defect " + id, severity, "", "closed", "run", "test", "", "");
  }
}
