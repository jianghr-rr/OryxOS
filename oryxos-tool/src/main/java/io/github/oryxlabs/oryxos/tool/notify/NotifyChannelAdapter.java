package io.github.oryxlabs.oryxos.tool.notify;

import io.github.oryxlabs.oryxos.core.execution.ExecutionContext;
import io.github.oryxlabs.oryxos.core.tool.ToolResult;

/**
 * Channel-neutral outbound notification adapter contract.
 *
 * <p>Core stage ships {@link WebhookNotifyAdapter} as the sole implementation; future channel
 * integrations (for example, dedicated IM SDKs or email) implement this interface without changing
 * {@link NotifyTool} or caller contracts.
 */
public interface NotifyChannelAdapter {

  /**
   * Validates outbound destination permissions (such as domain allowlist checks) before any
   * external I/O occurs. Unified {@code Sandbox.enforce(...)} enforcement will converge here in
   * Lesson 24.
   */
  void authorize(NotifyTarget target);

  /** Delivers {@code content} to the resolved {@code target} under the current execution budget. */
  ToolResult send(NotifyTarget target, String content, ExecutionContext ctx);
}
