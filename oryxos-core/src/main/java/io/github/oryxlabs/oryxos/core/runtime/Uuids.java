package io.github.oryxlabs.oryxos.core.runtime;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Lock-free RFC 4122 version-4 internal correlation identifiers. */
public final class Uuids {
  private Uuids() {}

  public static UUID v4() {
    var random = ThreadLocalRandom.current();
    long msb = (random.nextLong() & ~0x000000000000f000L) | 0x0000000000004000L;
    long lsb = (random.nextLong() & ~0xc000000000000000L) | 0x8000000000000000L;
    return new UUID(msb, lsb);
  }
}
