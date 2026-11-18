package io.github.oryxlabs.oryxos.tool.nativeext;

import org.springframework.ai.tool.annotation.Tool;

public final class EchoTool {
  @Tool(
      name = "echo",
      description = "Return the supplied text as a minimal native extension example")
  public String echo(String text) {
    return text;
  }
}
