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
        if (args.length > 1 || (args.length == 1 && !"--version".equals(args[0]))) {
            System.err.println("This thin JAR only supports --version. Use the boot JAR for full commands.");
            System.exit(2);
            return;
        }
        System.out.println("OryxOS " + version());
    }

    public static String version() {
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
