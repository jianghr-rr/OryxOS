package io.github.oryxlabs.oryxos.core.session;

import java.time.Instant;
import java.util.*;

public record Session(
    UUID id,
    String channel,
    String userId,
    String agentName,
    String providerName,
    String model,
    Kind kind,
    Status status,
    Instant createdAt,
    Instant archivedAt) {
  public enum Kind {
    CONVERSATION,
    ONE_SHOT
  }

  public enum Status {
    ACTIVE,
    ARCHIVED
  }

  public Session(
      UUID id,
      String channel,
      String userId,
      String agentName,
      String provider,
      String model,
      Kind kind,
      Status status,
      Instant createdAt) {
    this(id, channel, userId, agentName, provider, model, kind, status, createdAt, null);
  }

  public Session {
    Objects.requireNonNull(id);
    Objects.requireNonNull(kind);
    Objects.requireNonNull(status);
    Objects.requireNonNull(createdAt);
    for (String field : Arrays.asList(channel, userId, agentName, providerName, model))
      if (field == null || field.isBlank())
        throw new IllegalArgumentException("Session identity and model snapshot must be non-empty");
  }

  public Session archived(Instant at) {
    return new Session(
        id,
        channel,
        userId,
        agentName,
        providerName,
        model,
        kind,
        Status.ARCHIVED,
        createdAt,
        Objects.requireNonNull(at));
  }
}
