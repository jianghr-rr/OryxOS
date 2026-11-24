package io.github.oryxlabs.oryxos.tool.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.oryxlabs.oryxos.core.agent.AgentProfile;
import io.github.oryxlabs.oryxos.core.audit.AuditSink;
import io.github.oryxlabs.oryxos.core.audit.ModelCallRecord;
import io.github.oryxlabs.oryxos.core.audit.ToolCallRecord;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.execution.ExecutionContext;
import io.github.oryxlabs.oryxos.core.model.ToolCall;
import io.github.oryxlabs.oryxos.core.security.SecretResolver;
import io.github.oryxlabs.oryxos.core.tool.ToolExecutor;
import io.github.oryxlabs.oryxos.core.tool.ToolResult;
import io.github.oryxlabs.oryxos.tool.ToolRegistry;
import io.github.oryxlabs.oryxos.tool.policy.ToolPolicy;
import io.github.oryxlabs.oryxos.tool.support.ToolFixture;
import io.github.oryxlabs.oryxos.tool.validation.ToolArgumentValidator;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("us4-tools")
class NotifyToolsTest {
  @TempDir Path root;

  private static final class RecordingAdapter implements NotifyChannelAdapter {
    final List<String> events = new ArrayList<>();
    volatile boolean denyAuthorize;
    volatile NotifyTarget lastTarget;
    volatile String lastContent;

    @Override
    public void authorize(NotifyTarget target) {
      events.add("authorize");
      lastTarget = target;
      if (denyAuthorize) {
        throw new OryxError("HTTP_TARGET_DENIED", "Destination domain is not allowlisted");
      }
    }

    @Override
    public ToolResult send(NotifyTarget target, String content, ExecutionContext ctx) {
      events.add("send");
      lastTarget = target;
      lastContent = content;
      return new ToolResult(
          ToolResult.Outcome.SUCCESS,
          "Notification accepted by custom channel adapter",
          null,
          "Delivered via " + target.channelType(),
          ToolResult.RetryClass.NONE,
          ToolResult.SideEffectState.CONFIRMED,
          null,
          1,
          0);
    }
  }

  private static ToolExecutor newExecutor(NotifyTool tool, List<ToolCallRecord> auditRecords) {
    var registry = new ToolRegistry(List.of(tool), new ToolArgumentValidator(), new ToolPolicy());
    AuditSink sink =
        new AuditSink() {
          @Override
          public void modelCall(ModelCallRecord record) {}

          @Override
          public void toolCall(ToolCallRecord record) {
            auditRecords.add(record);
          }
        };
    return new ToolExecutor(registry, sink, duration -> {});
  }

  @Test
  @DisplayName("notify_channels 未配置时明确报错且不触发发送")
  void notify_failsExplicitlyWhenNotifyChannelsNotConfigured() throws Exception {
    try (var fixture = new ToolFixture(root)) {
      fixture.notifications = Map.of();
      var ctx = fixture.context("weather");
      var adapter = new RecordingAdapter();
      var resolver =
          new NotificationTargetResolver(
              List.of(ctx.agent()),
              new SecretResolver(Map.of(), root.resolve("none"), ctx.redactor()));
      var tool = new NotifyTool(resolver, adapter);

      OryxError error =
          assertThrows(OryxError.class, () -> tool.authorize(Map.of("content", "hello"), ctx));
      assertEquals("INVALID_ARGUMENTS", error.code());
      assertEquals(List.of(), adapter.events);
    }
  }

  @Test
  @DisplayName("channel 参数缺省时自动取唯一或默认渠道")
  void notify_usesDefaultChannelWhenTargetNameOmitted() throws Exception {
    try (var fixture = new ToolFixture(root)) {
      var primary =
          new AgentProfile.Notification(
              "${PRIMARY_URL}",
              Map.of("content", "$CONTENT"),
              Map.of("mode", "http_2xx"),
              Map.of(),
              true);
      var secondary =
          new AgentProfile.Notification(
              "${SECONDARY_URL}",
              Map.of("content", "$CONTENT"),
              Map.of("mode", "http_2xx"),
              Map.of(),
              false);
      fixture.notifications = Map.of("primary", primary, "secondary", secondary);
      var ctx = fixture.context("weather");
      var adapter = new RecordingAdapter();
      var resolver =
          new NotificationTargetResolver(
              List.of(ctx.agent()),
              new SecretResolver(
                  Map.of(
                      "PRIMARY_URL",
                      "https://hooks.example.test/primary",
                      "SECONDARY_URL",
                      "https://hooks.example.test/secondary"),
                  root.resolve("none"),
                  ctx.redactor()));
      var tool = new NotifyTool(resolver, adapter);

      var args = Map.<String, Object>of("content", "today sunny 22C");
      tool.authorize(args, ctx);
      ToolResult result = tool.execute(args, ctx);

      assertEquals(ToolResult.Outcome.SUCCESS, result.outcome());
      assertEquals("https://hooks.example.test/primary", adapter.lastTarget.config().get("url"));
      assertEquals("today sunny 22C", adapter.lastContent);
    }
  }

