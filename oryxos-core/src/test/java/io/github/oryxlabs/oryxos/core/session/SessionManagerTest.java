package io.github.oryxlabs.oryxos.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.oryxlabs.oryxos.core.agent.AgentProfile;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.react.LoopFixture;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("us5-cli")
class SessionManagerTest {
  @TempDir Path root;

  @Test
  @DisplayName("同一三元组按同一会话ID续聊返回同一个Session（多轮对话串连）")
  void sameIdentityTriple_reacquiresSameSessionAcrossTurns() throws Exception {
    try (var fixture = new LoopFixture(root, 10)) {
      UUID sessionId;
      try (var first =
          fixture.sessions.acquire(
              null, "cli", "wang", fixture.profile, Session.Kind.CONVERSATION)) {
        sessionId = first.session().id();
        assertNotNull(sessionId);
        assertEquals("cli", first.session().channel());
        assertEquals("wang", first.session().userId());
        assertEquals(fixture.profile.name(), first.session().agentName());
      }

      try (var second =
          fixture.sessions.acquire(
              sessionId, "cli", "wang", fixture.profile, Session.Kind.CONVERSATION)) {
        assertEquals(sessionId, second.session().id());
        assertEquals(fixture.sessions.lookup(sessionId), second.session());
      }
    }
  }

  @Test
  @DisplayName("channel/user/profile 任一不同即隔离拒绝（防串号）")
  void differentChannelUserOrProfile_rejectedWithIdentityMismatch() throws Exception {
    try (var fixture = new LoopFixture(root, 10)) {
      UUID sessionId;
      try (var first =
          fixture.sessions.acquire(
              null, "cli", "wang", fixture.profile, Session.Kind.CONVERSATION)) {
        sessionId = first.session().id();
      }

      var wrongChannel =
          assertThrows(
              OryxError.class,
              () ->
                  fixture.sessions.acquire(
                      sessionId, "web", "wang", fixture.profile, Session.Kind.CONVERSATION));
      assertEquals("SESSION_IDENTITY_MISMATCH", wrongChannel.code());

      var wrongUser =
          assertThrows(
              OryxError.class,
              () ->
                  fixture.sessions.acquire(
                      sessionId, "cli", "li", fixture.profile, Session.Kind.CONVERSATION));
      assertEquals("SESSION_IDENTITY_MISMATCH", wrongUser.code());

      var otherProfile = profileWithName(fixture.profile, "other-agent");
      var wrongProfile =
          assertThrows(
              OryxError.class,
              () ->
                  fixture.sessions.acquire(
                      sessionId, "cli", "wang", otherProfile, Session.Kind.CONVERSATION));
      assertEquals("SESSION_IDENTITY_MISMATCH", wrongProfile.code());

      try (var otherChannelSession =
          fixture.sessions.acquire(
              null, "web", "wang", fixture.profile, Session.Kind.CONVERSATION)) {
        assertNotEquals(sessionId, otherChannelSession.session().id());
      }
    }
  }

  @Test
  @DisplayName("会话 ID 由 SessionManager 内部统一生成且新会话互不冲突")
  void sessionIdGeneratedExclusivelyInsideSessionManager() throws Exception {
    try (var fixture = new LoopFixture(root, 10)) {
      UUID firstId;
      UUID secondId;
      try (var first =
          fixture.sessions.acquire(
              null, "cli", "wang", fixture.profile, Session.Kind.CONVERSATION)) {
        firstId = first.session().id();
      }
      try (var second =
          fixture.sessions.acquire(
              null, "cli", "wang", fixture.profile, Session.Kind.CONVERSATION)) {
        secondId = second.session().id();
      }

      assertNotNull(firstId);
      assertNotNull(secondId);
      assertNotEquals(firstId, secondId);

      var unknownId = UUID.randomUUID();
      var missing =
          assertThrows(
              OryxError.class,
              () ->
                  fixture.sessions.acquire(
                      unknownId, "cli", "wang", fixture.profile, Session.Kind.CONVERSATION));
      assertEquals("NOT_FOUND", missing.code());
    }
  }

  @Test
  @DisplayName("进行中租约拒绝重入、归档后拒绝续聊且保留历史")
  void busyOrArchivedSessionCannotBeReacquired() throws Exception {
    try (var fixture = new LoopFixture(root, 10)) {
      var activeId = fixture.lease.session().id();
      var busy =
          assertThrows(
              OryxError.class,
              () ->
                  fixture.sessions.acquire(
                      activeId, "cli", "user", fixture.profile, Session.Kind.CONVERSATION));
      assertEquals("SESSION_BUSY", busy.code());

      fixture.lease.close();
      fixture.sessions.archive(activeId, "cli", "user", fixture.profile.name());

      var archived =
          assertThrows(
              OryxError.class,
              () ->
                  fixture.sessions.acquire(
                      activeId, "cli", "user", fixture.profile, Session.Kind.CONVERSATION));
      assertEquals("SESSION_ARCHIVED", archived.code());
      assertEquals(Session.Status.ARCHIVED, fixture.sessions.lookup(activeId).status());
      assertFalse(fixture.store.messages(activeId).isEmpty());
    }
  }

  private static AgentProfile profileWithName(AgentProfile base, String name) {
    return new AgentProfile(
        name,
        base.description(),
        base.providerName(),
        base.model(),
        base.tools(),
        base.mcpServers(),
        base.bootstrap(),
        base.notifications(),
        base.schedules(),
        base.maxIterations(),
        base.maxHistoryTurns(),
        base.definition(),
        base.configRevision(),
        base.bodyRevision());
  }
}
