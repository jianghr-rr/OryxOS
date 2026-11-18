package io.github.oryxlabs.oryxos.core.workspace;

import io.github.oryxlabs.oryxos.core.error.OryxError;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.HashSet;
import java.util.Set;

public final class WorkspaceProcessLock implements AutoCloseable {
  private static final Set<Path> HELD = new HashSet<>();
  private final Path path;
  private final FileChannel channel;
  private final FileLock lock;
  private volatile boolean closed;

  private WorkspaceProcessLock(Path path, FileChannel channel, FileLock lock) {
    this.path = path;
    this.channel = channel;
    this.lock = lock;
  }

  public static synchronized WorkspaceProcessLock acquire(WorkspaceHandle workspace) {
    Path path = workspace.runtime().resolve("process.lock");
    if (HELD.contains(path)) throw busy();
    FileChannel channel = null;
    try {
      Files.createDirectories(workspace.runtime());
      channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
      FileLock lock = channel.tryLock();
      if (lock == null) throw busy();
      HELD.add(path);
      return new WorkspaceProcessLock(path, channel, lock);
    } catch (IOException | RuntimeException error) {
      if (channel != null)
        try {
          channel.close();
        } catch (IOException ignored) {
        }
      if (error instanceof OryxError known) throw known;
      throw new OryxError("WORKSPACE_UNAVAILABLE", "Cannot acquire workspace process lock");
    }
  }

  public static synchronized boolean isRunning(WorkspaceHandle workspace) {
    Path path = workspace.runtime().resolve("process.lock");
    if (HELD.contains(path)) return true;
    if (!Files.exists(path)) return false;
    try (var channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
      try (var lock = channel.tryLock()) {
        return lock == null;
      }
    } catch (OverlappingFileLockException error) {
      return true;
    } catch (IOException error) {
      throw new OryxError("WORKSPACE_UNAVAILABLE", "Cannot inspect workspace process lock");
    }
  }

  public boolean isHeld() {
    return !closed && lock.isValid();
  }

  public boolean owns(WorkspaceHandle workspace) {
    return path.equals(workspace.runtime().resolve("process.lock")) && isHeld();
  }

  @Override
  public void close() {
    synchronized (WorkspaceProcessLock.class) {
      if (closed) return;
      closed = true;
      try {
        try {
          lock.release();
        } finally {
          channel.close();
        }
      } catch (IOException error) {
        throw new OryxError("WORKSPACE_UNAVAILABLE", "Cannot release workspace process lock");
      } finally {
        HELD.remove(path);
      }
    }
  }

  private static OryxError busy() {
    return new OryxError(
        "WORKSPACE_BUSY",
        "Workspace already running; use its HTTP API; remote CLI chat is not supported");
  }
}
