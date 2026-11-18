package io.github.oryxlabs.oryxos.provider;

import io.github.oryxlabs.oryxos.core.runtime.RuntimeTiming;
import java.io.*;
import java.net.URI;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.http.*;
import org.springframework.http.client.*;

/** Content-free timings at the existing streaming request/response boundaries. */
final class TimedHttpRequestFactory implements ClientHttpRequestFactory {
  private final ClientHttpRequestFactory delegate;

  TimedHttpRequestFactory(ClientHttpRequestFactory delegate) {
    this.delegate = delegate;
  }

  @Override
  public ClientHttpRequest createRequest(URI uri, HttpMethod method) throws IOException {
    var request = delegate.createRequest(uri, method);
    var observer = RuntimeTiming.capture();
    return observer == null ? request : new Request(request, observer);
  }

  private record Request(ClientHttpRequest delegate, Consumer<RuntimeTiming.Segment> observer)
      implements ClientHttpRequest, StreamingHttpOutputMessage {
    public HttpMethod getMethod() {
      return delegate.getMethod();
    }

    public URI getURI() {
      return delegate.getURI();
    }

    public HttpHeaders getHeaders() {
      return delegate.getHeaders();
    }

    public Map<String, Object> getAttributes() {
      return delegate.getAttributes();
    }

    public OutputStream getBody() throws IOException {
      return delegate.getBody();
    }

    public void setBody(Body body) {
      if (!(delegate instanceof StreamingHttpOutputMessage streaming))
        throw new IllegalStateException("Provider transport must support streaming request bodies");
      streaming.setBody(
          new Body() {
            public void writeTo(OutputStream output) throws IOException {
              try (var binding = RuntimeTiming.bind(observer);
                  var timing = RuntimeTiming.measure("provider.http.encode")) {
                body.writeTo(output);
              }
            }

            public boolean repeatable() {
              return body.repeatable();
            }
          });
    }

    public ClientHttpResponse execute() throws IOException {
      ClientHttpResponse response;
      try (var binding = RuntimeTiming.bind(observer);
          var timing = RuntimeTiming.measure("provider.http.headers")) {
        response = delegate.execute();
      }
      return new Response(response, observer);
    }
  }

  private static final class Response implements ClientHttpResponse {
    private final ClientHttpResponse delegate;
    private final Consumer<RuntimeTiming.Segment> observer;
    private final long received = System.nanoTime();
    private InputStream input;
    private boolean closed;

    Response(ClientHttpResponse delegate, Consumer<RuntimeTiming.Segment> observer) {
      this.delegate = delegate;
      this.observer = observer;
    }

    public HttpStatusCode getStatusCode() throws IOException {
      return delegate.getStatusCode();
    }

    public String getStatusText() throws IOException {
      return delegate.getStatusText();
    }

    public HttpHeaders getHeaders() {
      return delegate.getHeaders();
    }

    public InputStream getBody() throws IOException {
      if (input == null)
        input =
            new FilterInputStream(delegate.getBody()) {
              public int read() throws IOException {
                try (var binding = RuntimeTiming.bind(observer);
                    var timing = RuntimeTiming.measure("provider.http.read")) {
                  return in.read();
                }
              }

              public int read(byte[] bytes, int offset, int length) throws IOException {
                try (var binding = RuntimeTiming.bind(observer);
                    var timing = RuntimeTiming.measure("provider.http.read")) {
                  return in.read(bytes, offset, length);
                }
              }
            };
      return input;
    }

    public void close() {
      if (closed) return;
      closed = true;
      try (var binding = RuntimeTiming.bind(observer);
          var timing = RuntimeTiming.measure("provider.http.close")) {
        delegate.close();
      } finally {
        RuntimeTiming.publish(
            observer,
            new RuntimeTiming.Segment("provider.http.consume", received, System.nanoTime()));
      }
    }
  }
}
