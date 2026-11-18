package io.github.oryxlabs.oryxos.core.memory;
import java.time.Instant;
import java.util.*;
public record MemoryEntry(UUID id,UUID workspaceId,Scope scope,String rawContent,Instant createdAt,
                          UUID sourceExecutionId,String sourceAgentName,String backend,WriteStatus writeStatus,List<String> remoteIds) {
    public enum Scope { CORE, ARCHIVAL }
    public enum WriteStatus { PREPARED, CONFIRMED, UNKNOWN, FAILED }
    public MemoryEntry {
        Objects.requireNonNull(id);Objects.requireNonNull(workspaceId);Objects.requireNonNull(scope);Objects.requireNonNull(createdAt);Objects.requireNonNull(sourceExecutionId);Objects.requireNonNull(writeStatus);
        if(rawContent==null||rawContent.isBlank()||sourceAgentName==null||sourceAgentName.isBlank()||!Set.of("md","sqlite","mem0").contains(backend))throw new IllegalArgumentException("Invalid memory source");
        remoteIds=List.copyOf(remoteIds);
    }
    public MemoryEntry withState(WriteStatus state,List<String> ids){return new MemoryEntry(id,workspaceId,scope,rawContent,createdAt,sourceExecutionId,sourceAgentName,backend,state,ids);}
    @Override public String toString(){return "MemoryEntry[id="+id+", scope="+scope+", status="+writeStatus+", content withheld]";}
}
