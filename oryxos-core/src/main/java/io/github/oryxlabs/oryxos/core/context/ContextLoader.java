package io.github.oryxlabs.oryxos.core.context;

import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.execution.ExecutionContext;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Set;

public final class ContextLoader {
  public String load(ExecutionContext context) {
    String definition = read(context.agent().definition(), context, true).replace("\r\n", "\n");
    int end = definition.indexOf("\n---", 4);
    if (!definition.startsWith("---\n")
        || end < 0
        || (end + 4 < definition.length() && definition.charAt(end + 4) != '\n'))
      throw unavailable();
    // Only body is hot-read; changed frontmatter cannot replace the startup configuration snapshot.
    var text = new StringBuilder(definition.substring(Math.min(end + 5, definition.length())));
    for (Path path : context.agent().bootstrap())
      text.append("\n\n").append(read(path, context, false));
    return text.toString();
  }

  private String read(Path path, ExecutionContext context, boolean definition) {
    try {
      Path root = context.workspace().root().toRealPath(), real = path.toRealPath();
      if (!real.startsWith(root) || !Files.isRegularFile(real)) throw unavailable();
      if (definition) {
        if (!real.equals(path.toAbsolutePath().normalize())
            || !real.startsWith(root.resolve("agents"))) throw unavailable();
      } else {
        String first = root.relativize(real).getName(0).toString();
        if (Set.of(
                    "secrets.local.yaml",
                    "runtime.yaml",
                    "mcp_servers.yaml",
                    "workspace.json",
                    "runtime",
                    "oryxos.db",
                    "oryxos.db-wal",
                    "oryxos.db-shm")
                .contains(first)
            || first.startsWith(".env")) throw unavailable();
      }
      long size = Files.size(real);
      if (size > 1024 * 1024)
        throw new OryxError("CONTEXT_TOO_LARGE", "Context source exceeds the 1 MiB file limit");
      byte[] bytes = Files.readAllBytes(real);
      if (bytes.length > 1024 * 1024)
        throw new OryxError("CONTEXT_TOO_LARGE", "Context source exceeds the 1 MiB file limit");
      return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    } catch (OryxError e) {
      throw e;
    } catch (Exception e) {
      throw unavailable();
    }
  }

  private static OryxError unavailable() {
    return new OryxError(
        "CONTEXT_UNAVAILABLE",
        "Agent body/Bootstrap is missing, invalid or outside its permitted path");
  }
}
