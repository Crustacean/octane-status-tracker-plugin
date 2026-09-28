package io.jenkins.plugins.octanesuitegatebyembiti.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

class UtilTest {
  @Test
  @ResourceLock(Resources.LOCALE)
  void normalizesIdentifiersIndependentlyOfDefaultLocale() {
    Locale original = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"));
      assertEquals("in_progress", Util.normalizeStatus(" IN PROGRESS "));
      assertEquals("skipped", Util.normalizeStatus("SKIPPED"));
      assertEquals("critical", Util.normalizeStatus("CRITICAL"));
    } finally {
      Locale.setDefault(original);
    }
  }

  @Test
  void logValuesAreSingleLineAndBounded() {
    assertEquals("release forged entry", Util.forLog("release\nforged\rentry"));

    String safe = Util.forLog("x".repeat(3_000));
    assertEquals(2_051, safe.length());
    assertTrue(safe.endsWith("..."));
  }

  @Test
  void percentageHelpersAreZeroSafeAndConsistentlyFormatted() {
    assertEquals(50.0, Util.percentage(2, 4));
    assertEquals(0.0, Util.percentage(2, 0));
    assertEquals("50%", Util.formatCompactPercentage(50.0));
    assertEquals("66.7%", Util.formatCompactPercentage(66.666));
    assertEquals("66.67", Util.formatDecimal(66.666, 2));
    assertEquals("66.67%", Util.formatPercentage(66.666, 2));
  }
}
