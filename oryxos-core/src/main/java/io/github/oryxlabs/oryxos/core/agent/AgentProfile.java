package io.github.oryxlabs.oryxos.core.agent;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.*;

public record AgentProfile(String name, String description, String providerName, String model,
                           List<String> tools, List<String> mcpServers, List<Path> bootstrap,
                           Map<String, Notification> notifications, List<Schedule> schedules,
                           int maxIterations, int maxHistoryTurns, Path definition,
                           String configRevision, String bodyRevision) {
    public record Notification(String urlReference, Map<String,Object> payload, Map<String,Object> acknowledgement, Map<String,String> headerReferences, boolean defaultTarget) {
        public Notification(String url,Map<String,Object> payload,Map<String,Object> ack){this(url,payload,ack,Map.of(),false);}
        public Notification {headerReferences=Map.copyOf(headerReferences);}
        @Override public String toString() { return "Notification[configuration withheld]"; }
    }
    public record Schedule(String id, String cron, ZoneId timezone, String message, boolean enabled, int maxDurationSeconds) {
        @Override public String toString() { return "Schedule[task withheld]"; }
    }
    public AgentProfile {
        tools = List.copyOf(tools); mcpServers = List.copyOf(mcpServers); bootstrap = List.copyOf(bootstrap);
        notifications = Map.copyOf(notifications); schedules = List.copyOf(schedules);
    }
    public Set<String> notifyChannelNames() { return notifications.keySet(); }
    @Override public String toString() { return "AgentProfile[name=" + name + ", configRevision=" + configRevision + "]"; }
}