  @Test
  @DisplayName("发送前必须先过白名单校验")
  void notify_enforcesAuthorizationStrictlyBeforeSend() throws Exception {
    try (var fixture = new ToolFixture(root)) {
      fixture.tools = List.of("notify");
      fixture.notifications =
          Map.of(
              "default",
              new AgentProfile.Notification(
                  "${HOOK_URL}", Map.of("content", "$CONTENT"), Map.of("mode", "http_2xx")));
      var ctx = fixture.context("weather");
      var adapter = new RecordingAdapter();
      var resolver =
          new NotificationTargetResolver(
              List.of(ctx.agent()),
              new SecretResolver(
                  Map.of("HOOK_URL", "https://hooks.example.test/team"),
                  root.resolve("none"),
                  ctx.redactor()));
      var audit = new ArrayList<ToolCallRecord>();
      var executor = newExecutor(new NotifyTool(resolver, adapter), audit);
      String argsJson =
          new ObjectMapper()
              .writeValueAsString(Map.of("content", "hello", "targetName", "default"));

      var allowedOutcome =
          executor.execute(ctx, new ToolCall("call-1", "notify", "notify", argsJson));
      assertEquals(ToolResult.Outcome.SUCCESS, allowedOutcome.result().outcome());
      assertEquals(List.of("authorize", "send"), adapter.events);

      adapter.events.clear();
      adapter.denyAuthorize = true;
      var deniedOutcome =
          executor.execute(ctx, new ToolCall("call-2", "notify", "notify", argsJson));
      assertEquals(ToolResult.Outcome.DENIED, deniedOutcome.result().outcome());
      assertEquals("HTTP_TARGET_DENIED", deniedOutcome.result().errorCode());
      assertEquals(List.of("authorize"), adapter.events);
    }
  }

  @Test
  @DisplayName("NotifyTool 仅依赖 NotifyChannelAdapter 接口并复用 ToolExecutor 审计")
  void notify_worksWithCustomAdapterAndWritesToolAudit() throws Exception {
    try (var fixture = new ToolFixture(root)) {
      fixture.tools = List.of("notify");
      fixture.notifications =
          Map.of(
              "ops",
              new AgentProfile.Notification(
                  "${SECRET_HOOK}", Map.of("content", "$CONTENT"), Map.of("mode", "http_2xx")));
      var ctx = fixture.context("digest");
      var customAdapter = new RecordingAdapter();
      var resolver =
          new NotificationTargetResolver(
              List.of(ctx.agent()),
              new SecretResolver(
                  Map.of("SECRET_HOOK", "https://hooks.example.test/secret-token-xyz"),
                  root.resolve("none"),
                  ctx.redactor()));
      var audit = new ArrayList<ToolCallRecord>();
      var executor = newExecutor(new NotifyTool(resolver, customAdapter), audit);
      String argsJson = new ObjectMapper().writeValueAsString(Map.of("content", "daily digest"));

      var success = executor.execute(ctx, new ToolCall("call-ok", "notify", "notify", argsJson));
      assertEquals(ToolResult.Outcome.SUCCESS, success.result().outcome());
      assertEquals(2, audit.size());
      assertEquals("STARTED", audit.get(0).status());
      assertEquals("notify", audit.get(1).toolName());
      assertEquals("SUCCESS", audit.get(1).status());
      assertFalse(audit.get(1).resultRedacted().contains("secret-token-xyz"));
      assertFalse(audit.get(1).argumentsRedacted().contains("secret-token-xyz"));

      customAdapter.denyAuthorize = true;
      var denied = executor.execute(ctx, new ToolCall("call-deny", "notify", "notify", argsJson));
      assertEquals(ToolResult.Outcome.DENIED, denied.result().outcome());
      assertEquals(3, audit.size());
      assertEquals("DENIED", audit.get(2).status());
    }
  }
}
