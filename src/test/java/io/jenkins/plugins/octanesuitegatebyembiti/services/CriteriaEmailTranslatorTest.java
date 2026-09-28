package io.jenkins.plugins.octanesuitegatebyembiti.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CriteriaEmailTranslatorTest {
  @Test
  public void translatesTopLevelChecksWithoutSplittingInnerAnds() {
    String criteria =
        "(regressions.executionRate == 100 AND regressions.passRate >= 95) "
            + "AND (critical.executionRate == 100% AND critical.passRate == 100) "
            + "AND (defects.major < 10% AND defects.minor <= 20%) "
            + "AND (defects.Unspecified == 0%)";

    String html = CriteriaEmailTranslator.renderHtml(criteria);

    assertTrue(
        html.contains(
            "To get a green light for release, the build must pass 4 non-negotiable QA checks:"));
    assertEquals(4, occurrences(html, "<li>"));
    assertTrue(
        html.contains(
            "<li>Regression tests completed must be exactly 100% and Regression test pass rate "
                + "must be at least 95</li>"));
    assertTrue(
        html.contains(
            "<li>Critical tests completed must be exactly 100% and Critical test pass rate "
                + "must be exactly 100%</li>"));
    assertTrue(
        html.contains(
            "<li>Major defects must remain under 10% and Minor defects must remain under 20%</li>"));
    assertTrue(html.contains("<li>Untriaged defects must be absolutely zero</li>"));
  }

  @Test
  public void escapesUnmappedCriteriaTextBeforeHtmlInjection() {
    String html =
        CriteriaEmailTranslator.renderHtml("(custom.metric < 5 AND <script>alert(1)</script>)");

    assertFalse(html.contains("<script>"));
    assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
  }

  @Test
  public void returnsExplicitFallbackForMissingCriteria() {
    assertEquals(
        "<p>Release criteria are not available.</p>", CriteriaEmailTranslator.renderHtml("  "));
  }

  private static int occurrences(String value, String token) {
    int count = 0;
    int index = 0;
    while ((index = value.indexOf(token, index)) >= 0) {
      count++;
      index += token.length();
    }
    return count;
  }
}
