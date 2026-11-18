package io.github.oryxlabs.oryxos.core.config;

import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.security.*;
import io.github.oryxlabs.oryxos.core.workspace.WorkspaceHandle;
import java.net.URI;
import java.nio.file.*;
import java.time.ZoneId;
import java.util.*;

/** Loads one restart-scoped snapshot. Provider failures are deliberately contained per definition. */
public final class RuntimeConfigLoader {
    private final Map<String, String> environment;
    private final Redactor redactor;
    public RuntimeConfigLoader(Map<String, String> environment, Redactor redactor) {
        this.environment = Map.copyOf(environment); this.redactor = redactor;
    }
    public RuntimeConfig load(WorkspaceHandle workspace) {
        Path file = workspace.root().resolve("runtime.yaml");
        var document = SafeYaml.document(file, "runtime.yaml");
        Map<String, Object> root = document.values();
        if (!Integer.valueOf(1).equals(root.get("schema_version"))) throw OryxError.configuration("schema_version");
        ZoneId timezone;
        try {
            String zone = SafeYaml.text(root.getOrDefault("timezone", "Asia/Shanghai"), "timezone");
            if (!ZoneId.getAvailableZoneIds().contains(zone)) throw OryxError.configuration("timezone");
            timezone = ZoneId.of(zone);
        } catch (Exception error) { throw OryxError.configuration("timezone"); }
        var secrets = new SecretResolver(environment, workspace.root().resolve("secrets.local.yaml"), redactor);
        var memory = SafeYaml.map(root.getOrDefault("memory", Map.of()), "memory");
        String backend = SafeYaml.text(memory.getOrDefault("backend", "md"), "memory.backend");
        if (!Set.of("md", "sqlite", "mem0").contains(backend)) throw OryxError.configuration("memory.backend");
        SafeYaml.positive(memory.getOrDefault("archival_context_characters", 4000), "memory.archival_context_characters");
        if (backend.equals("mem0")) {
            URI mem0Endpoint=endpoint(memory.get("endpoint"), "memory.endpoint");
            if(!Set.of("","/").contains(mem0Endpoint.getPath()))throw OryxError.configuration("memory.endpoint must be an OSS service root without /v1");
            SafeYaml.positive(memory.getOrDefault("timeout_seconds",20),"memory.timeout_seconds");
            SafeYaml.positive(memory.getOrDefault("search_top_k",10),"memory.search_top_k");
            Object rawThreshold=memory.getOrDefault("search_threshold",0.7);
            if(!(rawThreshold instanceof Number n)||!Double.isFinite(n.doubleValue())||n.doubleValue()<0||n.doubleValue()>1)throw OryxError.configuration("memory.search_threshold");
            secrets.resolve(SafeYaml.text(memory.get("api_key"), "memory.api_key"));
        }
        var rules = SafeYaml.map(root.getOrDefault("tools", Map.of()), "tools");
        validateRules(rules);
        var fileRules=SafeYaml.map(rules.getOrDefault("file",Map.of()),"tools.file");
        for(String key:List.of("allowed_read_paths","allowed_write_paths"))for(Object value:(List<?>)fileRules.getOrDefault(key,List.of()))if(!workspace.root().resolve((String)value).normalize().startsWith(workspace.root()))throw OryxError.configuration("tools.file paths outside workspace");
        var shellRules=SafeYaml.map(rules.getOrDefault("shell",Map.of()),"tools.shell");
        for(Object value:(List<?>)shellRules.getOrDefault("allowed_script_paths",List.of()))if(!workspace.root().resolve((String)value).normalize().startsWith(workspace.root()))throw OryxError.configuration("tools.shell script paths outside workspace");
        SafeYaml.map(shellRules.getOrDefault("env",Map.of()),"tools.shell.env").forEach((key,value)->{if(!key.matches("[A-Za-z_][A-Za-z0-9_]*")||Set.of("PATH","HOME","TMPDIR").contains(key))throw OryxError.configuration("tools.shell.env name");secrets.resolve(SafeYaml.text(value,"tools.shell.env reference"));});
        Map<String, ProviderDefinition> providers = new LinkedHashMap<>();
        SafeYaml.map(root.getOrDefault("providers", Map.of()), "providers").forEach((name, raw) -> {
            if (name.isBlank()) throw OryxError.configuration("providers name");
            try { providers.put(name, provider(name, raw, secrets)); }
            catch (OryxError error) {
                providers.put(name, new ProviderDefinition(name, null, null, null, null, 0, Map.of(),
                        new ConfigDiagnostics("PROVIDER_UNAVAILABLE", "providers definition", error.getMessage())));
            }
        });
        return new RuntimeConfig(timezone, backend, providers, freeze(rules), document.sha256(),freeze(memory));
    }
    private ProviderDefinition provider(String name, Object raw, SecretResolver secrets) {
        var data = SafeYaml.map(raw, "provider definition");
        String protocol = SafeYaml.text(data.get("protocol"), "provider.protocol");
        if (!protocol.equals("openai_chat_completions")) throw OryxError.configuration("provider.protocol");
        URI endpoint = endpoint(data.get("endpoint"), "provider.endpoint");
        String path = SafeYaml.text(data.get("completions_path"), "provider.completions_path");
        try {
            URI parsed = URI.create(path);
            if (!path.startsWith("/") || path.startsWith("//") || parsed.getRawQuery() != null || parsed.getRawFragment() != null
                    || !parsed.normalize().equals(parsed) || path.contains("//") || path.matches(".*/v1/v1(?:/.*)?")) throw new IllegalArgumentException();
        } catch (IllegalArgumentException error) { throw OryxError.configuration("provider.completions_path"); }
        String reference = SafeYaml.text(data.get("api_key"), "provider.api_key");
        secrets.resolve(reference); // Validate only; do not retain the resolved credential in the public snapshot.
        int timeout = SafeYaml.positive(data.getOrDefault("connect_timeout_seconds", 5), "provider.connect_timeout_seconds");
        var options = SafeYaml.map(data.getOrDefault("options", Map.of()), "provider.options");
        for (var option : options.entrySet()) {
            switch (option.getKey()) {
                case "thinking" -> {
                    var thinking = SafeYaml.map(option.getValue(), "provider.options.thinking");
                    if (thinking.size() != 1 || !(thinking.get("type") instanceof String type) || !Set.of("enabled", "disabled").contains(type)) throw OryxError.configuration("provider.options.thinking");
                }
                case "reasoning_effort" -> {
                    if (!(option.getValue() instanceof String value) || !Set.of("low", "medium", "high").contains(value)) throw OryxError.configuration("provider.options.reasoning_effort");
                }
                case "temperature" -> {
                    if (!(option.getValue() instanceof Number value) || !Double.isFinite(value.doubleValue()) || value.doubleValue() < 0 || value.doubleValue() > 2) throw OryxError.configuration("provider.options.temperature");
                }
                default -> throw OryxError.configuration("provider.options unsupported field");
            }
        }
        if ((name.equals("kimi") || endpoint.getHost().equalsIgnoreCase("api.moonshot.cn"))
                && (options.containsKey("temperature") || options.containsKey("thinking"))) throw OryxError.configuration("provider.options unsupported by Kimi");
        return new ProviderDefinition(name, protocol, endpoint, path, reference, timeout, freeze(options), null);
    }
    private static URI endpoint(Object value, String field) {
        try {
            URI uri = URI.create(SafeYaml.text(value, field));
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))
                    || uri.getPort() == 0 || uri.getPort() > 65535 || uri.getPort() < -1) throw new IllegalArgumentException();
            return uri;
        } catch (Exception error) { throw OryxError.configuration(field); }
    }
    private static void validateRules(Map<String, Object> rules) {
        for (String key : rules.keySet()) if (!Set.of("file", "shell", "http").contains(key)) throw OryxError.configuration("tools rule");
        var file = SafeYaml.map(rules.getOrDefault("file", Map.of()), "tools.file");
        for (String key : List.of("allowed_read_paths", "allowed_write_paths")) strings(file.getOrDefault(key, List.of()), "tools.file paths", false);
        var shell = SafeYaml.map(rules.getOrDefault("shell", Map.of()), "tools.shell");
        strings(shell.getOrDefault("allowed_commands", List.of()), "tools.shell commands", true);
        strings(shell.getOrDefault("allowed_script_paths", List.of()), "tools.shell script paths", false);
        SafeYaml.positive(shell.getOrDefault("timeout_seconds", 20), "tools.shell timeout");
        var http = SafeYaml.map(rules.getOrDefault("http", Map.of()), "tools.http");
        Object targets = http.getOrDefault("allowed_targets", List.of());
        if (!(targets instanceof List<?> list)) throw OryxError.configuration("tools.http targets");
        for (Object target : list) {
            var entry = SafeYaml.map(target, "tools.http target");
            String scheme = SafeYaml.text(entry.get("scheme"), "tools.http scheme");
            String host = SafeYaml.text(entry.get("host"), "tools.http host");
            if (!Set.of("http", "https").contains(scheme) || host.contains("/") || host.contains("@") || host.chars().anyMatch(Character::isWhitespace)) throw OryxError.configuration("tools.http target");
            try {
                String normalized = host.startsWith("*.") ? host.substring(2) : host;
                if (URI.create(scheme + "://" + normalized).getHost() == null) throw new IllegalArgumentException();
            } catch (IllegalArgumentException error) { throw OryxError.configuration("tools.http host"); }
            if (SafeYaml.positive(entry.get("port"), "tools.http port") > 65535) throw OryxError.configuration("tools.http port");
        }
        for (String key : List.of("connect_timeout_seconds", "read_timeout_seconds", "max_response_bytes")) {
            if (http.containsKey(key)) SafeYaml.positive(http.get(key), "tools.http limits");
        }
    }
    private static void strings(Object raw, String field, boolean absolute) {
        if (!(raw instanceof List<?> list)) throw OryxError.configuration(field);
        for (Object item : list) {
            try {
                Path path = Path.of(SafeYaml.text(item, field));
                if (absolute && !path.isAbsolute()) throw OryxError.configuration(field);
            } catch (IllegalArgumentException error) { throw OryxError.configuration(field); }
        }
    }
    private static Map<String, Object> freeze(Map<String, Object> input) {
        Map<String, Object> result = new LinkedHashMap<>();
        input.forEach((key, value) -> result.put(key, immutable(value)));
        return Collections.unmodifiableMap(result);
    }
    private static Object immutable(Object value) {
        if (value instanceof Map<?, ?> map) return freeze(SafeYaml.map(map, "configuration"));
        if (value instanceof List<?> list) return list.stream().map(RuntimeConfigLoader::immutable).toList();
        return value;
    }
}
