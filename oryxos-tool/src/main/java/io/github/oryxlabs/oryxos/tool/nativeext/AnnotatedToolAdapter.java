package io.github.oryxlabs.oryxos.tool.nativeext;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.oryxlabs.oryxos.core.execution.ExecutionContext;
import io.github.oryxlabs.oryxos.core.tool.*;
import java.lang.reflect.*;
import java.util.Map;
import org.springframework.ai.tool.support.ToolDefinitions;

public final class AnnotatedToolAdapter implements OryxTool {
  private final Object bean;
  private final Method method;
  private final ToolDescriptor descriptor;
  private final ObjectMapper json = new ObjectMapper();

  public AnnotatedToolAdapter(Object bean, Method method, boolean readOnly) {
    if (!Modifier.isPublic(method.getModifiers()))
      throw new IllegalArgumentException("Annotated tools must expose public methods");
    this.bean = bean;
    this.method = method;
    var d = ToolDefinitions.from(method);
    descriptor =
        new ToolDescriptor(
            d.name(), d.description(), d.inputSchema(), readOnly, !readOnly, null, null, "NATIVE");
  }

  public ToolDescriptor descriptor() {
    return descriptor;
  }

  public void authorize(Map<String, Object> a, ExecutionContext c) {}

  public ToolResult execute(Map<String, Object> a, ExecutionContext c) {
    Object[] values = new Object[method.getParameterCount()];
    try {
      var params = method.getParameters();
      for (int i = 0; i < params.length; i++)
        values[i] =
            json.convertValue(
                a.get(params[i].getName()),
                json.getTypeFactory().constructType(params[i].getParameterizedType()));
    } catch (RuntimeException e) {
      return ToolResult.failure(
          ToolResult.Outcome.ERROR,
          "INVALID_ARGUMENTS",
          "Native tool parameters cannot be converted");
    }
    c.dispatcher().check();
    try {
      Object result = method.invoke(bean, values);
      String text = result instanceof String s ? s : json.writeValueAsString(result);
      return new ToolResult(
          ToolResult.Outcome.SUCCESS,
          text,
          null,
          null,
          ToolResult.RetryClass.NONE,
          descriptor.sideEffecting()
              ? ToolResult.SideEffectState.CONFIRMED
              : ToolResult.SideEffectState.NONE,
          null,
          1,
          0);
    } catch (Exception e) {
      return new ToolResult(
          descriptor.sideEffecting() ? ToolResult.Outcome.UNKNOWN : ToolResult.Outcome.ERROR,
          "Native tool did not return a usable result",
          "NATIVE_TOOL_FAILED",
          "No implicit retry was made",
          ToolResult.RetryClass.NONE,
          descriptor.sideEffecting()
              ? ToolResult.SideEffectState.UNKNOWN
              : ToolResult.SideEffectState.NONE,
          null,
          1,
          0);
    }
  }
}
