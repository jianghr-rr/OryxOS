package io.github.oryxlabs.oryxos.storage.session;

import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.execution.*;
import io.github.oryxlabs.oryxos.core.model.ChatMessage;
import io.github.oryxlabs.oryxos.core.session.*;
import io.github.oryxlabs.oryxos.core.tool.ToolResult;
import io.github.oryxlabs.oryxos.storage.execution.*;
import io.github.oryxlabs.oryxos.storage.transaction.*;
import jakarta.persistence.EntityManager;
import java.sql.*;
import java.time.Instant;
import java.util.*;

public final class JpaSessionStore implements SessionStore {
  public record Content(ChatMessage message, ToolResult.Outcome toolOutcome) {}

  private final UUID workspace;
  private final WorkspaceWriteCoordinator writes;

  public JpaSessionStore(UUID workspace, WorkspaceWriteCoordinator writes) {
    this.workspace = workspace;
    this.writes = writes;
  }

  public boolean atomicBegin() {
    return true;
  }

  public void create(Session session) {
    writes.writeImmediate(
        em -> {
          insert(em, session);
          return null;
        });
  }

  public void begin(
      Session session,
      boolean fresh,
      Execution.Snapshot execution,
      SessionMessage user,
      ExecutionStore executions) {
    begin(session, fresh, execution, user, executions, () -> {}, () -> {});
  }

  @Override
  public void begin(
      Session session,
      boolean fresh,
      Execution.Snapshot execution,
      SessionMessage user,
      ExecutionStore executions,
      Runnable onCommit,
      Runnable onRollback) {
    if (!(executions instanceof JpaExecutionStore))
      throw new IllegalArgumentException("Persistent sessions require persistent executions");
    var preparedExecution = JpaExecutionStore.prepare(execution);
    var preparedMessage = encode(user);
    java.util.function.Consumer<jakarta.persistence.EntityManager> action =
        em -> {
          if (fresh) insert(em, session);
          JpaExecutionStore.put(em, preparedExecution);
          append(em, preparedMessage);
        };
    if (!fresh && io.github.oryxlabs.oryxos.core.execution.RecordingScope.isWindowOpen()) {
      writes.deferOrWrite(action, null, null);
      if (onCommit != null || onRollback != null) {
        io.github.oryxlabs.oryxos.core.execution.RecordingScope.onCoalescedOutcome(
            onCommit != null ? onCommit : () -> {},
            onRollback != null ? onRollback : () -> {});
      }
      return;
    }
    try {
      writes.writeImmediate(
          em -> {
            action.accept(em);
            return null;
          });
      if (onCommit != null) onCommit.run();
    } catch (RuntimeException error) {
      if (onRollback != null) onRollback.run();
      throw error;
    }
  }

  private void insert(EntityManager em, Session s) {
    StoredSql.update(
        em,
        INSERT_SESSION,
        s.id().toString(),
        workspace.toString(),
        s.channel(),
        s.userId(),
        s.agentName(),
        s.providerName(),
        s.model(),
        s.kind().name(),
        s.status().name(),
        s.createdAt().toString(),
        s.archivedAt() == null ? null : s.archivedAt().toString());
  }

  private static final String INSERT_SESSION =
      StoredSql.insert(
          "sessions",
          "id,workspace_id,channel,user_id,agent_name,provider_name,model,kind,status,created_at,archived_at");
  private static final String INSERT_MESSAGE =
      StoredSql.insert(
          "messages",
          "id,session_id,execution_id,turn_id,seq,role,content_json,provider_name,provider_opaque_json,created_at");

  private SessionEntity require(EntityManager em, UUID id) {
    var e = em.find(SessionEntity.class, id.toString());
    if (e == null || !e.workspaceId.equals(workspace.toString()))
      throw new IllegalArgumentException("Session not in this workspace");
    return e;
  }

  private record RawSession(
      String channel,
      String userId,
      String agentName,
      String providerName,
      String model,
      String kind,
      String status,
      String createdAt,
      String archivedAt) {}

  private record RawMessage(
      String id,
      String executionId,
      String turnId,
      long seq,
      String contentJson,
      String providerOpaqueJson,
      String createdAt) {}

  private record RawHistory(Set<String> completedTurns, List<RawMessage> messages) {}

