package io.github.oryxlabs.oryxos.boot;

import static org.junit.jupiter.api.Assertions.*;

import io.github.oryxlabs.oryxos.boot.support.LocalHttpFixture;
import java.net.http.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("f0-discovery")
class TestDiscoveryIT {
  @Test
  void localFixtureIsReachable() throws Exception {
    try (var fixture = new LocalHttpFixture("fixture");
        var client = HttpClient.newHttpClient()) {
      assertEquals(
          "fixture",
          client
              .send(
                  HttpRequest.newBuilder(fixture.uri()).build(),
                  HttpResponse.BodyHandlers.ofString())
              .body());
      assertEquals(1, fixture.calls());
    }
  }
}
