package io.jenkins.plugins.octanesuitegatebyembiti.services;

import io.jenkins.plugins.octanesuitegatebyembiti.models.DefectCriteriaMetrics;
import io.jenkins.plugins.octanesuitegatebyembiti.models.GateMetrics;
import io.jenkins.plugins.octanesuitegatebyembiti.models.GateRequest;
import io.jenkins.plugins.octanesuitegatebyembiti.models.MetricsContext;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneDefectSeveritySummary;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneGateScope;
import io.jenkins.plugins.octanesuitegatebyembiti.utils.Util;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

final class CriteriaValidator {
  private static final Pattern ALLOWED_CHARACTERS =
      Pattern.compile("^[a-zA-Z0-9\\s._><=!+\\-*/()%]+$");

  private CriteriaValidator() {}

  static CriteriaExpression validate(GateRequest request) {
    String criteria = request == null ? "" : Util.trimToEmpty(request.getCriteria());
    if (criteria.isEmpty()) {
      throw new CriteriaException("CRITERIA ERROR: Criteria expression is required.");
    }

    validateParentheses(criteria);
    if (!ALLOWED_CHARACTERS.matcher(criteria).matches()) {
      throw new CriteriaException("CRITERIA ERROR: Contains invalid or restricted characters.");
    }

    CriteriaExpression expression;
    try {
      expression = CriteriaExpression.parse(criteria);
    } catch (CriteriaException exception) {
      throw criteriaError(exception.getMessage());
    }
    expression.validateMetricReferences(validationContext(request));
    return expression;
  }

  private static void validateParentheses(String criteria) {
    int openParentheses = 0;
    for (int index = 0; index < criteria.length(); index++) {
      char character = criteria.charAt(index);
      if (character == '(') {
        openParentheses++;
      } else if (character == ')') {
        openParentheses--;
        if (openParentheses < 0) {
          throw new CriteriaException("CRITERIA ERROR: Unmatched closing parenthesis.");
        }
      }
    }
    if (openParentheses != 0) {
      throw new CriteriaException("CRITERIA ERROR: Unmatched opening parenthesis.");
    }
  }

  private static MetricsContext validationContext(GateRequest request) {
    GateMetrics emptyMetrics = new GateMetrics(0, 0, 0, 0, 0, 0);
    Map<String, GateMetrics> scopes = new LinkedHashMap<>();
    for (OctaneGateScope scope : request.getScopes()) {
      if (scope != null && !Util.isBlank(scope.getName())) {
        scopes.put(scope.getName(), emptyMetrics);
      }
    }
    DefectCriteriaMetrics defectMetrics =
        new DefectCriteriaMetrics(OctaneDefectSeveritySummary.empty(), request.getDefectGroups());
    return new MetricsContext(emptyMetrics, scopes, defectMetrics, emptyMetrics);
  }

  private static CriteriaException criteriaError(String message) {
    String detail = Util.trimToEmpty(message);
    return new CriteriaException(
        detail.startsWith("CRITERIA ERROR:") ? detail : "CRITERIA ERROR: " + detail);
  }
}
