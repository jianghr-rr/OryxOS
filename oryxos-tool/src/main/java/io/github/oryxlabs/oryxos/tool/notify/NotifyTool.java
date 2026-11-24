package io.github.oryxlabs.oryxos.tool.notify;

import io.github.oryxlabs.oryxos.core.execution.ExecutionContext;
import io.github.oryxlabs.oryxos.core.tool.OryxTool;
import io.github.oryxlabs.oryxos.core.tool.ToolDescriptor;
import io.github.oryxlabs.oryxos.core.tool.ToolResult;
import java.util.Map;

/**
 * Built-in {@code notify} tool backed by a channel-neutral {@link NotifyChannelAdapter}.
 *
 * <p>Resolves outbound targets from the current agent profile's {@code notify_channels}
 * configuration and delegates destination authorization before delivery.
 */
public final class NotifyTool implements OryxTool {
  private final NotificationTargetResolver targets;
  private final NotifyChannelAdapter adapter;

  public NotifyTool(NotificationTargetResolver targets, NotifyChannelAdapter adapter) {
    this.targets = targets;
    this.adapter = adapter;
  }

  @Override
  public ToolDescriptor descriptor() {
    return staticDescriptor();
  }

  public static ToolDescriptor staticDescriptor() {
    return new ToolDescriptor(
        "notify",
        "Send content to a notification target declared by this Agent",
        "{\"type\":\"object\",\"properties\":{\"content\":{\"type\":\"string\",\"minLength\":1},\"targetName\":{\"type\":\"string\",\"minLength\":1}},\"required\":[\"content\"],\"additionalProperties\":false}",
        false,
        true,
        null,
        null);
  }

  @Override
  public void authorize(Map<String, Object> a, ExecutionContext c) {
    adapter.authorize(targets.resolve(c.agent(), a.get("targetName")).toNotifyTarget());
  }

  @Override
  public ToolResult execute(Map<String, Object> a, ExecutionContext c) {
    return adapter.send(
        targets.resolve(c.agent(), a.get("targetName")).toNotifyTarget(),
        (String) a.get("content"),
        c);
  }
}
