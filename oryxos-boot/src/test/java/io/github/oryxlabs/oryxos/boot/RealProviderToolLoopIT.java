package io.github.oryxlabs.oryxos.boot;

import java.util.List;
import org.junit.jupiter.api.*;

/** Real models only; both DeepSeek and Kimi must be ready before either round trip runs. */
@Tag("real-integration")
@Tag("real-provider-tools")
class RealProviderToolLoopIT {
  final RealToolLoopAcceptance acceptance = new RealToolLoopAcceptance();

  @BeforeEach
  void prerequisites() {
    acceptance.prepare(List.of("deepseek", "kimi"));
  }

  @Test
  void deepseekToolResultRoundTrip() throws Exception {
    acceptance.run("deepseek");
  }

  @Test
  void kimiToolResultRoundTrip() throws Exception {
    acceptance.run("kimi");
  }
}
