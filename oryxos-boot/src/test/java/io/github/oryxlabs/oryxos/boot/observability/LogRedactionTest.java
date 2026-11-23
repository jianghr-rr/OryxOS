package io.github.oryxlabs.oryxos.boot.observability;

import static io.github.oryxlabs.oryxos.boot.observability.ObservabilitySupport.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.oryxlabs.oryxos.boot.support.PersistentFixture;
import io.github.oryxlabs.oryxos.core.security.Redactor;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

@Tag("f004-observability")
class LogRedactionTest {
  private static final String SECRET = "sk-live-OBSERVABILITY-0042";
  private static final String MESSAGE =
      "calling https://hooks.example.invalid/a with " + SECRET + " token=abc123";

  @TempDir Path root;
  @TempDir Path logs;

  @Test
  void jsonLogsRedactMessageExceptionStackAndMdc() throws Exception {
    String expectedMessage;
    try (var fixture = new PersistentFixture(root);
        var context =
            start(
                fixture,
                Map.of("spring.profiles.active", "prod", "oryxos.log.dir", logs.toString()))) {
      var redactor = context.getBean(Redactor.class);
      redactor.register(SECRET);
      expectedMessage = redactor.redact(MESSAGE);
      logProbe();
    }
    String line =
        Files.readAllLines(logs.resolve("oryxos.json")).stream()
            .filter(l -> l.contains("\"logger_name\":\"redaction.probe\""))
            .findFirst()
            .orElseThrow();
    assertClean(line);
    var event = JSON.readTree(line);
    assertEquals(expectedMessage, event.path("message").asText());
    assertTrue(event.path("stack_trace").asText().contains("[REDACTED]"));
    assertTrue(event.path("traceId").asText().contains("[REDACTED]"));
  }

  @Test
  void textLogsRedactMessageExceptionStackAndMdc() throws Exception {
    var buffer = new ByteArrayOutputStream();
    String expectedMessage;
    try (var fixture = new PersistentFixture(root);
        var context = start(fixture, Map.of())) {
      var redactor = context.getBean(Redactor.class);
      redactor.register(SECRET);
      expectedMessage = redactor.redact(MESSAGE);
      PrintStream original = System.err;
      System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
      try {
        logProbe();
      } finally {
        System.setErr(original);
      }
    }
    String text = buffer.toString(StandardCharsets.UTF_8);
    assertTrue(text.contains("redaction.probe"), text);
    assertClean(text);
    assertTrue(text.contains(expectedMessage), text);
  }

  private static void logProbe() {
    MDC.put("traceId", "token=abc123 " + SECRET);
    try {
      LoggerFactory.getLogger("redaction.probe")
          .error(
              MESSAGE,
              new IllegalStateException(
                  "failed with " + SECRET,
                  new RuntimeException("cause https://hooks.example.invalid/b")));
    } finally {
      MDC.remove("traceId");
    }
  }

  private static void assertClean(String output) {
    assertFalse(output.contains(SECRET), "secret leaked: " + output);
    assertFalse(output.contains("hooks.example.invalid"), "URL leaked: " + output);
    assertFalse(output.contains("abc123"), "credential leaked: " + output);
    assertTrue(output.contains("[REDACTED]"), output);
    assertTrue(output.contains("[REDACTED_URL]"), output);
  }
}
