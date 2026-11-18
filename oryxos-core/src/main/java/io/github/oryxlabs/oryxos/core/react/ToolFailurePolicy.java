package io.github.oryxlabs.oryxos.core.react;

import io.github.oryxlabs.oryxos.core.tool.ToolResult;

public final class ToolFailurePolicy {
    private ToolFailurePolicy(){ }
    public static boolean stop(ToolResult result){return result.sideEffectState()==ToolResult.SideEffectState.UNKNOWN || result.outcome()==ToolResult.Outcome.UNKNOWN;}
}
