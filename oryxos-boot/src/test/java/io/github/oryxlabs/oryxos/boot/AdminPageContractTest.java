package io.github.oryxlabs.oryxos.boot;

import static org.junit.jupiter.api.Assertions.*;

import io.github.oryxlabs.oryxos.boot.runtime.BootRuntimeLauncher;
import io.github.oryxlabs.oryxos.boot.support.PersistentFixture;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

@Tag("us5-admin")
class AdminPageContractTest {
  @TempDir Path root;

  HttpResponse<String> get(String base, String path) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void adminEntryAndModulesAreServedByTheRealBackend() throws Exception {
    try (var f = new PersistentFixture(root);
        var c =
            (ServletWebServerApplicationContext)
                new BootRuntimeLauncher().start(f.workspace, Map.of("server.port", 0))) {
      String base = "http://127.0.0.1:" + c.getWebServer().getPort();
      var page = get(base, "/admin/");
      assertEquals(200, page.statusCode());
      assertTrue(page.headers().firstValue("content-type").orElse("").contains("text/html"));
      assertTrue(page.body().contains("lang=\"zh-CN\""));
      assertTrue(page.body().contains("type=\"module\""));
      assertTrue(page.body().contains("运行控制台"));
      for (String file :
          List.of("app.js", "ui.js", "api.js", "read-views.js", "control-views.js", "styles.css")) {
        var r = get(base, "/admin/" + file);
        assertEquals(200, r.statusCode(), file);
        assertFalse(r.body().contains("<html"), file);
        assertTrue(
            r.headers()
                .firstValue("content-type")
                .orElse("")
                .contains(file.endsWith("css") ? "text/css" : "javascript"),
            file);
      }
      assertEquals(302, get(base, "/admin").statusCode());
      assertEquals(404, get(base, "/__fixture__/evidence").statusCode());
      assertEquals(200, get(base, "/api/v1/profiles").statusCode());
      assertEquals(0, f.models.get());
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void pausedWorkspaceStillServesTheConsoleAndRealIncident() throws Exception {
    try (var f = new PersistentFixture(root)) {
      Files.createDirectories(root.resolve("runtime/runs"));
      Files.writeString(root.resolve("runtime/runs/abandoned.json"), "{}");
      try (var c =
          (ServletWebServerApplicationContext)
              new BootRuntimeLauncher().start(f.workspace, Map.of("server.port", 0))) {
        String base = "http://127.0.0.1:" + c.getWebServer().getPort();
        assertEquals(200, get(base, "/admin/").statusCode());
        assertTrue(get(base, "/api/v1/runtime/state").body().contains("PAUSED_RECORDING"));
        assertEquals(503, get(base, "/api/v1/health").statusCode());
        assertEquals(0, f.models.get());
      }
    }
  }
}
