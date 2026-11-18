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
public class ExecutionController {
  private final WebRuntimePort runtime;
  private final HttpExecutionAdapter adapter;

  public ExecutionController(WebRuntimePort runtime, HttpExecutionAdapter adapter) {
    this.runtime = runtime;
    this.adapter = adapter;
  }

  @GetMapping("/executions")
  @HttpContract(data = ContractDtos.ExecutionPage.class)
  public Object list(
      @Parameter(schema = @Schema(minimum = "1", maximum = "100", defaultValue = "100"))
          @RequestParam(required = false)
          Integer limit,
      @Parameter(schema = @Schema(maxLength = 1024)) @RequestParam(required = false) String cursor,
      @Parameter(schema = @Schema(description = "Agent名称")) @RequestParam(required = false)
          String agentName,
      @Parameter(schema = @Schema(description = "UUID", format = "uuid"))
          @RequestParam(required = false)
          String sessionId) {
    return ApiResponse.ok(
        runtime.executions(
            limit(limit), cursor, agentName, sessionId == null ? null : uuid(sessionId)));
  }

  @GetMapping("/executions/{id}")
  @HttpContract(data = ContractDtos.Execution.class)
  public Object get(@Parameter(schema = @Schema(description = "资源标识")) @PathVariable String id) {
    return ApiResponse.ok(runtime.execution(uuid(id)));
  }
}
