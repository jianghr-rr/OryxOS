package io.github.oryxlabs.oryxos.provider;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import io.github.oryxlabs.oryxos.core.audit.*;
import io.github.oryxlabs.oryxos.core.config.*;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.model.*;
import io.github.oryxlabs.oryxos.core.provider.*;
import io.github.oryxlabs.oryxos.core.security.Redactor;
import io.github.oryxlabs.oryxos.core.workspace.WorkspaceHandle;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Tag("us1-contract")
class ProviderContractTest {
  @TempDir Path root;
  static final ObjectMapper JSON = new ObjectMapper();
  static final String SECRET = "fixture-secret-do-not-log";
  final List<ModelCallRecord> audit = new CopyOnWriteArrayList<>();
  final Redactor redactor = new Redactor();
  final List<ProviderRegistry> owned = new ArrayList<>();

  @AfterEach
  void closeRegistries() {
    owned.forEach(ProviderRegistry::close);
  }

  static final class Receiver implements AutoCloseable {
    final HttpServer server;
    final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    final List<String> paths = new CopyOnWriteArrayList<>();
    final List<Integer> remotePorts = new CopyOnWriteArrayList<>();
    final List<String> credentials = new CopyOnWriteArrayList<>();
    volatile int status = 200;
    volatile long delayMillis;
    volatile boolean delayBody;
    volatile boolean disconnect;
    volatile String response = ordinary("served-model", "fixture answer");

    Receiver() throws Exception {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(executor);
      server.createContext(
          "/",
          exchange -> {
            try (exchange) {
              paths.add(exchange.getRequestURI().toString());
              remotePorts.add(exchange.getRemoteAddress().getPort());
              credentials.add(exchange.getRequestHeaders().getFirst("Authorization"));
              requests.add(JSON.readTree(exchange.getRequestBody()));
              if (disconnect) return;
              if (delayMillis > 0 && !delayBody) pause(delayMillis);
              byte[] body = response.getBytes(StandardCharsets.UTF_8);
              exchange.getResponseHeaders().set("Content-Type", "application/json");
              exchange.sendResponseHeaders(status, body.length);
              if (delayBody) {
                exchange.getResponseBody().write(body[0]);
                exchange.getResponseBody().flush();
                pause(delayMillis);
                exchange.getResponseBody().write(body, 1, body.length - 1);
              } else exchange.getResponseBody().write(body);
            }
          });
      server.start();
    }

    String origin() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void close() {
      server.stop(0);
      executor.shutdownNow();
    }

