package io.github.oryxlabs.oryxos.web.controller;

import static io.github.oryxlabs.oryxos.web.api.RequestLimits.*;

import io.github.oryxlabs.oryxos.web.api.*;
import io.github.oryxlabs.oryxos.web.execution.HttpExecutionAdapter;
import io.github.oryxlabs.oryxos.web.openapi.HttpContract;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@org.springframework.context.annotation.Profile("!us2-local & !us2-real")
public class ScheduleController {
  private final WebRuntimePort runtime;
  private final HttpExecutionAdapter adapter;

  public ScheduleController(WebRuntimePort runtime, HttpExecutionAdapter adapter) {
    this.runtime = runtime;
    this.adapter = adapter;
  }

  @GetMapping("/schedules")
  @HttpContract(data = ContractDtos.SchedulePage.class)
  public Object list(
      @Parameter(schema = @Schema(minimum = "1", maximum = "100", defaultValue = "100"))
          @RequestParam(required = false)
          Integer limit,
      @Parameter(schema = @Schema(maxLength = 1024)) @RequestParam(required = false) String cursor,
      @Parameter(schema = @Schema(description = "Agent名称")) @RequestParam(required = false)
          String agentName) {
    return ApiResponse.ok(runtime.schedules(limit(limit), cursor, agentName));
  }

  @PutMapping("/schedules/{id}/enabled")
  @HttpContract(data = ContractDtos.Schedule.class)
  public Object enabled(
      @Parameter(schema = @Schema(description = "资源标识")) @PathVariable String id,
      @Parameter(schema = @Schema(minLength = 1, maxLength = 128)) @RequestHeader("X-Oryx-User-Id")
          String user,
      @RequestBody ContractDtos.EnabledInput input) {
    user(user);
    if (input.enabled() == null) throw invalid();
    return ApiResponse.ok(runtime.enabled(id, input.enabled()));
  }

  @PostMapping("/schedules/{id}/run")
  @HttpContract(
      data = ContractDtos.Execution.class,
      errors = {400, 404, 409, 503},
      executionErrors = {422, 500, 504})
  public Object run(
      @Parameter(schema = @Schema(description = "资源标识")) @PathVariable String id,
      @Parameter(schema = @Schema(minLength = 1, maxLength = 128)) @RequestHeader("X-Oryx-User-Id")
          String user,
      HttpServletRequest request) {
    user(user);
    return adapter.execute(request, e -> runtime.run(id, user, e.at(), e.nanos(), e.observer()));
  }

  @GetMapping("/schedules/{id}/executions")
  @HttpContract(data = ContractDtos.ScheduleEventPage.class)
  public Object history(
      @Parameter(schema = @Schema(description = "资源标识")) @PathVariable String id,
      @Parameter(schema = @Schema(minimum = "1", maximum = "100", defaultValue = "100"))
          @RequestParam(required = false)
          Integer limit,
      @Parameter(schema = @Schema(maxLength = 1024)) @RequestParam(required = false)
          String cursor) {
    return ApiResponse.ok(runtime.scheduleHistory(id, limit(limit), cursor));
  }
}
