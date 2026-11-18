package io.github.oryxlabs.oryxos.core.security;

import java.util.*;
import java.util.regex.Pattern;

/** Conservative boundary projection for untrusted diagnostic text, including stderr. */
public final class Redactor {
  private static final Pattern URL = Pattern.compile("(?i)https?://[^\\s\"<>]+");
  private static final Pattern CREDENTIAL =
      Pattern.compile("(?i)(authorization|api[_-]?key|token|password)(\\s*[:=]\\s*)[^,;\\r\\n]+");
  private volatile List<String> secrets = List.of();

  public synchronized void register(String value) {
    if (value == null || value.isEmpty() || secrets.contains(value)) return;
    var next = new ArrayList<>(secrets);
    next.add(value);
    next.sort(Comparator.comparingInt(String::length).reversed());
    secrets = List.copyOf(next);
  }

  public boolean containsSecret(String input) {
    if (input == null) return false;
    for (var secret : secrets) if (input.contains(secret)) return true;
    return false;
  }

  public String redact(String input) {
    if (input == null) return null;
    String result = input;
    // Each call observes an immutable registration snapshot; matchers remain call-local.
    for (String secret : secrets) {
      result = result.replace(secret, "[REDACTED]");
    }
    if (containsHttpPrefix(result)) {
      result = URL.matcher(result).replaceAll("[REDACTED_URL]");
    }
    if (result.indexOf(':') >= 0 || result.indexOf('=') >= 0) {
      result = CREDENTIAL.matcher(result).replaceAll("$1$2[REDACTED]");
    }
    return result;
  }

  private static boolean containsHttpPrefix(String value) {
    int limit = value.length() - 4;
    for (int i = 0; i <= limit; i++) {
      char c = value.charAt(i);
      if ((c == 'h' || c == 'H') && value.regionMatches(true, i, "http", 0, 4)) return true;
    }
    return false;
  }
}
