package io.github.oryxlabs.oryxos.memory;
import io.github.oryxlabs.oryxos.core.memory.*;
import io.github.oryxlabs.oryxos.core.execution.ExecutionContext;
import io.github.oryxlabs.oryxos.core.session.SessionHistoryPort;
import io.github.oryxlabs.oryxos.memory.backend.MemoryTextCodec;
import java.util.*;
import java.util.stream.Collectors;
/** History is requested exactly once. Loading context never saves dialogue as memory. */
public final class MemoryService implements MemoryPort {
    private final SessionHistoryPort history;private final LongTermMemoryStore store;private final int archivalLimit;
    public MemoryService(SessionHistoryPort history,LongTermMemoryStore store,int archivalLimit){this.history=history;this.store=store;if(archivalLimit<1)throw new IllegalArgumentException("Positive archival bound required");this.archivalLimit=Math.min(4000,archivalLimit);}
    public ContextMemory load(ExecutionContext ctx){
        var recent=history.history(ctx.execution().sessionId(),ctx.execution().turnId(),ctx.agent().maxHistoryTurns());
        var all=store.loadAll(ctx);
        var coreBuilder=new StringBuilder();
        var entries=new ArrayList<MemoryEntry>();
        for(var entry:all){
            if(entry.scope()==MemoryEntry.Scope.CORE){
                if(!coreBuilder.isEmpty()) coreBuilder.append('\n');
                coreBuilder.append(entry.rawContent());
            } else if(entry.scope()==MemoryEntry.Scope.ARCHIVAL){
                entries.add(entry);
            }
        }
        String core=coreBuilder.toString();
        entries.sort(Comparator.comparing(MemoryEntry::createdAt).thenComparing(MemoryEntry::id).reversed());
        String archival=entries.stream().map(MemoryEntry::rawContent).collect(Collectors.joining("\n"));
        if(ctx.redactor().containsSecret(core)||ctx.redactor().containsSecret(archival))throw new io.github.oryxlabs.oryxos.core.error.OryxError("MEMORY_UNAVAILABLE","Memory contains a registered credential and cannot enter model context");
        return new ContextMemory(recent.recent(),recent.current(),core,MemoryTextCodec.bounded(archival,archivalLimit));
    }
}
