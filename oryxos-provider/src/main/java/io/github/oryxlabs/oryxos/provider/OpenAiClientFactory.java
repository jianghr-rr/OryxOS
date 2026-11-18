package io.github.oryxlabs.oryxos.provider;

import io.github.oryxlabs.oryxos.core.config.ProviderDefinition;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import java.io.*;
import java.net.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.pool.PoolConcurrencyPolicy;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.*;
import org.springframework.http.client.*;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.web.client.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Provider-owned connection pool/codecs and reusable OpenAiApi wrapper; each request owns its
 * remaining timeout budget.
 */
final class OpenAiClientFactory implements AutoCloseable {
  private static final int PREWARM_TARGET_CONNECTIONS = 64;
  private final ProviderDefinition definition;
  private final org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager manager;
  private final CloseableHttpClient transport;
  private final ScheduledExecutorService deadlines =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofPlatform().daemon(true).name("oryx-provider-deadlines").factory());
  private final ThreadLocal<Long> requestDeadline = new ThreadLocal<>();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final java.util.concurrent.atomic.AtomicInteger inFlight =
      new java.util.concurrent.atomic.AtomicInteger();
  private final java.util.Set<HttpHost> prewarmedRoutes =
      java.util.concurrent.ConcurrentHashMap.newKeySet();
  private final HttpHost targetHost;
  private final OpenAiApi api;
  private final ResponseErrorHandler errors =
      new ResponseErrorHandler() {
        public boolean hasError(ClientHttpResponse response) throws IOException {
          return !response.getStatusCode().is2xxSuccessful();
        }

        public void handleError(
            URI uri, HttpMethod method, ClientHttpResponse response) throws IOException {
          throw ProviderErrorMapper.status(response.getStatusCode().value());
        }
      };

  OpenAiClientFactory(ProviderDefinition definition, String credential) {
    this.definition = definition;
    this.manager =
        PoolingHttpClientConnectionManagerBuilder.create()
            .setPoolConcurrencyPolicy(PoolConcurrencyPolicy.LAX)
            .setMaxConnTotal(128)
            .setMaxConnPerRoute(128)
            .setDefaultSocketConfig(SocketConfig.custom().setTcpNoDelay(true).build())
            .setDefaultConnectionConfig(
                ConnectionConfig.custom()
                    .setConnectTimeout(Timeout.ofSeconds(definition.connectTimeoutSeconds()))
                    .setValidateAfterInactivity(TimeValue.ofSeconds(15))
                    .setTimeToLive(TimeValue.ofMinutes(10))
                    .build())
            .build();
    transport =
        HttpClients.custom()
            .setConnectionManager(this.manager)
            .disableAutomaticRetries()
            .disableRedirectHandling()
            .disableCookieManagement()
            .disableAuthCaching()
            .disableContentCompression()
            .build();
    // Build one reusable RestClient/OpenAiApi; per-request deadlines travel on ThreadLocal.
    var restTemplate =
        RestClient.builder().requestFactory(new TimedHttpRequestFactory(new ApacheRequestFactory()));
    // OpenAiApi also constructs a WebClient even for blocking-only calls; refuse reactive use
    // without creating any selector or background threads.
    ClientHttpConnector disabledReactive =
        (method, uri, requestCallback) ->
            Mono.error(new UnsupportedOperationException("Streaming provider calls are disabled"));
    var reactiveTemplate = WebClient.builder().clientConnector(disabledReactive);
    String origin = definition.endpoint().toString().replaceAll("/$", "");
    targetHost = HttpHost.create(URI.create(origin));
    api =
        OpenAiApi.builder()
            .baseUrl(origin)
            .apiKey(credential)
            .completionsPath(definition.completionsPath())
            .restClientBuilder(restTemplate)
            .webClientBuilder(reactiveTemplate)
            .responseErrorHandler(errors)
            .build();
  }

  OpenAiApi create(Duration remaining) {
    if (closed.get() || deadlines.isShutdown())
      throw new OryxError("PROVIDER_UNAVAILABLE", "Provider client is closed");
    if (remaining.isNegative() || remaining.isZero())
      throw new OryxError("PROVIDER_TIMEOUT", "No time remains for a model call");
    return api;
  }

  <T> T call(Duration remaining, Function<OpenAiApi, T> action) {
    var client = create(remaining);
    Long previous = requestDeadline.get();
    requestDeadline.set(System.nanoTime() + remaining.toNanos());
    try {
      return action.apply(client);
    } finally {
      if (previous == null) requestDeadline.remove();
      else requestDeadline.set(previous);
    }
  }

  public void close() {
    if (closed.compareAndSet(false, true)) {
      deadlines.shutdownNow();
      transport.close(CloseMode.IMMEDIATE);
    }
  }

  private long currentDeadlineNanos() {
    Long deadline = requestDeadline.get();
    if (deadline != null) return deadline;
    return System.nanoTime()
        + TimeUnit.SECONDS.toNanos(Math.max(60L, definition.connectTimeoutSeconds() + 5L));
  }

  private final class ApacheRequestFactory implements ClientHttpRequestFactory {
    @Override
    public ClientHttpRequest createRequest(URI uri, HttpMethod method) {
      return new ApacheRequest(uri, method, currentDeadlineNanos());
    }
  }

  private final class ApacheRequest implements ClientHttpRequest, StreamingHttpOutputMessage {
    private final URI uri;
    private final HttpMethod method;
    private final long deadlineNanos;
    private final HttpHeaders headers = new HttpHeaders();
    private final java.util.Map<String, Object> attributes = new java.util.HashMap<>();
    private ByteArrayOutputStream body;
    private IOException encodeFailure;
    private boolean executed;

    ApacheRequest(URI uri, HttpMethod method, long deadlineNanos) {
      this.uri = uri;
      this.method = method;
      this.deadlineNanos = deadlineNanos;
    }

    @Override
    public HttpMethod getMethod() {
      return method;
    }

    @Override
    public URI getURI() {
      return uri;
    }

    @Override
    public HttpHeaders getHeaders() {
      return headers;
    }

    @Override
    public java.util.Map<String, Object> getAttributes() {
      return attributes;
    }

    @Override
    public OutputStream getBody() {
      if (executed) throw new IllegalStateException("Request already executed");
      if (body == null) body = new ByteArrayOutputStream(2048);
      return body;
    }

    @Override
    public void setBody(Body streamingBody) {
      if (executed) throw new IllegalStateException("Request already executed");
      body = new ByteArrayOutputStream(2048);
      try {
        streamingBody.writeTo(body);
      } catch (IOException error) {
        encodeFailure = error;
      }
    }

    @Override
    public ClientHttpResponse execute() throws IOException {
      executed = true;
      if (encodeFailure != null) throw encodeFailure;
      if (closed.get() || deadlines.isShutdown())
        throw new OryxError("PROVIDER_UNAVAILABLE", "Provider client is closed");
      long remaining = deadlineNanos - System.nanoTime();
      if (remaining <= 0)
        throw new SocketTimeoutException("Provider request exceeded remaining time budget");
      var request = new HttpUriRequestBase(method.name(), uri);
      headers.forEach(
          (name, values) -> {
            if (!name.equalsIgnoreCase(HttpHeaders.CONTENT_LENGTH)
                && !name.equalsIgnoreCase(HttpHeaders.TRANSFER_ENCODING)
                && !name.equalsIgnoreCase(HttpHeaders.HOST)) {
              for (String value : values) request.addHeader(name, value);
            }
          });
      if (body != null) request.setEntity(new ByteArrayEntity(body.toByteArray(), null));
      long millis = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining));
      request.setConfig(
          RequestConfig.custom()
              .setConnectionRequestTimeout(Timeout.ofMilliseconds(millis))
              .setResponseTimeout(Timeout.ofMilliseconds(millis))
              .build());
      var expired = new AtomicBoolean();
      var timeoutTask =
          deadlines.schedule(
              () -> {
                expired.set(true);
                request.cancel();
              },
              remaining,
              TimeUnit.NANOSECONDS);
      int concurrent = inFlight.incrementAndGet();
      try {
        HttpHost routeHost =
            uri.getHost() != null
                    && uri.getPort() == targetHost.getPort()
                    && targetHost.getHostName().equalsIgnoreCase(uri.getHost())
                ? targetHost
                : null;
        ClassicHttpResponse response =
            transport.executeOpen(routeHost, request, HttpClientContext.create());
        if (response.getCode() < 500 && concurrent >= 4) {
          prewarmRoute(routeHost != null ? routeHost : routeHost(uri));
        }
        return new ApacheResponse(response, timeoutTask, expired, deadlineNanos);
      } catch (IOException | RuntimeException error) {
        timeoutTask.cancel(false);
        if (expired.get() || System.nanoTime() >= deadlineNanos)
          throw new SocketTimeoutException("Provider request exceeded remaining time budget");
        throw error;
      } finally {
        inFlight.decrementAndGet();
      }
    }
  }

  int availableIdleConnections() {
    return manager.getTotalStats().getAvailable();
  }

  private static HttpHost routeHost(URI uri) {
    int port = uri.getPort() > 0 ? uri.getPort() : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
    return new HttpHost(uri.getScheme(), uri.getHost(), port);
  }

  private void prewarmRoute(HttpHost host) {
    if (host == null || host.getHostName() == null || closed.get()) return;
    if (!prewarmedRoutes.add(host)) return;
    var route = new org.apache.hc.client5.http.HttpRoute(host);
    Thread.ofVirtual()
        .name("oryx-provider-prewarm")
        .start(
            () -> {
              if (closed.get()) return;
              int target = PREWARM_TARGET_CONNECTIONS;
              var held = new java.util.ArrayList<org.apache.hc.client5.http.io.ConnectionEndpoint>(target);
              var toConnect = new java.util.ArrayList<org.apache.hc.client5.http.io.ConnectionEndpoint>();
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
                        .start(
                            () -> {
                              try {
                                if (!closed.get() && !ep.isConnected()) {
                                  manager.connect(
                                      ep, TimeValue.ofSeconds(2), HttpClientContext.create());
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

  private static final class ApacheResponse implements ClientHttpResponse {
    private final ClassicHttpResponse delegate;
    private final ScheduledFuture<?> timeoutTask;
    private final AtomicBoolean expired;
    private final long deadlineNanos;
    private final HttpHeaders headers;
    private final AtomicBoolean closed = new AtomicBoolean();
    private InputStream body;

    ApacheResponse(
        ClassicHttpResponse delegate,
        ScheduledFuture<?> timeoutTask,
        AtomicBoolean expired,
        long deadlineNanos) {
      this.delegate = delegate;
      this.timeoutTask = timeoutTask;
      this.expired = expired;
      this.deadlineNanos = deadlineNanos;
      this.headers = new HttpHeaders();
      for (Header header : delegate.getHeaders())
        this.headers.add(header.getName(), header.getValue());
    }

    @Override
    public HttpStatusCode getStatusCode() {
      return HttpStatusCode.valueOf(delegate.getCode());
    }

    @Override
    public String getStatusText() {
      return delegate.getReasonPhrase() == null ? "" : delegate.getReasonPhrase();
    }

    @Override
    public HttpHeaders getHeaders() {
      return headers;
    }

    @Override
    public InputStream getBody() throws IOException {
      if (body != null) return body;
      var entity = delegate.getEntity();
      if (entity == null) {
        body = InputStream.nullInputStream();
        return body;
      }
      body =
          new FilterInputStream(entity.getContent()) {
            @Override
            public int read() throws IOException {
              try {
                int value = in.read();
                if (value == -1 && expired.get())
                  throw new SocketTimeoutException("Provider response exceeded remaining time budget");
                return value;
              } catch (IOException error) {
                if (expired.get() || System.nanoTime() >= deadlineNanos)
                  throw new SocketTimeoutException("Provider response exceeded remaining time budget");
                throw error;
              }
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
              try {
                int count = in.read(bytes, offset, length);
                if (count == -1 && expired.get())
                  throw new SocketTimeoutException("Provider response exceeded remaining time budget");
                return count;
              } catch (IOException error) {
                if (expired.get() || System.nanoTime() >= deadlineNanos)
                  throw new SocketTimeoutException("Provider response exceeded remaining time budget");
                throw error;
              }
            }
          };
      return body;
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        timeoutTask.cancel(false);
        try {
          delegate.close();
        } catch (IOException ignored) {
        }
      }
    }
  }
}