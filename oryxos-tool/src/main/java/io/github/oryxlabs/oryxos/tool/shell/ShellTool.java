package io.github.oryxlabs.oryxos.tool.shell;

import io.github.oryxlabs.oryxos.core.execution.ExecutionContext;
import io.github.oryxlabs.oryxos.core.tool.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class ShellTool implements OryxTool {
  private final ProcessPolicy policy;

  public ShellTool(ProcessPolicy policy) {
    this.policy = policy;
  }

  public ToolDescriptor descriptor() {
    return staticDescriptor();
  }

  public static ToolDescriptor staticDescriptor() {
    return new ToolDescriptor(
        "shell",
        "Execute an explicitly allowed command and trusted script as argv; no OS/network sandbox",
        "{\"type\":\"object\",\"properties\":{\"command\":{\"type\":\"string\",\"minLength\":1},\"args\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},\"workingDirectory\":{\"type\":\"string\"}},\"required\":[\"command\"],\"additionalProperties\":false}",
        false,
        true,
        null,
        null);
  }

  public void authorize(Map<String, Object> a, ExecutionContext c) {
    policy.authorize(a);
  }

  public ToolResult execute(Map<String, Object> a, ExecutionContext c) {
    var invocation = policy.authorize(a);
    Process process = null;
    ExecutorService drains = Executors.newVirtualThreadPerTaskExecutor();
    try {
      for (String k : List.of("HOME", "TMPDIR"))
        Files.createDirectories(Path.of(invocation.environment().get(k)));
      var builder =
          new ProcessBuilder(invocation.argv()).directory(invocation.directory().toFile());
      builder.environment().clear();
      builder.environment().putAll(invocation.environment());
      c.dispatcher().check();
      process = builder.start();
      process.getOutputStream().close();
      final Process child = process;
      var stdout = drains.submit(() -> capture(child.getInputStream()));
      var stderr = drains.submit(() -> capture(child.getErrorStream()));
      long budget =
          Math.min(
              TimeUnit.SECONDS.toNanos(policy.timeoutSeconds()), c.deadline().remainingNanos());
      if (budget <= 0 || !process.waitFor(budget, TimeUnit.NANOSECONDS)) {
        boolean stopped = terminate(process);
        return unknown("Process exceeded its budget; cleanupConfirmed=" + stopped);
      }
      String content =
          "exitCode="
              + process.exitValue()
              + "\nstdout:\n"
              + stdout.get(2, TimeUnit.SECONDS)
              + "\nstderr:\n"
              + stderr.get(2, TimeUnit.SECONDS);
      return new ToolResult(
          process.exitValue() == 0 ? ToolResult.Outcome.SUCCESS : ToolResult.Outcome.ERROR,
          c.redactor().redact(content),
          process.exitValue() == 0 ? null : "PROCESS_EXIT_NONZERO",
          "Process exited",
          ToolResult.RetryClass.NONE,
          ToolResult.SideEffectState.CONFIRMED,
          null,
          1,
          0);
    } catch (Exception e) {
      if (process != null) {
        boolean stopped = terminate(process);
        return unknown("Process result is uncertain; cleanupConfirmed=" + stopped);
      }
      return ToolResult.failure(
          ToolResult.Outcome.ERROR, "PROCESS_START_FAILED", "Allowed process could not start");
    } finally {
      if (process != null && process.isAlive()) terminate(process);
      drains.shutdownNow();
    }
  }

  static String capture(InputStream in) throws IOException {
    try (in) {
      var output = new ByteArrayOutputStream();
      byte[] buffer = new byte[4096];
      boolean cut = false;
      int n;
      while ((n = in.read(buffer)) != -1) {
        int keep = Math.min(n, Math.max(0, 32768 - output.size()));
        output.write(buffer, 0, keep);
        cut |= keep < n;
      }
      return "truncated=" + cut + "\n" + output.toString(StandardCharsets.UTF_8);
    }
  }

  public static boolean terminate(Process process) {
    var children = process.descendants().toList();
    for (var child : children.reversed()) child.destroy();
    process.destroy();
    try {
      process.waitFor(200, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    for (var child : children.reversed()) if (child.isAlive()) child.destroyForcibly();
    if (process.isAlive()) process.destroyForcibly();
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while ((process.isAlive() || children.stream().anyMatch(ProcessHandle::isAlive))
        && System.nanoTime() < end) {
      java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
    }
    return !process.isAlive() && children.stream().noneMatch(ProcessHandle::isAlive);
  }

  private static ToolResult unknown(String text) {
    return new ToolResult(
        ToolResult.Outcome.UNKNOWN,
        text,
        "PROCESS_RESULT_UNKNOWN",
        text,
        ToolResult.RetryClass.NONE,
        ToolResult.SideEffectState.UNKNOWN,
        null,
        1,
        0);
  }
}
