package io.github.oryxlabs.oryxos.tool;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.oryxlabs.oryxos.core.agent.AgentProfile;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.security.SecretResolver;
import io.github.oryxlabs.oryxos.core.tool.*;
import io.github.oryxlabs.oryxos.tool.http.*;
import io.github.oryxlabs.oryxos.tool.notify.*;
import io.github.oryxlabs.oryxos.tool.support.ToolFixture;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Tag("us4-tools")
class NotifyContractTest {
  @TempDir Path root;

  static final class Receiver implements AutoCloseable {
    final HttpServer server;
    final AtomicInteger calls = new AtomicInteger();
    volatile boolean drop, reject;
    volatile int status = 200;
    volatile String body, header;

    Receiver() throws Exception {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/",
          e -> {
            calls.incrementAndGet();
            body = new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            header = e.getRequestHeaders().getFirst("X-Private-Key");
            if (drop) {
              e.close();
              return;
            }
            byte[] bytes =
                (reject ? "{\"errcode\":1}" : "{\"errcode\":0}").getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(status, bytes.length);
            e.getResponseBody().write(bytes);
            e.close();
          });
      server.start();
    }

    String url() {
      return "http://127.0.0.1:"
          + server.getAddress().getPort()
          + "/private?key=fixture-url-secret";
    }

    ControlledHttpClient http() {
      return new ControlledHttpClient(
          new HttpTargetPolicy(
              List.of(
                  new HttpTargetPolicy.Target("http", "127.0.0.1", server.getAddress().getPort()))),
          1,
          1,
          65536);
    }

