package io.github.oryxlabs.oryxos.core.execution;

import java.util.UUID;

public record ProcessRequest(
    String channel,
    String userId,
    String agentName,
    UUID sessionId,
    String content,
    Trigger trigger,
    String scheduleId,
    boolean oneShot) {
  public ProcessRequest(
      String channel,
      String userId,
      String agentName,
      UUID sessionId,
      String content,
      Trigger trigger,
      String scheduleId) {
    this(channel, userId, agentName, sessionId, content, trigger, scheduleId, false);
  }

  public enum Trigger {
    MANUAL,
    SCHEDULED
  }

  public static ProcessRequest manual(
      String channel, String user, String agent, UUID session, String text) {
    return new ProcessRequest(channel, user, agent, session, text, Trigger.MANUAL, null);
  }

  @Override
  public String toString() {
    return "ProcessRequest[content withheld]";
  }
}
