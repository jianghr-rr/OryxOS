package io.github.oryxlabs.oryxos.web.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.oryxlabs.oryxos.core.agent.AgentService;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.execution.Execution;
import io.github.oryxlabs.oryxos.core.observability.MdcContext;
import io.github.oryxlabs.oryxos.core.runtime.RuntimeTiming;
import io.github.oryxlabs.oryxos.core.runtime.TimeSource;
import io.github.oryxlabs.oryxos.web.api.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * One response per request; disconnected clients do not cancel admitted work or release its locks.
 */
public final class HttpExecutionAdapter implements AutoCloseable {
  public record Entered(
      Instant at, long nanos, java.util.function.Consumer<AgentService.Handle> observer) {
    public Entered(Instant at, long nanos) {
      this(at, nanos, h -> {});
    }
  }

  public interface Operation {
    AgentService.Handle start(Entered entered);
  }

  public static final String ENTERED = HttpExecutionAdapter.class.getName() + ".entered";
  private final TimeSource time;
  private final PublicDtoMapper mapper;
  private final ApiExceptionHandler errors;
  private final ObjectMapper json;
  // Admit the contracted 100 active sessions, with bounded headroom while completed
  // responses drain. The admission limit still rejects overload instead of queueing forever.
  private final Semaphore slots = new Semaphore(128);
  private final ExecutorService workers =
      Executors.newThreadPerTaskExecutor(
          Thread.ofVirtual().name("oryx-http-dispatch-", 0).factory());
  private final ScheduledExecutorService deadlines =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofPlatform().daemon(true).name("oryx-http-deadlines").factory());

  public HttpExecutionAdapter(TimeSource time, PublicDtoMapper mapper, ApiExceptionHandler errors) {
    this(
        time,
        mapper,
        errors,
        new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
  }

  public HttpExecutionAdapter(
      TimeSource time, PublicDtoMapper mapper, ApiExceptionHandler errors, ObjectMapper json) {
    this.time = time;
    this.mapper = mapper;
    this.errors = errors;
    this.json = json;
  }

  public OncePerRequestFilter ingress() {
    return new OncePerRequestFilter() {
      protected void doFilterInternal(
          HttpServletRequest request, HttpServletResponse response, FilterChain chain)
          throws ServletException, IOException {
        request.setAttribute(ENTERED, new Entered(time.instant(), time.nanoTime()));
        chain.doFilter(request, response);
      }
    };
  }

  public DeferredResult<ResponseEntity<?>> execute(
      HttpServletRequest request, Operation operation) {
    if (!slots.tryAcquire()) throw new OryxError("OVERLOADED", "HTTP execution capacity reached");
    var result = new DeferredResult<ResponseEntity<?>>(0L);
    dispatchAsync(
        request,
        result::setResult,
        error -> {
          throw error;
        },
        operation);
    return result;
  }

  public void executeAsync(
      HttpServletRequest request, HttpServletResponse response, Operation operation) {
    if (!slots.tryAcquire()) throw new OryxError("OVERLOADED", "HTTP execution capacity reached");
    AsyncContext async = request.startAsync(request, response);
    async.setTimeout(0L);
    var asyncClosed = new AtomicBoolean();
    async.addListener(
        new AsyncListener() {
          public void onComplete(AsyncEvent event) {
            asyncClosed.set(true);
          }

          public void onTimeout(AsyncEvent event) {
            asyncClosed.set(true);
          }

          public void onError(AsyncEvent event) {
            asyncClosed.set(true);
          }

          public void onStartAsync(AsyncEvent event) {}
        });
    Consumer<ResponseEntity<?>> completeDirect =
        entity -> writeDirect(async, response, asyncClosed, entity);
    dispatchAsync(
        request, completeDirect, error -> completeDirect.accept(errors.response(error)), operation);
  }

  private void writeDirect(
      AsyncContext async,
      HttpServletResponse response,
      AtomicBoolean asyncClosed,
      ResponseEntity<?> entity) {
    try {
      if (asyncClosed.get()) return;
      byte[] body = json.writeValueAsBytes(entity.getBody());
      if (asyncClosed.get()) return;
      response.setStatus(entity.getStatusCode().value());
      response.setContentType("application/json");
      response.setCharacterEncoding("UTF-8");
      response.setContentLength(body.length);
      var out = response.getOutputStream();
      out.write(body);
      out.flush();
    } catch (IOException | RuntimeException ignored) {
      // Disconnected clients must not disrupt settlement or lock release of admitted work.
    } finally {
      if (!asyncClosed.get()) {
        try {
          async.complete();
        } catch (RuntimeException ignored) {
        }
      }
    }
  }

  private void dispatchAsync(
      HttpServletRequest request,
      Consumer<ResponseEntity<?>> responder,
      Consumer<RuntimeException> onSubmitFailure,
      Operation operation) {
    var entered = (Entered) request.getAttribute(ENTERED);
    if (entered == null) entered = new Entered(time.instant(), time.nanoTime());
    var handle = new AtomicReference<AgentService.Handle>();
    var start = new Entered(entered.at(), entered.nanos(), handle::set);
    var mdc = MdcContext.capture();
    var responded = new AtomicBoolean();
    var slotReleased = new AtomicBoolean();
    Runnable releaseSlot =
        () -> {
          if (slotReleased.compareAndSet(false, true)) slots.release();
        };
    Consumer<ResponseEntity<?>> respondOnce =
        entity -> {
          if (responded.compareAndSet(false, true)) responder.accept(entity);
        };
    long remaining = Math.max(0, TimeUnit.SECONDS.toNanos(60) - (time.nanoTime() - start.nanos()));
    var timer =
        deadlines.schedule(
            mdc.wrap(() -> {
              var h = handle.get();
              if (h == null) {
                respondOnce.accept(
                    errors.response(
                        new OryxError(
                            "EXECUTION_TIMEOUT",
                            "Request admission deadline reached; no new action may start")));
                return;
              }
              h.expireIfDue(); // Seal the shared action permit before exposing the timeout
              // snapshot.
              respondOnce.accept(response(h.current(), h.waitExpired()));
            }),
            remaining,
            TimeUnit.NANOSECONDS);
    try {
      java.util.function.Function<AgentService.Handle, Boolean> attachCallbacks =
          attached ->
              attached.whenFinished(
                  mdc.wrap((Execution.Snapshot snapshot, Boolean expired) -> {
                    timer.cancel(false);
                    respondOnce.accept(response(snapshot, expired));
                  }),
                  mdc.wrap(() -> {
                    timer.cancel(false);
                    releaseSlot.run();
                  }));
      workers.execute(
          mdc.wrap(
              RuntimeTiming.wrap(
                  "http.dispatch.queue",
                  () -> {
                    AgentService.Handle h = null;
                    boolean[] handedOff = new boolean[1];
                    try {
                      h =
                          AgentService.withInlineWorker(
                              admitted -> {
                                handle.set(admitted);
                                admitted.expireIfDue();
                                handedOff[0] = attachCallbacks.apply(admitted);
                              },
                              () -> operation.start(start));
                      handle.set(h);
                      if (handedOff[0]) return;
                      h.expireIfDue();
                      if (attachCallbacks.apply(h)) {
                        handedOff[0] = true;
                        return;
                      }
                      var snapshot = h.await();
                      respondOnce.accept(response(snapshot, h.waitExpired()));
                    } catch (RuntimeException e) {
                      timer.cancel(false);
                      respondOnce.accept(errors.response(e));
                    } finally {
                      if (!handedOff[0]) {
                        timer.cancel(false);
                        if (h != null) h.awaitSettled();
                        releaseSlot.run();
                      }
                    }
                  })));
    } catch (RuntimeException e) {
      timer.cancel(false);
      releaseSlot.run();
      onSubmitFailure.accept(e);
    }
  }

  private ResponseEntity<?> response(Execution.Snapshot s, boolean waitExpired) {
    String code =
        switch (s.status()) {
          case TIMED_OUT -> "EXECUTION_TIMEOUT";
          case PARTIAL_RECORDING_FAILURE -> "PARTIAL_RECORDING_FAILURE";
          case FAILED, INTERRUPTED -> failureCode(s.errorCode());
          default -> "OK";
        };
    if (s.recordingStatus() == Execution.RecordingStatus.INCOMPLETE
        && s.status() != Execution.Status.TIMED_OUT) code = "PARTIAL_RECORDING_FAILURE";
    // A model result can be terminal while final persistence is still pending. The HTTP
    // wait still expires, while its data preserves the known result and unknown recording.
    if (waitExpired) code = "EXECUTION_TIMEOUT";
    int status = code.equals("OK") ? 200 : ApiExceptionHandler.status(code);
    if (status == 503)
      return ResponseEntity.status(status)
          .body(
              ApiResponse.of(
                  code,
                  "Execution could not complete",
                  new ContractDtos.ErrorDetail(
                      code,
                      "Execution could not complete",
                      s.id(),
                      s.recordingStatus().name(),
                      null)));
    return ResponseEntity.status(status)
        .body(
            ApiResponse.of(
                code,
                code.equals("OK") ? "OK" : "Inspect execution records before retrying",
                mapper.execution(s, null, null)));
  }

  private String failureCode(String code) {
    if (code != null && code.startsWith("PROVIDER_")) return "PROVIDER_UNAVAILABLE";
    if (java.util.Set.of("EXECUTION_PAUSED", "MEMORY_UNAVAILABLE", "OVERLOADED")
        .contains(String.valueOf(code))) return code;
    return "EXECUTION_FAILED";
  }

  public void close() {
    deadlines.shutdownNow();
    workers.shutdownNow();
  }
}