    private static void pause(long millis) {
      try {
        Thread.sleep(millis);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  static String ordinary(String model, String content) {
    return "{\"id\":\"fixture\",\"object\":\"chat.completion\",\"model\":\""
        + model
        + "\",\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\""
        + content
        + "\"}}]}";
  }

  static String definition(String name, String origin, String path, String options) {
    return "  "
        + name
        + ":\n    protocol: openai_chat_completions\n    endpoint: "
        + origin
        + "\n    completions_path: "
        + path
        + "\n    api_key: \"${"
        + name.toUpperCase(Locale.ROOT)
        + "_KEY}\"\n    options: "
        + options
        + "\n";
  }

  ProviderRegistry registry(String definitions, Map<String, String> environment) throws Exception {
    Files.writeString(
        root.resolve("runtime.yaml"), "schema_version: 1\nproviders:\n" + definitions);
    var workspace = new WorkspaceHandle(root);
    var config = new RuntimeConfigLoader(environment, redactor).load(workspace);
    var registry = new ProviderRegistry(config, workspace, environment, redactor, audit::add);
    owned.add(registry);
    return registry;
  }

  static ProviderRequest request(String provider, String text) {
    return request(
        provider,
        List.of(ChatMessage.user(text)),
        ToolNameMap.empty(),
        List.of(),
        Duration.ofSeconds(3));
  }

  static ProviderRequest request(
      String provider,
      List<ChatMessage> messages,
      ToolNameMap names,
      List<ProviderRequest.ToolSchema> tools,
      Duration budget) {
    return new ProviderRequest(
        provider,
        "configured-model",
        "fixture system",
        messages,
        tools,
        names,
        budget,
        UUID.randomUUID(),
        1);
  }

  @Test
  @Tag("kernel-provider-timing")
  void transportTimingSeparatesEncodingHeadersAndBodyWithoutChangingReplies() throws Exception {
    try (var receiver = new Receiver()) {
      var port =
          registry(
              definition("good", receiver.origin(), "/chat/completions", "{}"),
              Map.of("GOOD_KEY", SECRET));
      port.call(request("good", "warm"));
      receiver.delayMillis = 150;
      for (boolean delayedBody : List.of(false, true)) {
        receiver.delayBody = delayedBody;
        var spans =
            new CopyOnWriteArrayList<
                io.github.oryxlabs.oryxos.core.runtime.RuntimeTiming.Segment>();
        try (var timing = io.github.oryxlabs.oryxos.core.runtime.RuntimeTiming.bind(spans::add)) {
          assertEquals(
              "fixture answer", port.call(request("good", "timed")).assistantMessage().content());
        }
        var names =
            spans.stream()
                .map(io.github.oryxlabs.oryxos.core.runtime.RuntimeTiming.Segment::name)
                .toList();
        for (String name :
            List.of(
                "provider.http.encode",
                "provider.http.headers",
                "provider.http.read",
                "provider.http.consume",
                "provider.http.close"))
          assertTrue(names.contains(name), () -> "Missing measured HTTP boundary: " + name);
        String delayed = delayedBody ? "provider.http.read" : "provider.http.headers";
        long wait =
            spans.stream()
                .filter(s -> s.name().equals(delayed))
                .mapToLong(s -> s.endNanos() - s.startNanos())
                .sum();
        assertTrue(
            wait >= TimeUnit.MILLISECONDS.toNanos(100),
            "Controlled delay must stay in its measured boundary");
        var transport =
            spans.stream()
                .filter(s -> s.name().equals("provider.transport"))
                .findFirst()
                .orElseThrow();
        for (var span : spans)
          if (span.name().startsWith("provider.http.")) {
            assertTrue(span.startNanos() >= transport.startNanos());
            assertTrue(span.endNanos() <= transport.endNanos());
          }
      }
      assertEquals(3, receiver.requests.size(), "Instrumentation must not replay requests");
      assertEquals(6, audit.size(), "Every call retains STARTED and its completed audit record");
    }
  }

  @Test
  @Tag("kernel-provider-timing")
  void hundredConcurrentCallsEncodeInlineWithContentLengthAndReuseApiInstance() throws Exception {
    var serverThreads = Executors.newCachedThreadPool();
    var arrived = new CountDownLatch(100);
    var contentLengths = new ConcurrentLinkedQueue<String>();
    var transferEncodings = new ConcurrentLinkedQueue<String>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 256);
    server.setExecutor(serverThreads);
    server.createContext(
        "/chat/completions",
        exchange -> {
          try (exchange) {
            exchange.getRequestBody().readAllBytes();
            contentLengths.add(
                String.valueOf(exchange.getRequestHeaders().getFirst("Content-Length")));
            String te = exchange.getRequestHeaders().getFirst("Transfer-Encoding");
            if (te != null) transferEncodings.add(te);
            arrived.countDown();
            if (!arrived.await(10, TimeUnit.SECONDS)) {
              exchange.sendResponseHeaders(504, -1);
              return;
            }
            byte[] reply =
                ordinary("served-model", "inline-ok").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, reply.length);
            exchange.getResponseBody().write(reply);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exchange.sendResponseHeaders(500, -1);
          }
        });
    server.start();
    try {
      String origin = "http://127.0.0.1:" + server.getAddress().getPort();
      var def =
          new ProviderDefinition(
              "inline",
              "openai_chat_completions",
              URI.create(origin),
              "/chat/completions",
              "${GOOD_KEY}",
              5,
              Map.of(),
              null);
      try (var clients = new OpenAiClientFactory(def, SECRET)) {
        assertSame(
            clients.create(Duration.ofSeconds(3)),
            clients.create(Duration.ofSeconds(3)),
            "Provider must reuse one OpenAiApi wrapper instead of rebuilding per request");
      }
      var port =
          registry(
              definition("good", origin, "/chat/completions", "{}"), Map.of("GOOD_KEY", SECRET));
      var encodeCount = new java.util.concurrent.atomic.AtomicInteger();
      var mismatchedEncodeThreads = new ConcurrentLinkedQueue<String>();
      try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
        var barrier = new CountDownLatch(1);
        var calls = new ArrayList<Future<?>>();
        for (int i = 0; i < 100; i++) {
          calls.add(
              workers.submit(
                  () -> {
                    barrier.await();
                    long callerThread = Thread.currentThread().threadId();
                    try (var timing =
                        io.github.oryxlabs.oryxos.core.runtime.RuntimeTiming.bind(
                            s -> {
                              if (s.name().equals("provider.http.encode")) {
                                encodeCount.incrementAndGet();
                                long writerThread = Thread.currentThread().threadId();
                                if (writerThread != callerThread)
                                  mismatchedEncodeThreads.add(callerThread + "->" + writerThread);
                              }
                            })) {
                      assertEquals(
                          "inline-ok",
                          port.call(request("good", "inline-body")).assistantMessage().content());
                    }
                    return null;
                  }));
        }
        barrier.countDown();
        for (var call : calls) call.get(15, TimeUnit.SECONDS);
      }
      assertEquals(100, encodeCount.get());
      assertTrue(
          mismatchedEncodeThreads.isEmpty(),
          () ->
              "Request body encoding switched off caller thread: "
                  + mismatchedEncodeThreads.size());
      assertEquals(100, contentLengths.size());
      assertTrue(
          contentLengths.stream().allMatch(v -> v != null && !v.equals("null") && Integer.parseInt(v) > 0),
          () -> "Expected fixed Content-Length on every request: " + contentLengths);
      assertTrue(
          transferEncodings.isEmpty(),
          () -> "Unexpected Transfer-Encoding on small JSON requests: " + transferEncodings);
    } finally {
      server.stop(0);
      serverThreads.shutdownNow();
    }
  }

