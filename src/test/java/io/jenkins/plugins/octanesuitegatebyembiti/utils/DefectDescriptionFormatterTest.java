package io.jenkins.plugins.octanesuitegatebyembiti.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.jenkins.plugins.octanesuitegatebyembiti.entities.DefectRecord;
import org.junit.jupiter.api.Test;

class DefectDescriptionFormatterTest {
  @Test
  void stripsOctaneMarkupAndPreservesParagraphsBelowTheName() {
    DefectRecord defect =
        new DefectRecord(
            "101",
            "Rosebella",
            "<html><body><p>error on dormant refund</p>"
                + "<p>Trace Id :24ba66e4</p><br>"
                + "<p>Solution :Contact admin</p></body></html>",
            "High",
            "",
            "Opened",
            "run",
            "test",
            "",
            "");

    String output = defect.getDisplayDescription();
    assertEquals(
        "Rosebella:\nerror on dormant refund\nTrace Id :24ba66e4\nSolution :Contact admin", output);
    assertFalse(output.contains("<"));
    assertFalse(output.contains(">"));
  }
}
