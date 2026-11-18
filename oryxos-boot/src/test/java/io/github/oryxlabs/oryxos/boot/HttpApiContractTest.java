package io.github.oryxlabs.oryxos.boot;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import io.github.oryxlabs.oryxos.boot.runtime.BootRuntimeLauncher;
import io.github.oryxlabs.oryxos.boot.support.PersistentFixture;
import io.github.oryxlabs.oryxos.core.runtime.RuntimeGate;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

/**
 * Real MVC port, production adapters and SQLite. Loopback model is not real-provider acceptance.
 */
@Tag("us5-http-api")
class HttpApiContractTest {
  @TempDir Path root;
  final ObjectMapper json = new ObjectMapper();
  String base;
  JsonNode schemas;
  final Set<String> covered = new HashSet<>();

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void httpTimeoutPreservesCompletedModelAndUnconfirmedRecord() throws Exception {
    var snapshot =
        new io.github.oryxlabs.oryxos.core.execution.Execution.Snapshot(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "local",
            "http",
            "operator",
            io.github.oryxlabs.oryxos.core.execution.ProcessRequest.Trigger.MANUAL,
            null,
            "revision",
            Instant.now().minusSeconds(60),
            Instant.now(),
            Instant.now().minusSeconds(1),
            io.github.oryxlabs.oryxos.core.execution.Execution.Status.SUCCEEDED,
            "FINAL_REPLY",
            io.github.oryxlabs.oryxos.core.execution.Execution.RecordingStatus.UNKNOWN,
            0,
            "known reply",
            null,
            false,
            false,
            ZoneId.of("Asia/Shanghai"),
            new io.github.oryxlabs.oryxos.core.model.ToolNameMap(List.of()));
    var handle =
        org.mockito.Mockito.mock(io.github.oryxlabs.oryxos.core.agent.AgentService.Handle.class);
    org.mockito.Mockito.when(handle.await()).thenReturn(snapshot);
    org.mockito.Mockito.when(handle.awaitSettled()).thenReturn(snapshot);
    org.mockito.Mockito.when(handle.waitExpired()).thenReturn(true);
    var redactor = new io.github.oryxlabs.oryxos.core.security.Redactor();
    try (var adapter =
        new io.github.oryxlabs.oryxos.web.execution.HttpExecutionAdapter(
            io.github.oryxlabs.oryxos.core.runtime.TimeSource.system(),
            new io.github.oryxlabs.oryxos.web.api.PublicDtoMapper(redactor),
            new io.github.oryxlabs.oryxos.web.api.ApiExceptionHandler(redactor))) {
      var response =
          adapter.execute(
              new org.springframework.mock.web.MockHttpServletRequest(), entered -> handle);
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (!response.hasResult() && System.nanoTime() < until) Thread.sleep(5);
      var entity = (org.springframework.http.ResponseEntity<?>) response.getResult();
      assertNotNull(entity);
      assertEquals(504, entity.getStatusCode().value());
      var body =
          new ObjectMapper()
              .findAndRegisterModules()
              .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
              .valueToTree(entity.getBody());
      validate(body, schemas.path("ExecutionEnvelope"), "ExecutionEnvelope");
      assertEquals("EXECUTION_TIMEOUT", body.path("code").asText());
      assertEquals("SUCCEEDED", body.path("data").path("status").asText());
      assertEquals("UNKNOWN", body.path("data").path("recordingStatus").asText());
    }
  }

  @BeforeEach
  void contract() throws Exception {
    Path p = Path.of("../specs/001-single-node-agent-runtime/contracts/http-api.json");
    schemas = json.readTree(Files.readString(p)).path("components").path("schemas");
  }

  ServletWebServerApplicationContext start(PersistentFixture f) {
    var c =
        (ServletWebServerApplicationContext)
            new BootRuntimeLauncher().start(f.workspace, Map.of("server.port", 0));
    base = "http://127.0.0.1:" + c.getWebServer().getPort() + "/api/v1";
    return c;
  }

  JsonNode call(String method, String path, Object body, int status, String schema)
      throws Exception {
    return call(method, path, body, status, schema, "operator");
  }

