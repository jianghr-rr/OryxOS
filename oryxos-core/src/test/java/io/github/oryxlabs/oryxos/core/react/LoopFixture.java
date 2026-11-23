package io.github.oryxlabs.oryxos.core.react;

import io.github.oryxlabs.oryxos.core.agent.*;
import io.github.oryxlabs.oryxos.core.audit.*;
import io.github.oryxlabs.oryxos.core.config.SafeYaml;
import io.github.oryxlabs.oryxos.core.context.*;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.execution.*;
import io.github.oryxlabs.oryxos.core.memory.*;
import io.github.oryxlabs.oryxos.core.model.*;
import io.github.oryxlabs.oryxos.core.provider.*;
import io.github.oryxlabs.oryxos.core.runtime.*;
import io.github.oryxlabs.oryxos.core.security.Redactor;
import io.github.oryxlabs.oryxos.core.session.*;
import io.github.oryxlabs.oryxos.core.tool.*;
import io.github.oryxlabs.oryxos.core.workspace.WorkspaceHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

public final class LoopFixture implements AutoCloseable {
  public static final class Clock implements TimeSource {
    public volatile long nanos;

    public long nanoTime() {
      return nanos;
    }

    public Instant instant() {
      return Instant.parse("2026-11-12T16:00:01Z").plusNanos(nanos);
    }
  }

  public static class Store implements SessionStore {
    public final Map<UUID, Session> sessions = new HashMap<>();
    public final List<SessionMessage> messages = new CopyOnWriteArrayList<>();
    public final Set<UUID> completed = new HashSet<>();

    public synchronized void create(Session s) {
      sessions.put(s.id(), s);
    }

    public synchronized void archive(UUID id, java.time.Instant at) {
      sessions.put(id, sessions.get(id).archived(at));
    }

    public synchronized Session find(UUID id) {
      return sessions.get(id);
    }

    public List<SessionMessage> messages(UUID id) {
      return messages.stream().filter(m -> m.sessionId().equals(id)).toList();
    }

    public synchronized Set<UUID> completedTurns(UUID id) {
      return Set.copyOf(completed);
    }

    public void append(SessionMessage m) {
      messages.add(m);
    }

    public synchronized void finishTurn(UUID session, UUID turn) {
      completed.add(turn);
    }
  }

  public final Clock clock = new Clock();
  public final Store store = new Store();
  public final RuntimeGate gate = new RuntimeGate();
  public final List<ToolCallRecord> audit = new CopyOnWriteArrayList<>();
  public final List<ProviderRequest> requests = new ArrayList<>();
  public final List<String> effects = new ArrayList<>();
  public final List<Long> delays = new ArrayList<>();
  public final SessionManager sessions = new SessionManager(store, clock);
  public final SessionManager.Lease lease;
  public final ExecutionContext context;
  public final AgentProfile profile;
  public final Map<String, OryxTool> registered = new LinkedHashMap<>();
  public final List<ProviderReply> script = new ArrayList<>();
  public int memoryReads;
  public Function<ProviderRequest, ProviderReply> provider;
  public final AuditSink sink =
      new AuditSink() {
        public void modelCall(ModelCallRecord r) {}

        public void toolCall(ToolCallRecord r) {
          audit.add(r);
        }
      };
  public final ToolRegistryPort registry =
      new ToolRegistryPort() {
        public OryxTool find(String name) {
          return registered.get(name);
        }

        public List<ToolDescriptor> available(AgentProfile p) {
          return registered.values().stream()
              .map(OryxTool::descriptor)
              .filter(t -> p.tools().contains(t.name()))
              .toList();
        }

        public Validation validate(ToolDescriptor descriptor, String json) {
          try {
            var map =
                SafeYaml.document(json.getBytes(StandardCharsets.UTF_8), "fixture JSON").values();
            boolean valid = map.get("value") instanceof Number n && n.intValue() >= 0;
            return new Validation(
                map, true, valid ? Set.of() : Set.of("/value"), "Invalid field /value");
          } catch (RuntimeException e) {
            return new Validation(Map.of(), false, Set.of(""), "Invalid JSON object");
          }
        }

        public void authorize(OryxTool tool, Map<String, Object> args, ExecutionContext ctx) {
          if (!ctx.agent().tools().contains(tool.descriptor().name()))
            throw new OryxError("TOOL_DENIED", "Tool not allowed");
          tool.authorize(args, ctx);
        }
      };

