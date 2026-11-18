package io.github.oryxlabs.oryxos.core.context;

import io.github.oryxlabs.oryxos.core.execution.ExecutionContext;
import io.github.oryxlabs.oryxos.core.memory.ContextMemory;
import io.github.oryxlabs.oryxos.core.provider.ProviderRequest;
import io.github.oryxlabs.oryxos.core.tool.ToolDescriptor;
import java.time.Duration;
import java.util.*;

public final class PromptBuilder {
    private final ContextLoader loader;
    public PromptBuilder(ContextLoader loader){this.loader=loader;}
    public ProviderRequest build(ExecutionContext context,ContextMemory memory,List<ToolDescriptor> tools,int iteration) {
        String system=loader.load(context)+"\n\nCurrent workspace time: "+context.time().instant().atZone(context.execution().snapshot(0).timezone())
                +"\n\nCORE memory:\n"+memory.core()+"\n\nARCHIVAL memory:\n"+memory.archival();
        var messages=new ArrayList<>(memory.recent()); messages.addAll(memory.current());
        return new ProviderRequest(context.agent().providerName(),context.agent().model(),system,messages,
                tools.stream().map(t->new ProviderRequest.ToolSchema(t.name(),t.description(),t.inputSchemaJson())).toList(),
                context.execution().snapshot(0).toolNames(),Duration.ofNanos(context.deadline().remainingNanos()),context.execution().id(),iteration)
                .withDispatcher(context.dispatcher());
    }
}
