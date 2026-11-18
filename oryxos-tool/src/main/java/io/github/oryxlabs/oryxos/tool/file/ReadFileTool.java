package io.github.oryxlabs.oryxos.tool.file;

import io.github.oryxlabs.oryxos.core.execution.ExecutionContext;
import io.github.oryxlabs.oryxos.core.tool.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;

public final class ReadFileTool implements OryxTool {
  private final FileAccessPolicy policy;

  public ReadFileTool(FileAccessPolicy policy) {
    this.policy = policy;
  }

  static final String SCHEMA =
      "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"minLength\":1}},\"required\":[\"path\"],\"additionalProperties\":false}";

  public ToolDescriptor descriptor() {
    return staticDescriptor();
  }

  public static ToolDescriptor staticDescriptor() {
    return new ToolDescriptor(
        "read_file", "Read an allowed data file, at most 64 KiB", SCHEMA, true, false, null, null);
  }

  public void authorize(Map<String, Object> a, ExecutionContext c) {
    policy.authorize((String) a.get("path"), false);
  }

  public ToolResult execute(Map<String, Object> a, ExecutionContext c) {
    var path = policy.authorize((String) a.get("path"), false);
    c.dispatcher().check();
    try (var in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
      byte[] bytes = in.readNBytes(65537);
      boolean cut = bytes.length > 65536;
      return ToolResult.success(
          "truncated="
              + cut
              + "\n"
              + c.redactor()
                  .redact(
                      new String(bytes, 0, Math.min(65536, bytes.length), StandardCharsets.UTF_8)));
    } catch (java.io.IOException e) {
      return ToolResult.failure(
          ToolResult.Outcome.ERROR, "FILE_READ_FAILED", "Cannot read the allowed data file");
    }
  }
}
