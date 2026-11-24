package io.github.oryxlabs.oryxos.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.oryxlabs.oryxos.core.execution.Execution;
import io.github.oryxlabs.oryxos.core.execution.ProcessRequest;
import io.github.oryxlabs.oryxos.core.model.ChatMessage;
import io.github.oryxlabs.oryxos.core.model.ToolCall;
import io.github.oryxlabs.oryxos.core.model.ToolNameMap;
import io.github.oryxlabs.oryxos.core.runtime.RuntimeGate;
import io.github.oryxlabs.oryxos.core.session.Session;
import io.github.oryxlabs.oryxos.core.session.SessionMessage;
import io.github.oryxlabs.oryxos.core.tool.ToolResult;
import io.github.oryxlabs.oryxos.core.workspace.WorkspaceHandle;
import io.github.oryxlabs.oryxos.core.workspace.WorkspaceInitializer;
import io.github.oryxlabs.oryxos.core.workspace.WorkspaceProcessLock;
import io.github.oryxlabs.oryxos.storage.execution.JpaExecutionStore;
import io.github.oryxlabs.oryxos.storage.lifecycle.StorageResourceFactory;
import io.github.oryxlabs.oryxos.storage.session.JpaSessionStore;
import io.github.oryxlabs.oryxos.storage.transaction.WorkspaceWriteCoordinator;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("us5-cli")
class SessionRepositoryTest {
  @TempDir Path root;

  @Test
  @DisplayName("手工建表脚本建出的 sessions 表能存能读")
  void manualMigrationSchemaPersistsAndReadsSessionMetadata() throws Exception {
    var workspace = new WorkspaceHandle(root);
    UUID workspaceId = new WorkspaceInitializer().initialize(workspace, Map.of());
    UUID sessionId = UUID.randomUUID();
    Instant createdAt = Instant.parse("2026-11-24T12:00:00Z");
    var expected =
        new Session(
            sessionId,
            "cli",
            "wang",
            "weather",
            "deepseek",
            "deepseek-chat",
            Session.Kind.CONVERSATION,
            Session.Status.ACTIVE,
            createdAt);

    try (var lock = WorkspaceProcessLock.acquire(workspace);
        var resources = new StorageResourceFactory().open(workspace, lock)) {
      var writes = new WorkspaceWriteCoordinator(() -> resources, new RuntimeGate());
      var store = new JpaSessionStore(workspaceId, writes);

      store.create(expected);
      Session loaded = store.find(sessionId);

      assertNotNull(loaded);
      assertEquals(expected, loaded);
    }
  }

  @Test
  @DisplayName("结构化消息 JSON 序列化回读后消息与工具配对完整")
  void structuredMessagesJsonRoundTripPreservesToolPairs() throws Exception {
    var workspace = new WorkspaceHandle(root);
    UUID workspaceId = new WorkspaceInitializer().initialize(workspace, Map.of());
    UUID sessionId = UUID.randomUUID();
    var session = newConversationSession(sessionId);
    var execution = newExecution(sessionId, "北京今天天气如何？");
    var toolCall = new ToolCall("call-weather-1", "http_get", "http_get", "{\"city\":\"Beijing\"}");

    try (var lock = WorkspaceProcessLock.acquire(workspace);
        var resources = new StorageResourceFactory().open(workspace, lock)) {
      var writes = new WorkspaceWriteCoordinator(() -> resources, new RuntimeGate());
      var store = new JpaSessionStore(workspaceId, writes);
      var executions = new JpaExecutionStore(writes);

      Instant now = Instant.parse("2026-11-24T12:01:00Z");
      store.begin(
          session,
          true,
          execution.snapshot(0),
          new SessionMessage(
              UUID.randomUUID(),
              sessionId,
              execution.id(),
              execution.turnId(),
              1L,
              ChatMessage.user("北京今天天气如何？"),
              null,
              now),
          executions);

      var assistantToolProposal =
          new ChatMessage(
              ChatMessage.Role.ASSISTANT,
              "正在查询天气",
              List.of(toolCall),
              null,
              null,
              new ChatMessage.ProviderOpaque("deepseek", "deepseek-chat", "opaque-token"));
      store.append(
          new SessionMessage(
              UUID.randomUUID(),
              sessionId,
              execution.id(),
              execution.turnId(),
              2L,
              assistantToolProposal,
              null,
              now.plusSeconds(1)));
      store.append(
          new SessionMessage(
              UUID.randomUUID(),
              sessionId,
              execution.id(),
              execution.turnId(),
              3L,
              ChatMessage.tool(toolCall, "{\"tempC\":18,\"condition\":\"Sunny\"}"),
              ToolResult.Outcome.SUCCESS,
              now.plusSeconds(2)));
      store.append(
          new SessionMessage(
              UUID.randomUUID(),
              sessionId,
              execution.id(),
              execution.turnId(),
              4L,
              new ChatMessage(
                  ChatMessage.Role.ASSISTANT,
                  "北京今天晴，18°C，建议穿薄外套。",
                  List.of(),
                  null,
                  null,
                  new ChatMessage.ProviderOpaque("deepseek", "deepseek-chat", null)),
              null,
              now.plusSeconds(3)));
      store.finishTurn(sessionId, execution.turnId());

      List<SessionMessage> loaded = store.messages(sessionId);
      assertEquals(4, loaded.size());
      assertEquals(List.of(1L, 2L, 3L, 4L), loaded.stream().map(SessionMessage::seq).toList());
      assertEquals(ChatMessage.Role.USER, loaded.get(0).message().role());
      assertEquals("北京今天天气如何？", loaded.get(0).message().content());
      assertEquals(ChatMessage.Role.ASSISTANT, loaded.get(1).message().role());
      assertEquals(1, loaded.get(1).message().toolCalls().size());
      assertEquals("call-weather-1", loaded.get(1).message().toolCalls().getFirst().id());
      assertEquals(ChatMessage.Role.TOOL, loaded.get(2).message().role());
      assertEquals("call-weather-1", loaded.get(2).message().toolCallId());
      assertEquals(ToolResult.Outcome.SUCCESS, loaded.get(2).toolOutcome());
      assertEquals("北京今天晴，18°C，建议穿薄外套。", loaded.get(3).message().content());
      assertTrue(store.completedTurns(sessionId).contains(execution.turnId()));
    }
  }

