package io.github.oryxlabs.oryxos.boot;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.management.HotSpotDiagnosticMXBean;
import io.github.oryxlabs.oryxos.boot.runtime.BootRuntimeLauncher;
import io.github.oryxlabs.oryxos.boot.support.PersistentFixture;
import io.github.oryxlabs.oryxos.boot.support.SoakTiming;
import io.github.oryxlabs.oryxos.core.agent.AgentService;
import io.github.oryxlabs.oryxos.core.execution.Execution;
import io.github.oryxlabs.oryxos.core.execution.ExecutionContext;
import io.github.oryxlabs.oryxos.core.runtime.RuntimeGate;
import io.github.oryxlabs.oryxos.core.session.SessionManager;
import io.github.oryxlabs.oryxos.core.tool.ToolExecutor;
import io.github.oryxlabs.oryxos.provider.AuditedProvider;
import io.github.oryxlabs.oryxos.storage.lifecycle.StorageResources;
import io.github.oryxlabs.oryxos.web.api.ApiResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

@Tag("kernel-http-completion")
class HttpCompletionResumptionTest {
  @TempDir Path root;

  @Test
  void inFlightHttpExecutionDoesNotKeepDispatchWorkerParkedInHandleAwait() throws Exception {
    try (var fixture = new PersistentFixture(root);
        var context =
            new BootRuntimeLauncher()
                .start(
                    fixture.workspace,
                    Map.of("server.port", 0, "oryxos.scheduling.enabled", false));
        var http = HttpClient.newHttpClient()) {
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      fixture.beforeModel =
          n -> {
            if (n == 1) {
              entered.countDown();
              try {
                if (!release.await(10, TimeUnit.SECONDS))
                  throw new IllegalStateException("model release timed out");
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
              }
            }
          };
      String base =
          "http://127.0.0.1:"
              + ((ServletWebServerApplicationContext) context).getWebServer().getPort()
              + "/api/v1";
      var created = post(http, base + "/sessions", "{\"agentName\":\"local\"}", "alice");
      assertEquals(201, created.statusCode());
      var json = new ObjectMapper();
      String sessionId = json.readTree(created.body()).path("data").path("id").asText();
      CompletableFuture<HttpResponse<String>> firstReply =
          http.sendAsync(
              HttpRequest.newBuilder(URI.create(base + "/sessions/" + sessionId + "/messages"))
                  .timeout(Duration.ofSeconds(15))
                  .header("Content-Type", "application/json")
                  .header("X-Oryx-User-Id", "alice")
                  .POST(HttpRequest.BodyPublishers.ofString("{\"content\":\"in flight\"}"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      Thread.sleep(50);
      Path dumpPath = root.resolve("threads.json");
      try {
        ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class)
            .dumpThreads(dumpPath.toString(), HotSpotDiagnosticMXBean.ThreadDumpFormat.JSON);
        JsonNode rootNode = json.readTree(Files.readString(dumpPath));
        for (JsonNode threadsArray : rootNode.findValues("threads")) {
          for (JsonNode thread : threadsArray) {
            var frames = new ArrayList<String>();
            for (JsonNode frame : thread.path("stack")) frames.add(frame.asText());
            boolean hasAdapterFrame =
                frames.stream().anyMatch(f -> f.contains("HttpExecutionAdapter"));
            boolean parkedInAwait =
                frames.stream()
                    .anyMatch(
                        f ->
                            f.contains("AgentService$Handle.await(")
                                || f.contains("AgentService$Handle.awaitSettled("));
            assertFalse(
                hasAdapterFrame && parkedInAwait,
                () ->
                    "HTTP dispatch thread must not park in Handle.await/awaitSettled: " + frames);
          }
        }
        var overlap =
            post(
                http,
                base + "/sessions/" + sessionId + "/messages",
                "{\"content\":\"concurrent\"}",
                "alice");
        assertEquals(409, overlap.statusCode());
        assertEquals("SESSION_BUSY", json.readTree(overlap.body()).path("code").asText());
      } finally {
        release.countDown();
      }
      var completed = firstReply.get(10, TimeUnit.SECONDS);
      assertEquals(200, completed.statusCode());
      assertEquals("OK", json.readTree(completed.body()).path("code").asText());
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      HttpResponse<String> followUp = null;
      while (System.nanoTime() < until) {
        followUp =
            post(
                http,
                base + "/sessions/" + sessionId + "/messages",
                "{\"content\":\"after settle\"}",
                "alice");
        if (followUp.statusCode() != 409) break;
        Thread.sleep(10);
      }
      assertNotNull(followUp);
      assertEquals(200, followUp.statusCode());
    }
  }

  @Test
  void hotExecutionIdentifiersAvoidSynchronizedRandomUuid() throws Exception {
    for (Class<?> cls :
        List.of(
            AgentService.class,
            SessionManager.class,
            SessionManager.Lease.class,
            AuditedProvider.class,
            ToolExecutor.class,
            ApiResponse.class)) {
      String resource = cls.getName().substring(cls.getPackageName().length() + 1) + ".class";
      byte[] bytecode;
      try (var stream = cls.getResourceAsStream(resource)) {
        assertNotNull(stream, "Missing class resource: " + resource);
        bytecode = stream.readAllBytes();
      }
      String utf8 = new String(bytecode, StandardCharsets.UTF_8);
      assertFalse(
          utf8.contains("randomUUID"),
          () -> cls.getName() + " still references UUID.randomUUID()");
    }
    var ids = ConcurrentHashMap.<String>newKeySet(10_000);
    try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var futures = new ArrayList<Future<?>>(100);
      for (int i = 0; i < 100; i++) {
        futures.add(
            workers.submit(
                () -> {
                  for (int j = 0; j < 100; j++) {
                    String raw = ApiResponse.ok("ok").traceId();
                    UUID parsed = UUID.fromString(raw);
                    assertEquals(4, parsed.version());
                    assertEquals(2, parsed.variant());
                    ids.add(raw);
                  }
                }));
      }
      for (var future : futures) future.get(5, TimeUnit.SECONDS);
    }
    assertEquals(10_000, ids.size());
  }

  @Test
  void hotRuntimePathsAvoidSynchronizedMonitorsAndDirectAsyncCompletionSetsContentLength()
      throws Exception {
    var javap =
        java.util.spi.ToolProvider.findFirst("javap")
            .orElseThrow(() -> new IllegalStateException("javap tool missing"));
    Class<?>[] targets = {
      RuntimeGate.class,
      Class.forName(RuntimeGate.class.getName() + "$ExecutionPermit"),
      Class.forName(RuntimeGate.class.getName() + "$ActionPermit"),
      Execution.class,
      ExecutionContext.class,
      SessionManager.Lease.class,
      StorageResources.class
    };
    for (Class<?> cls : targets) {
      String resource = cls.getName().substring(cls.getPackageName().length() + 1) + ".class";
      byte[] bytecode;
      try (var stream = cls.getResourceAsStream(resource)) {
        assertNotNull(stream, "Missing class resource: " + resource);
        bytecode = stream.readAllBytes();
      }
      Path classFile = root.resolve(cls.getName().replace('.', '_') + ".class");
      Files.write(classFile, bytecode);
      var out = new StringWriter();
      var err = new StringWriter();
      int rc =
          javap.run(
              new PrintWriter(out),
              new PrintWriter(err),
              "-c",
              "-v",
              "-p",
              classFile.toString());
      assertEquals(0, rc, () -> "javap failed for " + cls.getName() + ": " + err);
      String disassembly = out.toString();
      assertFalse(
          disassembly.contains("ACC_SYNCHRONIZED"),
          () -> cls.getName() + " still uses ACC_SYNCHRONIZED");
      assertFalse(
          disassembly.contains("monitorenter"),
          () -> cls.getName() + " still uses monitorenter");
    }
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
      var created = post(http, base + "/sessions", "{\"agentName\":\"local\"}", "alice");
      assertEquals(201, created.statusCode());
      var json = new ObjectMapper();
      String sessionId = json.readTree(created.body()).path("data").path("id").asText();
      var span = new SoakTiming.Span();
      String soakId = UUID.randomUUID().toString();
      SoakTiming.SPANS.put(soakId, span);
      try {
        var response =
            http.send(
                HttpRequest.newBuilder(URI.create(base + "/sessions/" + sessionId + "/messages"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("X-Oryx-User-Id", "alice")
                    .header("X-Oryx-Soak-Request", soakId)
                    .POST(HttpRequest.BodyPublishers.ofString("{\"content\":\"direct async\"}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("OK", json.readTree(response.body()).path("code").asText());
        assertTrue(
            response.headers().firstValue("content-length").isPresent(),
            () ->
                "Direct async completion should emit explicit Content-Length instead of chunked"
                    + " DeferredResult dispatch: "
                    + response.headers().map());
        span.elapsed.get(5, TimeUnit.SECONDS);
      } finally {
        SoakTiming.SPANS.remove(soakId);
      }
    }
  }

  private static HttpResponse<String> post(
      HttpClient client, String url, String body, String user) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("X-Oryx-User-Id", user)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
