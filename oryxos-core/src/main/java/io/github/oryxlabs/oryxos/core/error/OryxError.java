package io.github.oryxlabs.oryxos.core.error;

/** Only controlled messages belong here; never attach raw external/config exceptions. */
public final class OryxError extends RuntimeException {
    private final String code;
    public OryxError(String code, String safeMessage) { super(safeMessage); this.code = code; }
    public String code() { return code; }
    public static OryxError configuration(String field) {
        return new OryxError("CONFIGURATION_ERROR", "Invalid or missing configuration: " + field);
    }
}
