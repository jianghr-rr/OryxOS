package io.github.oryxlabs.oryxos.provider;

import io.github.oryxlabs.oryxos.core.config.ProviderDefinition;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.model.ChatMessage;
import io.github.oryxlabs.oryxos.core.provider.*;
import io.github.oryxlabs.oryxos.core.runtime.RuntimeTiming;
import java.time.Duration;

/** Exactly one low-level call. There is no tool executor, ChatModel, retry template or fallback. */
final class SpringAiProvider implements ProviderPort, AutoCloseable {
  private final ProviderDefinition definition;
  private final OpenAiClientFactory clients;

  SpringAiProvider(ProviderDefinition definition, String credential) {
    this.definition = definition;
    this.clients = new OpenAiClientFactory(definition, credential);
  }

  static void validate(ProviderRequest request) {
    if (request.model() == null || request.model().isBlank() || request.iteration() < 1)
      throw OryxError.configuration("provider request model/iteration");
    var users =
        request.orderedMessages().stream().filter(m -> m.role() == ChatMessage.Role.USER).toList();
    if (users.isEmpty() || users.getLast().content() == null || users.getLast().content().isBlank())
      throw new OryxError("INVALID_TASK", "Provide non-blank task content before calling a model");
    if (request.remainingDuration().isNegative() || request.remainingDuration().isZero())
      throw new OryxError("PROVIDER_TIMEOUT", "No time remains for a model call");
  }

  public ProviderReply call(ProviderRequest request) {
    validate(request);
    if (!definition.name().equals(request.providerName()))
      throw OryxError.configuration("provider routing mismatch");
    long start = System.nanoTime();
    try {
      var messages = ProviderMessageMapper.messages(request);
      var wire = ProviderOptionsMapper.request(definition, request, messages);
      Duration remaining = request.remainingDuration().minusNanos(System.nanoTime() - start);
      {
        var response =
            RuntimeTiming.call(
                "provider.transport",
                () ->
                    clients.call(
                        remaining,
                        client ->
                            request.dispatcher() == null
                                ? client.chatCompletionEntity(wire).getBody()
                                : request
                                    .dispatcher()
                                    .call(() -> client.chatCompletionEntity(wire).getBody())));
        if (expired(start, request.remainingDuration())) throw timeout();
        return RuntimeTiming.call(
            "provider.reply", () -> ProviderMessageMapper.reply(request, response));
      }
    } catch (RuntimeException error) {
      // A timed-out streaming body can surface as JSON decoding EOF after Spring closes it.
      if (expired(start, request.remainingDuration())) throw timeout();
      throw ProviderErrorMapper.failure(error);
    }
  }

  public void close() {
    clients.close();
  }

  private static boolean expired(long start, Duration budget) {
    return Duration.ofNanos(System.nanoTime() - start).compareTo(budget) >= 0;
  }

  private static OryxError timeout() {
    return new OryxError("PROVIDER_TIMEOUT", "Provider request exceeded the remaining time budget");
  }
}
