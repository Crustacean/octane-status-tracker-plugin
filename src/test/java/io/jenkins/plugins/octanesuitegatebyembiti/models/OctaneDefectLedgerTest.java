package io.jenkins.plugins.octanesuitegatebyembiti.models;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.jenkins.plugins.octanesuitegatebyembiti.entities.DefectRecord;
import io.jenkins.plugins.octanesuitegatebyembiti.entities.RunRecord;
import java.util.List;
import org.junit.Test;

public class OctaneDefectLedgerTest {

  @Test
  public void replacesEarlierDefectStateWhenKnownDefectIsRefreshed() {
    OctaneDefectLedger ledger = new OctaneDefectLedger();

    ledger.merge(List.of(defect("901", "High", "opened")));
    ledger.merge(List.of(defect("901", "High", "closed")));

    OctaneDefectSeveritySummary summary =
        OctaneDefectSeveritySummary.fromDefects(ledger.getDefects());

    assertEquals(List.of("901"), ledger.getDefectIds());
    assertEquals(1, ledger.getDefects().size());
    assertFalse(ledger.getDefects().get(0).isOpen());
    assertEquals(0, summary.getOpenTotal());
    assertEquals(1, summary.getClosed());
  }

  @Test
  public void skipsBlankDefectIds() {
    OctaneDefectLedger ledger = new OctaneDefectLedger();

    ledger.merge(List.of(defect("", "High", "opened"), defect("902", "Critical", "opened")));

    assertEquals(List.of("902"), ledger.getDefectIds());
  }

  @Test
  public void boundsUniqueDefectHistoryButStillRefreshesKnownDefects() {
    OctaneDefectLedger ledger = new OctaneDefectLedger();
    for (int index = 0; index < OctaneDefectLedger.MAXIMUM_DEFECTS + 1; index++) {
      ledger.merge(List.of(defect(Integer.toString(index), "High", "opened")));
    }

    ledger.merge(List.of(defect("0", "High", "closed")));

    assertEquals(OctaneDefectLedger.MAXIMUM_DEFECTS, ledger.getDefects().size());
    assertTrue(ledger.isAtCapacity());
    assertFalse(ledger.getDefects().get(0).isOpen());
  }

  @Test
  public void purgesOpenAndClosedDefectsThatOnlyBelongToDeletedSuiteRuns() {
    OctaneDefectLedger ledger = new OctaneDefectLedger();
    DefectRecord survivingOpen = defect("901", "High", "opened", "run-1", "test-1");
    DefectRecord deletedOpen = defect("902", "Critical", "opened", "run-2", "test-2");
    DefectRecord deletedClosed = defect("903", "Medium", "closed", "run-2", "test-2");
    ledger.merge(List.of(survivingOpen, deletedOpen, deletedClosed));

    ledger.retainLinkedTo(
        List.of(new RunRecord("run-1", "Run 1", "passed", "", "test-1", "", "", "")),
        List.of(survivingOpen));

    assertEquals(List.of("901"), ledger.getDefectIds());
  }

  @Test
  public void retainsCurrentDefectsWhenOctaneOmitsRelationshipFields() {
    OctaneDefectLedger ledger = new OctaneDefectLedger();
    DefectRecord current = defect("904", "Low", "opened", "", "");
    ledger.merge(List.of(current));

    ledger.retainLinkedTo(List.of(), List.of(current));

    assertEquals(List.of("904"), ledger.getDefectIds());
  }

  @Test
  public void preservesSuiteRelationsWhenAStatusRefreshOmitsThem() {
    OctaneDefectLedger ledger = new OctaneDefectLedger();
    ledger.merge(List.of(defect("905", "High", "opened", "run-1", "test-1")));

    ledger.merge(List.of(defect("905", "High", "closed", "", "")));

    DefectRecord refreshed = ledger.getDefects().get(0);
    assertEquals("run-1", refreshed.getRunId());
    assertEquals("test-1", refreshed.getTestId());
    assertFalse(refreshed.isOpen());
  }

  private DefectRecord defect(String id, String severity, String phase) {
    return defect(id, severity, phase, "run", "test");
  }

  private DefectRecord defect(
      String id, String severity, String phase, String runId, String testId) {
    return new DefectRecord(id, "Defect " + id, severity, "", phase, runId, testId, "", "");
  }
}
