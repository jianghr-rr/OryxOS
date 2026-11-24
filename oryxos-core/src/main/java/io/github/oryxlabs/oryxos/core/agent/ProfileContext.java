package io.github.oryxlabs.oryxos.core.agent;

/**
 * The Agent of the execution running on the current thread. Only {@link AgentService} writes it,
 * and it must be cleared before the execution signals completion so a reused thread never sees
 * another request's Agent.
 */
public final class ProfileContext {
  private static final ThreadLocal<AgentProfile> CURRENT = new ThreadLocal<>();

  private ProfileContext() {}

  public static void set(AgentProfile profile) {
    CURRENT.set(profile);
  }

  /** Returns {@code null} outside an execution. */
  public static AgentProfile current() {
    return CURRENT.get();
  }

  public static void clear() {
    CURRENT.remove();
  }
}
