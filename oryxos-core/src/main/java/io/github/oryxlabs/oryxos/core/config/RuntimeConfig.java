package io.github.oryxlabs.oryxos.core.config;

import java.time.ZoneId;
import java.util.Map;

public record RuntimeConfig(ZoneId timezone, String memoryBackend, Map<String, ProviderDefinition> providers,
                            Map<String, Object> toolRules, String loadedRevision,Map<String,Object> memoryRules) {
    public RuntimeConfig(ZoneId timezone,String backend,Map<String,ProviderDefinition> providers,Map<String,Object> rules,String revision){this(timezone,backend,providers,rules,revision,Map.of());}
    @Override public String toString() { return "RuntimeConfig[timezone=" + timezone + ", backend=" + memoryBackend + ", providers=" + providers.size() + ", revision=" + loadedRevision + "]"; }
    public RuntimeConfig { providers = Map.copyOf(providers); toolRules = Map.copyOf(toolRules); memoryRules=Map.copyOf(memoryRules); }
}
