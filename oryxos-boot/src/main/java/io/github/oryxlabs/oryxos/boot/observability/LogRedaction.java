package io.github.oryxlabs.oryxos.boot.observability;

import io.github.oryxlabs.oryxos.core.security.Redactor;

/**
 * Bridges Logback components, which Logback instantiates outside Spring, to the runtime {@link
 * Redactor}. Until the runtime registers its instance, a fresh {@code Redactor} still masks URLs
 * and credential-shaped text.
 */
public final class LogRedaction {
  private static volatile Redactor redactor = new Redactor();

  private LogRedaction() {}

  public static String redact(String value) {
    return redactor.redact(value);
  }

  static void use(Redactor runtime) {
    redactor = runtime;
  }

  static void reset() {
    redactor = new Redactor();
  }
}
