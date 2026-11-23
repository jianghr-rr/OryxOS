package io.github.oryxlabs.oryxos.boot.observability;

import ch.qos.logback.classic.pattern.ExtendedThrowableProxyConverter;
import ch.qos.logback.classic.pattern.MDCConverter;
import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Text-pattern converters ({@code %redactedMessage}, {@code %redactedException}, {@code
 * %redactedMdc}).
 */
public final class RedactingConverters {
  private RedactingConverters() {}

  public static class Message extends MessageConverter {
    @Override
    public String convert(ILoggingEvent event) {
      return LogRedaction.redact(super.convert(event));
    }
  }

  public static class Throwable extends ExtendedThrowableProxyConverter {
    @Override
    public String convert(ILoggingEvent event) {
      return LogRedaction.redact(super.convert(event));
    }
  }

  public static class Mdc extends MDCConverter {
    @Override
    public String convert(ILoggingEvent event) {
      return LogRedaction.redact(super.convert(event));
    }
  }
}