  private static OryxError storageReadError() {
    return new OryxError(
        "STORAGE_UNAVAILABLE", "Stored records cannot be read; no empty fallback is used");
  }

  public Session find(UUID id) {
    var raw =
        query(
            connection -> {
              try (var sql =
                  connection.prepareStatement(
                      "select channel,user_id,agent_name,provider_name,model,kind,status,created_at,archived_at"
                          + " from sessions where id=? and workspace_id=?")) {
                sql.setString(1, id.toString());
                sql.setString(2, workspace.toString());
                try (var row = sql.executeQuery()) {
                  if (!row.next()) return null;
                  return new RawSession(
                      row.getString(1),
                      row.getString(2),
                      row.getString(3),
                      row.getString(4),
                      row.getString(5),
                      row.getString(6),
                      row.getString(7),
                      row.getString(8),
                      row.getString(9));
                }
              }
            });
    if (raw == null) return null;
    try {
      return new Session(
          id,
          raw.channel(),
          raw.userId(),
          raw.agentName(),
          raw.providerName(),
          raw.model(),
          Session.Kind.valueOf(raw.kind()),
          Session.Status.valueOf(raw.status()),
          Instant.parse(raw.createdAt()),
          raw.archivedAt() == null ? null : Instant.parse(raw.archivedAt()));
    } catch (RuntimeException e) {
      throw storageReadError();
    }
  }

  public void archive(UUID id, Instant at) {
    writes.write(
        em -> {
          var e = require(em, id);
          e.status = "ARCHIVED";
          e.archivedAt = at.toString();
          var attrs = StoredSql.batchAttributes();
          if (attrs != null) attrs.remove(new BatchContextKey(workspace, id));
          return null;
        });
  }

  public List<SessionMessage> messages(UUID id) {
    return writes.read(
        em -> {
          require(em, id);
          return messages(em, id);
        });
  }

  private static List<SessionMessage> messages(EntityManager em, UUID id) {
    return em
        .createQuery(
            "select m from MessageEntity m where m.sessionId=:id order by m.seq",
            MessageEntity.class)
        .setParameter("id", id.toString())
        .getResultList()
        .stream()
        .map(JpaSessionStore::message)
        .toList();
  }

  @Override
  public long lastSequence(UUID id) {
    return query(
        connection -> {
          try (var sql =
              connection.prepareStatement(
                  "select (select coalesce(max(m.seq),0) from messages m where m.session_id=s.id)"
                      + " from sessions s where s.id=? and s.workspace_id=?")) {
            sql.setString(1, id.toString());
            sql.setString(2, workspace.toString());
            try (var row = sql.executeQuery()) {
              if (!row.next()) throw new IllegalArgumentException("Session not in this workspace");
              return row.getLong(1);
            }
          }
        });
  }

  @Override
  public List<SessionMessage> turnMessages(UUID id, UUID turn) {
    var rows =
        query(
            connection -> {
              requireContext(connection, id);
              return selectedRawMessages(connection, id, List.of(turn.toString()));
            });
    return decodeMessages(id, rows);
  }

  @Override
  public SessionHistoryPort.History history(UUID id, UUID current, int maxTurns) {
    if (maxTurns < 1) throw new IllegalArgumentException("Positive turn limit required");
    var raw =
        query(
            connection -> {
              requireContext(connection, id);
              var completed = new LinkedHashSet<String>();
              try (var sql =
                  connection.prepareStatement(
                      "select m.turn_id from messages m where m.session_id=? and m.role='USER'"
                          + " and m.turn_id<>? and exists (select e.id from executions e"
                          + " where e.turn_id=m.turn_id and e.session_id=? and e.turn_complete=1)"
                          + " order by m.seq desc limit ?")) {
                sql.setString(1, id.toString());
                sql.setString(2, current.toString());
                sql.setString(3, id.toString());
                sql.setInt(4, maxTurns);
                try (var rows = sql.executeQuery()) {
                  while (rows.next()) completed.add(rows.getString(1));
                }
              }
              var selected = new ArrayList<>(completed);
              selected.add(current.toString());
              return new RawHistory(completed, selectedRawMessages(connection, id, selected));
            });
    try {
      var messages = decodeMessages(id, raw.messages());
      var completed =
          raw.completedTurns().stream()
              .map(UUID::fromString)
              .collect(java.util.stream.Collectors.toSet());
      return new HistorySelector().select(messages, completed, current, maxTurns);
    } catch (OryxError e) {
      throw e;
    } catch (RuntimeException e) {
      throw storageReadError();
    }
  }

