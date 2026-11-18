package io.github.oryxlabs.oryxos.web.controller;

import static io.github.oryxlabs.oryxos.web.api.RequestLimits.*;

import io.github.oryxlabs.oryxos.web.api.*;
import io.github.oryxlabs.oryxos.web.execution.HttpExecutionAdapter;
import io.github.oryxlabs.oryxos.web.openapi.HttpContract;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@org.springframework.context.annotation.Profile("!us2-local & !us2-real")
public class SystemController {
  private final WebRuntimePort runtime;
  private final HttpExecutionAdapter adapter;

  public SystemController(WebRuntimePort runtime, HttpExecutionAdapter adapter) {
    this.runtime = runtime;
    this.adapter = adapter;
  }

  @GetMapping("/health")
  @HttpContract(
      data = ContractDtos.Health.class,
      errors = {},
      dataErrors = {503})
  public Object health() {
    var h = runtime.health();
    return ResponseEntity.status(h.ready() ? 200 : 503)
        .body(
            ApiResponse.of(
                h.ready() ? "OK" : "EXECUTION_PAUSED",
                h.ready() ? "OK" : "Runtime is not ready",
                h));
  }

  @GetMapping("/info")
  @HttpContract(
      data = ContractDtos.Info.class,
      errors = {500})
  public Object info() {
    return ApiResponse.ok(runtime.info());
  }
}