  @Test
  @Tag("kernel-provider-resources")
  void requestBodySerializationDoesNotAllocatePerCallPlatformWorkers() throws Exception {
    try (var receiver = new Receiver();
        var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var port =
          registry(
              definition("good", receiver.origin(), "/chat/completions", "{}"),
              Map.of("GOOD_KEY", SECRET));
      var platformWriters = ConcurrentHashMap.<Long>newKeySet();
      var writes = new java.util.concurrent.atomic.AtomicInteger();
      var calls = new ArrayList<Future<?>>();
      for (int i = 0; i < 100; i++)
        calls.add(
            workers.submit(
                () -> {
                  try (var timing =
                      io.github.oryxlabs.oryxos.core.runtime.RuntimeTiming.bind(
                          s -> {
                            if (s.name().equals("provider.http.encode")) {
                              writes.incrementAndGet();
                              if (!Thread.currentThread().isVirtual())
                                platformWriters.add(Thread.currentThread().threadId());
                            }
                          })) {
                    assertEquals(
                        "fixture answer",
                        port.call(request("good", "body-worker")).assistantMessage().content());
                  }
                }));
      for (var call : calls) call.get(10, TimeUnit.SECONDS);
      assertEquals(100, receiver.requests.size());
      assertEquals(100, writes.get());
      assertTrue(
          platformWriters.isEmpty(),
          () -> "Request bodies allocated platform workers: " + platformWriters.size());
    }
  }

  @Test
  @Tag("kernel-provider-resources")
  void requestWrappersDoNotAllocateUnownedTransportThreads() throws Exception {
    var definition =
        new ProviderDefinition(
            "probe",
            "openai_chat_completions",
            URI.create("http://127.0.0.1:1"),
            "/chat/completions",
            "${PROBE_KEY}",
            5,
            Map.of(),
            null);
    var requests = new ArrayList<org.springframework.ai.openai.api.OpenAiApi>();
    try (var clients = new OpenAiClientFactory(definition, "local-resource-test")) {
      var before = selectorThreads();
      // Keep the wrappers reachable: garbage collection must not be the resource lifecycle.
      for (int i = 0; i < 100; i++) requests.add(clients.create(Duration.ofSeconds(3)));
      var added = selectorThreads();
      added.removeAll(before);
      assertEquals(0, added.size(), "Per-request wrappers created unowned HTTP selector threads");
    } finally {
      java.lang.ref.Reference.reachabilityFence(requests);
    }
  }

  private static Set<Long> selectorThreads() {
    var ids = new HashSet<Long>();
    for (var thread : Thread.getAllStackTraces().keySet())
      if (thread.getName().startsWith("HttpClient-")
          && thread.getName().endsWith("-SelectorManager")) ids.add(thread.threadId());
    return ids;
  }

