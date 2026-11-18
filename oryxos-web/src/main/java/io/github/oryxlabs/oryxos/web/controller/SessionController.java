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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@org.springframework.context.annotation.Profile("!us2-local & !us2-real")
public class SessionController {
  private final WebRuntimePort runtime;
  private final HttpExecutionAdapter adapter;

  public SessionController(WebRuntimePort runtime, HttpExecutionAdapter adapter) {
    this.runtime = runtime;
    this.adapter = adapter;
  }

  @PostMapping("/sessions")
  @HttpContract(data = ContractDtos.Session.class, success = 201)
  public Object create(
      @Parameter(schema = @Schema(minLength = 1, maxLength = 128)) @RequestHeader("X-Oryx-User-Id")
          String user,
      @RequestBody ContractDtos.CreateSession input) {
    return ResponseEntity.status(201)
        .body(ApiResponse.ok(runtime.create(name(input.agentName()), user(user))));
  }

  @GetMapping("/sessions")
  @HttpContract(data = ContractDtos.SessionPage.class)
  public Object list(
      @Parameter(schema = @Schema(minimum = "1", maximum = "100", defaultValue = "100"))
          @RequestParam(required = false)
          Integer limit,
      @Parameter(schema = @Schema(maxLength = 1024)) @RequestParam(required = false) String cursor,
      @Parameter(schema = @Schema(description = "Agent名称")) @RequestParam(required = false)
          String agentName,
      @Parameter(schema = @Schema(description = "用户筛选")) @RequestParam(required = false)
          String userId,
      @Parameter(schema = @Schema(allowableValues = {"http", "cli", "scheduler"}))
          @RequestParam(required = false)
          String channel,
      @RequestParam(required = false) Boolean archived) {
    return ApiResponse.ok(
        runtime.sessions(limit(limit), cursor, agentName, userId, channel, archived));
  }

  @GetMapping("/sessions/{id}")
  @HttpContract(data = ContractDtos.SessionHistory.class)
  public Object history(
      @Parameter(schema = @Schema(description = "资源标识")) @PathVariable String id,
      @Parameter(schema = @Schema(minimum = "1", maximum = "100", defaultValue = "100"))
          @RequestParam(required = false)
          Integer limit,
      @Parameter(schema = @Schema(minimum = "1", format = "int64")) @RequestParam(required = false)
          Long beforeSeq) {
    return ApiResponse.ok(runtime.history(uuid(id), limit(limit), before(beforeSeq)));
  }

  @DeleteMapping("/sessions/{id}")
  @HttpContract(data = ContractDtos.Session.class)
  public Object archive(
      @Parameter(schema = @Schema(description = "资源标识")) @PathVariable String id,
      @Parameter(schema = @Schema(minLength = 1, maxLength = 128)) @RequestHeader("X-Oryx-User-Id")
          String user) {
    return ApiResponse.ok(runtime.archive(uuid(id), user(user)));
  }

  @PostMapping("/sessions/{id}/messages")
  @HttpContract(
      data = ContractDtos.Execution.class,
      errors = {400, 404, 409, 413, 503},
      executionErrors = {422, 500, 504})
  public Object message(
      @Parameter(schema = @Schema(description = "资源标识")) @PathVariable String id,
      @Parameter(schema = @Schema(minLength = 1, maxLength = 128)) @RequestHeader("X-Oryx-User-Id")
          String user,
      @RequestBody ContractDtos.MessageInput input,
      HttpServletRequest request,
      HttpServletResponse response) {
    var session = uuid(id);
    user(user);
    text(input.content());
    adapter.executeAsync(
        request,
        response,
        e -> runtime.message(session, user, input.content(), e.at(), e.nanos(), e.observer()));
    return null;
  }
}
