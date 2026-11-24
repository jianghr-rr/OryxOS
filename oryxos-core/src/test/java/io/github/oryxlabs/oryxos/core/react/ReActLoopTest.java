package io.github.oryxlabs.oryxos.core.react;

import static io.github.oryxlabs.oryxos.core.react.LoopFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.oryxlabs.oryxos.core.execution.Execution;
import io.github.oryxlabs.oryxos.core.model.ChatMessage;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Tag("us2-loop")
class ReActLoopTest {
  @TempDir Path root;

  @Test
  void directReplyStopsWithoutToolIo() throws Exception {
    try (var f = new LoopFixture(root, 10)) {
      f.script.add(reply("hello"));
      var result = assertDoesNotThrow(f::run);
      assertEquals(Execution.Status.SUCCEEDED, result.status());
      assertEquals(1, f.requests.size());
      assertTrue(f.effects.isEmpty());
    }
  }

  @Test
  void toolsRunSequentiallyAndHistoryContainsPairedMessagesOnce() throws Exception {
    try (var f = new LoopFixture(root, 10)) {
      f.script.add(
          reply(
              null, call("one", "first", "{\"value\":1}"), call("two", "second", "{\"value\":2}")));
      f.script.add(reply("sunny and warm"));
      var result = assertDoesNotThrow(f::run);
      assertEquals(Execution.Status.SUCCEEDED, result.status());
      assertEquals(List.of("first", "second"), f.effects);
      var messages = f.requests.get(1).orderedMessages();
      assertEquals(4, messages.size());
      assertEquals(1, messages.stream().filter(m -> m.role() == ChatMessage.Role.USER).count());
      assertEquals(
          List.of("one", "two"),
          messages.stream()
              .filter(m -> m.role() == ChatMessage.Role.TOOL)
              .map(ChatMessage::toolCallId)
              .toList());
      assertEquals(2, f.memoryReads);
      assertEquals(4, f.audit.size());
    }
  }

  @Test
  void iterationLimitPairsUnexecutedProposalsAndStopsNewIo() throws Exception {
    try (var f = new LoopFixture(root, 1)) {
      f.script.add(reply(null, call("one", "first", "{\"value\":1}")));
      var result = assertDoesNotThrow(f::run);
      assertEquals("ITERATION_LIMIT", result.stopReason());
      assertNotEquals(Execution.Status.SUCCEEDED, result.status());
      assertTrue(f.effects.isEmpty());
      assertEquals(1, f.requests.size());
      assertEquals("one", f.store.messages.getLast().message().toolCallId());
    }
  }

  @Test
  @DisplayName("模型一直要调工具_转满最大轮数强制停")
  void modelAlwaysRequestingToolsStopsAtMaxIterations() throws Exception {
    try (var f = new LoopFixture(root, 10)) {
      f.provider =
          request -> {
            f.requests.add(request);
            return reply(null, call("c" + f.requests.size(), "first", "{\"value\":1}"));
          };
      var result = assertDoesNotThrow(f::run);
      assertEquals(10, f.requests.size());
      assertEquals("ITERATION_LIMIT", result.stopReason());
      assertTrue(result.finalContent().contains("达到迭代上限"));
      // The tenth proposal can no longer be observed by the model, so it is paired but not run.
      assertEquals(9, f.effects.size());
    }
  }

  @Test
  void everyRoundAccumulatesReplyAndToolResultsInSession() throws Exception {
    try (var f = new LoopFixture(root, 10)) {
      f.script.add(reply(null, call("one", "first", "{\"value\":1}")));
      f.script.add(reply(null, call("two", "second", "{\"value\":2}")));
      f.script.add(reply("done"));
      assertEquals(Execution.Status.SUCCEEDED, f.run().status());
      var turn = f.context.execution().turnId();
      var messages =
          f.store.messages.stream()
              .filter(m -> m.turnId().equals(turn))
              .map(m -> m.message())
              .toList();
      assertEquals(
          List.of(
              ChatMessage.Role.USER,
              ChatMessage.Role.ASSISTANT,
              ChatMessage.Role.TOOL,
              ChatMessage.Role.ASSISTANT,
              ChatMessage.Role.TOOL,
              ChatMessage.Role.ASSISTANT),
          messages.stream().map(ChatMessage::role).toList());
      for (int i = 2; i < messages.size(); i += 2) {
        if (messages.get(i).role() == ChatMessage.Role.TOOL) {
          assertEquals(
              messages.get(i - 1).toolCalls().getFirst().id(), messages.get(i).toolCallId());
        }
      }
      assertEquals(3, f.requests.size());
    }
  }
}
