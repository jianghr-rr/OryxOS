package io.github.oryxlabs.oryxos.provider;

import io.github.oryxlabs.oryxos.core.error.OryxError;
import org.springframework.web.client.ResourceAccessException;

final class ProviderErrorMapper {
    private ProviderErrorMapper() { }
    static OryxError status(int code) {
        return switch (code) {
            case 401,403 -> new OryxError("PROVIDER_AUTHENTICATION", "Provider rejected authentication or access; check the configured credential and account");
            case 429 -> new OryxError("PROVIDER_RATE_LIMIT", "Provider rate or quota limit; no automatic retry was made");
            default -> code >= 500 ? new OryxError("PROVIDER_UNAVAILABLE", "Provider service failed; no fallback was attempted")
                    : new OryxError("PROVIDER_REQUEST", "Provider rejected the request or redirected it; check endpoint, model and options");
        };
    }
    static OryxError failure(RuntimeException error) {
        if (error instanceof OryxError safe) return safe;
        // Inspect types, never copy messages/bodies/causes into user-visible exceptions.
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.net.http.HttpTimeoutException || cause instanceof java.net.SocketTimeoutException
                    || cause instanceof java.util.concurrent.TimeoutException)
                return new OryxError("PROVIDER_TIMEOUT", "Provider request exceeded the remaining time budget");
        }
        if (error instanceof ResourceAccessException) return new OryxError("PROVIDER_CONNECTION", "Provider connection failed; check the configured service and network");
        return protocol();
    }
    static OryxError protocol() { return new OryxError("PROVIDER_PROTOCOL", "Provider returned an unusable response or protocol message"); }
}
