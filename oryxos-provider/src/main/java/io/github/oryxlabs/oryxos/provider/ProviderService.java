package io.github.oryxlabs.oryxos.provider;

import io.github.oryxlabs.oryxos.core.audit.AuditSink;
import io.github.oryxlabs.oryxos.core.config.RuntimeConfig;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.model.ChatMessage;
import io.github.oryxlabs.oryxos.core.provider.ProviderPort;
import io.github.oryxlabs.oryxos.core.provider.ProviderReply;
import io.github.oryxlabs.oryxos.core.provider.ProviderRequest;
import io.github.oryxlabs.oryxos.core.security.Redactor;
import io.github.oryxlabs.oryxos.core.security.SecretResolver;
import io.github.oryxlabs.oryxos.core.workspace.WorkspaceHandle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ProviderService implements ProviderPort, AutoCloseable {
  private final List<SpringAiProvider> owned = new ArrayList<>();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final Map<String, ProviderPort> providers;
  private final Map<String, String> unavailable;

  public ProviderService(
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
    if (closed.compareAndSet(false, true)) {
      owned.forEach(SpringAiProvider::close);
    }
  }

  public ProviderReply call(ProviderRequest request) {
    if (closed.get()) {
      throw new OryxError("PROVIDER_UNAVAILABLE", "Provider registry is closed");
    }
    validate(request);
    var port = providers.get(request.providerName());
    if (port == null) {
      throw new OryxError(
          "PROVIDER_UNAVAILABLE",
          unavailable.getOrDefault(request.providerName(), "Provider name is not configured"));
    }
    return port.call(request);
  }

  private static void validate(ProviderRequest request) {
    if (request.model() == null || request.model().isBlank() || request.iteration() < 1) {
      throw OryxError.configuration("provider request model/iteration");
    }
    var users =
        request.orderedMessages().stream().filter(m -> m.role() == ChatMessage.Role.USER).toList();
    if (users.isEmpty()
        || users.getLast().content() == null
        || users.getLast().content().isBlank()) {
      throw new OryxError("INVALID_TASK", "Provide non-blank task content before calling a model");
    }
    if (request.remainingDuration().isNegative() || request.remainingDuration().isZero()) {
      throw new OryxError("PROVIDER_TIMEOUT", "No time remains for a model call");
    }
  }
}
