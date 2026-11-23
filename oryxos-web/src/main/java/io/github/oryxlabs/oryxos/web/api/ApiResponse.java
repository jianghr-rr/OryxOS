package io.github.oryxlabs.oryxos.web.api;

import io.github.oryxlabs.oryxos.web.observability.RequestTrace;
import java.time.Instant;

public record ApiResponse<T>(
    String code, String message, T data, Instant timestamp, String traceId) {
  public static <T> ApiResponse<T> of(String code, String message, T data) {
    return new ApiResponse<>(code, message, data, Instant.now(), RequestTrace.currentOrNew());
  }

  public static <T> ApiResponse<T> ok(T data) {
    return of("OK", "OK", data);
  }
}
