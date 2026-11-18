package io.github.oryxlabs.oryxos.channel.cli;

import com.fasterxml.jackson.databind.*;
import io.github.oryxlabs.oryxos.core.execution.Execution;
import io.github.oryxlabs.oryxos.core.security.Redactor;
import io.github.oryxlabs.oryxos.core.session.Session;
import java.io.PrintWriter;
import java.time.Instant;
import java.util.*;

/** Public CLI projections; never serialize private execution snapshots or model continuations. */
public final class CliOutput {
  private static final ObjectMapper JSON =
      new ObjectMapper()
          .findAndRegisterModules()
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  private CliOutput() {}

  public static Map<String, Object> fields(Object... pairs) {
    var result = new LinkedHashMap<String, Object>();
    for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
    return result;
  }

  public static void json(PrintWriter out, String code, String message, Object data) {
    try {
      out.println(
          JSON.writeValueAsString(
              fields(
                  "code",
                  code,
                  "message",
                  message,
                  "data",
                  data,
                  "timestamp",
                  Instant.now(),
                  "traceId",
                  UUID.randomUUID().toString())));
      out.flush();
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Cannot encode public CLI output");
    }
  }

  public static Object safe(Object value, Redactor redactor) {
    if (value instanceof String s) return redactor.redact(s);
    if (value instanceof Map<?, ?> m) {
      var result = new LinkedHashMap<String, Object>();
      m.forEach((k, v) -> result.put(k.toString(), safe(v, redactor)));
      return result;
    }
    if (value instanceof Collection<?> c) return c.stream().map(v -> safe(v, redactor)).toList();
    return value;
  }

  public static Object schema(String value, Redactor redactor) {
    try {
      return safe(JSON.readValue(value, Object.class), redactor);
    } catch (Exception e) {
      throw new IllegalStateException("Invalid registered tool schema");
    }
  }

  public static Object session(Session s, Redactor r) {
    return safe(
        fields(
            "id",
            s.id(),
            "identity",
            fields("channel", s.channel(), "userId", s.userId(), "agentName", s.agentName()),
            "kind",
            s.kind(),
            "status",
            s.status(),
            "providerName",
            s.providerName(),
            "model",
            s.model(),
            "createdAt",
            s.createdAt(),
            "archivedAt",
            s.archivedAt()),
        r);
  }

  public static Object execution(Execution.Snapshot s, Redactor r) {
    var data =
        fields(
            "id",
            s.id(),
            "sessionId",
            s.sessionId(),
            "identity",
            fields("channel", s.channel(), "userId", s.userId(), "agentName", s.agentName()),
            "scheduleId",
            s.scheduleId(),
            "status",
            s.status(),
            "stopReason",
            s.stopReason(),
            "recordingStatus",
            s.recordingStatus(),
            "inFlightCount",
            s.inFlightCount(),
            "content",
            s.finalContent(),
            "hasToolErrors",
            s.hasToolErrors(),
            "hasUnresolvedToolErrors",
            s.hasUnresolvedToolErrors(),
            "startedAt",
            s.startedAt(),
            "deadlineAt",
            s.deadlineAt(),
            "finishedAt",
            s.finishedAt(),
            "timezone",
            s.timezone().getId(),
            "error",
            s.errorCode() == null
                ? null
                : fields(
                    "code",
                    s.errorCode(),
                    "message",
                    "Execution ended: " + s.status(),
                    "executionId",
                    s.id(),
                    "recordingStatus",
                    s.recordingStatus()));
    if (s.errorCode() == null) data.remove("error");
    return safe(data, r);
  }

  public static String executionCode(Execution.Snapshot s, boolean waitExpired) {
    if (waitExpired
        || s.status() == Execution.Status.TIMED_OUT
        || s.recordingStatus() == Execution.RecordingStatus.UNKNOWN) return "EXECUTION_TIMEOUT";
    if (s.recordingStatus() == Execution.RecordingStatus.INCOMPLETE
        || s.status() == Execution.Status.PARTIAL_RECORDING_FAILURE)
      return "PARTIAL_RECORDING_FAILURE";
    if (s.status() == Execution.Status.FAILED || s.status() == Execution.Status.INTERRUPTED) {
      if (s.errorCode() != null && s.errorCode().startsWith("PROVIDER_"))
        return "PROVIDER_UNAVAILABLE";
      if (Set.of("EXECUTION_PAUSED", "MEMORY_UNAVAILABLE", "OVERLOADED")
          .contains(String.valueOf(s.errorCode()))) return s.errorCode();
      return "EXECUTION_FAILED";
    }
    return "OK";
  }

  public static int exitCode(String code) {
    return switch (code) {
      case "INVALID_INPUT", "MESSAGE_TOO_LARGE", "CONFIGURATION_ERROR", "INVALID_CURSOR" -> 2;
      case "SESSION_BUSY",
              "WORKSPACE_BUSY",
              "SESSION_IDENTITY_MISMATCH",
              "SESSION_MODEL_CHANGED",
              "SESSION_ARCHIVED",
              "PROFILE_EXISTS" ->
          4;
      case "MODEL_FAILED", "TOOL_FAILED", "EXECUTION_FAILED", "PARTIAL" -> 5;
      case "DEADLINE", "TIMEOUT", "EXECUTION_TIMEOUT", "PROVIDER_TIMEOUT" -> 6;
      case "RECORDING_FAILURE", "EXECUTION_PAUSED", "RUNTIME_NOT_READY" -> 7;
      default -> 3;
    };
  }

  public static int exitCode(Execution.Snapshot result, boolean waitExpired) {
    if (result.recordingStatus() == Execution.RecordingStatus.INCOMPLETE) return 7;
    if (waitExpired
        || result.status() == Execution.Status.TIMED_OUT
        || result.recordingStatus() == Execution.RecordingStatus.UNKNOWN) return 6;
    return switch (result.status()) {
      case SUCCEEDED -> 0;
      case PARTIAL_RECORDING_FAILURE -> 7;
      default -> 5;
    };
  }
}
