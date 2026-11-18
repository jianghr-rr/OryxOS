package io.github.oryxlabs.oryxos.boot.support;

import io.github.oryxlabs.oryxos.core.runtime.RuntimeTiming;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/** Test-only outer servlet boundary, including MVC async completion and response serialization. */
@TestConfiguration(proxyBeanMethods = false)
public class SoakTiming {
  public static final ConcurrentMap<String, Span> SPANS = new ConcurrentHashMap<>();

  public static final class Span {
    volatile long start;
    public final List<RuntimeTiming.Segment> segments =
        Collections.synchronizedList(new ArrayList<>());
    public final CompletableFuture<Long> elapsed = new CompletableFuture<>();

    public long startNanos() {
      return start;
    }

    void finish() {
      elapsed.complete(System.nanoTime() - start);
    }
  }

  @Bean
  FilterRegistrationBean<Filter> soakBoundary() {
    Filter filter =
        (request, response, chain) -> {
          var http = (HttpServletRequest) request;
          String key = http.getHeader("X-Oryx-Soak-Request");
          Span span = key == null ? null : SPANS.get(key);
          if (span == null) {
            chain.doFilter(request, response);
            return;
          }
          span.start = System.nanoTime();
          var async = new AtomicBoolean();
          var wrapper =
              new HttpServletRequestWrapper(http) {
                AsyncContext observe(AsyncContext context) {
                  async.set(true);
                  context.addListener(
                      new AsyncListener() {
                        public void onComplete(AsyncEvent event) {
                          span.finish();
                        }

                        public void onTimeout(AsyncEvent event) {}

                        public void onError(AsyncEvent event) {}

                        public void onStartAsync(AsyncEvent event) {
                          event.getAsyncContext().addListener(this);
                        }
                      });
                  return context;
                }

                @Override
                public AsyncContext startAsync() {
                  return observe(super.startAsync());
                }

                @Override
                public AsyncContext startAsync(ServletRequest r, ServletResponse s) {
                  return observe(super.startAsync(r, s));
                }
              };
          try (var timing = RuntimeTiming.bind(span.segments::add)) {
            chain.doFilter(wrapper, response);
          } finally {
            if (!async.get()) span.finish();
          }
        };
    var bean = new FilterRegistrationBean<Filter>(filter);
    bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
    bean.setAsyncSupported(true);
    bean.setDispatcherTypes(DispatcherType.REQUEST);
    return bean;
  }

  /** Never subtract provider serialization, transport, queueing, recording, or oversleep. */
  public static long deductibleWait(long observedSleep, long configuredSleep) {
    if (observedSleep < 0 || configuredSleep < 0)
      throw new IllegalArgumentException("negative wait");
    return Math.min(observedSleep, configuredSleep);
  }

  /**
   * Conservative upper bound also includes transport/client overhead, never subtracts ingress
   * queues.
   */
  public static long ingressIncludedInternal(long server, long client, long deducted) {
    if (server < 0 || client < 0 || deducted < 0 || deducted > server)
      throw new IllegalArgumentException("Invalid duration");
    return Math.max(server, client) - deducted;
  }

  public static double percentileMillis(List<Long> values, double p) {
    if (values.isEmpty()) return Double.NaN;
    var sorted = values.stream().mapToLong(Long::longValue).sorted().toArray();
    return sorted[Math.max(0, (int) Math.ceil(sorted.length * p) - 1)] / 1_000_000d;
  }
}
