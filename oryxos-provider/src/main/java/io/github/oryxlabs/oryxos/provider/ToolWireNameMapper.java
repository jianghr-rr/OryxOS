package io.github.oryxlabs.oryxos.provider;

import io.github.oryxlabs.oryxos.core.model.ToolNameMap;
import java.security.*;
import java.util.*;

public final class ToolWireNameMapper {
  public record Name(String internalName, String serverId, String remoteName) {}

  private ToolWireNameMapper() {}

  public static ToolNameMap snapshot(List<Name> tools) {
    return new ToolNameMap(
        tools.stream()
            .map(t ->
                new ToolNameMap.Entry(
                    t.internalName(), wireName(t.internalName()), t.serverId(), t.remoteName()))
            .toList());
  }

  public static String wireName(String internal) {
    return ToolNameMap.wireName(internal);
  }
}
