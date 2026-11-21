package io.github.oryxlabs.oryxos.web.api;

import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.security.Redactor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ApiExceptionHandler {
  private final Redactor redactor;

  public ApiExceptionHandler(Redactor redactor) {
    this.redactor = redactor;
  }

  public static int status(String code) {
    return switch (code) {
      case "NOT_FOUND" -> 404;
      case "MESSAGE_TOO_LARGE" -> 413;
      case "SESSION_BUSY",
          "SESSION_ARCHIVED",
          "SESSION_IDENTITY_MISMATCH",
          "SESSION_MODEL_CHANGED",
          "AGENT_UNAVAILABLE",
          "SCHEDULE_BUSY",
          "RECOVERY_NOT_READY" ->
          409;
      case "INVALID_INPUT", "INVALID_CONFIG", "CONFIGURATION_ERROR" -> 400;
      case "DEADLINE", "EXECUTION_TIMEOUT", "PROVIDER_TIMEOUT" -> 504;
      case "EXECUTION_FAILED", "ITERATION_LIMIT" -> 422;
      case "EXECUTION_PAUSED",
          "OVERLOADED",
          "MEMORY_UNAVAILABLE",
          "RUNTIME_NOT_READY",
          "RECOVERY_UNAVAILABLE",
          "PROVIDER_UNAVAILABLE",
          "PROVIDER_REJECTED",
          "PROVIDER_PROTOCOL",
          "PROVIDER_AUTHENTICATION" ->
          503;
      default -> code.startsWith("PROVIDER_") ? 503 : 500;
    };
  }

  public ResponseEntity<ApiResponse<ContractDtos.ErrorDetail>> response(Throwable error) {
    String code = error instanceof OryxError e ? e.code() : "INTERNAL_ERROR";
    if (code.equals("RECORDING_FAILURE")) code = "STORAGE_UNAVAILABLE";
    if (code.startsWith("PROVIDER_"))
      code = code.equals("PROVIDER_TIMEOUT") ? "EXECUTION_TIMEOUT" : "PROVIDER_UNAVAILABLE";
    if (code.equals("DEADLINE")) code = "EXECUTION_TIMEOUT";
    String message =
        error instanceof OryxError
            ? redactor.redact(error.getMessage())
            : "Request failed; inspect runtime diagnostics";
    return ResponseEntity.status(status(code))
        .body(
            ApiResponse.of(
                code, message, new ContractDtos.ErrorDetail(code, message, null, null, null)));
  }

  @ExceptionHandler(OryxError.class)
  public ResponseEntity<?> business(OryxError e) {
    return response(e);
  }

  @ExceptionHandler({
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.bind.ServletRequestBindingException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
    org.springframework.web.bind.MethodArgumentNotValidException.class
  })
  public ResponseEntity<?> input(Exception ignored) {
    return response(RequestLimits.invalid());
  }

  @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
  public ResponseEntity<?> missing(Exception ignored) {
    return response(new OryxError("NOT_FOUND", "Resource does not exist"));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<?> unexpected(Exception e) {
    return response(e);
  }
}
