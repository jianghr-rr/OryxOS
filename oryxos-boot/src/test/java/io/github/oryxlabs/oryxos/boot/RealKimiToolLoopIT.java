package io.github.oryxlabs.oryxos.boot;

import java.util.List;
import org.junit.jupiter.api.*;

/** Kimi-only real tool round trip; DeepSeek configuration is not an input. */
@Tag("real-integration")
@Tag("real-provider-tools-kimi")
class RealKimiToolLoopIT {
  final RealToolLoopAcceptance acceptance = new RealToolLoopAcceptance();

  @BeforeEach
  void prerequisites() {
    acceptance.prepare(List.of("kimi"));
  }

  @Test
  void kimiToolResultRoundTrip() throws Exception {
    acceptance.run("kimi");
  }
}
