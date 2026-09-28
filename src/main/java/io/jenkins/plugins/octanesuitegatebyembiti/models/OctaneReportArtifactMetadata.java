package io.jenkins.plugins.octanesuitegatebyembiti.models;

import java.io.Serializable;

public final class OctaneReportArtifactMetadata implements Serializable {
  private static final long serialVersionUID = 1L;

  private final int schemaVersion;
  private final String artifactDirectory;
  private final String checksum;
  private final String updatedAt;
  private final long jsonSize;
  private final int sectionCount;
  private final boolean clientRendered;
  private final boolean building;

  public OctaneReportArtifactMetadata(
      int schemaVersion,
      String artifactDirectory,
      String checksum,
      String updatedAt,
      long jsonSize,
      int sectionCount,
      boolean clientRendered,
      boolean building) {
    this.schemaVersion = schemaVersion;
    this.artifactDirectory = artifactDirectory == null ? "" : artifactDirectory;
    this.checksum = checksum == null ? "" : checksum;
    this.updatedAt = updatedAt == null ? "" : updatedAt;
    this.jsonSize = Math.max(0L, jsonSize);
    this.sectionCount = Math.max(0, sectionCount);
    this.clientRendered = clientRendered;
    this.building = building;
  }

  public static OctaneReportArtifactMetadata empty() {
    return new OctaneReportArtifactMetadata(0, "", "", "", 0L, 0, false, false);
  }

  public int getSchemaVersion() {
    return schemaVersion;
  }

  public String getArtifactDirectory() {
    return artifactDirectory;
  }

  public String getChecksum() {
    return checksum;
  }

  public String getUpdatedAt() {
    return updatedAt;
  }

  public long getJsonSize() {
    return jsonSize;
  }

  public int getSectionCount() {
    return sectionCount;
  }

  public boolean isClientRendered() {
    return clientRendered;
  }

  public boolean isBuilding() {
    return building;
  }

  public boolean isAvailable() {
    return schemaVersion > 0 && !artifactDirectory.isBlank() && !checksum.isBlank();
  }
}
