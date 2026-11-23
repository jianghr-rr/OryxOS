package io.github.oryxlabs.oryxos.core.config;

import static org.junit.jupiter.api.Assertions.*;

import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.runtime.*;
import io.github.oryxlabs.oryxos.core.security.*;
import io.github.oryxlabs.oryxos.core.workspace.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Tag("f0-foundation")
class RuntimeConfigContractTest {
  @TempDir Path root;

  WorkspaceHandle workspace() {
    return WorkspaceResolver.resolve(root.toString());
  }

  RuntimeConfig load(String content, Map<String, String> environment) throws Exception {
    Files.writeString(root.resolve("runtime.yaml"), content);
    return new RuntimeConfigLoader(environment, new Redactor()).load(workspace());
  }

  @Test
  void providerFailuresStayLocalAndSecretsNeverEnterDiagnostics() throws Exception {
    String yaml =
        """
        schema_version: 1
        providers:
          good:
            protocol: openai_chat_completions
            endpoint: https://example.invalid
            completions_path: /v1/chat/completions
            api_key: ${GOOD_KEY}
          broken:
            protocol: openai_chat_completions
            endpoint: https://example.invalid
            completions_path: /v1/chat/completions
            api_key: ${MISSING_KEY}
        """;
    var config = load(yaml, Map.of("GOOD_KEY", "CANARY-secret", "timezone", "UTC"));
    assertEquals("Asia/Shanghai", config.timezone().getId());
    assertTrue(config.providers().get("good").available());
    assertFalse(config.providers().get("broken").available());
    assertFalse(config.toString().contains("CANARY-secret"));
    for (String invalid :
        List.of(
            "ftp://example.invalid",
            "https://user:CANARY-secret@example.invalid",
            "https://example.invalid/v1")) {
      var invalidConfig =
          load(
              yaml.replaceFirst("https://example.invalid", invalid),
              Map.of("GOOD_KEY", "CANARY-secret"));
      assertFalse(invalidConfig.providers().get("good").available());
      assertFalse(invalidConfig.toString().contains("CANARY-secret"));
    }
    var options =
        load(
            yaml.replace(
                "api_key: ${GOOD_KEY}",
                "api_key: ${GOOD_KEY}\n    options: {thinking: {type: invalid}}"),
            Map.of("GOOD_KEY", "CANARY-secret"));
    assertFalse(options.providers().get("good").available());
    var redactor = new Redactor();
    redactor.register("CANARY-secret");
    String safe =
        redactor.redact(
            "args=CANARY-secret stderr: https://example.invalid/CANARY-secret?q=another Authorization: Bearer hidden");
    assertFalse(safe.contains("CANARY-secret"));
    assertFalse(safe.contains("hidden"));
    assertFalse(safe.contains("another"));
  }

  @Test
  void unsafeYamlAndGlobalErrorsFailClosed() throws Exception {
    for (String input :
        List.of(
            "providers: [CANARY-secret: [",
            "schema_version: 1\ntimezone: Not/AZone",
            "schema_version: 1\nproviders: {x: {}, x: {}}",
            "schema_version: 1\nx: !!java.lang.ProcessBuilder {}",
            "schema_version: 1\nx: &x [1]\ny: *x",
            "schema_version: 1\nx: " + "[".repeat(65) + "0" + "]".repeat(65),
            "schema_version: 1\nx: " + "z".repeat(1024 * 1024),
            "schema_version: 2",
            "schema_version: 1\ntools: {shell: {allowed_commands: [sh]}}")) {
      var error = assertThrows(OryxError.class, () -> load(input, Map.of()));
      assertEquals("CONFIGURATION_ERROR", error.code());
      assertFalse(error.toString().contains("CANARY-secret"));
    }
  }

  @Test
  void secretReferencesHaveExplicitPrecedenceAndNoExpansion() throws Exception {
    Path local = root.resolve("secrets.local.yaml");
    Files.writeString(local, "KEY: local-value\n");
    Files.setPosixFilePermissions(local, PosixFilePermissions.fromString("rw-------"));
    var resolver = new SecretResolver(Map.of("KEY", "environment-value"), local, new Redactor());
    assertEquals("environment-value", resolver.resolve("${KEY}"));
    assertEquals(
        "local-value", new SecretResolver(Map.of(), local, new Redactor()).resolve("${KEY}"));
    assertThrows(OryxError.class, () -> resolver.resolve("literal-key"));
    assertThrows(OryxError.class, () -> resolver.resolve("${MISSING}"));
    assertThrows(
        OryxError.class,
        () ->
            new SecretResolver(Map.of("KEY", "${NESTED}"), local, new Redactor())
                .resolve("${KEY}"));
    Files.setPosixFilePermissions(local, PosixFilePermissions.fromString("rw-r--r--"));
    assertThrows(
        OryxError.class,
        () -> new SecretResolver(Map.of(), local, new Redactor()).resolve("${KEY}"));
  }

  @Test
  void rootsAreCanonicalAndPrecedenceIsShared() throws Exception {
    Path real = Files.createDirectory(root.resolve("real"));
    Path link = root.resolve("link");
    Files.createSymbolicLink(link, real);
    assertEquals(
        real.toRealPath().resolve("new"),
        WorkspaceResolver.resolve("link/new", "prop", "env", root).root());
    assertEquals(
        root.toRealPath().resolve("prop"),
        WorkspaceResolver.resolve(null, "prop", "env", root).root());
    assertEquals(
        root.toRealPath().resolve("env"),
        WorkspaceResolver.resolve(null, null, "env", root).root());
    assertEquals(
        root.toRealPath().resolve(".oryxos/oryxos.db"),
        WorkspaceResolver.resolve(null, null, null, root).database());
  }

  @Test
  void permitsStopAtDeadlineAndPauseCannotAutoRecover() throws Exception {
    class Clock implements TimeSource {
      long nano;
      Instant wall = Instant.parse("2026-11-12T15:59:59Z");

      public long nanoTime() {
        return nano;
      }

      public Instant instant() {
        return wall;
      }
    }
    var time = new Clock();
    var deadline = new Deadline(time, Duration.ofSeconds(60));
    var gate = new RuntimeGate();
    gate.readyAtFirstStartup();
    var first = gate.execution(deadline);
    var other = gate.execution(deadline);
    first.stop();
    assertThrows(OryxError.class, first::admit);
    try (var action = other.admit()) {
      assertEquals(1, gate.inFlight());
    }
    assertEquals(0, gate.inFlight());
    time.wall = time.wall.plusSeconds(3600);
    assertFalse(deadline.expired());
    time.nano = 60_000_000_000L;
    assertThrows(OryxError.class, other::admit);
    gate.pause();
    assertThrows(IllegalStateException.class, gate::readyAtFirstStartup);
    try (var lock = WorkspaceProcessLock.acquire(workspace())) {
      assertTrue(WorkspaceProcessLock.isRunning(workspace()));
      assertThrows(OryxError.class, () -> WorkspaceProcessLock.acquire(workspace()));
    }
    assertFalse(WorkspaceProcessLock.isRunning(workspace()));
  }
}
