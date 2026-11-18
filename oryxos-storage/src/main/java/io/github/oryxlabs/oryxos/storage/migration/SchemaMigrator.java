package io.github.oryxlabs.oryxos.storage.migration;

import io.github.oryxlabs.oryxos.core.error.OryxError;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;

/** Explicit, checksummed migrations. The caller must hold the workspace process lock. */
public final class SchemaMigrator {
  public record Migration(int version, String sql) {}

  private final List<Migration> migrations;

  public SchemaMigrator() {
    this(
        List.of(
            new Migration(1, resource("/db/migration/V001__schema_metadata.sql")),
            new Migration(2, resource("/db/migration/V002__memory_ledger.sql")),
            new Migration(3, resource("/db/migration/V003__session_execution.sql")),
            new Migration(4, resource("/db/migration/V004__call_audits.sql")),
            new Migration(5, resource("/db/migration/V005__incidents.sql")),
            new Migration(6, resource("/db/migration/V006__schedules.sql")),
            new Migration(7, resource("/db/migration/V007__bounded_session_history.sql"))));
  }

  public SchemaMigrator(List<Migration> migrations) {
    this.migrations = List.copyOf(migrations);
    int previous = 0;
    for (var migration : migrations) {
      if (migration.version() <= previous)
        throw new IllegalArgumentException("Migration versions must increase");
      previous = migration.version();
    }
  }

  public void migrate(DataSource source) {
    try (var connection = source.getConnection()) {
      connection.setAutoCommit(false);
      try {
        try (var statement = connection.createStatement()) {
          statement.execute(
              "CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, checksum"
                  + " TEXT NOT NULL, applied_at TEXT NOT NULL)");
        }
        Map<Integer, String> applied = new LinkedHashMap<>();
        try (var statement = connection.createStatement();
            var rows =
                statement.executeQuery(
                    "SELECT version, checksum FROM schema_migrations ORDER BY version")) {
          while (rows.next()) applied.put(rows.getInt(1), rows.getString(2));
        }
        if (!migrations.stream().map(Migration::version).toList().containsAll(applied.keySet()))
          throw new SQLException("Unsupported schema version");
        for (var migration : migrations) {
          String hash = checksum(migration.sql());
          if (applied.containsKey(migration.version())) {
            if (!hash.equals(applied.get(migration.version())))
              throw new SQLException("Migration checksum mismatch");
            continue;
          }
          if (applied.keySet().stream().anyMatch(version -> version > migration.version()))
            throw new SQLException("Missing earlier migration");
          try (var statement = connection.createStatement()) {
            for (String sql : statements(migration.sql())) statement.execute(sql);
          }
          try (var statement =
              connection.prepareStatement(
                  "INSERT INTO schema_migrations(version, checksum, applied_at) VALUES(?,?,?)")) {
            statement.setInt(1, migration.version());
            statement.setString(2, hash);
            statement.setString(3, Instant.now().toString());
            statement.executeUpdate();
          }
        }
        connection.commit();
      } catch (Exception error) {
        connection.rollback();
        throw error;
      }
    } catch (Exception error) {
      throw new OryxError(
          "STORAGE_UNAVAILABLE",
          "Migration failed; inspect storage and migration checksums without deleting the"
              + " database");
    }
  }

  private static String checksum(String value) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
  }

  private static String resource(String path) {
    try (var input = SchemaMigrator.class.getResourceAsStream(path)) {
      if (input == null) throw new IllegalStateException("Missing migration resource");
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (java.io.IOException error) {
      throw new IllegalStateException("Unreadable migration resource");
    }
  }

  /** SQLite migration files use ordinary DDL, quotes and comments; no stored procedures. */
  static List<String> statements(String script) {
    List<String> result = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    char quote = 0;
    for (int i = 0; i < script.length(); i++) {
      char c = script.charAt(i), next = i + 1 < script.length() ? script.charAt(i + 1) : 0;
      if (quote != 0) {
        current.append(c);
        if (c == quote) {
          if (next == quote) {
            current.append(next);
            i++;
          } else quote = 0;
        }
      } else if (c == '\'' || c == '"' || c == '`') {
        quote = c;
        current.append(c);
      } else if (c == '-' && next == '-') {
        while (i < script.length() && script.charAt(i) != '\n') i++;
        current.append(' ');
      } else if (c == '/' && next == '*') {
        int end = script.indexOf("*/", i + 2);
        if (end < 0) throw new IllegalArgumentException("Unterminated SQL comment");
        i = end + 1;
        current.append(' ');
      } else if (c == ';') {
        if (!current.toString().isBlank()) result.add(current.toString());
        current.setLength(0);
      } else current.append(c);
    }
    if (quote != 0) throw new IllegalArgumentException("Unterminated SQL quote");
    if (!current.toString().isBlank()) result.add(current.toString());
    return result;
  }
}
