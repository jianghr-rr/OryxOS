package io.github.oryxlabs.oryxos.core.context;

import static io.github.oryxlabs.oryxos.core.react.LoopFixture.call;
import static io.github.oryxlabs.oryxos.core.react.LoopFixture.reply;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.oryxlabs.oryxos.core.execution.Execution;
import io.github.oryxlabs.oryxos.core.memory.ContextMemory;
import io.github.oryxlabs.oryxos.core.model.ChatMessage;
import io.github.oryxlabs.oryxos.core.react.LoopFixture;
import io.github.oryxlabs.oryxos.core.tool.ToolDescriptor;
import io.github.oryxlabs.oryxos.core.tool.ToolResult;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("us2-loop")
class PromptBuilderTest {
  @TempDir Path root;

  private static final ToolDescriptor TOOL =
      new ToolDescriptor(
          "first", "TOOL_DESCRIPTION_MARK", "{\"type\":\"object\"}", true, false, null, null);

  @Test
  void systemPromptOrdersBodyBootstrapDateTimeThenMemory() throws Exception {
    try (var f = new LoopFixture(root, 10)) {
      var memory =
          new ContextMemory(
              List.of(), List.of(ChatMessage.user("now")), "CORE_MARK", "ARCHIVAL_MARK");
      var request = new PromptBuilder(new ContextLoader()).build(f.context, memory, List.of(), 1);
      String system = request.systemPrompt();
      int body = system.indexOf("initial instructions");
      int bootstrap = system.indexOf("initial bootstrap");
      int time = system.indexOf("Current workspace time");
      int core = system.indexOf("CORE_MARK");
      int archival = system.indexOf("ARCHIVAL_MARK");
      assertTrue(body >= 0, "body present");
      assertTrue(body < bootstrap, "body before bootstrap");
      assertTrue(bootstrap < time, "bootstrap before date-time");
      assertTrue(time < core, "date-time before long-term memory");
      assertTrue(core < archival, "CORE before ARCHIVAL");
      assertTrue(system.substring(time, core).contains("2026-11-13"));
    }
  }

  @Test
  void historyPrecedesCurrentTurnAndToolsAreSeparate() throws Exception {
    try (var f = new LoopFixture(root, 10)) {
      var memory =
          new ContextMemory(
              List.of(ChatMessage.user("earlier"), reply("earlier answer").assistantMessage()),
              List.of(ChatMessage.user("now")),
              "",
              "");
      var request =
          new PromptBuilder(new ContextLoader()).build(f.context, memory, List.of(TOOL), 1);
      assertEquals(
          List.of("earlier", "earlier answer", "now"),
          request.orderedMessages().stream().map(ChatMessage::content).toList());
      assertEquals(List.of("first"), request.tools().stream().map(t -> t.name()).toList());
      assertEquals("TOOL_DESCRIPTION_MARK", request.tools().getFirst().description());
      assertFalse(request.systemPrompt().contains("TOOL_DESCRIPTION_MARK"));
    }
  }

  @Test
  void requestHistoryIsBoundedByMaxHistoryTurns() throws Exception {
    try (var f = new LoopFixture(root, 10)) {
      for (int i = 0; i < 25; i++) {
        var turn = UUID.randomUUID();
        var exec = UUID.randomUUID();
        var call = call("t" + i, "first", "{}");
        f.lease.append(exec, turn, ChatMessage.user("turn" + i), null);
        f.lease.append(exec, turn, reply(null, call).assistantMessage(), null);
        f.lease.append(exec, turn, ChatMessage.tool(call, "ok"), ToolResult.Outcome.SUCCESS);
        f.lease.append(exec, turn, reply("done").assistantMessage(), null);
        f.lease.finish(turn);
      }
      int stored = f.store.messages.size();
      f.script.add(reply("answer"));
      assertEquals(Execution.Status.SUCCEEDED, f.run().status());
      var messages = f.requests.getFirst().orderedMessages();
      var users = messages.stream().filter(m -> m.role() == ChatMessage.Role.USER).toList();
      assertEquals(21, users.size(), "20 history turns plus the current message");
      assertEquals("turn5", messages.getFirst().content());
      assertEquals("weather", messages.getLast().content());
      assertTrue(f.store.messages.size() >= stored, "trimming context never deletes history");
    }
  }
}
