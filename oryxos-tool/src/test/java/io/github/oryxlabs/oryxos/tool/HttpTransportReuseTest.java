package io.github.oryxlabs.oryxos.tool;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.github.oryxlabs.oryxos.core.execution.ExecutionContext;
import io.github.oryxlabs.oryxos.core.runtime.*;
import io.github.oryxlabs.oryxos.tool.http.*;
import io.github.oryxlabs.oryxos.tool.support.ToolFixture;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("kernel-tool-transport")
class HttpTransportReuseTest {
  @TempDir Path root;

  @Test
  void connectionsAreReusedAndOneDeadlineNeverClosesAnotherRequest() throws Exception {
    var ports = new CopyOnWriteArrayList<Integer>();
    var calls = new AtomicInteger();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    try (var workers = Executors.newVirtualThreadPerTaskExecutor();
        var f = new ToolFixture(root)) {
      server.setExecutor(workers);
      server.createContext(
          "/",
          e -> {
            calls.incrementAndGet();
            ports.add(e.getRemoteAddress().getPort());
            try {
              if (e.getRequestURI().getPath().equals("/slow")) Thread.sleep(500);
              e.sendResponseHeaders(200, 2);
              e.getResponseBody().write(new byte[] {'o', 'k'});
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            } finally {
              e.close();
            }
          });
      server.start();
      try (var client = client(server)) {
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        assertEquals("ok", get(client, url, f.context("first")).body());
        assertEquals("ok", get(client, url, f.context("second")).body());
        assertEquals(
            ports.get(0), ports.get(1), "The owned transport must reuse a live connection");
        var normal = workers.submit(() -> get(client, url + "/slow", f.context("normal")));
        var shortContext = withDeadline(f.context("short"), Duration.ofMillis(100));
        var failure =
            assertThrows(
                ControlledHttpClient.TransportFailure.class,
                () -> get(client, url + "/slow", shortContext));
        assertEquals("HTTP_TIMEOUT", failure.code());
        assertTrue(failure.sent());
        assertEquals("ok", normal.get(5, TimeUnit.SECONDS).body());
        assertEquals("ok", get(client, url, f.context("after-timeout")).body());
        assertEquals(5, calls.get(), "There must be exactly one physical attempt per call");
        client.close();
        var closed =
            assertThrows(
                ControlledHttpClient.TransportFailure.class,
                () -> get(client, url, f.context("closed")));
        assertFalse(closed.sent());
        assertEquals(5, calls.get());
      } finally {
        server.stop(0);
      }
    }
  }

  @Test
  void boundedSharedPoolCanAdmitOneHundredIndependentRequests() throws Exception {
    var entered = new CountDownLatch(100);
    var release = new CountDownLatch(1);
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 128);
    try (var workers = Executors.newVirtualThreadPerTaskExecutor();
        var f = new ToolFixture(root)) {
      server.setExecutor(workers);
      server.createContext(
          "/",
          e -> {
            entered.countDown();
            try {
              release.await();
              e.sendResponseHeaders(200, 2);
              e.getResponseBody().write(new byte[] {'o', 'k'});
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            } finally {
              e.close();
            }
          });
      server.start();
      try (var client = client(server)) {
        var pending = new ArrayList<Future<ControlledHttpClient.Reply>>();
        for (int i = 0; i < 100; i++) {
          var ctx = f.context("agent-" + i);
          pending.add(
              workers.submit(
                  () -> get(client, "http://127.0.0.1:" + server.getAddress().getPort(), ctx)));
        }
        try {
          assertTrue(
              entered.await(10, TimeUnit.SECONDS),
              "The transport must not serialize the 100-session workload behind five connections");
        } finally {
          release.countDown();
        }
        for (var call : pending) assertEquals("ok", call.get(5, TimeUnit.SECONDS).body());
      } finally {
        release.countDown();
        server.stop(0);
      }
    }
  }

  private static ControlledHttpClient client(HttpServer server) {
    return new ControlledHttpClient(
        new HttpTargetPolicy(
            List.of(
                new HttpTargetPolicy.Target("http", "127.0.0.1", server.getAddress().getPort()))),
        5,
        30,
        1024);
  }

  private static ControlledHttpClient.Reply get(
      ControlledHttpClient client, String url, ExecutionContext ctx) {
    return client.exchange("GET", url, Map.of(), null, ctx);
  }

  private static ExecutionContext withDeadline(ExecutionContext ctx, Duration duration) {
    var deadline = new Deadline(ctx.time(), duration);
    var permit = ctx.gate().execution(deadline);
    return new ExecutionContext(
        ctx.workspace(),
        ctx.agent(),
        ctx.execution(),
        deadline,
        ctx.gate(),
        permit,
        new ActionDispatcher(permit),
        ctx.time(),
        ctx.redactor());
  }
}
