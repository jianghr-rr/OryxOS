package io.github.oryxlabs.oryxos.provider;

import io.github.oryxlabs.oryxos.core.config.ProviderDefinition;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.provider.ProviderPort;
import io.github.oryxlabs.oryxos.core.provider.ProviderReply;
import io.github.oryxlabs.oryxos.core.provider.ProviderRequest;
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

  /** Requests reach this adapter only after ProviderService validated them. */
  public ProviderReply call(ProviderRequest request) {
    if (!definition.name().equals(request.providerName())) {
      throw OryxError.configuration("provider routing mismatch");
    }
    long start = System.nanoTime();
    try {
      var messages = ProviderMessageMapper.messages(request);
      var wire = ProviderOptionsMapper.request(definition, request, messages);
      Duration remaining = request.remainingDuration().minusNanos(System.nanoTime() - start);
      var response =
          RuntimeTiming.call(
              "provider.transport",
              () -> clients.call(remaining, client -> client.chatCompletionEntity(wire).getBody()));
      if (expired(start, request.remainingDuration())) {
        throw timeout();
      }
      return RuntimeTiming.call(
          "provider.reply", () -> ProviderMessageMapper.reply(request, response));
    } catch (RuntimeException error) {
      // A timed-out streaming body can surface as JSON decoding EOF after Spring closes it.
      if (expired(start, request.remainingDuration())) {
        throw timeout();
      }
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