  @Test
  @Tag("kernel-transport")
  void reusesConnectionsWithoutSharingTimeoutsOrClosingConcurrentCalls() throws Exception {
    try (var receiver = new Receiver();
        var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var port =
          registry(
              definition("good", receiver.origin(), "/chat/completions", "{}"),
              Map.of("GOOD_KEY", SECRET));
      port.call(request("good", "warm one"));
      port.call(request("good", "warm two"));
      assertEquals(
          receiver.remotePorts.get(0),
          receiver.remotePorts.get(1),
          "Sequential calls should reuse one physical connection");
      receiver.delayMillis = 300;
      var shortCall =
          workers.submit(
              () ->
                  assertCode(
                      "PROVIDER_TIMEOUT",
                      () ->
                          port.call(
                              request(
                                  "good",
                                  List.of(ChatMessage.user("short")),
                                  ToolNameMap.empty(),
                                  List.of(),
                                  Duration.ofMillis(80)))));
      var longCall =
          workers.submit(
              () ->
                  port.call(
                      request(
                          "good",
                          List.of(ChatMessage.user("long")),
                          ToolNameMap.empty(),
                          List.of(),
                          Duration.ofSeconds(3))));
      shortCall.get(5, TimeUnit.SECONDS);
      assertEquals(
          "fixture answer", longCall.get(5, TimeUnit.SECONDS).assistantMessage().content());
      receiver.delayMillis = 0;
      assertEquals(
          "fixture answer",
          port.call(request("good", "after timeout")).assistantMessage().content());
      assertEquals(5, receiver.requests.size(), "A timeout must not trigger a hidden retry");
      port.close();
      assertCode("PROVIDER_UNAVAILABLE", () -> port.call(request("good", "closed")));
      assertEquals(5, receiver.requests.size());
    }
  }

  @Test
  void routesTwoProvidersAndAuditsActualModelsWithoutInventingUsage() throws Exception {
    try (var deepseek = new Receiver();
        var kimi = new Receiver()) {
      deepseek.response = ordinary("deepseek-actual", "from deepseek");
      kimi.response = ordinary("kimi-actual", "from kimi");
      var port =
          registry(
              definition("kimi", kimi.origin(), "/v1/chat/completions", "{reasoning_effort: low}")
                  + definition(
                      "deepseek",
                      deepseek.origin(),
                      "/chat/completions",
                      "{thinking: {type: disabled}}"),
              Map.of("DEEPSEEK_KEY", SECRET, "KIMI_KEY", "kimi-fixture-secret"));
      var first = assertDoesNotThrow(() -> port.call(request("deepseek", "hello")));
      var second = assertDoesNotThrow(() -> port.call(request("kimi", "hello")));
      assertEquals("deepseek", first.actualProvider());
      assertEquals("deepseek-actual", first.actualModel());
      assertEquals("from kimi", second.assistantMessage().content());
      assertEquals("kimi-actual", second.actualModel());
      assertNull(first.usage());
      assertTrue(first.toolCalls().isEmpty());
      assertEquals(List.of("/chat/completions"), deepseek.paths);
      assertEquals(List.of("/v1/chat/completions"), kimi.paths);
      assertEquals(List.of("Bearer " + SECRET), deepseek.credentials);
      var d = deepseek.requests.getFirst();
      var k = kimi.requests.getFirst();
      assertEquals("configured-model", d.path("model").asText());
      assertEquals("disabled", d.path("thinking").path("type").asText());
      assertFalse(d.has("extra_body"));
      assertFalse(k.has("thinking"));
      assertFalse(k.has("temperature"));
      assertEquals("low", k.path("reasoning_effort").asText());
      assertEquals(2, d.path("messages").size());
      assertEquals("hello", d.path("messages").get(1).path("content").asText());
      assertEquals(4, audit.size());
      assertEquals("STARTED", audit.get(0).status());
      assertEquals("SUCCEEDED", audit.get(1).status());
      assertEquals(audit.get(0).callId(), audit.get(1).callId());
      assertEquals("deepseek-actual", audit.get(1).actualModel());
      assertNull(audit.get(1).inputTokens());
      assertNotNull(audit.get(1).durationMs());
      assertFalse(audit.toString().contains(SECRET));
      assertFalse(audit.toString().contains("hello"));
    }
  }

  @Test
  void missingCredentialsAndEmptyTasksNeverReachNetwork() throws Exception {
    try (var good = new Receiver();
        var bad = new Receiver()) {
      var port =
          registry(
              definition("good", good.origin(), "/chat/completions", "{}")
                  + definition("bad", bad.origin(), "/chat/completions", "{}"),
              Map.of("GOOD_KEY", SECRET));
      assertCode("PROVIDER_UNAVAILABLE", () -> port.call(request("bad", "hello")));
      assertCode("PROVIDER_UNAVAILABLE", () -> port.call(request("absent", "hello")));
      assertCode("INVALID_TASK", () -> port.call(request("good", " \n\t")));
      assertEquals(0, bad.requests.size());
      assertEquals(0, good.requests.size());
      assertDoesNotThrow(() -> port.call(request("good", "hello")));
      assertEquals(1, good.requests.size());
      for (String invalid :
          List.of(
              definition("kimi", bad.origin(), "/v1/chat/completions", "{temperature: 0.5}"),
              definition(
                  "kimi", bad.origin(), "/v1/chat/completions", "{thinking: {type: disabled}}"),
              definition("kimi", bad.origin(), "/v1/v1/chat/completions", "{}"))) {
        var isolated =
            registry(
                definition("good", good.origin(), "/chat/completions", "{}") + invalid,
                Map.of("GOOD_KEY", SECRET, "KIMI_KEY", "fixture-kimi"));
        assertCode("PROVIDER_UNAVAILABLE", () -> isolated.call(request("kimi", "hello")));
        assertDoesNotThrow(() -> isolated.call(request("good", "hello")));
      }
      assertEquals(0, bad.requests.size());
      assertEquals(4, good.requests.size());
    }
  }

