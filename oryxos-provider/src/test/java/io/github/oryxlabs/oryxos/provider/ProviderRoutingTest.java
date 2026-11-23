package io.github.oryxlabs.oryxos.provider;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import io.github.oryxlabs.oryxos.core.audit.*;
import io.github.oryxlabs.oryxos.core.config.*;
import io.github.oryxlabs.oryxos.core.model.*;
import io.github.oryxlabs.oryxos.core.provider.*;
import java.net.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

@Tag("us1-contract")
class ProviderRoutingTest extends ProviderTestSupport {
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
  void invalidRequestsNeverWriteAuditOrReachNetwork() throws Exception {
    try (var good = new Receiver()) {
      var port =
          registry(
              definition("good", good.origin(), "/chat/completions", "{}"),
              Map.of("GOOD_KEY", SECRET));
      assertCode("INVALID_TASK", () -> port.call(request("good", " ")));
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
      assertCode("PROVIDER_UNAVAILABLE", () -> port.call(request("absent", "hello")));
      assertEquals(List.of(), audit, "rejected requests must not write model audit records");
      assertEquals(0, good.requests.size());
    }
  }
}