  public LoopFixture(Path root, int iterations) throws Exception {
    Path definition = root.resolve("agents/demo/AGENT.md");
    Files.createDirectories(definition.getParent());
    Files.writeString(definition, "---\nname: demo\n---\ninitial instructions\n");
    Files.writeString(root.resolve("USER.md"), "initial bootstrap");
    profile =
        new AgentProfile(
            "demo",
            "demo",
            "fixture",
            "model",
            List.of("first", "second", "write"),
            List.of(),
            List.of(root.resolve("USER.md")),
            Map.of(),
            List.of(),
            iterations,
            20,
            definition,
            "revision",
            "body");
    gate.readyAtFirstStartup();
    lease = sessions.acquire(null, "cli", "user", profile, Session.Kind.CONVERSATION);
    var deadline = new Deadline(clock, Duration.ofSeconds(60));
    var permit = gate.execution(deadline);
    var names =
        new ToolNameMap(
            profile.tools().stream().map(n -> new ToolNameMap.Entry(n, n, null, null)).toList());
    var execution =
        new Execution(
            UUID.randomUUID(),
            lease.session().id(),
            UUID.randomUUID(),
            ProcessRequest.manual("cli", "user", "demo", null, "weather"),
            "revision",
            clock.instant(),
            deadline.deadlineAt(),
            ZoneId.of("Asia/Shanghai"),
            names);
    context =
        new ExecutionContext(
            new WorkspaceHandle(root),
            profile,
            execution,
            deadline,
            gate,
            permit,
            new ActionDispatcher(permit),
            clock,
            new Redactor());
    lease.append(execution.id(), execution.turnId(), ChatMessage.user("weather"), null);
    provider =
        request -> {
          requests.add(request);
          return script.removeFirst();
        };
    add("first", false, (args, ctx) -> ToolResult.success("sunny"));
    add("second", false, (args, ctx) -> ToolResult.success("warm"));
  }

  @FunctionalInterface
  public interface ToolBody {
    ToolResult apply(Map<String, Object> args, ExecutionContext ctx);
  }

  public void add(String name, boolean write, ToolBody body) {
    registered.put(
        name,
        new OryxTool() {
          public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                name, name, "{\"type\":\"object\"}", !write, write, null, null);
          }

          public void authorize(Map<String, Object> args, ExecutionContext ctx) {
            if ("denied".equals(args.get("target")))
              throw new OryxError("TARGET_DENIED", "Resource target denied");
          }

          public ToolResult execute(Map<String, Object> args, ExecutionContext ctx) {
            effects.add(name);
            return body.apply(args, ctx);
          }
        });
  }

  public io.github.oryxlabs.oryxos.core.agent.AgentCatalog catalog() throws Exception {
    var root = context.workspace().root();
    Files.writeString(
        root.resolve("runtime.yaml"),
        "schema_version: 1\nproviders:\n  fixture:\n    protocol: openai_chat_completions\n    endpoint: http://127.0.0.1:1\n    completions_path: /chat/completions\n    api_key: '${TEST_KEY}'\n");
    Files.writeString(
        profile.definition(),
        "---\nschema_version: 1\nname: demo\nprovider: {name: fixture, model: model}\ntools: [first, second, write]\nbootstrap: [USER.md]\n---\ninstructions\n");
    var env = Map.of("TEST_KEY", "fixture");
    var redactor = new Redactor();
    var config =
        new io.github.oryxlabs.oryxos.core.config.RuntimeConfigLoader(env, redactor)
            .load(context.workspace());
    return io.github.oryxlabs.oryxos.core.agent.AgentCatalog.load(
        context.workspace(), config, env, redactor);
  }

  public ReActLoop loop() {
    return new ReActLoop(
        r -> provider.apply(r),
        registry,
        new ToolExecutor(
            registry,
            sink,
            d -> {
              delays.add(d.toMillis());
              clock.nanos += d.toNanos();
            }),
        ctx -> {
          memoryReads++;
          var h =
              sessions.history(
                  lease.session().id(), context.execution().turnId(), profile.maxHistoryTurns());
          return new ContextMemory(h.recent(), h.current(), "", "");
        },
        new PromptBuilder(new ContextLoader()));
  }

  public Execution.Snapshot run() {
    loop().run(context, lease);
    return context.snapshot();
  }

  public static ToolCall call(String id, String name, String args) {
    return new ToolCall(id, name, name, args);
  }

  public static ProviderReply reply(String text, ToolCall... calls) {
    return new ProviderReply(
        new ChatMessage(ChatMessage.Role.ASSISTANT, text, List.of(calls), null, null, null),
        calls.length == 0 ? "STOP" : "TOOL_CALLS",
        null,
        "fixture",
        "model");
  }

  public void close() {
    lease.close();
    gate.close();
  }
}
