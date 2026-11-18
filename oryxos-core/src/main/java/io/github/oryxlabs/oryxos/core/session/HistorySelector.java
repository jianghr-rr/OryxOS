package io.github.oryxlabs.oryxos.core.session;

import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.model.ChatMessage;
import java.util.*;

public final class HistorySelector {
    public SessionHistoryPort.History select(List<SessionMessage> messages,Set<UUID> completed,UUID current,int max) {
        if(max<1) throw new IllegalArgumentException("Positive turn limit required");
        var turns=new LinkedHashMap<UUID,List<ChatMessage>>();
        for(var message:messages.stream().sorted(Comparator.comparingLong(SessionMessage::seq)).toList()) turns.computeIfAbsent(message.turnId(),id->new ArrayList<>()).add(message.message());
        var selected=turns.keySet().stream().filter(id->!id.equals(current)&&completed.contains(id)).toList();
        var recent=new ArrayList<ChatMessage>();
        for(UUID id:selected.subList(Math.max(0,selected.size()-max),selected.size())) { paired(turns.get(id));recent.addAll(turns.get(id)); }
        var active=turns.getOrDefault(current,List.of()); paired(active);
        return new SessionHistoryPort.History(recent,active);
    }
    public static void paired(List<ChatMessage> messages) {
        var pending=new HashMap<String,String>(); var seen=new HashSet<String>();
        for(var m:messages) {
            if(m.role()!=ChatMessage.Role.TOOL && !pending.isEmpty()) throw protocol();
            for(var c:m.toolCalls()) {
                if(c.id()==null || !seen.add(c.id())) throw protocol();
                pending.put(c.id(),c.name());
            }
            if(m.role()==ChatMessage.Role.TOOL) {
                if(!pending.containsKey(m.toolCallId()) || !Objects.equals(pending.remove(m.toolCallId()),m.toolName())) throw protocol();
            }
        }
        if(!pending.isEmpty()) throw protocol();
    }
    private static OryxError protocol() { return new OryxError("HISTORY_INCOMPLETE","History has unmatched tool calls; cannot send it to a model"); }
}
