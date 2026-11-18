package io.github.oryxlabs.oryxos.core.runtime;

import java.util.function.Supplier;

/** Admission is the handoff boundary. An admitted action remains in flight until it actually exits. */
public final class ActionDispatcher {
    private final RuntimeGate.ExecutionPermit permit;
    public ActionDispatcher(RuntimeGate.ExecutionPermit permit) { this.permit=permit; }
    public <T> T call(Supplier<T> action) {
        try(var admitted=permit.admit()) { return action.get(); }
    }
    public void check() { permit.check(); }
}
