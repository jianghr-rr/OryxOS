package io.github.oryxlabs.oryxos.boot;

import io.github.oryxlabs.oryxos.boot.support.LocalHttpFixture;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** This name matches only the added *IT pattern, unlike TestDiscoveryIT. */
@Tag("f0-discovery")
class DiscoverySuffixIT {
    @Test void suffixOnlyItIsDiscovered() throws Exception {
        try (var fixture = new LocalHttpFixture("unused")) {
            assertEquals("127.0.0.1", fixture.uri().getHost());
            assertEquals(0, fixture.calls());
        }
    }
}
