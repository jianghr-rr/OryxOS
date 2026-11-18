package io.github.oryxlabs.oryxos.tool.nativeext;

import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.tool.OryxTool;
import java.util.*;
import org.springframework.ai.tool.annotation.Tool;

public final class NativeToolRegistrar {
  public List<OryxTool> register(List<Object> beans) {
    var result = new ArrayList<OryxTool>();
    var names = new HashSet<String>();
    for (Object bean : beans)
      for (var method : bean.getClass().getDeclaredMethods())
        if (method.isAnnotationPresent(Tool.class)) {
          var tool = new AnnotatedToolAdapter(bean, method, bean instanceof EchoTool);
          if (!names.add(tool.descriptor().name()))
            throw OryxError.configuration("duplicate native tool name");
          result.add(tool);
        }
    return List.copyOf(result);
  }
}
