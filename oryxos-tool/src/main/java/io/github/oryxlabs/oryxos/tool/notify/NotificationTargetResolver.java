package io.github.oryxlabs.oryxos.tool.notify;

import io.github.oryxlabs.oryxos.core.agent.AgentProfile;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.security.SecretResolver;
import java.util.*;

public final class NotificationTargetResolver {
  public record Target(
      String url,
      Map<String, String> headers,
      Map<String, Object> payload,
      Map<String, Object> acknowledgement,
      boolean defaultTarget) {
    /** Converts this resolved webhook target into a channel-neutral {@link NotifyTarget}. */
    public NotifyTarget toNotifyTarget() {
      var config = new LinkedHashMap<String, Object>();
      config.put("url", url);
      config.put("headers", headers);
      config.put("payload", payload);
      config.put("acknowledgement", acknowledgement);
      return new NotifyTarget("webhook", config);
    }

    @Override
    public String toString() {
      return "Target[private configuration withheld]";
    }
  }

  private final Map<String, Map<String, Target>> targets;

  public NotificationTargetResolver(List<AgentProfile> agents, SecretResolver secrets) {
    var all = new LinkedHashMap<String, Map<String, Target>>();
    for (var agent : agents) {
      var local = new LinkedHashMap<String, Target>();
      agent
          .notifications()
          .forEach((name, n) -> {
            var headers = new LinkedHashMap<String, String>();
            n.headerReferences().forEach((k, v) -> headers.put(k, secrets.resolve(v)));
            local.put(
                name,
                new Target(
                    secrets.resolve(n.urlReference()),
                    Map.copyOf(headers),
                    n.payload(),
                    n.acknowledgement(),
                    n.defaultTarget()));
          });
      all.put(agent.name(), Map.copyOf(local));
    }
    targets = Map.copyOf(all);
  }

  public Target resolve(AgentProfile agent, Object name) {
    var allowed = targets.getOrDefault(agent.name(), Map.of());
    String target = name instanceof String s ? s : null;
    if (target == null) {
      var defaults = allowed.entrySet().stream().filter(e -> e.getValue().defaultTarget()).toList();
      if (defaults.size() == 1) target = defaults.getFirst().getKey();
      else if (allowed.size() == 1) target = allowed.keySet().iterator().next();
      else
        throw new OryxError(
            "INVALID_ARGUMENTS",
            "Specify targetName when multiple or no notification targets are configured");
    }
    if (!agent.notifications().containsKey(target) || !allowed.containsKey(target))
      throw new OryxError("TARGET_DENIED", "Notification target is not declared by this Agent");
    return allowed.get(target);
  }
}
