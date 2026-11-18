package io.github.oryxlabs.oryxos.core.schedule;

import java.time.Instant;
import java.util.UUID;

public record ScheduleState(
    String id,
    String agentName,
    String definitionHash,
    boolean enabled,
    boolean configuredPresent,
    UUID lastExecutionId,
    Instant updatedAt) {}
