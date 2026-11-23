package io.github.oryxlabs.oryxos.boot.observability;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.rolling.RollingFileAppender;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Rolling file appender that stays stopped, with a warning instead of an error status, when the log
 * directory cannot be created or written. The service keeps running on stderr logging alone.
 */
public class GuardedRollingFileAppender extends RollingFileAppender<ILoggingEvent> {
  @Override
  public void start() {
    String file = getFile();
    Path directory = file == null ? null : Path.of(file).toAbsolutePath().getParent();
    if (directory == null || !writable(directory)) {
      String shown = directory == null ? String.valueOf(file) : directory.toString();
      addWarn("log directory not writable: " + shown);
      System.err.println("[oryxos-logging] WARN log directory not writable: " + shown);
      return;
    }
    super.start();
  }

  private static boolean writable(Path directory) {
    try {
      Files.createDirectories(directory);
    } catch (IOException | SecurityException e) {
      return false;
    }
    return Files.isDirectory(directory) && Files.isWritable(directory);
  }
}
