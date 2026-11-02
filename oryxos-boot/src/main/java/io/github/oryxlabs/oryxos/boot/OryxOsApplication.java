package io.github.oryxlabs.oryxos.boot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Assembles the OryxOS modules into a single executable application.
 */
@SpringBootApplication(scanBasePackages = "io.github.oryxlabs.oryxos")
public class OryxOsApplication {

    public static void main(String[] args) {
        SpringApplication.run(OryxOsApplication.class, args);
    }
}