  @Test
  void returnsStructuredCallsAndReplaysReasoningWithoutExecutingTools() throws Exception {
    try (var receiver = new Receiver()) {
      receiver.response =
          new String(
              Objects.requireNonNull(
                      getClass().getResourceAsStream("/responses/tool-reasoning.json"))
                  .readAllBytes(),
              StandardCharsets.UTF_8);
      var port =
          registry(
              definition(
                  "kimi", receiver.origin(), "/v1/chat/completions", "{reasoning_effort: low}"),
              Map.of("KIMI_KEY", SECRET));
      var names = new ToolNameMap(List.of(new ToolNameMap.Entry("weather", "weather", null, null)));
      var schemas =
          List.of(
              new ProviderRequest.ToolSchema(
                  "weather", "Read weather", "{\"type\":\"object\",\"properties\":{}}"));
      var first =
          assertDoesNotThrow(
              () ->
                  port.call(
                      request(
                          "kimi",
                          List.of(ChatMessage.user("weather please")),
                          names,
                          schemas,
                          Duration.ofSeconds(3))));
      assertEquals(
          1, receiver.requests.size()); // A tool proposal cannot cause another request or callback.
      assertEquals("call-weather-1", first.toolCalls().getFirst().id());
      assertEquals("weather", first.toolCalls().getFirst().name());
      assertEquals("{\"city\":\"北京\"}", first.toolCalls().getFirst().argumentsJson());
      assertEquals(
          "private reasoning fixture",
          first.assistantMessage().providerOpaque().reasoningContent());
      assertEquals(12, first.usage().inputTokens());
      assertEquals(8, first.usage().outputTokens());
      assertFalse(first.toString().contains("private reasoning"));
      assertFalse(audit.toString().contains("private reasoning"));
      receiver.response = ordinary("kimi-actual", "sunny");
      var history =
          List.of(
              ChatMessage.user("weather please"),
              first.assistantMessage(),
              ChatMessage.tool(first.toolCalls().getFirst(), "sunny"));
      assertDoesNotThrow(
          () -> port.call(request("kimi", history, names, schemas, Duration.ofSeconds(3))));
      var messages = receiver.requests.get(1).path("messages");
      assertEquals(4, messages.size());
      assertEquals("private reasoning fixture", messages.get(2).path("reasoning_content").asText());
      assertEquals("call-weather-1", messages.get(3).path("tool_call_id").asText());
      assertEquals(
          "weather",
          messages.get(2).path("tool_calls").get(0).path("function").path("name").asText());
    }
  }

  @Test
  void classifiesFailuresWithoutRetriesFallbackOrRawResponseLeakage() throws Exception {
    try (var receiver = new Receiver()) {
      var port =
          registry(
              definition("good", receiver.origin(), "/chat/completions", "{}"),
              Map.of("GOOD_KEY", SECRET));
      int count = 0;
      for (var test :
          Map.of(
                  401,
                  "PROVIDER_AUTHENTICATION",
                  429,
                  "PROVIDER_RATE_LIMIT",
                  503,
                  "PROVIDER_UNAVAILABLE")
              .entrySet()) {
        receiver.status = test.getKey();
        receiver.response = "{\"error\":\"" + SECRET + " https://private.invalid/webhook\"}";
        assertCode(test.getValue(), () -> port.call(request("good", "hello")));
        assertEquals(++count, receiver.requests.size());
      }
      receiver.status = 200;
      for (String invalid :
          List.of("not json", "{}", "{\"model\":\"x\",\"choices\":[]}", ordinary("x", ""))) {
        receiver.response = invalid;
        assertCode("PROVIDER_PROTOCOL", () -> port.call(request("good", "hello")));
        assertEquals(++count, receiver.requests.size());
      }
      assertFalse(audit.toString().contains(SECRET));
      assertFalse(audit.toString().contains("private.invalid"));
    }
    var port =
        registry(
            definition("offline", "http://127.0.0.1:1", "/chat/completions", "{}"),
            Map.of("OFFLINE_KEY", SECRET));
    assertCode("PROVIDER_CONNECTION", () -> port.call(request("offline", "hello")));
  }

