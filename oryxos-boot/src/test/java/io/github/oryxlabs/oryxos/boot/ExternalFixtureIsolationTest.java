package io.github.oryxlabs.oryxos.boot;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.github.oryxlabs.oryxos.boot.support.ExternalFixtureExecutor;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

@Tag("kernel-external-isolation")
public class ExternalFixtureIsolationTest {
  @Test
  void evidenceWriterCannotStarveTheRuntimeCarrier() throws Exception {
    isolatedChild("driver");
  }

  @Test
  void partialExternalRequestBodyCannotStarveTheRuntimeCarrier() throws Exception {
    isolatedChild("server");
  }

  private void isolatedChild(String mode) throws Exception {
    var log =
        Path.of(
            System.getProperty("oryxos.test.evidenceDir"),
            "external-carrier-" + mode + "-" + System.getProperty("oryxos.test.runId") + ".log");
    var child =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djdk.virtualThreadScheduler.parallelism=1",
                "-Djdk.virtualThreadScheduler.maxPoolSize=1",
                "-cp",
                System.getProperty("java.class.path"),
                ExternalFixtureIsolationTest.class.getName(),
                mode)
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    try {
      assertTrue(child.waitFor(15, TimeUnit.SECONDS));
      assertEquals(0, child.exitValue(), () -> "External service blocked runtime carrier; " + log);
      assertTrue(Files.readString(log).contains("EXTERNAL_IO_ISOLATED_FROM_RUNTIME_CARRIER"));
    } finally {
      if (child.isAlive()) {
        child.destroyForcibly();
        child.waitFor();
      }
    }
  }

  public static void main(String[] args) throws Exception {
    if (args[0].equals("driver")) {
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      try (var clients = ExternalFixtureExecutor.clients()) {
        var writing =
            clients.submit(() -> {
              // The load driver's sample writer holds a monitor across blocking evidence IO.
              synchronized (entered) {
                entered.countDown();
                try {
                  release.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  throw new IllegalStateException(e);
                }
              }
            });
        try {
          if (!entered.await(5, TimeUnit.SECONDS)) throw new AssertionError("No driver");
          Thread.sleep(50);
          var runtime = new CompletableFuture<Boolean>();
          Thread.ofVirtual().start(() -> runtime.complete(true));
          if (!runtime.get(1, TimeUnit.SECONDS)) throw new AssertionError();
        } finally {
          release.countDown();
        }
        writing.get(5, TimeUnit.SECONDS);
      }
      System.out.println("EXTERNAL_IO_ISOLATED_FROM_RUNTIME_CARRIER");
      return;
    }
    var entered = new CountDownLatch(1);
    var received = new CompletableFuture<Integer>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    try (var workers = ExternalFixtureExecutor.create()) {
      server.setExecutor(workers);
      server.createContext(
          "/",
          exchange -> {
            try (exchange) {
              entered.countDown();
              received.complete(exchange.getRequestBody().readAllBytes().length);
              exchange.sendResponseHeaders(200, 0);
            }
          });
      server.start();
      try (var socket = new Socket("127.0.0.1", server.getAddress().getPort())) {
        var output = socket.getOutputStream();
        output.write(
            "POST / HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 4\r\n\r\n"
                .getBytes(StandardCharsets.US_ASCII));
        output.flush();
        try {
          if (!entered.await(5, TimeUnit.SECONDS)) throw new AssertionError("No request handler");
          Thread.sleep(50); // Receiver is awaiting body bytes, exactly the profiled pinning case.
          var runtime = new CompletableFuture<Boolean>();
          Thread.ofVirtual().start(() -> runtime.complete(true));
          if (!runtime.get(1, TimeUnit.SECONDS)) throw new AssertionError();
        } finally {
          output.write("body".getBytes(StandardCharsets.US_ASCII));
          output.flush();
          if (received.get(5, TimeUnit.SECONDS) != 4)
            throw new AssertionError("Incomplete request");
        }
      } finally {
        server.stop(0);
      }
    }
    System.out.println("EXTERNAL_IO_ISOLATED_FROM_RUNTIME_CARRIER");
  }
}
