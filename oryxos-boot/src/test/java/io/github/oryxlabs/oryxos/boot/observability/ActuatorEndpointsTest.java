package io.github.oryxlabs.oryxos.boot.observability;

import static io.github.oryxlabs.oryxos.boot.observability.ObservabilitySupport.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.oryxlabs.oryxos.boot.support.PersistentFixture;
import io.github.oryxlabs.oryxos.core.runtime.RuntimeGate;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("f004-observability")
class ActuatorEndpointsTest {
  @TempDir Path root;

  @Test
  void healthProbesInfoAndPrometheusAreExposedAndNothingElse() throws Exception {
    try (var fixture = new PersistentFixture(root);
        var context = start(fixture, Map.of());
        var http = HttpClient.newHttpClient()) {
      String origin = origin(context);
      for (String path :
          List.of("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness")) {
        var response = send(http, "GET", origin + path, null, Map.of());
        assertEquals(200, response.statusCode(), path);
        assertEquals("UP", json(response).path("status").asText(), path);
      }
      var invoke =
          send(
              http,
              "POST",
              origin + "/api/v1/agents/local/invoke",
              "{\"content\":\"hi\"}",
              Map.of());
      assertEquals(200, invoke.statusCode(), invoke.body());

      String metrics = send(http, "GET", origin + "/actuator/prometheus", null, Map.of()).body();
      for (String name :
          List.of("jvm_memory_used_bytes", "jvm_threads_live_threads", "process_uptime_seconds"))
        assertTrue(metrics.contains(name), name);
      assertTrue(
          metrics
              .lines()
              .anyMatch(l ->
                  l.startsWith("http_server_requests_seconds_count{")
                      && l.contains("application=\"oryxos\"")
                      && l.contains("uri=\"/api/v1/agents/{name}/invoke\"")),
          "async invoke must be measured");
      assertTrue(metrics.contains("http_server_requests_seconds_bucket{"), "histogram for p50/p95");

      var info = send(http, "GET", origin + "/actuator/info", null, Map.of());
      assertEquals(200, info.statusCode());
      assertFalse(json(info).path("build").path("version").asText().isEmpty(), info.body());

      for (String hidden : List.of("/actuator/env", "/actuator/beans", "/actuator/heapdump"))
        assertEquals(404, send(http, "GET", origin + hidden, null, Map.of()).statusCode(), hidden);
      assertFalse(
          send(http, "GET", origin + "/v3/api-docs", null, Map.of()).body().contains("actuator"));
    }
  }

  @Test
  void pausedRecordingTakesReadinessOutOfServiceLikeApiHealth() throws Exception {
    try (var fixture = new PersistentFixture(root);
        var context = start(fixture, Map.of());
        var http = HttpClient.newHttpClient()) {
      String origin = origin(context);
      context.getBean(RuntimeGate.class).pause();
      var readiness = send(http, "GET", origin + "/actuator/health/readiness", null, Map.of());
      assertEquals(503, readiness.statusCode());
      assertEquals("OUT_OF_SERVICE", json(readiness).path("status").asText());
      assertEquals(503, send(http, "GET", origin + "/api/v1/health", null, Map.of()).statusCode());
      assertEquals(
          200,
          send(http, "GET", origin + "/actuator/health/liveness", null, Map.of()).statusCode());
    }
  }
}
