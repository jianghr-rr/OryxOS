package io.github.oryxlabs.oryxos.core.workspace;

import io.github.oryxlabs.oryxos.core.error.OryxError;
import java.nio.file.*;

public final class WorkspaceResolver {
  private WorkspaceResolver() {}

  public static WorkspaceHandle resolve(String cliRoot) {
    return resolve(
        cliRoot,
        System.getProperty("oryxos.root"),
        System.getenv("ORYXOS_ROOT"),
        Path.of("").toAbsolutePath());
  }

  public static WorkspaceHandle resolve(String cli, String property, String environment, Path cwd) {
    String selected =
        cli != null
            ? cli
            : property != null ? property : environment != null ? environment : ".oryxos";
    if (selected.isBlank()) throw OryxError.configuration("workspace root");
    try {
      Path path = cwd.resolve(selected).toAbsolutePath().normalize();
      Path ancestor = path;
      while (!Files.exists(ancestor)) ancestor = ancestor.getParent();
      return new WorkspaceHandle(
          ancestor.toRealPath().resolve(ancestor.relativize(path)).normalize());
    } catch (Exception error) {
      throw OryxError.configuration("workspace root");
    }
  }
}
