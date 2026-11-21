package io.github.oryxlabs.oryxos.boot.support;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import io.github.oryxlabs.oryxos.boot.config.AgentRuntimeConfiguration;
import io.github.oryxlabs.oryxos.boot.runtime.BootRuntimeLauncher;
import io.github.oryxlabs.oryxos.core.agent.AgentService;
import io.github.oryxlabs.oryxos.core.audit.AuditSink;
import io.github.oryxlabs.oryxos.core.execution.ExecutionStore;
import io.github.oryxlabs.oryxos.core.runtime.RuntimeTiming;
import io.github.oryxlabs.oryxos.core.session.SessionStore;
import io.github.oryxlabs.oryxos.core.workspace.*;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Fixed load driver. Only external responses are fixtures; all runtime/storage paths are
 * production.
 */
public final class SoakHarness implements AutoCloseable {
  public static final ObjectMapper JSON = new ObjectMapper();
  private static final Pattern TOKEN =
      Pattern.compile("SOAK~[a-f0-9]+~(warmup|steady)~c(\\d+)~a(\\d+)~t(\\d+)");
  public final Path folder, root;
  private final JsonNode profile;
  private final boolean acceptance, diagnostic;
  private final long modelDelay, toolDelay, durationSeconds;
  private final String runPrefix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
  private final HttpServer external;
  private final ExecutorService serverThreads = ExternalFixtureExecutor.create();
  private final ExecutorService clients = ExternalFixtureExecutor.clients();
  private final ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor();
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private ConfigurableApplicationContext context;
  private AgentService service;
  private String base;
  private final BufferedWriter samples;
  private final SoakEvidenceLog actions;
  private final ConcurrentMap<String, Waits> waits = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, String> fixtureActive = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Expected> expected = new ConcurrentHashMap<>();
  private final List<Long> createNanos = new ArrayList<>(),
      internalNanos = new ArrayList<>(),
      ingressIncludedNanos = new ArrayList<>(),
      totalNanos = new ArrayList<>();
  private final List<String> violations = new CopyOnWriteArrayList<>();
  private final AtomicLong violationCount = new AtomicLong(),
      completed = new AtomicLong(),
      failed = new AtomicLong(),
      attempts = new AtomicLong();
  private final AtomicInteger activeClients = new AtomicInteger(),
      peakClients = new AtomicInteger();
  private final AtomicBoolean stop = new AtomicBoolean();
  private volatile long steadyStart, steadyEnd, finishedAt;
  private volatile Instant wallStart;
  private volatile String fatalFailure;
  private volatile String phase = "SETUP";
  private volatile Map<String, Object> audit = Map.of("verified", false);
  private final List<Map<String, Object>> sessionRecords = new CopyOnWriteArrayList<>();

  private static final class Waits {
    final List<String> stages = new ArrayList<>();
    long observed, deducted;
  }

  private record Expected(
      String token, String session, String agent, String user, boolean acknowledged) {}

  public record Response(
      JsonNode data,
      int status,
      long serverNanos,
      long clientNanos,
      long boundaryStartNanos,
      List<RuntimeTiming.Segment> segments) {}

  public static JsonNode profile() throws Exception {
    try (var stream = SoakHarness.class.getResourceAsStream("/performance/load-profile.json")) {
      if (stream == null) throw new IllegalStateException("Missing load profile");
      return JSON.readTree(stream);
    }
  }

  public static void requireAcceptanceSettings(JsonNode p, Map<String, String> arguments) {
    if (p.path("agents").asInt() != 10
        || p.path("sessions").asInt() != 100
        || p.path("durationSeconds").asLong() != 120
        || p.path("clientThinkMillis").asInt(-1) != 0
        || p.path("maxInternalMillis").asInt() != 50
        || p.path("sessionCreateP99Millis").asInt() != 200)
      throw new IllegalArgumentException("T101 fixed load/thresholds must not be weakened");
    if (!arguments.getOrDefault("oryxos.tests.mode", "").equals("soak"))
      throw new IllegalArgumentException("Explicit soak mode required");
    if (!arguments.getOrDefault("oryxos.test.agents", "10").equals("10")
        || !arguments.getOrDefault("oryxos.test.sessions", "100").equals("100")
        || !Duration.parse(arguments.getOrDefault("oryxos.test.duration", "PT2M"))
            .equals(Duration.ofMinutes(2)))
      throw new IllegalArgumentException(
          "Acceptance requires 10 Agents, 100 Sessions and PT2M; use the separately labelled"
              + " harness smoke test for development");
  }

