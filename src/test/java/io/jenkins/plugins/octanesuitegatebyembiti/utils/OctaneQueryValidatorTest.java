package io.jenkins.plugins.octanesuitegatebyembiti.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class OctaneQueryValidatorTest {
  @Test
  void preservesValidExplicitOctaneGrammar() {
    assertEquals(
        "(phase EQ ^open^);(severity EQ {id EQ 1001})",
        OctaneQueryValidator.normalize(
            " (phase EQ ^open^);(severity EQ {id EQ 1001}) ", "Defect query"));
  }

  @Test
  void rejectsControlCharactersAndOversizedQueries() {
    assertThrows(
        IllegalArgumentException.class,
        () -> OctaneQueryValidator.normalize("phase EQ ^open^\nforged", "Defect query"));
    assertThrows(
        IllegalArgumentException.class,
        () -> OctaneQueryValidator.normalize("x".repeat(4_097), "Defect query"));
  }
}
