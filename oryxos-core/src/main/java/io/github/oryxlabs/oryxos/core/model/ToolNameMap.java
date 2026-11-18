package io.github.oryxlabs.oryxos.core.model;

import io.github.oryxlabs.oryxos.core.error.OryxError;
import java.util.*;

/** Immutable execution snapshot; MCP remoteName is never reconstructed from internalName. */
public record ToolNameMap(List<Entry> entries) {
    public record Entry(String internalName, String wireName, String serverId, String remoteName) { }
    public ToolNameMap {
        entries = List.copyOf(entries);
        var internal = new HashSet<String>(); var wire = new HashSet<String>();
        for (var e : entries) {
            if (e.internalName() == null || e.internalName().isBlank() || e.wireName() == null
                    || !e.wireName().matches("[A-Za-z_][A-Za-z0-9_-]{0,63}")
                    || !internal.add(e.internalName()) || !wire.add(e.wireName()))
                throw OryxError.configuration("tool name mapping collision or invalid name");
        }
    }
    public Entry internal(String name) {
        return entries.stream().filter(e -> e.internalName().equals(name)).findFirst()
                .orElseThrow(() -> new OryxError("UNKNOWN_TOOL", "Tool is absent from this execution's name map"));
    }
    public Entry wire(String name) {
        return findWire(name)
                .orElseThrow(() -> new OryxError("UNKNOWN_TOOL", "Unknown model tool name"));
    }
    public Optional<Entry> findWire(String name) { return entries.stream().filter(e -> e.wireName().equals(name)).findFirst(); }
    public static ToolNameMap empty() { return new ToolNameMap(List.of()); }
    public static String wireName(String internal) {
        if(internal==null||internal.isBlank())throw OryxError.configuration("tool name");
        if(internal.matches("[A-Za-z_][A-Za-z0-9_-]{0,63}"))return internal;
        try{return "t_"+HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(internal.getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0,48);}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
