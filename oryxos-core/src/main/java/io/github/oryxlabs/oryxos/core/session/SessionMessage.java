package io.github.oryxlabs.oryxos.core.session;

import io.github.oryxlabs.oryxos.core.model.ChatMessage;
import io.github.oryxlabs.oryxos.core.tool.ToolResult;
import java.time.Instant;
import java.util.UUID;

public record SessionMessage(UUID id,UUID sessionId,UUID executionId,UUID turnId,long seq,
                             ChatMessage message,ToolResult.Outcome toolOutcome,Instant createdAt) { }