  JsonNode call(String method, String path, Object body, int status, String schema, String user)
      throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      var b =
          HttpRequest.newBuilder(URI.create(base + path))
              .timeout(Duration.ofSeconds(75))
              .header("Content-Type", "application/json");
      if (user != null) b.header("X-Oryx-User-Id", user);
      var r =
          client.send(
              b.method(
                      method,
                      body == null
                          ? HttpRequest.BodyPublishers.noBody()
                          : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(status, r.statusCode(), method + " " + path + " " + r.body());
      assertFalse(r.body().contains("PRIVATE_OPAQUE_CANARY"));
      assertFalse(r.body().contains("LOCAL_SECRET_CANARY"));
      JsonNode value = json.readTree(r.body());
      validate(value, schemas.path(schema), schema);
      covered.add(schema);
      return value.path("data");
    }
  }

  void validate(JsonNode value, JsonNode s, String at) {
    if (s.has("$ref")) {
      String n = s.path("$ref").asText().replace("#/components/schemas/", "");
      covered.add(n);
      validate(value, schemas.path(n), at);
      return;
    }
    if (value.isNull()) {
      assertTrue(s.path("nullable").asBoolean(), at + " unexpectedly null");
      return;
    }
    if (s.has("enum"))
      assertTrue(
          java.util.stream.StreamSupport.stream(s.path("enum").spliterator(), false)
              .anyMatch(value::equals),
          at + " enum: " + value);
    switch (s.path("type").asText()) {
      case "object" -> {
        assertTrue(value.isObject(), at);
        s.path("required").forEach(n -> assertTrue(value.has(n.asText()), at + " missing " + n));
        value
            .fields()
            .forEachRemaining(
                e -> {
                  var property = s.path("properties").get(e.getKey());
                  if (property != null) validate(e.getValue(), property, at + "." + e.getKey());
                  else
                    assertNotEquals(
                        false,
                        s.path("additionalProperties").isBoolean()
                            ? s.path("additionalProperties").asBoolean()
                            : true,
                        at + " unexpected " + e.getKey());
                });
      }
      case "array" -> {
        assertTrue(value.isArray(), at);
        value.forEach(v -> validate(v, s.path("items"), at + "[]"));
        if (s.has("minItems")) assertTrue(value.size() >= s.path("minItems").asInt());
      }
      case "string" -> {
        assertTrue(value.isTextual(), at);
        if (s.path("format").asText().equals("uuid"))
          assertDoesNotThrow(() -> UUID.fromString(value.asText()));
        if (s.path("format").asText().equals("date-time"))
          assertDoesNotThrow(() -> Instant.parse(value.asText()));
      }
      case "integer" -> {
        assertTrue(value.isIntegralNumber(), at);
        if (s.has("minimum")) assertTrue(value.asLong() >= s.path("minimum").asLong(), at);
        if (s.has("maximum")) assertTrue(value.asLong() <= s.path("maximum").asLong(), at);
      }
      case "boolean" -> assertTrue(value.isBoolean(), at);
      default -> {}
    }
  }

