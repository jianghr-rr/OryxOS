package io.github.oryxlabs.oryxos.web.observability;

import io.github.oryxlabs.oryxos.core.runtime.Uuids;
import java.util.regex.Pattern;
import org.slf4j.MDC;

/** The request's {@code traceId}: response envelopes and log lines read the same MDC value. */
public final class RequestTrace {
      public static final String MDC_KEY =   "traceId";
  private static final Pattern ACCEPTED = Pattern.compile("^[A-Za-z0-9._:-]{8,64}$");

  private RequestTrace() {}

  public static String currentOrNew() {
    String current = MDC.get(MDC_KEY);
    return current != null ? current : newId();
  }

  /** Reuses a well-formed upstream {@code X-Request-ID}; anything else gets a fresh UUID v4. */
  static String accept(String requested) {
    return requested != null && ACCEPTED.matcher(requested).matches() ? requested : newId();
  }

  private static String newId() {
    return Uuids.v4().toString();
  }
}
