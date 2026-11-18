package io.github.oryxlabs.oryxos.cli.command;

import io.github.oryxlabs.oryxos.core.agent.*;
import io.github.oryxlabs.oryxos.core.config.*;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.security.Redactor;
import io.github.oryxlabs.oryxos.core.workspace.*;
import java.nio.file.*;
import java.util.*;

/** File authority for CLI edits and safe current-file inspection; no service construction. */
public final class ProfileFiles {
  private ProfileFiles() {}

  public record Current(RuntimeConfig runtime, AgentCatalog agents, Redactor redactor) {}

  public static Current current(WorkspaceHandle workspace) {
    WorkspaceInitializer.readId(workspace);
    var redactor = new Redactor();
    var config = new RuntimeConfigLoader(System.getenv(), redactor).load(workspace);
    return new Current(
        config, AgentCatalog.load(workspace, config, System.getenv(), redactor), redactor);
  }

  public static void name(String name) {
    if (name == null || !name.matches("[a-z0-9][a-z0-9-]{0,63}"))
      throw new OryxError(
          "INVALID_INPUT",
          "Profile name must contain lowercase letters, digits or hyphens (1–64 characters)");
  }

  public static Path path(WorkspaceHandle workspace, String name, boolean mustExist) {
    name(name);
    WorkspaceInitializer.readId(workspace);
    Path dir = workspace.root().resolve("agents");
    boundary(dir);
    Path profile = dir.resolve(name);
    boundary(profile);
    Path file = profile.resolve("AGENT.md");
    boundary(file);
    if (mustExist && !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
      throw new OryxError("PROFILE_NOT_FOUND", "Profile definition does not exist");
    return file;
  }

  private static void boundary(Path path) {
    if (Files.isSymbolicLink(path))
      throw OryxError.configuration("profile path must not be a symbolic link");
  }

  public static void create(WorkspaceHandle workspace, String name) {
    Path file = path(workspace, name, false);
    try {
      Files.createDirectories(file.getParent().getParent());
      Files.createDirectory(file.getParent());
      try (var template = ProfileFiles.class.getResourceAsStream("/templates/AGENT.md")) {
        if (template == null) throw new IllegalStateException("Missing profile template");
        Files.writeString(
            file,
            new String(template.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                .replace("name: default\n", "name: " + name + "\n"),
            StandardOpenOption.CREATE_NEW);
      } catch (Exception e) {
        try {
          Files.delete(file.getParent());
        } catch (java.io.IOException ignored) {
          /* Preserve any concurrently created or partial file. */
        }
        throw e;
      }
    } catch (FileAlreadyExistsException e) {
      throw new OryxError("PROFILE_EXISTS", "Profile already exists; no files were overwritten");
    } catch (java.io.IOException e) {
      throw new OryxError("PROFILE_UNAVAILABLE", "Cannot create profile definition");
    }
  }

  public static Path archive(WorkspaceHandle workspace, String name) {
    Path file = path(workspace, name, true);
    // Acquire, do not merely probe, so a concurrent runtime cannot start during the move.
    boundary(workspace.runtime());
    boundary(workspace.runtime().resolve("process.lock"));
    try (var lock = WorkspaceProcessLock.acquire(workspace)) {
      Path archive = workspace.root().resolve("agent-archive");
      boundary(archive);
      Files.createDirectories(archive);
      Path target =
          archive.resolve(
              name + "-" + java.time.Instant.now().toEpochMilli() + "-" + UUID.randomUUID());
      Files.move(file.getParent(), target, StandardCopyOption.ATOMIC_MOVE);
      return target;
    } catch (java.io.IOException e) {
      throw new OryxError(
          "PROFILE_UNAVAILABLE", "Cannot archive profile; historical records are retained");
    }
  }
}
