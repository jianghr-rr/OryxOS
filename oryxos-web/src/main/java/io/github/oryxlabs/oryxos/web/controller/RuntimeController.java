package io.github.oryxlabs.oryxos.web.controller;

import static io.github.oryxlabs.oryxos.web.api.RequestLimits.*;

import io.github.oryxlabs.oryxos.web.api.*;
import io.github.oryxlabs.oryxos.web.execution.HttpExecutionAdapter;
import io.github.oryxlabs.oryxos.web.openapi.HttpContract;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@org.springframework.context.annotation.Profile("!us2-local & !us2-real")
public class RuntimeController {
  private final WebRuntimePort runtime;
  private final HttpExecutionAdapter adapter;

  public RuntimeController(WebRuntimePort runtime, HttpExecutionAdapter adapter) {
    this.runtime = runtime;
    this.adapter = adapter;
  }

  @GetMapping("/runtime/state")
  @HttpContract(
      data = ContractDtos.RuntimeState.class,
      errors = {500})
  public Object state() {
    return ApiResponse.ok(runtime.state());
  }

  @PostMapping("/runtime/recover")
  @HttpContract(data = ContractDtos.RuntimeState.class)
  public Object recover(
      @Parameter(schema = @Schema(minLength = 1, maxLength = 128)) @RequestHeader("X-Oryx-User-Id")
          String user,
      @RequestBody ContractDtos.RecoverInput input) {
    return ApiResponse.ok(runtime.recover(input, user(user)));
  }
}