  public static SoakHarness diagnostic(Path folder) throws Exception {
    return new SoakHarness(folder, false, true);
  }

  public SoakHarness(Path folder, boolean acceptance) throws Exception {
    this(folder, acceptance, false);
  }

  private SoakHarness(Path folder, boolean acceptance, boolean diagnostic) throws Exception {
    this.folder = folder;
    this.root = folder.resolve("workspace");
    this.acceptance = acceptance;
    this.diagnostic = diagnostic;
    profile = profile();
    modelDelay = acceptance || diagnostic ? profile.path("modelDelayMillis").asLong() : 5;
    toolDelay = acceptance || diagnostic ? profile.path("toolDelayMillis").asLong() : 3;
    durationSeconds = acceptance ? profile.path("durationSeconds").asLong() : diagnostic ? 120 : 3;
    Files.createDirectories(folder);
    samples = Files.newBufferedWriter(folder.resolve("samples.jsonl"));
    actions = new SoakEvidenceLog(Files.newBufferedWriter(folder.resolve("external-waits.jsonl")));
    external = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 256);
    external.setExecutor(serverThreads);
    external.createContext("/chat/completions", this::model);
    external.createContext("/tool", this::tool);
    external.start();
    new WorkspaceInitializer()
        .initialize(
            new WorkspaceHandle(root),
            Map.of(
                "runtime.yaml",
                """
                schema_version: 1
                timezone: Asia/Shanghai
                providers:
                  soak:
                    protocol: openai_chat_completions
                    endpoint: %s
                    completions_path: /chat/completions
                    api_key: '${SOAK_KEY}'
                memory: {backend: md, archival_context_characters: 4000}
                tools:
                  http:
                    allowed_targets: [{scheme: http, host: 127.0.0.1, port: %d}]
                    connect_timeout_seconds: 5
                    read_timeout_seconds: 10
                """
                    .formatted(externalUrl(""), external.getAddress().getPort())));
    Files.deleteIfExists(root.resolve("agents/default/AGENT.md"));
    Files.writeString(root.resolve("secrets.local.yaml"), "SOAK_KEY: SOAK_LOCAL_TEST_KEY\n");
    Files.setPosixFilePermissions(
        root.resolve("secrets.local.yaml"),
        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    for (int a = 0; a < 10; a++) {
      var path = root.resolve("agents/agent-%02d/AGENT.md".formatted(a));
      Files.createDirectories(path.getParent());
      Files.writeString(
          path,
"""
---
schema_version: 1
name: agent-%02d
provider: {name: soak, model: local-soak-model}
tools: [http_get]
bootstrap: []
settings: {max_iterations: 4, max_history_turns: 4}
---
SOAK_AGENT=agent-%02d
Local controlled performance fixture only. Fetch the current request token once, then echo the tool result.
"""
              .formatted(a, a));
    }
    context =
        new BootRuntimeLauncher(Map.of(), SoakTiming.class)
            .start(
                new WorkspaceHandle(root),
                Map.of(
                    "server.port",
                    0,
                    "spring.main.web-application-type",
                    "servlet",
                    "oryxos.scheduling.enabled",
                    false));
    assertEquals("JpaSessionStore", context.getBean(SessionStore.class).getClass().getSimpleName());
    assertEquals(
        "JpaExecutionStore", context.getBean(ExecutionStore.class).getClass().getSimpleName());
    assertEquals("JpaAuditSink", context.getBean(AuditSink.class).getClass().getSimpleName());
    service = context.getBean(AgentRuntimeConfiguration.RuntimeAgents.class).require();
    base =
        "http://127.0.0.1:"
            + ((ServletWebServerApplicationContext) context).getWebServer().getPort();
    writeJson(folder.resolve("environment.json"), environment());
  }

  private String externalUrl(String path) {
    return "http://127.0.0.1:" + external.getAddress().getPort() + path;
  }

  private void violation(String message) {
    violationCount.incrementAndGet();
    if (violations.size() < 100) violations.add(message);
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }

