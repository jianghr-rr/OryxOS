package io.github.oryxlabs.oryxos.core.security;

import io.github.oryxlabs.oryxos.core.config.SafeYaml;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

public final class SecretResolver {
    private static final Pattern REFERENCE = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");
    private final Map<String, String> environment;
    private final Path localFile;
    private final Redactor redactor;
    public SecretResolver(Map<String, String> environment, Path localFile, Redactor redactor) {
        this.environment = Map.copyOf(environment); this.localFile = localFile; this.redactor = redactor;
    }
    public String resolve(String reference) {
        var matcher = REFERENCE.matcher(reference == null ? "" : reference);
        if (!matcher.matches()) throw OryxError.configuration("secret reference");
        String value = environment.get(matcher.group(1));
        if (value == null && Files.exists(localFile)) {
            try {
                if (Files.isSymbolicLink(localFile)) throw OryxError.configuration("secrets.local.yaml permissions");
                if (Files.getFileStore(localFile).supportsFileAttributeView("posix")) {
                    for (var permission : Files.getPosixFilePermissions(localFile)) {
                        if (permission.name().startsWith("GROUP_") || permission.name().startsWith("OTHERS_")) throw OryxError.configuration("secrets.local.yaml permissions (require 0600)");
                    }
                }
                Object candidate = SafeYaml.read(localFile, "secrets.local.yaml").get(matcher.group(1));
                if (candidate instanceof String text) value = text;
            } catch (java.io.IOException e) { throw OryxError.configuration("secrets.local.yaml"); }
        }
        if (value == null || value.isBlank() || value.contains("${")) throw OryxError.configuration("secret reference unresolved");
        redactor.register(value);
        return value;
    }
}
