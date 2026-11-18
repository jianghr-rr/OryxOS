package io.github.oryxlabs.oryxos.boot.runtime;

import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.runtime.RecoveryPort;
import io.github.oryxlabs.oryxos.core.security.Redactor;

/** Shared explicit recovery service; HTTP delegates here and never replays actions. */
public final class RuntimeRecoveryService implements RecoveryPort {
  private final RuntimeStartupCheck runtime;
  private final Redactor redactor;

  public RuntimeRecoveryService(RuntimeStartupCheck runtime, Redactor redactor) {
    this.runtime = runtime;
    this.redactor = redactor;
  }

  public State state() {
    return runtime.state();
  }

  public State recover(Request request) {
    if (request == null
        || request.incidentId() == null
        || !request.acknowledgeMissingRecords()
        || request.notes() == null
        || request.notes().isBlank()
        || request.notes().codePointCount(0, request.notes().length()) > 2000
        || request.operator() == null
        || request.operator().isBlank()
        || request.operator().length() > 200)
      throw new OryxError(
          "INVALID_INPUT",
          "Recovery requires incidentId, explicit missing-record acknowledgement, 1–2000 characters"
              + " of notes and an operator");
    return runtime.recover(
        new Request(
            request.incidentId(),
            true,
            redactor.redact(request.notes()),
            redactor.redact(request.operator())));
  }
}
