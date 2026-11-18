package io.github.oryxlabs.oryxos.core.runtime;

import java.util.UUID;

public record ChatOptions(String profile, String userId, UUID sessionId, boolean json) {
  public ChatOptions(String profile, String userId, UUID sessionId) {
    this(profile, userId, sessionId, false);
  }
}
