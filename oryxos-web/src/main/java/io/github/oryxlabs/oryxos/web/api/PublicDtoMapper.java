package io.github.oryxlabs.oryxos.web.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.oryxlabs.oryxos.core.execution.Execution;
import io.github.oryxlabs.oryxos.core.runtime.*;
import io.github.oryxlabs.oryxos.core.schedule.ScheduleCoordinator;
import io.github.oryxlabs.oryxos.core.security.Redactor;
import io.github.oryxlabs.oryxos.core.session.*;
import java.util.*;

/**
 * Allowlisted DTO fields only. Never serializes ORM entities, opaque continuations or config maps.
 */
public final class PublicDtoMapper {
  private final Redactor redactor;
  private final ObjectMapper json = new ObjectMapper();

  public PublicDtoMapper(Redactor redactor) {
    this.redactor = redactor;
  }

  public String safe(String value) {
    return redactor.redact(value);
  }

  private String text(String v) {
    return v == null ? "" : safe(v);
  }

  public ContractDtos.Identity identity(String channel, String user, String agent) {
    return new ContractDtos.Identity(channel, safe(user), safe(agent));
  }

  public ContractDtos.Session session(Session s) {
    return new ContractDtos.Session(
        s.id(),
        identity(s.channel(), s.userId(), s.agentName()),
        s.kind().name(),
        s.status().name(),
        safe(s.providerName()),
        safe(s.model()),
        s.createdAt(),
        s.archivedAt());
  }

  public ContractDtos.PublicMessage message(SessionMessage s) {
    var m = s.message();
    return new ContractDtos.PublicMessage(
        s.id(),
        s.seq(),
        s.executionId(),
        s.turnId(),
        m.role().name(),
        text(m.content()),
        m.toolCalls().stream()
            .map(c ->
                new ContractDtos.PublicMessageToolCallsItem(
                    safe(c.id()),
                    text(c.name() == null ? c.wireName() : c.name()),
                    arguments(c.argumentsJson())))
            .toList(),
        safe(m.toolCallId()),
        s.toolOutcome() == null ? null : s.toolOutcome().name(),
        s.createdAt());
  }

  public ContractDtos.Execution execution(
      Execution.Snapshot s,
      List<ContractDtos.LlmAttempt> llm,
      List<ContractDtos.ToolAttempt> tools) {
    var error =
        s.errorCode() == null
            ? null
            : new ContractDtos.ErrorDetail(
                s.errorCode(),
                "Execution ended: " + s.status(),
                s.id(),
                s.recordingStatus().name(),
                null);
    return new ContractDtos.Execution(
        s.id(),
        s.sessionId(),
        identity(s.channel(), s.userId(), s.agentName()),
        safe(s.scheduleId()),
        s.status().name(),
        safe(s.stopReason()),
        s.recordingStatus().name(),
        s.inFlightCount(),
        safe(s.finalContent()),
        s.hasToolErrors(),
        s.startedAt(),
        s.deadlineAt(),
        s.finishedAt(),
        llm,
        tools,
        error,
        s.hasUnresolvedToolErrors(),
        s.timezone().getId());
  }

  public ContractDtos.LlmAttempt llm(Map<String, Object> r) {
    return new ContractDtos.LlmAttempt(
        UUID.fromString(str(r, "id")),
        number(r, "iteration").intValue(),
        str(r, "provider"),
        str(r, "model"),
        modelStatus(str(r, "status")),
        number(r, "duration_ms"),
        number(r, "input_tokens"),
        number(r, "output_tokens"),
        str(r, "error_code"));
  }

  public ContractDtos.ToolAttempt tool(Map<String, Object> r) {
    return new ContractDtos.ToolAttempt(
        UUID.fromString(str(r, "id")),
        str(r, "tool_call_id"),
        str(r, "tool_name"),
        publicAttempt(number(r, "attempt").intValue()),
        str(r, "status"),
        str(r, "side_effect_state"),
        number(r, "duration_ms"),
        text(str(r, "result_redacted")),
        str(r, "error_code"),
        str(r, "resolution_status"),
        str(r, "correction_of_call_id"),
        str(r, "resolved_by_call_id"));
  }

  // The engine's zero denotes pre-dispatch validation (zero IO), not a retry. The HTTP
  // contract numbers that proposal's first audit entry as 1; outcome/effect evidence is unchanged.
  private static int publicAttempt(int attempt) {
    return attempt == 0 ? 1 : attempt;
  }

  private static String modelStatus(String status) {
    return switch (status) {
      case "SUCCEEDED" -> "SUCCESS";
      case "FAILED" -> "ERROR";
      default -> status;
    };
  }

  private String str(Map<String, Object> r, String k) {
    return r.get(k) == null ? null : safe(r.get(k).toString());
  }

