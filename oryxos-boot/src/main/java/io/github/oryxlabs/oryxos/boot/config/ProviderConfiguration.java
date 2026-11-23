package io.github.oryxlabs.oryxos.boot.config;

import io.github.oryxlabs.oryxos.core.agent.AgentCatalog;
import io.github.oryxlabs.oryxos.core.audit.AuditSink;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.provider.ProviderPort;
import io.github.oryxlabs.oryxos.core.security.Redactor;
import io.github.oryxlabs.oryxos.core.workspace.WorkspaceHandle;
import io.github.oryxlabs.oryxos.provider.ProviderService;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
public class ProviderConfiguration {
  /** Null catalog on global configuration failure is explicit, never a fabricated empty result. */
  public record LoadedAgents(AgentCatalog catalog, String code, String safeMessage) {}

  @Bean
  Redactor providerRedactor() {
    return new Redactor();
  }

  @Bean
  LoadedAgents loadedAgents(
      RuntimeConfiguration.LoadedRuntime loaded, WorkspaceHandle workspace, Redactor redactor) {
    if (loaded.config() == null) return new LoadedAgents(null, loaded.code(), loaded.safeMessage());
    try {
      return new LoadedAgents(
          AgentCatalog.load(workspace, loaded.config(), System.getenv(), redactor),
          "CONFIGURED",
          "Agent definitions loaded; services not probed");
    } catch (OryxError e) {
      return new LoadedAgents(null, e.code(), e.getMessage());
    }
  }

  @Bean
  ProviderPort providerPort(
      RuntimeConfiguration.LoadedRuntime loaded,
      WorkspaceHandle workspace,
      Redactor redactor,
      AuditSink audit) {
    if (loaded.config() == null)
      return request -> {
        throw new OryxError(loaded.code(), loaded.safeMessage());
      };
    return new ProviderService(loaded.config(), workspace, System.getenv(), redactor, audit);
  }
}
