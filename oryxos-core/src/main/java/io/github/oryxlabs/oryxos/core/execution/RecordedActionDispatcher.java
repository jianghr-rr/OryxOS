package io.github.oryxlabs.oryxos.core.execution;

import io.github.oryxlabs.oryxos.core.runtime.ActionDispatcher;
import java.util.function.Supplier;

/** A committed intent is followed by a fresh, atomic permission check before the IO handoff. */
public final class RecordedActionDispatcher {
  private RecordedActionDispatcher() {}

  public static <T> T call(
      ActionDispatcher dispatcher, Runnable recordStarted, Supplier<T> action) {
    dispatcher.check();
    recordStarted.run();
    return dispatcher.call(action);
  }
}
