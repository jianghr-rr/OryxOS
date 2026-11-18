package io.github.oryxlabs.oryxos.boot;

import static org.junit.jupiter.api.Assertions.*;

import io.github.oryxlabs.oryxos.boot.support.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Tag("kernel-evidence-log")
public class EvidenceLogTest {
  @TempDir Path root;

  @Test
  void waitingForEvidenceDoesNotOccupyTheOnlyVirtualCarrier() throws Exception {
    var log =
        Path.of(
            System.getProperty("oryxos.test.evidenceDir"),
            "evidence-carrier-" + System.getProperty("oryxos.test.runId") + ".log");
    var process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djdk.virtualThreadScheduler.parallelism=1",
                "-Djdk.virtualThreadScheduler.maxPoolSize=1",
                "-cp",
                System.getProperty("java.class.path"),
                EvidenceLogTest.class.getName())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    try {
      assertTrue(process.waitFor(15, TimeUnit.SECONDS));
      assertEquals(0, process.exitValue(), () -> "Evidence carrier probe failed; " + log);
      assertTrue(Files.readString(log).contains("EVIDENCE_WAIT_RELEASES_CARRIER"));
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
        process.waitFor();
      }
    }
  }

  @Test
  void everyRecordIsSynchronousCompleteAndWriteFailureIsVisible() throws Exception {
    var flushes = new AtomicInteger();
    var file = root.resolve("records.jsonl");
    try (var writer =
            new SoakEvidenceLog(
                new BufferedWriter(
                    new FilterWriter(Files.newBufferedWriter(file)) {
                      @Override
                      public void flush() throws IOException {
                        super.flush();
                        flushes.incrementAndGet();
                      }
                    }));
        var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var calls = new ArrayList<Future<?>>();
      for (int i = 0; i < 100; i++) {
        int id = i;
        calls.add(
            workers.submit(
                () -> {
                  writer.append(Map.of("id", id));
                  return null;
                }));
      }
      for (var call : calls) call.get(5, TimeUnit.SECONDS);
      assertEquals(100, flushes.get());
      var ids = new HashSet<Integer>();
      for (var line : Files.readAllLines(file))
        ids.add(SoakHarness.JSON.readTree(line).get("id").asInt());
      assertEquals(100, ids.size());
    }
    try (var writer =
        new SoakEvidenceLog(
            new BufferedWriter(
                new StringWriter() {
                  @Override
                  public void write(String value, int off, int len) {
                    throw new UncheckedIOException(new IOException("fixture failure"));
                  }

                  @Override
                  public void write(char[] chars, int off, int len) {
                    throw new UncheckedIOException(new IOException("fixture failure"));
                  }
                }))) {
      assertThrows(UncheckedIOException.class, () -> writer.append(Map.of("must", "fail")));
    } catch (UncheckedIOException expectedCloseFailure) {
      /* failed buffer is not silently discarded */
    }
  }

  public static void main(String[] args) throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var buffer =
        new BufferedWriter(
            new StringWriter() {
              @Override
              public void flush() {
                entered.countDown();
                try {
                  release.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  throw new RuntimeException(e);
                }
              }
            });
    try (var log = new SoakEvidenceLog(buffer)) {
      var owner = Thread.ofPlatform().start(() -> append(log, "owner"));
      Thread waiter = null;
      try {
        if (!entered.await(5, TimeUnit.SECONDS)) throw new AssertionError("No active flush");
        waiter = Thread.ofVirtual().start(() -> append(log, "waiter"));
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (waiter.getState() != Thread.State.BLOCKED
            && waiter.getState() != Thread.State.WAITING
            && System.nanoTime() < until) Thread.sleep(1);
        var other = new CompletableFuture<Boolean>();
        Thread.ofVirtual().start(() -> other.complete(true));
        if (!other.get(1, TimeUnit.SECONDS)) throw new AssertionError();
      } finally {
        release.countDown();
        owner.join();
        if (waiter != null) waiter.join();
      }
    }
    System.out.println("EVIDENCE_WAIT_RELEASES_CARRIER");
  }

  private static void append(SoakEvidenceLog log, String value) {
    try {
      log.append(Map.of("value", value));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