  private void model(HttpExchange exchange) throws IOException {
    try {
      var request = JSON.readTree(exchange.getRequestBody());
      var messages = request.path("messages");
      int last = -1;
      for (int i = 0; i < messages.size(); i++)
        if (messages.get(i).path("role").asText().equals("user")) last = i;
      require(last >= 0, "missing user");
      String token = messages.get(last).path("content").asText();
      var matcher = TOKEN.matcher(token);
      require(matcher.matches(), "invalid current token");
      String owner = matcher.group(1) + ":" + matcher.group(2),
          agent = "agent-%02d".formatted(Integer.parseInt(matcher.group(3)));
      require(
          messages.get(0).path("content").asText().contains("SOAK_AGENT=" + agent),
          "cross-agent instructions");
      for (var message : messages)
        if (message.path("role").asText().equals("user")) {
          var prior = TOKEN.matcher(message.path("content").asText());
          require(
              prior.matches() && (prior.group(1) + ":" + prior.group(2)).equals(owner),
              "cross-session history");
        }
      boolean second = messages.get(messages.size() - 1).path("role").asText().equals("tool");
      Map<String, Object> answer;
      if (second) {
        require(
            messages.get(messages.size() - 1).path("content").asText().contains(token),
            "wrong tool result");
        require(token.equals(fixtureActive.get(owner)), "overlapping task or wrong continuation");
        delay(token, "MODEL_2", modelDelay);
        answer = Map.of("role", "assistant", "content", "ACK " + token);
        require(fixtureActive.remove(owner, token), "wrong active token");
      } else {
        require(
            fixtureActive.putIfAbsent(owner, token) == null,
            "overlapping execution for one session");
        delay(token, "MODEL_1", modelDelay);
        answer =
            Map.of(
                "role",
                "assistant",
                "content",
                "FETCH " + token,
                "tool_calls",
                List.of(
                    Map.of(
                        "id",
                        "call-" + token,
                        "type",
                        "function",
                        "function",
                        Map.of(
                            "name",
                            "http_get",
                            "arguments",
                            JSON.writeValueAsString(
                                Map.of(
                                    "url",
                                    externalUrl(
                                        "/tool?token="
                                            + URLEncoder.encode(
                                                token, StandardCharsets.UTF_8))))))));
      }
      reply(
          exchange,
          200,
          Map.of(
              "id",
              "reply-" + token + "-" + second,
              "model",
              "local-soak-model",
              "choices",
              List.of(
                  Map.of(
                      "index",
                      0,
                      "finish_reason",
                      second ? "stop" : "tool_calls",
                      "message",
                      answer)),
              "usage",
              Map.of("prompt_tokens", 10, "completion_tokens", 5, "total_tokens", 15)));
    } catch (Throwable e) {
      violation("model fixture: " + e.getMessage());
      reply(exchange, 500, Map.of("error", "controlled fixture rejected request"));
    } finally {
      exchange.close();
    }
  }

  private void tool(HttpExchange exchange) throws IOException {
    try {
      String query = exchange.getRequestURI().getRawQuery();
      require(query != null && query.startsWith("token="), "missing tool token");
      String token = URLDecoder.decode(query.substring(6), StandardCharsets.UTF_8);
      require(TOKEN.matcher(token).matches(), "bad tool token");
      delay(token, "TOOL", toolDelay);
      reply(exchange, 200, Map.of("value", token));
    } catch (Throwable e) {
      violation("tool fixture: " + e.getMessage());
      reply(exchange, 500, Map.of("error", "controlled tool failed"));
    } finally {
      exchange.close();
    }
  }

  private void delay(String token, String stage, long millis) throws Exception {
    var item = waits.computeIfAbsent(token, k -> new Waits());
    long begin = System.nanoTime();
    Thread.sleep(millis);
    long end = System.nanoTime();
    long deducted = SoakTiming.deductibleWait(end - begin, TimeUnit.MILLISECONDS.toNanos(millis));
    synchronized (item) {
      item.stages.add(stage);
      item.observed += end - begin;
      item.deducted += deducted;
    }
    actions.append(
        Map.of(
            "token",
            token,
            "stage",
            stage,
            "startNanos",
            begin,
            "endNanos",
            end,
            "configuredMillis",
            millis,
            "deductedNanos",
            deducted));
  }

  private static void reply(HttpExchange e, int status, Object value) throws IOException {
    byte[] b = JSON.writeValueAsBytes(value);
    e.getResponseHeaders().set("Content-Type", "application/json");
    e.sendResponseHeaders(status, b.length);
    e.getResponseBody().write(b);
  }

