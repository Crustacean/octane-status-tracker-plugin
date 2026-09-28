package io.jenkins.plugins.octanesuitegatebyembiti.services;

import hudson.model.Run;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneGateReportSnapshot;
import io.jenkins.plugins.octanesuitegatebyembiti.models.OctaneReportArtifactMetadata;
import io.jenkins.plugins.octanesuitegatebyembiti.utils.OctaneReportJson;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

public final class OctaneReportArtifactStore {
  static final String ROOT_DIRECTORY = "octane-suite-gate";
  static final String INDEX_FILE = "octane-index.json";
  static final String RESULTS_FILE = "octane-results.json";
  static final String SNAPSHOT_FILE = "octane-snapshot.bin.gz";
  static final int MAX_ARTIFACT_BYTES = 64 * 1024 * 1024;
  private static final long MAX_DESERIALIZED_REFERENCES = 1_000_000L;
  private static final long MAX_ARRAY_LENGTH = 1_000_000L;
  private static final long MAX_DESERIALIZATION_DEPTH = 64L;
  private static final Pattern GENERATION_CHECKSUM = Pattern.compile("[0-9a-f]{64}");

  private final ObjectMapper objectMapper;
  private final OctaneReportDataMapper dataMapper;

  public OctaneReportArtifactStore() {
    this(new ObjectMapper(), new OctaneReportDataMapper());
  }

  OctaneReportArtifactStore(ObjectMapper objectMapper, OctaneReportDataMapper dataMapper) {
    this.objectMapper = objectMapper;
    this.dataMapper = dataMapper;
  }

