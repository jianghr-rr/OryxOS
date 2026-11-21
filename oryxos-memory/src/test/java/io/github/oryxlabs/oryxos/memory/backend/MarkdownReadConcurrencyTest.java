package io.github.oryxlabs.oryxos.memory.backend;

import static org.junit.jupiter.api.Assertions.*;

import io.github.oryxlabs.oryxos.core.memory.MemoryEntry;
import io.github.oryxlabs.oryxos.memory.MemoryFixture;
import java.nio.file.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Tag("kernel-memory-read")
class MarkdownReadConcurrencyTest {
  @TempDir Path root;

  @Test
  void stalledSnapshotReaderDoesNotBlockOtherReadersOrAtomicPublication() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var f = new MemoryFixture(root);
        var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var store = new MarkdownMemoryStore(f.workspace, f.id, f.lock);
      assertTrue(store.save("original", MemoryEntry.Scope.CORE, f.context("writer")).confirmed());
      var slow =
          new MarkdownMemoryStore(
              f.workspace,
              f.id,
              f.lock,
              file -> {
                String snapshot = Files.readString(file);
                entered.countDown();
                try {
                  release.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  throw new java.io.IOException(e);
                }
                return snapshot;
              });
      var pending = workers.submit(() -> slow.load(MemoryEntry.Scope.CORE, f.context("slow")));
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        var other = workers.submit(() -> store.browse(MemoryEntry.Scope.CORE));
        assertEquals("original", other.get(2, TimeUnit.SECONDS).getFirst().rawContent());
        var write =
            workers.submit(() ->
                new MarkdownMemoryStore(f.workspace, f.id, f.lock)
                    .save("published", MemoryEntry.Scope.CORE, f.context("second-writer")));
        assertTrue(write.get(2, TimeUnit.SECONDS).confirmed());
        assertEquals(2, store.load(MemoryEntry.Scope.CORE, f.context("fresh-reader")).size());
      } finally {
        release.countDown();
      }
      assertEquals(
          1, pending.get(5, TimeUnit.SECONDS).size(), "An opened snapshot remains coherent");
      assertEquals(
          2,
          new MarkdownMemoryStore(f.workspace, f.id, f.lock).browse(MemoryEntry.Scope.CORE).size(),
          "Later reads must observe the durable file");
    }
  }
}
