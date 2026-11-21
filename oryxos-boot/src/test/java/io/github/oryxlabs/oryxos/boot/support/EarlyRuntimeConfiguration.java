package io.github.oryxlabs.oryxos.boot.support;

import io.github.oryxlabs.oryxos.boot.audit.StructuredLogAuditSink;
import io.github.oryxlabs.oryxos.core.audit.*;
import io.github.oryxlabs.oryxos.core.security.Redactor;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;

@TestConfiguration(proxyBeanMethods = false)
@Profile({"us2-local", "us2-real"})
public class EarlyRuntimeConfiguration {
  public static volatile TransientSessionStore lastSessions;
  public static volatile TransientExecutionStore lastExecutions;
  public static volatile RecordingAudit lastAudit;

  @Bean
  TransientSessionStore transientSessionStore() {
    return lastSessions = new TransientSessionStore();
  }

  @Bean
  TransientExecutionStore transientExecutionStore() {
    return lastExecutions = new TransientExecutionStore();
  }

  @Bean
  @Primary
  AuditSink earlyAuditSink(Redactor redactor) {
    return lastAudit = new RecordingAudit(redactor);
  }

  public static final class RecordingAudit implements AuditSink {
    public final List<ModelCallRecord> models = new CopyOnWriteArrayList<>();
    public final List<ToolCallRecord> tools = new CopyOnWriteArrayList<>();
    private final AuditSink log;

    RecordingAudit(Redactor r) {
      log = new StructuredLogAuditSink(r);
    }

    public void modelCall(ModelCallRecord r) {
      models.add(r);
      log.modelCall(r);
    }

    public void toolCall(ToolCallRecord r) {
      tools.add(r);
      log.toolCall(r);
    }
  }
}
