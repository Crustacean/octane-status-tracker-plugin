package io.jenkins.plugins.octanesuitegatebyembiti.controllers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.model.TaskListener;
import hudson.util.FormValidation;
import io.jenkins.plugins.octanesuitegatebyembiti.models.GateRequest;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneDefectGroup;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class OctaneSuiteGateStepTest {
  @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void bindsDefectGroupsIntoGateRequest() {
    OctaneDefectGroup major = new OctaneDefectGroup("major");
    major.setTypes("Critical, Very High, High, Unspecified");
    OctaneSuiteGateStep step = new OctaneSuiteGateStep("octane-prod", "1196");
    step.setDefectGroups(List.of(major));

    GateRequest request = step.toRequest();

    assertEquals(1, request.getDefectGroups().size());
    assertEquals("major", request.getDefectGroups().get(0).getName());
    assertEquals(
        List.of("critical", "veryhigh", "high", "unspecified"),
        request.getDefectGroups().get(0).getNormalizedTypes());
  }

  @Test
  public void bindsDynamicConnectionIntoGateRequest() {
    OctaneSuiteGateStep step = new OctaneSuiteGateStep("default_shared_space", "1196");
    step.setBaseUrl(" https://octane.example.test ");
    step.setCredentialsId(" default_shared_space ");

    GateRequest request = step.toRequest();

    assertEquals("https://octane.example.test", request.getBaseUrl());
    assertEquals("default_shared_space", request.getCredentialsId());
  }

  @Test
  public void freestyleBuilderDelegatesDefectGroups() {
    OctaneDefectGroup minor = new OctaneDefectGroup("minor");
    minor.setTypes("Low, Medium");
    OctaneSuiteGateBuilder builder = new OctaneSuiteGateBuilder("1196");
    builder.setDefectGroups(List.of(minor));

    assertEquals(1, builder.getDefectGroups().size());
    assertEquals("minor", builder.getDefectGroups().get(0).getName());
  }

  @Test
  public void freestyleBuilderBindsDynamicMappingSelectors() {
    OctaneSuiteGateBuilder builder = new OctaneSuiteGateBuilder("1196");
    builder.setSpacesMappingFile("config/octane_spaces_mapping.json");
    builder.setSharedSpaceName("Default Shared Space");
    builder.setWorkspaceName("Payments");

    assertEquals("config/octane_spaces_mapping.json", builder.getSpacesMappingFile());
    assertEquals("Default Shared Space", builder.getSharedSpaceName());
    assertEquals("Payments", builder.getWorkspaceName());
  }

  @Test
  public void freestyleBuilderResolvesDynamicConnectionBeforeCreatingRequest() throws Exception {
    FilePath workspace = new FilePath(temporaryFolder.getRoot());
    workspace
        .child("octane_spaces_mapping.json")
        .write(
            """
            {
              "shared_url": "https://octane.example.test",
              "shared_spaces": [{
                "sharedSpaceId": "1001",
                "sharedSpaceName": "Default Space",
                "workspaces": [{
                  "workspaceId": "2002",
                  "workspaceName": "Payments"
                }]
              }]
            }
            """,
            StandardCharsets.UTF_8.name());
    OctaneSuiteGateBuilder builder = new OctaneSuiteGateBuilder("1196");
    builder.setSharedSpaceName("Default Space");
    builder.setWorkspaceName("Payments");

    GateRequest request = builder.createRequest(workspace, new EnvVars(), TaskListener.NULL);

    assertEquals("default_space", request.getServerId());
    assertEquals("https://octane.example.test", request.getBaseUrl());
    assertEquals("default_space", request.getCredentialsId());
    assertEquals("1001", request.getSharedSpaceId());
    assertEquals("2002", request.getWorkspaceId());
  }

  @Test
  public void bindsTesterDetailThresholdsIntoGateRequest() {
    OctaneSuiteGateStep step = new OctaneSuiteGateStep("octane-prod", "1196");
    step.setBasePassrateFigure(82);
    step.setBaseExecutionFigure(91);

    GateRequest request = step.toRequest();

    assertEquals(82, request.getBasePassrateFigure());
    assertEquals(91, request.getBaseExecutionFigure());
  }

  @Test
  public void bindsAdaptiveMetricRunLimitIntoGateRequestAndFreestyleBuilder() {
    OctaneSuiteGateStep step = new OctaneSuiteGateStep("octane-prod", "1196");
    step.setLimitForMetricRunsInSuite(350);
    OctaneSuiteGateBuilder builder = new OctaneSuiteGateBuilder("1196");
    builder.setLimitForMetricRunsInSuite(275);

    assertEquals(350, step.toRequest().getLimitForMetricRunsInSuite());
    assertEquals(275, builder.getLimitForMetricRunsInSuite());
  }

  @Test
  public void freestyleBuilderDelegatesTesterDetailThresholds() {
    OctaneSuiteGateBuilder builder = new OctaneSuiteGateBuilder("1196");
    builder.setBasePassrateFigure(84);
    builder.setBaseExecutionFigure(93);

    assertEquals(84, builder.getBasePassrateFigure());
    assertEquals(93, builder.getBaseExecutionFigure());
  }

  @Test
  public void extendedTimeoutValidationAcceptsBlankOrZero() {
    OctaneSuiteGateStep.DescriptorImpl descriptor = new OctaneSuiteGateStep.DescriptorImpl();

    assertEquals(FormValidation.Kind.OK, descriptor.doCheckTimeoutMinutesExtended("").kind);
    assertEquals(FormValidation.Kind.OK, descriptor.doCheckTimeoutMinutesExtended(" ").kind);
    assertEquals(FormValidation.Kind.OK, descriptor.doCheckTimeoutMinutesExtended("0").kind);
    assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckTimeoutMinutesExtended("-1").kind);
  }

  @Test
  public void regressionSuiteRunValidationAcceptsBlankValues() {
    OctaneSuiteGateStep.DescriptorImpl descriptor = new OctaneSuiteGateStep.DescriptorImpl();

    assertEquals(FormValidation.Kind.OK, descriptor.doCheckSuiteRunId("").kind);
    assertEquals(FormValidation.Kind.OK, descriptor.doCheckSuiteRunId(" ").kind);
    assertEquals(
        FormValidation.Kind.OK, descriptor.doCheckSuiteRunId("Release 2.4, Sprint 3").kind);
    assertEquals(FormValidation.Kind.OK, descriptor.doCheckSuiteRunId("Kanban Release 2.4").kind);
    assertEquals(FormValidation.Kind.OK, descriptor.doCheckSuiteRunId("1196,1200").kind);
    assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckSuiteRunId("Release 2.4,").kind);
  }

  @Test
  public void readsAndValidatesAutomationTargetFromEnvironment() throws Exception {
    assertEquals(100, OctaneSuiteGateStep.automatedTestingTarget(null));
    assertEquals(100, OctaneSuiteGateStep.automatedTestingTarget(new EnvVars()));
    assertEquals(
        80,
        OctaneSuiteGateStep.automatedTestingTarget(
            new EnvVars(GateRequest.AUTOMATED_TESTING_TARGET_ENV, "80")));
    assertEquals(
        100,
        OctaneSuiteGateStep.automatedTestingTarget(
            new EnvVars(GateRequest.AUTOMATED_TESTING_TARGET_ENV, "101")));
    assertEquals(
        100,
        OctaneSuiteGateStep.automatedTestingTarget(
            new EnvVars(GateRequest.AUTOMATED_TESTING_TARGET_ENV, "500")));

    EnvVars globalTarget = new EnvVars(GateRequest.GLOBAL_AUTOMATED_TESTING_TARGET_ENV, "75");
    assertEquals(75, OctaneSuiteGateStep.automatedTestingTarget(globalTarget));

    EnvVars blankLocalTarget =
        new EnvVars(
            GateRequest.AUTOMATED_TESTING_TARGET_ENV,
            "",
            GateRequest.GLOBAL_AUTOMATED_TESTING_TARGET_ENV,
            "85");
    assertEquals(85, OctaneSuiteGateStep.automatedTestingTarget(blankLocalTarget));

    EnvVars nullLocalTarget =
        new EnvVars(
            GateRequest.AUTOMATED_TESTING_TARGET_ENV,
            "null",
            GateRequest.GLOBAL_AUTOMATED_TESTING_TARGET_ENV,
            "90");
    assertEquals(90, OctaneSuiteGateStep.automatedTestingTarget(nullLocalTarget));

    EnvVars undefinedLocalTarget =
        new EnvVars(
            GateRequest.AUTOMATED_TESTING_TARGET_ENV,
            "undefined",
            GateRequest.GLOBAL_AUTOMATED_TESTING_TARGET_ENV,
            "95");
    assertEquals(95, OctaneSuiteGateStep.automatedTestingTarget(undefinedLocalTarget));

    EnvVars localOverride =
        new EnvVars(
            GateRequest.AUTOMATED_TESTING_TARGET_ENV,
            "80",
            GateRequest.GLOBAL_AUTOMATED_TESTING_TARGET_ENV,
            "75");
    assertEquals(80, OctaneSuiteGateStep.automatedTestingTarget(localOverride));

    assertInvalidAutomationTarget("0");
    assertInvalidAutomationTarget("80.5");
    assertInvalidAutomationTarget("high");
  }

  @Test
  public void declaresEnvironmentAsRequiredPipelineContext() {
    assertTrue(
        new OctaneSuiteGateStep.DescriptorImpl().getRequiredContext().contains(EnvVars.class));
  }

  @Test
  public void readsDefinedScopeFromEnvironment() {
    EnvVars environment = new EnvVars(GateRequest.DEFINED_SCOPE_ENV, "ESA - Ada lovelace, Digisoc");

    assertEquals("ESA - Ada lovelace, Digisoc", OctaneSuiteGateStep.definedScope(environment));
    assertEquals("", OctaneSuiteGateStep.definedScope(null));
  }

  @Test
  public void graphTitlesAreTrimmedUppercasedAndTreatNullMarkersAsBlank() {
    GateRequest request = new GateRequest("server", "1");

    request.setRegressionGraphsTitle("  release regression  ");
    request.setCriticalGraphsTitle("Release blockers");

    assertEquals("RELEASE REGRESSION", request.getRegressionGraphsTitle());
    assertEquals("RELEASE BLOCKERS", request.getCriticalGraphsTitle());

    request.setRegressionGraphsTitle("undefined");
    request.setCriticalGraphsTitle("NULL");

    assertEquals("", request.getRegressionGraphsTitle());
    assertEquals("", request.getCriticalGraphsTitle());
  }

  private void assertInvalidAutomationTarget(String value) throws Exception {
    try {
      OctaneSuiteGateStep.automatedTestingTarget(
          new EnvVars(GateRequest.AUTOMATED_TESTING_TARGET_ENV, value));
    } catch (AbortException e) {
      assertEquals("AUTOMATED_TESTING_TARGET must be a positive whole number.", e.getMessage());
      return;
    }
    throw new AssertionError("Expected invalid target to be rejected: " + value);
  }
}
