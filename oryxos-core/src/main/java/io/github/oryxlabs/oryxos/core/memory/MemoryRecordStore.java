package io.github.oryxlabs.oryxos.core.memory;
import java.time.Instant;
import java.util.*;
/** Local source ledger. Implementations use short transactions, never remote IO inside one. */
public interface MemoryRecordStore {
    void insert(MemoryEntry entry);
    void finish(UUID workspace,UUID id,MemoryEntry.WriteStatus state,List<String> remoteIds,Instant verifiedAt);
    List<MemoryEntry> list(UUID workspace,String backend,MemoryEntry.Scope scope,boolean confirmedOnly);
    MemoryEntry find(UUID workspace,UUID id);
    boolean maps(UUID workspace,UUID source,String remoteId);
}
