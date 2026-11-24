package io.github.oryxlabs.oryxos.boot;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.oryxlabs.oryxos.boot.config.AgentRuntimeConfiguration;
import io.github.oryxlabs.oryxos.boot.runtime.BootRuntimeLauncher;
import io.github.oryxlabs.oryxos.boot.support.EarlyRuntimeConfiguration;
import io.github.oryxlabs.oryxos.core.agent.AgentCatalog;
import io.github.oryxlabs.oryxos.core.config.*;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.execution.*;
import io.github.oryxlabs.oryxos.core.model.*;
import io.github.oryxlabs.oryxos.core.provider.*;
import io.github.oryxlabs.oryxos.core.security.Redactor;
import io.github.oryxlabs.oryxos.core.workspace.WorkspaceHandle;
import io.github.oryxlabs.oryxos.tool.http.HttpTargetPolicy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;

/**
 * Shared real tool round-trip acceptance. Real models only; a loopback server supplies known tool
 * data, never model answers. One run, no automatic rerun.
 */
final class RealToolLoopAcceptance {
  static final String URL = "http://127.0.0.1:18473/weather";
  private static final Map<String, String> HOSTS =
      Map.of("deepseek", "api.deepseek.com", "kimi", "api.moonshot.cn");
  private WorkspaceHandle workspace;
  private AgentCatalog catalog;
  private Redactor redactor;

  void prepare(List<String> providers) {
    assertEquals("real", System.getProperty("oryxos.tests.mode"), "Explicit real mode required");
    String location = System.getProperty("oryxos.real.workspace");
    assertTrue(
        location != null && !location.isBlank(),
        "Missing -Doryxos.real.workspace=/absolute/private/workspace; see tools/dev/examples/real-provider-tools or real-provider-kimi");
    assertTrue(Path.of(location).isAbsolute(), "Workspace must be absolute");
    workspace = new WorkspaceHandle(Path.of(location));
    assertTrue(
        Files.isRegularFile(workspace.root().resolve("runtime.yaml")), "Missing runtime.yaml");
    redactor = new Redactor();
    var config = new RuntimeConfigLoader(System.getenv(), redactor).load(workspace);
    catalog = AgentCatalog.load(workspace, config, System.getenv(), redactor);
    for (String name : providers) {
      var provider = config.providers().get(name);
      assertNotNull(provider, "Missing provider " + name);
      assertTrue(provider.available(), "Missing credentials/configuration for " + name);
      assertEquals("https", provider.endpoint().getScheme());
      assertEquals(
          HOSTS.get(name),
          provider.endpoint().getHost(),
          "A fake model endpoint cannot satisfy real acceptance");
      var agent = catalog.require("real-tools-" + name);
      assertEquals(name, agent.providerName());
      assertFalse(
          agent.model().startsWith("REPLACE_"), "Configure account-enabled tool/reasoning models");
      assertEquals(List.of("http_get"), agent.tools());
    }
    HttpTargetPolicy.from(SafeYaml.map(config.toolRules().get("http"), "tools.http"))
        .authorize(URL);
  }

