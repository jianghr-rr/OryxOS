package io.github.oryxlabs.oryxos.tool.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.oryxlabs.oryxos.core.tool.ToolResult;
import io.github.oryxlabs.oryxos.tool.http.ControlledHttpClient;
import io.github.oryxlabs.oryxos.tool.http.HttpTargetPolicy;
import io.github.oryxlabs.oryxos.tool.support.ToolFixture;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("us4-tools")
class WebhookNotifyAdapterTest {
  @TempDir Path root;

  private static final class LocalWebhookServer implements AutoCloseable {
    final HttpServer server;
    final AtomicInteger calls = new AtomicInteger();
    volatile int status = 200;
    volatile String responseBody = "{\"errcode\":0}";
    volatile boolean dropConnection;
    volatile String lastMethod;
    volatile String lastPath;
    volatile String lastBody;

    LocalWebhookServer() throws Exception {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/",
          exchange -> {
            calls.incrementAndGet();
            lastMethod = exchange.getRequestMethod();
            lastPath = exchange.getRequestURI().getRawPath();
            lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (dropConnection) {
              exchange.close();
              return;
            }
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
          });
      server.start();
    }

    String url(String path) {
      return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    ControlledHttpClient httpClient() {
      return new ControlledHttpClient(
          new HttpTargetPolicy(
              List.of(
                  new HttpTargetPolicy.Target("http", "127.0.0.1", server.getAddress().getPort()))),
          2,
          2,
          65536);
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }

  @Test
  @DisplayName("发送后请求发往 NotifyTarget 配置的动态 URL 且 body 包含正确编码的 content")
  void send_postsContentToTargetConfiguredUrl() throws Exception {
    try (var fixture = new ToolFixture(root);
        var server = new LocalWebhookServer();
        var http = server.httpClient()) {
      NotifyChannelAdapter adapter = new WebhookNotifyAdapter(http);
      String dynamicPath = "/hooks/team-alpha";
      NotifyTarget target = new NotifyTarget("webhook", Map.of("url", server.url(dynamicPath)));
      String content = "hello \"oryx\"\nline2 🌤️";

      adapter.authorize(target);
      ToolResult result = adapter.send(target, content, fixture.context("weather"));

      assertEquals(ToolResult.Outcome.SUCCESS, result.outcome());
      assertEquals("POST", server.lastMethod);
      assertEquals(dynamicPath, server.lastPath);
      var parsed = new ObjectMapper().readTree(server.lastBody);
      assertEquals(content, parsed.path("content").asText());
      assertEquals(1, server.calls.get());
      assertFalse(target.toString().contains(dynamicPath));
    }
  }

  @Test
  @DisplayName("webhook 返回 5xx 时明确返回失败、不静默吞掉且不重试")
  void send_returnsErrorWithoutRetryOnServer5xx() throws Exception {
    try (var fixture = new ToolFixture(root);
        var server = new LocalWebhookServer();
        var http = server.httpClient()) {
      server.status = 502;
      server.responseBody = "bad gateway";
      NotifyChannelAdapter adapter = new WebhookNotifyAdapter(http);
      NotifyTarget target = new NotifyTarget("webhook", Map.of("url", server.url("/hooks/fail")));

      ToolResult result = adapter.send(target, "alert", fixture.context("weather"));

      assertEquals(ToolResult.Outcome.ERROR, result.outcome());
      assertEquals("NOTIFY_REJECTED", result.errorCode());
      assertEquals(ToolResult.RetryClass.NONE, result.retryClass());
      assertEquals(1, server.calls.get());
    }
  }

  @Test
  @DisplayName("webhook 业务确认失败时明确返回失败且不静默成功")
  void send_returnsErrorWhenBusinessAckRejected() throws Exception {
    try (var fixture = new ToolFixture(root);
        var server = new LocalWebhookServer();
        var http = server.httpClient()) {
      server.status = 200;
      server.responseBody = "{\"errcode\":93000}";
      NotifyChannelAdapter adapter = new WebhookNotifyAdapter(http);
      NotifyTarget target =
          new NotifyTarget(
              "webhook",
              Map.of(
                  "url",
                  server.url("/hooks/ack"),
                  "payload",
                  Map.of("msgtype", "text", "text", Map.of("content", "$CONTENT")),
                  "acknowledgement",
                  Map.of("mode", "json_equals", "pointer", "/errcode", "expected", 0)));

      ToolResult result = adapter.send(target, "digest", fixture.context("digest"));

      assertEquals(ToolResult.Outcome.ERROR, result.outcome());
      assertEquals("NOTIFY_REJECTED", result.errorCode());
      assertEquals(1, server.calls.get());
    }
  }

  @Test
  @DisplayName("请求发出后连接断开返回 UNKNOWN 且不自动重试")
  void send_returnsUnknownWithoutRetryWhenConnectionDrops() throws Exception {
    try (var fixture = new ToolFixture(root);
        var server = new LocalWebhookServer();
        var http = server.httpClient()) {
      server.dropConnection = true;
      NotifyChannelAdapter adapter = new WebhookNotifyAdapter(http);
      NotifyTarget target = new NotifyTarget("webhook", Map.of("url", server.url("/hooks/drop")));

      ToolResult result = adapter.send(target, "probe", fixture.context("weather"));

      assertEquals(ToolResult.Outcome.UNKNOWN, result.outcome());
      assertEquals(ToolResult.SideEffectState.UNKNOWN, result.sideEffectState());
      assertEquals("NOTIFY_DELIVERY_UNKNOWN", result.errorCode());
      assertEquals(ToolResult.RetryClass.NONE, result.retryClass());
      assertEquals(1, server.calls.get());
    }
  }
}
