package io.github.oryxlabs.oryxos.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Tag("kernel-writer-isolation")
class WriterIsolationTest {
  @TempDir Path root;

  @Test
  void physicalWriterProgressDoesNotDependOnVirtualThreadCarrierAvailability() throws Exception {
    var log =
        Path.of(
            System.getProperty("oryxos.test.evidenceDir"),
            "writer-carrier-" + System.getProperty("oryxos.test.runId") + ".log");
    var process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djdk.virtualThreadScheduler.parallelism=1",
                "-Djdk.virtualThreadScheduler.maxPoolSize=1",
                "-cp",
                System.getProperty("java.class.path"),
                WriterCarrierProbe.class.getName(),
                root.toString())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    try {
      assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Writer child process did not finish");
      assertEquals(0, process.exitValue(), () -> "Writer carrier probe failed; " + log);
      assertTrue(Files.readString(log).contains("WRITER_PROGRESS_WITH_PINNED_CARRIER"));
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
        process.waitFor();
      }
    }
  }
}
