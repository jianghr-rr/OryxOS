package io.github.oryxlabs.oryxos.web.api;

import io.github.oryxlabs.oryxos.core.runtime.Uuids;
import java.time.Instant;

public record ApiResponse<T>(
    String code, String message, T data, Instant timestamp, String traceId) {
  public static <T> ApiResponse<T> of(String code, String message, T data) {
    return new ApiResponse<>(code, message, data, Instant.now(), Uuids.v4().toString());
  }

  public static <T> ApiResponse<T> ok(T data) {
    return of("OK", "OK", data);
  }
}
