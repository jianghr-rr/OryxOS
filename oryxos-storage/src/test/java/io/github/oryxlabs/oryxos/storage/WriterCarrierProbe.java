package io.github.oryxlabs.oryxos.storage;

import io.github.oryxlabs.oryxos.core.runtime.RuntimeGate;
import io.github.oryxlabs.oryxos.core.workspace.*;
import io.github.oryxlabs.oryxos.storage.lifecycle.StorageResourceFactory;
import io.github.oryxlabs.oryxos.storage.transaction.*;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.*;

/** Child-JVM probe: pin the sole virtual-thread carrier, then require real SQLite progress. */
public final class WriterCarrierProbe {
  private static final Object MONITOR = new Object();

  public static void main(String[] args) throws Exception {
    var workspace = new WorkspaceHandle(Path.of(args[0]));
    new WorkspaceInitializer().initialize(workspace, Map.of());
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var lock = WorkspaceProcessLock.acquire(workspace);
        var storage = new StorageResourceFactory().open(workspace, lock);
        var caller = Executors.newSingleThreadExecutor()) {
      var writes = new WorkspaceWriteCoordinator(() -> storage, new RuntimeGate());
      writes.write(
          em -> StoredSql.update(em, "create table carrier_probe (id integer primary key)"));
      var pinned =
          Thread.startVirtualThread(
              () -> {
                synchronized (MONITOR) {
                  entered.countDown();
                  try {
                    release.await();
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                }
              });
      try {
        if (!entered.await(5, TimeUnit.SECONDS)) throw new AssertionError("Pin setup failed");
        var result =
            caller.submit(
                () ->
                    writes.write(
                        em -> StoredSql.update(em, "insert into carrier_probe values (1)")));
        try {
          if (result.get(1, TimeUnit.SECONDS) != 1) throw new AssertionError("Write missing");
        } catch (TimeoutException e) {
          throw new AssertionError("SQLite writer was starved by unrelated virtual-thread work", e);
        }
      } finally {
        release.countDown();
        pinned.join();
      }
      try (var connection = storage.dataSource().getConnection();
          var sql = connection.createStatement();
          var rows = sql.executeQuery("select count(*) from carrier_probe")) {
        if (rows.getLong(1) != 1) throw new AssertionError("Acknowledged write is not durable");
      }
      System.out.println("WRITER_PROGRESS_WITH_PINNED_CARRIER");
    }
  }
}
