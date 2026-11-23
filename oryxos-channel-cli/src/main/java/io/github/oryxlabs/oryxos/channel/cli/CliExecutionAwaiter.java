package io.github.oryxlabs.oryxos.channel.cli;

import io.github.oryxlabs.oryxos.core.agent.AgentService;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.execution.*;
import io.github.oryxlabs.oryxos.core.runtime.TimeSource;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** The caller deadline includes admission and final recording, just as the HTTP adapter does. */
final class CliExecutionAwaiter {
  record Result(Execution.Snapshot snapshot, boolean expired) {}

  static Result execute(AgentService service, ProcessRequest request, TimeSource time) {
    var at = time.instant();
    long nanos = time.nanoTime();
    var handle = new AtomicReference<AgentService.Handle>();
    var result = new CompletableFuture<Result>();
    Thread.ofVirtual()
        .name("oryx-cli-admission")
        .start(() -> {
          try {
            var h = service.start(request, s -> {}, () -> {}, at, nanos, handle::set);
            handle.set(h);
            result.complete(new Result(h.await(), h.waitExpired()));
          } catch (RuntimeException e) {
            result.completeExceptionally(e);
          }
        });
    try {
      return result.get(
          Math.max(0, TimeUnit.SECONDS.toNanos(60) - (time.nanoTime() - nanos)),
          TimeUnit.NANOSECONDS);
    } catch (TimeoutException e) {
      var h = handle.get();
      if (h == null)
        throw new OryxError(
            "EXECUTION_TIMEOUT",
            "Admission deadline reached; inspect runtime records before retrying");
      h.expireIfDue();
      return new Result(h.current(), h.waitExpired());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new OryxError(
          "EXECUTION_TIMEOUT",
          "CLI wait interrupted; admitted work may still be running; inspect records before"
              + " retrying");
    } catch (ExecutionException e) {
      if (e.getCause() instanceof OryxError known) throw known;
      throw new OryxError(
          "EXECUTION_FAILED", "Execution could not complete; inspect runtime records");
    }
  }
}
