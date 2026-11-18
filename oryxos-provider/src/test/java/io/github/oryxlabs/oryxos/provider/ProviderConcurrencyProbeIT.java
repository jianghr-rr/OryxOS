package io.github.oryxlabs.oryxos.provider;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.oryxlabs.oryxos.core.audit.ModelCallRecord;
import io.github.oryxlabs.oryxos.core.config.RuntimeConfigLoader;
import io.github.oryxlabs.oryxos.core.runtime.RuntimeTiming;
import io.github.oryxlabs.oryxos.core.security.Redactor;
import io.github.oryxlabs.oryxos.core.workspace.WorkspaceHandle;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Explicit diagnostic of the real Provider port; no SQLite, never T101 acceptance. */
@Tag("kernel-provider-probe")
class ProviderConcurrencyProbeIT {
  @TempDir Path root;
  private static final ObjectMapper JSON = new ObjectMapper();

  private record Wait(long start, long end) {}

  private record Sample(
      String token,
      long totalNanos,
      long externalNanos,
      long internalNanos,
      List<RuntimeTiming.Segment> segments) {}

  @Test
  void hundredProviderCallersKeepSerializationTransportAndResumptionCosts() throws Exception {
    var waits = new ConcurrentHashMap<String, Wait>();
    var active = new AtomicInteger();
    var peak = new AtomicInteger();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 256);
    var samples = new ArrayList<Sample>();
    var audit = new ConcurrentLinkedQueue<ModelCallRecord>();
    try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      server.setExecutor(workers);
      server.createContext(
          "/chat/completions",
          exchange -> {
            int now = active.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            try (exchange) {
              var messages = JSON.readTree(exchange.getRequestBody()).path("messages");
              String token = messages.get(messages.size() - 1).path("content").asText();
              long start = System.nanoTime();
              try {
                Thread.sleep(150);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException(e);
              }
              waits.put(token, new Wait(start, System.nanoTime()));
              byte[] reply =
                  ProviderContractTest.ordinary("served-model", token)
                      .getBytes(StandardCharsets.UTF_8);
              exchange.getResponseHeaders().set("Content-Type", "application/json");
              exchange.sendResponseHeaders(200, reply.length);
              exchange.getResponseBody().write(reply);
            } finally {
              active.decrementAndGet();
            }
          });
      server.start();
      var workspace = new WorkspaceHandle(root);
      String origin = "http://127.0.0.1:" + server.getAddress().getPort();
      Files.writeString(
          root.resolve("runtime.yaml"),
          "schema_version: 1\nproviders:\n"
              + ProviderContractTest.definition("probe", origin, "/chat/completions", "{}"));
      var env = Map.of("PROBE_KEY", "local-controlled-provider-probe");
      var redactor = new Redactor();
      var config = new RuntimeConfigLoader(env, redactor).load(workspace);
      try (var provider = new ProviderRegistry(config, workspace, env, redactor, audit::add)) {
        for (int i = 0; i < 3; i++)
          provider.call(ProviderContractTest.request("probe", "warm-" + i));
        for (int round = 0; round < 3; round++) {
          var release = new CountDownLatch(1);
          var pending = new ArrayList<Future<Sample>>();
          for (int caller = 0; caller < 100; caller++) {
            String token = "probe-" + round + "-" + caller;
            pending.add(
                workers.submit(
                    () -> {
                      release.await();
                      var spans = new ConcurrentLinkedQueue<RuntimeTiming.Segment>();
                      long start = System.nanoTime();
                      try (var timing = RuntimeTiming.bind(spans::add)) {
                        assertEquals(
                            token,
                            provider
                                .call(ProviderContractTest.request("probe", token))
                                .assistantMessage()
                                .content());
                      }
                      long total = System.nanoTime() - start;
                      var wait = waits.get(token);
                      long external =
                          Math.min(wait.end() - wait.start(), TimeUnit.MILLISECONDS.toNanos(150));
                      return new Sample(
                          token, total, external, total - external, List.copyOf(spans));
                    }));
          }
          release.countDown();
          for (var call : pending) samples.add(call.get(10, TimeUnit.SECONDS));
        }
      } finally {
        server.stop(0);
      }
    }
    long max = samples.stream().mapToLong(Sample::internalNanos).max().orElseThrow();
    var file =
        Path.of(
            System.getProperty("oryxos.test.evidenceDir"),
            "provider-probe-" + System.getProperty("oryxos.test.runId") + ".json");
    JSON.writerWithDefaultPrettyPrinter()
        .writeValue(
            file.toFile(),
            Map.of(
                "diagnosticOnly",
                true,
                "includesSqlite",
                false,
                "callers",
                100,
                "samples",
                samples,
                "maxMillis",
                max / 1_000_000.0,
                "peakExternalCalls",
                peak.get(),
                "auditRecords",
                audit.size(),
                "measurement",
                "Public Provider wall time minus bounded external sleep only; retain serialization,"
                    + " network, audit projection, queue and resumption"));
    assertEquals(300, samples.size());
    assertEquals(606, audit.size());
    assertEquals(303, waits.size());
    assertTrue(
        max <= 50_000_000L,
        () -> "Provider probe exceeds 50ms: " + max / 1_000_000.0 + "; " + file);
  }
}
