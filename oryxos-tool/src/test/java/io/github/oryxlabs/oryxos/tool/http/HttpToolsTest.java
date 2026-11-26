package io.github.oryxlabs.oryxos.tool.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.tool.ToolResult;
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
class HttpToolsTest {
  @TempDir Path root;

  private static final class LocalHttpServer implements AutoCloseable {
    final HttpServer server;
    final AtomicInteger calls = new AtomicInteger();
    volatile int status = 200;
    volatile String body = "{\"city\":\"beijing\",\"temp\":22}";
    volatile String redirectLocation;
    volatile String lastMethod;
    volatile String lastRequestBody;

    LocalHttpServer() throws Exception {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/",
          exchange -> {
            calls.incrementAndGet();
            lastMethod = exchange.getRequestMethod();
            lastRequestBody =
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (redirectLocation != null) {
              exchange.getResponseHeaders().set("Location", redirectLocation);
            }
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
          });
      server.start();
    }

    String url(String path) {
      return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    HttpTargetPolicy.Target target() {
      return new HttpTargetPolicy.Target("http", "127.0.0.1", server.getAddress().getPort());
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }

  @Test
  @DisplayName("http_get 应能取回响应")
  void httpGet_fetchesResponseFromAllowedTarget() throws Exception {
    try (var fixture = new ToolFixture(root);
        var server = new LocalHttpServer();
        var http =
            new ControlledHttpClient(new HttpTargetPolicy(List.of(server.target())), 2, 2, 65536)) {
      var tool = new HttpGetTool(http);
      var ctx = fixture.context("weather");
      Map<String, Object> args = Map.of("url", server.url("/beijing"));

      tool.authorize(args, ctx);
      ToolResult result = tool.execute(args, ctx);

      assertEquals(ToolResult.Outcome.SUCCESS, result.outcome());
      assertNotNull(result.content());
      assertTrue(result.content().contains("beijing"));
      assertEquals("GET", server.lastMethod);
      assertEquals(1, server.calls.get());
    }
  }

  @Test
  @DisplayName("http_get 命中白名单外域名应被拦下")
  void httpGet_blocksDisallowedDomainWithoutNetworkIo() throws Exception {
    try (var fixture = new ToolFixture(root);
        var allowed = new LocalHttpServer();
        var denied = new LocalHttpServer();
        var http =
            new ControlledHttpClient(
                new HttpTargetPolicy(List.of(allowed.target())), 2, 2, 65536)) {
      var tool = new HttpGetTool(http);
      var ctx = fixture.context("weather");

      OryxError directDenied =
          assertThrows(
              OryxError.class,
              () -> tool.authorize(Map.of("url", "https://evil.example.com"), ctx));
      assertEquals("TARGET_DENIED", directDenied.code());
      assertThrows(OryxError.class, () -> tool.authorize(Map.of("url", denied.url("/steal")), ctx));
      assertEquals(0, denied.calls.get());

      allowed.status = 302;
      allowed.redirectLocation = denied.url("/redirected");
      ToolResult redirectResult = tool.execute(Map.of("url", allowed.url("/hop")), ctx);
      assertEquals(ToolResult.Outcome.DENIED, redirectResult.outcome());
      assertEquals(0, denied.calls.get());
    }
  }

  @Test
  @DisplayName("http_post 白名单内正常发送且白名单外域名被拦下")
  void httpPost_sendsJsonToAllowedTargetAndBlocksDisallowedDomain() throws Exception {
    try (var fixture = new ToolFixture(root);
        var allowed = new LocalHttpServer();
        var denied = new LocalHttpServer();
        var http =
            new ControlledHttpClient(
                new HttpTargetPolicy(List.of(allowed.target())), 2, 2, 65536)) {
      var tool = new HttpPostTool(http);
      var ctx = fixture.context("reporter");

      Map<String, Object> allowedArgs =
          Map.of("url", allowed.url("/submit"), "jsonBody", Map.of("city", "beijing"));
      tool.authorize(allowedArgs, ctx);
      ToolResult ok = tool.execute(allowedArgs, ctx);
      assertEquals(ToolResult.Outcome.SUCCESS, ok.outcome());
      assertEquals("POST", allowed.lastMethod);
      assertTrue(allowed.lastRequestBody.contains("beijing"));

      OryxError blocked =
          assertThrows(
              OryxError.class,
              () ->
                  tool.authorize(
                      Map.of("url", denied.url("/blocked"), "jsonBody", Map.of("k", "v")), ctx));
      assertEquals("TARGET_DENIED", blocked.code());
      assertEquals(0, denied.calls.get());
    }
  }
}
