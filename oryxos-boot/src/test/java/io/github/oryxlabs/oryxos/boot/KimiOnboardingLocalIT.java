package io.github.oryxlabs.oryxos.boot;

import static io.github.oryxlabs.oryxos.boot.support.RuntimeProcess.JSON;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.github.oryxlabs.oryxos.boot.support.KernelFixture;
import io.github.oryxlabs.oryxos.boot.support.RuntimeProcess;
import io.github.oryxlabs.oryxos.core.config.*;
import io.github.oryxlabs.oryxos.core.security.Redactor;
import io.github.oryxlabs.oryxos.core.workspace.WorkspaceHandle;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/**
 * Kimi onboarding through the real CLI entry: shipped templates, documented uncommenting, and a
 * loopback stand-in for api.moonshot.cn. Never contacts a real model service.
 */
@Tag("kimi-onboarding")
class KimiOnboardingLocalIT {
  static final Path REPO = KernelFixture.REPO;
  static final Path INIT_TEMPLATE =
      REPO.resolve("oryxos-cli/src/main/resources/templates/runtime.yaml");
  static final Path USER_EXAMPLE = REPO.resolve("examples/runtime.example.yaml");
  static final Path REAL_TEMPLATE =
      REPO.resolve("tools/dev/examples/real-provider-kimi/runtime.yaml");
  static final String CANARY = "CANARY-kimi-fixture";
  static final String DEEPSEEK_CANARY = "CANARY-deepseek-fixture";
  static final String BLOCK_START = "#   kimi:";

  @TempDir Path root;
  final List<FakeModel> models = new ArrayList<>();

  @AfterEach
  void stop() {
    models.forEach(m -> m.server.stop(0));
  }

  @Test
  void initTemplateListsNoKimiProvider() throws Exception {
    init();
    var items = providers();
    for (var item : items) assertNotEquals("kimi", item.path("name").asText());
    assertEquals(0, items.size(), "init keeps providers empty: " + items);
  }

  @Test
  void commentedKimiBlocksAreIdentical() throws Exception {
    var expected = kimiDefinition(Files.readString(REAL_TEMPLATE));
    for (var source : List.of(INIT_TEMPLATE, USER_EXAMPLE)) {
      var block = commentedBlock(Files.readString(source), source);
      for (String line : block) {
        assertFalse(line.contains("temperature"), source + " must not configure temperature");
        assertFalse(line.contains("thinking"), source + " must not configure thinking");
      }
      var actual = kimiDefinition("schema_version: 1\nproviders:\n" + String.join("\n", block));
      assertTrue(actual.available(), source + ": " + actual);
      assertEquals(expected.protocol(), actual.protocol(), source.toString());
      assertEquals(expected.endpoint(), actual.endpoint(), source.toString());
      assertEquals(expected.completionsPath(), actual.completionsPath(), source.toString());
      assertEquals(expected.apiKeyReference(), actual.apiKeyReference(), source.toString());
      assertEquals(
          expected.connectTimeoutSeconds(), actual.connectTimeoutSeconds(), source.toString());
      assertEquals(expected.options(), actual.options(), source.toString());
    }
    assertEquals("https://api.moonshot.cn", expected.endpoint().toString());
    assertEquals("/v1/chat/completions", expected.completionsPath());
    assertEquals(Map.of("reasoning_effort", "low"), expected.options());
  }

  @Test
  void uncommentedKimiChatReachesKimiPathOnly() throws Exception {
    var kimi = fakeModel();
    init();
    enableKimi(kimi, false);
    secrets(true, false);
    var data = chat("default", "你好");
    assertEquals("SUCCEEDED", data.path("status").asText(), data.toString());
    assertEquals(1, kimi.requests.size());
    var request = kimi.requests.getFirst();
    assertEquals("POST", request.method);
    assertEquals("/v1/chat/completions", request.path);
    assertEquals("Bearer " + CANARY, request.authorization);
    assertEquals("kimi-k3.1-pro", request.body.path("model").asText());
    assertEquals("low", request.body.path("reasoning_effort").asText());
    assertFalse(request.body.has("temperature"), request.body.toString());
    assertFalse(request.body.has("thinking"), request.body.toString());
  }

