package io.github.oryxlabs.oryxos.tool.mcp;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import io.github.oryxlabs.oryxos.core.execution.ExecutionContext;
import io.github.oryxlabs.oryxos.core.security.Redactor;
import io.github.oryxlabs.oryxos.tool.shell.ShellTool;
import io.modelcontextprotocol.client.transport.*;
import io.modelcontextprotocol.json.*;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import reactor.core.publisher.Mono;

/**
 * SDK transport boundary with bounded pipes and owned process cleanup; protocol remains in the SDK.
 */
public final class SanitizedStdioTransport extends StdioClientTransport {
  private final ServerParameters params;
  private final Path root;
  private final Map<String, String> environment;
  private final Redactor redactor;
  private final Consumer<String> schemaCheck;
  private final McpJsonMapper mapper;
  private final ObjectMapper json;
  private final ExecutorService pipes = Executors.newVirtualThreadPerTaskExecutor();
  private volatile Process child;
  private volatile boolean closing, failed;
  private volatile ExecutionContext active;
  private volatile String stderr = "";
  private volatile boolean closeConfirmed = true;
  private final AtomicLong calls = new AtomicLong();
  private final Object writes = new Object();

  public SanitizedStdioTransport(
      ServerParameters params,
      Path root,
      Map<String, String> environment,
      Redactor redactor,
      Consumer<String> schemaCheck) {
    this(
        params,
        root,
        environment,
        redactor,
        schemaCheck,
        new ObjectMapper(
            JsonFactory.builder()
                .streamReadConstraints(
                    StreamReadConstraints.builder()
                        .maxNestingDepth(64)
                        .maxStringLength(1048576)
                        .build())
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build()));
  }

  private SanitizedStdioTransport(
      ServerParameters params,
      Path root,
      Map<String, String> environment,
      Redactor redactor,
      Consumer<String> check,
      ObjectMapper json) {
    super(params, new JacksonMcpJsonMapper(json));
    this.params = params;
    this.root = root;
    this.environment = Map.copyOf(environment);
    this.redactor = redactor;
    this.schemaCheck = check;
    this.json = json;
    mapper = new JacksonMcpJsonMapper(json);
  }

  @Override
  protected ProcessBuilder getProcessBuilder() {
    var builder = new ProcessBuilder().directory(root.toFile());
    builder.environment().clear();
    builder.environment().putAll(environment);
    return builder;
  }

  @Override
  public Mono<Void> connect(
      Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
    return Mono.fromRunnable(
        () -> {
          try {
            var command = new ArrayList<String>();
            command.add(params.getCommand());
            command.addAll(params.getArgs());
            child = getProcessBuilder().command(command).start();
            pipes.submit(
                () -> {
                  try (var input = child.getInputStream()) {
                    String line;
                    while (!closing && (line = readLine(input, 1048576)) != null) {
                      var node = json.readTree(line);
                      if (node.path("result").path("tools").isArray())
                        for (var tool : node.path("result").path("tools")) {
                          schemaCheck.accept(tool.path("inputSchema").toString());
                          if (tool.hasNonNull("outputSchema"))
                            schemaCheck.accept(tool.path("outputSchema").toString());
                        }
                      handler
                          .apply(Mono.just(McpSchema.deserializeJsonRpcMessage(mapper, line)))
                          .subscribe(
                              v -> {},
                              e -> {
                                failed = true;
                              });
                    }
                    if (!closing) failed = true;
                  } catch (Exception e) {
                    failed = true;
                  }
                });
            pipes.submit(
                () -> {
                  try (var input = child.getErrorStream()) {
                    var capture = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int count;
                    while ((count = input.read(buf)) != -1) {
                      int keep = Math.min(count, Math.max(0, 65536 - capture.size()));
                      capture.write(buf, 0, keep);
                    }
                    stderr = redactor.redact(capture.toString(StandardCharsets.UTF_8));
                  } catch (IOException e) {
                    stderr = "MCP stderr could not be read";
                  }
                });
          } catch (IOException e) {
            failed = true;
            throw new IllegalStateException("MCP process could not start");
          }
        });
  }

  @Override
  public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
    return Mono.fromRunnable(
        () -> {
          synchronized (writes) {
            if (closing || failed || child == null || !child.isAlive())
              throw new IllegalStateException("MCP transport unavailable");
            Supplier<Void> send =
                () -> {
                  try {
                    byte[] data =
                        (mapper.writeValueAsString(message) + "\n")
                            .getBytes(StandardCharsets.UTF_8);
                    if (data.length > 1048576) throw new IOException();
                    if (message instanceof McpSchema.JSONRPCRequest r
                        && r.method().equals("tools/call")) calls.incrementAndGet();
                    child.getOutputStream().write(data);
                    child.getOutputStream().flush();
                    return null;
                  } catch (Exception e) {
                    failed = true;
                    throw new IllegalStateException("MCP protocol write failed");
                  }
                };
            var ctx = active;
            if (ctx != null && message instanceof McpSchema.JSONRPCRequest)
              ctx.dispatcher().call(send);
            else send.get();
          }
        });
  }

  public void active(ExecutionContext context) {
    active = context;
  }

  public long sentCalls() {
    return calls.get();
  }

  public boolean available() {
    return !closing && !failed && child != null && child.isAlive();
  }

  public String safeStderr() {
    return stderr;
  }

  public boolean closeConfirmed() {
    return closeConfirmed;
  }

  @Override
  public Mono<Void> closeGracefully() {
    return Mono.defer(
        () -> {
          closing = true;
          boolean graceful = true;
          if (child != null) {
            child.destroy();
            try {
              graceful = child.waitFor(300, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              graceful = false;
            }
            closeConfirmed = ShellTool.terminate(child);
          }
          pipes.shutdownNow();
          final boolean ok = graceful && closeConfirmed;
          return super.closeGracefully()
              .then(
                  ok
                      ? Mono.empty()
                      : Mono.error(
                          new IllegalStateException(
                              "MCP graceful close failed; forced cleanup attempted")));
        });
  }

  @Override
  public void awaitForExit() {
    if (child != null)
      try {
        if (!child.waitFor(3, TimeUnit.SECONDS))
          throw new IllegalStateException("MCP process is still running");
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("MCP close interrupted");
      }
  }

  private static String readLine(InputStream in, int max) throws IOException {
    var bytes = new ByteArrayOutputStream();
    int value;
    while ((value = in.read()) != -1 && value != '\n') {
      if (bytes.size() >= max) throw new IOException("MCP line bound exceeded");
      bytes.write(value);
    }
    return value == -1 && bytes.size() == 0 ? null : bytes.toString(StandardCharsets.UTF_8);
  }
}
