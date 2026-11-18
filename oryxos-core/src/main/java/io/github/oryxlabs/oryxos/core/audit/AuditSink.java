package io.github.oryxlabs.oryxos.core.audit;

@FunctionalInterface
public interface AuditSink {
    /** Return only after the record has been accepted, or throw; no silent loss. */
    void modelCall(ModelCallRecord record);
    /** US1 sinks that do not support tools must fail closed rather than discard records. */
    default void toolCall(ToolCallRecord record) { throw new IllegalStateException("Tool audit sink is not configured"); }
}