  @Test
  void budgetsCoverHeadersAndBodyAndExpiredRequestsDoNotStart() throws Exception {
    try (var receiver = new Receiver()) {
      var port =
          registry(
              definition("good", receiver.origin(), "/chat/completions", "{}"),
              Map.of("GOOD_KEY", SECRET));
      // Warm the library before testing network budgets (cold start is also charged to the budget).
      port.call(request("good", "warmup"));
      receiver.requests.clear();
      assertCode(
          "PROVIDER_TIMEOUT",
          () ->
              port.call(
                  request(
                      "good",
                      List.of(ChatMessage.user("hello")),
                      ToolNameMap.empty(),
                      List.of(),
                      Duration.ZERO)));
      assertTrue(receiver.requests.isEmpty());
      receiver.delayMillis = 1000;
      for (boolean body : List.of(false, true)) {
        receiver.delayBody = body;
        long start = System.nanoTime();
        assertCode(
            "PROVIDER_TIMEOUT",
            () ->
                port.call(
                    request(
                        "good",
                        List.of(ChatMessage.user("hello")),
                        ToolNameMap.empty(),
                        List.of(),
                        Duration.ofMillis(150))));
        assertTrue(
            Duration.ofNanos(System.nanoTime() - start).toMillis() < 800,
            "deadline must cover slow response body as well as headers");
      }
      assertEquals(2, receiver.requests.size());
    }
  }

  @Test
  void rejectsRedirectsDisconnectRetriesAndForeignContinuation() throws Exception {
    try (var receiver = new Receiver();
        var other = new Receiver()) {
      var port =
          registry(
              definition("good", receiver.origin(), "/chat/completions", "{}"),
              Map.of("GOOD_KEY", SECRET));
      receiver.status = 307;
      receiver.server.removeContext("/");
      receiver.server.createContext(
          "/",
          e -> {
            receiver.paths.add(e.getRequestURI().toString());
            e.getRequestBody().readAllBytes();
            e.getResponseHeaders().set("Location", other.origin() + "/steal");
            e.sendResponseHeaders(307, -1);
            e.close();
          });
      assertCode("PROVIDER_REQUEST", () -> port.call(request("good", "hello")));
      assertEquals(1, receiver.paths.size());
      assertTrue(other.requests.isEmpty());
      var opaque =
          new ChatMessage(
              ChatMessage.Role.ASSISTANT,
              "prior",
              List.of(),
              null,
              null,
              new ChatMessage.ProviderOpaque("another", "configured-model", "private"));
      assertCode(
          "SESSION_MODEL_CHANGED",
          () ->
              port.call(
                  request(
                      "good",
                      List.of(ChatMessage.user("hello"), opaque),
                      ToolNameMap.empty(),
                      List.of(),
                      Duration.ofSeconds(3))));
      assertEquals(1, receiver.paths.size());
    }
    try (var receiver = new Receiver()) {
      receiver.disconnect = true;
      var port =
          registry(
              definition("good", receiver.origin(), "/chat/completions", "{}"),
              Map.of("GOOD_KEY", SECRET));
      assertCode("PROVIDER_CONNECTION", () -> port.call(request("good", "hello")));
      assertEquals(1, receiver.requests.size());
    }
  }

