package io.github.oryxlabs.oryxos.tool.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.oryxlabs.oryxos.core.execution.ExecutionContext;
import io.github.oryxlabs.oryxos.core.tool.*;
import java.util.Map;

public final class HttpPostTool implements OryxTool {
  private final ControlledHttpClient http;
  private final ObjectMapper json = new ObjectMapper();

  public HttpPostTool(ControlledHttpClient http) {
    this.http = http;
  }

  public ToolDescriptor descriptor() {
    return staticDescriptor();
  }

  public static ToolDescriptor staticDescriptor() {
    return new ToolDescriptor(
        "http_post",
        "POST JSON to an allowed URL; non-idempotent and never retried",
        "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\",\"minLength\":1},\"jsonBody\":{\"type\":\"object\"}},\"required\":[\"url\",\"jsonBody\"],\"additionalProperties\":false}",
        false,
        true,
        null,
        null);
  }

  public void authorize(Map<String, Object> a, ExecutionContext c) {
    http.authorize((String) a.get("url"));
  }

  public ToolResult execute(Map<String, Object> a, ExecutionContext c) {
    String body;
    try {
      body = json.writeValueAsString(a.get("jsonBody"));
    } catch (Exception e) {
      return ToolResult.failure(
          ToolResult.Outcome.ERROR, "INVALID_ARGUMENTS", "Cannot encode JSON body");
    }
    try {
      var reply = http.exchange("POST", (String) a.get("url"), Map.of(), body, c);
      boolean ok = reply.status() >= 200 && reply.status() < 300;
      return new ToolResult(
          ok ? ToolResult.Outcome.SUCCESS : ToolResult.Outcome.ERROR,
          "status=" + reply.status() + "\n" + reply.body(),
          ok ? null : "HTTP_" + reply.status(),
          "HTTP POST completed",
          ToolResult.RetryClass.NONE,
          ToolResult.SideEffectState.CONFIRMED,
          null,
          1,
          0);
    } catch (ControlledHttpClient.TransportFailure e) {
      return new ToolResult(
          e.sent() ? ToolResult.Outcome.UNKNOWN : ToolResult.Outcome.ERROR,
          "POST result is unconfirmed; inspect before retrying",
          e.code(),
          "POST was not retried",
          ToolResult.RetryClass.NONE,
          e.sent() ? ToolResult.SideEffectState.UNKNOWN : ToolResult.SideEffectState.NONE,
          null,
          1,
          0);
    }
  }
}
