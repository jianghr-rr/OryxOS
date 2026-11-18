package io.github.oryxlabs.oryxos.storage.query;

import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.session.*;
import io.github.oryxlabs.oryxos.core.workspace.WorkspaceHandle;
import io.github.oryxlabs.oryxos.storage.session.JpaSessionStore;
import io.github.oryxlabs.oryxos.storage.transaction.StoredJson;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/** File-only read connection: never creates, migrates, mutates state, or initializes a runtime. */
public final class SqliteReadOnlyQueries {
  private final WorkspaceHandle workspace;

  public SqliteReadOnlyQueries(WorkspaceHandle workspace) {
    this.workspace = workspace;
  }

  private String workspaceId() {
    return io.github.oryxlabs.oryxos.core.workspace.WorkspaceInitializer.readId(workspace)
        .toString();
  }

  private Connection open() throws Exception {
    if (!Files.isRegularFile(workspace.database(), LinkOption.NOFOLLOW_LINKS))
      throw new IllegalStateException("Database missing");
    var config = new SQLiteConfig();
    config.setReadOnly(true);
    config.setBusyTimeout(5000);
    var source = new SQLiteDataSource(config);
    source.setUrl("jdbc:sqlite:" + workspace.database().toUri() + "?mode=ro");
    var c = source.getConnection();
    try (var s = c.createStatement()) {
      s.execute("PRAGMA query_only=ON");
    } catch (Exception e) {
      c.close();
      throw e;
    }
    return c;
  }

  public List<Session> sessions(int limit) {
    limit(limit);
    try (var c = open();
        var s =
            c.prepareStatement(
                "SELECT * FROM sessions WHERE workspace_id=? ORDER BY created_at DESC,id LIMIT"
                    + " ?")) {
      s.setString(1, workspaceId());
      s.setInt(2, limit);
      try (var r = s.executeQuery()) {
        var result = new ArrayList<Session>();
        while (r.next()) result.add(session(r));
        return List.copyOf(result);
      }
    } catch (Exception e) {
      throw unavailable();
    }
  }

  public Session session(UUID id) {
    try (var c = open();
        var s = c.prepareStatement("SELECT * FROM sessions WHERE id=? AND workspace_id=?")) {
      s.setString(1, id.toString());
      s.setString(2, workspaceId());
      try (var r = s.executeQuery()) {
        return r.next() ? session(r) : null;
      }
    } catch (Exception e) {
      throw unavailable();
    }
  }

  /** Stable keyset pagination; one extra row tells the HTTP adapter whether a next page exists. */
  public List<Session> sessionPage(
      int limit, UUID after, String agent, String user, String channel, Boolean archived) {
    limit(limit);
    var values = new ArrayList<Object>();
    values.add(workspaceId());
    var where = new StringBuilder("workspace_id=?");
    condition(where, values, "agent_name", agent);
    condition(where, values, "user_id", user);
    condition(where, values, "channel", channel);
    condition(where, values, "status", archived == null ? null : archived ? "ARCHIVED" : "ACTIVE");
    checkCursor("sessions", where.toString(), values, after);
    if (after != null) {
      where.append(" AND id>?");
      values.add(after.toString());
    }
    values.add(limit + 1);
    return rows("SELECT id FROM sessions WHERE " + where + " ORDER BY id LIMIT ?", values).stream()
        .map(r -> session(UUID.fromString(r.get("id").toString())))
        .toList();
  }

  public List<UUID> executionPage(int limit, UUID after, String agent, UUID session) {
    limit(limit);
    var values = new ArrayList<Object>();
    var where = new StringBuilder("1=1");
    condition(where, values, "agent_name", agent);
    condition(where, values, "session_id", session == null ? null : session.toString());
    checkCursor("executions", where.toString(), values, after);
    if (after != null) {
      where.append(" AND id>?");
      values.add(after.toString());
    }
    values.add(limit + 1);
    return rows("SELECT id FROM executions WHERE " + where + " ORDER BY id LIMIT ?", values)
        .stream()
        .map(r -> UUID.fromString(r.get("id").toString()))
        .toList();
  }

  private void checkCursor(String table, String where, List<Object> values, UUID after) {
    if (after == null) return;
    var parameters = new ArrayList<>(values);
    parameters.add(after.toString());
    if (rows("SELECT id FROM " + table + " WHERE " + where + " AND id=?", parameters).isEmpty())
      throw new OryxError("INVALID_INPUT", "Cursor does not belong to the requested result set");
  }