  @Test
  void mapsMcpNamesAndDetectsCollisionsAndUnknownWireNames() throws Exception {
    var names =
        ToolWireNameMapper.snapshot(
            List.of(
                new ToolWireNameMapper.Name("read_file", null, null),
                new ToolWireNameMapper.Name("mcp.one.search", "one", "search"),
                new ToolWireNameMapper.Name("mcp.two.search", "two", "search")));
    assertEquals("read_file", names.internal("read_file").wireName());
    String wire = names.internal("mcp.one.search").wireName();
    assertTrue(wire.matches("t_[a-f0-9]{48}"));
    assertNotEquals(wire, names.internal("mcp.two.search").wireName());
    assertEquals("search", names.wire(wire).remoteName());
    assertCode(
        "CONFIGURATION_ERROR",
        () ->
            ToolWireNameMapper.snapshot(
                List.of(
                    new ToolWireNameMapper.Name("mcp.one.search", "one", "search"),
                    new ToolWireNameMapper.Name(wire, null, null))));
    assertCode("UNKNOWN_TOOL", () -> names.wire("unregistered"));
    try (var receiver = new Receiver()) {
      receiver.response =
          new String(
                  getClass().getResourceAsStream("/responses/tool-reasoning.json").readAllBytes(),
                  StandardCharsets.UTF_8)
              .replace("\"name\": \"weather\"", "\"name\": \"" + wire + "\"");
      var port =
          registry(
              definition("good", receiver.origin(), "/chat/completions", "{}"),
              Map.of("GOOD_KEY", SECRET));
      var schemas =
          List.of(
              new ProviderRequest.ToolSchema("mcp.one.search", "search", "{\"type\":\"object\"}"));
      var first =
          port.call(
              request(
                  "good",
                  List.of(ChatMessage.user("search")),
                  names,
                  schemas,
                  Duration.ofSeconds(3)));
      assertEquals("mcp.one.search", first.toolCalls().getFirst().name());
      assertEquals(
          wire,
          receiver.requests.getFirst().path("tools").get(0).path("function").path("name").asText());
      receiver.response = ordinary("actual", "done");
      port.call(
          request(
              "good",
              List.of(
                  ChatMessage.user("search"),
                  first.assistantMessage(),
                  ChatMessage.tool(first.toolCalls().getFirst(), "result")),
              names,
              schemas,
              Duration.ofSeconds(3)));
      assertEquals(
          wire,
          receiver
              .requests
              .get(1)
              .path("messages")
              .get(2)
              .path("tool_calls")
              .get(0)
              .path("function")
              .path("name")
              .asText());
      assertCode(
          "PROVIDER_PROTOCOL",
          () ->
              port.call(
                  request(
                      "good",
                      List.of(ChatMessage.user("search"), first.assistantMessage()),
                      names,
                      schemas,
                      Duration.ofSeconds(3))));
      assertEquals(2, receiver.requests.size());
      receiver.response =
          new String(
              getClass().getResourceAsStream("/responses/tool-reasoning.json").readAllBytes(),
              StandardCharsets.UTF_8);
      var unknown =
          port.call(
              request(
                  "good",
                  List.of(ChatMessage.user("search")),
                  names,
                  schemas,
                  Duration.ofSeconds(3)));
      assertEquals("UNKNOWN_TOOL", unknown.toolCalls().getFirst().nameErrorCode());
      assertNull(unknown.toolCalls().getFirst().name());
      assertEquals("weather", unknown.toolCalls().getFirst().wireName());
      receiver.response = ordinary("actual", "recovered");
      port.call(
          request(
              "good",
              List.of(
                  ChatMessage.user("search"),
                  unknown.assistantMessage(),
                  ChatMessage.tool(unknown.toolCalls().getFirst(), "UNKNOWN_TOOL")),
              names,
              schemas,
              Duration.ofSeconds(3)));
      assertEquals(
          "UNKNOWN_TOOL",
          receiver.requests.get(3).path("messages").get(3).path("content").asText());
    }
  }

