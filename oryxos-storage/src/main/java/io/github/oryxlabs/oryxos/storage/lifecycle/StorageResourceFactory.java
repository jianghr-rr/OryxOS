package io.github.oryxlabs.oryxos.storage.lifecycle;

import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.workspace.*;
import io.github.oryxlabs.oryxos.storage.migration.SchemaMigrator;
import io.github.oryxlabs.oryxos.storage.sqlite.SqliteConnectionFactory;
import org.hibernate.cfg.Configuration;

public class StorageResourceFactory {
  public StorageResources open(
      WorkspaceHandle workspace, WorkspaceProcessLock lock, Class<?>... entities) {
    if (lock == null || !lock.owns(workspace))
      throw new OryxError(
          "WORKSPACE_BUSY", "Storage creation requires this workspace's process lock");
    var pool = new SqliteConnectionFactory().create(workspace);
    try {
      new SchemaMigrator().migrate(pool);
      Configuration configuration = new Configuration();
      configuration.getProperties().put("hibernate.connection.datasource", pool);
      configuration.setProperty(
          "hibernate.dialect", "org.hibernate.community.dialect.SQLiteDialect");
      configuration.setProperty("hibernate.hbm2ddl.auto", "none");
      configuration.setProperty("hibernate.cache.use_second_level_cache", "false");
      configuration.setProperty("hibernate.cache.use_query_cache", "false");
      configuration.setProperty("hibernate.show_sql", "false");
      configuration.addAnnotatedClass(
          io.github.oryxlabs.oryxos.storage.memory.MemoryEntryEntity.class);
      configuration.addAnnotatedClass(
          io.github.oryxlabs.oryxos.storage.memory.RemoteLinkEntity.class);
      configuration.addAnnotatedClass(
          io.github.oryxlabs.oryxos.storage.session.SessionEntity.class);
      configuration.addAnnotatedClass(
          io.github.oryxlabs.oryxos.storage.session.MessageEntity.class);
      configuration.addAnnotatedClass(
          io.github.oryxlabs.oryxos.storage.execution.ExecutionEntity.class);
      configuration.addAnnotatedClass(
          io.github.oryxlabs.oryxos.storage.runtime.WorkspaceStateEntity.class);
      configuration.addAnnotatedClass(io.github.oryxlabs.oryxos.storage.audit.LlmCallEntity.class);
      configuration.addAnnotatedClass(
          io.github.oryxlabs.oryxos.storage.audit.ToolInvocationEntity.class);
      configuration.addAnnotatedClass(
          io.github.oryxlabs.oryxos.storage.runtime.IncidentEntity.class);
      configuration.addAnnotatedClass(
          io.github.oryxlabs.oryxos.storage.schedule.ScheduleStateEntity.class);
      configuration.addAnnotatedClass(
          io.github.oryxlabs.oryxos.storage.schedule.ScheduleEventEntity.class);
      for (Class<?> entity : entities) configuration.addAnnotatedClass(entity);
      return new StorageResources(pool, configuration.buildSessionFactory());
    } catch (Exception error) {
      pool.close();
      throw new OryxError(
          "STORAGE_UNAVAILABLE",
          "Storage resource creation failed; no business execution is available");
    }
  }
}
