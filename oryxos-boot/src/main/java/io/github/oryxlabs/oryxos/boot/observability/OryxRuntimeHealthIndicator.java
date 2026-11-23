package io.github.oryxlabs.oryxos.boot.observability;

import io.github.oryxlabs.oryxos.web.api.WebRuntimePort;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Readiness follows the same source as {@code /api/v1/health}, so both always agree. Registered as
 * bean {@code oryxRuntime} by the web configuration.
 */
public class OryxRuntimeHealthIndicator implements HealthIndicator {
  private final WebRuntimePort runtime;

  public OryxRuntimeHealthIndicator(WebRuntimePort runtime) {
    this.runtime = runtime;
  }

  @Override
  public Health health() {
    return Boolean.TRUE.equals(runtime.health().ready())
        ? Health.up().build()
        : Health.outOfService().build();
  }
}