  @Test
  void recordingFailuresNeverBecomeSuccessOrTriggerReplay() throws Exception {
    try (var receiver = new Receiver()) {
      String definitions = definition("good", receiver.origin(), "/chat/completions", "{}");
      registry(definitions, Map.of("GOOD_KEY", SECRET));
      var workspace = new WorkspaceHandle(root);
      var env = Map.of("GOOD_KEY", SECRET);
      var config = new RuntimeConfigLoader(env, redactor).load(workspace);
      var before =
          new ProviderRegistry(
              config,
              workspace,
              env,
              redactor,
              r -> {
                throw new IllegalStateException("sink unavailable");
              });
      assertCode("RECORDING_UNAVAILABLE", () -> before.call(request("good", "hello")));
      assertTrue(receiver.requests.isEmpty());
      var after =
          new ProviderRegistry(
              config,
              workspace,
              env,
              redactor,
              r -> {
                if (!r.status().equals("STARTED"))
                  throw new IllegalStateException("sink unavailable");
              });
      assertCode("PARTIAL_RECORDING_FAILURE", () -> after.call(request("good", "hello")));
      assertEquals(1, receiver.requests.size());
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("kernel-provider-timing")
  void concurrentWarmupPrewarmsIdleRouteConnectionsForBurstReuse() throws Exception {
    var acceptedConnections = new java.util.concurrent.atomic.AtomicInteger();
    var responseDelayMillis = new java.util.concurrent.atomic.AtomicLong();
    var openSockets = new java.util.concurrent.ConcurrentLinkedQueue<java.net.Socket>();
    byte[] body = ordinary("actual", "recovered").getBytes(StandardCharsets.UTF_8);
    try (var serverThreads = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        var callerThreads = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        var server =
            new java.net.ServerSocket(0, 256, java.net.InetAddress.getLoopbackAddress())) {
      serverThreads.execute(
          () -> {
            while (!server.isClosed()) {
              try {
                var socket = server.accept();
                openSockets.add(socket);
                acceptedConnections.incrementAndGet();
                serverThreads.execute(
                    () -> {
                      try (socket) {
                        var in = new java.io.BufferedInputStream(socket.getInputStream());
                        var out = socket.getOutputStream();
                        while (true) {
                          int contentLength = readHttpRequestHeadersLength(in);
                          if (contentLength < 0) break;
                          if (contentLength > 0) {
                            byte[] req = in.readNBytes(contentLength);
                            if (req.length < contentLength) break;
                          }
                          long delay = responseDelayMillis.get();
                          if (delay > 0) Thread.sleep(delay);
                          byte[] header =
                              ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                                      + body.length
                                      + "\r\nConnection: keep-alive\r\n\r\n")
                                  .getBytes(StandardCharsets.US_ASCII);
                          out.write(header);
                          out.write(body);
                          out.flush();
                        }
                      } catch (Exception ignored) {
                      }
                    });
              } catch (Exception ignored) {
                break;
              }
            }
          });

      try {
        String origin = "http://127.0.0.1:" + server.getLocalPort();
        var port =
            registry(
                definition("good", origin, "/chat/completions", "{}"),
                Map.of("GOOD_KEY", SECRET));

        // Sequential single requests should stay lazy and avoid fanning out 96 sockets.
        assertEquals("recovered", port.call(request("good", "seq-1")).assistantMessage().content());
        Thread.sleep(150);
        assertTrue(
            acceptedConnections.get() <= 4,
            () -> "Single sequential request should not prewarm burst pool: "
                + acceptedConnections.get());

        // Concurrent requests (inFlight >= 4) trigger asynchronous route pre-warming.
        responseDelayMillis.set(50);
        var warmupCalls = new java.util.ArrayList<java.util.concurrent.CompletableFuture<String>>();
        for (int i = 0; i < 8; i++) {
          int idx = i;
          warmupCalls.add(
              java.util.concurrent.CompletableFuture.supplyAsync(
                  () ->
                      port.call(request("good", "warm-" + idx)).assistantMessage().content(),
                  callerThreads));
        }
        for (var call : warmupCalls) {
          assertEquals("recovered", call.get(5, java.util.concurrent.TimeUnit.SECONDS));
        }
        responseDelayMillis.set(0);

        long prewarmDeadline =
            System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < prewarmDeadline && acceptedConnections.get() < 64) {
          Thread.sleep(25);
        }
        assertTrue(
            acceptedConnections.get() >= 64,
            () -> "Expected concurrent warmup to prewarm >= 64 idle route connections, got "
                + acceptedConnections.get());

        int beforeBurst = acceptedConnections.get();
        var burstCalls = new java.util.ArrayList<java.util.concurrent.CompletableFuture<String>>();
        for (int i = 0; i < 64; i++) {
          int idx = i;
          burstCalls.add(
              java.util.concurrent.CompletableFuture.supplyAsync(
                  () ->
                      port.call(request("good", "burst-" + idx)).assistantMessage().content(),
                  callerThreads));
        }
        for (var call : burstCalls) {
          assertEquals("recovered", call.get(5, java.util.concurrent.TimeUnit.SECONDS));
        }
        int deltaAccepted = acceptedConnections.get() - beforeBurst;
        assertTrue(
            deltaAccepted <= 8,
            () -> "64-way burst should reuse prewarmed route connections, new accepts="
                + deltaAccepted);
      } finally {
        try {
          server.close();
        } catch (Exception ignored) {
        }
        for (var socket : openSockets) {
          try {
            socket.close();
          } catch (Exception ignored) {
          }
        }
      }
    }
  }

  private static int readHttpRequestHeadersLength(java.io.InputStream in) throws Exception {
    var headers = new java.io.ByteArrayOutputStream();
    int state = 0;
    while (true) {
      int b = in.read();
      if (b < 0) return -1;
      headers.write(b);
      if (b == '\r') {
        state = (state == 2) ? 3 : 1;
      } else if (b == '\n') {
        if (state == 1) state = 2;
        else if (state == 3) break;
        else state = 0;
      } else {
        state = 0;
      }
    }
    String raw = headers.toString(StandardCharsets.US_ASCII);
    int contentLength = 0;
    for (String line : raw.split("\r\n")) {
      int colon = line.indexOf(':');
      if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
        contentLength = Integer.parseInt(line.substring(colon + 1).trim());
      }
    }
    return contentLength;
  }

  static void assertCode(String code, org.junit.jupiter.api.function.Executable executable) {
    var error = assertThrows(OryxError.class, executable);
    assertEquals(code, error.code());
    assertFalse(error.toString().contains(SECRET));
    assertNull(error.getCause());
  }
}
