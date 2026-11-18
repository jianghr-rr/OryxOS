package io.github.oryxlabs.oryxos.core.config;

import io.github.oryxlabs.oryxos.core.error.OryxError;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

public final class SafeYaml {
    private SafeYaml() { }
    public record Document(Map<String, Object> values, String sha256) { }
    public static Map<String, Object> read(Path file, String safeField) {
        return document(file, safeField).values();
    }
    public static Document document(Path file, String safeField) {
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(1024 * 1024 + 1);
            return document(bytes, safeField);
        } catch (Exception error) { throw OryxError.configuration(safeField); }
    }
    public static Document document(byte[] bytes, String safeField) {
        try {
            if (bytes.length > 1024 * 1024) throw OryxError.configuration(safeField);
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            options.setMaxAliasesForCollections(0);
            options.setNestingDepthLimit(64);
            options.setCodePointLimit(1024 * 1024);
            Object value = new Yaml(new SafeConstructor(options)).load(new String(bytes, StandardCharsets.UTF_8));
            String revision = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            return new Document(map(value, safeField), revision);
        } catch (Exception error) {
            // YAML parser messages quote input, so deliberately do not preserve their message/cause.
            throw OryxError.configuration(safeField);
        }
    }
    public static Map<String, Object> map(Object value, String safeField) {
        if (!(value instanceof Map<?, ?> source)) throw OryxError.configuration(safeField);
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> {
            if (!(key instanceof String name)) throw OryxError.configuration(safeField);
            result.put(name, item);
        });
        return Collections.unmodifiableMap(result);
    }
    public static String text(Object value, String field) {
        if (!(value instanceof String text) || text.isBlank()) throw OryxError.configuration(field);
        return text;
    }
    public static int positive(Object value, String field) {
        if (!(value instanceof Number n) || n.longValue() != n.doubleValue() || n.longValue() < 1 || n.longValue() > Integer.MAX_VALUE) throw OryxError.configuration(field);
        return n.intValue();
    }
}
