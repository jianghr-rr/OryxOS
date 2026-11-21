package io.github.oryxlabs.oryxos.storage.transaction;

import io.github.oryxlabs.oryxos.core.runtime.RuntimeTiming;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/** Bound SQL for the small hot record writes, on the existing JPA-owned transaction/connection. */
public final class StoredSql {
  @FunctionalInterface
  public interface ResultReader<T> {
    T read(ResultSet rows) throws SQLException;
  }

  private static final ThreadLocal<Batch> CURRENT = new ThreadLocal<>();

  private StoredSql() {}

  public static int update(EntityManager em, String sql, Object... values) {
    var batch = CURRENT.get();
    var bound = batch == null ? null : batch.boundConnection();
    if (bound != null) {
      try {
        return updateOnConnection(bound, batch, sql, values);
      } catch (SQLException error) {
        throw new PersistenceException(error);
      }
    }
    return em.unwrap(org.hibernate.Session.class)
        .doReturningWork(connection -> updateOnConnection(connection, batch, sql, values));
  }

  public static <T> T query(
      EntityManager em, String sql, ResultReader<T> reader, Object... values) {
    var batch = CURRENT.get();
    var bound = batch == null ? null : batch.boundConnection();
    if (bound != null) {
      try {
        return queryOnConnection(bound, batch, sql, reader, values);
      } catch (SQLException error) {
        throw new PersistenceException(error);
      }
    }
    return em.unwrap(org.hibernate.Session.class)
        .doReturningWork(connection -> queryOnConnection(connection, batch, sql, reader, values));
  }

  private static int updateOnConnection(
      Connection connection, Batch batch, String sql, Object... values) throws SQLException {
    try (var lease =
        batch == null
            ? new StatementLease(prepare(connection, sql), true)
            : batch.statement(connection, sql)) {
      var statement = lease.statement();
      bindValues(statement, values);
      try (var timing = RuntimeTiming.measure("db.statement.execute")) {
        return statement.executeUpdate();
      }
    }
  }

  private static <T> T queryOnConnection(
      Connection connection, Batch batch, String sql, ResultReader<T> reader, Object... values)
      throws SQLException {
    try (var lease =
        batch == null
            ? new StatementLease(prepare(connection, sql), true)
            : batch.statement(connection, sql)) {
      var statement = lease.statement();
      bindValues(statement, values);
      try (var timing = RuntimeTiming.measure("db.statement.execute");
          var rows = statement.executeQuery()) {
        return reader.read(rows);
      }
    }
  }

  private static void bindValues(PreparedStatement statement, Object... values)
      throws SQLException {
    try (var timing = RuntimeTiming.measure("db.statement.bind")) {
      statement.clearParameters();
      for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
    }
  }

  private static PreparedStatement prepare(Connection connection, String sql) throws SQLException {
    try (var timing = RuntimeTiming.measure("db.statement.prepare")) {
      return connection.prepareStatement(sql);
    }
  }

  private record StatementLease(PreparedStatement statement, boolean owns)
      implements AutoCloseable {
    @Override
    public void close() throws SQLException {
      if (owns) statement.close();
    }
  }

  public static Map<Object, Object> batchAttributes() {
    var batch = CURRENT.get();
    return batch == null ? null : batch.attributes;
  }

  /** One physical transaction only; never retain a pooled connection or statement after release. */
  static Batch batch() {
    if (CURRENT.get() != null) throw new IllegalStateException("Nested SQL batch");
    var batch = new Batch();
    CURRENT.set(batch);
    return batch;
  }

  static final class Batch implements AutoCloseable {
    private final Map<String, PreparedStatement> statements = new LinkedHashMap<>();
    private final Map<Object, Object> attributes = new HashMap<>(8);
    private Connection owner;

    void bind(Connection connection) {
      if (owner == null) owner = connection;
      if (owner != connection) throw new IllegalStateException("SQL batch changed connection");
    }

    Connection boundConnection() {
      return owner;
    }

    StatementLease statement(Connection connection, String sql) throws SQLException {
      bind(connection);
      var statement = statements.get(sql);
      if (statement != null) return new StatementLease(statement, false);
      statement = prepare(connection, sql);
      // A bounded cache; unusual statements still execute and close normally.
      if (statements.size() >= 32) return new StatementLease(statement, true);
      statements.put(sql, statement);
      return new StatementLease(statement, false);
    }

    @Override
    public void close() throws SQLException {
      CURRENT.remove();
      SQLException failure = null;
      for (var statement : statements.values()) {
        try {
          statement.close();
        } catch (SQLException error) {
          if (failure == null) failure = error;
          else failure.addSuppressed(error);
        }
      }
      statements.clear();
      attributes.clear();
      owner = null;
      if (failure != null) throw failure;
    }
  }

  // Called only with compile-time schema identifiers, never user data.
  public static String insert(String table, String columns) {
    return "INSERT INTO "
        + table
        + " ("
        + columns
        + ") VALUES ("
        + String.join(",", Collections.nCopies(columns.split(",").length, "?"))
        + ")";
  }

  public static String upsert(String table, String columns, String guard) {
    return insert(table, columns)
        + " ON CONFLICT(id) DO UPDATE SET "
        + Arrays.stream(columns.split(","))
            .filter(column -> !column.equals("id"))
            .map(column -> column + "=excluded." + column)
            .collect(Collectors.joining(","))
        + " WHERE "
        + guard;
  }
}
