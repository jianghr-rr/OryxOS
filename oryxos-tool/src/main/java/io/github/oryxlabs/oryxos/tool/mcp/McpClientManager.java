package io.github.oryxlabs.oryxos.tool.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.oryxlabs.oryxos.core.config.SafeYaml;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.execution.ExecutionContext;
import io.github.oryxlabs.oryxos.core.security.*;
import io.github.oryxlabs.oryxos.core.tool.*;
import io.github.oryxlabs.oryxos.tool.validation.ToolArgumentValidator;
import io.modelcontextprotocol.client.*;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.ReentrantLock;

/** One explicitly configured process per server, no reconnect/replay after an uncertain call. */
public final class McpClientManager implements AutoCloseable {
  public record Definition(
      String id,
      String command,
      List<String> args,
      Map<String, String> environment,
      int initializationSeconds,
      int requestSeconds) {
    @Override
    public String toString() {
      return "McpDefinition[id=" + id + ", private configuration withheld]";
    }
  }

  public record State(String id, boolean available, String code, boolean closeConfirmed) {}

  private static final class Connection {
    final Definition definition;
    final SanitizedStdioTransport transport;
    final McpSyncClient client;
    final ReentrantLock lock = new ReentrantLock();
    List<McpSchema.Tool> tools = List.of();
    volatile boolean disabled;
    Boolean closed;
    String version;

    Connection(Definition d, SanitizedStdioTransport t, McpSyncClient c) {
      definition = d;
      transport = t;
      client = c;
    }
  }

