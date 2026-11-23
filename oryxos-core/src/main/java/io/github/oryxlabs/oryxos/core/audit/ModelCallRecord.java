package io.github.oryxlabs.oryxos.core.audit;

import java.time.Instant;
import java.util.UUID;

/** Safe projection only: no task text, arguments, headers, URLs, response body or opaque data. */
public record ModelCallRecord(
    UUID callId,
    UUID executionId,
    int iteration,
    int attempt,
    String provider,
    String requestedModel,
    String actualModel,
    String status,
    Instant startedAt,
    Instant finishedAt,
    Long durationMs,
    Integer inputTokens,
    Integer outputTokens,
    String errorCode) {}
