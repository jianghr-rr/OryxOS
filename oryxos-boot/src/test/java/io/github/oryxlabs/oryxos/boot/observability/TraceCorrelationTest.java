package io.github.oryxlabs.oryxos.boot.observability;

import static io.github.oryxlabs.oryxos.boot.observability.ObservabilitySupport.*;
import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.github.oryxlabs.oryxos.boot.config.AgentRuntimeConfiguration;
import io.github.oryxlabs.oryxos.boot.support.PersistentFixture;
import io.github.oryxlabs.oryxos.core.agent.AgentService;
import io.github.oryxlabs.oryxos.core.execution.ProcessRequest;
import io.github.oryxlabs.oryxos.core.observability.MdcContext;
import io.github.oryxlabs.oryxos.core.runtime.TimeSource;
import io.github.oryxlabs.oryxos.core.security.Redactor;
import io.github.oryxlabs.oryxos.web.api.ApiExceptionHandler;
import io.github.oryxlabs.oryxos.web.api.ApiResponse;
import io.github.oryxlabs.oryxos.web.api.PublicDtoMapper;
import io.github.oryxlabs.oryxos.web.execution.HttpExecutionAdapter;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

@Tag("f004-observability")
class TraceCorrelationTest {
  private static final Pattern UUID_V4 =
      Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
  private static final String ACCESS = "io.github.oryxlabs.oryxos.web.access";

  @TempDir Path root;

  @Test
  void everyResponseTraceIdMatchesItsAccessLogLineAndAsyncExecutionLogs() throws Exception {
    try (var fixture = new PersistentFixture(root);
        var context = start(fixture, Map.of());
        var capture = new LogCapture().level(AgentService.class, Level.DEBUG);
        var http = HttpClient.newHttpClient()) {
      String api = origin(context) + "/api/v1";
      var traceIds = new ArrayList<String>();
      var executions = new ArrayList<String[]>();
      for (int i = 0; i < 4; i++)
        traceIds.add(
            json(send(http, "GET", api + "/health", null, Map.of())).path("traceId").asText());
      for (int i = 0; i < 3; i++)
        traceIds.add(
            json(send(http, "GET", api + "/profiles", null, Map.of())).path("traceId").asText());
      for (int i = 0; i < 3; i++) {
        var body =
            json(
                send(http, "POST", api + "/agents/local/invoke", "{\"content\":\"hi\"}", Map.of()));
        assertEquals("OK", body.path("code").asText(), body.toString());
        traceIds.add(body.path("traceId").asText());
        executions.add(
            new String[] {body.path("traceId").asText(), body.path("data").path("id").asText()});
      }
      assertEquals(10, new HashSet<>(traceIds).size(), "trace ids must be distinct: " + traceIds);
      var events =
          capture.await(all -> traceIds.stream().allMatch(id -> accessLine(all, id) != null));
      for (String id : traceIds) {
        var line = accessLine(events, id);
        assertNotNull(line, "no access log for traceId " + id);
        assertTrue(
            line.getFormattedMessage()
                .matches("http_request method=\\w+ path=/api/v1/\\S+ status=200 durationMs=\\d+"),
            line.getFormattedMessage());
      }
      for (String[] pair : executions) {
        var finished =
            events.stream()
                .filter(e -> e.getFormattedMessage().startsWith("execution_finished"))
                .filter(e -> pair[1].equals(e.getMDCPropertyMap().get("executionId")))
                .findFirst();
        assertTrue(finished.isPresent(), "execution log for " + pair[1]);
        assertEquals(pair[0], finished.get().getMDCPropertyMap().get("traceId"));
      }
    }
  }

