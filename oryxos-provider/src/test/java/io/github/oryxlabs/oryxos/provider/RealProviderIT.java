package io.github.oryxlabs.oryxos.provider;

import java.util.List;
import org.junit.jupiter.api.*;

/**
 * Explicit, credential-dependent tests. Both DeepSeek and Kimi must be ready; missing prerequisites
 * FAIL; there is no assume/skip/mock path.
 */
@Tag("real-integration")
@Tag("real-provider")
class RealProviderIT {
  final RealProviderAcceptance acceptance = new RealProviderAcceptance();

  @BeforeEach
  void prerequisites() {
    acceptance.prepare(List.of("deepseek", "kimi"));
  }

  @Test
  void deepseekOrdinaryRequest() throws Exception {
    acceptance.ordinary("deepseek");
  }

  @Test
  void kimiOrdinaryRequest() throws Exception {
    acceptance.ordinary("kimi");
  }
}
