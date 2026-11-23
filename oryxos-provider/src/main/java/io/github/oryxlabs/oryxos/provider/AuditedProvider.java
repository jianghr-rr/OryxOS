package io.github.oryxlabs.oryxos.provider;

import io.github.oryxlabs.oryxos.core.audit.AuditSink;
import io.github.oryxlabs.oryxos.core.audit.ModelCallRecord;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.provider.ProviderPort;
import io.github.oryxlabs.oryxos.core.provider.ProviderReply;
import io.github.oryxlabs.oryxos.core.provider.ProviderRequest;
import io.github.oryxlabs.oryxos.core.runtime.Uuids;
import io.github.oryxlabs.oryxos.core.security.Redactor;
import java.time.Instant;
import java.util.UUID;

public final class AuditedProvider implements ProviderPort {
  private final ProviderPort delegate;
  private final AuditSink sink;
  private final Redactor redactor;

  public AuditedProvider(ProviderPort delegate, AuditSink sink, Redactor redactor) {
    this.delegate = delegate;
    this.sink = sink;
    this.redactor = redactor;
  }

  public ProviderReply call(ProviderRequest request) {
    UUID id = Uuids.v4();
    Instant started = Instant.now();
    long begin = System.nanoTime();
    write(record(id, request, started, null, null, "STARTED", null, null, null, null), false);
    ProviderReply reply;
    try {
      var remaining =
          request.withRemainingDuration(
              request.remainingDuration().minusNanos(System.nanoTime() - begin));
      reply = delegate.call(remaining);
    } catch (RuntimeException raw) {
      OryxError error = raw instanceof OryxError known ? known : ProviderErrorMapper.protocol();
      write(
          record(
              id,
              request,
              started,
              Instant.now(),
              elapsed(begin),
              "FAILED",
              null,
              null,
              null,
              error.code()),
          true);
      throw error;
    }
    var usage = reply.usage();
    write(
        record(
            id,
            request,
            started,
            Instant.now(),
            elapsed(begin),
            "SUCCEEDED",
            reply.actualModel(),
            usage == null ? null : usage.inputTokens(),
            usage == null ? null : usage.outputTokens(),
            null),
        true);
    return reply;
  }

  private ModelCallRecord record(
      UUID id,
      ProviderRequest r,
      Instant start,
      Instant end,
      Long duration,
      String status,
      String actual,
      Integer input,
      Integer output,
      String error) {
    return new ModelCallRecord(
        id,
        r.executionId(),
        r.iteration(),
        1,
        safe(r.providerName()),
        safe(r.model()),
        safe(actual),
        status,
        start,
        end,
        duration,
        input,
        output,
        error);
  }

  private String safe(String value) {
    String safe = redactor.redact(value);
    if (safe == null) {
      return null;
    }
    safe = safe.replaceAll("[\\p{Cntrl}]", "?");
    return safe.substring(0, Math.min(256, safe.length()));
  }

  private static long elapsed(long begin) {
    return (System.nanoTime() - begin) / 1_000_000;
  }

  private void write(ModelCallRecord record, boolean attempted) {
    try {
      sink.modelCall(record);
    } catch (RuntimeException error) {
      throw new OryxError(
          attempted ? "PARTIAL_RECORDING_FAILURE" : "RECORDING_UNAVAILABLE",
          attempted
              ? "Model attempt may have occurred but its record could not be saved; do not replay automatically"
              : "Cannot record model intent; no request was sent");
    }
  }
}
