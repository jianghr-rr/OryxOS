package io.github.oryxlabs.oryxos.tool.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.execution.ExecutionContext;
import io.github.oryxlabs.oryxos.core.tool.ToolResult;
import io.github.oryxlabs.oryxos.tool.http.ControlledHttpClient;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Core-stage {@link NotifyChannelAdapter} backed by generic HTTP webhooks.
 *
 * <p>Interprets {@code url}, optional {@code headers}, optional {@code payload} (defaulting to
 * {@code {"content": "$CONTENT"}}), and optional {@code acknowledgement} (defaulting to HTTP 2xx)
 * from {@link NotifyTarget#config()}.
 */
public final class WebhookNotifyAdapter implements NotifyChannelAdapter {
  private static final Map<String, Object> DEFAULT_PAYLOAD = Map.of("content", "$CONTENT");
  private static final Map<String, Object> DEFAULT_ACK = Map.of("mode", "http_2xx");

  private final ControlledHttpClient http;
  private final ObjectMapper json = new ObjectMapper();

  public WebhookNotifyAdapter(ControlledHttpClient http) {
    this.http = http;
  }

  @Override
  public void authorize(NotifyTarget target) {
    http.authorize(requireUrl(target));
  }

  /** Backward-compatible overload delegating to {@link #authorize(NotifyTarget)}. */
  public void authorize(NotificationTargetResolver.Target target) {
    authorize(target.toNotifyTarget());
  }

  @Override
  public ToolResult send(NotifyTarget target, String content, ExecutionContext ctx) {
    String url = requireUrl(target);
    Map<String, String> headers = extractHeaders(target);
    Map<String, Object> payload = extractMap(target, "payload", DEFAULT_PAYLOAD);
    Map<String, Object> acknowledgement = extractMap(target, "acknowledgement", DEFAULT_ACK);
    String body;
    try {
      body = json.writeValueAsString(replace(json.valueToTree(payload), content));
    } catch (Exception e) {
      return ToolResult.failure(
          ToolResult.Outcome.ERROR,
          "NOTIFY_PAYLOAD_INVALID",
          "Notification payload cannot be encoded");
    }
    try {
      var response = http.exchange("POST", url, headers, body, ctx);
      boolean accepted = response.status() >= 200 && response.status() < 300;
      if (accepted && "json_equals".equals(acknowledgement.get("mode"))) {
        try {
          var ack = json.readTree(response.body());
          accepted =
              ack != null
                  && ack.at((String) acknowledgement.get("pointer"))
                      .equals(json.valueToTree(acknowledgement.get("expected")));
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

  /**
   * Backward-compatible overload delegating to {@link #send(NotifyTarget, String,
   * ExecutionContext)}.
   */
  public ToolResult send(
      NotificationTargetResolver.Target target, String content, ExecutionContext ctx) {
    return send(target.toNotifyTarget(), content, ctx);
  }

  private static String requireUrl(NotifyTarget target) {
    Object raw = target.config().get("url");
    if (!(raw instanceof String url) || url.isBlank()) {
      throw OryxError.configuration("notify.webhook.url");
    }
    return url;
  }

  private static Map<String, String> extractHeaders(NotifyTarget target) {
    Object raw = target.config().get("headers");
    if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
      return Map.of();
    }
    var result = new LinkedHashMap<String, String>();
    map.forEach((k, v) -> {
      if (k != null && v != null) {
        result.put(k.toString(), v.toString());
      }
    });
    return Map.copyOf(result);
  }

  private static Map<String, Object> extractMap(
      NotifyTarget target, String key, Map<String, Object> fallback) {
    Object raw = target.config().get(key);
    if (!(raw instanceof Map<?, ?> map)) {
      return fallback;
    }
    var result = new LinkedHashMap<String, Object>();
    map.forEach((k, v) -> {
      if (k != null) {
        result.put(k.toString(), v);
      }
    });
    return result;
  }

  private JsonNode replace(JsonNode value, String content) {
    if (value.isTextual() && "$CONTENT".equals(value.asText())) {
      return TextNode.valueOf(content);
    }
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
