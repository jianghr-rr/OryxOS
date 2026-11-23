package io.github.oryxlabs.oryxos.core.workspace;

import java.nio.file.Path;

public record WorkspaceHandle(Path root) {
  public WorkspaceHandle {
    if (root == null || !root.isAbsolute() || !root.equals(root.normalize()))
      throw new IllegalArgumentException("Workspace root must be absolute and normalized");
  }

  public Path database() {
    return root.resolve("oryxos.db");
  }

  public Path runtime() {
    return root.resolve("runtime");
  }
}
