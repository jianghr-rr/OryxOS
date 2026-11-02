package io.github.oryxlabs.oryxos.boot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OryxOsApplicationTest {

    @Test
    void applicationStartsWithAllModules() {
        // Context initialization starts the assembled application and embedded server.
    }
}
