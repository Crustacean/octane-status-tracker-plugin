package io.jenkins.plugins.octanesuitegatebyembiti.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class SourceSecurityAuditTest {
  private static final Pattern HARDCODED_SECRET =
      Pattern.compile(
          "(?i)(?:password|client[_-]?secret|api[_-]?key|access[_-]?token)\\s*[=:]\\s*[\\\"](?!\\$|\\{|<)[^\\\"]{8,}[\\\"]");

  @Test
  void productionSourcesContainNoHardcodedCredentials() throws IOException {
    List<String> findings = new ArrayList<>();
    try (Stream<Path> files = Files.walk(Path.of("src/main"))) {
      files
          .filter(Files::isRegularFile)
          .filter(SourceSecurityAuditTest::isAuditedSource)
          .forEach(path -> scan(path, findings));
    }
    assertTrue(findings.isEmpty(), () -> "Potential hardcoded credentials: " + findings);
  }

  @Test
  void productionJavaAvoidsDirectProcessAndTrustBypassApis() throws IOException {
    String production = readTree(Path.of("src/main/java"), ".java");

    assertFalse(production.contains("Runtime.getRuntime().exec("));
    assertFalse(production.contains("new ProcessBuilder("));
    assertFalse(production.contains("setDefaultHostnameVerifier("));
    assertFalse(production.contains("setDefaultSSLSocketFactory("));
    assertFalse(
        Pattern.compile("\\.to(?:Lower|Upper)Case\\(\\s*\\)").matcher(production).find(),
        "Protocol/status/metric identifiers must use an explicit locale.");
  }

  private static boolean isAuditedSource(Path path) {
    String name = path.getFileName().toString();
    return name.endsWith(".java");
  }

  private static void scan(Path path, List<String> findings) {
    try {
      String value = Files.readString(path, StandardCharsets.UTF_8);
      if (HARDCODED_SECRET.matcher(value).find()) {
        findings.add(path.toString());
      }
    } catch (IOException e) {
      throw new IllegalStateException("Could not audit " + path, e);
    }
  }

  private static String readTree(Path root, String suffix) throws IOException {
    StringBuilder value = new StringBuilder();
    try (Stream<Path> files = Files.walk(root)) {
      for (Path path :
          files.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(suffix)).toList()) {
        value.append(Files.readString(path, StandardCharsets.UTF_8));
      }
    }
    return value.toString();
  }
}
