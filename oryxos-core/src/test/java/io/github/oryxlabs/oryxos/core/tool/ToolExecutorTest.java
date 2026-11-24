package io.github.oryxlabs.oryxos.core.tool;

import static io.github.oryxlabs.oryxos.core.react.LoopFixture.call;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.oryxlabs.oryxos.core.audit.AuditSink;
import io.github.oryxlabs.oryxos.core.audit.ModelCallRecord;
import io.github.oryxlabs.oryxos.core.audit.ToolCallRecord;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.react.LoopFixture;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("us2-loop")
class ToolExecutorTest {
  @TempDir Path root;

  private static ToolExecutor executor(LoopFixture f, AuditSink sink) {
    return new ToolExecutor(f.registry, sink, d -> {});
  }

  private static AuditSink recording(List<ToolCallRecord> records) {
    return new AuditSink() {
      @Override
      public void modelCall(ModelCallRecord r) {}

      @Override
      public void toolCall(ToolCallRecord r) {
        records.add(r);
      }
    };
  }

  @Test
  void successfulToolWritesStartedThenSuccessAudit() throws Exception {
    try (var f = new LoopFixture(root, 10)) {
      var records = new CopyOnWriteArrayList<ToolCallRecord>();
      var attempt =
          executor(f, recording(records)).execute(f.context, call("one", "first", "{\"value\":1}"));
      assertEquals(ToolResult.Outcome.SUCCESS, attempt.result().outcome());
      assertEquals(
          List.of("STARTED", "SUCCESS"), records.stream().map(ToolCallRecord::status).toList());
      assertTrue(records.stream().allMatch(r -> r.toolCallId().equals("one")));
    }
  }

  @Test
  void failedToolWritesFailureAuditWithErrorCode() throws Exception {
    try (var f = new LoopFixture(root, 10)) {
      f.add(
          "first",
          false,
          (args, ctx) -> ToolResult.failure(ToolResult.Outcome.ERROR, "BOOM", "tool failed"));
      var records = new CopyOnWriteArrayList<ToolCallRecord>();
      var attempt =
          executor(f, recording(records)).execute(f.context, call("one", "first", "{\"value\":1}"));
      assertEquals(ToolResult.Outcome.ERROR, attempt.result().outcome());
      assertEquals(
          List.of("STARTED", "ERROR"), records.stream().map(ToolCallRecord::status).toList());
      assertEquals("BOOM", records.getLast().errorCode());
    }
  }

  @Test
  void throwingToolIsAuditedNotSwallowed() throws Exception {
    try (var f = new LoopFixture(root, 10)) {
      f.add(
          "first",
          false,
          (args, ctx) -> {
            throw new IllegalStateException("RAW_EXCEPTION_TEXT");
          });
      var records = new CopyOnWriteArrayList<ToolCallRecord>();
      var attempt =
          executor(f, recording(records)).execute(f.context, call("one", "first", "{\"value\":1}"));
      assertEquals(ToolResult.Outcome.ERROR, attempt.result().outcome());
      assertEquals("TOOL_FAILED", attempt.result().errorCode());
      assertEquals(
          List.of("STARTED", "ERROR"), records.stream().map(ToolCallRecord::status).toList());
      assertEquals("TOOL_FAILED", records.getLast().errorCode());
      assertFalse(
          String.valueOf(records.getLast().resultRedacted()).contains("RAW_EXCEPTION_TEXT"));
    }
  }

  @Test
  void auditWriteFailureIsRethrownAsRecordingFailure() throws Exception {
    try (var f = new LoopFixture(root, 10)) {
      var broken =
          new AuditSink() {
            @Override
            public void modelCall(ModelCallRecord r) {}

            @Override
            public void toolCall(ToolCallRecord r) {
              throw new IllegalStateException("disk full");
            }
          };
      var error =
          assertThrows(
              OryxError.class,
              () -> executor(f, broken).execute(f.context, call("one", "first", "{\"value\":1}")));
      assertEquals("RECORDING_FAILURE", error.code());
      assertTrue(f.effects.isEmpty(), "tool must not run when its intent cannot be recorded");
    }
  }
}
