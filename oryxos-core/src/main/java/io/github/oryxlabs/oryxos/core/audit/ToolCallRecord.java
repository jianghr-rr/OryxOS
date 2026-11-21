package io.github.oryxlabs.oryxos.core.audit;

import io.github.oryxlabs.oryxos.core.tool.ToolResult;
import java.time.Instant;
import java.util.*;

public record ToolCallRecord(
    UUID id,
    UUID executionId,
    String toolCallId,
    String toolName,
    int attempt,
    String status,
    String argumentsRedacted,
    String resultRedacted,
    ToolResult.SideEffectState sideEffectState,
    String resolutionStatus,
    String correctionOfCallId,
    String resolvedByCallId,
    Instant startedAt,
    Instant finishedAt,
    long durationMs,
    String errorCode) {}
