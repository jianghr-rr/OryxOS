package io.github.oryxlabs.oryxos.boot;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.oryxlabs.oryxos.boot.audit.StructuredLogAuditSink;
import io.github.oryxlabs.oryxos.core.audit.ModelCallRecord;
import io.github.oryxlabs.oryxos.core.security.Redactor;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;

@Tag("us1-contract")
class StructuredLogAuditTest {
  @Test
  void logProjectionIsJsonWithUnknownUsageAndNoSecretsOrOpaqueData() throws Exception {
    var redactor = new Redactor();
    redactor.register("private-api-key");
    var logger = (Logger) LoggerFactory.getLogger(StructuredLogAuditSink.class);
    var appender = new ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try {
      new StructuredLogAuditSink(redactor)
          .modelCall(
              new ModelCallRecord(
                  UUID.randomUUID(),
                  UUID.randomUUID(),
                  1,
                  1,
                  "deepseek",
                  "configured",
                  "private-api-key https://example.invalid/hook",
                  "SUCCEEDED",
                  Instant.now(),
                  Instant.now(),
                  25L,
                  null,
                  null,
                  null));
      assertEquals(1, appender.list.size());
      String output = appender.list.getFirst().getFormattedMessage();
      var json = new ObjectMapper().readTree(output);
      assertEquals("model_call", json.path("event").asText());
      assertTrue(json.path("inputTokens").isNull());
      assertEquals("structured_log_not_database", json.path("evidence").asText());
      assertFalse(output.contains("private-api-key"));
      assertFalse(output.contains("example.invalid"));
      assertFalse(json.has("providerOpaque"));
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }
}
