package io.github.oryxlabs.oryxos.core.runtime;

import io.github.oryxlabs.oryxos.core.workspace.WorkspaceHandle;

@FunctionalInterface
public interface RuntimeLauncher {
    int launch(LaunchMode mode, WorkspaceHandle workspace);
    default int chat(WorkspaceHandle workspace,ChatOptions options,java.io.Reader input,java.io.PrintWriter output) {
        throw new io.github.oryxlabs.oryxos.core.error.OryxError("RUNTIME_NOT_READY","Chat requires a configured execution runtime");
    }
}
