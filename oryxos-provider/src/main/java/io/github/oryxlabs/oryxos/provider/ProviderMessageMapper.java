package io.github.oryxlabs.oryxos.provider;

import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.model.*;
import io.github.oryxlabs.oryxos.core.provider.*;
import org.springframework.ai.openai.api.OpenAiApi.*;
import java.util.*;

final class ProviderMessageMapper {
    private ProviderMessageMapper() { }
    static List<ChatCompletionMessage> messages(ProviderRequest request) {
        var messages = new ArrayList<ChatCompletionMessage>();
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) messages.add(new ChatCompletionMessage(request.systemPrompt(),ChatCompletionMessage.Role.SYSTEM));
        Map<String,ToolCall> pending = new LinkedHashMap<>(); var seen = new HashSet<String>();
        for (var message : request.orderedMessages()) {
            if (message.role() == null) throw ProviderErrorMapper.protocol();
            if (message.role() != ChatMessage.Role.TOOL && !pending.isEmpty()) throw ProviderErrorMapper.protocol();
            String reasoning = null;
            if (message.providerOpaque() != null) {
                var opaque = message.providerOpaque();
                if (!Objects.equals(opaque.provider(),request.providerName()) || !Objects.equals(opaque.model(),request.model()))
                    throw new OryxError("SESSION_MODEL_CHANGED", "Protocol continuation belongs to another provider/model; start a new session");
                if (message.role() != ChatMessage.Role.ASSISTANT) throw ProviderErrorMapper.protocol();
                reasoning = opaque.reasoningContent();
            }
            var calls = new ArrayList<ChatCompletionMessage.ToolCall>();
            for (ToolCall call : message.toolCalls()) {
                String mapped = request.toolNames().findWire(call.wireName()).map(ToolNameMap.Entry::internalName).orElse(null);
                if (message.role() != ChatMessage.Role.ASSISTANT || call.id() == null || call.id().isBlank() || !seen.add(call.id())
                        || !validWire(call.wireName()) || !Objects.equals(mapped,call.name())) throw ProviderErrorMapper.protocol();
                pending.put(call.id(),call);
                calls.add(new ChatCompletionMessage.ToolCall(call.id(),"function",new ChatCompletionMessage.ChatCompletionFunction(call.wireName(),call.argumentsJson())));
            }
            if (message.role() == ChatMessage.Role.TOOL) {
                var original = pending.remove(message.toolCallId());
                if (original == null || !Objects.equals(original.name(),message.toolName()) || message.content() == null) throw ProviderErrorMapper.protocol();
            } else if (message.toolCallId() != null) throw ProviderErrorMapper.protocol();
            messages.add(new ChatCompletionMessage(message.content(),ChatCompletionMessage.Role.valueOf(message.role().name()),null,
                    message.toolCallId(),calls.isEmpty() ? null : calls,null,null,null,reasoning));
        }
        if (!pending.isEmpty()) throw ProviderErrorMapper.protocol();
        return messages;
    }
    static ProviderReply reply(ProviderRequest request, ChatCompletion completion) {
        if (completion == null || completion.choices() == null || completion.choices().size() != 1 || completion.model() == null || completion.model().isBlank()) throw ProviderErrorMapper.protocol();
        var choice = completion.choices().getFirst();
        if (choice == null || choice.message() == null || choice.finishReason() == null) throw ProviderErrorMapper.protocol();
        var message = choice.message();
        if (message.role() != ChatCompletionMessage.Role.ASSISTANT) throw ProviderErrorMapper.protocol();
        var calls = new ArrayList<ToolCall>(); var ids = new HashSet<String>();
        if (message.toolCalls() != null) for (var call : message.toolCalls()) {
            if (call == null || call.id() == null || call.id().isBlank() || !ids.add(call.id()) || !"function".equals(call.type())
                    || call.function() == null || call.function().arguments() == null) throw ProviderErrorMapper.protocol();
            String wire = call.function().name();
            if (!validWire(wire)) throw ProviderErrorMapper.protocol();
            // Preserve unknown proposals for the engine's paired UNKNOWN_TOOL result. Never guess an internal name.
            String internal = request.toolNames().findWire(wire).map(ToolNameMap.Entry::internalName).orElse(null);
            calls.add(new ToolCall(call.id(),internal,wire,call.function().arguments()));
        }
        if (calls.isEmpty() && (message.content() == null || message.content().isBlank())) throw ProviderErrorMapper.protocol();
        if (choice.finishReason() == ChatCompletionFinishReason.TOOL_CALLS && calls.isEmpty()) throw ProviderErrorMapper.protocol();
        var opaque = message.reasoningContent() == null ? null : new ChatMessage.ProviderOpaque(request.providerName(),request.model(),message.reasoningContent());
        var assistant = new ChatMessage(ChatMessage.Role.ASSISTANT,message.content(),calls,null,null,opaque);
        var usage = completion.usage();
        if (usage != null && (negative(usage.promptTokens()) || negative(usage.completionTokens()) || negative(usage.totalTokens()))) throw ProviderErrorMapper.protocol();
        return new ProviderReply(assistant,choice.finishReason().name(),usage == null ? null :
                new ProviderReply.Usage(usage.promptTokens(),usage.completionTokens(),usage.totalTokens()),request.providerName(),completion.model());
    }
    private static boolean negative(Integer n) { return n != null && n < 0; }
    private static boolean validWire(String name) { return name != null && name.matches("[A-Za-z_][A-Za-z0-9_-]{0,63}"); }
}