  @Test
  void twoProvidersNeverCrossRoute() throws Exception {
    var kimi = fakeModel();
    var deepseek = fakeModel();
    init();
    enableKimi(kimi, true);
    addDeepseekAgent(deepseek);
    secrets(true, true);
    var kimiRun = chat("default", "第一条");
    var deepseekRun = chat("ds-agent", "第二条");
    assertEquals("SUCCEEDED", kimiRun.path("status").asText(), kimiRun.toString());
    assertEquals("SUCCEEDED", deepseekRun.path("status").asText(), deepseekRun.toString());
    assertEquals(List.of("kimi-k3.1-pro"), kimi.models());
    assertEquals(List.of("ds-local-model"), deepseek.models());
    assertEquals(List.of("/v1/chat/completions"), kimi.paths());
    assertEquals(List.of("/chat/completions"), deepseek.paths());
    assertEquals(List.of("kimi"), auditedProviders(kimiRun.path("id").asText()));
    assertEquals(List.of("deepseek"), auditedProviders(deepseekRun.path("id").asText()));
  }

  @Test
  void misconfiguredKimiIsolatedAndRedacted() throws Exception {
    var kimi = fakeModel();
    var deepseek = fakeModel();
    init();
    enableKimi(kimi, true);
    addDeepseekAgent(deepseek);
    secrets(true, true);
    assertEquals("SUCCEEDED", chat("default", "带密钥").path("status").asText());
    secrets(false, true);
    var kimiItem = providers().findValues("name").stream().anyMatch(n -> n.asText().equals("kimi"));
    assertTrue(kimiItem);
    for (var item : providers()) {
      if (item.path("name").asText().equals("kimi"))
        assertFalse(item.path("configured").asBoolean(), item.toString());
      if (item.path("name").asText().equals("deepseek"))
        assertTrue(item.path("configured").asBoolean(), item.toString());
    }
    assertEquals("SUCCEEDED", chat("ds-agent", "另一个 Agent").path("status").asText());
    assertEquals(1, kimi.requests.size(), "unavailable kimi must not reach the network");
    try (var files = Files.walk(root)) {
      for (var file : files.filter(Files::isRegularFile).toList()) {
        if (file.getFileName().toString().equals("secrets.local.yaml")) continue;
        String text = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
        assertFalse(text.contains(CANARY), "Secret leaked into " + root.relativize(file));
        assertFalse(text.contains(DEEPSEEK_CANARY), "Secret leaked into " + root.relativize(file));
      }
    }
  }

  private void init() throws Exception {
    var result = RuntimeProcess.cli(root, "", "init", "--json");
    assertEquals(0, result.exit(), result.stdout() + result.stderr());
  }

  private JsonNode providers() throws Exception {
    var result = RuntimeProcess.cli(root, "", "provider", "list", "--json");
    assertEquals(0, result.exit(), result.stdout() + result.stderr());
    return JSON.readTree(result.stdout()).path("data").path("items");
  }

  private JsonNode chat(String agent, String content) throws Exception {
    var result =
        RuntimeProcess.cli(
            root,
            content + "\n/quit\n",
            "chat",
            "--profile",
            agent,
            "--user",
            "kimi-onboarding",
            "--json");
    assertEquals(0, result.exit(), result.stdout() + result.stderr());
    return JSON.readTree(result.stdout().lines().reduce((a, b) -> b).orElseThrow()).path("data");
  }

  /** Follows the documented steps: `providers: {}` → `providers:` and strip each `# `. */
  private void enableKimi(FakeModel kimi, boolean keepProvidersOpen) throws Exception {
    var runtime = root.resolve("runtime.yaml");
    String text = Files.readString(runtime);
    assertTrue(text.contains("providers: {}"), "init template must keep providers empty");
    var lines = new ArrayList<String>();
    boolean inBlock = false;
    for (String line : text.replace("providers: {}", "providers:").lines().toList()) {
      if (line.equals(BLOCK_START)) inBlock = true;
      else if (inBlock && !line.startsWith("#     ")) inBlock = false;
      lines.add(inBlock ? line.substring(2) : line);
    }
    String enabled =
        String.join("\n", lines).replace("https://api.moonshot.cn", kimi.origin()) + "\n";
    assertTrue(enabled.contains("  kimi:"), "Kimi block missing from init template");
    Files.writeString(runtime, enabled);
    var agent = root.resolve("agents/default/AGENT.md");
    Files.writeString(
        agent,
        Files.readString(agent)
            .replace("name: configure-provider", "name: kimi")
            .replace("model: configure-model", "model: kimi-k3.1-pro"));
  }

