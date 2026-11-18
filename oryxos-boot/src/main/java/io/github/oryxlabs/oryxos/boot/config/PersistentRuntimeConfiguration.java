package io.github.oryxlabs.oryxos.boot.config;

import io.github.oryxlabs.oryxos.boot.runtime.*;
import io.github.oryxlabs.oryxos.core.audit.AuditSink;
import io.github.oryxlabs.oryxos.core.execution.ExecutionStore;
import io.github.oryxlabs.oryxos.core.runtime.*;
import io.github.oryxlabs.oryxos.core.security.Redactor;
import io.github.oryxlabs.oryxos.core.session.SessionStore;
import io.github.oryxlabs.oryxos.core.workspace.*;
import io.github.oryxlabs.oryxos.storage.audit.JpaAuditSink;
import io.github.oryxlabs.oryxos.storage.execution.JpaExecutionStore;
import io.github.oryxlabs.oryxos.storage.session.JpaSessionStore;
import io.github.oryxlabs.oryxos.storage.transaction.WorkspaceWriteCoordinator;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
@Profile("!us2-local & !us2-real")
public class PersistentRuntimeConfiguration {
  @Bean
  WorkspaceWriteCoordinator workspaceWriteCoordinator(StorageLifecycle storage, RuntimeGate gate) {
    return new WorkspaceWriteCoordinator(storage::resources, gate);
  }

  @Bean(destroyMethod = "close")
  RuntimeStartupCheck runtimeStartupCheck(
      WorkspaceHandle workspace,
      WorkspaceProcessLock lock,
      RuntimeGate gate,
      StorageLifecycle storage,
      WorkspaceWriteCoordinator writes,
      RuntimeConfiguration.LoadedRuntime loaded) {
    return new RuntimeStartupCheck(workspace, lock, gate, storage, writes, loaded.config() != null);
  }

  @Bean
  SessionStore sessionStore(
      WorkspaceHandle workspace, WorkspaceWriteCoordinator writes, RuntimeStartupCheck startup) {
    java.util.UUID id;
    try {
      id = WorkspaceInitializer.readId(workspace);
    } catch (RuntimeException e) {
      id = null;
    }
    return new JpaSessionStore(id, writes);
  }

  @Bean
  ExecutionStore executionStore(WorkspaceWriteCoordinator writes, RuntimeStartupCheck startup) {
    return new JpaExecutionStore(writes);
  }

  @Bean
  AuditSink modelAuditSink(
      WorkspaceWriteCoordinator writes, RuntimeStartupCheck startup, Redactor redactor) {
    return new JpaAuditSink(writes, redactor);
  }

  @Bean
  RecoveryPort runtimeRecoveryService(RuntimeStartupCheck startup, Redactor redactor) {
    return new RuntimeRecoveryService(startup, redactor);
  }
}
