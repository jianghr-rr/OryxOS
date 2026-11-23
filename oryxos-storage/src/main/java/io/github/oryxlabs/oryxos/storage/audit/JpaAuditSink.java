package io.github.oryxlabs.oryxos.storage.audit;

import io.github.oryxlabs.oryxos.core.audit.*;
import io.github.oryxlabs.oryxos.core.runtime.RuntimeTiming;
import io.github.oryxlabs.oryxos.core.security.Redactor;
import io.github.oryxlabs.oryxos.storage.transaction.StoredSql;
import io.github.oryxlabs.oryxos.storage.transaction.WorkspaceWriteCoordinator;
import java.time.Instant;
import java.util.*;

/**
 * One row per physical attempt; later resolution adds links without rewriting the original outcome.
 */
public final class JpaAuditSink implements AuditSink {
  private final WorkspaceWriteCoordinator writes;
  private final Redactor redactor;

  public JpaAuditSink(WorkspaceWriteCoordinator writes, Redactor redactor) {
    this.writes = writes;
    this.redactor = redactor;
  }

  private static final String COUNT_TOOL_LINK =
      "select count(*) from tool_invocations where execution_id=? and tool_call_id=?";

  public void modelCall(ModelCallRecord r) {
    try (var timing = RuntimeTiming.measure("audit.model")) {
      Object[] prepared =
          new Object[] {
            r.callId().toString(),
            r.executionId().toString(),
            r.iteration(),
            r.attempt(),
            safe(r.provider()),
            safe(r.requestedModel()),
            safe(r.actualModel() == null ? r.requestedModel() : r.actualModel()),
            r.status(),
            r.startedAt().toString(),
            text(r.finishedAt()),
            r.durationMs(),
            number(r.inputTokens()),
            number(r.outputTokens()),
            safe(r.errorCode())
          };
      Runnable onFailure =
          () ->
              writes.failedAction(
                  new io.github.oryxlabs.oryxos.core.runtime.IncidentDetail.Action(
                      r.executionId(),
                      r.callId(),
                      null,
                      "MODEL",
                      safe(r.provider()),
                      r.status().equals("STARTED")
                          ? "NOT_EXECUTED"
                          : r.status().equals("SUCCEEDED") ? "SUCCESS" : "ERROR",
                      "NONE",
                      "Observed in memory; the required model audit was not committed"));
      if ("SUCCEEDED".equals(r.status())) {
        writes.deferOrWrite(
            em -> {
              int changed = StoredSql.update(em, MODEL, prepared);
              if (changed != 1)
                throw new IllegalStateException("Attempt already closed or foreign");
            },
            onFailure,
            new io.github.oryxlabs.oryxos.core.error.OryxError(
                "PARTIAL_RECORDING_FAILURE",
                "Model attempt may have occurred but its record could not be saved; do not replay"
                    + " automatically"));
        return;
      }
      try {
        writes.writeImmediate(em -> {
          int changed = StoredSql.update(em, MODEL, prepared);
          if (changed != 1) throw new IllegalStateException("Attempt already closed or foreign");
          return null;
        });
      } catch (RuntimeException error) {
        onFailure.run();
        throw error;
      }
    }
  }

  public void toolCall(ToolCallRecord r) {
    try (var timing = RuntimeTiming.measure("audit.tool")) {
      Object[] prepared =
          new Object[] {
            r.id().toString(),
            r.executionId().toString(),
            r.toolCallId(),
            safe(r.toolName()),
            r.attempt(),
            r.status(),
            safe(r.argumentsRedacted()),
            safe(r.resultRedacted()),
            r.sideEffectState().name(),
            r.resolutionStatus(),
            r.correctionOfCallId(),
            r.resolvedByCallId(),
            r.startedAt().toString(),
            text(r.finishedAt()),
            r.finishedAt() == null ? null : r.durationMs(),
            safe(r.errorCode())
          };
      Runnable onFailure =
          () ->
              writes.failedAction(
                  new io.github.oryxlabs.oryxos.core.runtime.IncidentDetail.Action(
                      r.executionId(),
                      r.id(),
                      r.toolCallId(),
                      "TOOL",
                      safe(r.toolName()),
                      r.status().equals("STARTED") ? "NOT_EXECUTED" : r.status(),
                      r.sideEffectState().name(),
                      "Observed in memory; the required tool audit was not committed"));
      java.util.function.Consumer<jakarta.persistence.EntityManager> action =
          em -> {
            if (r.correctionOfCallId() != null)
              verifyToolLink(em, r.executionId(), r.correctionOfCallId());
            if (r.resolvedByCallId() != null)
              verifyToolLink(em, r.executionId(), r.resolvedByCallId());
            int changed = StoredSql.update(em, TOOL, prepared);
            if (changed != 1)
              throw new IllegalStateException("Attempt identity/outcome is immutable");
          };
      if ("STARTED".equals(r.status())) {
        try {
          writes.writeImmediate(em -> {
            action.accept(em);
            return null;
          });
        } catch (RuntimeException error) {
          onFailure.run();
          throw error;
        }
        return;
      }
      writes.deferOrWrite(
          action,
          onFailure,
          new io.github.oryxlabs.oryxos.core.error.OryxError(
              "RECORDING_FAILURE", "Tool audit could not be saved; workspace paused"));
    }
  }

  private static void verifyToolLink(
      jakarta.persistence.EntityManager em, UUID executionId, String link) {
    long n =
        StoredSql.query(
            em,
            COUNT_TOOL_LINK,
            rows -> rows.next() ? rows.getLong(1) : 0L,
            executionId.toString(),
            link);
    if (n == 0) throw new IllegalArgumentException("Correction must refer to the same execution");
  }

  private static final String MODEL =
      StoredSql.upsert(
          "llm_calls",
          "id,execution_id,iteration,attempt,provider,requested_model,model,status,started_at,finished_at,duration_ms,input_tokens,output_tokens,error_code",
          "llm_calls.execution_id=excluded.execution_id AND llm_calls.status='STARTED'");
  private static final String TOOL =
      StoredSql.upsert(
          "tool_invocations",
          "id,execution_id,tool_call_id,tool_name,attempt,status,args_redacted,result_redacted,side_effect_state,resolution_status,correction_of_call_id,resolved_by_call_id,started_at,finished_at,duration_ms,error_code",
          "tool_invocations.execution_id=excluded.execution_id AND"
              + " tool_invocations.tool_call_id=excluded.tool_call_id AND"
              + " (tool_invocations.status='STARTED' OR tool_invocations.status=excluded.status)");

  private String safe(String value) {
    return redactor.redact(value);
  }

  private static String text(Instant value) {
    return value == null ? null : value.toString();
  }

  private static Long number(Integer value) {
    return value == null ? null : value.longValue();
  }
}
