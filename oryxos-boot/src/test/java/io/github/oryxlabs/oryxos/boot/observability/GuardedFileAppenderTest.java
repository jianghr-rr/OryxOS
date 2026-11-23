package io.github.oryxlabs.oryxos.boot.observability;

import static io.github.oryxlabs.oryxos.boot.observability.ObservabilitySupport.*;
import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.status.Status;
import io.github.oryxlabs.oryxos.boot.support.PersistentFixture;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

@Tag("f004-observability")
class GuardedFileAppenderTest {
  @TempDir Path root;
  @TempDir Path logs;

  @Test
  void unwritableLogDirectoryWarnsAndServiceStillStarts() throws Exception {
    Path readOnly = Files.createDirectory(logs.resolve("read-only"));
    Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("r-xr-xr-x"));
    var buffer = new ByteArrayOutputStream();
    PrintStream original = System.err;
    System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
    try (var fixture = new PersistentFixture(root);
        var context =
            start(
                fixture,
                Map.of("spring.profiles.active", "prod", "oryxos.log.dir", readOnly.toString()));
        var http = HttpClient.newHttpClient()) {
      assertEquals(
          200, send(http, "GET", origin(context) + "/api/v1/health", null, Map.of()).statusCode());
      var statuses =
          ((LoggerContext) LoggerFactory.getILoggerFactory())
              .getStatusManager()
              .getCopyOfStatusList();
      assertTrue(
          statuses.stream().noneMatch(s -> s.getLevel() == Status.ERROR),
          "logback must not report errors: " + statuses);
    } finally {
      System.setErr(original);
      Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("rwxr-xr-x"));
    }
    assertTrue(
        buffer
            .toString(StandardCharsets.UTF_8)
            .contains("[oryxos-logging] WARN log directory not writable: " + readOnly),
        buffer.toString(StandardCharsets.UTF_8));
    try (var files = Files.list(readOnly)) {
      assertEquals(0, files.count());
    }
  }

  @Test
  void rollingDefaultsApplyAndEnvironmentOverridesWin() throws Exception {
    try (var fixture = new PersistentFixture(root);
        var context =
            start(
                fixture,
                Map.of("spring.profiles.active", "prod", "oryxos.log.dir", logs.toString()))) {
      var logback = (LoggerContext) LoggerFactory.getILoggerFactory();
      assertEquals("50MB", logback.getProperty("ORYXOS_LOG_MAX_FILE_SIZE"));
      assertEquals("14", logback.getProperty("ORYXOS_LOG_MAX_HISTORY"));
      assertEquals("1GB", logback.getProperty("ORYXOS_LOG_TOTAL_SIZE_CAP"));
    }
    Path other = Files.createDirectory(logs.resolve("other"));
    try (var fixture = new PersistentFixture(root.resolve("second"));
        var context =
            start(
                fixture,
                Map.of(
                    "spring.profiles.active", "prod",
                    "oryxos.log.dir", other.toString(),
                    "oryxos.log.max-file-size", "10MB",
                    "oryxos.log.max-history", "3",
                    "oryxos.log.total-size-cap", "200MB"))) {
      var logback = (LoggerContext) LoggerFactory.getILoggerFactory();
      assertEquals("10MB", logback.getProperty("ORYXOS_LOG_MAX_FILE_SIZE"));
      assertEquals("3", logback.getProperty("ORYXOS_LOG_MAX_HISTORY"));
      assertEquals("200MB", logback.getProperty("ORYXOS_LOG_TOTAL_SIZE_CAP"));
      assertEquals(other.toString(), logback.getProperty("ORYXOS_LOG_DIR"));
    }
    assertTrue(Files.isRegularFile(other.resolve("oryxos.json")));
  }
}
