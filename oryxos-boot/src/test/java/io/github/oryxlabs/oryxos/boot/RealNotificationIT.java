package io.github.oryxlabs.oryxos.boot;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.oryxlabs.oryxos.boot.support.RealToolProbe;
import io.github.oryxlabs.oryxos.core.config.SafeYaml;
import io.github.oryxlabs.oryxos.core.tool.ToolResult;
import io.github.oryxlabs.oryxos.tool.http.*;
import io.github.oryxlabs.oryxos.tool.notify.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;

@Tag("real-integration")
@Tag("real-notify")
class RealNotificationIT {
  @Test
  void configuredWebhookAcknowledgesOneIdentifiableMessage() throws Exception {
    var evidence = new LinkedHashMap<String, Object>();
    evidence.put("kind", "REAL_CONFIGURED_WEBHOOK");
    evidence.put("time", Instant.now().toString());
    evidence.put("status", "PREREQUISITES_MISSING");
    evidence.put("humanReceiptReview", "PENDING: verify the marker at the intended test receiver");
    try {
      assertEquals("true", RealToolProbe.required("oryxos.real.notify.confirmSend"));
      String target = RealToolProbe.required("oryxos.real.notify.target");
      try (var probe = new RealToolProbe()) {
        assertTrue(probe.agent.tools().contains("notify"), "Agent must explicitly allow notify");
        assertTrue(
            probe.agent.notifications().containsKey(target),
            "Declare the chosen test target and acknowledgement");
        var rules = SafeYaml.map(probe.config.toolRules().get("http"), "tools.http");
        try (var http = new ControlledHttpClient(HttpTargetPolicy.from(rules), 5, 20, 65536)) {
          var tool =
              new NotifyTool(
                  new NotificationTargetResolver(List.of(probe.agent), probe.secrets()),
                  new WebhookNotifyAdapter(http));
          String marker = "ORYXOS-US4-" + UUID.randomUUID();
          evidence.put("marker", marker);
          evidence.put("status", "STARTED");
          var result =
              probe.execute(
                  List.of(tool),
                  "notify",
                  new ObjectMapper()
                      .writeValueAsString(Map.of("content", marker, "targetName", target)));
          evidence.put("executionId", result.record().executionId().toString());
          evidence.put("toolCallId", result.call().id());
          evidence.put("outcome", result.result().outcome());
          evidence.put("attempts", result.result().attemptCount());
          evidence.put(
              "status",
              result.result().outcome() == ToolResult.Outcome.SUCCESS
                  ? "ACKNOWLEDGED_REVIEW_PENDING"
                  : "FAILED");
          assertEquals(
              ToolResult.Outcome.SUCCESS,
              result.result().outcome(),
              "Test target did not acknowledge the notification; inspect effects before rerunning");
          assertEquals(1, result.result().attemptCount());
        }
      }
    } finally {
      RealToolProbe.evidence("notify", evidence);
    }
  }
}
