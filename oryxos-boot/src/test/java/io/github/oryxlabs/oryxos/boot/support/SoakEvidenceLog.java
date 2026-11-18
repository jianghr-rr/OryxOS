package io.github.oryxlabs.oryxos.boot.support;

import java.io.*;
import java.util.concurrent.locks.ReentrantLock;

/** Synchronous diagnostic evidence only; every append is flushed before returning. */
public final class SoakEvidenceLog implements AutoCloseable {
  private final BufferedWriter writer;
  private final ReentrantLock lock = new ReentrantLock();

  public SoakEvidenceLog(BufferedWriter writer) {
    this.writer = writer;
  }

  public void append(Object value) throws IOException {
    String line = SoakHarness.JSON.writeValueAsString(value);
    lock.lock();
    try {
      writer.write(line);
      writer.newLine();
      writer.flush();
    } finally {
      lock.unlock();
    }
  }

  public void close() throws IOException {
    lock.lock();
    try {
      writer.close();
    } finally {
      lock.unlock();
    }
  }
}
