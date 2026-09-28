package io.jenkins.plugins.octanesuitegatebyembiti.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.jenkins.plugins.octanesuitegatebyembiti.models.GateRequest;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneDefectGroup;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneGateScope;
import java.util.List;
import org.junit.Test;

public class CriteriaValidatorTest {
  @Test
  public void acceptsConfiguredScopesDefectGroupsAliasesAndArithmetic() {
    GateRequest request = configuredRequest();
    request.setCriteria(
        "(tests_executed / total_tests * 100) >= 95 "
            + "AND critical.completionRate == 100 "
            + "AND (defects.majorCount + defects.unspecifiedCount) <= 10");

    CriteriaExpression expression = CriteriaValidator.validate(request);

    assertTrue(expression.usesMetricNamespace("critical"));
    assertTrue(expression.usesMetricNamespace("defects"));
  }

  @Test
  public void acceptsVariablesRegardlessOfLetterCase() {
    GateRequest request = configuredRequest();
    request.setCriteria("CRITICAL.PASSRATE == 100 AND DEFECTS.MAJORCOUNT == 0");

    CriteriaExpression expression = CriteriaValidator.validate(request);

    assertTrue(expression.usesMetricNamespace("critical"));
    assertTrue(expression.usesMetricNamespace("defects"));
  }

  @Test
  public void rejectsUnmatchedClosingParenthesis() {
    assertCriteriaError(
        "regressions.executionRate == 100)", "CRITERIA ERROR: Unmatched closing parenthesis.");
  }

  @Test
  public void rejectsUnmatchedOpeningParenthesis() {
    assertCriteriaError(
        "(regressions.executionRate == 100", "CRITERIA ERROR: Unmatched opening parenthesis.");
  }

  @Test
  public void rejectsRestrictedCharactersBeforeParsing() {
    assertCriteriaError(
        "regressions.executionRate == 100; shutdown",
        "CRITERIA ERROR: Contains invalid or restricted characters.");
  }

  @Test
  public void rejectsUnknownVariablesBeforePolling() {
    assertCriteriaError(
        "regressions.executionRate == 100 AND critical.unknownRate == 0",
        "CRITERIA ERROR: Unknown variable used - 'critical.unknownRate'.");
  }

  @Test
  public void prefixesMalformedGrammarWithCriteriaError() {
    GateRequest request = configuredRequest();
    request.setCriteria("regressions.executionRate AND 100");

    try {
      CriteriaValidator.validate(request);
      fail("Expected malformed criteria to be rejected.");
    } catch (CriteriaException exception) {
      assertTrue(exception.getMessage().startsWith("CRITERIA ERROR:"));
    }
  }

  private void assertCriteriaError(String criteria, String expectedMessage) {
    GateRequest request = configuredRequest();
    request.setCriteria(criteria);

    try {
      CriteriaValidator.validate(request);
      fail("Expected criteria validation to fail.");
    } catch (CriteriaException exception) {
      assertEquals(expectedMessage, exception.getMessage());
    }
  }

  private GateRequest configuredRequest() {
    GateRequest request = new GateRequest("octane-prod", "450312");
    OctaneGateScope critical = new OctaneGateScope("critical");
    critical.setSuiteRunId("450306");
    request.setScopes(List.of(critical));
    OctaneDefectGroup major = new OctaneDefectGroup("major");
    major.setTypes("Critical, Very High, High, Unspecified");
    request.setDefectGroups(List.of(major));
    return request;
  }
}
