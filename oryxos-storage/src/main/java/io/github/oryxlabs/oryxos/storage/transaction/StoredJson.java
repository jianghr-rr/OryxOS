package io.github.oryxlabs.oryxos.storage.transaction;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

public final class StoredJson {
  private static final ObjectMapper JSON =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  private StoredJson() {}

  public static String write(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalArgumentException("Cannot encode record");
    }
  }

  public static <T> T read(String value, Class<T> type) {
    try {
      return JSON.readValue(value, type);
    } catch (Exception e) {
      throw new IllegalArgumentException("Cannot decode record");
    }
  }
}
