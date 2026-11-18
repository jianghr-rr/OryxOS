package io.github.oryxlabs.oryxos.storage.runtime;

import io.github.oryxlabs.oryxos.core.runtime.IncidentDetail;
import io.github.oryxlabs.oryxos.core.workspace.*;
import io.github.oryxlabs.oryxos.storage.transaction.StoredJson;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/**
 * Independent durable evidence: unreviewed runs are never overwritten, including on a second crash.
 */
public final class RunJournal {
  public record Run(UUID runId, UUID workspaceId, Instant startedAt) {}

  public record Previous(List<Path> runs, IncidentDetail fault, boolean incomplete) {}

  private final WorkspaceHandle workspace;
  private final UUID runId = UUID.randomUUID();
  private final Path runs, fault, own;

  public RunJournal(WorkspaceHandle workspace, WorkspaceProcessLock lock) {
    if (!lock.owns(workspace)) throw new IllegalStateException("Journal requires workspace lock");
    this.workspace = workspace;
    runs = workspace.runtime().resolve("runs");
    fault = workspace.runtime().resolve("fault.json");
    own = runs.resolve(runId + ".json");
  }

  public UUID runId() {
    return runId;
  }

  public Previous inspect() {
    var old = new ArrayList<Path>();
    IncidentDetail detail = null;
    boolean incomplete = false;
    try {
      checkDirectory(workspace.runtime());
      if (Files.exists(runs, LinkOption.NOFOLLOW_LINKS)) {
        checkDirectory(runs);
        try (var files = Files.list(runs)) {
          old.addAll(files.filter(p -> !p.equals(own)).sorted().toList());
        }
      }
    } catch (Exception e) {
      incomplete = true;
    }
    if (Files.exists(fault, LinkOption.NOFOLLOW_LINKS))
      try {
        if (!Files.isRegularFile(fault, LinkOption.NOFOLLOW_LINKS) || Files.size(fault) > 1_048_576)
          throw new IllegalStateException();
        detail = StoredJson.read(Files.readString(fault), IncidentDetail.class);
      } catch (Exception e) {
        incomplete = true;
      }
    return new Previous(List.copyOf(old), detail, incomplete);
  }

  public void start(UUID workspaceId) {
    try {
      checkDirectory(workspace.runtime());
      if (!Files.exists(runs, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(runs);
      checkDirectory(runs);
      writeNew(own, StoredJson.write(new Run(runId, workspaceId, Instant.now())));
      sync(runs);
    } catch (Exception e) {
      throw new IllegalStateException("Cannot persist run marker");
    }
  }

  public boolean started() {
    return Files.isRegularFile(own, LinkOption.NOFOLLOW_LINKS);
  }

  public void fault(IncidentDetail detail) {
    Path temp = null;
    try {
      checkDirectory(workspace.runtime());
      temp = Files.createTempFile(workspace.runtime(), ".fault-", ".tmp");
      writeExisting(temp, StoredJson.write(detail));
      Files.move(temp, fault, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      sync(workspace.runtime());
    } catch (Exception e) {
      throw new IllegalStateException("Cannot persist fault marker; preserve run evidence");
    } finally {
      if (temp != null)
        try {
          Files.deleteIfExists(temp);
        } catch (Exception ignored) {
        }
    }
  }

  public void acknowledged() {
    try {
      checkDirectory(runs);
      try (var files = Files.list(runs)) {
        for (var p : files.toList())
          if (!p.equals(own)) {
            if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
              throw new IllegalStateException();
            Files.delete(p);
          }
      }
      sync(runs);
      Files.deleteIfExists(fault);
      sync(workspace.runtime());
    } catch (Exception e) {
      throw new IllegalStateException("Evidence cleanup incomplete; admission remains closed");
    }
  }

  public void cleanClose() {
    try {
      Files.delete(own);
      sync(runs);
    } catch (Exception e) {
      throw new IllegalStateException("Run marker remains unclosed");
    }
  }

  private static void checkDirectory(Path p) throws Exception {
    if (!Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
      throw new IllegalStateException("Invalid journal directory");
  }

  private static void writeNew(Path p, String text) throws Exception {
    try (var c = FileChannel.open(p, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
      write(c, text);
    }
  }

  private static void writeExisting(Path p, String text) throws Exception {
    try (var c =
        FileChannel.open(p, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
      write(c, text);
    }
  }

  private static void write(FileChannel c, String text) throws Exception {
    var bytes = java.nio.charset.StandardCharsets.UTF_8.encode(text);
    while (bytes.hasRemaining()) c.write(bytes);
    c.force(true);
  }

  private static void sync(Path p) throws Exception {
    try (var c = FileChannel.open(p, StandardOpenOption.READ)) {
      c.force(true);
    }
  }
}