  private static void condition(
      StringBuilder sql, List<Object> values, String column, Object value) {
    if (value != null) {
      sql.append(" AND ").append(column).append("=?");
      values.add(value);
    }
  }

  /**
   * Public history deliberately never selects provider_opaque_json. Latest page returned in seq
   * order.
   */
  public List<SessionMessage> history(UUID id, Long beforeSeq, int limit) {
    limit(limit);
    if (beforeSeq != null && beforeSeq < 1)
      throw new OryxError("INVALID_INPUT", "beforeSeq must be positive");
    try (var c = open();
        var s =
            c.prepareStatement(
                "SELECT id,session_id,execution_id,turn_id,seq,content_json,created_at FROM"
                    + " messages WHERE session_id=? AND seq<? ORDER BY seq DESC LIMIT ?")) {
      s.setString(1, id.toString());
      s.setLong(2, beforeSeq == null ? Long.MAX_VALUE : beforeSeq);
      s.setInt(3, limit);
      try (var r = s.executeQuery()) {
        var result = new ArrayList<SessionMessage>();
        while (r.next()) {
          var content = StoredJson.read(r.getString("content_json"), JpaSessionStore.Content.class);
          result.add(
              new SessionMessage(
                  UUID.fromString(r.getString("id")),
                  id,
                  UUID.fromString(r.getString("execution_id")),
                  UUID.fromString(r.getString("turn_id")),
                  r.getLong("seq"),
                  content.message(),
                  content.toolOutcome(),
                  Instant.parse(r.getString("created_at"))));
        }
        Collections.reverse(result);
        return List.copyOf(result);
      }
    } catch (Exception e) {
      throw unavailable();
    }
  }

  public List<Map<String, Object>> executions(UUID sessionId, int limit) {
    limit(limit);
    return rows(
        "SELECT * FROM executions"
            + (sessionId == null ? "" : " WHERE session_id=?")
            + " ORDER BY started_at DESC,id LIMIT ?",
        sessionId == null ? List.of(limit) : List.of(sessionId.toString(), limit));
  }

  public List<Map<String, Object>> modelAttempts(UUID execution) {
    return rows(
        "SELECT * FROM llm_calls WHERE execution_id=? ORDER BY iteration,attempt",
        List.of(execution.toString()));
  }

  public List<Map<String, Object>> toolAttempts(UUID execution) {
    return rows(
        "SELECT * FROM tool_invocations WHERE execution_id=? ORDER BY"
            + " started_at,tool_call_id,attempt",
        List.of(execution.toString()));
  }

  private List<Map<String, Object>> rows(String sql, List<?> parameters) {
    try (var c = open();
        var s = c.prepareStatement(sql)) {
      for (int i = 0; i < parameters.size(); i++) s.setObject(i + 1, parameters.get(i));
      try (var r = s.executeQuery()) {
        var result = new ArrayList<Map<String, Object>>();
        var meta = r.getMetaData();
        while (r.next()) {
          var row = new LinkedHashMap<String, Object>();
          for (int i = 1; i <= meta.getColumnCount(); i++)
            row.put(meta.getColumnLabel(i), r.getObject(i));
          result.add(Collections.unmodifiableMap(row));
        }
        return List.copyOf(result);
      }
    } catch (Exception e) {
      throw unavailable();
    }
  }

  private static Session session(ResultSet r) throws SQLException {
    return new Session(
        UUID.fromString(r.getString("id")),
        r.getString("channel"),
        r.getString("user_id"),
        r.getString("agent_name"),
        r.getString("provider_name"),
        r.getString("model"),
        Session.Kind.valueOf(r.getString("kind")),
        Session.Status.valueOf(r.getString("status")),
        Instant.parse(r.getString("created_at")),
        r.getString("archived_at") == null ? null : Instant.parse(r.getString("archived_at")));
  }

  private static void limit(int n) {
    if (n < 1 || n > 100) throw new OryxError("INVALID_INPUT", "Page size must be 1–100");
  }

  private static OryxError unavailable() {
    return new OryxError(
        "STORAGE_UNAVAILABLE", "Stored data cannot be read; database was not created or repaired");
  }
}
