package io.github.oryxlabs.oryxos.storage;

import static org.junit.jupiter.api.Assertions.*;

import io.github.oryxlabs.oryxos.core.workspace.*;
import io.github.oryxlabs.oryxos.storage.lifecycle.StorageResourceFactory;
import io.github.oryxlabs.oryxos.storage.migration.SchemaMigrator;
import io.github.oryxlabs.oryxos.storage.sqlite.SqliteConnectionFactory;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Tag("us5-schedules")
class ScheduleStorageContractTest {
  @TempDir Path root;

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void historyOrdersFractionalInstantsAndRejectsForeignCursorsWithoutPausing() throws Exception {
    var workspace = new WorkspaceHandle(root);
    new WorkspaceInitializer().initialize(workspace, Map.of());
    try (var lock = WorkspaceProcessLock.acquire(workspace);
        var resources = new StorageResourceFactory().open(workspace, lock)) {
      var gate = new io.github.oryxlabs.oryxos.core.runtime.RuntimeGate();
      gate.readyAtFirstStartup();
      var writes =
          new io.github.oryxlabs.oryxos.storage.transaction.WorkspaceWriteCoordinator(
              () -> resources, gate);
      var store = new io.github.oryxlabs.oryxos.storage.schedule.JpaScheduleStore(writes);
      var at = java.time.Instant.parse("2026-11-13T00:00:00Z");
      store.reconcile(
          List.of(
              new io.github.oryxlabs.oryxos.core.schedule.ScheduleDefinition(
                  "agent:daily",
                  "agent",
                  "daily",
                  "0 0 8 * * *",
                  java.time.ZoneId.of("Asia/Shanghai"),
                  "task",
                  true,
                  60)),
          at);
      UUID older = UUID.randomUUID(), newer = UUID.randomUUID();
      for (var event :
          List.of(
              new io.github.oryxlabs.oryxos.core.schedule.ScheduleEvent(
                  older,
                  "agent:daily",
                  null,
                  io.github.oryxlabs.oryxos.core.execution.ProcessRequest.Trigger.MANUAL,
                  null,
                  io.github.oryxlabs.oryxos.core.schedule.ScheduleEvent.Status.SKIPPED_OVERLAP,
                  "SCHEDULE_BUSY",
                  at.plusMillis(100)),
              new io.github.oryxlabs.oryxos.core.schedule.ScheduleEvent(
                  newer,
                  "agent:daily",
                  null,
                  io.github.oryxlabs.oryxos.core.execution.ProcessRequest.Trigger.MANUAL,
                  null,
                  io.github.oryxlabs.oryxos.core.schedule.ScheduleEvent.Status.SKIPPED_PAUSED,
                  "EXECUTION_PAUSED",
                  at.plusMillis(110)))) store.record(event);
      assertEquals(
          List.of(newer, older),
          store.history("agent:daily", 100, null).stream().map(e -> e.id()).toList());
      assertEquals(older, store.history("agent:daily", 1, newer).getFirst().id());
      assertEquals(
          "INVALID_INPUT",
          assertThrows(
                  io.github.oryxlabs.oryxos.core.error.OryxError.class,
                  () -> store.history("foreign:task", 100, newer))
              .code());
      assertEquals(io.github.oryxlabs.oryxos.core.runtime.RuntimeGate.State.READY, gate.state());
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void v5UpgradePreservesSessionExecutionAuditAndMigrationChecksums() throws Exception {
    var workspace = new WorkspaceHandle(root);
    UUID wid = new WorkspaceInitializer().initialize(workspace, Map.of());
    UUID session = UUID.randomUUID(),
        execution = UUID.randomUUID(),
        turn = UUID.randomUUID(),
        audit = UUID.randomUUID();
    Map<Integer, String> before;
    try (var lock = WorkspaceProcessLock.acquire(workspace)) {
      try (var pool = new SqliteConnectionFactory().create(workspace)) {
        var migrations = new ArrayList<SchemaMigrator.Migration>();
        var names =
            List.of(
                "V001__schema_metadata.sql",
                "V002__memory_ledger.sql",
                "V003__session_execution.sql",
                "V004__call_audits.sql",
                "V005__incidents.sql");
        for (int i = 0; i < names.size(); i++)
          try (var in = getClass().getResourceAsStream("/db/migration/" + names.get(i))) {
            migrations.add(
                new SchemaMigrator.Migration(
                    i + 1, new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
          }
        new SchemaMigrator(migrations).migrate(pool);
        try (var c = pool.getConnection();
            var s = c.createStatement()) {
          s.executeUpdate(
              "INSERT INTO"
                  + " sessions(id,workspace_id,channel,user_id,agent_name,provider_name,model,kind,status,created_at)"
                  + " VALUES('"
                  + session
                  + "','"
                  + wid
                  + "','cli','alice','agent','provider','model','CONVERSATION','ACTIVE','2026-11-13T00:00:00Z')");
          s.executeUpdate(
              "INSERT INTO"
                  + " executions(id,session_id,turn_id,agent_name,channel,user_id,trigger_type,config_revision,started_at,deadline_at,status,recording_status,in_flight_count,has_tool_errors,has_unresolved_tool_errors,timezone,tool_name_map_json)"
                  + " VALUES('"
                  + execution
                  + "','"
                  + session
                  + "','"
                  + turn
                  + "','agent','cli','alice','MANUAL','old-revision','2026-11-13T00:00:00Z','2026-11-13T00:01:00Z','SUCCEEDED','COMPLETE',0,0,0,'Asia/Shanghai','{}')");
          s.executeUpdate(
              "INSERT INTO"
                  + " llm_calls(id,execution_id,iteration,attempt,provider,model,requested_model,status,started_at)"
                  + " VALUES('"
                  + audit
                  + "','"
                  + execution
                  + "',1,1,'provider','actual-model','model','SUCCESS','2026-11-13T00:00:00Z')");
          before = checksums(c);
          assertEquals(5, before.size());
        }
      }
      try (var resources = new StorageResourceFactory().open(workspace, lock);
          var c = resources.dataSource().getConnection();
          var s = c.createStatement()) {
        var after = checksums(c);
        assertEquals(7, after.size());
        before.forEach((version, hash) -> assertEquals(hash, after.get(version)));
        try (var rows =
            s.executeQuery("SELECT provider_name,model FROM sessions WHERE id='" + session + "'")) {
          assertTrue(rows.next());
          assertEquals("provider", rows.getString(1));
          assertEquals("model", rows.getString(2));
        }
        try (var rows =
            s.executeQuery(
                "SELECT status,config_revision FROM executions WHERE id='" + execution + "'")) {
          assertTrue(rows.next());
          assertEquals("SUCCEEDED", rows.getString(1));
          assertEquals("old-revision", rows.getString(2));
        }
        try (var rows =
            s.executeQuery("SELECT model,input_tokens FROM llm_calls WHERE id='" + audit + "'")) {
          assertTrue(rows.next());
          assertEquals("actual-model", rows.getString(1));
          assertNull(rows.getObject(2));
        }
        var columns = new HashSet<String>();
        try (var rows = s.executeQuery("PRAGMA table_info(scheduled_tasks)")) {
          while (rows.next()) columns.add(rows.getString("name"));
        }
        assertEquals(
            Set.of(
                "id",
                "agent_name",
                "definition_hash",
                "enabled",
                "configured_present",
                "last_execution_id",
                "updated_at"),
            columns);
        assertThrows(
            SQLException.class,
            () ->
                s.executeUpdate(
                    "INSERT INTO task_executions(id,schedule_id,trigger_kind,status,created_at)"
                        + " VALUES('"
                        + UUID.randomUUID()
                        + "','missing:task','MANUAL','SKIPPED_PAUSED','2026-11-13T00:00:00Z')"));
        try (var rows = s.executeQuery("SELECT count(*) FROM task_executions")) {
          assertEquals(0, rows.getInt(1));
        }
      }
    }
  }

  static Map<Integer, String> checksums(Connection c) throws Exception {
    var result = new TreeMap<Integer, String>();
    try (var s = c.createStatement();
        var rows = s.executeQuery("SELECT version,checksum FROM schema_migrations")) {
      while (rows.next()) result.put(rows.getInt(1), rows.getString(2));
    }
    return result;
  }
}
