package io.github.oryxlabs.oryxos.storage;

import static org.junit.jupiter.api.Assertions.*;

import io.github.oryxlabs.oryxos.core.workspace.*;
import io.github.oryxlabs.oryxos.storage.lifecycle.*;
import jakarta.persistence.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Tag("f0-foundation")
class StorageLifecycleIntegrationTest {
  @TempDir Path root;

  WorkspaceHandle workspace() {
    return WorkspaceResolver.resolve(root.toString());
  }

  @Entity(name = "Probe")
  @Table(name = "probe")
  public static class Probe {
    @Id String id;
    String value;

    public Probe() {}

    Probe(String id, String value) {
      this.id = id;
      this.value = value;
    }
  }

  @Test
  void sqlitePragmasTransactionsAndJpaSurviveReopen() throws Exception {
    var factory = new StorageResourceFactory();
    try (var lock = WorkspaceProcessLock.acquire(workspace())) {
      StorageResources first = factory.open(workspace(), lock, Probe.class);
      EntityManager stale;
      try (first) {
        assertEquals(4, first.dataSource().getMaximumPoolSize());
        try (var connection = first.dataSource().getConnection();
            var statement = connection.createStatement()) {
          for (var pragma :
              Map.of(
                      "journal_mode",
                      "wal",
                      "synchronous",
                      "2",
                      "foreign_keys",
                      "1",
                      "busy_timeout",
                      "5000")
                  .entrySet()) {
            try (var rows = statement.executeQuery("PRAGMA " + pragma.getKey())) {
              assertTrue(rows.next());
              assertEquals(pragma.getValue(), rows.getString(1));
            }
          }
          try (var resource = getClass().getResourceAsStream("/migration/probe.sql")) {
            for (String sql :
                new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .split(";")) if (!sql.isBlank()) statement.execute(sql);
          }
          assertThrows(
              java.sql.SQLException.class,
              () -> statement.executeUpdate("INSERT INTO child VALUES ('missing')"));
        }
        try (var em = first.openEntityManager()) {
          em.getTransaction().begin();
          em.persist(new Probe("saved", "value"));
          em.getTransaction().commit();
          em.getTransaction().begin();
          em.persist(new Probe("rolled-back", "discard"));
          em.getTransaction().rollback();
        }
        stale = first.openEntityManager();
      }
      assertFalse(first.isOpen());
      assertFalse(stale.isOpen());
      assertTrue(first.dataSource().isClosed());
      try (var second = factory.open(workspace(), lock, Probe.class);
          var em = second.openEntityManager()) {
        assertEquals("value", em.find(Probe.class, "saved").value);
        assertNull(em.find(Probe.class, "rolled-back"));
      }
    }
  }

  @Test
  void checksumMismatchAndCorruptionNeverRecreateDatabase() throws Exception {
    var factory = new StorageResourceFactory();
    try (var lock = WorkspaceProcessLock.acquire(workspace())) {
      try (var resources = factory.open(workspace(), lock);
          var connection = resources.dataSource().getConnection();
          var statement = connection.createStatement()) {
        statement.executeUpdate("UPDATE schema_migrations SET checksum='tampered'");
      }
      assertThrows(RuntimeException.class, () -> factory.open(workspace(), lock));
    }
    Path brokenRoot = Files.createDirectory(root.resolve("broken"));
    var broken = WorkspaceResolver.resolve(brokenRoot.toString());
    Files.writeString(broken.database(), "broken-database-CANARY");
    try (var lock = WorkspaceProcessLock.acquire(broken)) {
      assertThrows(RuntimeException.class, () -> factory.open(broken, lock));
      assertEquals("broken-database-CANARY", Files.readString(broken.database()));
    }
  }

  @Test
  void storageRequiresTheSameWorkspaceLock() throws Exception {
    var other = WorkspaceResolver.resolve(root.resolve("other").toString());
    try (var lock = WorkspaceProcessLock.acquire(other)) {
      assertThrows(
          RuntimeException.class, () -> new StorageResourceFactory().open(workspace(), lock));
      assertFalse(Files.exists(workspace().database()));
    }
  }
}
