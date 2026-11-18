package io.github.oryxlabs.oryxos.boot.config;

import io.github.oryxlabs.oryxos.core.config.*;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.runtime.*;
import io.github.oryxlabs.oryxos.core.security.Redactor;
import io.github.oryxlabs.oryxos.core.workspace.*;
import io.github.oryxlabs.oryxos.storage.lifecycle.StorageResourceFactory;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class RuntimeConfiguration {
  private static final Logger LOG = LoggerFactory.getLogger(RuntimeConfiguration.class);

  public record LoadedRuntime(RuntimeConfig config, String code, String safeMessage) {}

  @Bean
  @ConditionalOnMissingBean(WorkspaceHandle.class)
  WorkspaceHandle workspaceHandle(Environment environment) {
    // Used by direct Spring test/bootstrap only. The executable launcher supplies the
    // already-resolved bean.
    return WorkspaceResolver.resolve(environment.getProperty("oryxos.root"));
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean(WorkspaceProcessLock.class)
  WorkspaceProcessLock workspaceProcessLock(WorkspaceHandle workspace) {
    return WorkspaceProcessLock.acquire(workspace);
  }

  @Bean
  RuntimeGate runtimeGate() {
    return new RuntimeGate();
  }

  @Bean
  StorageResourceFactory storageResourceFactory() {
    return new StorageResourceFactory();
  }

  @Bean
  LoadedRuntime loadedRuntime(WorkspaceHandle workspace) {
    try {
      WorkspaceInitializer.readId(workspace);
      var config = new RuntimeConfigLoader(System.getenv(), new Redactor()).load(workspace);
      LOG.info(
          "Workspace={} timezone={} backend={}; only this memory backend is visible; no automatic"
              + " migration; execution readiness depends on session/execution ports",
          workspace.root(),
          config.timezone(),
          config.memoryBackend());
      return new LoadedRuntime(
          config, "CONFIGURED", "Configuration loaded; provider accounts are not probed");
    } catch (OryxError error) {
      LOG.warn("F0 diagnostic: {}: {}", error.code(), error.getMessage());
      return new LoadedRuntime(null, error.code(), error.getMessage());
    }
  }

  @Bean(destroyMethod = "close")
  StorageLifecycle storageLifecycle(
      StorageResourceFactory factory,
      WorkspaceHandle workspace,
      WorkspaceProcessLock lock,
      RuntimeGate gate,
      LoadedRuntime loaded) {
    var lifecycle = new StorageLifecycle(factory, workspace, lock, gate);
    if (loaded.config() != null) lifecycle.rebuild();
    LOG.info(
        "Storage initialization: storage={} admission={}; runtime readiness is reported by"
            + " /api/v1/health",
        lifecycle.availability().code(),
        gate.state());
    return lifecycle;
  }
}
