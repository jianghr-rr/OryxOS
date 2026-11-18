package io.github.oryxlabs.oryxos.tool.file;

import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.execution.ExecutionContext;
import io.github.oryxlabs.oryxos.core.tool.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;

public final class WriteFileTool implements OryxTool {
  private final FileAccessPolicy policy;

  public WriteFileTool(FileAccessPolicy policy) {
    this.policy = policy;
  }

  public ToolDescriptor descriptor() {
    return staticDescriptor();
  }

  public static ToolDescriptor staticDescriptor() {
    return new ToolDescriptor(
        "write_file",
        "Atomically replace an allowed output file",
        "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"minLength\":1},\"content\":{\"type\":\"string\"}},\"required\":[\"path\",\"content\"],\"additionalProperties\":false}",
        false,
        true,
        null,
        null);
  }

  public void authorize(Map<String, Object> a, ExecutionContext c) {
    policy.authorize((String) a.get("path"), true);
    if (c.redactor().containsSecret((String) a.get("content")))
      throw new OryxError("TARGET_DENIED", "Known credentials cannot be written by ordinary tools");
  }

  public ToolResult execute(Map<String, Object> a, ExecutionContext c) {
    Path temp = null;
    boolean publishing = false;
    try {
      authorize(a, c);
      var path = policy.authorize((String) a.get("path"), true);
      c.dispatcher().check();
      Files.createDirectories(path.getParent());
      policy.verifyParents(path);
      temp = Files.createTempFile(path.getParent(), ".oryx-write-", ".tmp");
      try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
        var data = StandardCharsets.UTF_8.encode((String) a.get("content"));
        while (data.hasRemaining()) channel.write(data);
        channel.force(true);
      }
      c.dispatcher().check();
      policy.authorize((String) a.get("path"), true);
      publishing = true;
      Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      try (var dir = FileChannel.open(path.getParent(), StandardOpenOption.READ)) {
        dir.force(true);
      }
      return new ToolResult(
          ToolResult.Outcome.SUCCESS,
          "File replacement confirmed",
          null,
          null,
          ToolResult.RetryClass.NONE,
          ToolResult.SideEffectState.CONFIRMED,
          null,
          1,
          0);
    } catch (Exception e) {
      if (publishing)
        return new ToolResult(
            ToolResult.Outcome.UNKNOWN,
            "File replacement is uncertain; inspect before retrying",
            "FILE_WRITE_UNKNOWN",
            "Inspect the file before retrying",
            ToolResult.RetryClass.NONE,
            ToolResult.SideEffectState.UNKNOWN,
            null,
            1,
            0);
      return ToolResult.failure(
          e instanceof OryxError ? ToolResult.Outcome.DENIED : ToolResult.Outcome.ERROR,
          e instanceof OryxError o ? o.code() : "FILE_WRITE_FAILED",
          "File replacement did not start");
    } finally {
      if (temp != null)
        try {
          Files.deleteIfExists(temp);
        } catch (java.io.IOException ignored) {
        }
    }
  }
}