  private Response call(String method, String path, Object body, String user) throws Exception {
    String id = UUID.randomUUID().toString();
    var span = new SoakTiming.Span();
    SoakTiming.SPANS.put(id, span);
    try {
      var request =
          HttpRequest.newBuilder(URI.create(base + "/api/v1" + path))
              .timeout(Duration.ofSeconds(75))
              .header("Content-Type", "application/json")
              .header("X-Oryx-User-Id", user)
              .header("X-Oryx-Soak-Request", id)
              .method(
                  method,
                  body == null
                      ? HttpRequest.BodyPublishers.noBody()
                      : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
              .build();
      long start = System.nanoTime();
      var response = http.send(request, HttpResponse.BodyHandlers.ofString());
      long end = System.nanoTime();
      long server = span.elapsed.get(10, TimeUnit.SECONDS);
      return new Response(
          JSON.readTree(response.body()),
          response.statusCode(),
          server,
          end - start,
          span.startNanos(),
          List.copyOf(span.segments));
    } finally {
      SoakTiming.SPANS.remove(id);
    }
  }

  private UUID create(int client, boolean warmup, String purpose) throws Exception {
    String agent = "agent-%02d".formatted(client % 10),
        user = (warmup ? "warm-" : "client-") + client;
    var r = call("POST", "/sessions", Map.of("agentName", agent), user);
    synchronized (this) {
      if (!warmup) createNanos.add(r.serverNanos());
      record(
          Map.of(
              "kind",
              "session-create",
              "phase",
              warmup ? "warmup" : "steady",
              "purpose",
              purpose,
              "client",
              client,
              "agent",
              agent,
              "serverNanos",
              r.serverNanos(),
              "clientNanos",
              r.clientNanos(),
              "httpStatus",
              r.status(),
              "response",
              r.data()));
    }
    require(r.status() == 201, "session creation failed");
    UUID id = UUID.fromString(r.data().path("data").path("id").asText());
    sessionRecords.add(
        Map.of(
            "id",
            id.toString(),
            "client",
            client,
            "agent",
            agent,
            "user",
            user,
            "purpose",
            purpose));
    return id;
  }

  private void turn(int client, UUID session, int number, boolean warmup, long previousEnd)
      throws Exception {
    String phase = warmup ? "warmup" : "steady",
        agent = "agent-%02d".formatted(client % 10),
        user = (warmup ? "warm-" : "client-") + client;
    String token =
        "SOAK~" + runPrefix + "~" + phase + "~c" + client + "~a" + client % 10 + "~t" + number;
    long began = System.nanoTime();
    int active = activeClients.incrementAndGet();
    peakClients.accumulateAndGet(active, Math::max);
    if (!warmup) attempts.incrementAndGet();
    try {
      var r = call("POST", "/sessions/" + session + "/messages", Map.of("content", token), user);
      var data = r.data().path("data");
      String execution = data.path("id").asText();
      if (execution.isBlank()) execution = r.data().path("error").path("executionId").asText();
      var external = waits.remove(token);
      long deducted = external == null ? 0 : external.deducted;
      long internal = r.serverNanos() - deducted;
      long ingressIncluded =
          SoakTiming.ingressIncludedInternal(r.serverNanos(), r.clientNanos(), deducted);
      if (internal < 0) violation("negative internal duration");
      boolean success =
          r.status() == 200
              && data.path("status").asText().equals("SUCCEEDED")
              && data.path("recordingStatus").asText().equals("COMPLETE")
              && data.path("content").asText().equals("ACK " + token)
              && data.path("sessionId").asText().equals(session.toString())
              && data.path("identity").path("agentName").asText().equals(agent)
              && external != null
              && external.stages.equals(List.of("MODEL_1", "TOOL", "MODEL_2"));
      if (!execution.isBlank())
        expected.put(execution, new Expected(token, session.toString(), agent, user, success));
      if (!success)
        violation(
            "task not completely acknowledged: "
                + token
                + " HTTP "
                + r.status()
                + " "
                + data.path("status").asText());
      synchronized (this) {
        if (!warmup) {
          internalNanos.add(internal);
          ingressIncludedNanos.add(ingressIncluded);
          totalNanos.add(r.serverNanos());
          if (success) completed.incrementAndGet();
          else failed.incrementAndGet();
        }
        var sample = new LinkedHashMap<String, Object>();
        sample.put("kind", "task");
        sample.put("phase", phase);
        sample.put("token", token);
        sample.put("sessionId", session);
        sample.put("executionId", execution);
        sample.put("startNanos", began);
        sample.put("endNanos", System.nanoTime());
        sample.put("clientGapNanos", previousEnd == 0 ? null : began - previousEnd);
        sample.put("serverNanos", r.serverNanos());
        sample.put("clientNanos", r.clientNanos());
        sample.put("externalObservedNanos", external == null ? 0 : external.observed);
        sample.put("externalDeductedNanos", deducted);
        sample.put("internalNanos", internal);
        sample.put("ingressIncludedInternalNanos", ingressIncluded);
        sample.put("boundaryStartNanos", r.boundaryStartNanos());
        sample.put("segments", r.segments());
        sample.put("clientMinusServerNanos", r.clientNanos() - r.serverNanos());
        sample.put("success", success);
        sample.put("response", r.data());
        record(sample);
      }
      // Never let a caller timeout create a second in-flight execution for the same session.
      if (!execution.isBlank()) {
        UUID id = UUID.fromString(execution);
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(75);
        while (service.activeSnapshot(id) != null && System.nanoTime() < until) Thread.sleep(10);
        require(
            service.activeSnapshot(id) == null,
            "execution did not settle; client stopped without overlap");
      }
      if (!success)
        Thread.sleep(1000); // Fixed failure backoff only; no replay of the previous token.
    } finally {
      activeClients.decrementAndGet();
    }
  }

  public Map<String, Object> run() throws Exception {
    try {
      return executeLoad();
    } catch (Exception | AssertionError failure) {
      stop.set(true);
      fatalFailure = failure.getClass().getSimpleName() + ": " + failure.getMessage();
      violation("run aborted: " + fatalFailure);
      finishedAt = System.nanoTime();
      phase = "ABORTED";
      try {
        writeJson(folder.resolve("summary.json"), summary());
      } catch (IOException evidenceFailure) {
        failure.addSuppressed(evidenceFailure);
      }
      throw failure;
    }
  }

  private Map<String, Object> executeLoad() throws Exception {
    phase = "WARMUP";
    var warmup = new ArrayList<Future<?>>();
    for (int i = 0; i < (acceptance || diagnostic ? 10 : 1); i++) {
      int client = i;
      warmup.add(
          clients.submit(() -> {
            try {
              UUID session = create(client, true, "warmup");
              for (int round = 0; round < (acceptance || diagnostic ? 2 : 1); round++)
                turn(client, session, round, true, 0);
              require(
                  call("DELETE", "/sessions/" + session, null, "warm-" + client).status() == 200,
                  "warmup archive failed");
            } catch (Exception e) {
              throw new CompletionException(e);
            }
          }));
    }
    for (var f : warmup) f.get(150, TimeUnit.SECONDS);
    phase = "CREATE_100_SESSIONS";
    UUID[] sessions = new UUID[100];
    var created = new ArrayList<Future<?>>();
    var creationBarrier = new CountDownLatch(1);
    for (int i = 0; i < 100; i++) {
      int client = i;
      created.add(
          clients.submit(() -> {
            try {
              creationBarrier.await();
              sessions[client] = create(client, false, "load-client");
            } catch (Exception e) {
              throw new CompletionException(e);
            }
          }));
    }
    creationBarrier.countDown();
    for (var f : created) f.get(120, TimeUnit.SECONDS);
    writeJson(folder.resolve("sessions.json"), sessionRecords);
    phase = "STEADY";
    wallStart = Instant.now();
    steadyStart = System.nanoTime();
    steadyEnd = steadyStart + TimeUnit.SECONDS.toNanos(durationSeconds);
    var start = new CountDownLatch(1);
    var workers = new ArrayList<Future<?>>();
    for (int i = 0; i < 100; i++) {
      int client = i;
      workers.add(
          clients.submit(() -> {
            try {
              start.await();
              int round = 0;
              long previousEnd = 0;
              do {
                turn(client, sessions[client], round++, false, previousEnd);
                previousEnd = System.nanoTime();
              } while (!stop.get() && System.nanoTime() < steadyEnd);
            } catch (Throwable e) {
              violation(
                  "client "
                      + client
                      + " stopped: "
                      + e.getClass().getSimpleName()
                      + ": "
                      + e.getMessage());
            }
          }));
    }
    monitor.scheduleAtFixedRate(
        () -> {
          try {
            heartbeat();
            if (Files.getFileStore(folder).getUsableSpace() < 2L * 1024 * 1024 * 1024) {
              violation("disk space guard stopped load");
              stop.set(true);
            }
          } catch (Exception e) {
            violation("heartbeat failed: " + e.getClass().getSimpleName());
            stop.set(true);
          }
        },
        0,
        acceptance || diagnostic ? 30 : 1,
        TimeUnit.SECONDS);
    var probes =
        clients.submit(() -> {
          int n = 0;
          try {
            while (!stop.get() && System.nanoTime() < steadyEnd) {
              Thread.sleep(acceptance || diagnostic ? 60000 : 1000);
              if (stop.get() || System.nanoTime() >= steadyEnd) break;
              int client = 100 + n++;
              UUID id = create(client, false, "creation-under-load");
              require(
                  call("DELETE", "/sessions/" + id, null, "client-" + client).status() == 200,
                  "probe archive failed");
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } catch (Exception e) {
            violation("creation probe stopped: " + e.getMessage());
          }
        });
    start.countDown();
    // Even metric violations do not truncate the required observation period.
    while (!stop.get() && System.nanoTime() < steadyEnd)
      Thread.sleep(
          Math.min(
              1000, Math.max(1, TimeUnit.NANOSECONDS.toMillis(steadyEnd - System.nanoTime()))));
    long drainUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(150);
    for (var worker : workers)
      worker.get(Math.max(1, drainUntil - System.nanoTime()), TimeUnit.NANOSECONDS);
    probes.cancel(true);
    finishedAt = System.nanoTime();
    phase = "VERIFYING_SQLITE";
    monitor.shutdown();
    require(monitor.awaitTermination(10, TimeUnit.SECONDS), "monitor did not settle");
    heartbeat();
    require(service.activeCount() == 0, "in-flight runtime work remained after client drain");
    audit = verifyDatabase();
    phase = "FINISHED";
    var result = summary();
    writeJson(folder.resolve("summary.json"), result);
    heartbeat();
    return result;
  }

  private Map<String, Object> verifyDatabase() throws Exception {
    var counts = new LinkedHashMap<String, Long>();
    long missing = 0, corrupt = 0, badPairing = 0;
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("oryxos.db"));
        var s = c.createStatement()) {
      try (var r = s.executeQuery("PRAGMA integrity_check")) {
        require(r.next() && r.getString(1).equals("ok"), "SQLite integrity check failed");
      }
      try (var r = s.executeQuery("PRAGMA foreign_key_check")) {
        require(!r.next(), "SQLite foreign key violation");
      }
      for (var table :
          List.of("sessions", "executions", "messages", "llm_calls", "tool_invocations")) {
        try (var r = s.executeQuery("SELECT count(*) FROM " + table)) {
          r.next();
          counts.put(table, r.getLong(1));
        }
      }
      var seen = new HashSet<String>();
      String sql =
"""
SELECT e.*, coalesce(l.n,0) ln, coalesce(l.ok,0) lok, coalesce(t.n,0) tn, coalesce(t.ok,0) tok, coalesce(m.n,0) mn
FROM executions e
LEFT JOIN (SELECT execution_id,count(*) n,sum(status='SUCCEEDED') ok FROM llm_calls GROUP BY execution_id) l ON l.execution_id=e.id
LEFT JOIN (SELECT execution_id,count(*) n,sum(status='SUCCESS') ok FROM tool_invocations GROUP BY execution_id) t ON t.execution_id=e.id
LEFT JOIN (SELECT execution_id,count(*) n FROM messages GROUP BY execution_id) m ON m.execution_id=e.id
""";
      try (var r = s.executeQuery(sql)) {
        while (r.next()) {
          var wanted = expected.get(r.getString("id"));
          seen.add(r.getString("id"));
          if (wanted == null) {
            corrupt++;
            continue;
          }
          boolean valid =
              wanted.session.equals(r.getString("session_id"))
                  && wanted.agent.equals(r.getString("agent_name"))
                  && wanted.user.equals(r.getString("user_id"));
          if (wanted.acknowledged)
            valid &=
                r.getString("status").equals("SUCCEEDED")
                    && r.getString("recording_status").equals("COMPLETE")
                    && r.getInt("turn_complete") == 1
                    && r.getString("final_content").equals("ACK " + wanted.token)
                    && r.getInt("ln") == 2
                    && r.getInt("lok") == 2
                    && r.getInt("tn") == 1
                    && r.getInt("tok") == 1
                    && r.getInt("mn") == 4;
          if (!valid) corrupt++;
        }
      }
      for (var e : expected.entrySet())
        if (e.getValue().acknowledged && !seen.contains(e.getKey())) missing++;
      Map<String, Set<String>> proposals = new HashMap<>(), answers = new HashMap<>();
      var sequences = new HashMap<String, Long>();
      try (var r = s.executeQuery("SELECT * FROM messages ORDER BY session_id,seq")) {
        while (r.next()) {
          String execution = r.getString("execution_id"), session = r.getString("session_id");
          var wanted = expected.get(execution);
          long previous = sequences.getOrDefault(session, 0L);
          if (r.getLong("seq") != previous + 1) corrupt++;
          sequences.put(session, r.getLong("seq"));
          var message = JSON.readTree(r.getString("content_json")).path("message");
          if (wanted == null
              || !wanted.session.equals(session)
              || !message.path("content").asText().contains(wanted.token)) corrupt++;
          for (var call : message.path("toolCalls"))
            proposals
                .computeIfAbsent(execution, k -> new HashSet<>())
                .add(call.path("id").asText());
          if (r.getString("role").equals("TOOL"))
            answers
                .computeIfAbsent(execution, k -> new HashSet<>())
                .add(message.path("toolCallId").asText());
        }
      }
      for (var entry : expected.entrySet())
        if (entry.getValue().acknowledged
            && (!proposals
                    .getOrDefault(entry.getKey(), Set.of())
                    .equals(answers.getOrDefault(entry.getKey(), Set.of()))
                || proposals.getOrDefault(entry.getKey(), Set.of()).size() != 1)) badPairing++;
      try (var r =
          s.executeQuery(
              "SELECT count(*) FROM (SELECT started_at,lag(finished_at) OVER (PARTITION BY"
                  + " session_id ORDER BY started_at,id) previous FROM executions) WHERE"
                  + " julianday(started_at)<julianday(previous)")) {
        r.next();
        counts.put("overlappingExecutionIntervals", r.getLong(1));
      }
      try (var r = s.executeQuery("SELECT count(*) FROM sessions WHERE status='ACTIVE'")) {
        r.next();
        counts.put("activeSessionsAtEnd", r.getLong(1));
      }
    }
    boolean valid =
        missing == 0
            && corrupt == 0
            && badPairing == 0
            && counts.get("overlappingExecutionIntervals") == 0
            && counts.get("activeSessionsAtEnd") == 100;
    return Map.of(
        "verified",
        valid,
        "rows",
        counts,
        "missingAcknowledgedWrites",
        missing,
        "corruptOrForeignRecords",
        corrupt,
        "badToolCallPairing",
        badPairing);
  }

