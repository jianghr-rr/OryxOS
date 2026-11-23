package io.github.oryxlabs.oryxos.provider;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import io.github.oryxlabs.oryxos.core.audit.*;
import io.github.oryxlabs.oryxos.core.config.*;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.model.*;
import io.github.oryxlabs.oryxos.core.provider.*;
import io.github.oryxlabs.oryxos.core.security.Redactor;
import io.github.oryxlabs.oryxos.core.workspace.WorkspaceHandle;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

abstract class ProviderTestSupport {
  @TempDir Path root;
  static final ObjectMapper JSON = new ObjectMapper();
  static final String SECRET = "fixture-secret-do-not-log";
  final List<ModelCallRecord> audit = new CopyOnWriteArrayList<>();
  final Redactor redactor = new Redactor();
  final List<ProviderService> owned = new ArrayList<>();

  @AfterEach
  void closeRegistries() {
    owned.forEach(ProviderService::close);
  }

  static final class Receiver implements AutoCloseable {
    final HttpServer server;
    final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    final List<String> paths = new CopyOnWriteArrayList<>();
    final List<Integer> remotePorts = new CopyOnWriteArrayList<>();
    final List<String> credentials = new CopyOnWriteArrayList<>();
    volatile int status = 200;
    volatile long delayMillis;
    volatile boolean delayBody;
    volatile boolean disconnect;
    volatile String response = ordinary("served-model", "fixture answer");
    volatile Runnable onRequest = () -> {};

    Receiver() throws Exception {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(executor);
      server.createContext(
          "/",
          exchange -> {
            try (exchange) {
              paths.add(exchange.getRequestURI().toString());
              remotePorts.add(exchange.getRemoteAddress().getPort());
              credentials.add(exchange.getRequestHeaders().getFirst("Authorization"));
              requests.add(JSON.readTree(exchange.getRequestBody()));
              onRequest.run();
              if (disconnect) return;
              if (delayMillis > 0 && !delayBody) pause(delayMillis);
              byte[] body = response.getBytes(StandardCharsets.UTF_8);
              exchange.getResponseHeaders().set("Content-Type", "application/json");
              exchange.sendResponseHeaders(status, body.length);
              if (delayBody) {
                exchange.getResponseBody().write(body[0]);
                exchange.getResponseBody().flush();
                pause(delayMillis);
                exchange.getResponseBody().write(body, 1, body.length - 1);
              } else exchange.getResponseBody().write(body);
            }
          });
      server.start();
    }

    String origin() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void close() {
      server.stop(0);
      executor.shutdownNow();
    }

    private static void pause(long millis) {
      try {
        Thread.sleep(millis);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  static String ordinary(String model, String content) {
    return "{\"id\":\"fixture\",\"object\":\"chat.completion\",\"model\":\""
        + model
        + "\",\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\""
        + content
        + "\"}}]}";
  }

  static String definition(String name, String origin, String path, String options) {
    return "  "
        + name
        + ":\n    protocol: openai_chat_completions\n    endpoint: "
        + origin
        + "\n    completions_path: "
        + path
        + "\n    api_key: \"${"
        + name.toUpperCase(Locale.ROOT)
        + "_KEY}\"\n    options: "
        + options
        + "\n";
  }

  ProviderService registry(String definitions, Map<String, String> environment) throws Exception {
    return registry(definitions, environment, audit::add);
  }

  ProviderService registry(String definitions, Map<String, String> environment, AuditSink sink)
      throws Exception {
    Files.writeString(
        root.resolve("runtime.yaml"), "schema_version: 1\nproviders:\n" + definitions);
    var workspace = new WorkspaceHandle(root);
    var config = new RuntimeConfigLoader(environment, redactor).load(workspace);
    var registry = new ProviderService(config, workspace, environment, redactor, sink);
    owned.add(registry);
    return registry;
  }

  static ProviderRequest request(String provider, String text) {
    return request(
        provider,
        List.of(ChatMessage.user(text)),
        ToolNameMap.empty(),
        List.of(),
        Duration.ofSeconds(3));
  }

  static ProviderRequest request(
      String provider,
      List<ChatMessage> messages,
      ToolNameMap names,
      List<ProviderRequest.ToolSchema> tools,
      Duration budget) {
    return new ProviderRequest(
        provider,
        "configured-model",
        "fixture system",
        messages,
        tools,
        names,
        budget,
        UUID.randomUUID(),
        1);
  }

  static void assertCode(String code, org.junit.jupiter.api.function.Executable executable) {
    var error = assertThrows(OryxError.class, executable);
    assertEquals(code, error.code());
    assertFalse(error.toString().contains(SECRET));
    assertNull(error.getCause());
  }
}
