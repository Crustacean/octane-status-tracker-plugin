package io.jenkins.plugins.octanesuitegatebyembiti.models;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import io.jenkins.plugins.octanesuitegatebyembiti.entities.RunRecord;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class OctaneSuiteAttributionsTest {
  private final StatusClassifier classifier =
      new StatusClassifier(
          StatusClassifier.DEFAULT_PASSED_STATUSES,
          StatusClassifier.DEFAULT_FAILED_STATUSES,
          StatusClassifier.DEFAULT_NEUTRAL_STATUSES,
          StatusClassifier.DEFAULT_RUNNING_STATUSES);

  @Test
  public void preservesFirstValidOwnerAndAttributesPartialPolls() {
    GateResult initial = result(Map.of("4501", runs("4501", "Ada@example.com")));
    Map<String, String> persisted = OctaneSuiteAttributions.mergeFirstValid(Map.of(), initial);

    GateResult partial = result(Map.of("4501", runs("4501", "Unassigned (4501)")));
    Map<String, String> merged = OctaneSuiteAttributions.mergeFirstValid(persisted, partial);
    GateResult attributed = OctaneSuiteAttributions.apply(partial, merged);

    assertEquals(Map.of("4501", "Ada@example.com"), merged);
    assertEquals(
        "Ada@example.com", attributed.getSuiteRuns().get("4501").get(0).getSuiteOwnerName());
    assertEquals("Ada@example.com", attributed.getRuns().get(0).getSuiteOwnerName());
  }

  @Test
  public void continuousDiscoveryAddsSuitesAndGroupsMatchingOwnersIntoOneBar() {
    Map<String, String> persisted = Map.of("4501", "Ada@example.com");
    Map<String, List<RunRecord>> discovered = new LinkedHashMap<>();
    discovered.put("4501", runs("4501", "Unassigned (4501)"));
    discovered.put("4502", runs("4502", "ADA@example.com"));
    GateResult current = result(discovered);

    Map<String, String> merged = OctaneSuiteAttributions.mergeFirstValid(persisted, current);
    GateResult attributed = OctaneSuiteAttributions.apply(current, merged);
    OctaneGateReportSnapshot snapshot =
        OctaneGateReportSnapshot.fromResult(
            OctaneGateReportState.POLLING, "Polling", attributed, classifier, 30);

    assertEquals("Ada@example.com", merged.get("4501"));
    assertEquals("ADA@example.com", merged.get("4502"));
    assertEquals(1, snapshot.getReportSections().get(0).getSuiteRuns().size());
    assertEquals(
        List.of("4501", "4502"),
        snapshot.getReportSections().get(0).getSuiteRuns().get(0).getSuiteRunIds());
    assertFalse(
        snapshot.getReportSections().get(0).getSuiteRuns().get(0).getDisplayName().isBlank());
  }

  @Test
  public void neverCachesTemporaryUnassignedOrOverwritesFirstOwner() {
    GateResult unresolved = result(Map.of("4501", runs("4501", "Unassigned (4501)")));
    assertEquals(Map.of(), OctaneSuiteAttributions.mergeFirstValid(Map.of(), unresolved));

    GateResult changed = result(Map.of("4501", runs("4501", "Grace@example.com")));
    assertEquals(
        Map.of("4501", "Ada@example.com"),
        OctaneSuiteAttributions.mergeFirstValid(Map.of("4501", "Ada@example.com"), changed));
  }

  @Test
  public void appliesPersistedOwnerToSuiteRunBackedScopes() {
    List<RunRecord> unresolvedRuns = runs("9001", "Unassigned (9001)");
    GateScopeResult scope =
        new GateScopeResult(
            "critical",
            "",
            List.of(),
            "9001",
            List.of("9001"),
            GateMetrics.fromRuns(unresolvedRuns, classifier),
            unresolvedRuns,
            Map.of("9001", unresolvedRuns));
    GateResult current =
        new GateResult(
            "",
            "critical.executionRate >= 0",
            false,
            false,
            new GateMetrics(0, 0, 0, 0, 0, 0),
            List.of(),
            Map.of(),
            Map.of("critical", scope),
            Instant.parse("2026-08-11T12:00:00Z"));

    GateResult attributed =
        OctaneSuiteAttributions.apply(current, Map.of("9001", "Grace@example.com"));

    GateScopeResult attributedScope = attributed.getScopedResults().get("critical");
    assertEquals(
        "Grace@example.com", attributedScope.getSuiteRuns().get("9001").get(0).getSuiteOwnerName());
    assertEquals("Grace@example.com", attributedScope.getRuns().get(0).getSuiteOwnerName());
  }

  private GateResult result(Map<String, List<RunRecord>> suiteRuns) {
    List<RunRecord> flattened = new ArrayList<>();
    suiteRuns.values().forEach(flattened::addAll);
    return new GateResult(
        String.join(",", suiteRuns.keySet()),
        "regressions.executionRate >= 0",
        false,
        false,
        GateMetrics.fromRuns(flattened, classifier),
        flattened,
        suiteRuns,
        Map.of(),
        Instant.parse("2026-08-11T12:00:00Z"));
  }

  private List<RunRecord> runs(String suiteRunId, String owner) {
    return List.of(new RunRecord("run-" + suiteRunId, "Run " + suiteRunId, "planned", owner));
  }
}
