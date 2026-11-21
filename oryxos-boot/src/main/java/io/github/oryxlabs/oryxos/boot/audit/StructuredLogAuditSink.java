package io.github.oryxlabs.oryxos.boot.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.oryxlabs.oryxos.core.audit.*;
import io.github.oryxlabs.oryxos.core.security.Redactor;
import java.util.*;
import org.slf4j.*;

/** US1–4 structured log evidence only. This is not the US5 SQLite audit implementation. */
public final class StructuredLogAuditSink implements AuditSink {
  private static final Logger LOG = LoggerFactory.getLogger(StructuredLogAuditSink.class);
  private final ObjectMapper json = new ObjectMapper();
  private final Redactor redactor;

  public StructuredLogAuditSink(Redactor redactor) {
    this.redactor = redactor;
  }

  public void toolCall(ToolCallRecord r) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("event", "tool_call");
    fields.put("evidence", "structured_log_not_database");
    fields.put("id", r.id().toString());
    fields.put("executionId", r.executionId().toString());
    fields.put("toolCallId", r.toolCallId());
    fields.put("tool", redactor.redact(r.toolName()));
    fields.put("attempt", r.attempt());
    fields.put("status", r.status());
    fields.put("arguments", redactor.redact(r.argumentsRedacted()));
    fields.put("result", redactor.redact(r.resultRedacted()));
    fields.put("sideEffectState", r.sideEffectState());
    fields.put("resolutionStatus", r.resolutionStatus());
    fields.put("correctionOfCallId", r.correctionOfCallId());
    fields.put("resolvedByCallId", r.resolvedByCallId());
    fields.put("startedAt", r.startedAt().toString());
    fields.put("finishedAt", r.finishedAt() == null ? null : r.finishedAt().toString());
    fields.put("durationMs", r.durationMs());
    fields.put("errorCode", r.errorCode());
    try {
      LOG.info("{}", json.writeValueAsString(fields));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("Cannot serialize safe tool audit projection");
    }
  }

  public void modelCall(ModelCallRecord record) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("event", "model_call");
    fields.put("evidence", "structured_log_not_database");
    fields.put("callId", record.callId().toString());
    fields.put("executionId", record.executionId().toString());
    fields.put("iteration", record.iteration());
    fields.put("attempt", record.attempt());
    fields.put("provider", redactor.redact(record.provider()));
    fields.put("requestedModel", redactor.redact(record.requestedModel()));
    fields.put("actualModel", redactor.redact(record.actualModel()));
    fields.put("status", record.status());
    fields.put("startedAt", record.startedAt().toString());
    fields.put("finishedAt", record.finishedAt() == null ? null : record.finishedAt().toString());
    fields.put("durationMs", record.durationMs());
    fields.put("inputTokens", record.inputTokens());
    fields.put("outputTokens", record.outputTokens());
    fields.put("errorCode", record.errorCode());
    try {
      LOG.info("{}", json.writeValueAsString(fields));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("Cannot serialize safe model audit projection");
    }
  }
}