  void run(String provider) throws Exception {
    var hits = new AtomicInteger();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 18473), 0);
    server.createContext(
        "/weather",
        exchange -> {
          hits.incrementAndGet();
          byte[] bytes =
              "{\"city\":\"Beijing\",\"condition\":\"sunny\",\"temperatureC\":23}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    var evidence = new LinkedHashMap<String, Object>();
    evidence.put("kind", "REAL_MODEL_WITH_LOOPBACK_TOOL_DATA");
    evidence.put("provider", provider);
    evidence.put(
        "requestedModel", redactor.redact(catalog.require("real-tools-" + provider).model()));
    evidence.put("time", Instant.now().toString());
    evidence.put("status", "FAILED");
    evidence.put("springAi", "1.1.8");
    evidence.put("java", System.getProperty("java.version"));
    long started = System.nanoTime();
    try (var context =
        new BootRuntimeLauncher(
                Map.of("spring.profiles.active", "us2-real"),
                EarlyRuntimeConfiguration.class,
                CaptureConfiguration.class)
            .start(workspace, Map.of("spring.main.web-application-type", "none"))) {
      var capture = context.getBean(CapturedProvider.class);
      try {
        var result =
            context
                .getBean(AgentRuntimeConfiguration.RuntimeAgents.class)
                .require()
                .process(
                    ProcessRequest.manual(
                        "cli",
                        "us2-real",
                        "real-tools-" + provider,
                        null,
                        "Use http_get exactly once on "
                            + URL
                            + ". Report the returned city, weather and temperature. Do not invent the weather or make other tool calls."));
        evidence.put("executionId", result.id().toString());
        evidence.put("executionStatus", result.status());
        evidence.put("modelCalls", capture.requests.size());
        evidence.put(
            "actualModels",
            capture.replies.stream().map(ProviderReply::actualModel).distinct().toList());
        evidence.put("inputTokens", sum(capture.replies, ProviderReply.Usage::inputTokens));
        evidence.put("outputTokens", sum(capture.replies, ProviderReply.Usage::outputTokens));
        var proposed = capture.replies.stream().flatMap(r -> r.toolCalls().stream()).toList();
        evidence.put(
            "calls",
            proposed.stream().map(c -> Map.of("id", c.id(), "wireName", c.wireName())).toList());
        require(
            evidence,
            "EXECUTION_FAILED",
            result.status() == Execution.Status.SUCCEEDED && capture.requests.size() >= 2);
        require(evidence, "NO_TOOL_CALL", !proposed.isEmpty());
        require(evidence, "MULTIPLE_TOOL_CALLS", proposed.size() == 1);
        assertTrue(capture.replies.stream().allMatch(r -> provider.equals(r.actualProvider())));
        var call = proposed.getFirst();
        assertEquals("http_get", call.name());
        assertEquals("http_get", call.wireName());
        long succeeded =
            EarlyRuntimeConfiguration.lastAudit.tools.stream()
                .filter(t -> t.status().equals("SUCCESS"))
                .count();
        require(evidence, "TOOL_NOT_EXECUTED_ONCE", hits.get() == 1 && succeeded == 1);
        require(
            evidence,
            "PAIRING_MISSING",
            capture.requests.stream()
                .skip(1)
                .anyMatch(r ->
                    r.orderedMessages().stream()
                        .anyMatch(m ->
                            m.role() == ChatMessage.Role.TOOL
                                && call.id().equals(m.toolCallId())
                                && m.content().contains("Beijing")
                                && m.content().contains("23"))));
        var opaque =
            capture.replies.stream()
                .filter(r -> !r.toolCalls().isEmpty())
                .map(r -> r.assistantMessage().providerOpaque())
                .filter(Objects::nonNull)
                .findFirst();
        evidence.put("reasoningObserved", opaque.isPresent());
        boolean replayed =
            opaque.isPresent()
                && capture.requests.stream()
                    .skip(1)
                    .flatMap(r -> r.orderedMessages().stream())
                    .anyMatch(m -> opaque.get().equals(m.providerOpaque()));
        evidence.put("reasoningReplayed", replayed);
        require(evidence, "REASONING_NOT_REPLAYED", replayed);
        String reply = Objects.requireNonNullElse(result.finalContent(), "");
        boolean facts = (reply.contains("Beijing") || reply.contains("北京")) && reply.contains("23");
        evidence.put("finalReplyHasFacts", facts);
        require(evidence, "FINAL_REPLY_MISSING_FACT", facts);
        evidence.put("status", "SUCCEEDED");
      } catch (OryxError e) {
        evidence.putIfAbsent("failureReason", "PROVIDER_ERROR");
        evidence.put("errorCode", e.code());
        throw e;
      } finally {
        evidence.put("modelCalls", capture.requests.size());
      }
    } finally {
      server.stop(0);
      evidence.put("durationMs", Duration.ofNanos(System.nanoTime() - started).toMillis());
      evidence.put("toolHttpRequests", hits.get());
      Path target =
          Path.of(System.getProperty("oryxos.test.evidenceDir"))
              .resolve("us2-real")
              .resolve(System.getProperty("oryxos.test.runId"));
      Files.createDirectories(target);
      new ObjectMapper()
          .writerWithDefaultPrettyPrinter()
          .writeValue(target.resolve(provider + ".json").toFile(), evidence);
    }
  }

  private static void require(Map<String, Object> evidence, String reason, boolean condition) {
    if (!condition) {
      evidence.put("failureReason", reason);
      fail("Real tool round-trip failed: " + reason);
    }
  }

  private static Integer sum(
      List<ProviderReply> replies,
      java.util.function.Function<ProviderReply.Usage, Integer> field) {
    int total = 0;
    for (var reply : replies) {
      if (reply.usage() == null || field.apply(reply.usage()) == null) return null;
      total += field.apply(reply.usage());
    }
    return total;
  }

  @TestConfiguration(proxyBeanMethods = false)
  @Profile("us2-real")
  static class CaptureConfiguration {
    @Bean
    @Primary
    CapturedProvider capturedProvider(@Qualifier("providerPort") ProviderPort actual) {
      return new CapturedProvider(actual);
    }
  }

  static final class CapturedProvider implements ProviderPort {
    final ProviderPort actual;
    final List<ProviderReply> replies = new ArrayList<>();
    final List<ProviderRequest> requests = new ArrayList<>();

    CapturedProvider(ProviderPort actual) {
      this.actual = actual;
    }

    public ProviderReply call(ProviderRequest request) {
      requests.add(request);
      var reply = actual.call(request);
      replies.add(reply);
      return reply;
    }
  }
}
