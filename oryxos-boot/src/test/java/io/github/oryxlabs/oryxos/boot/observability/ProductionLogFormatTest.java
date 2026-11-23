package io.github.oryxlabs.oryxos.boot.observability;

import static io.github.oryxlabs.oryxos.boot.observability.ObservabilitySupport.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.oryxlabs.oryxos.boot.support.PersistentFixture;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("f004-observability")
class ProductionLogFormatTest {
  @TempDir Path root;
  @TempDir Path logs;

  @Test
  void prodProfileWritesParseableJsonLinesWithRequiredFields() throws Exception {
    try (var fixture = new PersistentFixture(root);
        var context =
            start(
                fixture,
                Map.of("spring.profiles.active", "prod", "oryxos.log.dir", logs.toString()));
        var http = HttpClient.newHttpClient()) {
      assertEquals(
          200, send(http, "GET", origin(context) + "/api/v1/health", null, Map.of()).statusCode());
    }
    List<String> lines;
    try (Stream<String> all = Files.lines(logs.resolve("oryxos.json"))) {
      lines = all.limit(100).toList();
    }
    assertFalse(lines.isEmpty());
    boolean access = false;
    for (String line : lines) {
      var event = JSON.readTree(line);
      for (String field :
          List.of("@timestamp", "level", "logger_name", "thread_name", "message", "application"))
        assertTrue(event.hasNonNull(field), field + " missing in " + line);
      assertEquals("oryxos", event.path("application").asText());
      assertFalse(event.has("HOSTNAME"), "host name must not be logged");
      assertFalse(event.has("caller_class_name"), "caller data must not be logged");
      access |=
          event.path("message").asText().startsWith("http_request method=GET path=/api/v1/health");
    }
    assertTrue(access, "access log line expected in the JSON file");
  }

  @Test
  void withoutProdProfileNoLogFileIsCreated() throws Exception {
    try (var fixture = new PersistentFixture(root);
        var context = start(fixture, Map.of("oryxos.log.dir", logs.toString()));
        var http = HttpClient.newHttpClient()) {
      send(http, "GET", origin(context) + "/api/v1/health", null, Map.of());
    }
    try (Stream<Path> files = Files.list(logs)) {
      assertEquals(List.of(), files.toList());
    }
  }
}