  @Test
  void wellFormedRequestIdIsReusedAndAnythingElseIsReplaced() throws Exception {
    try (var fixture = new PersistentFixture(root);
        var context = start(fixture, Map.of());
        var http = HttpClient.newHttpClient()) {
      String health = origin(context) + "/api/v1/health";
      assertEquals(
          "probe-abcdef12",
          json(send(http, "GET", health, null, Map.of("X-Request-ID", "probe-abcdef12")))
              .path("traceId")
              .asText());
      for (String bad : List.of("short", "has space!", "x".repeat(65))) {
        String id =
            json(send(http, "GET", health, null, Map.of("X-Request-ID", bad)))
                .path("traceId")
                .asText();
        assertTrue(UUID_V4.matcher(id).matches(), bad + " -> " + id);
      }
    }
  }

  @Test
  void deadlineResponseKeepsCallerTraceAndPooledThreadsKeepNoMdc() throws Exception {
    var redactor = new Redactor();
    var system = TimeSource.system();
    TimeSource entered60sAgo =
        new TimeSource() {
          @Override
          public Instant instant() {
            return system.instant();
          }

          @Override
          public long nanoTime() {
            return system.nanoTime() + TimeUnit.SECONDS.toNanos(61);
          }
        };
    var release = new CountDownLatch(1);
    try (var adapter =
        new HttpExecutionAdapter(
            entered60sAgo, new PublicDtoMapper(redactor), new ApiExceptionHandler(redactor))) {
      var request = new MockHttpServletRequest();
      request.setAttribute(
          HttpExecutionAdapter.ENTERED,
          new HttpExecutionAdapter.Entered(system.instant(), system.nanoTime()));
      MDC.put("traceId", "caller-trace-0001");
      var result =
          adapter.execute(
              request,
              entered -> {
                try {
                  release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
                throw new IllegalStateException("released");
              });
      MDC.clear();
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (!result.hasResult() && System.nanoTime() < until) Thread.sleep(5);
      var entity = (ResponseEntity<?>) result.getResult();
      assertNotNull(entity);
      assertEquals(504, entity.getStatusCode().value());
      assertEquals("caller-trace-0001", ((ApiResponse<?>) entity.getBody()).traceId());
    } finally {
      release.countDown();
      MDC.clear();
    }

    var pool = Executors.newSingleThreadExecutor();
    try {
      MDC.put("traceId", "pooled-trace-0001");
      MDC.put("executionId", "pooled-execution");
      pool.submit(
              MdcContext.capture()
                  .wrap(() -> assertEquals("pooled-trace-0001", MDC.get("traceId"))))
          .get();
      MDC.clear();
      var leftover = pool.submit(MDC::getCopyOfContextMap).get();
      assertTrue(leftover == null || leftover.isEmpty(), "pooled thread kept MDC: " + leftover);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void directAgentServiceExecutionLogsCarryThePersistedExecutionId() throws Exception {
    try (var fixture = new PersistentFixture(root);
        var context = start(fixture, Map.of());
        var capture = new LogCapture().level(AgentService.class, Level.DEBUG)) {
      var service = context.getBean(AgentRuntimeConfiguration.RuntimeAgents.class).require();
      var snapshot = service.process(ProcessRequest.manual("cli", "alice", "local", null, "hi"));
      String id = snapshot.id().toString();
      var events =
          capture.await(all ->
              all.stream().anyMatch(e -> e.getFormattedMessage().startsWith("execution_finished")));
      var finished =
          events.stream()
              .filter(e -> e.getFormattedMessage().startsWith("execution_finished"))
              .toList();
      assertEquals(1, finished.size());
      assertEquals(id, finished.get(0).getMDCPropertyMap().get("executionId"));
      for (var event : events) {
        String seen = event.getMDCPropertyMap().get("executionId");
        if (seen != null) assertEquals(id, seen, event.getFormattedMessage());
      }
    }
  }

  private static ILoggingEvent accessLine(List<ILoggingEvent> events, String traceId) {
    return events.stream()
        .filter(e -> ACCESS.equals(e.getLoggerName()))
        .filter(e -> traceId.equals(e.getMDCPropertyMap().get("traceId")))
        .findFirst()
        .orElse(null);
  }
}
