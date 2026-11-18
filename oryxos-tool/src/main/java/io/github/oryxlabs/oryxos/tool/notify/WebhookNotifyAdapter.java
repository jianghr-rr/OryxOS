package io.github.oryxlabs.oryxos.tool.notify;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.github.oryxlabs.oryxos.core.execution.ExecutionContext;
import io.github.oryxlabs.oryxos.core.tool.*;
import io.github.oryxlabs.oryxos.tool.http.ControlledHttpClient;

public final class WebhookNotifyAdapter {
  private final ControlledHttpClient http;
  private final ObjectMapper json = new ObjectMapper();

  public WebhookNotifyAdapter(ControlledHttpClient http) {
    this.http = http;
  }

  public void authorize(NotificationTargetResolver.Target target) {
    http.authorize(target.url());
  }

  public ToolResult send(
      NotificationTargetResolver.Target target, String content, ExecutionContext ctx) {
    String body;
    try {
      body = json.writeValueAsString(replace(json.valueToTree(target.payload()), content));
    } catch (Exception e) {
      return ToolResult.failure(
          ToolResult.Outcome.ERROR,
          "NOTIFY_PAYLOAD_INVALID",
          "Notification payload cannot be encoded");
    }
    try {
      var response = http.exchange("POST", target.url(), target.headers(), body, ctx);
      boolean accepted = response.status() >= 200 && response.status() < 300;
      if (accepted && "json_equals".equals(target.acknowledgement().get("mode"))) {
        try {
          var ack = json.readTree(response.body());
          accepted =
              ack != null
                  && ack.at((String) target.acknowledgement().get("pointer"))
                      .equals(json.valueToTree(target.acknowledgement().get("expected")));
        } catch (Exception e) {
          accepted = false;
        }
      }
      return new ToolResult(
          accepted ? ToolResult.Outcome.SUCCESS : ToolResult.Outcome.ERROR,
          accepted
              ? "Notification accepted by configured target (not a read receipt)"
              : "Target did not acknowledge notification",
          accepted ? null : "NOTIFY_REJECTED",
          "Notification transport and acknowledgement checked",
          ToolResult.RetryClass.NONE,
          ToolResult.SideEffectState.CONFIRMED,
          null,
          1,
          0);
    } catch (ControlledHttpClient.TransportFailure e) {
      return new ToolResult(
          e.sent() ? ToolResult.Outcome.UNKNOWN : ToolResult.Outcome.ERROR,
          "Notification receipt is unconfirmed; inspect the target before retrying",
          e.sent() ? "NOTIFY_DELIVERY_UNKNOWN" : e.code(),
          "Notification was not retried",
          ToolResult.RetryClass.NONE,
          e.sent() ? ToolResult.SideEffectState.UNKNOWN : ToolResult.SideEffectState.NONE,
          null,
          1,
          0);
    }
  }

  private JsonNode replace(JsonNode value, String content) {
    if (value.isTextual() && value.asText().equals("$CONTENT")) return TextNode.valueOf(content);
    if (value.isObject()) {
      var node = json.createObjectNode();
      value.fields().forEachRemaining(e -> node.set(e.getKey(), replace(e.getValue(), content)));
      return node;
    }
    if (value.isArray()) {
      var node = json.createArrayNode();
      value.forEach(e -> node.add(replace(e, content)));
      return node;
    }
    return value;
  }
}
