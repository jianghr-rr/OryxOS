package io.github.oryxlabs.oryxos.tool.policy;

import io.github.oryxlabs.oryxos.core.execution.ExecutionContext;
import io.github.oryxlabs.oryxos.core.tool.*;
import java.util.*;

/** Closed retry classification. A transient label alone never makes an external effect safe. */
public final class ToolRetryPolicy {
  private static final Set<String> READS =
      Set.of("read_file", "list_dir", "http_get", "recall_memory", "echo");

  public static ToolDescriptor classify(ToolDescriptor d) {
    return new ToolDescriptor(
        d.name(),
        d.description(),
        d.inputSchemaJson(),
        READS.contains(d.name()) && !d.sideEffecting(),
        d.sideEffecting(),
        d.serverId(),
        d.remoteName(),
        d.source());
  }

  public OryxTool apply(OryxTool tool) {
    var d = tool.descriptor();
    var classified = classify(d);
    boolean safe = classified.safeToRetry();
    return new OryxTool() {
      public ToolDescriptor descriptor() {
        return classified;
      }

      public void authorize(Map<String, Object> a, ExecutionContext c) {
        tool.authorize(a, c);
      }

      public ToolResult execute(Map<String, Object> a, ExecutionContext c) {
        var r = tool.execute(a, c);
        boolean retry =
            safe
                && r.outcome() == ToolResult.Outcome.ERROR
                && r.sideEffectState() == ToolResult.SideEffectState.NONE
                && (r.retryClass() == ToolResult.RetryClass.SAFE_TRANSIENT
                    || d.name().equals("http_get")
                        && Set.of("HTTP_CONNECTION", "HTTP_TIMEOUT")
                            .contains(String.valueOf(r.errorCode())));
        return new ToolResult(
            r.outcome(),
            r.content(),
            r.errorCode(),
            r.safeMessage(),
            retry ? ToolResult.RetryClass.SAFE_TRANSIENT : ToolResult.RetryClass.NONE,
            r.sideEffectState(),
            r.callId(),
            r.attemptCount(),
            r.durationMs());
      }
    };
  }
}
