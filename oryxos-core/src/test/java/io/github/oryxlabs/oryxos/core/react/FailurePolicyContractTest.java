package io.github.oryxlabs.oryxos.core.react;

import static io.github.oryxlabs.oryxos.core.react.LoopFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.oryxlabs.oryxos.core.execution.Execution;
import io.github.oryxlabs.oryxos.core.tool.ToolResult;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Tag("us2-loop")
@Tag("deadline-boundary")
class FailurePolicyContractTest {
  @TempDir Path root;

  @Test
  void unknownSideEffectStopsBeforeModelOrRemainingToolCanRun() throws Exception {
    try (var f = new LoopFixture(root, 10)) {
      var fixture =
          io.github.oryxlabs.oryxos.core.config.SafeYaml.document(
                  getClass().getResourceAsStream("/react/scripted-turns.json").readAllBytes(),
                  "fixture")
              .values();
      var expected = (Map<?, ?>) fixture.get("unknown");
      f.add(
          "write",
          true,
          (a, c) ->
              new ToolResult(
                  ToolResult.Outcome.UNKNOWN,
                  "uncertain",
                  "NETWORK_UNKNOWN",
                  "结果不确定",
                  ToolResult.RetryClass.SAFE_TRANSIENT,
                  ToolResult.SideEffectState.UNKNOWN,
                  null,
                  1,
                  0));
      f.script.add(
          reply(
              null,
              call("write1", "write", "{\"value\":1}"),
              call("next", "second", "{\"value\":1}")));
      f.script.add(reply(null, call("write2", "write", "{\"value\":1}")));
      var result = assertDoesNotThrow(f::run);
      assertEquals(Execution.Status.PARTIAL, result.status());
      assertEquals("SIDE_EFFECT_UNCERTAIN", result.stopReason());
      assertEquals(List.of("write"), f.effects);
      assertEquals(((Number) expected.get("expectedModelCalls")).intValue(), f.requests.size());
      assertEquals(ToolResult.Outcome.NOT_EXECUTED, f.store.messages.getLast().toolOutcome());
      assertTrue(f.delays.isEmpty());
    }
  }

  @Test
  void parameterCorrectionPreservesOriginalErrorAndLinksResolution() throws Exception {
    try (var f = new LoopFixture(root, 10)) {
      f.script.add(reply(null, call("bad", "first", "{\"value\":-1,\"target\":\"same\"}")));
      f.script.add(reply(null, call("fix", "first", "{\"value\":2,\"target\":\"same\"}")));
      f.script.add(reply("done"));
      var result = assertDoesNotThrow(f::run);
      assertEquals(Execution.Status.SUCCEEDED, result.status());
      assertTrue(result.hasToolErrors());
      assertFalse(result.hasUnresolvedToolErrors());
      assertEquals(List.of("first"), f.effects);
      assertEquals(ToolResult.Outcome.ERROR, f.store.messages.get(2).toolOutcome());
      assertTrue(
          f.audit.stream()
              .anyMatch(r ->
                  "bad".equals(r.toolCallId())
                      && "RESOLVED".equals(r.resolutionStatus())
                      && "fix".equals(r.resolvedByCallId())));
    }
  }

  @Test
  void changedValidFieldsOrDeniedUnknownToolsCannotClearErrors() throws Exception {
    try (var f = new LoopFixture(root, 10)) {
      f.script.add(reply(null, call("bad", "first", "{\"value\":-1,\"target\":\"same\"}")));
      f.script.add(reply(null, call("fix", "first", "{\"value\":2,\"target\":\"different\"}")));
      f.script.add(
          reply(
              null,
              call("deny", "first", "{\"value\":1,\"target\":\"denied\"}"),
              call("unknown", "missing", "{}")));
      f.script.add(reply("all successful"));
      var result = assertDoesNotThrow(f::run);
      assertEquals(Execution.Status.PARTIAL, result.status());
      assertTrue(result.hasUnresolvedToolErrors());
      assertEquals(List.of("first"), f.effects);
      assertFalse(f.audit.stream().anyMatch(r -> "RESOLVED".equals(r.resolutionStatus())));
    }
  }

  @Test
  void malformedOrMultipleFailuresCannotBeClearedByLaterSuccess() throws Exception {
    for (boolean multiple : List.of(false, true))
      try (var f = new LoopFixture(root.resolve("case" + multiple), 10)) {
        if (multiple)
          f.script.add(
              reply(
                  null,
                  call("a", "first", "{\"value\":-1}"),
                  call("b", "second", "{\"value\":-1}")));
        else f.script.add(reply(null, call("a", "first", "[")));
        f.script.add(reply(null, call("fix", "first", "{\"value\":1}")));
        f.script.add(reply("done"));
        assertEquals(Execution.Status.PARTIAL, f.run().status());
        assertFalse(f.audit.stream().anyMatch(r -> "RESOLVED".equals(r.resolutionStatus())));
      }
  }

  @Test
  void correctionChainLinksEveryErrorWithoutRewritingOriginalOutcomes() throws Exception {
    try (var f = new LoopFixture(root, 10)) {
      f.script.add(reply(null, call("a", "first", "{\"value\":-2,\"target\":\"same\"}")));
      f.script.add(reply(null, call("b", "first", "{\"value\":-1,\"target\":\"same\"}")));
      f.script.add(reply(null, call("c", "first", "{\"value\":1,\"target\":\"same\"}")));
      f.script.add(reply("done"));
      var result = f.run();
      assertEquals(Execution.Status.SUCCEEDED, result.status());
      assertTrue(result.hasToolErrors());
      assertFalse(result.hasUnresolvedToolErrors());
      for (String id : List.of("a", "b"))
        assertTrue(
            f.audit.stream()
                .anyMatch(r ->
                    id.equals(r.toolCallId())
                        && "ERROR".equals(r.status())
                        && "RESOLVED".equals(r.resolutionStatus())
                        && "c".equals(r.resolvedByCallId())));
      assertEquals(
          2,
          f.store.messages.stream()
              .filter(m -> m.toolOutcome() == ToolResult.Outcome.ERROR)
              .count());
    }
  }

  @Test
  void safeRetrySuccessResolvesItsAttemptsButUnknownNeverRetries() throws Exception {
    try (var f = new LoopFixture(root, 10)) {
      var attempts = new java.util.concurrent.atomic.AtomicInteger();
      f.add(
          "first",
          false,
          (a, c) ->
              attempts.incrementAndGet() == 1
                  ? new ToolResult(
                      ToolResult.Outcome.ERROR,
                      "temporary",
                      "HTTP_503",
                      "temporary",
                      ToolResult.RetryClass.SAFE_TRANSIENT,
                      ToolResult.SideEffectState.NONE,
                      null,
                      0,
                      0)
                  : ToolResult.success("ok"));
      f.script.add(reply(null, call("a", "first", "{\"value\":1}")));
      f.script.add(reply("done"));
      var result = f.run();
      assertEquals(Execution.Status.SUCCEEDED, result.status());
      assertTrue(result.hasToolErrors());
      assertFalse(result.hasUnresolvedToolErrors());
      assertEquals(List.of(250L), f.delays);
      assertEquals(2, attempts.get());
      assertTrue(
          f.audit.stream()
              .anyMatch(r ->
                  r.attempt() == 1
                      && "ERROR".equals(r.status())
                      && "RESOLVED".equals(r.resolutionStatus())));
    }
  }
}
