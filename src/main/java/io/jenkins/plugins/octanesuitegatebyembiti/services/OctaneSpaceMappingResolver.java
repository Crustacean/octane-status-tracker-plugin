package io.jenkins.plugins.octanesuitegatebyembiti.services;

import hudson.AbortException;
import hudson.FilePath;
import io.jenkins.plugins.octanesuitegatebyembiti.configs.OctaneServerUrl;
import io.jenkins.plugins.octanesuitegatebyembiti.utils.Util;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Resolves a centrally managed Octane connection from a workspace mapping file. */
public final class OctaneSpaceMappingResolver {
  public static final String DEFAULT_MAPPING_FILE = "octane_spaces_mapping.json";
  static final int MAXIMUM_MAPPING_BYTES = 2 * 1024 * 1024;

  private static final Pattern NUMERIC_ID = Pattern.compile("[0-9]{1,18}");
  private static final Pattern WINDOWS_ABSOLUTE_PATH = Pattern.compile("^[A-Za-z]:[\\\\/].*");

  private final ObjectMapper objectMapper;

  public OctaneSpaceMappingResolver() {
    this(new ObjectMapper());
  }

  OctaneSpaceMappingResolver(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public ResolvedConnection resolve(
      FilePath workspace, String mappingFile, String sharedSpaceSelector, String workspaceSelector)
      throws IOException, InterruptedException {
    if (workspace == null) {
      throw new AbortException("A Jenkins workspace is required to resolve the Octane mapping.");
    }

    String normalizedMappingFile = normalizeMappingFile(mappingFile);
    String normalizedSharedSpaceSelector = requiredSelector("Shared space", sharedSpaceSelector);
    String normalizedWorkspaceSelector = requiredSelector("Workspace", workspaceSelector);
    FilePath mapping = workspace.child(normalizedMappingFile);
    if (!mapping.exists() || mapping.isDirectory()) {
      throw new AbortException(
          "Octane spaces mapping file was not found in the workspace: " + normalizedMappingFile);
    }

    JsonNode document;
    try (InputStream input = mapping.read()) {
      byte[] content = input.readNBytes(MAXIMUM_MAPPING_BYTES + 1);
      if (content.length > MAXIMUM_MAPPING_BYTES) {
        throw new AbortException(
            "Octane spaces mapping file exceeds the "
                + MAXIMUM_MAPPING_BYTES
                + " byte limit: "
                + normalizedMappingFile);
      }
      document = objectMapper.readTree(new String(content, StandardCharsets.UTF_8));
    } catch (AbortException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new AbortException(
          "Octane spaces mapping file is not valid JSON: " + normalizedMappingFile);
    }

    return resolve(
        document,
        normalizedSharedSpaceSelector,
        normalizedWorkspaceSelector,
        normalizedMappingFile);
  }

  ResolvedConnection resolve(
      JsonNode document, String sharedSpaceSelector, String workspaceSelector, String mappingFile)
      throws AbortException {
    JsonNode sharedSpaces = document == null ? null : document.path("shared_spaces");
    if (sharedSpaces == null || !sharedSpaces.isArray()) {
      throw new AbortException(
          "Octane spaces mapping file must contain a shared_spaces array: " + mappingFile);
    }

    JsonNode sharedSpace =
        findSelection(sharedSpaces, sharedSpaceSelector, "sharedSpaceId", "sharedSpaceName");
    if (sharedSpace == null) {
      throw new AbortException(
          "Shared space '"
              + sharedSpaceSelector
              + "' did not match a name or ID in "
              + mappingFile);
    }

    JsonNode workspaces = sharedSpace.path("workspaces");
    if (!workspaces.isArray()) {
      throw new AbortException(
          "Shared space '" + sharedSpaceSelector + "' has no workspaces array in " + mappingFile);
    }
    JsonNode selectedWorkspace =
        findSelection(workspaces, workspaceSelector, "workspaceId", "workspaceName");
    if (selectedWorkspace == null) {
      throw new AbortException(
          "Workspace '"
              + workspaceSelector
              + "' did not match a name or ID under shared space '"
              + sharedSpaceSelector
              + "' in "
              + mappingFile);
    }

    String sharedSpaceId = text(sharedSpace, "sharedSpaceId");
    String sharedSpaceName = text(sharedSpace, "sharedSpaceName");
    String workspaceId = text(selectedWorkspace, "workspaceId");
    String workspaceName = text(selectedWorkspace, "workspaceName");
    if (!NUMERIC_ID.matcher(sharedSpaceId).matches()
        || !NUMERIC_ID.matcher(workspaceId).matches()
        || sharedSpaceName.isEmpty()
        || workspaceName.isEmpty()) {
      throw new AbortException(
          "Shared space '"
              + sharedSpaceSelector
              + "' and workspace '"
              + workspaceSelector
              + "' must define names and numeric IDs in "
              + mappingFile
              + ".");
    }

    String baseUrl = text(sharedSpace, "specific_url");
    if (baseUrl.isEmpty()) {
      baseUrl = text(document, "shared_url");
    }
    if (baseUrl.isEmpty()) {
      throw new AbortException(
          "Base URL missing for space: " + sharedSpaceName + " in octane_spaces_mapping.json");
    }
    try {
      baseUrl = OctaneServerUrl.normalize(baseUrl);
    } catch (IllegalArgumentException e) {
      throw new AbortException(
          "Base URL for space '" + sharedSpaceName + "' is invalid: " + e.getMessage());
    }

    String derivedIdentifier = sharedSpaceName.toLowerCase(Locale.ROOT).replaceAll("\\s+", "_");
    String credentialsId = text(sharedSpace, "apiCredentialId");
    if (credentialsId.isEmpty()) {
      credentialsId = derivedIdentifier;
    }
    String serverId = text(sharedSpace, "serverId");
    if (serverId.isEmpty()) {
      serverId = derivedIdentifier;
    }

    return new ResolvedConnection(
        serverId,
        baseUrl,
        credentialsId,
        sharedSpaceId,
        sharedSpaceName,
        workspaceId,
        workspaceName,
        baseUrl.toLowerCase(Locale.ROOT).startsWith("http://"));
  }

  public static String normalizeMappingFile(String mappingFile) throws AbortException {
    String candidate = Util.trimToEmpty(mappingFile);
    if (candidate.isEmpty()) {
      candidate = DEFAULT_MAPPING_FILE;
    }
    String portable = candidate.replace('\\', '/');
    if (portable.startsWith("/")
        || WINDOWS_ABSOLUTE_PATH.matcher(candidate).matches()
        || portable.equals("..")
        || portable.startsWith("../")
        || portable.contains("/../")
        || portable.endsWith("/..")) {
      throw new AbortException("Octane spaces mapping file must be a workspace-relative path.");
    }
    return candidate;
  }

  private static String requiredSelector(String label, String selector) throws AbortException {
    String value = Util.trimToEmpty(selector);
    if (value.isEmpty()) {
      throw new AbortException(label + " name or ID is required for dynamic Octane mapping.");
    }
    return value;
  }

  private static JsonNode findSelection(
      JsonNode candidates, String selector, String idField, String nameField) {
    boolean numericSelector = NUMERIC_ID.matcher(selector).matches();
    for (JsonNode candidate : candidates) {
      String value = text(candidate, numericSelector ? idField : nameField);
      if (numericSelector ? selector.equals(value) : selector.equalsIgnoreCase(value)) {
        return candidate;
      }
    }
    return null;
  }

  private static String text(JsonNode node, String field) {
    if (node == null) {
      return "";
    }
    JsonNode value = node.path(field);
    return value.isMissingNode() || value.isNull() ? "" : Util.trimToEmpty(value.asString());
  }

  public record ResolvedConnection(
      String serverId,
      String baseUrl,
      String credentialsId,
      String sharedSpaceId,
      String sharedSpaceName,
      String workspaceId,
      String workspaceName,
      boolean insecureTransport) {}
}
