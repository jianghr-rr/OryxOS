package io.github.oryxlabs.oryxos.web.observability;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Assigns the request {@code traceId} and writes one access log line per {@code /api/v1} request,
 * after the asynchronous response completes when the request went async.
 */
public final class TraceIdFilter extends OncePerRequestFilter {
  public static final String REQUEST_ID_HEADER = "X-Request-ID";
  private static final Logger ACCESS =
      LoggerFactory.getLogger("io.github.oryxlabs.oryxos.web.access");

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = path(request);
    return path.startsWith("/actuator") || path.startsWith("/admin");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String traceId = RequestTrace.accept(request.getHeader(REQUEST_ID_HEADER));
    long started = System.nanoTime();
    boolean logged = path(request).startsWith("/api/v1/");
    MDC.put(RequestTrace.MDC_KEY, traceId);
    try {
      chain.doFilter(request, response);
    } finally {
      if (logged) {
        if (request.isAsyncStarted()) {
          request
              .getAsyncContext()
              .addListener(new AccessOnComplete(request, response, traceId, started));
        } else {
          access(request, response.getStatus(), started);
        }
      }
      MDC.remove(RequestTrace.MDC_KEY);
    }
  }

  private static void access(HttpServletRequest request, int status, long started) {
    ACCESS.info(
        "http_request method={} path={} status={} durationMs={}",
        request.getMethod(),
        path(request),
        status,
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
  }

  private static String path(HttpServletRequest request) {
    String uri = request.getRequestURI();
    String context = request.getContextPath();
    return context != null && !context.isEmpty() && uri.startsWith(context)
        ? uri.substring(context.length())
        : uri;
  }

  private static final class AccessOnComplete implements AsyncListener {
    private final HttpServletRequest request;
    private final HttpServletResponse response;
    private final String traceId;
    private final long started;

    private AccessOnComplete(
        HttpServletRequest request, HttpServletResponse response, String traceId, long started) {
      this.request = request;
      this.response = response;
      this.traceId = traceId;
      this.started = started;
    }

    @Override
    public void onComplete(AsyncEvent event) {
      String previous = MDC.get(RequestTrace.MDC_KEY);
      MDC.put(RequestTrace.MDC_KEY, traceId);
      try {
        access(request, response.getStatus(), started);
      } finally {
        if (previous == null) {
          MDC.remove(RequestTrace.MDC_KEY);
        } else {
          MDC.put(RequestTrace.MDC_KEY, previous);
        }
      }
    }

    @Override
    public void onTimeout(AsyncEvent event) {}

    @Override
    public void onError(AsyncEvent event) {}

    @Override
    public void onStartAsync(AsyncEvent event) {}
  }
}
