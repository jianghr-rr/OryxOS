package io.github.oryxlabs.oryxos.core.config;

import java.net.URI;
import java.util.Map;

/** Stores only a secret reference; resolving credentials is a separate private boundary. */
public record ProviderDefinition(String name, String protocol, URI endpoint, String completionsPath,
                                 String apiKeyReference, int connectTimeoutSeconds, Map<String, Object> options,
                                 ConfigDiagnostics error) {
    public boolean available() { return error == null; }
    @Override public String toString() { return "ProviderDefinition[" + name + ", " + (available() ? "CONFIGURED" : "UNAVAILABLE") + "]"; }
}
