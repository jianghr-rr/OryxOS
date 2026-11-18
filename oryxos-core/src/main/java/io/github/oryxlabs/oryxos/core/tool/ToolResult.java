package io.github.oryxlabs.oryxos.core.tool;

public record ToolResult(Outcome outcome, String content, String errorCode, String safeMessage,
                         RetryClass retryClass, SideEffectState sideEffectState, String callId,
                         int attemptCount, long durationMs) {
    public enum Outcome { SUCCESS, ERROR, DENIED, UNKNOWN, NOT_EXECUTED }
    public enum RetryClass { NONE, SAFE_TRANSIENT }
    public enum SideEffectState { NONE, CONFIRMED, UNKNOWN }
    public static ToolResult success(String content) { return new ToolResult(Outcome.SUCCESS,content,null,null,RetryClass.NONE,SideEffectState.NONE,null,1,0); }
    public static ToolResult failure(Outcome outcome, String code, String message) {
        return new ToolResult(outcome,message,code,message,RetryClass.NONE,SideEffectState.NONE,null,0,0);
    }
    public ToolResult measured(String id, int attempts, long millis) { return new ToolResult(outcome,content,errorCode,safeMessage,retryClass,sideEffectState,id,attempts,millis); }
    @Override public String toString() { return "ToolResult[outcome="+outcome+", errorCode="+errorCode+", content withheld]"; }
}
