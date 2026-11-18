package io.github.oryxlabs.oryxos.web.openapi;

import java.lang.annotation.*;

/**
 * Public response metadata for a registered MVC handler, including asynchronous execution results.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface HttpContract {
  Class<?> data();

  int success() default 200;

  int[] errors() default {400, 404, 409, 500, 503};

  /** These statuses can carry either an execution snapshot or a pre-admission error. */
  int[] executionErrors() default {};

  /** Non-success responses with the same data shape, e.g. readiness probes. */
  int[] dataErrors() default {};
}
