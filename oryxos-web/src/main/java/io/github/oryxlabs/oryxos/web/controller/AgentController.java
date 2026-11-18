package io.github.oryxlabs.oryxos.web.controller;

import static io.github.oryxlabs.oryxos.web.api.RequestLimits.*;

import io.github.oryxlabs.oryxos.web.api.*;
import io.github.oryxlabs.oryxos.web.execution.HttpExecutionAdapter;
import io.github.oryxlabs.oryxos.web.openapi.HttpContract;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@org.springframework.context.annotation.Profile("!us2-local & !us2-real")
public class AgentController {
  private final WebRuntimePort runtime;
  private final HttpExecutionAdapter adapter;

  public AgentController(WebRuntimePort runtime, HttpExecutionAdapter adapter) {
    this.runtime = runtime;
    this.adapter = adapter;
  }

  @PostMapping("/agents/{name}/invoke")
  @HttpContract(
      data = ContractDtos.Execution.class,
      errors = {400, 404, 409, 413, 503},
      executionErrors = {422, 500, 504})
  public Object invoke(
      @Parameter(schema = @Schema(description = "资源标识")) @PathVariable String name,
      @Parameter(schema = @Schema(minLength = 1, maxLength = 128)) @RequestHeader("X-Oryx-User-Id")
          String user,
      @RequestBody ContractDtos.MessageInput input,
      HttpServletRequest request,
      HttpServletResponse response) {
    user(user);
    text(input.content());
    adapter.executeAsync(
        request,
        response,
        e -> runtime.invoke(name, user, input.content(), e.at(), e.nanos(), e.observer()));
    return null;
  }
}
