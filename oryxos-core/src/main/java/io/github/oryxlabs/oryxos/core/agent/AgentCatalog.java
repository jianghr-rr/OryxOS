package io.github.oryxlabs.oryxos.core.agent;

import io.github.oryxlabs.oryxos.core.config.*;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.security.Redactor;
import io.github.oryxlabs.oryxos.core.workspace.WorkspaceHandle;
import java.util.*;
import java.nio.file.*;
import io.github.oryxlabs.oryxos.core.security.SecretResolver;

public final class AgentCatalog {
    private final List<Entry> entries;
    private AgentCatalog(List<Entry> entries) { this.entries = List.copyOf(entries); }
    public record Entry(String name, AgentProfile profile, ConfigDiagnostics error) {
        public boolean available() { return error == null; }
    }
    public static AgentCatalog load(WorkspaceHandle workspace, RuntimeConfig config, Map<String,String> environment, Redactor redactor) {
        Path directory = workspace.root().resolve("agents");
        if (!Files.exists(directory)) return new AgentCatalog(List.of());
        var entries = new ArrayList<Entry>();
        var reader = new AgentDefinitionReader();
        var secrets = new SecretResolver(environment, workspace.root().resolve("secrets.local.yaml"), redactor);
        try (var paths = Files.list(directory)) {
            for (Path path : paths.filter(Files::isDirectory).sorted().toList()) {
                String name = path.getFileName().toString();
                String safeName = name.matches("[a-z0-9][a-z0-9-]{0,63}") ? name : "[invalid directory name]";
                try { entries.add(new Entry(safeName, reader.derive(workspace,path.resolve("AGENT.md"),config,secrets),null)); }
                catch (RuntimeException e) {
                    String message = e instanceof OryxError ? e.getMessage() : "Invalid agent configuration";
                    entries.add(new Entry(safeName,null,new ConfigDiagnostics("AGENT_UNAVAILABLE","AGENT.md",redactor.redact(message))));
                }
            }
        } catch (java.io.IOException e) { throw OryxError.configuration("agents directory"); }
        return new AgentCatalog(entries);
    }
    public AgentCatalog validateTools(io.github.oryxlabs.oryxos.core.tool.ToolRegistryPort tools,java.util.function.Predicate<String> serverAvailable){
        return new AgentCatalog(entries.stream().map(e->{
            if(!e.available())return e;
            var p=e.profile();boolean valid=p.mcpServers().stream().allMatch(serverAvailable)&&p.tools().stream().allMatch(name->{var tool=tools.find(name);return tool!=null&&(tool.descriptor().serverId()==null||p.mcpServers().contains(tool.descriptor().serverId()));});
            return valid?e:new Entry(e.name(),null,new ConfigDiagnostics("AGENT_UNAVAILABLE","agent.tools/mcp_servers","An explicitly selected tool or MCP server is unavailable"));
        }).toList());
    }
    public AgentCatalog validateSchedules(java.util.function.Predicate<String> validCron) {
        return new AgentCatalog(entries.stream().map(e -> !e.available() || e.profile().schedules().stream().allMatch(s -> validCron.test(s.cron()))
            ? e : new Entry(e.name(),null,new ConfigDiagnostics("AGENT_UNAVAILABLE","agent.schedules.cron","Schedule cron is not supported by the runtime"))).toList());
    }
    public List<Entry> entries() { return entries; }
    public AgentProfile require(String name) {
        var entry = entries.stream().filter(e -> e.name().equals(name)).findFirst()
                .orElseThrow(() -> new OryxError("AGENT_UNAVAILABLE", "Agent name is not configured"));
        if (!entry.available()) throw new OryxError("AGENT_UNAVAILABLE", entry.error().safeMessage());
        return entry.profile();
    }
}
