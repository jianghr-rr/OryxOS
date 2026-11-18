package io.github.oryxlabs.oryxos.boot.config;

import io.github.oryxlabs.oryxos.core.runtime.*;
import io.github.oryxlabs.oryxos.core.workspace.*;
import io.github.oryxlabs.oryxos.storage.lifecycle.*;

/** Replaces storage only while admission is closed. Does not implement user recovery or reopen the gate. */
public final class StorageLifecycle implements AutoCloseable {
    private final StorageResourceFactory factory;
    private final WorkspaceHandle workspace;
    private final WorkspaceProcessLock lock;
    private final RuntimeGate gate;
    private volatile StorageResources resources;
    private volatile StorageAvailability availability = StorageAvailability.unavailable();
    public StorageLifecycle(StorageResourceFactory factory, WorkspaceHandle workspace, WorkspaceProcessLock lock, RuntimeGate gate) {
        this.factory = factory; this.workspace = workspace; this.lock = lock; this.gate = gate;
    }
    public StorageAvailability availability() { return availability; }
    public StorageResources resources() {
        var current = resources;
        if (current == null) throw new io.github.oryxlabs.oryxos.core.error.OryxError("STORAGE_UNAVAILABLE", availability.safeMessage());
        return current;
    }
    public synchronized void rebuild() {
        gate.withLock(() -> {
            if (gate.state() == RuntimeGate.State.READY || gate.state() == RuntimeGate.State.CLOSED || gate.inFlight() != 0 || gate.activeExecutions() != 0) {
                throw new IllegalStateException("Storage replacement requires a closed admission gate and no in-flight actions");
            }
            availability = StorageAvailability.unavailable();
            StorageResources old = resources;
            resources = null;
            try {
                if (old != null) old.close();
                resources = factory.open(workspace, lock);
                availability = StorageAvailability.ready();
            } catch (RuntimeException error) {
                gate.pause();
                // Only the safe availability projection is public; no raw JDBC/config exception escapes.
            }
        });
    }
    @Override public synchronized void close() {
        gate.close();
        if (resources != null) { resources.close(); resources = null; }
        availability = StorageAvailability.unavailable();
    }
}
