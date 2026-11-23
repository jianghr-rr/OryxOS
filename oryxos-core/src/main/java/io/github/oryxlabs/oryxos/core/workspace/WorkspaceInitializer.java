package io.github.oryxlabs.oryxos.core.workspace;

import io.github.oryxlabs.oryxos.core.config.SafeYaml;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class WorkspaceInitializer {
  public UUID initialize(WorkspaceHandle workspace, Map<String, String> templates) {
    try {
      Files.createDirectories(workspace.root());
      for (String folder : List.of("agents/default", "memory", "runtime", "output"))
        ensureDirectory(workspace.root(), Path.of(folder));
      createMissing(
          workspace.root().resolve("workspace.json"),
          "{\"schemaVersion\":1,\"workspaceId\":\"" + UUID.randomUUID() + "\"}\n");
      UUID id = readId(workspace);
      for (var template : templates.entrySet()) {
        Path target = workspace.root().resolve(template.getKey()).normalize();
        if (!target.startsWith(workspace.root())) throw OryxError.configuration("template path");
        ensureDirectory(workspace.root(), workspace.root().relativize(target.getParent()));
        createMissing(target, template.getValue());
      }
      return id;
    } catch (java.io.IOException error) {
      throw OryxError.configuration("workspace initialization");
    }
  }

  public static UUID readId(WorkspaceHandle workspace) {
    try {
      var data = SafeYaml.read(workspace.root().resolve("workspace.json"), "workspace.json");
      if (!Integer.valueOf(1).equals(data.get("schemaVersion")))
        throw OryxError.configuration("workspace.json schemaVersion");
      return UUID.fromString(SafeYaml.text(data.get("workspaceId"), "workspace.json workspaceId"));
    } catch (IllegalArgumentException error) {
      throw OryxError.configuration("workspace.json workspaceId");
    }
  }

  private void ensureDirectory(Path root, Path relative) throws java.io.IOException {
    Path current = root;
    for (Path part : relative) {
      current = current.resolve(part);
      if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(current);
      if (!Files.isDirectory(current) || !current.toRealPath().startsWith(root))
        throw OryxError.configuration("workspace directory boundary");
    }
  }

  private void createMissing(Path target, String text) throws java.io.IOException {
    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return;
    Files.createDirectories(target.getParent());
    Path temporary = Files.createTempFile(target.getParent(), ".init-", ".tmp");
    try {
      try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
        ByteBuffer bytes = StandardCharsets.UTF_8.encode(text);
        while (bytes.hasRemaining()) channel.write(bytes);
        channel.force(true);
      }
      // Publish a fully written file atomically, without replacing a concurrent initializer's file.
      try {
        Files.createLink(target, temporary);
      } catch (FileAlreadyExistsException ignored) {
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }
}
