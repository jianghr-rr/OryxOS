package io.github.oryxlabs.oryxos.tool.http;

import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.execution.ExecutionContext;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.hc.client5.http.config.*;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.pool.PoolConcurrencyPolicy;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;

/** Shared physical transport; retries only belong to ToolExecutor. */
public final class ControlledHttpClient implements AutoCloseable {
  public record Reply(int status, String location, String body) {}

  public static final class TransportFailure extends RuntimeException {
    private final String code;
    private final boolean sent;

    public TransportFailure(String code, boolean sent) {
      super("Controlled HTTP request failed");
      this.code = code;
      this.sent = sent;
    }

    public String code() {
      return code;
    }

    public boolean sent() {
      return sent;
    }
  }

  private static final int PREWARM_TARGET_CONNECTIONS = 64;
  private final HttpTargetPolicy policy;
  private final int readSeconds, maxBytes;
  private final org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager manager;
  private final CloseableHttpClient client;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final java.util.concurrent.atomic.AtomicInteger inFlight =
      new java.util.concurrent.atomic.AtomicInteger();
  private final java.util.Set<org.apache.hc.core5.http.HttpHost> prewarmedRoutes =
      java.util.concurrent.ConcurrentHashMap.newKeySet();
  private final ScheduledExecutorService timer =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofPlatform().daemon(true).name("oryx-http-deadline").factory());

  public ControlledHttpClient(
      HttpTargetPolicy policy, int connectSeconds, int readSeconds, int maxBytes) {
    if (connectSeconds < 1 || readSeconds < 1 || maxBytes < 1)
      throw OryxError.configuration("HTTP limits");
    this.policy = policy;
    this.readSeconds = readSeconds;
    this.maxBytes = Math.min(65536, maxBytes);
    this.manager =
        PoolingHttpClientConnectionManagerBuilder.create()
            .setPoolConcurrencyPolicy(PoolConcurrencyPolicy.LAX)
            .setMaxConnTotal(128)
            .setMaxConnPerRoute(128)
            .setDefaultSocketConfig(SocketConfig.custom().setTcpNoDelay(true).build())
            .setDefaultConnectionConfig(
                ConnectionConfig.custom()
                    .setConnectTimeout(Timeout.ofSeconds(Math.min(5, connectSeconds)))
                    .setSocketTimeout(Timeout.ofSeconds(readSeconds))
                    .setValidateAfterInactivity(TimeValue.ofSeconds(15))
                    .setTimeToLive(TimeValue.ofMinutes(10))
                    .build())
            .build();
    client =
        HttpClients.custom()
            .setConnectionManager(this.manager)
            .disableAutomaticRetries()
            .disableRedirectHandling()
            .disableCookieManagement()
            .disableAuthCaching()
            .disableContentCompression()
            .build();
  }

  public URI authorize(String value) {
    return policy.authorize(value);
  }

  public Reply exchange(
      String method, String url, Map<String, String> headers, String body, ExecutionContext ctx) {
    URI uri = policy.authorize(url);
    boolean sent = false;
    for (int hop = 0; hop <= 3; hop++) {
      URI target = policy.authorize(uri.toString());
      String verb = method, payload = body;
      Reply reply;
      try {
        reply = ctx.dispatcher().call(() -> request(verb, target, headers, payload, ctx));
        sent = true;
      } catch (TransportFailure e) {
        throw new TransportFailure(e.code(), sent || e.sent());
      }
      if (reply.status() < 300 || reply.status() >= 400) return reply;
      if (hop == 3 || reply.location() == null)
        throw new TransportFailure("HTTP_REDIRECT_LIMIT", sent);
      URI next;
      try {
        next = policy.authorize(target.resolve(reply.location()).toString());
        if (!headers.isEmpty() && !origin(target).equals(origin(next)))
          throw new IllegalArgumentException();
      } catch (Exception e) {
        throw new TransportFailure("TARGET_DENIED", sent);
      }
      if (reply.status() == 303) {
        method = "GET";
        body = null;
      }
      uri = next;
    }
    throw new IllegalStateException("Redirect limit");
  }

  private static String origin(URI u) {
    return u.getScheme()
        + "://"
        + u.getHost()
        + ":"
        + (u.getPort() == -1 ? (u.getScheme().equals("https") ? 443 : 80) : u.getPort());
  }

  private Reply request(
      String method, URI uri, Map<String, String> headers, String body, ExecutionContext ctx) {
    long budget = Math.min(TimeUnit.SECONDS.toNanos(readSeconds), ctx.deadline().remainingNanos());
    if (budget <= 0) throw new OryxError("DEADLINE", "No time remains for HTTP GET");
    if (closed.get()) throw new TransportFailure("HTTP_CONNECTION", false);
    long millis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(budget));
    var request = new org.apache.hc.client5.http.classic.methods.HttpUriRequestBase(method, uri);
    // Lease wait and response reads use this request's remaining budget. Cancellation closes
    // only this exchange; the owned pool remains available to concurrent executions.
    request.setConfig(
        RequestConfig.custom()
            .setConnectionRequestTimeout(Timeout.ofMilliseconds(millis))
            .setResponseTimeout(Timeout.ofMilliseconds(millis))
            .build());
    var expired = new AtomicBoolean();
    var sent = new AtomicBoolean();
    headers.forEach(request::setHeader);
    if (body != null)
      request.setEntity(
          new org.apache.hc.core5.http.io.entity.StringEntity(
              body, org.apache.hc.core5.http.ContentType.APPLICATION_JSON));
    ScheduledFuture<?> timeout = null;
    try {
      timeout =
          timer.schedule(
              () -> {
                expired.set(true);
                request.cancel();
              },
              budget,
              TimeUnit.NANOSECONDS);
      ctx.dispatcher().check();
      sent.set(true);
      int concurrent = inFlight.incrementAndGet();
      try {
        return client.execute(
            request,
            response -> {
              if (response.getCode() < 500 && concurrent >= 4) {
                try {
                  prewarmRoute(routeHost(request.getUri()));
                } catch (Exception ignored) {
                }
              }
              String location =
                  response.getFirstHeader("Location") == null
                      ? null
                      : response.getFirstHeader("Location").getValue();
              byte[] bytes =
                  response.getEntity() == null
                      ? new byte[0]
                      : response.getEntity().getContent().readNBytes(maxBytes + 1);
              if (bytes.length > maxBytes) {
                request.cancel();
                throw new OryxError(
                    "HTTP_RESPONSE_TOO_LARGE", "HTTP response exceeds the configured byte limit");
              }
              if (expired.get() || ctx.deadline().expired())
                throw new OryxError("HTTP_TIMEOUT", "HTTP response exceeded its time budget");
              return new Reply(
                  response.getCode(), location, new String(bytes, StandardCharsets.UTF_8));
            });
      } finally {
        inFlight.decrementAndGet();
      }
    } catch (OryxError e) {
      throw new TransportFailure(e.code(), sent.get());
    } catch (Exception e) {
      throw new TransportFailure(
          expired.get() || ctx.deadline().expired() ? "HTTP_TIMEOUT" : "HTTP_CONNECTION",
          sent.get());
    } finally {
      if (timeout != null) timeout.cancel(false);
    }
  }

  private static org.apache.hc.core5.http.HttpHost routeHost(URI uri) {
    int port =
        uri.getPort() > 0 ? uri.getPort() : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
    return new org.apache.hc.core5.http.HttpHost(uri.getScheme(), uri.getHost(), port);
  }

  private void prewarmRoute(org.apache.hc.core5.http.HttpHost host) {
    if (host == null || host.getHostName() == null || closed.get()) return;
    if (!prewarmedRoutes.add(host)) return;
    var route = new org.apache.hc.client5.http.HttpRoute(host);
    Thread.ofVirtual()
        .name("oryx-http-prewarm")
        .start(() -> {
          if (closed.get()) return;
          int target = PREWARM_TARGET_CONNECTIONS;
          var held =
              new java.util.ArrayList<org.apache.hc.client5.http.io.ConnectionEndpoint>(target);
          var toConnect =
              new java.util.ArrayList<org.apache.hc.client5.http.io.ConnectionEndpoint>();
          try {
            for (int i = 0; i < target && !closed.get(); i++) {
              try {
                var ep =
                    manager
                        .lease("prewarm-" + i, route, Timeout.ofMilliseconds(50), null)
                        .get(Timeout.ofMilliseconds(50));
                held.add(ep);
                if (!ep.isConnected()) toConnect.add(ep);
              } catch (Exception ignored) {
                break;
              }
            }
            if (!toConnect.isEmpty() && !closed.get()) {
              var latch = new java.util.concurrent.CountDownLatch(toConnect.size());
              for (var ep : toConnect) {
                Thread.ofVirtual()
                    .start(() -> {
                      try {
                        if (!closed.get() && !ep.isConnected()) {
                          manager.connect(
                              ep,
                              TimeValue.ofSeconds(2),
                              org.apache.hc.client5.http.protocol.HttpClientContext.create());
                        }
                      } catch (Exception ignored) {
                      } finally {
                        latch.countDown();
                      }
                    });
              }
              try {
                latch.await(5, TimeUnit.SECONDS);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            }
          } finally {
            for (var ep : held) {
              boolean ok = ep.isConnected() && !closed.get();
              try {
                manager.release(
                    ep, null, ok ? TimeValue.ofMinutes(10) : TimeValue.ZERO_MILLISECONDS);
              } catch (Exception ignored) {
              }
            }
          }
        });
  }

  public void close() {
    if (closed.compareAndSet(false, true)) {
      timer.shutdownNow();
      client.close(org.apache.hc.core5.io.CloseMode.IMMEDIATE);
    }
  }
}