  @Test
  @DisplayName("模拟重启（新建存储上下文重查）后会话与历史完整保留")
  void reopeningStorageContextAfterRestartPreservesSessionHistory() throws Exception {
    var workspace = new WorkspaceHandle(root);
    UUID workspaceId = new WorkspaceInitializer().initialize(workspace, Map.of());
    UUID sessionId = UUID.randomUUID();
    var session = newConversationSession(sessionId);
    var execution = newExecution(sessionId, "记住我更关注 AI 方向");
    Instant now = Instant.parse("2026-11-24T12:05:00Z");

    try (var lock = WorkspaceProcessLock.acquire(workspace);
        var firstContext = new StorageResourceFactory().open(workspace, lock)) {
      var writes = new WorkspaceWriteCoordinator(() -> firstContext, new RuntimeGate());
      var store = new JpaSessionStore(workspaceId, writes);
      var executions = new JpaExecutionStore(writes);

      store.begin(
          session,
          true,
          execution.snapshot(0),
          new SessionMessage(
              UUID.randomUUID(),
              sessionId,
              execution.id(),
              execution.turnId(),
              1L,
              ChatMessage.user("记住我更关注 AI 方向"),
              null,
              now),
          executions);
      store.append(
          new SessionMessage(
              UUID.randomUUID(),
              sessionId,
              execution.id(),
              execution.turnId(),
              2L,
              new ChatMessage(
                  ChatMessage.Role.ASSISTANT,
                  "好的，已记录你的偏好。",
                  List.of(),
                  null,
                  null,
                  new ChatMessage.ProviderOpaque("deepseek", "deepseek-chat", null)),
              null,
              now.plusSeconds(1)));
      store.finishTurn(sessionId, execution.turnId());
    }

    // Reopen the same SQLite database in a brand-new storage context to simulate process restart.
    try (var lock = WorkspaceProcessLock.acquire(workspace);
        var reopenedContext = new StorageResourceFactory().open(workspace, lock)) {
      var writes = new WorkspaceWriteCoordinator(() -> reopenedContext, new RuntimeGate());
      var store = new JpaSessionStore(workspaceId, writes);

      Session reloaded = store.find(sessionId);
      assertEquals(session, reloaded);

      List<SessionMessage> messages = store.messages(sessionId);
      assertEquals(2, messages.size());
      assertEquals("记住我更关注 AI 方向", messages.get(0).message().content());
      assertEquals("好的，已记录你的偏好。", messages.get(1).message().content());
      assertTrue(store.completedTurns(sessionId).contains(execution.turnId()));
      assertEquals(2L, store.lastSequence(sessionId));
    }
  }

  private static Session newConversationSession(UUID sessionId) {
    return new Session(
        sessionId,
        "cli",
        "wang",
        "weather",
        "deepseek",
        "deepseek-chat",
        Session.Kind.CONVERSATION,
        Session.Status.ACTIVE,
        Instant.parse("2026-11-24T12:00:00Z"));
  }

  private static Execution newExecution(UUID sessionId, String input) {
    Instant started = Instant.parse("2026-11-24T12:01:00Z");
    return new Execution(
        UUID.randomUUID(),
        sessionId,
        UUID.randomUUID(),
        ProcessRequest.manual("cli", "wang", "weather", sessionId, input),
        "cfg-rev-1",
        started,
        started.plusSeconds(60),
        ZoneId.of("Asia/Shanghai"),
        ToolNameMap.empty());
  }
}
