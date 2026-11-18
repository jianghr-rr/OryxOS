package io.github.oryxlabs.oryxos.tool.http;

import io.github.oryxlabs.oryxos.core.execution.ExecutionContext;
import io.github.oryxlabs.oryxos.core.tool.*;
import java.util.Map;

public final class HttpGetTool implements OryxTool, AutoCloseable {
  private final ControlledHttpClient http;
  private final boolean owns;

  public HttpGetTool(HttpTargetPolicy policy, int connect, int read, int max) {
    http = new ControlledHttpClient(policy, connect, read, max);
    owns = true;
  }

  public HttpGetTool(ControlledHttpClient http) {
    this.http = http;
    owns = false;
  }

  public ToolDescriptor descriptor() {
    return staticDescriptor();
  }

  public static ToolDescriptor staticDescriptor() {
    return new ToolDescriptor(
        "http_get",
        "Read an explicitly allowed HTTP URL",
        "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\",\"minLength\":1}},\"required\":[\"url\"],\"additionalProperties\":false}",
        true,
        false,
        null,
        null);
  }

  public void authorize(Map<String, Object> a, ExecutionContext c) {
    http.authorize((String) a.get("url"));
  }

  public ToolResult execute(Map<String, Object> a, ExecutionContext c) {
    try {
      var reply = http.exchange("GET", (String) a.get("url"), Map.of(), null, c);
      if (reply.status() >= 200 && reply.status() < 300)
        return ToolResult.success("status=" + reply.status() + "\n" + reply.body());
      boolean retry = reply.status() == 429 || reply.status() >= 500;
      return new ToolResult(
          ToolResult.Outcome.ERROR,
          "HTTP request failed",
          "HTTP_" + reply.status(),
          "HTTP request failed",
          retry ? ToolResult.RetryClass.SAFE_TRANSIENT : ToolResult.RetryClass.NONE,
          ToolResult.SideEffectState.NONE,
          null,
          1,
          0);
    } catch (ControlledHttpClient.TransportFailure e) {
      return ToolResult.failure(
          e.code().equals("TARGET_DENIED") ? ToolResult.Outcome.DENIED : ToolResult.Outcome.ERROR,
          e.code(),
          "HTTP read failed; no implicit retry was made");
    }
  }

  public void close() {
    if (owns) http.close();
  }
}
