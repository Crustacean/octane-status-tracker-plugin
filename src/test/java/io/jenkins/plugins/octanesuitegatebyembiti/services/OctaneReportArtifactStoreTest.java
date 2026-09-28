package io.jenkins.plugins.octanesuitegatebyembiti.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneGateReportSnapshot;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneReportArtifactMetadata;
import io.jenkins.plugins.octanesuitegatebyembiti.utils.OctaneReportJson;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

public class OctaneReportArtifactStoreTest {
  @Rule public JenkinsRule jenkins = new JenkinsRule();

  @Test
  public void publishesAtomicBoundedArtifactsAndReloadsCompatibilitySnapshot() throws Exception {
    FreeStyleProject project = jenkins.createFreeStyleProject();
    FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
    OctaneGateReportSnapshot snapshot = OctaneScaleTestFixture.snapshot(0, 500, 1);
    OctaneReportArtifactStore store = new OctaneReportArtifactStore();

    OctaneReportArtifactMetadata metadata = store.publish(build, snapshot);

    assertTrue(metadata.isAvailable());
    assertEquals(OctaneReportDataMapper.SCHEMA_VERSION, metadata.getSchemaVersion());
    assertTrue(metadata.getJsonSize() < 5_000_000L);
    assertTrue(OctaneReportJson.writeBytes(store.readIndex(build, metadata)).length < 250_000);
    assertEquals(1, metadata.getSectionCount());
    OctaneGateReportSnapshot reloaded = store.loadSnapshot(build, metadata);
    assertNotNull(reloaded);
    assertEquals(snapshot.getUpdatedAt(), reloaded.getUpdatedAt());
    assertEquals(snapshot.getSuiteAttributions(), reloaded.getSuiteAttributions());
    assertEquals(1, reloaded.getReportSections().get(0).getSuiteRuns().size());
    assertEquals("Status", reloaded.getReportSections().get(0).getXAxis());
    assertEquals(500, reloaded.getProjectTestTotal());
    assertTrue(
        Files.isRegularFile(
            build
                .getRootDir()
                .toPath()
                .resolve(metadata.getArtifactDirectory())
                .resolve(OctaneReportArtifactStore.RESULTS_FILE)));
  }

