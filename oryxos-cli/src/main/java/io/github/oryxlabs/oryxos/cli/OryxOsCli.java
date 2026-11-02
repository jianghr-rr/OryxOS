package io.github.oryxlabs.oryxos.cli;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Lightweight CLI entry point that prints the Maven project version.
 */
public final class OryxOsCli {

    private OryxOsCli() {
    }

    public static void main(String[] args) {
        System.out.println("OryxOS " + version());
    }

    private static String version() {
        try (InputStream input = OryxOsCli.class.getResourceAsStream("version.properties")) {
            if (input == null) {
                throw new IllegalStateException("Missing CLI version metadata; build with Maven first.");
            }
            Properties properties = new Properties();
            properties.load(input);
            String version = properties.getProperty("version");
            if (version == null || version.isBlank() || version.contains("@")) {
                throw new IllegalStateException("Invalid CLI version metadata; build with Maven first.");
            }
            return version;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read CLI version metadata.", exception);
        }
    }
}
