package io.github.oryxlabs.oryxos.boot.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.oryxlabs.oryxos.boot.runtime.BootRuntimeLauncher;
import io.github.oryxlabs.oryxos.boot.support.PersistentFixture;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

final class ObservabilitySupport {
  static final ObjectMapper JSON = new ObjectMapper();

  private ObservabilitySupport() {}

  static ConfigurableApplicationContext start(
      PersistentFixture fixture, Map<String, Object> properties) {
    var defaults = new HashMap<String, Object>();
    defaults.put("server.port", 0);
    defaults.put("oryxos.scheduling.enabled", false);
    defaults.putAll(properties);
    return new BootRuntimeLauncher().start(fixture.workspace, defaults);
  }

  static String origin(ConfigurableApplicationContext context) {
    return "http://127.0.0.1:"
        + ((ServletWebServerApplicationContext) context).getWebServer().getPort();
  }

  static HttpResponse<String> send(
      HttpClient http, String method, String url, String body, Map<String, String> headers)
      throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header("X-Oryx-User-Id", "operator");
    headers.forEach(builder::header);
    builder.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body));
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  static JsonNode json(HttpResponse<String> response) throws Exception {
    return JSON.readTree(response.body());
  }

  /** Captures events from the root logger of the currently configured Logback context. */
  static final class LogCapture implements AutoCloseable {
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger root;
    private final Map<Logger, Level> levels = new HashMap<>();

    LogCapture() {
      var context = (LoggerContext) LoggerFactory.getILoggerFactory();
      root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
      appender.setContext(context);
      appender.start();
      root.addAppender(appender);
    }

    LogCapture level(Class<?> type, Level level) {
      var logger = (Logger) LoggerFactory.getLogger(type);
      levels.putIfAbsent(logger, logger.getLevel());
      logger.setLevel(level);
      return this;
    }

    List<ILoggingEvent> events() {
      synchronized (appender.list) {
        return List.copyOf(appender.list);
      }
    }

    /** Waits for asynchronously completed requests to log, up to five seconds. */
    List<ILoggingEvent> await(Predicate<List<ILoggingEvent>> done) throws InterruptedException {
      long until = System.nanoTime() + Duration.ofSeconds(5).toNanos();
      List<ILoggingEvent> current = events();
      while (!done.test(current) && System.nanoTime() < until) {
        Thread.sleep(20);
        current = events();
      }
      return current;
    }

    @Override
    public void close() {
      root.detachAppender(appender);
      appender.stop();
      levels.forEach(Logger::setLevel);
    }
  }
}
