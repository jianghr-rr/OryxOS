package io.github.oryxlabs.oryxos.web.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.*;

/** Explicit public projection of the 44 OpenAPI schemas. Envelopes share ApiResponse<T>. */
public final class ContractDtos {
  private ContractDtos() {}

  public record ErrorDetail(
      String code,
      String message,
      @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true) UUID executionId,
      @Schema(
              requiredMode = Schema.RequiredMode.NOT_REQUIRED,
              nullable = true,
              allowableValues = {"COMPLETE", "INCOMPLETE", "UNKNOWN"})
          String recordingStatus,
      @JsonInclude(JsonInclude.Include.NON_NULL)
          @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
          Map<String, Object> details) {}

  public record Identity(
      @Schema(allowableValues = {"http", "cli", "scheduler"}) String channel,
      String userId,
      String agentName) {}

  public record CreateSession(@Schema(minLength = 1) String agentName) {}

  public record MessageInput(@Schema(minLength = 1, maxLength = 32768) String content) {}

  public record Session(
      UUID id,
      Identity identity,
      @Schema(allowableValues = {"CONVERSATION", "ONE_SHOT"}) String kind,
      @Schema(allowableValues = {"ACTIVE", "ARCHIVED"}) String status,
      String providerName,
      String model,
      Instant createdAt,
      @Schema(nullable = true) Instant archivedAt) {}

  public record PublicMessage(
      UUID id,
      @Schema(format = "int64") Long seq,
      UUID executionId,
      UUID turnId,
      @Schema(allowableValues = {"USER", "ASSISTANT", "TOOL"}) String role,
      String content,
      @JsonInclude(JsonInclude.Include.NON_NULL)
          @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
          List<PublicMessageToolCallsItem> toolCalls,
      @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true) String toolCallId,
      @Schema(
              requiredMode = Schema.RequiredMode.NOT_REQUIRED,
              nullable = true,
              allowableValues = {"SUCCESS", "ERROR", "DENIED", "UNKNOWN", "NOT_EXECUTED"})
          String toolOutcome,
      Instant createdAt) {}

  public record SessionHistory(
      Session session,
      List<PublicMessage> messages,
      @Schema(nullable = true, format = "int64") Long nextBeforeSeq) {}

  public record ToolAttempt(
      UUID attemptId,
      String toolCallId,
      String name,
      @Schema(minimum = "1", maximum = "4") Integer attempt,
      @Schema(
              allowableValues = {
                "STARTED",
                "SUCCESS",
                "ERROR",
                "DENIED",
                "UNKNOWN",
                "NOT_EXECUTED"
              })
          String outcome,
      @Schema(allowableValues = {"NONE", "CONFIRMED", "UNKNOWN"}) String sideEffectState,
      @Schema(nullable = true, minimum = "0") Long durationMs,
      String safeResult,
      @Schema(nullable = true) String errorCode,
      @Schema(allowableValues = {"NOT_APPLICABLE", "UNRESOLVED", "RESOLVED"})
          String resolutionStatus,
      @Schema(nullable = true) String correctionOfCallId,
      @Schema(nullable = true) String resolvedByCallId) {}

  public record LlmAttempt(
      UUID attemptId,
      @Schema(minimum = "1") Integer iteration,
      String providerName,
      String model,
      @Schema(allowableValues = {"STARTED", "SUCCESS", "ERROR", "UNKNOWN"}) String status,
      @Schema(nullable = true, minimum = "0") Long durationMs,
      @Schema(nullable = true, minimum = "0") Long inputTokens,
      @Schema(nullable = true, minimum = "0") Long outputTokens,
      @Schema(nullable = true) String errorCode) {}

  public record Execution(
      UUID id,
      UUID sessionId,
      Identity identity,
      @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true) String scheduleId,
      @Schema(
              allowableValues = {
                "RUNNING",
                "SUCCEEDED",
                "PARTIAL",
                "FAILED",
                "TIMED_OUT",
                "PARTIAL_RECORDING_FAILURE",
                "INTERRUPTED"
              })
          String status,
      @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true) String stopReason,
      @Schema(allowableValues = {"COMPLETE", "INCOMPLETE", "UNKNOWN"}) String recordingStatus,
      @Schema(minimum = "0") Integer inFlightCount,
      @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true) String content,
      Boolean hasToolErrors,
      Instant startedAt,
      Instant deadlineAt,
      @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true) Instant finishedAt,
      @JsonInclude(JsonInclude.Include.NON_NULL)
          @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
          List<LlmAttempt> llmCalls,
      @JsonInclude(JsonInclude.Include.NON_NULL)
          @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
          List<ToolAttempt> toolAttempts,
      @JsonInclude(JsonInclude.Include.NON_NULL)
          @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
          ErrorDetail error,
      Boolean hasUnresolvedToolErrors,
      String timezone) {}

  public record Agent(
      String name,
      String description,
      @Schema(allowableValues = {"AVAILABLE", "UNAVAILABLE"}) String availability,
      String providerName,
      String model,
      List<String> tools,
      List<String> notificationTargets,
      List<String> scheduleIds,
      String loadedRevision,
      String bodyRevision,
      @Schema(nullable = true) String safeError) {}

  public record Tool(
      String name,
      String description,
      @Schema(allowableValues = {"BUILTIN", "NATIVE", "MCP"}) String source,
      Boolean available,
      Map<String, Object> inputSchema,
      Boolean safeRetry) {}

  public record MemoryEntry(
      UUID id,
      @Schema(allowableValues = {"CORE", "ARCHIVAL"}) String scope,
      String content,
      Instant createdAt,
      @Schema(nullable = true) String sourceAgentName) {}

  public record MemoryView(
      @Schema(allowableValues = {"md", "sqlite", "mem0"}) String backend,
      List<MemoryEntry> core,
      List<MemoryEntry> archival,
      @Schema(nullable = true) String nextCursor,
      @Schema(allowableValues = {"true"}) Boolean sharedAcrossAgents) {}

  public record Schedule(
      String id,
      String agentName,
      String cron,
      String timezone,
      String message,
      Boolean configuredPresent,
      Boolean enabled,
      @Schema(nullable = true) Instant nextRunAt,
      @Schema(nullable = true) UUID lastExecutionId,
      @Schema(nullable = true) String lastStatus) {}

  public record ScheduleEvent(
      UUID id,
      String scheduleId,
      @Schema(nullable = true) UUID executionId,
      @Schema(allowableValues = {"SCHEDULED", "MANUAL"}) String triggerKind,
      @Schema(allowableValues = {"DISPATCHED", "SKIPPED_OVERLAP", "SKIPPED_PAUSED", "FAILED"})
          String status,
      @Schema(nullable = true) String reason,
      Instant createdAt) {}

  public record EnabledInput(Boolean enabled) {}

  public record RuntimeState(
      UUID workspaceId,
      @Schema(allowableValues = {"READY", "PAUSED_RECORDING", "CONFIGURATION_ERROR", "STARTING"})
          String state,
      Boolean storageWritable,
      @Schema(nullable = true) UUID incidentId,
      @Schema(nullable = true) String safeReason,
      @Schema(minimum = "0") Integer inFlightCount,
      Boolean recoveryRequired,
      @JsonInclude(JsonInclude.Include.NON_NULL)
          @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
          IncidentDetail currentIncident) {}

  public record RecoverInput(
      UUID incidentId,
      @Schema(allowableValues = {"true"}) Boolean acknowledgeMissingRecords,
      @Schema(minLength = 1, maxLength = 2000) String notes) {}

  public record Health(Boolean live, Boolean ready, String state) {}

  public record Info(
      String version,
      UUID workspaceId,
      List<String> providerNames,
      @Schema(minimum = "0") Integer agentCount,
      @Schema(minimum = "0") Integer toolCount,
      @Schema(allowableValues = {"md", "sqlite", "mem0"}) String memoryBackend,
      String timezone) {}

  public record SessionPage(List<Session> items, @Schema(nullable = true) String nextCursor) {}

  public record ExecutionPage(List<Execution> items, @Schema(nullable = true) String nextCursor) {}

  public record AgentPage(List<Agent> items, @Schema(nullable = true) String nextCursor) {}

  public record ToolPage(List<Tool> items, @Schema(nullable = true) String nextCursor) {}

  public record SchedulePage(List<Schedule> items, @Schema(nullable = true) String nextCursor) {}

  public record ScheduleEventPage(
      List<ScheduleEvent> items, @Schema(nullable = true) String nextCursor) {}

  public record IncidentAction(
      @Schema(nullable = true) UUID executionId,
      @Schema(nullable = true) UUID attemptId,
      @Schema(nullable = true) String toolCallId,
      @Schema(allowableValues = {"MODEL", "TOOL"}) String kind,
      @Schema(nullable = true) String name,
      @Schema(allowableValues = {"SUCCESS", "ERROR", "DENIED", "UNKNOWN", "NOT_EXECUTED"})
          String outcome,
      @Schema(allowableValues = {"NONE", "CONFIRMED", "UNKNOWN"}) String sideEffectState,
      String safeSummary) {}

  public record IncidentDetail(
      UUID id,
      @Schema(allowableValues = {"OPEN", "ACKNOWLEDGED"}) String state,
      Instant detectedAt,
      String safeReason,
      @ArraySchema(uniqueItems = true) List<UUID> affectedExecutionIds,
      List<IncidentAction> knownActions,
      List<IncidentAction> unknownActions,
      String missingRecordSummary,
      @ArraySchema(
              minItems = 1,
              uniqueItems = true,
              schema =
                  @Schema(allowableValues = {"MEMORY", "FAULT_MARKER", "RUN_MARKER", "DATABASE"}))
          List<String> evidenceSources,
      Boolean detailsComplete) {}

  @Schema(schemaResolution = Schema.SchemaResolution.INLINE)
  public record PublicMessageToolCallsItem(String id, String name, Map<String, Object> arguments) {}
}
