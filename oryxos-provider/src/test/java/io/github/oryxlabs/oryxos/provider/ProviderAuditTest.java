package io.github.oryxlabs.oryxos.provider;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import io.github.oryxlabs.oryxos.core.audit.*;
import io.github.oryxlabs.oryxos.core.config.*;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.model.*;
import io.github.oryxlabs.oryxos.core.provider.*;
import io.github.oryxlabs.oryxos.core.workspace.WorkspaceHandle;
import java.net.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

@Tag("us1-contract")
class ProviderAuditTest extends ProviderTestSupport {
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
  void recordingFailuresNeverBecomeSuccessOrTriggerReplay() throws Exception {
    try (var receiver = new Receiver()) {
      String definitions = definition("good", receiver.origin(), "/chat/completions", "{}");
      registry(definitions, Map.of("GOOD_KEY", SECRET));
      var workspace = new WorkspaceHandle(root);
      var env = Map.of("GOOD_KEY", SECRET);
      var config = new RuntimeConfigLoader(env, redactor).load(workspace);
      var before =
          new ProviderService(
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
          new ProviderService(
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
  void failedCallsAreAuditedWithClassifiedCodeBeforeRethrow() throws Exception {
    try (var receiver = new Receiver()) {
      var port =
          registry(
              definition("good", receiver.origin(), "/chat/completions", "{}"),
              Map.of("GOOD_KEY", SECRET));
      receiver.status = 503;
      receiver.response = "{\"error\":\"" + SECRET + " https://private.invalid/webhook\"}";
      var unavailable = assertThrows(OryxError.class, () -> port.call(request("good", "hello")));
      receiver.status = 200;
      receiver.response = ordinary("served-model", "late");
      receiver.delayMillis = 800;
      var timeout =
          assertThrows(
              OryxError.class,
              () ->
                  port.call(
                      request(
                          "good",
                          List.of(ChatMessage.user("hello")),
                          ToolNameMap.empty(),
                          List.of(),
                          Duration.ofMillis(200))));
      assertEquals("PROVIDER_UNAVAILABLE", unavailable.code());
      assertEquals("PROVIDER_TIMEOUT", timeout.code());
      assertEquals(4, audit.size(), "each failed call writes STARTED then FAILED");
      for (int i = 0; i < 4; i += 2) {
        var started = audit.get(i);
        var failed = audit.get(i + 1);
        assertEquals("STARTED", started.status());
        assertEquals("FAILED", failed.status());
        assertEquals(started.callId(), failed.callId());
        assertEquals(i == 0 ? unavailable.code() : timeout.code(), failed.errorCode());
      }
      assertFalse(audit.toString().contains(SECRET));
      assertFalse(audit.toString().contains("private.invalid"));
    }
  }
}
