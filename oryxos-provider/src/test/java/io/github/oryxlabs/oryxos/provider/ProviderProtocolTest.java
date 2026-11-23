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
class ProviderProtocolTest extends ProviderTestSupport {
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
}