  @FunctionalInterface
  private interface Read<T> {
    T apply(Connection connection) throws SQLException;
  }

  /** Project bounded results on the same SQLite pool without a persistence context. */
  private <T> T query(Read<T> read) {
    return writes.readSql(read::apply);
  }

  private static List<RawMessage> selectedRawMessages(
      Connection connection, UUID id, List<String> turns) throws SQLException {
    // Keep the bounded turn-index seek instead of scanning all messages for this session.
    try (var sql =
        connection.prepareStatement(
            "select id,execution_id,turn_id,seq,content_json,provider_opaque_json,created_at"
                + " from messages indexed by messages_turn"
                + " where session_id=? and turn_id in ("
                + String.join(",", Collections.nCopies(turns.size(), "?"))
                + ") order by seq")) {
      sql.setString(1, id.toString());
      for (int i = 0; i < turns.size(); i++) sql.setString(i + 2, turns.get(i));
      try (var rows = sql.executeQuery()) {
        var result = new ArrayList<RawMessage>();
        while (rows.next()) {
          result.add(
              new RawMessage(
                  rows.getString(1),
                  rows.getString(2),
                  rows.getString(3),
                  rows.getLong(4),
                  rows.getString(5),
                  rows.getString(6),
                  rows.getString(7)));
        }
        return result;
      }
    }
  }

  private static List<SessionMessage> decodeMessages(UUID sessionId, List<RawMessage> rows) {
    try {
      var result = new ArrayList<SessionMessage>(rows.size());
      for (var row : rows) {
        var c = StoredJson.read(row.contentJson(), Content.class);
        var m = c.message();
        String rawOpaque = row.providerOpaqueJson();
        var opaque =
            rawOpaque == null ? null : StoredJson.read(rawOpaque, ChatMessage.ProviderOpaque.class);
        result.add(
            new SessionMessage(
                UUID.fromString(row.id()),
                sessionId,
                UUID.fromString(row.executionId()),
                UUID.fromString(row.turnId()),
                row.seq(),
                new ChatMessage(
                    m.role(), m.content(), m.toolCalls(), m.toolCallId(), m.toolName(), opaque),
                c.toolOutcome(),
                Instant.parse(row.createdAt())));
      }
      return List.copyOf(result);
    } catch (RuntimeException e) {
      throw storageReadError();
    }
  }

  public Set<UUID> completedTurns(UUID id) {
    return writes.read(
        em -> {
          require(em, id);
          return new HashSet<>(
              em
                  .createQuery(
                      "select e.turnId from ExecutionEntity e where e.sessionId=:id and"
                          + " e.turnComplete=1",
                      String.class)
                  .setParameter("id", id.toString())
                  .getResultList()
                  .stream()
                  .map(UUID::fromString)
                  .toList());
        });
  }

  public void append(SessionMessage m) {
    var encoded = encode(m);
    writes.deferOrWrite(
        em -> append(em, encoded),
        null,
        new OryxError("RECORDING_FAILURE", "Session storage operation failed"));
  }

  private record EncodedMessage(SessionMessage message, String content, String opaque) {}

  private static EncodedMessage encode(SessionMessage m) {
    var c = m.message();
    return new EncodedMessage(
        m,
        StoredJson.write(
            new Content(
                new ChatMessage(
                    c.role(), c.content(), c.toolCalls(), c.toolCallId(), c.toolName(), null),
                m.toolOutcome())),
        c.providerOpaque() == null ? null : StoredJson.write(c.providerOpaque()));
  }

  private static final String SELECT_CONTEXT =
      "select workspace_id,status,provider_name,model from sessions where id=?";
  private static final String SELECT_TURN_CONTENT =
      "select content_json from messages indexed by messages_turn where session_id=? and turn_id=?"
          + " order by seq";
  private static final String FINISH_TURN =
      "update executions set turn_complete=1 where session_id=? and turn_id=?";

  private record SessionContext(String status, String providerName, String model) {}

