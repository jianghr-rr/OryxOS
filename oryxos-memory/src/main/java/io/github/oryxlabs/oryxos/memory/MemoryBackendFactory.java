package io.github.oryxlabs.oryxos.memory;
import io.github.oryxlabs.oryxos.core.config.RuntimeConfig;
import io.github.oryxlabs.oryxos.core.memory.*;
import io.github.oryxlabs.oryxos.core.workspace.*;
import io.github.oryxlabs.oryxos.core.security.*;
import io.github.oryxlabs.oryxos.memory.backend.*;
import io.github.oryxlabs.oryxos.memory.mem0.*;
import java.util.Map;
public final class MemoryBackendFactory {
    public LongTermMemoryStore create(RuntimeConfig config,WorkspaceHandle workspace,WorkspaceProcessLock lock,MemoryRecordStore records,Map<String,String> environment,Redactor redactor){
        var id=WorkspaceInitializer.readId(workspace);
        return switch(config.memoryBackend()){
            case "md" -> new MarkdownMemoryStore(workspace,id,lock);
            case "sqlite" -> new SqliteMemoryStore(id,records);
            case "mem0" -> {var settings=Mem0Config.from(config.memoryRules());String key=new SecretResolver(environment,workspace.root().resolve("secrets.local.yaml"),redactor).resolve(settings.apiKeyReference());yield new Mem0MemoryStore(id,records,new Mem0Client(settings,key));}
            default -> throw new IllegalArgumentException("Unknown memory backend");
        };
    }
}
