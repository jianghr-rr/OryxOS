package io.github.oryxlabs.oryxos.tool.policy;

import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.execution.ExecutionContext;
import io.github.oryxlabs.oryxos.core.tool.OryxTool;
import java.util.Map;

public final class ToolPolicy {
    public void authorize(OryxTool tool,Map<String,Object> arguments,ExecutionContext context) {
        if(!context.agent().tools().contains(tool.descriptor().name()))throw new OryxError("TOOL_DENIED","Tool is not in this Agent's explicit allowlist");
        tool.authorize(arguments,context);
    }
}
