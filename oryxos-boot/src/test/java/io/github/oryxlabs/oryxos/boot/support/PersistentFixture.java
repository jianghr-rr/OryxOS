package io.github.oryxlabs.oryxos.boot.support;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import io.github.oryxlabs.oryxos.boot.runtime.BootRuntimeLauncher;
import io.github.oryxlabs.oryxos.core.workspace.*;
import java.net.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.ConfigurableApplicationContext;

/** Actual protocol adapters + SQLite; only the remote model and recipient are loopback fixtures. */
public final class PersistentFixture implements AutoCloseable {
  public final WorkspaceHandle workspace;
  public final AtomicInteger models = new AtomicInteger(), receipts = new AtomicInteger();
  public final List<JsonNode> requests = new CopyOnWriteArrayList<>();
  public volatile Runnable afterReceipt = () -> {};
  public volatile boolean useTool;
  public volatile java.util.function.IntConsumer beforeModel = n -> {};
  public volatile int modelStatus = 200;
  public volatile java.util.function.IntFunction<Object> modelMessage;
  private final HttpServer model, recipient;
  private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
  private final ObjectMapper json = new ObjectMapper();

  public PersistentFixture(Path root) throws Exception {
    workspace = new WorkspaceHandle(root);
    model = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    recipient = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    model.setExecutor(threads);
    recipient.setExecutor(threads);
    recipient.createContext(
        "/receive",
        e -> {
          e.getRequestBody().readAllBytes();
          receipts.incrementAndGet();
          afterReceipt.run();
          byte[] b = "accepted".getBytes();
          e.sendResponseHeaders(modelStatus, b.length);
          e.getResponseBody().write(b);
          e.close();
        });
    model.createContext(
        "/chat/completions",
        e -> {
          var request = json.readTree(e.getRequestBody());
          requests.add(request);
          int n = models.incrementAndGet();
          beforeModel.accept(n);
          Object message;
          if (useTool && n == 1)
            message =
                Map.of(
                    "role",
                    "assistant",
                    "content",
                    "send",
                    "reasoning_content",
                    "PRIVATE_OPAQUE_CANARY",
                    "tool_calls",
                    List.of(
                        Map.of(
                            "id",
                            "send-1",
                            "type",
                            "function",
                            "function",
                            Map.of(
                                "name",
                                "http_post",
                                "arguments",
                                json.writeValueAsString(
                                    Map.of("url", url(), "jsonBody", Map.of("text", "hello")))))));
          else
            message =
                Map.of(
                    "role",
                    "assistant",
                    "content",
                    "reply-" + n,
                    "reasoning_content",
                    "PRIVATE_OPAQUE_CANARY");
          if (modelMessage != null) message = modelMessage.apply(n);
          byte[] b =
              json.writeValueAsBytes(
                  Map.of(
                      "id",
                      "response-" + n,
                      "model",
                      "actual-fixture-model",
                      "choices",
                      List.of(
                          Map.of(
                              "index",
                              0,
                              "finish_reason",
                              useTool && n == 1 ? "tool_calls" : "stop",
                              "message",
                              message))));
          e.getResponseHeaders().set("Content-Type", "application/json");
          e.sendResponseHeaders(modelStatus, b.length);
          e.getResponseBody().write(b);
          e.close();
        });
    new WorkspaceInitializer()
        .initialize(
            workspace,
            Map.of(
                "runtime.yaml",
                "schema_version: 1\n"
                    + "providers:\n"
                    + "  local:\n"
                    + "    protocol: openai_chat_completions\n"
                    + "    endpoint: http://127.0.0.1:"
                    + model.getAddress().getPort()
                    + "\n"
                    + "    completions_path: /chat/completions\n"
                    + "    api_key: '${LOCAL_KEY}'\n"
                    + "tools:\n"
                    + "  http:\n"
                    + "    allowed_targets: [{scheme: http, host: 127.0.0.1, port: "
                    + recipient.getAddress().getPort()
                    + "}]\n"));
    Files.writeString(root.resolve("secrets.local.yaml"), "LOCAL_KEY: LOCAL_SECRET_CANARY\n");
    Files.setPosixFilePermissions(
        root.resolve("secrets.local.yaml"), PosixFilePermissions.fromString("rw-------"));
    Path agent = root.resolve("agents/local/AGENT.md");
    Files.createDirectories(agent.getParent());
    Files.writeString(
        agent,
        "---\n"
            + "schema_version: 1\n"
            + "name: local\n"
            + "provider: {name: local, model: requested-model}\n"
            + "tools: [http_post]\n"
            + "bootstrap: []\n"
            + "---\n"
            + "Use the configured tools only.\n");
    model.start();
    recipient.start();
  }

  public String url() {
    return "http://127.0.0.1:" + recipient.getAddress().getPort() + "/receive";
  }

  public ConfigurableApplicationContext start() {
    return new BootRuntimeLauncher()
        .start(workspace, Map.of("spring.main.web-application-type", "none"));
  }

  public void close() {
    model.stop(0);
    recipient.stop(0);
    threads.shutdownNow();
  }
}
