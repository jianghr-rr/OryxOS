package io.github.oryxlabs.oryxos.boot.config;

import io.github.oryxlabs.oryxos.core.config.SafeYaml;
import io.github.oryxlabs.oryxos.core.memory.*;
import io.github.oryxlabs.oryxos.core.security.Redactor;
import io.github.oryxlabs.oryxos.core.session.SessionHistoryPort;
import io.github.oryxlabs.oryxos.core.workspace.*;
import io.github.oryxlabs.oryxos.memory.*;
import io.github.oryxlabs.oryxos.storage.memory.JpaMemoryRecordStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
public class MemoryConfiguration {
  public record SelectedMemory(LongTermMemoryStore store, String code) implements AutoCloseable {
    public void close() {
      if (store != null) store.close();
    }
  }

  @Bean
  MemoryRecordStore memoryRecordStore(
      StorageLifecycle lifecycle,
      ObjectProvider<io.github.oryxlabs.oryxos.storage.transaction.WorkspaceWriteCoordinator>
          writes) {
    return new JpaMemoryRecordStore(lifecycle::resources, writes.getIfAvailable());
  }

  @Bean(destroyMethod = "close")
  SelectedMemory selectedMemory(
      RuntimeConfiguration.LoadedRuntime loaded,
      WorkspaceHandle workspace,
      WorkspaceProcessLock lock,
      StorageLifecycle storage,
      MemoryRecordStore records,
      Redactor redactor) {
    if (loaded.config() == null) return new SelectedMemory(null, "MEMORY_UNAVAILABLE");
    try {
      return new SelectedMemory(
          new MemoryBackendFactory()
              .create(loaded.config(), workspace, lock, records, System.getenv(), redactor),
          "AVAILABLE");
    } catch (RuntimeException e) {
      return new SelectedMemory(null, "MEMORY_UNAVAILABLE");
    }
  }

  @Bean
  MemoryPort memoryPort(
      SelectedMemory selected,
      ObjectProvider<SessionHistoryPort> histories,
      RuntimeConfiguration.LoadedRuntime loaded) {
    var history = histories.getIfAvailable();
    if (selected.store() == null || history == null || loaded.config() == null) return null;
    return new MemoryService(
        history,
        selected.store(),
        SafeYaml.positive(
            loaded.config().memoryRules().getOrDefault("archival_context_characters", 4000),
            "memory context bound"));
  }
}