  private record BatchContextKey(UUID workspace, UUID session) {}

  private SessionContext requireContext(EntityManager em, UUID id) {
    var attrs = StoredSql.batchAttributes();
    var key = attrs == null ? null : new BatchContextKey(workspace, id);
    if (key != null) {
      var cached = attrs.get(key);
      if (cached instanceof SessionContext found) return found;
    }
    var loaded =
        StoredSql.query(
            em,
            SELECT_CONTEXT,
            row -> {
              if (!row.next() || !row.getString(1).equals(workspace.toString()))
                throw new IllegalArgumentException("Session not in this workspace");
              return new SessionContext(row.getString(2), row.getString(3), row.getString(4));
            },
            id.toString());
    if (key != null) attrs.put(key, loaded);
    return loaded;
  }

  private SessionContext requireContext(Connection connection, UUID id) throws SQLException {
    try (var sql =
        connection.prepareStatement(
            "select workspace_id,status,provider_name,model from sessions where id=?")) {
      sql.setString(1, id.toString());
      try (var row = sql.executeQuery()) {
        if (!row.next() || !row.getString(1).equals(workspace.toString()))
          throw new IllegalArgumentException("Session not in this workspace");
        return new SessionContext(row.getString(2), row.getString(3), row.getString(4));
      }
    }
  }

  private void append(EntityManager em, EncodedMessage encoded) {
    var m = encoded.message();
    var c = m.message();
    var s = requireContext(em, m.sessionId());
    if (!s.status().equals("ACTIVE")) throw new IllegalStateException("Archived session");
    if (c.providerOpaque() != null
        && (!c.providerOpaque().provider().equals(s.providerName())
            || !c.providerOpaque().model().equals(s.model())))
      throw new IllegalArgumentException("Foreign protocol continuation");
    StoredSql.update(
        em,
        INSERT_MESSAGE,
        m.id().toString(),
        m.sessionId().toString(),
        m.executionId().toString(),
        m.turnId().toString(),
        m.seq(),
        c.role().name(),
        encoded.content(),
        s.providerName(),
        encoded.opaque(),
        m.createdAt().toString());
  }

  public void finishTurn(UUID id, UUID turn) {
    writes.writeImmediate(
        em -> {
          requireContext(em, id);
          var messages =
              StoredSql.query(
                  em,
                  SELECT_TURN_CONTENT,
                  rows -> {
                    var values = new ArrayList<ChatMessage>();
                    while (rows.next())
                      values.add(StoredJson.read(rows.getString(1), Content.class).message());
                    return values;
                  },
                  id.toString(),
                  turn.toString());
          HistorySelector.paired(messages);
          int n = StoredSql.update(em, FINISH_TURN, id.toString(), turn.toString());
          if (n != 1) throw new IllegalStateException("Missing execution");
          return null;
        });
  }

  @Override
  public void finishTurnVerified(UUID id, UUID turn) {
    writes.deferOrWrite(
        em -> {
          requireContext(em, id);
          int n = StoredSql.update(em, FINISH_TURN, id.toString(), turn.toString());
          if (n != 1) throw new IllegalStateException("Missing execution");
        },
        null,
        null);
  }

  public static Session session(SessionEntity e) {
    return new Session(
        UUID.fromString(e.id),
        e.channel,
        e.userId,
        e.agentName,
        e.providerName,
        e.model,
        Session.Kind.valueOf(e.kind),
        Session.Status.valueOf(e.status),
        Instant.parse(e.createdAt),
        e.archivedAt == null ? null : Instant.parse(e.archivedAt));
  }

  public static SessionMessage message(MessageEntity e) {
    var c = StoredJson.read(e.contentJson, Content.class);
    var m = c.message();
    var opaque =
        e.providerOpaqueJson == null
            ? null
            : StoredJson.read(e.providerOpaqueJson, ChatMessage.ProviderOpaque.class);
    return new SessionMessage(
        UUID.fromString(e.id),
        UUID.fromString(e.sessionId),
        UUID.fromString(e.executionId),
        UUID.fromString(e.turnId),
        e.seq,
        new ChatMessage(m.role(), m.content(), m.toolCalls(), m.toolCallId(), m.toolName(), opaque),
        c.toolOutcome(),
        Instant.parse(e.createdAt));
  }
}