  private synchronized void record(Object value) throws IOException {
    samples.write(JSON.writeValueAsString(value));
    samples.newLine();
    samples.flush();
  }

  private static void writeJson(Path path, Object value) throws IOException {
    Path tmp = Path.of(path + ".tmp");
    JSON.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), value);
    Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  private synchronized Map<String, Object> distribution(List<Long> values) {
    if (values.isEmpty()) return Map.of("count", 0);
    return Map.of(
        "count",
        values.size(),
        "p50Millis",
        SoakTiming.percentileMillis(values, .5),
        "p95Millis",
        SoakTiming.percentileMillis(values, .95),
        "p99Millis",
        SoakTiming.percentileMillis(values, .99),
        "maxMillis",
        values.stream().mapToLong(Long::longValue).max().orElseThrow() / 1_000_000d,
        "over50Millis",
        values.stream().filter(n -> n > 50_000_000L).count());
  }

  public synchronized Map<String, Object> summary() {
    long now = finishedAt == 0 ? System.nanoTime() : finishedAt;
    double seconds = steadyStart == 0 ? 0 : (now - steadyStart) / 1_000_000_000d;
    double p99 = SoakTiming.percentileMillis(createNanos, .99),
        max =
            internalNanos.stream().mapToLong(Long::longValue).max().orElse(Long.MAX_VALUE)
                / 1_000_000d;
    double ingressMax =
        ingressIncludedNanos.stream().mapToLong(Long::longValue).max().orElse(Long.MAX_VALUE)
            / 1_000_000d;
    boolean full = acceptance && seconds >= 120 && !stop.get();
    boolean correct =
        Boolean.TRUE.equals(audit.get("verified"))
            && violationCount.get() == 0
            && failed.get() == 0;
    var value = new LinkedHashMap<String, Object>();
    value.put("fatalFailure", fatalFailure);
    value.put("phase", phase);
    value.put("acceptanceRun", acceptance);
    value.put(
        "result",
        phase.equals("ABORTED")
            ? "ABORTED"
            : !phase.equals("FINISHED")
                ? "RUNNING"
                : !acceptance
                    ? diagnostic ? "DIAGNOSTIC_ONLY_NOT_T101" : "SMOKE_ONLY_NOT_T101"
                    : full && correct && p99 <= 200 && max <= 50 && ingressMax <= 50
                        ? "PASSED"
                        : "FAILED");
    value.put("startedAt", wallStart == null ? null : wallStart.toString());
    value.put(
        "startedAtShanghai",
        wallStart == null ? null : wallStart.atZone(ZoneId.of("Asia/Shanghai")).toString());
    value.put("elapsedSeconds", seconds);
    value.put("requiredSeconds", 120);
    value.put("agents", 10);
    value.put("clients", 100);
    value.put("activeClients", activeClients.get());
    value.put("peakInFlightClients", peakClients.get());
    value.put("attempts", attempts.get());
    value.put("completed", completed.get());
    value.put("failedTasks", failed.get());
    value.put("throughputPerSecond", seconds == 0 ? 0 : completed.get() / seconds);
    value.put("creation", distribution(createNanos));
    value.put("internal", distribution(internalNanos));
    value.put("ingressIncludedInternal", distribution(ingressIncludedNanos));
    value.put("serverTotal", distribution(totalNanos));
    value.put(
        "criteria",
        Map.of(
            "twoMinutes",
            full,
            "stabilityAndAudit",
            correct,
            "sessionCreateP99AtMost200ms",
            p99 <= 200,
            "everyTaskInternalAtMost50ms",
            max <= 50,
            "everyTaskIncludingIngressAtMost50ms",
            ingressMax <= 50));
    value.put("violations", violationCount.get());
    value.put("firstViolations", violations);
    value.put("audit", audit);
    value.put("heapUsedBytes", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
    var os =
        (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    value.put("processCpuNanos", os.getProcessCpuTime());
    value.put("committedVirtualMemoryBytes", os.getCommittedVirtualMemorySize());
    value.put(
        "gcCount",
        ManagementFactory.getGarbageCollectorMXBeans().stream()
            .mapToLong(b -> Math.max(0, b.getCollectionCount()))
            .sum());
    value.put(
        "gcMillis",
        ManagementFactory.getGarbageCollectorMXBeans().stream()
            .mapToLong(b -> Math.max(0, b.getCollectionTime()))
            .sum());
    value.put("runtimeInFlight", service == null ? 0 : service.activeCount());
    return value;
  }

  private void heartbeat() throws IOException {
    var value = summary();
    writeJson(folder.resolve("progress.json"), value);
    synchronized (this) {
      record(Map.of("kind", "heartbeat", "time", Instant.now().toString(), "state", value));
    }
    System.out.println(
        "SOAK_PROGRESS "
            + JSON.writeValueAsString(
                Map.of(
                    "phase",
                    phase,
                    "elapsedSeconds",
                    value.get("elapsedSeconds"),
                    "completed",
                    completed.get(),
                    "failed",
                    failed.get(),
                    "violations",
                    violationCount.get(),
                    "internal",
                    value.get("internal"))));
  }

  private Map<String, Object> environment() throws Exception {
    var data = new LinkedHashMap<String, Object>();
    data.put("profile", profile);
    data.put("acceptanceRun", acceptance);
    data.put("diagnosticRun", diagnostic);
    data.put(
        "externalFixtureExecutor",
        Map.of(
            "kind",
            "prestarted platform pool",
            "threads",
            128,
            "queueCapacity",
            256,
            "sharesRuntimeVirtualCarriers",
            false));
    data.put(
        "loadDriverExecutor",
        Map.of(
            "kind",
            "prestarted platform pool",
            "threads",
            128,
            "queueCapacity",
            256,
            "sharesRuntimeVirtualCarriers",
            false));
    data.put("actualModelDelayMillis", modelDelay);
    data.put("actualToolDelayMillis", toolDelay);
    data.put("actualDurationSeconds", durationSeconds);
    data.put("creationProbeMillis", acceptance || diagnostic ? 60000 : 1000);
    data.put("pid", ProcessHandle.current().pid());
    data.put("javaVersion", System.getProperty("java.version"));
    data.put("jvm", System.getProperty("java.vm.name"));
    data.put("jvmArguments", ManagementFactory.getRuntimeMXBean().getInputArguments());
    data.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
    data.put("availableProcessors", Runtime.getRuntime().availableProcessors());
    data.put("os", System.getProperty("os.name"));
    data.put("osVersion", System.getProperty("os.version"));
    data.put("arch", System.getProperty("os.arch"));
    data.put("diskType", Files.getFileStore(folder).type());
    data.put("usableDiskBytesAtStart", Files.getFileStore(folder).getUsableSpace());
    data.put(
        "measurement",
        "monotonic outer servlet Filter entry to final async onComplete; subtract only min(observed"
            + " external sleep, configured external sleep); transport, framework, queue, DB and"
            + " audit remain included; HTTP client roundtrip also retained");
    data.put("stores", List.of("JpaSessionStore", "JpaExecutionStore", "JpaAuditSink"));
    data.put("runId", System.getProperty("oryxos.test.runId"));
    return data;
  }

  public void close() throws Exception {
    stop.set(true);
    monitor.shutdownNow();
    clients.shutdownNow();
    if (context != null) context.close();
    external.stop(0);
    serverThreads.shutdownNow();
    http.close();
    samples.close();
    actions.close();
  }
}
