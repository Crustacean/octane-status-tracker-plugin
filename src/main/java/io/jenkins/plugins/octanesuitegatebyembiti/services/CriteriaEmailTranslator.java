package io.jenkins.plugins.octanesuitegatebyembiti.services;

import io.jenkins.plugins.octanesuitegatebyembiti.utils.Util;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

final class CriteriaEmailTranslator {
  private static final Pattern TOP_LEVEL_AND =
      Pattern.compile("\\)\\s*AND\\s*\\(", Pattern.CASE_INSENSITIVE);
  private static final Pattern EXACTLY_ONE_HUNDRED = Pattern.compile("==\\s*100\\s*%?(?![\\d.])");
  private static final Pattern EXACTLY_ZERO = Pattern.compile("==\\s*0\\s*%?(?![\\d.])");
  private static final String NUMERIC_THRESHOLD = "(?=[+-]?(?:\\d|\\.))";
  private static final Pattern AT_LEAST = Pattern.compile(">=\\s*" + NUMERIC_THRESHOLD);
  private static final Pattern AT_MOST = Pattern.compile("<=\\s*" + NUMERIC_THRESHOLD);
  private static final Pattern LESS_THAN = Pattern.compile("<\\s*" + NUMERIC_THRESHOLD);
  private static final Pattern INNER_AND = Pattern.compile("\\bAND\\b", Pattern.CASE_INSENSITIVE);
  private static final Map<String, String> VARIABLE_LABELS = variableLabels();

  private CriteriaEmailTranslator() {}

  static String renderHtml(String rawCriteria) {
    String criteria = Util.trimToEmpty(rawCriteria);
    if (criteria.isEmpty()) {
      return "<p>Release criteria are not available.</p>";
    }

    String[] fragments = TOP_LEVEL_AND.split(criteria);
    List<String> checks = new ArrayList<>();
    for (String fragment : fragments) {
      String cleaned = removeDanglingParentheses(fragment);
      if (!cleaned.isEmpty()) {
        checks.add(translate(cleaned));
      }
    }
    if (checks.isEmpty()) {
      return "<p>Release criteria are not available.</p>";
    }

    StringBuilder html = new StringBuilder();
    html.append(
            "<p style=\"margin:0 0 8px;\">To get a green light for release, the build must pass ")
        .append(checks.size())
        .append(" non-negotiable QA checks:</p>")
        .append("<ul style=\"margin:0 0 0 20px;padding:0;\">");
    for (String check : checks) {
      html.append("<li>").append(escape(check)).append("</li>");
    }
    html.append("</ul>");
    return html.toString();
  }

  private static String translate(String condition) {
    String translated = condition;
    for (Map.Entry<String, String> variable : VARIABLE_LABELS.entrySet()) {
      translated =
          Pattern.compile(Pattern.quote(variable.getKey()), Pattern.CASE_INSENSITIVE)
              .matcher(translated)
              .replaceAll(variable.getValue());
    }
    translated = EXACTLY_ONE_HUNDRED.matcher(translated).replaceAll("must be exactly 100% ");
    translated = EXACTLY_ZERO.matcher(translated).replaceAll("must be absolutely zero ");
    translated = AT_LEAST.matcher(translated).replaceAll("must be at least ");
    translated = AT_MOST.matcher(translated).replaceAll("must remain under ");
    translated = LESS_THAN.matcher(translated).replaceAll("must remain under ");
    translated = INNER_AND.matcher(translated).replaceAll("and");
    return translated.trim().replaceAll("\\s+", " ");
  }

  private static String removeDanglingParentheses(String value) {
    String cleaned = Util.trimToEmpty(value);
    int balance = parenthesisBalance(cleaned);
    while (balance > 0 && cleaned.startsWith("(")) {
      cleaned = cleaned.substring(1).trim();
      balance--;
    }
    while (balance < 0 && cleaned.endsWith(")")) {
      cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
      balance++;
    }
    while (isWrappedInParentheses(cleaned)) {
      cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
    }
    return cleaned;
  }

  private static int parenthesisBalance(String value) {
    int balance = 0;
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character == '(') {
        balance++;
      } else if (character == ')') {
        balance--;
      }
    }
    return balance;
  }

  private static boolean isWrappedInParentheses(String value) {
    if (value.length() < 2 || value.charAt(0) != '(' || value.charAt(value.length() - 1) != ')') {
      return false;
    }
    int depth = 0;
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character == '(') {
        depth++;
      } else if (character == ')') {
        depth--;
        if (depth == 0 && index < value.length() - 1) {
          return false;
        }
      }
      if (depth < 0) {
        return false;
      }
    }
    return depth == 0;
  }

  private static Map<String, String> variableLabels() {
    Map<String, String> labels = new LinkedHashMap<>();
    labels.put("regressions.executionRate", "Regression tests completed");
    labels.put("regressions.passRate", "Regression test pass rate");
    labels.put("critical.executionRate", "Critical tests completed");
    labels.put("critical.passRate", "Critical test pass rate");
    labels.put("defects.major", "Major defects");
    labels.put("defects.minor", "Minor defects");
    labels.put("defects.Unspecified", "Untriaged defects");
    return Map.copyOf(labels);
  }

  private static String escape(String value) {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }
}
