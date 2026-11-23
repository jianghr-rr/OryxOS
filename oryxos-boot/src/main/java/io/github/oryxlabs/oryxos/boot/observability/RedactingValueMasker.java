package io.github.oryxlabs.oryxos.boot.observability;

import com.fasterxml.jackson.core.JsonStreamContext;
import net.logstash.logback.mask.ValueMasker;

/** Redacts every string value of a JSON log event: message, stack trace and MDC fields. */
public class RedactingValueMasker implements ValueMasker {
  @Override
  public Object mask(JsonStreamContext context, Object value) {
    if (value instanceof CharSequence text) {
      String original = text.toString();
      String redacted = LogRedaction.redact(original);
      return redacted.equals(original) ? null : redacted;
    }
    return null;
  }
}
