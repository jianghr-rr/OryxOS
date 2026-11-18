package io.github.oryxlabs.oryxos.web.api;

import io.github.oryxlabs.oryxos.core.error.OryxError;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

public final class RequestLimits {
  private RequestLimits() {}

  public static OryxError invalid() {
    return new OryxError("INVALID_INPUT", "Invalid request fields, identity or pagination");
  }

  public static String user(String user) {
    if (user == null
        || user.isBlank()
        || user.codePointCount(0, user.length()) > 128
        || user.codePoints().anyMatch(Character::isISOControl)) throw invalid();
    return user;
  }

  public static String text(String text) {
    if (text == null || text.isBlank()) throw invalid();
    if (text.getBytes(StandardCharsets.UTF_8).length > 32768)
      throw new OryxError("MESSAGE_TOO_LARGE", "Content exceeds 32768 UTF-8 bytes");
    return text;
  }

  public static String name(String name) {
    if (name == null || name.isBlank()) throw invalid();
    return name;
  }

  public static UUID uuid(String id) {
    try {
      UUID value = UUID.fromString(id);
      if (!value.toString().equalsIgnoreCase(id)) throw invalid();
      return value;
    } catch (RuntimeException e) {
      throw invalid();
    }
  }

  public static int limit(Integer n) {
    int v = n == null ? 100 : n;
    if (v < 1 || v > 100) throw invalid();
    return v;
  }

  public static Long before(Long n) {
    if (n != null && n < 1) throw invalid();
    return n;
  }

  public static String cursor(String value, String scope) {
    if (value == null) return null;
    if (value.length() > 1024) throw invalid();
    try {
      String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
      if (!decoded.startsWith(scope + "\n") || decoded.length() == scope.length() + 1)
        throw invalid();
      return decoded.substring(scope.length() + 1);
    } catch (RuntimeException e) {
      throw invalid();
    }
  }

  public static String encode(String scope, String value) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString((scope + "\n" + value).getBytes(StandardCharsets.UTF_8));
  }

  public record Page<T>(List<T> items, String nextCursor) {}

  /** Sorted stable-key pagination for file snapshots and confirmed memory sources. */
  public static <T> Page<T> page(
      List<T> input, int limit, String cursor, String scope, Function<T, String> key) {
    String after = cursor(cursor, scope);
    var sorted = input.stream().sorted(Comparator.comparing(key)).toList();
    if (after != null && sorted.stream().noneMatch(v -> key.apply(v).equals(after)))
      throw invalid();
    var rows =
        sorted.stream()
            .filter(v -> after == null || key.apply(v).compareTo(after) > 0)
            .limit(limit + 1L)
            .toList();
    boolean more = rows.size() > limit;
    var items = more ? rows.subList(0, limit) : rows;
    return new Page<>(items, more ? encode(scope, key.apply(items.getLast())) : null);
  }
}
