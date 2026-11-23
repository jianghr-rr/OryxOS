package io.github.oryxlabs.oryxos.provider;

import static org.junit.jupiter.api.Assertions.*;

import io.github.oryxlabs.oryxos.core.agent.AgentProfile;
import io.github.oryxlabs.oryxos.core.audit.*;
import io.github.oryxlabs.oryxos.core.context.*;
import io.github.oryxlabs.oryxos.core.execution.*;
import io.github.oryxlabs.oryxos.core.memory.ContextMemory;
import io.github.oryxlabs.oryxos.core.model.ChatMessage;
import io.github.oryxlabs.oryxos.core.model.ToolNameMap;
import io.github.oryxlabs.oryxos.core.react.ReActLoop;
import io.github.oryxlabs.oryxos.core.runtime.*;
import io.github.oryxlabs.oryxos.core.security.Redactor;
import io.github.oryxlabs.oryxos.core.session.*;
import io.github.oryxlabs.oryxos.core.tool.*;
import io.github.oryxlabs.oryxos.core.workspace.WorkspaceHandle;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;

/** Drives a real ReActLoop and PromptBuilder so admission is counted on the production path. */
@Tag("us1-contract")
class ProviderAdmissionTest extends ProviderTestSupport {
  static final class Store implements SessionStore {
    final Map<UUID, Session> sessions = new HashMap<>();
    final List<SessionMessage> messages = new CopyOnWriteArrayList<>();
    final Set<UUID> completed = new HashSet<>();

    public synchronized void create(Session session) {
      sessions.put(session.id(), session);
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

    public void append(SessionMessage message) {
      messages.add(message);
    }

    public synchronized void finishTurn(UUID session, UUID turn) {
      completed.add(turn);
    }
  }

  static final ToolRegistryPort NO_TOOLS =
      new ToolRegistryPort() {
        public OryxTool find(String name) {
          return null;
        }

        public List<ToolDescriptor> available(AgentProfile profile) {
          return List.of();
        }

        public Validation validate(ToolDescriptor descriptor, String arguments) {
          return new Validation(Map.of(), false, Set.of(""), "No tools");
        }

        public void authorize(OryxTool tool, Map<String, Object> arguments, ExecutionContext ctx) {
          throw new IllegalStateException("No tools");
        }
      };

  final RuntimeGate gate = new RuntimeGate();
  final List<ModelCallRecord> models = new CopyOnWriteArrayList<>();
  RuntimeGate.ExecutionPermit permit;

  @AfterEach
  void closeGate() {
    gate.close();
  }

  ExecutionContext run(Receiver receiver, AuditSink sink) throws Exception {
    Path workspace = root.toRealPath();
    Path definition = workspace.resolve("agents/demo/AGENT.md");
    Files.createDirectories(definition.getParent());
    Files.writeString(definition, "---\nname: demo\n---\nanswer briefly\n");
    var port =
        registry(
            definition("good", receiver.origin(), "/chat/completions", "{}"),
            Map.of("GOOD_KEY", SECRET),
            sink);
    var profile =
        new AgentProfile(
            "demo",
            "demo",
            "good",
            "configured-model",
            List.of(),
            List.of(),
            List.of(),
            Map.of(),
            List.of(),
            3,
            20,
            definition,
            "revision",
            "body");
    var time = TimeSource.system();
    var sessions = new SessionManager(new Store(), time);
    gate.readyAtFirstStartup();
    var deadline = new Deadline(time, Duration.ofSeconds(10));
    permit = gate.execution(deadline);
    try (var lease = sessions.acquire(null, "cli", "user", profile, Session.Kind.CONVERSATION)) {
      var execution =
          new Execution(
              UUID.randomUUID(),
              lease.session().id(),
              UUID.randomUUID(),
              ProcessRequest.manual("cli", "user", "demo", null, "hello"),
              "revision",
              time.instant(),
              deadline.deadlineAt(),
              ZoneId.of("Asia/Shanghai"),
              ToolNameMap.empty());
      var context =
          new ExecutionContext(
              new WorkspaceHandle(workspace),
              profile,
              execution,
              deadline,
              gate,
              permit,
              new ActionDispatcher(permit),
              time,
              new Redactor());
      lease.append(execution.id(), execution.turnId(), ChatMessage.user("hello"), null);
      new ReActLoop(
              port,
              NO_TOOLS,
              new ToolExecutor(NO_TOOLS, sink, delay -> {}),
              ctx -> {
                var history =
                    sessions.history(
                        lease.session().id(), execution.turnId(), profile.maxHistoryTurns());
                return new ContextMemory(history.recent(), history.current(), "", "");
              },
              new PromptBuilder(new ContextLoader()))
          .run(context, lease);
      return context;
    }
  }

  @Test
  void modelCallHoldsExactlyOneAdmissionWhileInFlight() throws Exception {
    try (var receiver = new Receiver()) {
      var observed = new AtomicInteger(-1);
      receiver.onRequest = () -> observed.set(gate.inFlight());
      run(receiver, models::add);
      assertEquals(1, receiver.requests.size());
      assertEquals(1, observed.get(), "one model call must hold exactly one runtime admission");
      assertEquals(0, gate.inFlight());
      assertEquals(
          List.of("STARTED", "SUCCEEDED"), models.stream().map(ModelCallRecord::status).toList());
    }
  }

  @Test
  void stopAfterAdmissionLetsTheCallFinishAndRecordsActualOutcome() throws Exception {
    try (var receiver = new Receiver()) {
      AuditSink stopOnStart =
          record -> {
            models.add(record);
            if ("STARTED".equals(record.status())) permit.stop();
          };
      run(receiver, stopOnStart);
      assertEquals(1, receiver.requests.size(), "an admitted call must still reach the provider");
      var started = models.getFirst();
      assertEquals("STARTED", started.status());
      var terminal =
          models.stream()
              .filter(r -> r.callId().equals(started.callId()) && !"STARTED".equals(r.status()))
              .toList();
      assertEquals(1, terminal.size());
      assertEquals("SUCCEEDED", terminal.getFirst().status());
      assertTrue(
          models.stream()
              .map(ModelCallRecord::errorCode)
              .filter(Objects::nonNull)
              .noneMatch(code ->
                  Set.of("EXECUTION_STOPPED", "EXECUTION_PAUSED", "DEADLINE").contains(code)),
          "a stop after admission must not be recorded as a model failure");
      assertEquals(0, gate.inFlight());
    }
  }
}