    public void close() {
      server.stop(0);
    }
  }

  @Test
  void configuredTargetEscapesContentChecksBusinessAckAndNeverExposesSecrets() throws Exception {
    try (var f = new ToolFixture(root);
        var target = new Receiver();
        var http = target.http()) {
      f.notifications =
          Map.of(
              "daily",
              new AgentProfile.Notification(
                  "${URL}",
                  Map.of(
                      "text",
                      "$CONTENT",
                      "literal",
                      "prefix $CONTENT",
                      "nested",
                      List.of("$CONTENT")),
                  Map.of("mode", "json_equals", "pointer", "/errcode", "expected", 0),
                  Map.of("X-Private-Key", "${HEADER}"),
                  false));
      var ctx = f.context("writer");
      var resolver =
          new NotificationTargetResolver(
              List.of(ctx.agent()),
              new SecretResolver(
                  Map.of("URL", target.url(), "HEADER", "fixture-private-header"),
                  root.resolve("none"),
                  ctx.redactor()));
      var tool = new NotifyTool(resolver, new WebhookNotifyAdapter(http));
      String content = "quote \" and newline\n 😀";
      var args = Map.<String, Object>of("content", content);
      tool.authorize(args, ctx);
      var result = tool.execute(args, ctx);
      assertEquals(ToolResult.Outcome.SUCCESS, result.outcome());
      var sent = new ObjectMapper().readTree(target.body);
      assertEquals(content, sent.path("text").asText());
      assertEquals("prefix $CONTENT", sent.path("literal").asText());
      assertEquals("fixture-private-header", target.header);
      assertFalse(tool.descriptor().inputSchemaJson().contains("fixture-private"));
      assertFalse(result.content().contains("fixture-url-secret"));
      target.reject = true;
      assertEquals(ToolResult.Outcome.ERROR, tool.execute(args, ctx).outcome());
      assertEquals(2, target.calls.get());
    }
  }

  @Test
  void missingOrForeignTargetsAreRejectedBeforeNetwork() throws Exception {
    try (var f = new ToolFixture(root);
        var target = new Receiver();
        var http = target.http()) {
      var n =
          new AgentProfile.Notification(
              "${URL}", Map.of("text", "$CONTENT"), Map.of("mode", "http_2xx"));
      f.notifications = Map.of("first", n, "second", n);
      var ctx = f.context("writer");
      var resolver =
          new NotificationTargetResolver(
              List.of(ctx.agent()),
              new SecretResolver(
                  Map.of("URL", target.url()), root.resolve("none"), ctx.redactor()));
      var tool = new NotifyTool(resolver, new WebhookNotifyAdapter(http));
      assertEquals(
          "INVALID_ARGUMENTS",
          assertThrows(OryxError.class, () -> tool.authorize(Map.of("content", "message"), ctx))
              .code());
      assertThrows(
          OryxError.class,
          () -> tool.authorize(Map.of("content", "message", "targetName", "other"), ctx));
      assertEquals(0, target.calls.get());
    }
  }

  @Test
  void acceptedThenDisconnectedNotifyAndPostAreUnknownWithoutRetries() throws Exception {
    try (var f = new ToolFixture(root);
        var target = new Receiver();
        var http = target.http()) {
      target.drop = true;
      f.notifications =
          Map.of(
              "daily",
              new AgentProfile.Notification(
                  "${URL}", Map.of("text", "$CONTENT"), Map.of("mode", "http_2xx")));
      var ctx = f.context("writer");
      var tool =
          new NotifyTool(
              new NotificationTargetResolver(
                  List.of(ctx.agent()),
                  new SecretResolver(
                      Map.of("URL", target.url()), root.resolve("none"), ctx.redactor())),
              new WebhookNotifyAdapter(http));
      var result = tool.execute(Map.of("content", "receipt probe"), ctx);
      assertEquals(ToolResult.Outcome.UNKNOWN, result.outcome());
      assertEquals(ToolResult.SideEffectState.UNKNOWN, result.sideEffectState());
      assertEquals(1, target.calls.get());
      result =
          new HttpPostTool(http)
              .execute(Map.of("url", target.url(), "jsonBody", Map.of("value", 1)), ctx);
      assertEquals(ToolResult.Outcome.UNKNOWN, result.outcome());
      assertEquals(2, target.calls.get());
    }
  }

  @Test
  void getRetriesAreBoundedButPostAndInjectedHeadersNeverReplay() throws Exception {
    try (var f = new ToolFixture(root);
        var target = new Receiver();
        var http = target.http()) {
      target.status = 503;
      var policy = new io.github.oryxlabs.oryxos.tool.policy.ToolRetryPolicy();
      var registry =
          new ToolRegistry(
              List.of(policy.apply(new HttpGetTool(http)), policy.apply(new HttpPostTool(http))),
              new io.github.oryxlabs.oryxos.tool.validation.ToolArgumentValidator(),
              new io.github.oryxlabs.oryxos.tool.policy.ToolPolicy());
      var audit = new ArrayList<io.github.oryxlabs.oryxos.core.audit.ToolCallRecord>();
      var sink =
          new io.github.oryxlabs.oryxos.core.audit.AuditSink() {
            public void modelCall(io.github.oryxlabs.oryxos.core.audit.ModelCallRecord record) {}

            public void toolCall(io.github.oryxlabs.oryxos.core.audit.ToolCallRecord record) {
              audit.add(record);
            }
          };
      var delays = new ArrayList<Long>();
      var executor = new ToolExecutor(registry, sink, duration -> delays.add(duration.toMillis()));
      String getArgs = new ObjectMapper().writeValueAsString(Map.of("url", target.url()));
      var get =
          executor.execute(
              f.context("caller"),
              new io.github.oryxlabs.oryxos.core.model.ToolCall(
                  "get", "http_get", "http_get", getArgs));
      assertEquals(ToolResult.Outcome.ERROR, get.result().outcome());
      assertEquals(4, target.calls.get());
      assertEquals(List.of(250L, 500L, 1000L), delays);
      assertEquals(4, audit.stream().filter(r -> r.status().equals("ERROR")).count());
      String postArgs =
          new ObjectMapper()
              .writeValueAsString(Map.of("url", target.url(), "jsonBody", Map.of("value", 1)));
      var post =
          executor.execute(
              f.context("caller"),
              new io.github.oryxlabs.oryxos.core.model.ToolCall(
                  "post", "http_post", "http_post", postArgs));
      assertEquals(ToolResult.RetryClass.NONE, post.result().retryClass());
      assertEquals(5, target.calls.get());
      String injected =
          new ObjectMapper()
              .writeValueAsString(
                  Map.of(
                      "url",
                      target.url(),
                      "jsonBody",
                      Map.of(),
                      "headers",
                      Map.of("Authorization", "unauthorized")));
      assertEquals(
          "INVALID_ARGUMENTS",
          executor
              .execute(
                  f.context("caller"),
                  new io.github.oryxlabs.oryxos.core.model.ToolCall(
                      "bad", "http_post", "http_post", injected))
              .result()
              .errorCode());
      assertEquals(5, target.calls.get());
    }
  }
}
