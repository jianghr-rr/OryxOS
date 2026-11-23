package io.github.oryxlabs.oryxos.boot.observability;

import io.github.oryxlabs.oryxos.core.security.Redactor;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/** Hands the runtime {@link Redactor} (with registered secrets) to the logging layer. */
@Component
public class LogRedactionRegistrar implements DisposableBean {
  public LogRedactionRegistrar(Redactor redactor) {
    LogRedaction.use(redactor);
  }

  @Override
  public void destroy() {
    LogRedaction.reset();
  }
}
