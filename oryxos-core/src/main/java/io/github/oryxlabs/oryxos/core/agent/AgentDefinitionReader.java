package io.github.oryxlabs.oryxos.core.agent;

import io.github.oryxlabs.oryxos.core.config.*;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.security.SecretResolver;
import io.github.oryxlabs.oryxos.core.workspace.WorkspaceHandle;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.ZoneId;
import java.util.*;
import static io.github.oryxlabs.oryxos.core.config.SafeYaml.*;

/** AGENT.md is the only source; parsing never registers tools, runs processes or contacts services. */
public final class AgentDefinitionReader {
    public record Definition(Map<String,Object> configuration, String body) { }
    public Definition read(Path file) {
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(1024 * 1024 + 1);
            if (bytes.length > 1024 * 1024) throw OryxError.configuration("AGENT.md size");
            String source = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString().replace("\r\n", "\n");
            if (!source.startsWith("---\n")) throw OryxError.configuration("AGENT.md frontmatter");
            int end = source.indexOf("\n---", 4);
            if (end < 0 || (end + 4 < source.length() && source.charAt(end+4) != '\n')) throw OryxError.configuration("AGENT.md frontmatter");
            var configuration = document(source.substring(4,end).getBytes(StandardCharsets.UTF_8), "AGENT.md frontmatter").values();
            return new Definition(configuration, source.substring(Math.min(end+5, source.length())));
        } catch (Exception e) { throw OryxError.configuration("AGENT.md (safe YAML frontmatter and UTF-8 body)"); }
    }
    public AgentProfile derive(WorkspaceHandle workspace, Path file, RuntimeConfig runtime, SecretResolver secrets) {
        try {
            Path root = workspace.root().toRealPath();
            if (!file.toRealPath().startsWith(root.resolve("agents")) || Files.isSymbolicLink(file)
                    || Files.isSymbolicLink(file.getParent()) || Files.isSymbolicLink(root.resolve("agents")))
                throw OryxError.configuration("agent definition path");
            var definition = read(file); var c = definition.configuration();
            if (!Integer.valueOf(1).equals(c.get("schema_version"))) throw OryxError.configuration("agent.schema_version");
            String name = text(c.get("name"), "agent.name");
            if (!name.matches("[a-z0-9][a-z0-9-]{0,63}") || !name.equals(file.getParent().getFileName().toString())) throw OryxError.configuration("agent.name must match directory");
            var provider = map(c.get("provider"), "agent.provider");
            String providerName = text(provider.get("name"), "agent.provider.name");
            String model = text(provider.get("model"), "agent.provider.model");
            var registered = runtime.providers().get(providerName);
            if (registered == null) throw OryxError.configuration("agent.provider.name (not configured)");
            if (!registered.available()) throw OryxError.configuration("agent.provider: " + registered.error().safeMessage());
            var tools = strings(c.getOrDefault("tools", List.of()), "agent.tools");
            var servers = strings(c.getOrDefault("mcp_servers", List.of()), "agent.mcp_servers");
            validateServers(root, servers, secrets);
            var bootstrap = new ArrayList<Path>();
            for (String path : strings(c.getOrDefault("bootstrap", List.of()), "agent.bootstrap")) bootstrap.add(bootstrap(root,path));
            var notifications = new LinkedHashMap<String,AgentProfile.Notification>();
            map(c.getOrDefault("notify_channels",Map.of()), "agent.notify_channels").forEach((target,raw) -> {
                text(target,"agent.notify_channels name"); var n = map(raw,"agent.notify_channels target");
                if (!"webhook".equals(n.get("type"))) throw OryxError.configuration("agent.notify_channels.type");
                String reference = text(n.get("url"),"agent.notify_channels.url");
                try {
                    URI url = URI.create(secrets.resolve(reference));
                    if (!Set.of("http","https").contains(url.getScheme()) || url.getHost() == null || url.getUserInfo() != null) throw new IllegalArgumentException();
                } catch (RuntimeException e) { throw OryxError.configuration("agent.notify_channels.url reference"); }
                var payload = map(n.get("payload"),"agent.notify_channels.payload");
                var ack = map(n.get("acknowledgement"),"agent.notify_channels.acknowledgement");
                String mode = text(ack.get("mode"),"agent.notify_channels.acknowledgement.mode");
                if (!Set.of("http_2xx","json_equals").contains(mode)) throw OryxError.configuration("agent.notify_channels.acknowledgement.mode");
                if (mode.equals("json_equals") && (!(ack.get("pointer") instanceof String pointer) || !pointer.startsWith("/") || !ack.containsKey("expected"))) throw OryxError.configuration("agent.notify_channels.acknowledgement.pointer/expected");
                var headers=new LinkedHashMap<String,String>();map(n.getOrDefault("headers",Map.of()),"agent.notify_channels.headers").forEach((header,value)->{
                    if(!header.matches("[A-Za-z0-9-]+")||Set.of("host","content-length","transfer-encoding","connection").contains(header.toLowerCase(Locale.ROOT)))throw OryxError.configuration("notification header name");
                    String ref=text(value,"notification header reference");String resolved=secrets.resolve(ref);if(resolved.contains("\r")||resolved.contains("\n"))throw OryxError.configuration("notification header value");headers.put(header,ref);
                });
                notifications.put(target,new AgentProfile.Notification(reference, freeze(payload),freeze(ack),headers,target.equals(c.get("default_target"))));
            });
            if(c.containsKey("default_target")&&!notifications.containsKey(text(c.get("default_target"),"agent.default_target")))throw OryxError.configuration("agent.default_target");
            var schedules = new ArrayList<AgentProfile.Schedule>(); var ids = new HashSet<String>();
            if (!(c.getOrDefault("schedules", List.of()) instanceof List<?> list)) throw OryxError.configuration("agent.schedules");
            for (Object raw : list) {
                var s = map(raw,"agent.schedules entry"); String id = text(s.get("id"),"agent.schedules.id");
                if (!ids.add(id)) throw OryxError.configuration("agent.schedules.id duplicate");
                String cron = text(s.get("cron"),"agent.schedules.cron"); CronSyntax.validate(cron);
                ZoneId zone;
                try { zone = ZoneId.of(text(s.getOrDefault("timezone",runtime.timezone().getId()),"agent.schedules.timezone")); }
                catch (RuntimeException e) { throw OryxError.configuration("agent.schedules.timezone"); }
                if (!zone.equals(runtime.timezone())) throw OryxError.configuration("agent.schedules.timezone must match workspace timezone");
                if (!(s.getOrDefault("enabled",true) instanceof Boolean enabled)) throw OryxError.configuration("agent.schedules.enabled");
                int budget=positive(s.getOrDefault("max_duration_seconds",60),"agent.schedules.max_duration_seconds");
                if(budget<60)throw OryxError.configuration("agent.schedules.max_duration_seconds >= 60");
                schedules.add(new AgentProfile.Schedule(id,cron,zone,text(s.get("message"),"agent.schedules.message"),enabled,
                        budget));
            }
            var settings = map(c.getOrDefault("settings",Map.of()),"agent.settings");
            int iterations = positive(settings.getOrDefault("max_iterations",10),"agent.settings.max_iterations");
            if (iterations > 100) throw OryxError.configuration("agent.settings.max_iterations <= 100");
            int turns = positive(settings.getOrDefault("max_history_turns",20),"agent.settings.max_history_turns");
            Object description = c.getOrDefault("description","");
            if (!(description instanceof String)) throw OryxError.configuration("agent.description");
            return new AgentProfile(name,(String)description,providerName,model,tools,servers,bootstrap,notifications,schedules,
                    iterations,turns,file.toAbsolutePath().normalize(),hash(canonical(c)),hash(definition.body()));
        } catch (java.io.IOException e) { throw OryxError.configuration("agent definition path"); }
    }
    private static Path bootstrap(Path root, String text) {
        try {
            Path relative = Path.of(text); Path path = root.resolve(relative).normalize();
            if (relative.isAbsolute() || !path.startsWith(root) || !path.toRealPath().startsWith(root) || !Files.isRegularFile(path)
                    || sensitive(root.relativize(path)) || sensitive(root.relativize(path.toRealPath()))) throw new IllegalArgumentException();
            return path;
        } catch (Exception e) { throw OryxError.configuration("agent.bootstrap path missing, secret or outside workspace"); }
    }
    private static boolean sensitive(Path relative) {
        String first = relative.getName(0).toString();
        return Set.of("secrets.local.yaml","runtime.yaml","mcp_servers.yaml","workspace.json","runtime","oryxos.db","oryxos.db-wal","oryxos.db-shm").contains(first) || first.startsWith(".env");
    }
    private static void validateServers(Path root, List<String> names, SecretResolver secrets) {
        if (names.isEmpty()) return;
        var config = SafeYaml.read(root.resolve("mcp_servers.yaml"),"agent.mcp_servers definitions");
        if (!Integer.valueOf(1).equals(config.get("schema_version"))) throw OryxError.configuration("agent.mcp_servers schema_version");
        var servers = map(config.get("servers"),"agent.mcp_servers definitions");
        for (String name : names) {
            var server = map(servers.get(name),"agent.mcp_servers reference");
            if (!"stdio".equals(server.get("transport"))) throw OryxError.configuration("agent.mcp_servers transport");
            if (!Path.of(text(server.get("command"),"agent.mcp_servers command")).isAbsolute()) throw OryxError.configuration("agent.mcp_servers command");
            for (Object reference : map(server.getOrDefault("env",Map.of()),"agent.mcp_servers env").values()) secrets.resolve(text(reference,"agent.mcp_servers env reference"));
        }
    }
    private static List<String> strings(Object raw, String field) {
        if (!(raw instanceof List<?> list)) throw OryxError.configuration(field);
        var values = list.stream().map(v -> text(v,field)).toList();
        if (new HashSet<>(values).size() != values.size()) throw OryxError.configuration(field + " duplicate");
        return values;
    }
    private static Map<String,Object> freeze(Map<String,Object> values) {
        var result = new LinkedHashMap<String,Object>(); values.forEach((k,v) -> result.put(k,immutable(v))); return Collections.unmodifiableMap(result);
    }
    private static Object immutable(Object v) {
        if (v instanceof Map<?,?> m) return freeze(map(m,"agent configuration"));
        if (v instanceof List<?> l) return l.stream().map(AgentDefinitionReader::immutable).toList();
        return v;
    }
    private static String canonical(Object value) {
        if (value instanceof Map<?,?> map) {
            var sorted = new TreeMap<String,Object>(); map.forEach((k,v) -> sorted.put((String)k,v));
            var out = new StringBuilder("{"); sorted.forEach((k,v) -> out.append(canonical(k)).append(canonical(v))); return out.append('}').toString();
        }
        if (value instanceof List<?> list) return "[" + list.stream().map(AgentDefinitionReader::canonical).reduce("",String::concat) + "]";
        String s = String.valueOf(value); return (value == null ? "null" : value.getClass().getName()) + ":" + s.length() + ":" + s;
    }
    private static String hash(String source) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
