package io.github.oryxlabs.oryxos.core.runtime;

import io.github.oryxlabs.oryxos.core.error.OryxError;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/** Atomic admission bookkeeping shared by all external action dispatchers. */
public final class RuntimeGate {
    public enum State { STARTING, READY, PAUSED_RECORDING, CLOSED }
    private final ReentrantLock lock = new ReentrantLock();
    private volatile State state = State.STARTING;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger activeExecutions = new AtomicInteger();
    private volatile Consumer<UUID> recordingFailure = id -> {};

    public int activeExecutions() { return activeExecutions.get(); }

    public void onRecordingFailure(Consumer<UUID> listener) {
        lock.lock();
        try { recordingFailure = listener; } finally { lock.unlock(); }
    }

    public void recordingFailure(UUID id) {
        Consumer<UUID> listener;
        lock.lock();
        try { pauseLocked(); listener = recordingFailure; } finally { lock.unlock(); }
        listener.accept(id);
    }

    public void readyAfterAcknowledgement() {
        lock.lock();
        try {
            if (state != State.PAUSED_RECORDING || inFlight.get() != 0 || activeExecutions.get() != 0)
                throw new IllegalStateException("Recovery requires paused and fully drained runtime");
            state = State.READY;
        } finally {
            lock.unlock();
        }
    }

    public State state() { return state; }

    public int inFlight() { return inFlight.get(); }

    public void readyAtFirstStartup() {
        lock.lock();
        try {
            if (state != State.STARTING) throw new IllegalStateException("Only initial startup may open this gate");
            state = State.READY;
        } finally {
            lock.unlock();
        }
    }

    public void pause() {
        lock.lock();
        try { pauseLocked(); } finally { lock.unlock(); }
    }

    private void pauseLocked() {
        if (state != State.CLOSED) state = State.PAUSED_RECORDING;
    }

    public void close() {
        lock.lock();
        try { state = State.CLOSED; } finally { lock.unlock(); }
    }

    public void withLock(Runnable action) {
        lock.lock();
        try { action.run(); } finally { lock.unlock(); }
    }

    public ExecutionPermit execution(Deadline deadline) { return new ExecutionPermit(deadline); }

    public final class ExecutionPermit {
        private final Deadline deadline;
        private volatile boolean stopped;
        private final AtomicInteger active = new AtomicInteger();
        private boolean registered;

        public void beginExecution() {
            lock.lock();
            try {
                checkUnlocked(stopped);
                if (registered) throw new IllegalStateException("Already registered");
                registered = true;
                activeExecutions.incrementAndGet();
            } finally {
                lock.unlock();
            }
        }

        public void finishExecution() {
            lock.lock();
            try {
                if (registered) { registered = false; activeExecutions.decrementAndGet(); }
            } finally {
                lock.unlock();
            }
        }

        private ExecutionPermit(Deadline deadline) { this.deadline = deadline; }

        public void stop() {
            lock.lock();
            try { stopped = true; } finally { lock.unlock(); }
        }

        public int inFlight() { return active.get(); }

        public void check() { checkUnlocked(stopped); }

        private void checkUnlocked(boolean isStopped) {
            if (deadline.expired()) throw new OryxError("DEADLINE", "Execution deadline reached; no new action is allowed");
            if (state != State.READY) throw new OryxError("EXECUTION_PAUSED", "Workspace is not accepting execution actions");
            if (isStopped) throw new OryxError("EXECUTION_STOPPED", "Execution is closed to new actions");
        }

        public ActionPermit admit() {
            lock.lock();
            try {
                checkUnlocked(stopped);
                inFlight.incrementAndGet();
                active.incrementAndGet();
                return new ActionPermit(this);
            } finally {
                lock.unlock();
            }
        }
    }

    public final class ActionPermit implements AutoCloseable {
        private final ExecutionPermit owner;
        private boolean released;

        private ActionPermit(ExecutionPermit owner) { this.owner = owner; }

        @Override public void close() {
            lock.lock();
            try {
                if (!released) {
                    released = true;
                    inFlight.decrementAndGet();
                    owner.active.decrementAndGet();
                }
            } finally {
                lock.unlock();
            }
        }
    }
}
