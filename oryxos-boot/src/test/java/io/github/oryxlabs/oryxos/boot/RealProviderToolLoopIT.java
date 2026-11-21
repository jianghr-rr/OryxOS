package io.github.oryxlabs.oryxos.boot;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.oryxlabs.oryxos.boot.config.AgentRuntimeConfiguration;
import io.github.oryxlabs.oryxos.boot.runtime.BootRuntimeLauncher;
import io.github.oryxlabs.oryxos.boot.support.EarlyRuntimeConfiguration;
import io.github.oryxlabs.oryxos.core.agent.AgentCatalog;
import io.github.oryxlabs.oryxos.core.config.*;
import io.github.oryxlabs.oryxos.core.execution.*;
import io.github.oryxlabs.oryxos.core.model.*;
import io.github.oryxlabs.oryxos.core.provider.*;
import io.github.oryxlabs.oryxos.core.security.Redactor;
import io.github.oryxlabs.oryxos.core.workspace.WorkspaceHandle;
import io.github.oryxlabs.oryxos.tool.http.HttpTargetPolicy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;

/** Real models only. A loopback server supplies known tool data, never model answers. */
@Tag("real-integration")
@Tag("real-provider-tools")
class RealProviderToolLoopIT {
  static final String URL = "http://127.0.0.1:18473/weather";
  WorkspaceHandle workspace;

  @BeforeEach
  void prerequisites() {
    assertEquals("real", System.getProperty("oryxos.tests.mode"), "Explicit real mode required");
    String location = System.getProperty("oryxos.real.workspace");
    assertTrue(
        location != null && !location.isBlank(),
        "Missing -Doryxos.real.workspace=/absolute/private/workspace; see tools/dev/examples/real-provider-tools");
    assertTrue(Path.of(location).isAbsolute(), "Workspace must be absolute");
    workspace = new WorkspaceHandle(Path.of(location));
    assertTrue(
        Files.isRegularFile(workspace.root().resolve("runtime.yaml")), "Missing runtime.yaml");
    var redactor = new Redactor();
    var config = new RuntimeConfigLoader(System.getenv(), redactor).load(workspace);
    var catalog = AgentCatalog.load(workspace, config, System.getenv(), redactor);
    for (String name : List.of("deepseek", "kimi")) {
      var provider = config.providers().get(name);
      assertNotNull(provider, "Missing provider " + name);
      assertTrue(provider.available(), "Missing credentials/configuration for " + name);
      assertEquals("https", provider.endpoint().getScheme());
      assertEquals(
          name.equals("deepseek") ? "api.deepseek.com" : "api.moonshot.cn",
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

  @Test
  void deepseekToolResultRoundTrip() throws Exception {
    run("deepseek");
  }

  @Test
  void kimiToolResultRoundTrip() throws Exception {
    run("kimi");
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
    evidence.put("time", Instant.now().toString());
    evidence.put("status", "FAILED");
    evidence.put("springAi", "1.1.8");
    evidence.put("java", System.getProperty("java.version"));
    try (var context =
        new BootRuntimeLauncher(
                Map.of("spring.profiles.active", "us2-real"),
                EarlyRuntimeConfiguration.class,
                CaptureConfiguration.class)
            .start(workspace, Map.of("spring.main.web-application-type", "none"))) {
      var capture = context.getBean(CapturedProvider.class);
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
      evidence.put("toolHttpRequests", hits.get());
      evidence.put(
          "actualModels",
          capture.replies.stream().map(ProviderReply::actualModel).distinct().toList());
      var proposed = capture.replies.stream().flatMap(r -> r.toolCalls().stream()).toList();
      evidence.put(
          "calls",
          proposed.stream().map(c -> Map.of("id", c.id(), "wireName", c.wireName())).toList());
      assertEquals(Execution.Status.SUCCEEDED, result.status());
      assertTrue(capture.requests.size() >= 2);
      assertEquals(1, hits.get());
      assertEquals(1, proposed.size());
      assertTrue(capture.replies.stream().allMatch(r -> provider.equals(r.actualProvider())));
      var call = proposed.getFirst();
      assertEquals("http_get", call.name());
      assertEquals("http_get", call.wireName());
      assertTrue(
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
      assertTrue(
          opaque.isPresent(),
          "This model did not emit reasoning_content; choose a supported reasoning-enabled configuration before marking T042 complete");
      boolean replayed =
          capture.requests.stream()
              .skip(1)
              .flatMap(r -> r.orderedMessages().stream())
              .anyMatch(m -> opaque.get().equals(m.providerOpaque()));
      evidence.put("reasoningReplayed", replayed);
      assertTrue(replayed);
      assertEquals(
          1,
          EarlyRuntimeConfiguration.lastAudit.tools.stream()
              .filter(t -> t.status().equals("SUCCESS"))
              .count());
      evidence.put("status", "SUCCEEDED");
    } finally {
      server.stop(0);
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
    final List<ProviderRequest> requests = new ArrayList<>();
    final List<ProviderReply> replies = new ArrayList<>();

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
