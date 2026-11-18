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
public class CapabilityController {
  private final WebRuntimePort runtime;
  private final HttpExecutionAdapter adapter;

  public CapabilityController(WebRuntimePort runtime, HttpExecutionAdapter adapter) {
    this.runtime = runtime;
    this.adapter = adapter;
  }

  @GetMapping("/profiles")
  @HttpContract(
      data = ContractDtos.AgentPage.class,
      errors = {400, 500})
  public Object profiles(
      @Parameter(schema = @Schema(minimum = "1", maximum = "100", defaultValue = "100"))
          @RequestParam(required = false)
          Integer limit,
      @Parameter(schema = @Schema(maxLength = 1024)) @RequestParam(required = false)
          String cursor) {
    return ApiResponse.ok(runtime.profiles(limit(limit), cursor));
  }

  @GetMapping("/memory")
  @HttpContract(
      data = ContractDtos.MemoryView.class,
      errors = {400, 500, 503})
  public Object memory(
      @Parameter(schema = @Schema(minimum = "1", maximum = "100", defaultValue = "100"))
          @RequestParam(required = false)
          Integer limit,
      @Parameter(schema = @Schema(maxLength = 1024)) @RequestParam(required = false)
          String cursor) {
    return ApiResponse.ok(runtime.memory(limit(limit), cursor));
  }

  @GetMapping("/tools")
  @HttpContract(
      data = ContractDtos.ToolPage.class,
      errors = {400, 404, 500})
  public Object tools(
      @Parameter(schema = @Schema(description = "可选Agent过滤")) @RequestParam(required = false)
          String agentName,
      @Parameter(schema = @Schema(minimum = "1", maximum = "100", defaultValue = "100"))
          @RequestParam(required = false)
          Integer limit,
      @Parameter(schema = @Schema(maxLength = 1024)) @RequestParam(required = false)
          String cursor) {
    return ApiResponse.ok(runtime.tools(agentName, limit(limit), cursor));
  }
}
