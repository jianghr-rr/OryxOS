package io.github.oryxlabs.oryxos.provider;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
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
class ProviderToolSchemaTest extends ProviderTestSupport {
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
          assertDoesNotThrow(() ->
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
  void mapsMcpNamesAndDetectsCollisionsAndUnknownWireNames() throws Exception {
    var names =
        new ToolNameMap(
            List.of(
                entry("read_file", null, null),
                entry("mcp.one.search", "one", "search"),
                entry("mcp.two.search", "two", "search")));
    assertEquals("read_file", names.internal("read_file").wireName());
    String wire = names.internal("mcp.one.search").wireName();
    assertTrue(wire.matches("t_[a-f0-9]{48}"));
    assertNotEquals(wire, names.internal("mcp.two.search").wireName());
    assertEquals("search", names.wire(wire).remoteName());
    assertCode(
        "CONFIGURATION_ERROR",
        () ->
            new ToolNameMap(
                List.of(entry("mcp.one.search", "one", "search"), entry(wire, null, null))));
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

  private static ToolNameMap.Entry entry(String internal, String server, String remote) {
    return new ToolNameMap.Entry(internal, ToolNameMap.wireName(internal), server, remote);
  }
}