  private Long number(Map<String, Object> r, String k) {
    return r.get(k) == null ? null : ((Number) r.get(k)).longValue();
  }

  public ContractDtos.MemoryEntry memory(io.github.oryxlabs.oryxos.core.memory.MemoryEntry m) {
    return new ContractDtos.MemoryEntry(
        m.id(), m.scope().name(), safe(m.rawContent()), m.createdAt(), safe(m.sourceAgentName()));
  }

  public ContractDtos.Schedule schedule(ScheduleCoordinator.View view) {
    var s = view.state();
    var d = view.definition();
    return new ContractDtos.Schedule(
        s.id(),
        s.agentName(),
        d == null ? "" : d.cron(),
        d == null ? "" : d.timezone().getId(),
        d == null ? "" : safe(d.message()),
        s.configuredPresent(),
        s.enabled(),
        view.nextRunAt(),
        s.lastExecutionId(),
        view.lastStatus());
  }

  public ContractDtos.ScheduleEvent event(io.github.oryxlabs.oryxos.core.schedule.ScheduleEvent e) {
    return new ContractDtos.ScheduleEvent(
        e.id(),
        e.scheduleId(),
        e.executionId(),
        e.triggerKind().name(),
        e.status().name(),
        safe(e.reason()),
        e.createdAt());
  }

  public ContractDtos.RuntimeState runtime(RecoveryPort.State s) {
    return new ContractDtos.RuntimeState(
        s.workspaceId(),
        s.state(),
        s.storageWritable(),
        s.incidentId(),
        safe(s.safeReason()),
        s.inFlightCount(),
        s.recoveryRequired(),
        s.currentIncident() == null ? null : incident(s.currentIncident()));
  }

  private ContractDtos.IncidentDetail incident(IncidentDetail i) {
    return new ContractDtos.IncidentDetail(
        i.id(),
        i.state(),
        i.detectedAt(),
        safe(i.safeReason()),
        i.affectedExecutionIds(),
        i.knownActions().stream().map(this::action).toList(),
        i.unknownActions().stream().map(this::action).toList(),
        safe(i.missingRecordSummary()),
        i.evidenceSources().stream().sorted().toList(),
        i.detailsComplete());
  }

  private ContractDtos.IncidentAction action(IncidentDetail.Action a) {
    return new ContractDtos.IncidentAction(
        a.executionId(),
        a.attemptId(),
        safe(a.toolCallId()),
        a.kind(),
        safe(a.name()),
        a.outcome(),
        a.sideEffectState(),
        safe(a.safeSummary()));
  }

  public Map<String, Object> arguments(String source) {
    try {
      return safeMap(json.readValue(source, new TypeReference<Map<String, Object>>() {}));
    } catch (Exception e) {
      return Map.of("_unavailable", "Arguments could not be represented as an object");
    }
  }

  /** Schema property names may themselves be token/password; retain their schema structure. */
  public Map<String, Object> schema(String source) {
    try {
      return schemaMap(json.readValue(source, new TypeReference<Map<String, Object>>() {}));
    } catch (Exception e) {
      throw new io.github.oryxlabs.oryxos.core.error.OryxError(
          "INTERNAL_ERROR", "Registered tool schema is unavailable");
    }
  }

  private Map<String, Object> schemaMap(Map<String, Object> source) {
    var result = new LinkedHashMap<String, Object>();
    source.forEach((k, v) -> result.put(safe(k), schemaValue(v)));
    return result;
  }

  private Object schemaValue(Object v) {
    if (v instanceof String s) return safe(s);
    if (v instanceof Map<?, ?> m) {
      var values = new LinkedHashMap<String, Object>();
      m.forEach((k, x) -> values.put(k.toString(), x));
      return schemaMap(values);
    }
    if (v instanceof List<?> a) return a.stream().map(this::schemaValue).toList();
    return v;
  }

  public Map<String, Object> safeMap(Map<String, Object> source) {
    var result = new LinkedHashMap<String, Object>();
    source.forEach((k, v) -> {
      if (!Set.of("providerOpaque", "provider_opaque", "reasoning_content").contains(k))
        result.put(
            safe(k),
            k.matches("(?i)(authorization|api[_-]?key|token|password)")
                ? "[REDACTED]"
                : safeValue(v));
    });
    return result;
  }

  private Object safeValue(Object v) {
    if (v instanceof String s) return safe(s);
    if (v instanceof Map<?, ?> m) {
      var values = new LinkedHashMap<String, Object>();
      m.forEach((k, x) -> values.put(k.toString(), x));
      return safeMap(values);
    }
    if (v instanceof List<?> a) return a.stream().map(this::safeValue).toList();
    return v;
  }
}
