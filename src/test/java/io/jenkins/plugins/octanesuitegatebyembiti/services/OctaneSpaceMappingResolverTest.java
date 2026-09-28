package io.jenkins.plugins.octanesuitegatebyembiti.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.AbortException;
import hudson.FilePath;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class OctaneSpaceMappingResolverTest {
  @TempDir public Path temporary;

  @Test
  public void resolvesRootUrlAndDerivedIdentifiersByName() throws Exception {
    FilePath workspace = workspaceWithMapping(mapping("", ""));

    OctaneSpaceMappingResolver.ResolvedConnection connection =
        new OctaneSpaceMappingResolver()
            .resolve(workspace, "octane_spaces_mapping.json", "Default Shared Space", "Payments");

    assertEquals("default_shared_space", connection.serverId());
    assertEquals("default_shared_space", connection.credentialsId());
    assertEquals("https://octane.example.test", connection.baseUrl());
    assertEquals("1001", connection.sharedSpaceId());
    assertEquals("5001", connection.workspaceId());
    assertFalse(connection.insecureTransport());
  }

  @Test
  public void resolvesSpecificUrlAndExplicitIdentifiersByNumericIds() throws Exception {
    FilePath workspace =
        workspaceWithMapping(
            mapping(
                    "\"specific_url\": \"https://octane-canary.example.test\",",
                    "\"serverId\": \"octane-canary\",\n"
                        + "      \"apiCredentialId\": \"octane-canary-key\",")
                .replace("\"1001\"", "1001")
                .replace("\"5001\"", "5001"));

    OctaneSpaceMappingResolver.ResolvedConnection connection =
        new OctaneSpaceMappingResolver()
            .resolve(workspace, "octane_spaces_mapping.json", "1001", "5001");

    assertEquals("octane-canary", connection.serverId());
    assertEquals("octane-canary-key", connection.credentialsId());
    assertEquals("https://octane-canary.example.test", connection.baseUrl());
    assertFalse(connection.insecureTransport());
  }

  @Test
  public void rejectsMissingBaseUrlWithActionableMessage() throws Exception {
    FilePath workspace =
        workspaceWithMapping(mapping("", "").replace("https://octane.example.test", ""));

    AbortException failure =
        assertThrows(
            AbortException.class,
            () ->
                new OctaneSpaceMappingResolver()
                    .resolve(
                        workspace,
                        "octane_spaces_mapping.json",
                        "Default Shared Space",
                        "Payments"));

    assertEquals(
        "Base URL missing for space: Default Shared Space in octane_spaces_mapping.json",
        failure.getMessage());
  }

  @Test
  public void carriesCredentialIdsWithoutDecryptingAndPrefersSpecificUrl() throws Exception {
    for (String override : new String[] {"", "  ", "octane-specific-url"}) {
      String json =
          mapping("\"specific_url\": \"" + override + "\",", "")
              .replace("https://octane.example.test", "octane-shared-url");
      var connection =
          new OctaneSpaceMappingResolver()
              .resolve(workspaceWithMapping(json), "octane_spaces_mapping.json", "1001", "5001");
      assertEquals(override.isBlank() ? "octane-shared-url" : override, connection.baseUrl());
    }
  }

  @Test
  public void rejectsPathsOutsideTheWorkspace() {
    AbortException failure =
        assertThrows(
            AbortException.class,
            () -> OctaneSpaceMappingResolver.normalizeMappingFile("../mapping.json"));

    assertEquals(
        "Octane spaces mapping file must be a workspace-relative path.", failure.getMessage());

    assertThrows(
        AbortException.class, () -> OctaneSpaceMappingResolver.normalizeMappingFile("config/.."));
  }

  @Test
  public void rejectsMalformedJsonWithActionableMessage() throws Exception {
    FilePath workspace = workspaceWithMapping("{not-json");

    AbortException failure =
        assertThrows(
            AbortException.class,
            () ->
                new OctaneSpaceMappingResolver()
                    .resolve(
                        workspace,
                        "octane_spaces_mapping.json",
                        "Default Shared Space",
                        "Payments"));

    assertEquals(
        "Octane spaces mapping file is not valid JSON: octane_spaces_mapping.json",
        failure.getMessage());
  }

  @Test
  public void rejectsOversizedMappingFiles() throws Exception {
    File directory = Files.createTempDirectory(temporary, "octane-test-").toFile();
    File mapping = new File(directory, "octane_spaces_mapping.json");
    Files.writeString(
        mapping.toPath(),
        " ".repeat(OctaneSpaceMappingResolver.MAXIMUM_MAPPING_BYTES + 1),
        StandardCharsets.UTF_8);

    AbortException failure =
        assertThrows(
            AbortException.class,
            () ->
                new OctaneSpaceMappingResolver()
                    .resolve(
                        new FilePath(directory),
                        "octane_spaces_mapping.json",
                        "Default Shared Space",
                        "Payments"));

    assertTrue(failure.getMessage().contains("exceeds the"));
  }

  private FilePath workspaceWithMapping(String content) throws Exception {
    File directory = Files.createTempDirectory(temporary, "octane-test-").toFile();
    Files.writeString(
        new File(directory, "octane_spaces_mapping.json").toPath(),
        content,
        StandardCharsets.UTF_8);
    return new FilePath(directory);
  }

  private String mapping(String specificUrl, String identifiers) {
    return """
        {
          "shared_url": "https://octane.example.test",
          "shared_spaces": [{
            "sharedSpaceId": "1001",
            "sharedSpaceName": "Default Shared Space",
            %s
            %s
            "workspaces": [{
              "workspaceId": "5001",
              "workspaceName": "Payments"
            }]
          }]
        }
        """
        .formatted(specificUrl, identifiers);
  }
}
