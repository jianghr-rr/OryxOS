package io.github.oryxlabs.oryxos.tool.notify;

import java.util.Map;
import java.util.Objects;

/**
 * Channel-neutral outbound notification target description.
 *
 * <p>Carries only a channel type discriminator and an immutable configuration map interpreted by
 * the corresponding {@link NotifyChannelAdapter}, keeping channel-specific details out of tool
 * interfaces and agent prompts.
 */
public record NotifyTarget(String channelType, Map<String, Object> config) {

  public NotifyTarget {
    if (channelType == null || channelType.isBlank()) {
      throw new IllegalArgumentException("NotifyTarget channelType must not be blank");
    }
    Objects.requireNonNull(config, "NotifyTarget config must not be null");
    config = Map.copyOf(config);
  }

  @Override
  public String toString() {
    return "NotifyTarget[channelType=" + channelType + ", config=<redacted>]";
  }
}
