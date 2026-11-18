package io.github.oryxlabs.oryxos.provider;

import io.github.oryxlabs.oryxos.core.audit.AuditSink;
import io.github.oryxlabs.oryxos.core.config.RuntimeConfig;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.provider.*;
import io.github.oryxlabs.oryxos.core.security.Redactor;
import io.github.oryxlabs.oryxos.core.security.SecretResolver;
import io.github.oryxlabs.oryxos.core.workspace.WorkspaceHandle;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ProviderRegistry implements ProviderPort, AutoCloseable {
  private final java.util.List<SpringAiProvider> owned = new java.util.ArrayList<>();
  private final java.util.concurrent.atomic.AtomicBoolean closed =
      new java.util.concurrent.atomic.AtomicBoolean();
  private final Map<String, ProviderPort> providers;
  private final Map<String, String> unavailable;

  public ProviderRegistry(
      RuntimeConfig config,
      WorkspaceHandle workspace,
      Map<String, String> environment,
      Redactor redactor,
      AuditSink audit) {
    var ports = new LinkedHashMap<String, ProviderPort>();
    var errors = new LinkedHashMap<String, String>();
    var secrets =
        new SecretResolver(environment, workspace.root().resolve("secrets.local.yaml"), redactor);
    for (var definition : config.providers().values()) {
      if (!definition.available()) {
        errors.put(definition.name(), definition.error().safeMessage());
        continue;
      }
      try {
        var provider =
            new SpringAiProvider(definition, secrets.resolve(definition.apiKeyReference()));
        owned.add(provider);
        ports.put(definition.name(), new AuditedProvider(provider, audit, redactor));
      } catch (OryxError error) {
        errors.put(definition.name(), redactor.redact(error.getMessage()));
      }
    }
    providers = Map.copyOf(ports);
    unavailable = Map.copyOf(errors);
  }

  public void close() {
    if (closed.compareAndSet(false, true)) owned.forEach(SpringAiProvider::close);
  }

  public ProviderReply call(ProviderRequest request) {
    if (closed.get()) throw new OryxError("PROVIDER_UNAVAILABLE", "Provider registry is closed");
    SpringAiProvider.validate(request);
    var port = providers.get(request.providerName());
    if (port == null)
      throw new OryxError(
          "PROVIDER_UNAVAILABLE",
          unavailable.getOrDefault(request.providerName(), "Provider name is not configured"));
    return port.call(request);
  }
}