  private final Map<String, Connection> connections = new LinkedHashMap<>();
  private final Map<String, State> states = new ConcurrentHashMap<>();
  private final ObjectMapper json = new ObjectMapper();
  private final ScheduledExecutorService timer =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofPlatform().daemon(true).name("oryx-mcp-deadline").factory());

  public McpClientManager(
      Path root, List<Definition> definitions, Redactor redactor, ToolArgumentValidator validator) {
    for (var d : definitions) {
      if (states.containsKey(d.id())) throw OryxError.configuration("duplicate MCP server");
      var env = new HashMap<String, String>();
      env.put("PATH", "/usr/bin:/bin");
      env.put("HOME", root.resolve("runtime/mcp-home").toString());
      env.put("TMPDIR", root.resolve("runtime/mcp-tmp").toString());
      env.putAll(d.environment());
      d.environment().values().forEach(redactor::register);
      Connection connection = null;
      try {
        if (!d.id().matches("[a-z0-9][a-z0-9-]{0,63}")
            || !Path.of(d.command()).isAbsolute()
            || d.initializationSeconds() < 1
            || d.requestSeconds() < 1) throw OryxError.configuration("MCP definition");
        Files.createDirectories(Path.of(env.get("HOME")));
        Files.createDirectories(Path.of(env.get("TMPDIR")));
        var transport =
            new SanitizedStdioTransport(
                ServerParameters.builder(d.command()).args(d.args()).build(),
                root,
                env,
                redactor,
                schema -> {
                  if (redactor.containsSecret(schema))
                    throw OryxError.configuration("MCP schema contains a credential");
                  validator.register(schema);
                });
        var client =
            McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(d.requestSeconds()))
                .initializationTimeout(Duration.ofSeconds(d.initializationSeconds()))
                .capabilities(new McpSchema.ClientCapabilities(null, null, null, null))
                .enableCallToolSchemaCaching(false)
                .jsonSchemaValidator((schema, value) -> {
                  try {
                    return validator.matches(json.writeValueAsString(schema), value)
                        ? io.modelcontextprotocol.json.schema.JsonSchemaValidator.ValidationResponse
                            .asValid(json.writeValueAsString(value))
                        : io.modelcontextprotocol.json.schema.JsonSchemaValidator.ValidationResponse
                            .asInvalid("MCP output schema mismatch");
                  } catch (Exception e) {
                    return io.modelcontextprotocol.json.schema.JsonSchemaValidator
                        .ValidationResponse.asInvalid("MCP schema validation failed offline");
                  }
                })
                .build();
        connection = new Connection(d, transport, client);
        connections.put(d.id(), connection);
        long expires = System.nanoTime() + TimeUnit.SECONDS.toNanos(d.initializationSeconds());
        connection.version = bounded(expires, client::initialize).serverInfo().version();
        var tools = new ArrayList<McpSchema.Tool>();
        String cursor = null;
        var cursors = new HashSet<String>();
        do {
          String page = cursor;
          var result = bounded(expires, () -> client.listTools(page));
          for (var tool : result.tools()) {
            if (tool.name() == null
                || tool.name().isBlank()
                || redactor.containsSecret(tool.name())
                || tools.stream().anyMatch(t -> t.name().equals(tool.name()))
                || tools.size() >= 1000) throw OryxError.configuration("MCP tool list");
            if (redactor.containsSecret(Objects.toString(tool.description(), "")))
              throw OryxError.configuration("MCP description contains a credential");
            validator.register(json.writeValueAsString(tool.inputSchema()));
            tools.add(tool);
          }
          cursor = result.nextCursor();
          if (cursor != null && !cursors.add(cursor))
            throw OryxError.configuration("MCP pagination cycle");
        } while (cursor != null);
        connection.tools = List.copyOf(tools);
        states.put(d.id(), new State(d.id(), true, "AVAILABLE", true));
      } catch (Exception e) {
        if (connection != null) {
          connection.disabled = true;
          boolean closed = close(connection);
          states.put(d.id(), new State(d.id(), false, "MCP_INITIALIZATION_FAILED", closed));
        } else states.put(d.id(), new State(d.id(), false, "MCP_CONFIGURATION_ERROR", true));
      }
    }
  }

  public static List<Definition> definitions(
      Path root, Set<String> selected, SecretResolver secrets) {
    if (selected.isEmpty()) return List.of();
    var yaml = SafeYaml.read(root.resolve("mcp_servers.yaml"), "MCP definitions");
    if (!Integer.valueOf(1).equals(yaml.get("schema_version")))
      throw OryxError.configuration("MCP schema version");
    var servers = SafeYaml.map(yaml.get("servers"), "MCP servers");
    var out = new ArrayList<Definition>();
    for (String name : selected) {
      var v = SafeYaml.map(servers.get(name), "MCP definition");
      if (!"stdio".equals(v.get("transport"))) throw OryxError.configuration("MCP transport");
      var env = new LinkedHashMap<String, String>();
      SafeYaml.map(v.getOrDefault("env", Map.of()), "MCP env")
          .forEach((k, value) -> {
            if (!k.matches("[A-Za-z_][A-Za-z0-9_]*")
                || Set.of("PATH", "HOME", "TMPDIR").contains(k))
              throw OryxError.configuration("MCP env name");
            env.put(k, secrets.resolve(SafeYaml.text(value, "MCP env reference")));
          });
      out.add(
          new Definition(
              name,
              SafeYaml.text(v.get("command"), "MCP command"),
              ((List<?>) v.getOrDefault("args", List.of()))
                  .stream().map(x -> SafeYaml.text(x, "MCP argument")).toList(),
              Map.copyOf(env),
              SafeYaml.positive(
                  v.getOrDefault("initialization_timeout_seconds", 20),
                  "MCP initialization timeout"),
              SafeYaml.positive(
                  v.getOrDefault("request_timeout_seconds", 20), "MCP request timeout")));
    }
    return out;
  }

  public String serverVersion(String id) {
    var c = connections.get(id);
    return c == null ? null : c.version;
  }

  public List<State> states() {
    return List.copyOf(states.values());
  }

  public List<McpSchema.Tool> tools(String id) {
    var c = connections.get(id);
    return c == null || c.disabled ? List.of() : c.tools;
  }

  public boolean available(String id) {
    var c = connections.get(id);
    return c != null && !c.disabled && c.transport.available();
  }

  public ToolResult call(String id, String remote, Map<String, Object> args, ExecutionContext ctx) {
    var c = connections.get(id);
    if (c == null || c.disabled || !c.transport.available())
      return ToolResult.failure(
          ToolResult.Outcome.ERROR,
          "MCP_UNAVAILABLE",
          "MCP server is unavailable; it was not reconnected");
    if (!c.lock.tryLock())
      return ToolResult.failure(
          ToolResult.Outcome.ERROR, "MCP_BUSY", "MCP server has an active call; no call was sent");
    long before = c.transport.sentCalls();
    try {
      ctx.dispatcher().check();
      c.transport.active(ctx);
      long expires =
          System.nanoTime()
              + Math.min(
                  ctx.deadline().remainingNanos(),
                  TimeUnit.SECONDS.toNanos(c.definition.requestSeconds()));
      var result =
          bounded(expires, () -> c.client.callTool(new McpSchema.CallToolRequest(remote, args)));
      var parts = new ArrayList<String>();
      if (result.content() != null)
        for (var item : result.content())
          if (item instanceof McpSchema.TextContent text) parts.add(text.text());
      String content = ctx.redactor().redact(String.join("\n", parts));
      if (content.length() > 65536) content = content.substring(0, 65500) + "\n[truncated]";
      return new ToolResult(
          Boolean.TRUE.equals(result.isError())
              ? ToolResult.Outcome.ERROR
              : ToolResult.Outcome.SUCCESS,
          content,
          Boolean.TRUE.equals(result.isError()) ? "MCP_TOOL_ERROR" : null,
          "MCP replied",
          ToolResult.RetryClass.NONE,
          ToolResult.SideEffectState.CONFIRMED,
          null,
          1,
          0);
    } catch (Exception e) {
      boolean sent = c.transport.sentCalls() > before;
      c.disabled = true;
      boolean closed = close(c);
      states.put(id, new State(id, false, "MCP_CALL_FAILED", closed));
      return new ToolResult(
          sent ? ToolResult.Outcome.UNKNOWN : ToolResult.Outcome.NOT_EXECUTED,
          "MCP result is unconfirmed; no reconnect or replay",
          sent ? "MCP_RESULT_UNKNOWN" : "MCP_NOT_EXECUTED",
          "Inspect external effects before retrying",
          ToolResult.RetryClass.NONE,
          sent ? ToolResult.SideEffectState.UNKNOWN : ToolResult.SideEffectState.NONE,
          null,
          1,
          0);
    } finally {
      c.transport.active(null);
      c.lock.unlock();
    }
  }

  private <T> T bounded(long expires, java.util.function.Supplier<T> action) {
    long remaining = expires - System.nanoTime();
    if (remaining <= 0) throw new IllegalStateException("MCP deadline");
    Thread caller = Thread.currentThread();
    var guard = new Object();
    var finished = new AtomicBoolean();
    var expired = new AtomicBoolean();
    var alarm =
        timer.schedule(
            () -> {
              synchronized (guard) {
                if (!finished.get()) {
                  expired.set(true);
                  caller.interrupt();
                }
              }
            },
            remaining,
            TimeUnit.NANOSECONDS);
    try {
      return action.get();
    } finally {
      synchronized (guard) {
        finished.set(true);
        alarm.cancel(false);
        if (expired.get()) Thread.interrupted();
      }
    }
  }

  private boolean close(Connection c) {
    synchronized (c) {
      if (c.closed != null) return c.closed;
      boolean graceful = c.client.closeGracefully();
      c.closed = graceful && c.transport.closeConfirmed();
      return c.closed;
    }
  }

  public String safeStderr(String id) {
    var c = connections.get(id);
    return c == null ? "" : c.transport.safeStderr();
  }

  public void close() {
    for (var c : connections.values()) {
      c.disabled = true;
      boolean closed = close(c);
      states.put(
          c.definition.id(),
          new State(c.definition.id(), false, closed ? "CLOSED" : "MCP_CLOSE_FAILED", closed));
    }
    timer.shutdownNow();
  }
}
