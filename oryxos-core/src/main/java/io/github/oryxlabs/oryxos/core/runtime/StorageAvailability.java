package io.github.oryxlabs.oryxos.core.runtime;

public record StorageAvailability(boolean available, String code, String safeMessage) {
  public static StorageAvailability ready() {
    return new StorageAvailability(true, "AVAILABLE", "Storage resources available");
  }

  public static StorageAvailability unavailable() {
    return new StorageAvailability(
        false,
        "STORAGE_UNAVAILABLE",
        "Storage initialization failed; inspect database and migration state before recovery");
  }
}
