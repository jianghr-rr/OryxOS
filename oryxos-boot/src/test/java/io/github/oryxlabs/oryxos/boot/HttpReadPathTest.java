package io.github.oryxlabs.oryxos.boot;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.oryxlabs.oryxos.boot.runtime.BootRuntimeLauncher;
import io.github.oryxlabs.oryxos.boot.support.*;
import io.github.oryxlabs.oryxos.core.runtime.RuntimeGate;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

@Tag("kernel-http-read")
class HttpReadPathTest {
  @TempDir Path root;

  @Test
  void messageLookupUsesMeasuredPoolAndPreservesIdentityMissingAndPauseErrors() throws Exception {
    try (var fixture = new PersistentFixture(root);
        var context =
            new BootRuntimeLauncher(Map.of(), SoakTiming.class)
                .start(
                    fixture.workspace,
                    Map.of("server.port", 0, "oryxos.scheduling.enabled", false));
        var http = HttpClient.newHttpClient()) {
      String base =
          "http://127.0.0.1:"
              + ((ServletWebServerApplicationContext) context).getWebServer().getPort()
              + "/api/v1";
      var created = post(http, base + "/sessions", "{\"agentName\":\"local\"}", "alice", null);
      assertEquals(201, created.statusCode());
      String id = new ObjectMapper().readTree(created.body()).path("data").path("id").asText();
      var span = new SoakTiming.Span();
      String token = UUID.randomUUID().toString();
      SoakTiming.SPANS.put(token, span);
      try {
        var reply =
            post(
                http,
                base + "/sessions/" + id + "/messages",
                "{\"content\":\"hello\"}",
                "alice",
                token);
        assertEquals(200, reply.statusCode());
        span.elapsed.get(5, TimeUnit.SECONDS);
      } finally {
        SoakTiming.SPANS.remove(token);
      }
      assertEquals(1, fixture.models.get());
      assertEquals(
          409,
          post(http, base + "/sessions/" + id + "/messages", "{\"content\":\"hello\"}", "bob", null)
              .statusCode());
      assertEquals(
          404,
          post(
                  http,
                  base + "/sessions/" + UUID.randomUUID() + "/messages",
                  "{\"content\":\"hello\"}",
                  "alice",
                  null)
              .statusCode());
      context.getBean(RuntimeGate.class).pause();
      assertEquals(
          503,
          post(
                  http,
                  base + "/sessions/" + id + "/messages",
                  "{\"content\":\"hello\"}",
                  "alice",
                  null)
              .statusCode());
      assertEquals(1, fixture.models.get(), "Rejected requests must not dispatch a model");
      var lookup =
          span.segments.stream().filter(s -> s.name().equals("http.session.lookup")).findFirst();
      assertTrue(lookup.isPresent(), "HTTP admission lookup must be included in segmented timing");
      for (var name : List.of("db.read.connection", "db.read.query"))
        assertTrue(
            span.segments.stream()
                .anyMatch(
                    s ->
                        s.name().equals(name)
                            && s.startNanos() >= lookup.get().startNanos()
                            && s.endNanos() <= lookup.get().endNanos()),
            "HTTP admission must reuse the measured runtime pool: " + name);
    }
  }