  void schedule(PersistentFixture f) throws Exception {
    var p = f.workspace.root().resolve("agents/local/AGENT.md");
    Files.writeString(
        p,
        Files.readString(p)
            .replace(
                "bootstrap: []",
                "bootstrap: []\n"
                    + "schedules:\n"
                    + "  - id: tick\n"
                    + "    cron: '0 0 0 * * *'\n"
                    + "    message: scheduled task\n"
                    + "    enabled: false"));
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void allNineteenOperationsUseContractEnvelopesAndPersistentRecords() throws Exception {
    try (var f = new PersistentFixture(root)) {
      schedule(f);
      f.useTool = true;
      try (var c = start(f)) {
        call("GET", "/health", null, 200, "HealthEnvelope");
        call("GET", "/info", null, 200, "InfoEnvelope");
        call("GET", "/profiles", null, 200, "AgentPageEnvelope");
        call("GET", "/tools", null, 200, "ToolPageEnvelope");
        call("GET", "/memory", null, 200, "MemoryViewEnvelope");
        var session =
            call("POST", "/sessions", Map.of("agentName", "local"), 201, "SessionEnvelope");
        String id = session.path("id").asText();
        assertEquals("requested-model", session.path("model").asText());
        call("GET", "/sessions?limit=1", null, 200, "SessionPageEnvelope");
        var execution =
            call(
                "POST",
                "/sessions/" + id + "/messages",
                Map.of("content", "send LOCAL_SECRET_CANARY"),
                200,
                "ExecutionEnvelope");
        assertEquals(1, f.receipts.get());
        assertEquals("http", execution.path("identity").path("channel").asText());
        var detail =
            call(
                "GET",
                "/executions/" + execution.path("id").asText(),
                null,
                200,
                "ExecutionEnvelope");
        assertEquals(2, detail.path("llmCalls").size());
        assertTrue(detail.path("llmCalls").get(0).path("inputTokens").isNull());
        assertEquals(1, detail.path("toolAttempts").size());
        var latest =
            call("GET", "/sessions/" + id + "?limit=2", null, 200, "SessionHistoryEnvelope");
        assertEquals(2, latest.path("messages").size());
        assertTrue(latest.path("nextBeforeSeq").asLong() > 1);
        var earlier =
            call(
                "GET",
                "/sessions/" + id + "?limit=2&beforeSeq=" + latest.path("nextBeforeSeq").asLong(),
                null,
                200,
                "SessionHistoryEnvelope");
        assertTrue(
            earlier.path("messages").get(0).path("seq").asLong()
                < latest.path("messages").get(0).path("seq").asLong());
        call("GET", "/executions?sessionId=" + id, null, 200, "ExecutionPageEnvelope");
        call("DELETE", "/sessions/" + id, null, 200, "SessionEnvelope");
        call("DELETE", "/sessions/" + id, null, 200, "SessionEnvelope");
        var once =
            call(
                "POST",
                "/agents/local/invoke",
                Map.of("content", "once"),
                200,
                "ExecutionEnvelope");
        var onceSession =
            call(
                    "GET",
                    "/sessions/" + once.path("sessionId").asText(),
                    null,
                    200,
                    "SessionHistoryEnvelope")
                .path("session");
        assertEquals("ONE_SHOT", onceSession.path("kind").asText());
        assertEquals("ARCHIVED", onceSession.path("status").asText());
        call("GET", "/schedules", null, 200, "SchedulePageEnvelope");
        call(
            "PUT",
            "/schedules/local:tick/enabled",
            Map.of("enabled", false),
            200,
            "ScheduleEnvelope");
        call("POST", "/schedules/local:tick/run", null, 200, "ExecutionEnvelope");
        assertFalse(
            call("GET", "/schedules", null, 200, "SchedulePageEnvelope")
                .path("items")
                .get(0)
                .path("enabled")
                .asBoolean());
        assertEquals(
            "MANUAL",
            call("GET", "/schedules/local:tick/executions", null, 200, "ScheduleEventPageEnvelope")
                .path("items")
                .get(0)
                .path("triggerKind")
                .asText());
        c.getBean(RuntimeGate.class)
            .recordingFailure(UUID.fromString(execution.path("id").asText()));
        var state = call("GET", "/runtime/state", null, 200, "RuntimeStateEnvelope");
        assertEquals(state.path("incidentId"), state.path("currentIncident").path("id"));
        var recover =
            Map.of(
                "incidentId",
                state.path("incidentId").asText(),
                "acknowledgeMissingRecords",
                true,
                "notes",
                "Reviewed known actions and missing records");
        call("POST", "/runtime/recover", recover, 200, "RuntimeStateEnvelope");
        call("POST", "/runtime/recover", recover, 200, "RuntimeStateEnvelope");
      }
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void invalidInputAndIdentityNeverReachModel() throws Exception {
    try (var f = new PersistentFixture(root);
        var c = start(f)) {
      var session = call("POST", "/sessions", Map.of("agentName", "local"), 201, "SessionEnvelope");
      String id = session.path("id").asText();
      call("POST", "/sessions", Map.of("agentName", "local"), 400, "ErrorEnvelope", null);
      call("POST", "/sessions", Map.of("agentName", "local", "extra", true), 400, "ErrorEnvelope");
      call(
          "POST", "/sessions", Map.of("agentName", "local"), 400, "ErrorEnvelope", "u".repeat(129));
      call("POST", "/agents/local/invoke", Map.of("content", 123), 400, "ErrorEnvelope");
      call("POST", "/agents/missing/invoke", Map.of("content", "hello"), 404, "ErrorEnvelope");
      call(
          "POST",
          "/sessions/" + id + "/messages",
          Map.of("content", "hello"),
          409,
          "ErrorEnvelope",
          "other");
      call("POST", "/agents/local/invoke", Map.of("content", " "), 400, "ErrorEnvelope");
      call(
          "POST",
          "/agents/local/invoke",
          Map.of("content", "界".repeat(10923)),
          413,
          "ErrorEnvelope");
      for (String p :
          List.of(
              "/sessions?limit=0",
              "/sessions?limit=101",
              "/sessions?cursor=invalid",
              "/sessions/" + id + "?beforeSeq=0",
              "/sessions/not-a-uuid",
              "/sessions?archived=maybe")) call("GET", p, null, 400, "ErrorEnvelope");
      assertEquals(0, f.models.get());
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void pausedHealthAndQueriesStayHonestAndRecoveryRequiresConfirmation() throws Exception {
    try (var f = new PersistentFixture(root);
        var c = start(f)) {
      c.getBean(RuntimeGate.class).recordingFailure(null);
      assertFalse(call("GET", "/health", null, 503, "HealthEnvelope").path("ready").asBoolean());
      var state = call("GET", "/runtime/state", null, 200, "RuntimeStateEnvelope");
      call("POST", "/agents/local/invoke", Map.of("content", "no"), 503, "ErrorEnvelope");
      call(
          "POST",
          "/runtime/recover",
          Map.of(
              "incidentId",
              state.path("incidentId").asText(),
              "acknowledgeMissingRecords",
              false,
              "notes",
              "no"),
          400,
          "ErrorEnvelope");
      call(
          "POST",
          "/runtime/recover",
          Map.of(
              "incidentId",
              UUID.randomUUID().toString(),
              "acknowledgeMissingRecords",
              true,
              "notes",
              "wrong incident"),
          409,
          "ErrorEnvelope");
      assertEquals(0, f.models.get());
    }
  }

  @Test
  @Tag("http-deadline")
  @org.junit.jupiter.api.Tag("us5-http")
  void actualSixtySecondHttpDeadlineSealsActionsBefore504() throws Exception {
    try (var f = new PersistentFixture(root)) {
      var release = new CountDownLatch(1);
      f.useTool = true;
      f.beforeModel =
          n -> {
            try {
              release.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          };
      try (var c = start(f)) {
        call("GET", "/health", null, 200, "HealthEnvelope");
        long before = System.nanoTime();
        JsonNode result;
        try {
          result =
              call(
                  "POST",
                  "/agents/local/invoke",
                  Map.of("content", "deadline"),
                  504,
                  "ExecutionEnvelope");
        } finally {
          release.countDown();
        }
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);
        assertTrue(millis >= 59000 && millis < 65000, "actual wait ms=" + millis);
        assertEquals("TIMED_OUT", result.path("status").asText());
        assertFalse(result.path("id").asText().isBlank());
        Thread.sleep(400);
        assertEquals(1, f.models.get());
        assertEquals(0, f.receipts.get(), "late model cannot dispatch proposed tool");
        assertEquals(
            "TIMED_OUT",
            call("GET", "/executions/" + result.path("id").asText(), null, 200, "ExecutionEnvelope")
                .path("status")
                .asText());
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void everySchemaHasExactDtoFieldsAndValidNullableSerialization() throws Exception {
    var mapper =
        new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    var classes =
        Arrays.stream(io.github.oryxlabs.oryxos.web.api.ContractDtos.class.getDeclaredClasses())
            .collect(java.util.stream.Collectors.toMap(Class::getSimpleName, c -> c));
    var names = new HashSet<String>();
    schemas
        .fields()
        .forEachRemaining(
            e -> {
              String name = e.getKey();
              try {
                var sample = sample(e.getValue());
                Object dto;
                if (name.endsWith("Envelope"))
                  dto =
                      new io.github.oryxlabs.oryxos.web.api.ApiResponse<>(
                          "OK", "OK", sample.path("data"), Instant.now(), "trace");
                else {
                  var type = classes.get(name);
                  assertNotNull(type, name);
                  var fields =
                      Arrays.stream(type.getRecordComponents())
                          .map(java.lang.reflect.RecordComponent::getName)
                          .collect(java.util.stream.Collectors.toSet());
                  var expected = new HashSet<String>();
                  e.getValue().path("properties").fieldNames().forEachRemaining(expected::add);
                  assertEquals(expected, fields, name);
                  dto = mapper.treeToValue(sample, type);
                }
                validate(mapper.valueToTree(dto), e.getValue(), name);
                names.add(name);
              } catch (Exception ex) {
                throw new AssertionError(name, ex);
              }
            });
    assertEquals(44, names.size());
  }

  JsonNode sample(JsonNode s) {
    if (s.has("$ref"))
      return sample(schemas.path(s.path("$ref").asText().replace("#/components/schemas/", "")));
    if (s.path("nullable").asBoolean()) return json.nullNode();
    if (s.has("enum")) return s.path("enum").get(0);
    return switch (s.path("type").asText()) {
      case "object" -> {
        var v = json.createObjectNode();
        s.path("properties")
            .fields()
            .forEachRemaining(e -> v.set(e.getKey(), sample(e.getValue())));
        yield v;
      }
      case "array" -> json.createArrayNode().add(sample(s.path("items")));
      case "integer" -> json.getNodeFactory().numberNode(s.path("minimum").asInt(1));
      case "boolean" -> json.getNodeFactory().booleanNode(true);
      default ->
          json.getNodeFactory()
              .textNode(
                  s.path("format").asText().equals("uuid")
                      ? UUID.randomUUID().toString()
                      : s.path("format").asText().equals("date-time")
                          ? Instant.now().toString()
                          : "value");
    };
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void paginationAndSharedConfirmedMemorySurviveHttpRestart() throws Exception {
    try (var f = new PersistentFixture(root)) {
      var entries = new ArrayList<io.github.oryxlabs.oryxos.core.memory.MemoryEntry>();
      for (int n = 0; n < 105; n++)
        for (var scope : io.github.oryxlabs.oryxos.core.memory.MemoryEntry.Scope.values())
          entries.add(
              new io.github.oryxlabs.oryxos.core.memory.MemoryEntry(
                  UUID.randomUUID(),
                  io.github.oryxlabs.oryxos.core.workspace.WorkspaceInitializer.readId(f.workspace),
                  scope,
                  "memory-" + n,
                  Instant.now(),
                  UUID.randomUUID(),
                  "other-agent",
                  "md",
                  io.github.oryxlabs.oryxos.core.memory.MemoryEntry.WriteStatus.CONFIRMED,
                  List.of()));
      Files.createDirectories(root.resolve("memory"));
      Files.writeString(
          root.resolve("memory/MEMORY.md"),
          new io.github.oryxlabs.oryxos.memory.backend.MemoryTextCodec()
              .encode(
                  io.github.oryxlabs.oryxos.core.workspace.WorkspaceInitializer.readId(f.workspace),
                  entries));
      String id, execution;
      try (var c = start(f)) {
        id =
            call("POST", "/sessions", Map.of("agentName", "local"), 201, "SessionEnvelope")
                .path("id")
                .asText();
        execution =
            call(
                    "POST",
                    "/sessions/" + id + "/messages",
                    Map.of("content", "first"),
                    200,
                    "ExecutionEnvelope")
                .path("id")
                .asText();
        call("POST", "/sessions", Map.of("agentName", "local"), 201, "SessionEnvelope");
        var page = call("GET", "/sessions?limit=1", null, 200, "SessionPageEnvelope");
        var second =
            call(
                "GET",
                "/sessions?limit=1&cursor=" + page.path("nextCursor").asText(),
                null,
                200,
                "SessionPageEnvelope");
        assertNotEquals(
            page.path("items").get(0).path("id"), second.path("items").get(0).path("id"));
        assertTrue(second.path("nextCursor").isNull());
        var memory = call("GET", "/memory?limit=100", null, 200, "MemoryViewEnvelope");
        assertEquals(105, memory.path("core").size());
        assertEquals(100, memory.path("archival").size());
        assertTrue(memory.path("sharedAcrossAgents").asBoolean());
        assertEquals(
            5,
            call(
                    "GET",
                    "/memory?limit=100&cursor=" + memory.path("nextCursor").asText(),
                    null,
                    200,
                    "MemoryViewEnvelope")
                .path("archival")
                .size());
      }
      try (var c = start(f)) {
        assertEquals(
            2,
            call("GET", "/sessions/" + id, null, 200, "SessionHistoryEnvelope")
                .path("messages")
                .size());
        assertEquals(
            1,
            call("GET", "/executions/" + execution, null, 200, "ExecutionEnvelope")
                .path("llmCalls")
                .size());
        call(
            "POST",
            "/sessions/" + id + "/messages",
            Map.of("content", "continue"),
            200,
            "ExecutionEnvelope");
        assertTrue(f.requests.getLast().path("messages").toString().contains("first"));
      }
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void actualSqlFailuresPauseAndHttpRecoveryDoesNotReplayAcceptedAction() throws Exception {
    try (var f = new PersistentFixture(root)) {
      f.useTool = true;
      try (var c = start(f)) {
        f.afterReceipt =
            () -> {
              try {
                RecordingFailureIT.sql(
                    c,
                    "CREATE TRIGGER fail_result BEFORE UPDATE ON tool_invocations BEGIN SELECT"
                        + " RAISE(ABORT,'test'); END");
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            };
        var failed =
            call(
                "POST",
                "/agents/local/invoke",
                Map.of("content", "send"),
                500,
                "ExecutionEnvelope");
        assertEquals("INCOMPLETE", failed.path("recordingStatus").asText());
        assertEquals(1, f.receipts.get());
        assertEquals(1, f.models.get());
        call("POST", "/agents/local/invoke", Map.of("content", "again"), 503, "ErrorEnvelope");
        var state = call("GET", "/runtime/state", null, 200, "RuntimeStateEnvelope");
        assertFalse(state.path("currentIncident").path("detailsComplete").asBoolean());
        RecordingFailureIT.sql(c, "DROP TRIGGER fail_result");
        call(
            "POST",
            "/runtime/recover",
            Map.of(
                "incidentId",
                state.path("incidentId").asText(),
                "acknowledgeMissingRecords",
                true,
                "notes",
                "Verified one receipt; acknowledge missing result"),
            200,
            "RuntimeStateEnvelope");
        assertEquals(1, f.models.get());
        assertEquals(1, f.receipts.get());
      }
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void unreadableStorageReturnsErrorsAndRecover503WithoutFakeLists() throws Exception {
    try (var f = new PersistentFixture(root)) {
      Files.writeString(f.workspace.database(), "corrupt-db");
      try (var c = start(f)) {
        call("GET", "/health", null, 503, "HealthEnvelope");
        call("GET", "/sessions", null, 500, "ErrorEnvelope");
        call("GET", "/executions", null, 500, "ErrorEnvelope");
        var state = call("GET", "/runtime/state", null, 200, "RuntimeStateEnvelope");
        assertFalse(state.path("currentIncident").path("detailsComplete").asBoolean());
        call(
            "POST",
            "/runtime/recover",
            Map.of(
                "incidentId",
                state.path("incidentId").asText(),
                "acknowledgeMissingRecords",
                true,
                "notes",
                "Still broken"),
            503,
            "ErrorEnvelope");
        assertEquals(0, f.models.get());
      }
      assertEquals("corrupt-db", Files.readString(f.workspace.database()));
    }
  }

  Object toolMessage(String id, Object arguments) {
    try {
      return Map.of(
          "role",
          "assistant",
          "content",
          "step",
          "tool_calls",
          List.of(
              Map.of(
                  "id",
                  id,
                  "type",
                  "function",
                  "function",
                  Map.of("name", "http_post", "arguments", json.writeValueAsString(arguments)))));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void providerIterationAndCorrectedToolOutcomesKeepTheirEvidence() throws Exception {
    try (var f = new PersistentFixture(root)) {
      f.modelStatus = 503;
      try (var c = start(f)) {
        var failure =
            call(
                "POST",
                "/agents/local/invoke",
                Map.of("content", "provider fails"),
                503,
                "ErrorEnvelope");
        assertFalse(failure.path("executionId").asText().isBlank());
        f.modelStatus = 200;
        f.models.set(0);
        f.useTool = true;
        f.modelMessage =
            n ->
                n == 1
                    ? toolMessage("bad", Map.of("url", f.url(), "jsonBody", "wrong"))
                    : n == 2
                        ? toolMessage(
                            "fixed", Map.of("url", f.url(), "jsonBody", Map.of("text", "ok")))
                        : Map.of("role", "assistant", "content", "done");
        var result =
            call(
                "POST",
                "/agents/local/invoke",
                Map.of("content", "correct"),
                200,
                "ExecutionEnvelope");
        assertEquals("SUCCEEDED", result.path("status").asText());
        assertTrue(result.path("hasToolErrors").asBoolean());
        assertFalse(result.path("hasUnresolvedToolErrors").asBoolean());
        assertEquals(1, f.receipts.get());
        var attempts =
            call("GET", "/executions/" + result.path("id").asText(), null, 200, "ExecutionEnvelope")
                .path("toolAttempts");
        assertEquals(2, attempts.size());
        assertEquals("ERROR", attempts.get(0).path("outcome").asText());
        assertEquals("fixed", attempts.get(0).path("resolvedByCallId").asText());
        assertEquals("bad", attempts.get(1).path("correctionOfCallId").asText());
      }
      var agent = root.resolve("agents/local/AGENT.md");
      Files.writeString(
          agent,
          Files.readString(agent)
              .replace("bootstrap: []", "bootstrap: []\nsettings: {max_iterations: 1}"));
      f.models.set(0);
      f.modelMessage =
          n -> toolMessage("limit", Map.of("url", f.url(), "jsonBody", Map.of("text", "limit")));
      try (var c = start(f)) {
        call("POST", "/agents/local/invoke", Map.of("content", "limit"), 422, "ExecutionEnvelope");
      }
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void concurrentScheduleAndSessionMutationsAreRejectedUntilSettlement() throws Exception {
    try (var f = new PersistentFixture(root)) {
      schedule(f);
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      f.beforeModel =
          n -> {
            entered.countDown();
            try {
              release.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          };
      try (var c = start(f);
          var pool = Executors.newVirtualThreadPerTaskExecutor()) {
        var running =
            pool.submit(
                () -> call("POST", "/schedules/local:tick/run", null, 503, "ErrorEnvelope"));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        call("POST", "/schedules/local:tick/run", null, 409, "ErrorEnvelope");
        var session =
            call("GET", "/sessions", null, 200, "SessionPageEnvelope")
                .path("items")
                .get(0)
                .path("id")
                .asText();
        call("DELETE", "/sessions/" + session, null, 409, "ErrorEnvelope");
        call(
            "POST",
            "/sessions/" + session + "/messages",
            Map.of("content", "busy"),
            409,
            "ErrorEnvelope");
        c.getBean(RuntimeGate.class).recordingFailure(null);
        var state = call("GET", "/runtime/state", null, 200, "RuntimeStateEnvelope");
        call(
            "POST",
            "/runtime/recover",
            Map.of(
                "incidentId",
                state.path("incidentId").asText(),
                "acknowledgeMissingRecords",
                true,
                "notes",
                "Still in flight"),
            409,
            "ErrorEnvelope");
        release.countDown();
        running.get(5, TimeUnit.SECONDS);
        assertEquals(1, f.models.get());
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void disconnectedClientDoesNotCancelWorkOrReleaseSessionLock() throws Exception {
    try (var f = new PersistentFixture(root)) {
      f.useTool = true;
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      f.beforeModel =
          n -> {
            if (n == 1) {
              entered.countDown();
              try {
                release.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            }
          };
      try (var c = start(f)) {
        var uri = URI.create(base);
        byte[] body = json.writeValueAsBytes(Map.of("content", "disconnect"));
        try (var socket = new java.net.Socket("127.0.0.1", uri.getPort())) {
          socket
              .getOutputStream()
              .write(
                  ("POST /api/v1/agents/local/invoke HTTP/1.1\r\n"
                          + "Host: localhost\r\n"
                          + "X-Oryx-User-Id: operator\r\n"
                          + "Content-Type: application/json\r\n"
                          + "Content-Length: "
                          + body.length
                          + "\r\n\r\n")
                      .getBytes(java.nio.charset.StandardCharsets.UTF_8));
          socket.getOutputStream().write(body);
          socket.getOutputStream().flush();
          assertTrue(entered.await(5, TimeUnit.SECONDS));
        }
        String id =
            call("GET", "/sessions", null, 200, "SessionPageEnvelope")
                .path("items")
                .get(0)
                .path("id")
                .asText();
        call("DELETE", "/sessions/" + id, null, 409, "ErrorEnvelope");
        release.countDown();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (c.getBean(RuntimeGate.class).activeExecutions() > 0 && System.nanoTime() < until)
          Thread.sleep(10);
        assertEquals(0, c.getBean(RuntimeGate.class).activeExecutions());
        assertEquals(1, f.receipts.get());
        assertEquals(2, f.models.get());
        assertEquals(
            "ARCHIVED",
            call("GET", "/sessions/" + id, null, 200, "SessionHistoryEnvelope")
                .path("session")
                .path("status")
                .asText());
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void failedScheduleControlCannotClaimEnabledAndBadAgentIsIsolated() throws Exception {
    try (var f = new PersistentFixture(root)) {
      schedule(f);
      var config = root.resolve("agents/local/AGENT.md");
      Files.writeString(
          config, Files.readString(config).replace("requested-model", "LOCAL_SECRET_CANARY"));

      Files.createDirectories(root.resolve("agents/broken"));
      Files.writeString(root.resolve("agents/broken/AGENT.md"), "---\ninvalid: [\n---\n");
      try (var c = start(f)) {
        var profiles = call("GET", "/profiles", null, 200, "AgentPageEnvelope").path("items");
        assertEquals("UNAVAILABLE", profiles.get(0).path("availability").asText());
        call("POST", "/agents/broken/invoke", Map.of("content", "bad"), 409, "ErrorEnvelope");
        call(
            "PUT",
            "/schedules/local:tick/enabled",
            Map.of("enabled", "true"),
            400,
            "ErrorEnvelope");
        RecordingFailureIT.sql(
            c,
            "CREATE TRIGGER fail_enable BEFORE UPDATE ON scheduled_tasks BEGIN SELECT"
                + " RAISE(ABORT,'test'); END");
        call("PUT", "/schedules/local:tick/enabled", Map.of("enabled", true), 500, "ErrorEnvelope");
        assertFalse(
            call("GET", "/schedules", null, 200, "SchedulePageEnvelope")
                .path("items")
                .get(0)
                .path("enabled")
                .asBoolean());
        assertEquals(0, f.models.get());
        call("GET", "/health", null, 503, "HealthEnvelope");
        RecordingFailureIT.sql(c, "DROP TRIGGER fail_enable");
      }
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void httpReadonlyCrossChannelAndEmptyModelSnapshotStayBoundAcrossReload() throws Exception {
    try (var f = new PersistentFixture(root)) {
      String empty, cli;
      try (var c = start(f)) {
        var manager = c.getBean(io.github.oryxlabs.oryxos.core.session.SessionManager.class);
        var agent =
            c.getBean(io.github.oryxlabs.oryxos.boot.config.ToolConfiguration.CheckedAgents.class)
                .catalog()
                .require("local");
        try (var lease =
            manager.acquire(
                null,
                "cli",
                "operator",
                agent,
                io.github.oryxlabs.oryxos.core.session.Session.Kind.CONVERSATION)) {
          cli = lease.session().id().toString();
        }
        call("GET", "/sessions/" + cli, null, 200, "SessionHistoryEnvelope");
        call(
            "POST",
            "/sessions/" + cli + "/messages",
            Map.of("content", "wrong channel"),
            409,
            "ErrorEnvelope");
        empty =
            call("POST", "/sessions", Map.of("agentName", "local"), 201, "SessionEnvelope")
                .path("id")
                .asText();
        call(
            "POST",
            "/agents/local/invoke",
            Map.of("content", "a".repeat(32768)),
            200,
            "ExecutionEnvelope");
      }
      var file = root.resolve("agents/local/AGENT.md");
      Files.writeString(file, Files.readString(file).replace("requested-model", "changed-model"));
      try (var c = start(f)) {
        assertEquals(
            "requested-model",
            call("GET", "/sessions/" + empty, null, 200, "SessionHistoryEnvelope")
                .path("session")
                .path("model")
                .asText());
        assertEquals(
            "SESSION_MODEL_CHANGED",
            call(
                    "POST",
                    "/sessions/" + empty + "/messages",
                    Map.of("content", "no implicit switch"),
                    409,
                    "ErrorEnvelope")
                .path("code")
                .asText());
        var fresh = call("POST", "/sessions", Map.of("agentName", "local"), 201, "SessionEnvelope");
        assertEquals("changed-model", fresh.path("model").asText());
        assertEquals(1, f.models.get());
      }
    }
  }
}
