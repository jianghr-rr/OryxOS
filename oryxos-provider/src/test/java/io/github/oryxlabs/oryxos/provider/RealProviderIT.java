package io.github.oryxlabs.oryxos.provider;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.oryxlabs.oryxos.core.agent.AgentCatalog;
import io.github.oryxlabs.oryxos.core.audit.ModelCallRecord;
import io.github.oryxlabs.oryxos.core.config.*;
import io.github.oryxlabs.oryxos.core.model.*;
import io.github.oryxlabs.oryxos.core.provider.ProviderRequest;
import io.github.oryxlabs.oryxos.core.security.Redactor;
import io.github.oryxlabs.oryxos.core.workspace.WorkspaceHandle;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

/**
 * Explicit, credential-dependent tests. Missing prerequisites FAIL; there is no assume/skip/mock
 * path.
 */
@Tag("real-integration")
@Tag("real-provider")
class RealProviderIT {
  WorkspaceHandle workspace;
  RuntimeConfig config;
  AgentCatalog agents;
  Redactor redactor;
  final List<ModelCallRecord> audit = new ArrayList<>();

  @BeforeEach
  void prerequisites() {
    assertEquals("real", System.getProperty("oryxos.tests.mode"), "Enable real mode explicitly");
    String location = System.getProperty("oryxos.real.workspace");
    assertTrue(
        location != null && !location.isBlank(),
        "Missing -Doryxos.real.workspace=/absolute/private/workspace (see tools/dev/examples/real-provider)");
    assertTrue(Path.of(location).isAbsolute(), "Real-provider workspace must be absolute");
    workspace = new WorkspaceHandle(Path.of(location));
    assertTrue(
        Files.isRegularFile(workspace.root().resolve("runtime.yaml")),
        "Missing real workspace runtime.yaml; both provider credentials, account models and Agent definitions are required");
    redactor = new Redactor();
    config = new RuntimeConfigLoader(System.getenv(), redactor).load(workspace);
    agents = AgentCatalog.load(workspace, config, System.getenv(), redactor);
    // Preflight BOTH providers before either request; cannot silently pass with only one account.
    for (String name : List.of("deepseek", "kimi")) {
      var provider = config.providers().get(name);
      assertNotNull(provider, "Missing runtime provider: " + name);
      assertTrue(
          provider.available(),
          "Missing/invalid provider configuration or secret reference: " + name);
      assertEquals("https", provider.endpoint().getScheme(), "Real tests require HTTPS");
      assertEquals(
          name.equals("deepseek") ? "api.deepseek.com" : "api.moonshot.cn",
          provider.endpoint().getHost(),
          "Real acceptance cannot use a loopback/fake endpoint");
      var agent = agents.require("real-" + name);
      assertEquals(name, agent.providerName());
      assertFalse(
          agent.model().startsWith("REPLACE_"),
          "Configure an account-enabled model in real-" + name + "/AGENT.md");
      assertTrue(
          agent.tools().isEmpty(), "US1 real acceptance sends ordinary requests with no tools");
    }
  }

  @Test
  void deepseekOrdinaryRequest() throws Exception {
    ordinary("deepseek");
  }

  @Test
  void kimiOrdinaryRequest() throws Exception {
    ordinary("kimi");
  }

  private void ordinary(String name) throws Exception {
    var profile = agents.require("real-" + name);
    var registry = new ProviderService(config, workspace, System.getenv(), redactor, audit::add);
    UUID execution = UUID.randomUUID();
    String prompt = "Reply briefly with this exact marker: ORYXOS_US1_" + execution;
    var request =
        new ProviderRequest(
            name,
            profile.model(),
            "Answer the user's ordinary text request.",
            List.of(ChatMessage.user(prompt)),
            List.of(),
            ToolNameMap.empty(),
            Duration.ofSeconds(60),
            execution,
            1);
    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("kind", "REAL_MODEL");
    evidence.put("time", Instant.now().toString());
    evidence.put("provider", name);
    evidence.put("requestedModel", redactor.redact(profile.model()));
    evidence.put("request", prompt);
    evidence.put("executionId", execution.toString());
    evidence.put("java", System.getProperty("java.version"));
    evidence.put("springAi", "1.1.8");
    try {
      var reply = registry.call(request);
      assertEquals(name, reply.actualProvider());
      assertFalse(reply.assistantMessage().content().isBlank());
      assertTrue(reply.toolCalls().isEmpty());
      evidence.put("status", "SUCCEEDED");
      evidence.put("actualModel", redactor.redact(reply.actualModel()));
      evidence.put("response", redactor.redact(reply.assistantMessage().content()));
      evidence.put("inputTokens", reply.usage() == null ? null : reply.usage().inputTokens());
      evidence.put("outputTokens", reply.usage() == null ? null : reply.usage().outputTokens());
    } catch (io.github.oryxlabs.oryxos.core.error.OryxError e) {
      evidence.put("status", "FAILED");
      evidence.put("errorCode", e.code());
      evidence.put("safeMessage", e.getMessage());
      throw e;
    } finally {
      evidence.put(
          "auditEvents",
          audit.stream()
              .map(r ->
                  Map.of(
                      "callId",
                      r.callId().toString(),
                      "status",
                      r.status(),
                      "iteration",
                      r.iteration(),
                      "attempt",
                      r.attempt()))
              .toList());
      Path directory =
          Path.of(System.getProperty("oryxos.test.evidenceDir"))
              .resolve("us1-real")
              .resolve(System.getProperty("oryxos.test.runId"));
      Files.createDirectories(directory);
      new ObjectMapper()
          .writerWithDefaultPrettyPrinter()
          .writeValue(directory.resolve(name + ".json").toFile(), evidence);
    }
  }
}
