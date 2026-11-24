package io.github.oryxlabs.oryxos.provider;

import java.util.List;
import org.junit.jupiter.api.*;

/**
 * Kimi-only real ordinary request; DeepSeek configuration is not an input. Missing Kimi
 * prerequisites FAIL before any network request.
 */
@Tag("real-integration")
@Tag("real-provider-kimi")
class RealKimiProviderIT {
  final RealProviderAcceptance acceptance = new RealProviderAcceptance();

  @BeforeEach
  void prerequisites() {
    acceptance.prepare(List.of("kimi"));
  }

  @Test
  void kimiOrdinaryRequest() throws Exception {
    acceptance.ordinary("kimi");
  }
}
