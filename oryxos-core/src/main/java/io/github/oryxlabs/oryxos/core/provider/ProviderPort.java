package io.github.oryxlabs.oryxos.core.provider;

@FunctionalInterface
public interface ProviderPort {
    ProviderReply call(ProviderRequest request);
}
