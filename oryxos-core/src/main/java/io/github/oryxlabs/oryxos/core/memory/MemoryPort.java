package io.github.oryxlabs.oryxos.core.memory;

import io.github.oryxlabs.oryxos.core.execution.ExecutionContext;

@FunctionalInterface
public interface MemoryPort { ContextMemory load(ExecutionContext context); }
