package io.github.oryxlabs.oryxos.provider;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import io.github.oryxlabs.oryxos.core.audit.*;
import io.github.oryxlabs.oryxos.core.config.*;
import io.github.oryxlabs.oryxos.core.model.*;
import io.github.oryxlabs.oryxos.core.provider.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

@Tag("us1-contract")
class ProviderTransportTest extends ProviderTestSupport {
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
            byte[] reply = ordinary("served-model", "inline-ok").getBytes(StandardCharsets.UTF_8);
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
              workers.submit(() -> {
                barrier.await();
                long callerThread = Thread.currentThread().threadId();
                try (var timing =
                    io.github.oryxlabs.oryxos.core.runtime.RuntimeTiming.bind(s -> {
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
          contentLengths.stream()
              .allMatch(v -> v != null && !v.equals("null") && Integer.parseInt(v) > 0),
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
            workers.submit(() -> {
              try (var timing =
                  io.github.oryxlabs.oryxos.core.runtime.RuntimeTiming.bind(s -> {
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
          workers.submit(() ->
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
          workers.submit(() ->
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
  @org.junit.jupiter.api.Tag("kernel-provider-timing")
  void concurrentWarmupPrewarmsIdleRouteConnectionsForBurstReuse() throws Exception {
    var acceptedConnections = new java.util.concurrent.atomic.AtomicInteger();
    var responseDelayMillis = new java.util.concurrent.atomic.AtomicLong();
    var openSockets = new java.util.concurrent.ConcurrentLinkedQueue<java.net.Socket>();
    byte[] body = ordinary("actual", "recovered").getBytes(StandardCharsets.UTF_8);
    try (var serverThreads = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        var callerThreads = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        var server = new java.net.ServerSocket(0, 256, java.net.InetAddress.getLoopbackAddress())) {
      serverThreads.execute(() -> {
        while (!server.isClosed()) {
          try {
            var socket = server.accept();
            openSockets.add(socket);
            acceptedConnections.incrementAndGet();
            serverThreads.execute(() -> {
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
                definition("good", origin, "/chat/completions", "{}"), Map.of("GOOD_KEY", SECRET));

        // Sequential single requests should stay lazy and avoid fanning out 96 sockets.
        assertEquals("recovered", port.call(request("good", "seq-1")).assistantMessage().content());
        Thread.sleep(150);
        assertTrue(
            acceptedConnections.get() <= 4,
            () ->
                "Single sequential request should not prewarm burst pool: "
                    + acceptedConnections.get());

        // Concurrent requests (inFlight >= 4) trigger asynchronous route pre-warming.
        responseDelayMillis.set(50);
        var warmupCalls = new java.util.ArrayList<java.util.concurrent.CompletableFuture<String>>();
        for (int i = 0; i < 8; i++) {
          int idx = i;
          warmupCalls.add(
              java.util.concurrent.CompletableFuture.supplyAsync(
                  () -> port.call(request("good", "warm-" + idx)).assistantMessage().content(),
                  callerThreads));
        }
        for (var call : warmupCalls) {
          assertEquals("recovered", call.get(5, java.util.concurrent.TimeUnit.SECONDS));
        }
        responseDelayMillis.set(0);

        long prewarmDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < prewarmDeadline && acceptedConnections.get() < 64) {
          Thread.sleep(25);
        }
        assertTrue(
            acceptedConnections.get() >= 64,
            () ->
                "Expected concurrent warmup to prewarm >= 64 idle route connections, got "
                    + acceptedConnections.get());

        int beforeBurst = acceptedConnections.get();
        var burstCalls = new java.util.ArrayList<java.util.concurrent.CompletableFuture<String>>();
        for (int i = 0; i < 64; i++) {
          int idx = i;
          burstCalls.add(
              java.util.concurrent.CompletableFuture.supplyAsync(
                  () -> port.call(request("good", "burst-" + idx)).assistantMessage().content(),
                  callerThreads));
        }
        for (var call : burstCalls) {
          assertEquals("recovered", call.get(5, java.util.concurrent.TimeUnit.SECONDS));
        }
        int deltaAccepted = acceptedConnections.get() - beforeBurst;
        assertTrue(
            deltaAccepted <= 8,
            () ->
                "64-way burst should reuse prewarmed route connections, new accepts="
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
}
