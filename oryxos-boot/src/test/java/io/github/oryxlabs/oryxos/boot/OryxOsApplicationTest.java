package io.github.oryxlabs.oryxos.boot;

import io.github.oryxlabs.oryxos.boot.config.StorageLifecycle;
import io.github.oryxlabs.oryxos.core.runtime.RuntimeGate;
import io.github.oryxlabs.oryxos.core.workspace.*;
import java.nio.file.*;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;

@Tag("f0-foundation")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OryxOsApplicationTest {
    static final WorkspaceHandle WORKSPACE = temporaryWorkspace();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("oryxos.root", () -> WORKSPACE.root().toString());
    }
    static WorkspaceHandle temporaryWorkspace() {
        try {
            var workspace = WorkspaceResolver.resolve(Files.createTempDirectory("oryxos-boot-").toString());
            new WorkspaceInitializer().initialize(workspace, Map.of("runtime.yaml", "schema_version: 1\nproviders: {}\n"));
            return workspace;
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
    @Autowired StorageLifecycle storage;
    @Autowired RuntimeGate gate;
    @LocalServerPort int port;
    @Test void applicationStartsWithAllModules() {
        assertTrue(port > 0);
        assertTrue(storage.availability().available());
        assertEquals(RuntimeGate.State.READY, gate.state(), "US5 production ports must open a clean runtime");
        assertTrue(Files.exists(WORKSPACE.database()));
    }
}
