package io.github.oryxlabs.oryxos.boot;

import static org.junit.jupiter.api.Assertions.*;

import io.github.oryxlabs.oryxos.boot.config.StorageLifecycle;
import io.github.oryxlabs.oryxos.core.runtime.*;
import io.github.oryxlabs.oryxos.core.workspace.*;
import io.github.oryxlabs.oryxos.storage.lifecycle.StorageResourceFactory;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Tag("f0-foundation")
class DegradedStartupTest {
  @Test
  void diagnosticHttpContextSurvivesBadDatabaseAndConfiguration() throws Exception {
    var workspace = WorkspaceResolver.resolve(root.toString());
    new WorkspaceInitializer()
        .initialize(
            workspace, java.util.Map.of("runtime.yaml", "schema_version: 1\nproviders: {}\n"));
    Files.writeString(workspace.database(), "corrupt-db");
    var launcher = new io.github.oryxlabs.oryxos.boot.runtime.BootRuntimeLauncher();
    try (var context = launcher.start(workspace, java.util.Map.of("server.port", 0))) {
      assertTrue(context.isActive());
      assertFalse(context.getBean(StorageLifecycle.class).availability().available());
      assertEquals(RuntimeGate.State.PAUSED_RECORDING, context.getBean(RuntimeGate.class).state());
      int port =
          ((org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext)
                  context)
              .getWebServer()
              .getPort();
      try (var client = java.net.http.HttpClient.newHttpClient()) {
        var request =
            java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create("http://127.0.0.1:" + port + "/api/v1/health"))
                .build();
        var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(503, response.statusCode(), "HTTP remains live while execution is paused");
        var body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body());
        assertTrue(body.path("data").path("live").asBoolean());
        assertFalse(body.path("data").path("ready").asBoolean());
        assertEquals("PAUSED_RECORDING", body.path("data").path("state").asText());
      }
      var error =
          assertThrows(
              io.github.oryxlabs.oryxos.core.error.OryxError.class,
              () -> launcher.start(workspace, java.util.Map.of("server.port", 0)));
      assertEquals("WORKSPACE_BUSY", error.code());
    }
    assertEquals("corrupt-db", Files.readString(workspace.database()));
    Files.writeString(workspace.root().resolve("runtime.yaml"), "providers: [CANARY: [");
    try (var context = launcher.start(workspace, java.util.Map.of("server.port", 0))) {
      assertTrue(context.isActive());
      assertEquals(
          "CONFIGURATION_ERROR",
          context
              .getBean(
                  io.github.oryxlabs.oryxos.boot.config.RuntimeConfiguration.LoadedRuntime.class)
              .code());
    }
    assertFalse(WorkspaceProcessLock.isRunning(workspace));
  }

  @TempDir Path root;

  @Test
  void failedStorageCanBeRebuiltWithoutReopeningGateOrLeakingManagers() throws Exception {
    var workspace = WorkspaceResolver.resolve(root.toString());
    Files.createDirectory(
        workspace.database()); // deterministic unwritable database, even under privileged users
    var gate = new RuntimeGate();
    try (var lock = WorkspaceProcessLock.acquire(workspace);
        var lifecycle = new StorageLifecycle(new StorageResourceFactory(), workspace, lock, gate)) {
      assertDoesNotThrow(lifecycle::rebuild);
      assertFalse(lifecycle.availability().available());
      assertEquals(RuntimeGate.State.PAUSED_RECORDING, gate.state());
      Files.delete(workspace.database());
      lifecycle.rebuild();
      assertTrue(lifecycle.availability().available());
      assertEquals(
          RuntimeGate.State.PAUSED_RECORDING,
          gate.state(),
          "resource repair is not human-confirmed runtime recovery");
      var old = lifecycle.resources();
      var manager = old.openEntityManager();
      lifecycle.rebuild();
      assertFalse(old.isOpen());
      assertFalse(manager.isOpen());
      assertTrue(lifecycle.resources().isOpen());
    }
    assertFalse(WorkspaceProcessLock.isRunning(workspace));
  }
}