  @Test
  @Tag("kernel-http-read")
  void persistentToolLoopCoalescesZeroIoPostStepWritesWhileKeepingStartedBoundaries()
      throws Exception {
    try (var fixture = new PersistentFixture(root);
        var context =
            new BootRuntimeLauncher(Map.of(), SoakTiming.class)
                .start(
                    fixture.workspace,
                    Map.of("server.port", 0, "oryxos.scheduling.enabled", false));
        var http = HttpClient.newHttpClient()) {
      fixture.useTool = true;
      var observedAtModel1 = new java.util.concurrent.atomic.AtomicLong(-1);
      var observedLlm1SucceededAtTool = new java.util.concurrent.atomic.AtomicLong(-1);
      var observedToolStartedAtTool = new java.util.concurrent.atomic.AtomicLong(-1);
      var observedToolSuccessAtModel2 = new java.util.concurrent.atomic.AtomicLong(-1);
      var observedLlm2StartedAtModel2 = new java.util.concurrent.atomic.AtomicLong(-1);
      fixture.beforeModel =
          n -> {
            if (n == 1) {
              observedAtModel1.set(
                  queryCount(fixture.workspace.database(), "SELECT count(*) FROM llm_calls WHERE status='STARTED'"));
            } else if (n == 2) {
              observedToolSuccessAtModel2.set(
                  queryCount(
                      fixture.workspace.database(),
                      "SELECT count(*) FROM tool_invocations WHERE status='SUCCESS'"));
              observedLlm2StartedAtModel2.set(
                  queryCount(
                      fixture.workspace.database(),
                      "SELECT count(*) FROM llm_calls WHERE status='STARTED'"));
            }
          };
      fixture.afterReceipt =
          () -> {
            observedLlm1SucceededAtTool.set(
                queryCount(
                    fixture.workspace.database(),
                    "SELECT count(*) FROM llm_calls WHERE status='SUCCEEDED'"));
            observedToolStartedAtTool.set(
                queryCount(
                    fixture.workspace.database(),
                    "SELECT count(*) FROM tool_invocations WHERE status='STARTED'"));
          };
      String base =
          "http://127.0.0.1:"
              + ((ServletWebServerApplicationContext) context).getWebServer().getPort()
              + "/api/v1";
      var created = post(http, base + "/sessions", "{\"agentName\":\"local\"}", "alice", null);
      assertEquals(201, created.statusCode());
      String id = new ObjectMapper().readTree(created.body()).path("data").path("id").asText();
      var span = new SoakTiming.Span();
      String token = UUID.randomUUID().toString();
      SoakTiming.SPANS.put(token, span);
      try {
        var reply =
            post(
                http,
                base + "/sessions/" + id + "/messages",
                "{\"content\":\"run tool loop\"}",
                "alice",
                token);
        assertEquals(200, reply.statusCode());
        span.elapsed.get(5, TimeUnit.SECONDS);
      } finally {
        SoakTiming.SPANS.remove(token);
      }
      assertEquals(1, observedAtModel1.get(), "llm_calls(STARTED) must commit before model 1 IO");
      assertEquals(
          1,
          observedLlm1SucceededAtTool.get(),
          "llm_calls(SUCCEEDED) must commit before tool IO");
      assertEquals(
          1,
          observedToolStartedAtTool.get(),
          "tool_invocations(STARTED) must commit before tool IO");
      assertEquals(
          1,
          observedToolSuccessAtModel2.get(),
          "tool_invocations(SUCCESS) must commit before model 2 IO");
      assertEquals(
          1,
          observedLlm2StartedAtModel2.get(),
          "second llm_calls(STARTED) must commit before model 2 IO");
      long writeCount = span.segments.stream().filter(s -> s.name().equals("db.write")).count();
      assertTrue(
          writeCount <= 4,
          () ->
              "Zero-IO turn start, post-step, and pre-tool STARTED writes should coalesce to <= 4"
                  + " barriers, got "
                  + writeCount);
      long readCount = span.segments.stream().filter(s -> s.name().equals("db.read")).count();
      assertTrue(
          readCount <= 1,
          () -> "Hot session cache should issue <= 1 db.read call per steady turn, got " + readCount);
      assertEquals(
          1,
          queryCount(
              fixture.workspace.database(),
              "SELECT count(*) FROM executions WHERE status='SUCCEEDED' AND"
                  + " recording_status='COMPLETE' AND turn_complete=1"));
      assertEquals(4, queryCount(fixture.workspace.database(), "SELECT count(*) FROM messages"));
      assertEquals(
          2,
          queryCount(
              fixture.workspace.database(),
              "SELECT count(*) FROM llm_calls WHERE status='SUCCEEDED'"));
      assertEquals(
          1,
          queryCount(
              fixture.workspace.database(),
              "SELECT count(*) FROM tool_invocations WHERE status='SUCCESS'"));
      assertEquals(2, queryCount(fixture.workspace.database(), "PRAGMA synchronous"));
    }
  }

  private static long queryCount(Path db, String sql) {
    try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db.toUri() + "?mode=ro");
        var s = c.createStatement();
        var r = s.executeQuery(sql)) {
      return r.next() ? r.getLong(1) : -1;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static HttpResponse<String> post(
      HttpClient client, String url, String body, String user, String timing) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("X-Oryx-User-Id", user);
    if (timing != null) request.header("X-Oryx-Soak-Request", timing);
    return client.send(
        request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
