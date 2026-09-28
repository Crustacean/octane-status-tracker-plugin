package io.jenkins.plugins.octanesuitegatebyembiti.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.jenkins.plugins.octanesuitegatebyembiti.entities.DefectRecord;
import io.jenkins.plugins.octanesuitegatebyembiti.entities.RunRecord;
import io.jenkins.plugins.octanesuitegatebyembiti.models.CriteriaEvaluation;
import io.jenkins.plugins.octanesuitegatebyembiti.models.DefectCriteriaMetrics;
import io.jenkins.plugins.octanesuitegatebyembiti.models.GateMetrics;
import io.jenkins.plugins.octanesuitegatebyembiti.models.MetricsContext;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneDefectGroup;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneDefectSeveritySummary;
import io.jenkins.plugins.octanesuitegatebyembiti.models.StatusClassifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class CriteriaExpressionTest {
  private final StatusClassifier classifier =
      new StatusClassifier(
          StatusClassifier.DEFAULT_PASSED_STATUSES,
          StatusClassifier.DEFAULT_FAILED_STATUSES,
          StatusClassifier.DEFAULT_NEUTRAL_STATUSES,
          StatusClassifier.DEFAULT_RUNNING_STATUSES);

  @Test
  public void detailsPreserveComparisonOrderAndFormatActualRates() {
    MetricsContext context = new MetricsContext(new GateMetrics(4, 4, 2, 1, 1, 0), Map.of());

    CriteriaEvaluation evaluation =
        CriteriaExpression.parse("regressions.executionRate == 100 AND regressions.passRate >= 95")
            .evaluateDetailed(context);

    assertFalse(evaluation.isPassed());
    assertEquals(2, evaluation.getComparisons().size());
    assertEquals(
        "regressions.executionRate == 100%",
        evaluation.getComparisons().get(0).getCriterionLabel());
    assertEquals("75%", evaluation.getComparisons().get(0).getActualLabel());
    assertEquals("NOT OK", evaluation.getComparisons().get(0).getResultLabel());
    assertEquals(
        "regressions.passRate >= 95%", evaluation.getComparisons().get(1).getCriterionLabel());
    assertEquals("66.67%", evaluation.getComparisons().get(1).getActualLabel());
    assertEquals("NOT OK", evaluation.getComparisons().get(1).getResultLabel());
  }

  @Test
  public void skipsRegressionComparisonsWithoutChangingRemainingAndSemantics() {
    MetricsContext context =
        new MetricsContext(
            new GateMetrics(0, 0, 0, 0, 0, 0),
            Map.of("critical", new GateMetrics(2, 2, 2, 0, 0, 0)));

    CriteriaEvaluation evaluation =
        CriteriaExpression.parse("regressions.executionRate == 100 AND critical.passRate == 100")
            .evaluateDetailed(context, false);

    assertTrue(evaluation.isPassed());
    assertEquals(1, evaluation.getComparisons().size());
    assertEquals("critical.passRate", evaluation.getComparisons().get(0).getMetricReference());
  }

  @Test
  public void removesRegressionBranchInsteadOfMakingOrExpressionPass() {
    MetricsContext context =
        new MetricsContext(
            new GateMetrics(1, 1, 1, 0, 0, 0),
            Map.of("critical", new GateMetrics(2, 2, 1, 1, 0, 0)));

    CriteriaEvaluation evaluation =
        CriteriaExpression.parse("regressions.passRate == 100 OR critical.passRate == 100")
            .evaluateDetailed(context, false);

    assertFalse(evaluation.isPassed());
    assertEquals(1, evaluation.getComparisons().size());
    assertEquals("critical.passRate", evaluation.getComparisons().get(0).getMetricReference());
  }

  @Test
  public void skipsUnqualifiedRegressionShorthandWhenRegressionIsBypassed() {
    MetricsContext context =
        new MetricsContext(
            new GateMetrics(0, 0, 0, 0, 0, 0),
            Map.of("critical", new GateMetrics(1, 1, 1, 0, 0, 0)));

    CriteriaEvaluation evaluation =
        CriteriaExpression.parse("100% execution AND critical.executionRate == 100")
            .evaluateDetailed(context, false);

    assertTrue(evaluation.isPassed());
    assertEquals(1, evaluation.getComparisons().size());
    assertEquals("critical.executionRate", evaluation.getComparisons().get(0).getMetricReference());
  }

  @Test
  public void effectiveExpressionDropsRegressionRulesAndPreservesRemainingLogic() {
    MetricsContext context =
        new MetricsContext(
            new GateMetrics(0, 0, 0, 0, 0, 0),
            Map.of("critical", new GateMetrics(2, 2, 2, 0, 0, 0)));
    CriteriaExpression criteria =
        CriteriaExpression.parse(
            "(regressions.executionRate == 100 OR critical.passRate == 100) "
                + "AND defects.majorCount == 0");

    assertEquals(
        "(critical.passRate == 100) AND defects.majorCount == 0",
        criteria.effectiveExpression(context, false));
  }

  @Test
  public void effectiveExpressionPreservesDistinctApplicableBuckets() {
    String criteria =
        "(regressions.executionRate == 100 AND regressions.passRate >= 95) "
            + "AND (critical.executionRate == 100 AND critical.passRate == 100) "
            + "AND (defects.major < 10% AND defects.minor < 20%) "
            + "AND (defects.Unspecified == 0%)";

    assertEquals(
        "(critical.executionRate == 100 AND critical.passRate == 100) "
            + "AND (defects.major < 10% AND defects.minor < 20%) "
            + "AND (defects.Unspecified == 0%)",
        CriteriaExpression.parse(criteria).effectiveExpression(emptyContext(), false));
  }

  @Test
  public void effectiveExpressionDropsDeletedCriticalBucketAndKeepsRegressionRules() {
    String criteria =
        "(regressions.executionRate == 100 AND regressions.passRate >= 95) "
            + "AND (critical.executionRate == 100 AND critical.passRate == 100)";
    MetricsContext context = new MetricsContext(new GateMetrics(2, 2, 2, 0, 0, 0), Map.of());

    CriteriaExpression expression = CriteriaExpression.parse(criteria);

    assertEquals(
        "(regressions.executionRate == 100 AND regressions.passRate >= 95)",
        expression.effectiveExpression(context, Set.of("critical")));
    CriteriaEvaluation evaluation = expression.evaluateAppliedDetailed(context, Set.of("CRITICAL"));
    assertTrue(evaluation.isPassed());
    assertEquals(2, evaluation.getComparisons().size());
  }

  @Test
  public void effectiveExpressionDropsDeletedRegressionBucketAndKeepsCriticalRules() {
    String criteria =
        "(regressions.executionRate == 100 AND regressions.passRate >= 95) "
            + "AND (critical.executionRate == 100 AND critical.passRate == 100)";
    MetricsContext context =
        new MetricsContext(
            new GateMetrics(0, 0, 0, 0, 0, 0),
            Map.of("critical", new GateMetrics(2, 2, 2, 0, 0, 0)));

    CriteriaExpression expression = CriteriaExpression.parse(criteria);

    assertEquals(
        "(critical.executionRate == 100 AND critical.passRate == 100)",
        expression.effectiveExpression(context, Set.of("regression")));
    CriteriaEvaluation evaluation =
        expression.evaluateAppliedDetailed(context, Set.of("regressions"));
    assertTrue(evaluation.isPassed());
    assertEquals(2, evaluation.getComparisons().size());
  }

  @Test
  public void effectiveExpressionConsolidatesMixedCriticalBucketsWithAnd() {
    String criteria =
        "(regressions.executionRate == 100 AND critical.executionRate == 100) "
            + "AND (critical.passRate == 100 OR regressions.passRate >= 95) "
            + "AND (defects.major < 10% AND defects.minor < 20%) "
            + "AND (defects.Unspecified == 0%)";

    assertEquals(
        "(critical.executionRate == 100 AND critical.passRate == 100) "
            + "AND (defects.major < 10% AND defects.minor < 20%) "
            + "AND (defects.Unspecified == 0%)",
        CriteriaExpression.parse(criteria).effectiveExpression(emptyContext(), false));
  }

  @Test
  public void effectiveExpressionConsolidatesMixedCriticalBucketsWithOr() {
    String criteria =
        "(regressions.executionRate == 100 AND critical.executionRate == 100) "
            + "OR (critical.passRate == 100 OR regressions.passRate >= 95) "
            + "AND (defects.major < 10% AND defects.minor < 20%) "
            + "AND (defects.Unspecified == 0%)";

    assertEquals(
        "(critical.executionRate == 100 OR critical.passRate == 100) "
            + "AND (defects.major < 10% AND defects.minor < 20%) "
            + "AND (defects.Unspecified == 0%)",
        CriteriaExpression.parse(criteria).effectiveExpression(emptyContext(), false));
  }

  @Test
  public void appliedEvaluationUsesTheSameConsolidatedPrecedenceAsThePrintedExpression() {
    OctaneDefectGroup major = new OctaneDefectGroup("major");
    major.setTypes("Critical, Very High, High, Unspecified");
    OctaneDefectGroup minor = new OctaneDefectGroup("minor");
    minor.setTypes("Low, Medium");
    DefectCriteriaMetrics defects =
        new DefectCriteriaMetrics(
            OctaneDefectSeveritySummary.fromDefects(
                List.of(
                    new DefectRecord(
                        "1", "Critical", "Critical", "", "opened", "run", "test", "", ""))),
            List.of(major, minor));
    MetricsContext context =
        new MetricsContext(
            new GateMetrics(0, 0, 0, 0, 0, 0),
            Map.of("critical", new GateMetrics(1, 1, 1, 0, 0, 0)),
            defects);
    CriteriaExpression criteria =
        CriteriaExpression.parse(
            "(regressions.executionRate == 100 AND critical.executionRate == 100) "
                + "OR (critical.passRate == 0 OR regressions.passRate >= 95) "
                + "AND (defects.major < 10% AND defects.minor < 20%) "
                + "AND (defects.Unspecified == 0%)");

    assertTrue(criteria.evaluateDetailed(context, false).isPassed());
    assertFalse(criteria.evaluateAppliedDetailed(context, false).isPassed());
  }

  @Test
  public void effectiveExpressionReportsWhenEveryRuleWasBypassed() {
    MetricsContext context = new MetricsContext(new GateMetrics(0, 0, 0, 0, 0, 0), Map.of());

    assertEquals(
        "No applicable criteria.",
        CriteriaExpression.parse("100% execution AND regressions.passRate == 100")
            .effectiveExpression(context, false));
  }

  @Test
  public void detailsEvaluateEveryOrBranchWhileKeepingOverallResult() {
    MetricsContext context = new MetricsContext(new GateMetrics(2, 2, 1, 1, 0, 0), Map.of());

    CriteriaEvaluation evaluation =
        CriteriaExpression.parse("regressions.passRate == 100 OR regressions.failRate == 50")
            .evaluateDetailed(context);

    assertTrue(evaluation.isPassed());
    assertEquals(2, evaluation.getComparisons().size());
    assertFalse(evaluation.getComparisons().get(0).isPassed());
    assertTrue(evaluation.getComparisons().get(1).isPassed());
  }

  @Test
  public void detailsDistinguishDefectPercentageAndCountMetrics() {
    OctaneDefectGroup major = new OctaneDefectGroup("major");
    major.setTypes("Critical, High");
    DefectCriteriaMetrics defects =
        new DefectCriteriaMetrics(
            OctaneDefectSeveritySummary.fromDefects(
                List.of(
                    new DefectRecord(
                        "1", "Critical", "Critical", "", "opened", "run", "test", "", ""),
                    new DefectRecord("2", "Closed", "Low", "", "closed", "run", "test", "", ""))),
            List.of(major));
    MetricsContext context =
        new MetricsContext(new GateMetrics(1, 1, 1, 0, 0, 0), Map.of(), defects);

    CriteriaEvaluation evaluation =
        CriteriaExpression.parse("defects.major < 20% OR defects.majorCount == 1")
            .evaluateDetailed(context);

    assertTrue(evaluation.isPassed());
    assertEquals("defects.major < 20%", evaluation.getComparisons().get(0).getCriterionLabel());
    assertEquals("50%", evaluation.getComparisons().get(0).getActualLabel());
    assertEquals("defects.majorCount == 1", evaluation.getComparisons().get(1).getCriterionLabel());
    assertEquals("1", evaluation.getComparisons().get(1).getActualLabel());
  }

  @Test
  public void detailsSupportShorthandParenthesesScopedAliasesAndCaseInsensitiveReferences() {
    Map<String, GateMetrics> scopes = new LinkedHashMap<>();
    scopes.put("critical", new GateMetrics(2, 2, 2, 0, 0, 0));
    MetricsContext context = new MetricsContext(new GateMetrics(4, 4, 3, 1, 0, 0), scopes);

    CriteriaEvaluation evaluation =
        CriteriaExpression.parse(
                "(100% execution AND regression.pass >= 95%) OR CRITICAL.passRate == 100%")
            .evaluateDetailed(context);

    assertTrue(evaluation.isPassed());
    assertEquals(3, evaluation.getComparisons().size());
    assertEquals("execution >= 100%", evaluation.getComparisons().get(0).getCriterionLabel());
    assertEquals("regression.pass >= 95%", evaluation.getComparisons().get(1).getCriterionLabel());
    assertEquals(
        "CRITICAL.passRate == 100%", evaluation.getComparisons().get(2).getCriterionLabel());
    assertFalse(evaluation.getComparisons().get(1).isPassed());
    assertTrue(evaluation.getComparisons().get(2).isPassed());
  }

  @Test
  public void evaluatesShorthandExecutionAndPassThresholds() {
    MetricsContext context =
        context(
            List.of(
                new RunRecord("1", "first", "passed"),
                new RunRecord("2", "second", "passed"),
                new RunRecord("3", "third", "failed"),
                new RunRecord("4", "fourth", "skipped")));

    assertTrue(CriteriaExpression.parse("75% execution AND 50% pass").evaluate(context));
    assertFalse(CriteriaExpression.parse("100% execution AND 50% pass").evaluate(context));
    assertFalse(CriteriaExpression.parse("100% execution AND 90% pass").evaluate(context));
  }

  @Test
  public void excludesSkippedAndPlannedTestsFromPassRateDenominator() {
    GateMetrics metrics =
        GateMetrics.fromRuns(
            List.of(
                new RunRecord("1", "passed one", "passed"),
                new RunRecord("2", "passed two", "passed"),
                new RunRecord("3", "failed", "failed"),
                new RunRecord("4", "blocked", "blocked"),
                new RunRecord("5", "skipped", "skipped"),
                new RunRecord("6", "planned", "planned")),
            classifier);

    assertEquals(6, metrics.getTotal());
    assertEquals(4, metrics.getExecuted());
    assertEquals(66.667, metrics.getExecutionRate(), 0.001);
    assertEquals(50.0, metrics.getPassRate(), 0.001);
    assertTrue(
        CriteriaExpression.parse("regressions.executionRate >= 66 AND regressions.passRate == 50")
            .evaluate(new MetricsContext(metrics, Map.of())));
  }

  @Test
  public void supportsParenthesesOrAndScopedMetrics() {
    Map<String, GateMetrics> scopes = new LinkedHashMap<>();
    scopes.put(
        "payments",
        GateMetrics.fromRuns(
            List.of(new RunRecord("1", "first", "passed"), new RunRecord("2", "second", "passed")),
            classifier));
    MetricsContext context =
        new MetricsContext(
            GateMetrics.fromRuns(
                List.of(
                    new RunRecord("1", "first", "passed"), new RunRecord("2", "second", "failed")),
                classifier),
            scopes);

    assertTrue(
        CriteriaExpression.parse("(100% execution AND 100% pass) OR payments.pass == 100%")
            .evaluate(context));
    assertFalse(
        CriteriaExpression.parse("100% execution AND (100% pass OR payments.fail > 0%)")
            .evaluate(context));
  }

  @Test
  public void criteriaReadsCombinedScopeMetrics() {
    Map<String, GateMetrics> scopes = new LinkedHashMap<>();
    scopes.put(
        "payments",
        GateMetrics.fromRuns(
            List.of(
                new RunRecord("101", "product area 1004 run", "passed"),
                new RunRecord("102", "product area 1005 run", "failed")),
            classifier));
    MetricsContext context =
        new MetricsContext(GateMetrics.fromRuns(List.of(), classifier), scopes);

    assertTrue(CriteriaExpression.parse("payments.total == 2").evaluate(context));
    assertTrue(CriteriaExpression.parse("payments.passRate == 50").evaluate(context));
  }

  @Test
  public void criteriaReadsRegressionAliasForMainSuiteRunMetrics() {
    MetricsContext context =
        context(
            List.of(
                new RunRecord("1", "first", "passed"),
                new RunRecord("2", "second", "passed"),
                new RunRecord("3", "third", "planned")));

    assertTrue(CriteriaExpression.parse("regressions.executionRate >= 60").evaluate(context));
    assertTrue(CriteriaExpression.parse("regressions.passRate == 100").evaluate(context));
    assertTrue(CriteriaExpression.parse("regression.total == 3").evaluate(context));
  }

  @Test(expected = CriteriaException.class)
  public void rejectsUnknownScopedMetricAliases() {
    CriteriaExpression.parse("global.executionRate == 100").evaluate(context(List.of()));
  }

  @Test
  public void evaluatesRegressionsAndCriticalSuiteRunMetricsIndependently() {
    Map<String, GateMetrics> scopes = new LinkedHashMap<>();
    scopes.put(
        "critical",
        GateMetrics.fromRuns(
            List.of(
                new RunRecord("450304", "overlap critical child", "passed"),
                new RunRecord("450205", "critical child", "passed")),
            classifier));
    MetricsContext context =
        new MetricsContext(
            GateMetrics.fromRuns(
                List.of(
                    new RunRecord("450298", "normal child", "passed"),
                    new RunRecord("450299", "normal child", "passed"),
                    new RunRecord("450304", "overlap critical child", "passed")),
                classifier),
            scopes);

    assertTrue(
        CriteriaExpression.parse(
                "(regressions.executionRate == 100 AND regressions.passRate >= 95) "
                    + "AND (critical.executionRate == 100 AND critical.passRate == 100)")
            .evaluate(context));
  }

  @Test
  public void criticalSuiteRunMetricsCanKeepCriteriaFalse() {
    Map<String, GateMetrics> scopes = new LinkedHashMap<>();
    scopes.put(
        "critical",
        GateMetrics.fromRuns(
            List.of(
                new RunRecord("450304", "overlap critical child", "passed"),
                new RunRecord("450205", "critical child", "planned")),
            classifier));
    MetricsContext context =
        new MetricsContext(
            GateMetrics.fromRuns(
                List.of(
                    new RunRecord("450298", "normal child", "passed"),
                    new RunRecord("450299", "normal child", "passed"),
                    new RunRecord("450304", "overlap critical child", "passed")),
                classifier),
            scopes);

    assertFalse(
        CriteriaExpression.parse(
                "(regressions.executionRate == 100 AND regressions.passRate >= 95) "
                    + "AND (critical.executionRate == 100 AND critical.passRate == 100)")
            .evaluate(context));
  }

  @Test(expected = CriteriaException.class)
  public void rejectsUnknownMetrics() {
    CriteriaExpression.parse("unknownMetric >= 10").evaluate(context(List.of()));
  }

  @Test(expected = CriteriaException.class)
  public void rejectsBadSyntax() {
    CriteriaExpression.parse("passRate >=");
  }

  @Test(expected = CriteriaException.class)
  public void rejectsOversizedCriteriaExpressions() {
    CriteriaExpression.parse("passRate == 100 AND ".repeat(500) + "passRate == 100");
  }

  @Test(expected = CriteriaException.class)
  public void rejectsExcessivelyNestedCriteriaExpressions() {
    CriteriaExpression.parse(
        "(".repeat(CriteriaExpression.MAX_NESTING_DEPTH + 1)
            + "passRate == 100"
            + ")".repeat(CriteriaExpression.MAX_NESTING_DEPTH + 1));
  }

  @Test
  public void acceptsTheMaximumSupportedNestingDepth() {
    String expression =
        "(".repeat(CriteriaExpression.MAX_NESTING_DEPTH)
            + "passRate == 100"
            + ")".repeat(CriteriaExpression.MAX_NESTING_DEPTH);

    assertTrue(
        CriteriaExpression.parse(expression)
            .evaluate(new MetricsContext(new GateMetrics(1, 1, 1, 0, 0, 0), Map.of())));
  }

  @Test
  public void unaryOperatorsShareTheGroupNestingBudget() {
    MetricsContext context = new MetricsContext(new GateMetrics(1, 1, 1, 0, 0, 0), Map.of());
    assertTrue(
        CriteriaExpression.parse(
                "+".repeat(CriteriaExpression.MAX_NESTING_DEPTH) + "passRate == 100")
            .evaluate(context));
    assertTrue(CriteriaExpression.parse("--passRate == 100").evaluate(context));
    for (String expression :
        List.of(
            "+".repeat(CriteriaExpression.MAX_NESTING_DEPTH + 1) + "passRate == 100",
            "(" + "-".repeat(CriteriaExpression.MAX_NESTING_DEPTH) + "passRate) == 100")) {
      try {
        CriteriaExpression.parse(expression);
        throw new AssertionError("Expected unary operators to respect the nesting limit.");
      } catch (CriteriaException expected) {
        assertTrue(expected.getMessage().contains("nesting limit"));
      }
    }
  }

  @Test
  public void acceptsTheTokenBoundaryAndRejectsTheNextEquivalencePartition() {
    String accepted = String.join(" AND ", java.util.Collections.nCopies(256, "passRate >= 0"));
    String rejected = String.join(" AND ", java.util.Collections.nCopies(257, "passRate >= 0"));
    MetricsContext context = new MetricsContext(new GateMetrics(1, 1, 1, 0, 0, 0), Map.of());

    assertTrue(CriteriaExpression.parse(accepted).evaluate(context));
    try {
      CriteriaExpression.parse(rejected);
      throw new AssertionError("Expected the criteria token limit to be enforced.");
    } catch (CriteriaException expected) {
      assertTrue(expected.getMessage().contains("token limit"));
    }
  }

  @Test
  public void handlesZeroRunRates() {
    MetricsContext context = context(List.of());

    assertTrue(CriteriaExpression.parse("executionRate == 0 AND passRate == 0").evaluate(context));
    assertFalse(CriteriaExpression.parse("1% pass").evaluate(context));
  }

  @Test
  public void evaluatesNestedArithmeticAgainstAggregateExecutionMetrics() {
    OctaneDefectGroup major = new OctaneDefectGroup("major");
    major.setTypes("Critical, High");
    DefectCriteriaMetrics defects =
        new DefectCriteriaMetrics(
            OctaneDefectSeveritySummary.fromDefects(
                List.of(
                    new DefectRecord(
                        "1", "Critical", "Critical", "", "opened", "run", "test", "", ""),
                    new DefectRecord("2", "Closed", "Low", "", "closed", "run", "test", "", ""))),
            List.of(major));
    GateMetrics regressions = new GateMetrics(4, 0, 1, 0, 0, 3);
    GateMetrics total = new GateMetrics(10, 0, 1, 1, 1, 7);
    MetricsContext context = new MetricsContext(regressions, Map.of(), defects, total);

    CriteriaEvaluation evaluation =
        CriteriaExpression.parse(
                "total.executionRate > 15 "
                    + "AND ((defects.majorCount / tests_executed) * 100) > 40")
            .evaluateDetailed(context);

    assertTrue(evaluation.isPassed());
    assertEquals(2, evaluation.getComparisons().size());
    assertEquals("20%", evaluation.getComparisons().get(0).getActualLabel());
    assertEquals("50", evaluation.getComparisons().get(1).getActualLabel());
  }

  @Test
  public void exposesStrictExecutionAndSkippedInclusiveCompletionAliases() {
    GateMetrics regressions = new GateMetrics(2, 0, 1, 0, 0, 1);
    GateMetrics total = new GateMetrics(5, 0, 1, 1, 1, 2);
    MetricsContext context = new MetricsContext(regressions, Map.of(), null, total);

    assertTrue(
        CriteriaExpression.parse(
                "tests_executed == 2 AND tests_run == 2 AND total_tests == 5 "
                    + "AND tests_resolved == 3 AND execution_percentage == 40 "
                    + "AND completion_percentage == 60 AND total.completionRate == 60")
            .evaluate(context));
  }

  @Test
  public void arithmeticHonorsPrecedenceAndSafelyDefaultsDivisionByZeroToZero() {
    GateMetrics noExecution = new GateMetrics(5, 0, 0, 0, 0, 5);
    MetricsContext context =
        new MetricsContext(
            noExecution,
            Map.of(),
            new DefectCriteriaMetrics(OctaneDefectSeveritySummary.empty(), List.of()),
            noExecution);

    assertTrue(
        CriteriaExpression.parse(
                "defects.openCount / tests_executed == 0 "
                    + "AND (tests_executed + 3 * 2 - 4) / 2 == 1")
            .evaluate(context));
  }

  @Test
  public void arithmeticCanCompareTwoCalculatedExpressions() {
    GateMetrics total = new GateMetrics(8, 0, 2, 2, 1, 3);
    MetricsContext context = new MetricsContext(total, Map.of(), null, total);

    assertTrue(
        CriteriaExpression.parse("tests_executed + 1 == total_tests - tests_resolved + 2")
            .evaluate(context));
  }

  @Test
  public void evaluatesGroupedAndIndividualDefectCriteriaCaseInsensitively() {
    OctaneDefectGroup major = new OctaneDefectGroup("major");
    major.setTypes("Critical, Very High, High, Unspecified");
    OctaneDefectGroup minor = new OctaneDefectGroup("minor");
    minor.setTypes("Low, Medium");
    List<DefectRecord> defects = new ArrayList<>();
    defects.add(new DefectRecord("1", "Critical", "Critical", "", "opened", "run", "test", "", ""));
    for (int index = 2; index <= 20; index++) {
      defects.add(
          new DefectRecord(
              Integer.toString(index),
              "Closed " + index,
              "Low",
              "",
              "closed",
              "run",
              "test",
              "",
              ""));
    }
    DefectCriteriaMetrics defectMetrics =
        new DefectCriteriaMetrics(
            OctaneDefectSeveritySummary.fromDefects(defects), List.of(major, minor));
    MetricsContext context =
        new MetricsContext(
            new GateMetrics(2, 2, 2, 0, 0, 0),
            Map.of("critical", new GateMetrics(1, 1, 1, 0, 0, 0)),
            defectMetrics);
    CriteriaExpression expression =
        CriteriaExpression.parse(
            "(regressions.executionRate == 100 AND regressions.passRate >= 95) "
                + "AND (CRITICAL.executionRate == 100 AND critical.passRate == 100) "
                + "AND (defects.MAJOR < 10% AND defects.minor < 20%) "
                + "AND (DEFECTS.Unspecified == 0%)");

    assertTrue(expression.usesMetricNamespace("defects"));
    assertTrue(expression.evaluate(context));
    assertFalse(
        CriteriaExpression.parse("regressions.passRate == 100").usesMetricNamespace("defects"));
  }

  private MetricsContext context(List<RunRecord> runs) {
    return new MetricsContext(GateMetrics.fromRuns(runs, classifier), Map.of());
  }

  private MetricsContext emptyContext() {
    return new MetricsContext(new GateMetrics(0, 0, 0, 0, 0, 0), Map.of());
  }
}