  public OctaneReportArtifactMetadata publish(Run<?, ?> run, OctaneGateReportSnapshot snapshot)
      throws IOException {
    OctaneReportDataMapper.ReportData reportData = dataMapper.map(snapshot);
    byte[] completeBytes = OctaneReportJson.writeBytes(reportData.complete());
    byte[] indexBytes = OctaneReportJson.writeBytes(reportData.index());
    String checksum = sha256(completeBytes);
    Path root = root(run);
    createPrivateDirectory(root);
    Path destination = root.resolve(checksum);
    if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
      requireDirectory(destination);
    } else {
      Path temporary = root.resolve(".tmp-" + UUID.randomUUID());
      createPrivateDirectory(temporary);
      boolean published = false;
      try {
        writeBytes(temporary.resolve(RESULTS_FILE), completeBytes);
        writeBytes(temporary.resolve(INDEX_FILE), indexBytes);
        int sectionIndex = 0;
        for (Map<String, Object> section : reportData.sections()) {
          writeBytes(
              temporary.resolve(sectionFile(sectionIndex++)), OctaneReportJson.writeBytes(section));
        }
        writeSnapshot(temporary.resolve(SNAPSHOT_FILE), snapshot);
        moveDirectory(temporary, destination);
        published = true;
      } finally {
        if (!published) {
          deleteRecursively(temporary);
        }
      }
    }
    return new OctaneReportArtifactMetadata(
        OctaneReportDataMapper.SCHEMA_VERSION,
        ROOT_DIRECTORY + "/" + checksum,
        checksum,
        snapshot.getUpdatedAt(),
        completeBytes.length,
        reportData.sections().size(),
        snapshot.isClientRenderedReport(),
        snapshot.isBuilding());
  }

  public OctaneGateReportSnapshot loadSnapshot(Run<?, ?> run, OctaneReportArtifactMetadata metadata)
      throws IOException {
    if (run == null || metadata == null || !metadata.isAvailable()) {
      return null;
    }
    Path path = artifactDirectory(run, metadata).resolve(SNAPSHOT_FILE);
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      return null;
    }
    requireRegularFile(path);
    verifyArtifactSize(path);
    try (ObjectInputStream input =
        new ObjectInputStream(
            new GZIPInputStream(
                new BufferedInputStream(Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS))))) {
      input.setObjectInputFilter(OctaneReportArtifactStore::filterSnapshotObject);
      Object value = input.readObject();
      if (value instanceof OctaneGateReportSnapshot reportSnapshot) {
        return reportSnapshot;
      }
      throw new IOException("Octane report snapshot artifact has an unexpected type.");
    } catch (ClassNotFoundException e) {
      throw new IOException("Unable to load the Octane report snapshot artifact.", e);
    }
  }

  public ObjectNode readIndex(Run<?, ?> run, OctaneReportArtifactMetadata metadata)
      throws IOException {
    return readJsonArtifact(run, metadata, INDEX_FILE);
  }

  public ObjectNode readResults(Run<?, ?> run, OctaneReportArtifactMetadata metadata)
      throws IOException {
    return readJsonArtifact(run, metadata, RESULTS_FILE);
  }

  public ObjectNode readSectionPage(
      Run<?, ?> run, OctaneReportArtifactMetadata metadata, int section, int cursor, int limit)
      throws IOException {
    if (section < 0 || section >= metadata.getSectionCount()) {
      throw new IOException("Invalid Octane report section.");
    }
    ObjectNode source = readJsonArtifact(run, metadata, sectionFile(section));
    ArrayNode bars = source.withArray("bars");
    int safeCursor = Math.min(Math.max(0, cursor), bars.size());
    int safeLimit = Math.min(200, Math.max(1, limit));
    int end = safeCursor + Math.min(safeLimit, bars.size() - safeCursor);
    ArrayNode page = objectMapper.createArrayNode();
    for (int index = safeCursor; index < end; index++) {
      page.add(bars.get(index));
    }
    source.set("bars", page);
    source.put("cursor", safeCursor);
    source.put("nextCursor", end < bars.size() ? end : -1);
    source.put("totalBars", bars.size());
    return source;
  }

  public void deleteGeneration(Run<?, ?> run, OctaneReportArtifactMetadata metadata) {
    if (run == null || metadata == null || !metadata.isAvailable()) {
      return;
    }
    try {
      deleteRecursively(artifactDirectory(run, metadata));
    } catch (IOException ignored) {
      // A stale generation is harmless and can be cleaned with the build later.
    }
  }

  private byte[] readArtifact(Run<?, ?> run, OctaneReportArtifactMetadata metadata, String fileName)
      throws IOException {
    if (run == null || metadata == null || !metadata.isAvailable()) {
      throw new IOException("Octane report data is not available for this build.");
    }
    Path directory = artifactDirectory(run, metadata);
    Path path = directory.resolve(fileName).normalize();
    if (!directory.equals(path.getParent())) {
      throw new IOException("Octane report data is incomplete for this build.");
    }
    requireRegularFile(path);
    verifyArtifactSize(path);
    try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
      byte[] content = input.readNBytes(MAX_ARTIFACT_BYTES + 1);
      if (content.length > MAX_ARTIFACT_BYTES) {
        throw new IOException("Octane report artifact exceeds the byte safety limit.");
      }
      return content;
    }
  }

  private ObjectNode readJsonArtifact(
      Run<?, ?> run, OctaneReportArtifactMetadata metadata, String fileName) throws IOException {
    return OctaneReportJson.readObject(readArtifact(run, metadata, fileName));
  }

  private static ObjectInputFilter.Status filterSnapshotObject(ObjectInputFilter.FilterInfo info) {
    if (exceedsDeserializationLimits(info)) {
      return ObjectInputFilter.Status.REJECTED;
    }
    Class<?> serialClass = info.serialClass();
    if (serialClass == null) {
      return ObjectInputFilter.Status.UNDECIDED;
    }
    while (serialClass.isArray()) {
      serialClass = serialClass.getComponentType();
    }
    return serialClass.isPrimitive() || isAllowedSnapshotClass(serialClass.getName())
        ? ObjectInputFilter.Status.ALLOWED
        : ObjectInputFilter.Status.REJECTED;
  }

  private static boolean exceedsDeserializationLimits(ObjectInputFilter.FilterInfo info) {
    return info.depth() > MAX_DESERIALIZATION_DEPTH
        || info.references() > MAX_DESERIALIZED_REFERENCES
        || info.streamBytes() > MAX_ARTIFACT_BYTES
        || (info.arrayLength() >= 0 && info.arrayLength() > MAX_ARRAY_LENGTH);
  }

  private static boolean isAllowedSnapshotClass(String className) {
    return className.startsWith("io.jenkins.plugins.octanesuitegatebyembiti.models.")
        || className.startsWith("io.jenkins.plugins.octanesuitegatebyembiti.entities.")
        || className.startsWith("java.lang.")
        || className.startsWith("java.time.")
        || className.startsWith("java.util.");
  }

  private void verifyArtifactSize(Path path) throws IOException {
    if (Files.size(path) > MAX_ARTIFACT_BYTES) {
      throw new IOException(
          "Octane report artifact exceeds the " + MAX_ARTIFACT_BYTES + " byte safety limit.");
    }
  }

  private Path artifactDirectory(Run<?, ?> run, OctaneReportArtifactMetadata metadata)
      throws IOException {
    String checksum = metadata.getChecksum();
    if (!GENERATION_CHECKSUM.matcher(checksum).matches()
        || !(ROOT_DIRECTORY + "/" + checksum).equals(metadata.getArtifactDirectory())) {
      throw new IOException("Invalid Octane report artifact path.");
    }
    Path root = root(run);
    requireDirectory(root);
    Path directory = root.resolve(checksum);
    requireDirectory(directory);
    return directory;
  }

  private Path root(Run<?, ?> run) throws IOException {
    return run.getRootDir().toPath().toRealPath().resolve(ROOT_DIRECTORY);
  }

  private static void requireDirectory(Path path) throws IOException {
    if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("Octane report artifact directory is missing or symbolic.");
    }
  }

  private static void requireRegularFile(Path path) throws IOException {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("Octane report artifact is missing, non-regular, or symbolic.");
    }
  }

  private static FileAttribute<?>[] privateAttributes(Path path, String permissions)
      throws IOException {
    Path parent = path.getParent();
    if (parent == null) {
      throw new IOException("Octane report artifact path must have a parent directory.");
    }
    if (Files.getFileStore(parent).supportsFileAttributeView(PosixFileAttributeView.class)) {
      return new FileAttribute<?>[] {
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(permissions))
      };
    }
    // Non-POSIX filesystems retain the Jenkins build directory's inherited ACL.
    return new FileAttribute<?>[0];
  }

  private static void createPrivateDirectory(Path path) throws IOException {
    Files.createDirectories(path, privateAttributes(path, "rwx------"));
    requireDirectory(path);
    PosixFileAttributeView permissions =
        Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    if (permissions != null) {
      // Also protect generations written before owner-only creation was introduced.
      permissions.setPermissions(PosixFilePermissions.fromString("rwx------"));
    }
  }

  private static OutputStream newArtifactOutput(Path path) throws IOException {
    return Channels.newOutputStream(
        Files.newByteChannel(
            path,
            Set.of(
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
            privateAttributes(path, "rw-------")));
  }

  private void writeSnapshot(Path path, OctaneGateReportSnapshot snapshot) throws IOException {
    try (ObjectOutputStream output =
        new ObjectOutputStream(
            new GZIPOutputStream(new BufferedOutputStream(newArtifactOutput(path))))) {
      output.writeObject(snapshot);
    }
  }

  private void writeBytes(Path path, byte[] content) throws IOException {
    try (OutputStream output = newArtifactOutput(path)) {
      output.write(content);
    }
  }

  private void moveDirectory(Path source, Path destination) throws IOException {
    try {
      Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
    } catch (java.nio.file.FileAlreadyExistsException e) {
      deleteRecursively(source);
    } catch (java.nio.file.AtomicMoveNotSupportedException e) {
      Files.move(source, destination);
    }
  }

  void deleteRecursively(Path path) throws IOException {
    if (path == null || !Files.exists(path)) {
      return;
    }
    Files.walkFileTree(
        path,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
              throws IOException {
            Files.deleteIfExists(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path directory, IOException failure)
              throws IOException {
            if (failure != null) {
              throw failure;
            }
            Files.deleteIfExists(directory);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private String sectionFile(int section) {
    return "section-" + section + ".json";
  }

  private String sha256(byte[] value) throws IOException {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException e) {
      throw new IOException("SHA-256 is unavailable while writing Octane report data.", e);
    }
  }
}
