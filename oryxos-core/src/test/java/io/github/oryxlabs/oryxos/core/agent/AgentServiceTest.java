package io.github.oryxlabs.oryxos.core.agent;

import static io.github.oryxlabs.oryxos.core.react.LoopFixture.call;
import static io.github.oryxlabs.oryxos.core.react.LoopFixture.reply;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.oryxlabs.oryxos.core.execution.Execution;
import io.github.oryxlabs.oryxos.core.execution.ExecutionStore;
import io.github.oryxlabs.oryxos.core.execution.ProcessRequest;
import io.github.oryxlabs.oryxos.core.model.ChatMessage;
import io.github.oryxlabs.oryxos.core.react.LoopFixture;
import io.github.oryxlabs.oryxos.core.security.Redactor;
import io.github.oryxlabs.oryxos.core.tool.ToolResult;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("us2-loop")
class AgentServiceTest {
  @TempDir Path root;

  private static final class MemoryExecutions implements ExecutionStore {
    final ConcurrentHashMap<UUID, Execution.Snapshot> saved = new ConcurrentHashMap<>();

    @Override
    public void save(Execution.Snapshot execution) {
      saved.put(execution.id(), execution);
    }

    @Override
    public Execution.Snapshot find(UUID executionId) {
      return saved.get(executionId);
    }
  }

  private static AgentService service(LoopFixture f) throws Exception {
    return new AgentService(
        f.context.workspace(),
        f.catalog(),
        f.sessions,
        new MemoryExecutions(),
        f.loop(),
        f.registry,
        f.gate,
        f.clock,
        ZoneId.of("Asia/Shanghai"),
        new Redactor());
  }

  private static ProcessRequest request() {
    return ProcessRequest.manual("http", "u", "demo", null, "weather");
  }

  @Test
  void profileContextIsAvailableDuringToolExecution() throws Exception {
    try (var f = new LoopFixture(root, 10)) {
      var seen = new AtomicReference<AgentProfile>();
      var agent = new AtomicReference<AgentProfile>();
      f.add(
          "first",
          false,
          (args, ctx) -> {
            seen.set(ProfileContext.current());
            agent.set(ctx.agent());
            return ToolResult.success("sunny");
          });
      f.script.add(reply(null, call("one", "first", "{\"value\":1}")));
      f.script.add(reply("done"));
      try (var service = service(f)) {
        var result = service.process(request());
        assertEquals(Execution.Status.SUCCEEDED, result.status());
      }
      assertTrue(agent.get() != null);
      assertSame(agent.get(), seen.get());
    }
  }

  @Test
  @DisplayName("处理中抛异常_ProfileContext也必须被清掉")
  void profileContextIsClearedWhenProcessingThrows() throws Exception {
    try (var f = new LoopFixture(root, 10)) {
      f.provider =
          r -> {
            throw new RuntimeException("boom");
          };
      try (var service = service(f)) {
        var handle = AgentService.withInlineWorker(h -> {}, () -> service.start(request()));
        var result = handle.await();
        assertEquals(Execution.Status.FAILED, result.status());
        assertEquals("EXECUTION_FAILED", result.errorCode());
      }
      assertNull(ProfileContext.current());
    }
  }

  @Test
  void profileContextIsClearedBeforeCompletionOnWorkerThread() throws Exception {
    try (var f = new LoopFixture(root, 10)) {
      var during = new AtomicReference<AgentProfile>();
      var atCompletion = new AtomicReference<Object>("unset");
      f.provider =
          r -> {
            during.set(ProfileContext.current());
            throw new RuntimeException("boom");
          };
      try (var service = service(f)) {
        var handle =
            service.start(request(), s -> {}, () -> atCompletion.set(ProfileContext.current()));
        var result = handle.await();
        assertEquals(Execution.Status.FAILED, result.status());
      }
      assertTrue(during.get() != null);
      assertNull(atCompletion.get());
    }
  }

  @Test
  void sessionMessagesArePersistedWhenProcessingEnds() throws Exception {
    try (var f = new LoopFixture(root, 10)) {
      f.script.add(reply(null, call("one", "first", "{\"value\":1}")));
      f.script.add(reply("sunny"));
      Execution.Snapshot result;
      try (var service = service(f)) {
        result = service.process(request());
      }
      assertEquals(Execution.Status.SUCCEEDED, result.status());
      var stored =
          f.store.messages.stream()
              .filter(m -> m.turnId().equals(result.turnId()))
              .map(m -> m.message().role())
              .toList();
      assertEquals(
          List.of(
              ChatMessage.Role.USER,
              ChatMessage.Role.ASSISTANT,
              ChatMessage.Role.TOOL,
              ChatMessage.Role.ASSISTANT),
          stored);
      assertTrue(f.store.completed.contains(result.turnId()));
    }
  }
}
