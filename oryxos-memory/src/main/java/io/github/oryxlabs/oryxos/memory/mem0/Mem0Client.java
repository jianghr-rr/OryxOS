package io.github.oryxlabs.oryxos.memory.mem0;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.execution.ExecutionContext;
import java.util.*;
import java.util.concurrent.*;
import org.apache.hc.client5.http.classic.methods.*;
import org.apache.hc.client5.http.config.*;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;

/**
 * Pinned OSS protocol. Every physical request re-enters the execution dispatcher; no retries or
 * redirects.
 */
public final class Mem0Client implements AutoCloseable {
  private final Mem0Config config;
  private final String key;
  private final ObjectMapper json =
      new ObjectMapper(
          JsonFactory.builder()
              .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64).build())
              .build());
  private final ScheduledExecutorService timer =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofPlatform().daemon(true).name("oryx-mem0-deadline").factory());

  public Mem0Client(Mem0Config config, String key) {
    this.config = config;
    this.key = key;
    if (key == null || key.isBlank()) throw OryxError.configuration("Mem0 credential");
  }

  public JsonNode add(UUID workspace, UUID source, String content, ExecutionContext ctx) {
    return request(
        "POST",
        "/memories",
        Map.of(
            "messages",
            List.of(Map.of("role", "user", "content", content)),
            "user_id",
            workspace.toString(),
            "metadata",
            Map.of("oryx_scope", "ARCHIVAL", "source_entry_id", source.toString())),
        ctx);
  }

  public JsonNode get(String id, ExecutionContext ctx) {
    if (id == null || !id.matches("[A-Za-z0-9_-]{1,128}"))
      throw new OryxError("MEM0_PROTOCOL", "Invalid remote identifier");
    return request("GET", "/memories/" + id, null, ctx);
  }

  public JsonNode search(UUID workspace, String query, ExecutionContext ctx) {
    return request(
        "POST",
        "/search",
        Map.of(
            "query",
            query,
            "filters",
            Map.of("user_id", workspace.toString(), "oryx_scope", "ARCHIVAL"),
            "top_k",
            config.searchTopK(),
            "threshold",
            config.searchThreshold()),
        ctx);
  }

  public double threshold() {
    return config.searchThreshold();
  }

  private JsonNode request(String method, String path, Object body, ExecutionContext ctx) {
    return ctx.dispatcher()
        .call(() -> {
          long nanos =
              Math.min(
                  TimeUnit.SECONDS.toNanos(config.timeoutSeconds()),
                  ctx.deadline().remainingNanos());
          if (nanos <= 0) throw new OryxError("DEADLINE", "Mem0 request has no remaining time");
          long millis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(nanos));
          var connection =
              ConnectionConfig.custom()
                  .setConnectTimeout(Timeout.ofMilliseconds(Math.min(5000, millis)))
                  .setSocketTimeout(Timeout.ofMilliseconds(millis))
                  .build();
          var manager =
              PoolingHttpClientConnectionManagerBuilder.create()
                  .setDefaultConnectionConfig(connection)
                  .build();
          var request = new HttpUriRequestBase(method, config.endpoint().resolve(path));
          request.setHeader("X-API-Key", key);
          request.setHeader("Accept", "application/json");
          try {
            if (body != null)
              request.setEntity(
                  new StringEntity(json.writeValueAsString(body), ContentType.APPLICATION_JSON));
          } catch (Exception e) {
            manager.close();
            throw new OryxError("MEM0_PROTOCOL", "Cannot encode memory request");
          }
          var timeout = timer.schedule(request::cancel, nanos, TimeUnit.NANOSECONDS);
          try (var client =
              HttpClients.custom()
                  .setConnectionManager(manager)
                  .disableAutomaticRetries()
                  .disableRedirectHandling()
                  .disableCookieManagement()
                  .disableAuthCaching()
                  .disableContentCompression()
                  .setDefaultRequestConfig(
                      RequestConfig.custom()
                          .setResponseTimeout(Timeout.ofMilliseconds(millis))
                          .build())
                  .build()) {
            ctx.dispatcher().check();
            return client.execute(
                request,
                response -> {
                  int status = response.getCode();
                  if (status < 200 || status >= 300)
                    throw new OryxError(
                        status >= 400 && status < 500 ? "MEM0_REJECTED" : "MEM0_UNCERTAIN",
                        "Mem0 did not confirm the operation");
                  byte[] bytes =
                      response.getEntity() == null
                          ? new byte[0]
                          : response.getEntity().getContent().readNBytes(1024 * 1024 + 1);
                  if (bytes.length > 1024 * 1024)
                    throw new OryxError("MEM0_PROTOCOL", "Mem0 response exceeds its bound");
                  var value = json.readTree(bytes);
                  if (value == null || !value.isObject())
                    throw new OryxError("MEM0_PROTOCOL", "Mem0 returned an invalid object");
                  return value;
                });
          } catch (OryxError e) {
            throw e;
          } catch (Exception e) {
            throw new OryxError(
                "MEM0_UNCERTAIN", "Mem0 result is uncertain; the request was not retried");
          } finally {
            timeout.cancel(false);
            manager.close();
          }
        });
  }

  public void close() {
    timer.shutdownNow();
  }
}
