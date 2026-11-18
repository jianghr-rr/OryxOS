package io.github.oryxlabs.oryxos.boot.support;

import java.util.concurrent.*;

/** Executor owned by the controlled external service, separate from application workers. */
public final class ExternalFixtureExecutor {
  private ExternalFixtureExecutor() {}

  public static ExecutorService clients() {
    return platformPool("oryx-load-driver-");
  }

  public static ExecutorService create() {
    // JDK21 HttpServer's synchronized request-body reads pin virtual carriers. The controlled
    // remote service must not occupy the application/driver carrier pool while awaiting IO.
    return platformPool("oryx-external-fixture-");
  }

  private static ExecutorService platformPool(String name) {
    var pool =
        new ThreadPoolExecutor(
            128,
            128,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(256),
            Thread.ofPlatform().daemon(true).name(name, 0).factory());
    pool.prestartAllCoreThreads();
    return pool;
  }
}