  private void addDeepseekAgent(FakeModel deepseek) throws Exception {
    var runtime = root.resolve("runtime.yaml");
    String deepseekBlock =
        "  deepseek:\n"
            + "    protocol: openai_chat_completions\n"
            + "    endpoint: "
            + deepseek.origin()
            + "\n    completions_path: /chat/completions\n"
            + "    api_key: \"${DEEPSEEK_API_KEY}\"\n";
    Files.writeString(
        runtime, Files.readString(runtime).replace("providers:\n", "providers:\n" + deepseekBlock));
    var dir = Files.createDirectories(root.resolve("agents/ds-agent"));
    Files.writeString(
        dir.resolve("AGENT.md"),
        "---\nschema_version: 1\nname: ds-agent\nprovider: {name: deepseek, model: ds-local-model}\n"
            + "tools: []\n---\n简短回答。\n");
  }

  private void secrets(boolean kimi, boolean deepseek) throws Exception {
    var file = root.resolve("secrets.local.yaml");
    var text = new StringBuilder();
    if (kimi) text.append("KIMI_API_KEY: ").append(CANARY).append('\n');
    if (deepseek) text.append("DEEPSEEK_API_KEY: ").append(DEEPSEEK_CANARY).append('\n');
    if (text.isEmpty()) text.append("{}\n");
    Files.writeString(file, text);
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
  }

  private List<String> auditedProviders(String executionId) throws Exception {
    var providers = new ArrayList<String>();
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("oryxos.db"));
        var s =
            c.prepareStatement(
                "SELECT provider, duration_ms FROM llm_calls WHERE execution_id=? ORDER BY iteration")) {
      s.setString(1, executionId);
      try (var rows = s.executeQuery()) {
        while (rows.next()) {
          providers.add(rows.getString(1));
          assertTrue(rows.getLong(2) >= 0, "audited duration");
        }
      }
    }
    return providers;
  }

  private static List<String> commentedBlock(String text, Path source) {
    var block = new ArrayList<String>();
    boolean inBlock = false;
    for (String line : text.lines().toList()) {
      if (line.equals(BLOCK_START)) inBlock = true;
      else if (inBlock && !line.startsWith("#     ")) break;
      if (inBlock) block.add(line.substring(2));
    }
    assertFalse(block.isEmpty(), "Missing commented Kimi block in " + source);
    return block;
  }

  private ProviderDefinition kimiDefinition(String runtimeYaml) throws Exception {
    var workspace = Files.createTempDirectory(root, "parse");
    Files.writeString(workspace.resolve("runtime.yaml"), runtimeYaml);
    var config =
        new RuntimeConfigLoader(Map.of("KIMI_API_KEY", CANARY), new Redactor())
            .load(new WorkspaceHandle(workspace));
    var kimi = config.providers().get("kimi");
    assertNotNull(kimi, "kimi provider not parsed");
    return kimi;
  }

  private FakeModel fakeModel() throws Exception {
    var model = new FakeModel();
    models.add(model);
    return model;
  }

  record Captured(String method, String path, String authorization, JsonNode body) {}

  static final class FakeModel {
    final HttpServer server;
    final List<Captured> requests = new CopyOnWriteArrayList<>();

    FakeModel() throws Exception {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/",
          e -> {
            try {
              var body = JSON.readTree(e.getRequestBody());
              requests.add(
                  new Captured(
                      e.getRequestMethod(),
                      e.getRequestURI().getPath(),
                      e.getRequestHeaders().getFirst("Authorization"),
                      body));
              byte[] bytes =
                  JSON.writeValueAsBytes(
                      Map.of(
                          "id",
                          "local-" + requests.size(),
                          "model",
                          body.path("model").asText(),
                          "choices",
                          List.of(
                              Map.of(
                                  "index",
                                  0,
                                  "finish_reason",
                                  "stop",
                                  "message",
                                  Map.of("role", "assistant", "content", "LOCAL fixture reply"))),
                          "usage",
                          Map.of("prompt_tokens", 7, "completion_tokens", 3, "total_tokens", 10)));
              e.getResponseHeaders().set("Content-Type", "application/json");
              e.sendResponseHeaders(200, bytes.length);
              e.getResponseBody().write(bytes);
            } finally {
              e.close();
            }
          });
      server.start();
    }

    String origin() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    List<String> models() {
      return requests.stream().map(r -> r.body.path("model").asText()).toList();
    }

    List<String> paths() {
      return requests.stream().map(Captured::path).toList();
    }
  }
}
