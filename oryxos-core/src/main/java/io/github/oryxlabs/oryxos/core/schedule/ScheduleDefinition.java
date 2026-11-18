package io.github.oryxlabs.oryxos.core.schedule;

import io.github.oryxlabs.oryxos.core.agent.AgentProfile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneId;
import java.util.HexFormat;

/** Immutable startup file snapshot. Persistence never supplies cron, message or timezone. */
public record ScheduleDefinition(
    String id,
    String agentName,
    String localId,
    String cron,
    ZoneId timezone,
    String message,
    boolean initiallyEnabled,
    int maxDurationSeconds) {
  public static ScheduleDefinition from(AgentProfile agent, AgentProfile.Schedule schedule) {
    return new ScheduleDefinition(
        agent.name() + ":" + schedule.id(),
        agent.name(),
        schedule.id(),
        schedule.cron(),
        schedule.timezone(),
        schedule.message(),
        schedule.enabled(),
        schedule.maxDurationSeconds());
  }

  public String definitionHash() {
    try {
      var hash = MessageDigest.getInstance("SHA-256");
      for (String value :
          new String[] {
            id,
            cron,
            timezone.getId(),
            message,
            Boolean.toString(initiallyEnabled),
            Integer.toString(maxDurationSeconds)
          }) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        hash.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
        hash.update(bytes);
      }
      return HexFormat.of().formatHex(hash.digest());
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public String toString() {
    return "ScheduleDefinition[id=" + id + "]";
  }
}