  @Test
  public void artifactsAreOwnerOnlyAndExistingArtifactRootIsHardened() throws Exception {
    FreeStyleBuild build = jenkins.buildAndAssertSuccess(jenkins.createFreeStyleProject());
    Path buildRoot = build.getRootDir().toPath();
    assumeTrue(
        Files.getFileStore(buildRoot).supportsFileAttributeView(PosixFileAttributeView.class));
    Path root = buildRoot.resolve(OctaneReportArtifactStore.ROOT_DIRECTORY);
    Files.createDirectory(root);
    Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxr-xr-x"));
    OctaneReportArtifactStore store = new OctaneReportArtifactStore();
    OctaneGateReportSnapshot snapshot = OctaneScaleTestFixture.snapshot(0, 5, 1);
    OctaneReportArtifactMetadata metadata = store.publish(build, snapshot);
    Path generation = buildRoot.resolve(metadata.getArtifactDirectory());

    try (var artifacts = Files.walk(root)) {
      for (Path path : artifacts.toList()) {
        assertEquals(
            path.toString(),
            PosixFilePermissions.fromString(Files.isDirectory(path) ? "rwx------" : "rw-------"),
            Files.getPosixFilePermissions(path));
      }
    }
    Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxr-xr-x"));
    assertEquals(metadata.getChecksum(), store.publish(build, snapshot).getChecksum());
    assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(root));
    assertTrue(Files.isRegularFile(generation.resolve(OctaneReportArtifactStore.SNAPSHOT_FILE)));
    assertEquals(5, store.loadSnapshot(build, metadata).getProjectTestTotal());
  }

  @Test
  public void paginationBoundsRejectInvalidSectionsAndClampExtremeIntegers() throws Exception {
    FreeStyleBuild build = jenkins.buildAndAssertSuccess(jenkins.createFreeStyleProject());
    OctaneReportArtifactStore store = new OctaneReportArtifactStore();
    OctaneReportArtifactMetadata metadata =
        store.publish(build, OctaneScaleTestFixture.snapshot(0, 1, 1));
    Path section =
        build
            .getRootDir()
            .toPath()
            .resolve(metadata.getArtifactDirectory())
            .resolve("section-0.json");
    Files.writeString(section, "{\"bars\":[{\"id\":1},{\"id\":2},{\"id\":3}]}");
    for (int cursor : new int[] {Integer.MIN_VALUE, -1, 0, 2, 3, Integer.MAX_VALUE}) {
      for (int limit : new int[] {Integer.MIN_VALUE, 0, 1, 200, Integer.MAX_VALUE}) {
        var page = store.readSectionPage(build, metadata, 0, cursor, limit);
        int start = Math.max(0, Math.min(cursor, 3));
        int count = Math.min(3 - start, Math.max(1, Math.min(limit, 200)));
        assertEquals(start, page.path("cursor").asInt());
        assertEquals(count, page.path("bars").size());
        assertEquals(3, page.path("totalBars").asInt());
        assertEquals(start + count < 3 ? start + count : -1, page.path("nextCursor").asInt());
      }
    }
    for (int invalid : new int[] {Integer.MIN_VALUE, -1, 1, Integer.MAX_VALUE}) {
      assertThrows(IOException.class, () -> store.readSectionPage(build, metadata, invalid, 0, 10));
    }
    Files.writeString(section, "{\"bars\":[]}");
    var empty = store.readSectionPage(build, metadata, 0, Integer.MAX_VALUE, Integer.MAX_VALUE);
    assertEquals(0, empty.path("bars").size());
    assertEquals(-1, empty.path("nextCursor").asInt());
  }

  @Test
  public void rejectsUnexpectedClassesInPersistedSnapshotArtifact() throws Exception {
    FreeStyleProject project = jenkins.createFreeStyleProject();
    FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
    String checksum = "a".repeat(64);
    String relativeDirectory = OctaneReportArtifactStore.ROOT_DIRECTORY + "/" + checksum;
    Path directory = build.getRootDir().toPath().resolve(relativeDirectory);
    Files.createDirectories(directory);
    Path snapshotPath = directory.resolve(OctaneReportArtifactStore.SNAPSHOT_FILE);
    try (ObjectOutputStream output =
        new ObjectOutputStream(
            new GZIPOutputStream(
                new BufferedOutputStream(
                    Files.newOutputStream(snapshotPath, StandardOpenOption.CREATE_NEW))))) {
      output.writeObject(new File("unexpected-class"));
    }
    OctaneReportArtifactMetadata metadata =
        new OctaneReportArtifactMetadata(
            1, relativeDirectory, checksum, Instant.now().toString(), 1L, 0, false, false);

    IOException failure =
        assertThrows(
            IOException.class, () -> new OctaneReportArtifactStore().loadSnapshot(build, metadata));

    assertTrue(failure.getMessage().contains("filter status: REJECTED"));
  }

  @Test
  public void deletesNestedArtifactTreesInPostOrder() throws Exception {
    Path root = Files.createTempDirectory("octane-artifact-cleanup-");
    Path nested = Files.createDirectories(root.resolve("one/two/three"));
    Files.writeString(nested.resolve("report.json"), "{}");

    new OctaneReportArtifactStore().deleteRecursively(root);

    assertFalse(Files.exists(root));
  }

  @Test
  public void servesOnlyValidatedHtmlSafeJsonAndPreservesDecodedText() throws Exception {
    FreeStyleBuild build = jenkins.buildAndAssertSuccess(jenkins.createFreeStyleProject());
    OctaneReportArtifactStore store = new OctaneReportArtifactStore();
    OctaneReportArtifactMetadata metadata =
        store.publish(build, OctaneScaleTestFixture.snapshot(0, 1, 1));
    Path directory = build.getRootDir().toPath().resolve(metadata.getArtifactDirectory());
    String text = "</script><img src=x onerror=alert('x')>&";
    String json = new ObjectMapper().writeValueAsString(java.util.Map.of("name", text));
    for (String file :
        List.of(
            OctaneReportArtifactStore.INDEX_FILE,
            OctaneReportArtifactStore.RESULTS_FILE,
            "section-0.json")) {
      Files.writeString(directory.resolve(file), json);
    }
    for (ObjectNode response :
        List.of(
            store.readIndex(build, metadata),
            store.readResults(build, metadata),
            store.readSectionPage(build, metadata, 0, 0, 10))) {
      String body = OctaneReportJson.writeString(response);
      assertFalse(body.contains("<"));
      assertFalse(body.contains(">"));
      assertFalse(body.contains("&"));
      assertEquals(text, new ObjectMapper().readTree(body).path("name").asString());
    }
    for (String invalid : List.of("<script>alert(1)</script>", "{} {}", "[]", "null")) {
      Files.writeString(directory.resolve(OctaneReportArtifactStore.INDEX_FILE), invalid);
      assertThrows(IOException.class, () -> store.readIndex(build, metadata));
    }
    assertThrows(IOException.class, () -> store.readSectionPage(build, metadata, -1, 0, 10));
    assertThrows(
        IOException.class,
        () -> store.readSectionPage(build, metadata, metadata.getSectionCount(), 0, 10));
  }

  @Test
  public void rejectsTraversalSymlinksAndOversizedArtifacts() throws Exception {
    FreeStyleBuild build = jenkins.buildAndAssertSuccess(jenkins.createFreeStyleProject());
    OctaneReportArtifactStore store = new OctaneReportArtifactStore();
    OctaneReportArtifactMetadata metadata =
        store.publish(build, OctaneScaleTestFixture.snapshot(0, 1, 1));
    Path root = build.getRootDir().toPath();
    Path directory = root.resolve(metadata.getArtifactDirectory());
    for (String path :
        List.of("../outside", directory.toString(), "octane-suite-gate/../outside")) {
      OctaneReportArtifactMetadata invalid =
          new OctaneReportArtifactMetadata(
              1, path, metadata.getChecksum(), Instant.now().toString(), 1, 1, false, false);
      assertThrows(IOException.class, () -> store.readIndex(build, invalid));
      store.deleteGeneration(build, invalid);
      assertTrue(Files.isDirectory(directory));
    }
    Path outside = root.resolve("outside.json");
    Files.writeString(outside, "{}");
    for (String file :
        List.of(OctaneReportArtifactStore.INDEX_FILE, OctaneReportArtifactStore.SNAPSHOT_FILE)) {
      Path artifact = directory.resolve(file);
      Files.delete(artifact);
      Files.createSymbolicLink(artifact, outside);
      assertThrows(
          IOException.class,
          () -> {
            if (file.equals(OctaneReportArtifactStore.INDEX_FILE)) {
              store.readIndex(build, metadata);
            } else {
              store.loadSnapshot(build, metadata);
            }
          });
      Files.delete(artifact);
    }
    Path index = directory.resolve(OctaneReportArtifactStore.INDEX_FILE);
    try (java.nio.channels.FileChannel channel =
        java.nio.channels.FileChannel.open(
            index, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
      channel.position(OctaneReportArtifactStore.MAX_ARTIFACT_BYTES);
      channel.write(java.nio.ByteBuffer.wrap(new byte[] {0}));
    }
    assertThrows(IOException.class, () -> store.readIndex(build, metadata));
    Path moved = root.resolve("moved-generation");
    Files.move(directory, moved);
    Files.createSymbolicLink(directory, moved);
    assertThrows(IOException.class, () -> store.readIndex(build, metadata));
    store.deleteGeneration(build, metadata);
    assertTrue(Files.exists(moved));
    Files.delete(directory);
    Path artifactRoot = root.resolve(OctaneReportArtifactStore.ROOT_DIRECTORY);
    Files.delete(artifactRoot);
    Files.createSymbolicLink(artifactRoot, moved);
    assertThrows(IOException.class, () -> store.readIndex(build, metadata));
    assertThrows(
        IOException.class, () -> store.publish(build, OctaneScaleTestFixture.snapshot(0, 1, 1)));
    assertEquals("{}", Files.readString(outside));
  }
}
