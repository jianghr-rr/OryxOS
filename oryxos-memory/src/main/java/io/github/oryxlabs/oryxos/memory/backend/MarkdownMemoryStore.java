package io.github.oryxlabs.oryxos.memory.backend;

import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.execution.ExecutionContext;
import io.github.oryxlabs.oryxos.core.memory.*;
import io.github.oryxlabs.oryxos.core.workspace.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class MarkdownMemoryStore implements LongTermMemoryStore {
  private final WorkspaceHandle workspace;
  private final UUID id;
  private final WorkspaceProcessLock lock;
  private final MemoryTextCodec codec = new MemoryTextCodec();

  @FunctionalInterface
  interface FileReader {
    String read(Path path) throws java.io.IOException;
  }

  private final FileReader reader;

  public MarkdownMemoryStore(WorkspaceHandle workspace, UUID id, WorkspaceProcessLock lock) {
    this(workspace, id, lock, path -> Files.readString(path, StandardCharsets.UTF_8));
  }

  MarkdownMemoryStore(
      WorkspaceHandle workspace, UUID id, WorkspaceProcessLock lock, FileReader reader) {
    this.workspace = workspace;
    this.id = id;
    this.lock = lock;
    this.reader = reader;
    if (!lock.owns(workspace))
      throw new OryxError("WORKSPACE_BUSY", "Memory requires the workspace lock");
  }

  public void authorize(ExecutionContext ctx) {
    LongTermMemoryStore.super.authorize(ctx);
    verify(ctx);
    path();
  }

  public UUID workspaceId() {
    return id;
  }

  public String backend() {
    return "md";
  }

  public List<MemoryEntry> browse(MemoryEntry.Scope scope) {
    if (!lock.owns(workspace))
      throw new OryxError("MEMORY_UNAVAILABLE", "Memory workspace is closed");
    return read().stream()
        .filter(e -> e.scope() == scope && e.writeStatus() == MemoryEntry.WriteStatus.CONFIRMED)
        .toList();
  }

  public List<MemoryEntry> load(MemoryEntry.Scope scope, ExecutionContext ctx) {
    verify(ctx);
    // Writers publish a complete replacement with ATOMIC_MOVE. Each opened file is a coherent
    // old or new snapshot, so independent readers need not hold the workspace monitor during
    // disk IO/decoding. Writers still serialize their read-modify-publish across store instances.
    return read().stream().filter(e -> e.scope() == scope).toList();
  }

  @Override
  public List<MemoryEntry> loadAll(ExecutionContext ctx) {
    verify(ctx);
    return read();
  }

  public List<MemoryEntry> recall(String query, ExecutionContext ctx) {
    String key = MemoryTextCodec.keyword(query);
    return load(MemoryEntry.Scope.ARCHIVAL, ctx).stream()
        .filter(e -> MemoryTextCodec.keyword(e.rawContent()).contains(key))
        .toList();
  }

  public WriteReceipt save(String text, MemoryEntry.Scope scope, ExecutionContext ctx) {
    MemoryTextCodec.validateContent(text);
    synchronized (lock) {
      verify(ctx);
      var entries = new ArrayList<>(read());
      var entry =
          new MemoryEntry(
              UUID.randomUUID(),
              id,
              scope,
              text,
              ctx.time().instant(),
              ctx.execution().id(),
              ctx.agent().name(),
              backend(),
              MemoryEntry.WriteStatus.CONFIRMED,
              List.of());
      entries.add(entry);
      return ctx.dispatcher()
          .call(() -> {
            Path temporary = null;
            boolean publicationAttempted = false;
            try {
              Path file = path();
              if (!Files.exists(file.getParent(), LinkOption.NOFOLLOW_LINKS))
                Files.createDirectory(file.getParent());
              path();
              temporary = Files.createTempFile(file.getParent(), ".memory-", ".tmp");
              try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(codec.encode(id, entries));
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
              }
              ctx.dispatcher().check();
              path();
              publicationAttempted = true;
              Files.move(
                  temporary,
                  file,
                  StandardCopyOption.ATOMIC_MOVE,
                  StandardCopyOption.REPLACE_EXISTING);
              try (var directory = FileChannel.open(file.getParent(), StandardOpenOption.READ)) {
                directory.force(true);
              }
              return new WriteReceipt(
                  entry.id(),
                  true,
                  WriteReceipt.ProjectionState.NOT_REQUIRED,
                  MemoryEntry.WriteStatus.CONFIRMED,
                  "Original memory saved");
            } catch (OryxError e) {
              throw e;
            } catch (Exception e) {
              return new WriteReceipt(
                  entry.id(),
                  publicationAttempted ? null : false,
                  publicationAttempted
                      ? WriteReceipt.ProjectionState.UNKNOWN
                      : WriteReceipt.ProjectionState.FAILED,
                  publicationAttempted
                      ? MemoryEntry.WriteStatus.UNKNOWN
                      : MemoryEntry.WriteStatus.FAILED,
                  "Memory write could not be confirmed; inspect before retrying");
            } finally {
              if (temporary != null)
                try {
                  Files.deleteIfExists(temporary);
                } catch (java.io.IOException ignored) {
                }
            }
          });
    }
  }

  private void verify(ExecutionContext ctx) {
    if (!lock.owns(workspace) || !workspace.equals(ctx.workspace()))
      throw new OryxError("TARGET_DENIED", "Memory belongs to another workspace");
  }

  private List<MemoryEntry> read() {
    try {
      Path file = path();
      return Files.exists(file, LinkOption.NOFOLLOW_LINKS)
          ? codec.decode(id, reader.read(file))
          : List.of();
    } catch (OryxError e) {
      throw e;
    } catch (Exception e) {
      throw new OryxError("MEMORY_UNAVAILABLE", "Cannot read memory; no empty fallback is used");
    }
  }

  private Path path() {
    try {
      Path root = workspace.root().toRealPath(),
          directory = root.resolve("memory"),
          file = directory.resolve("MEMORY.md");
      if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return file;
      if (Files.isSymbolicLink(directory)
          || !Files.isDirectory(directory)
          || !directory.toRealPath().equals(directory)
          || Files.isSymbolicLink(file))
        throw new OryxError("TARGET_DENIED", "Memory path is not permitted");
      return file;
    } catch (OryxError e) {
      throw e;
    } catch (Exception e) {
      throw new OryxError("MEMORY_UNAVAILABLE", "Cannot access memory directory");
    }
  }
}
