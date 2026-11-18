package io.github.oryxlabs.oryxos.tool.file;

import io.github.oryxlabs.oryxos.core.error.OryxError;
import java.nio.file.*;
import java.util.*;

/** Application allowlist. Trusted external processes are not an OS sandbox. */
public final class FileAccessPolicy {
  private final Path root;
  private final List<Path> read, write;

  public FileAccessPolicy(Path root, List<String> read, List<String> write) {
    this.root = root.toAbsolutePath().normalize();
    this.read = roots(read);
    this.write = roots(write);
  }

  private List<Path> roots(List<String> values) {
    return values.stream()
        .map(v -> root.resolve(v).normalize())
        .peek(
            p -> {
              if (!p.startsWith(root))
                throw OryxError.configuration("file allowlist outside workspace");
            })
        .toList();
  }

  public Path authorize(String value, boolean writing) {
    try {
      if (value == null || value.isBlank() || Path.of(value).isAbsolute()) throw denied();
      Path path = root.resolve(value).normalize();
      if (!path.startsWith(root) || protectedPath(path)) throw denied();
      if ((writing ? write : read).stream().noneMatch(path::startsWith)) throw denied();
      verifyParents(path);
      return path;
    } catch (OryxError e) {
      throw e;
    } catch (Exception e) {
      throw denied();
    }
  }

  public void verifyParents(Path path) throws java.io.IOException {
    Path realRoot = root.toRealPath(), current = root;
    for (Path piece : root.relativize(path)) {
      current = current.resolve(piece);
      if (Files.isSymbolicLink(current)) throw denied();
      if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
          && !current.toRealPath().startsWith(realRoot)) throw denied();
    }
  }

  private boolean protectedPath(Path path) {
    Path rel = root.relativize(path);
    if (rel.getNameCount() == 0) return true;
    String first = rel.getName(0).toString().toLowerCase(Locale.ROOT),
        name = path.getFileName().toString().toLowerCase(Locale.ROOT);
    return Set.of(
                "runtime",
                "memory",
                ".git",
                "runtime.yaml",
                "secrets.local.yaml",
                "mcp_servers.yaml",
                "workspace.json",
                "agents.md",
                "soul.md",
                "user.md")
            .contains(first)
        || first.startsWith(".env")
        || name.equals("agent.md")
        || name.endsWith(".db")
        || name.endsWith(".db-wal")
        || name.endsWith(".db-shm")
        || name.equals("secrets.local.yaml");
  }

  private static OryxError denied() {
    return new OryxError(
        "TARGET_DENIED",
        "File path is outside the allowed data area or is a protected control file");
  }
}
