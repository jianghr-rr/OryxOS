package io.github.oryxlabs.oryxos.memory;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import io.github.oryxlabs.oryxos.core.config.*;
import io.github.oryxlabs.oryxos.core.memory.*;
import io.github.oryxlabs.oryxos.core.security.*;
import io.github.oryxlabs.oryxos.core.workspace.*;
import io.github.oryxlabs.oryxos.memory.backend.Mem0MemoryStore;
import io.github.oryxlabs.oryxos.memory.mem0.*;
import io.github.oryxlabs.oryxos.storage.lifecycle.StorageResourceFactory;
import io.github.oryxlabs.oryxos.storage.memory.JpaMemoryRecordStore;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;

/**
 * Explicit real integration. Restart is restricted to the isolated, pinned Compose service. No
 * skip/mock path.
 */
@Tag("real-integration")
@Tag("real-mem0")
class RealMem0IT {
  final ObjectMapper json = new ObjectMapper();
  Path workspacePath;
  Mem0Config config;
  String key, container, image, llm, embedding;
  final Redactor redactor = new Redactor();

  @BeforeEach
  void prerequisites() throws Exception {
    assertEquals("real", System.getProperty("oryxos.tests.mode"));
    String root = System.getProperty("oryxos.real.workspace");
    assertTrue(
        root != null && !root.isBlank(),
        "Missing -Doryxos.real.workspace=/absolute/private/mem0-workspace; see tools/mem0/README.md");
    workspacePath = Path.of(root);
    assertTrue(workspacePath.isAbsolute());
    var workspace = new WorkspaceHandle(workspacePath);
    var runtime = new RuntimeConfigLoader(System.getenv(), redactor).load(workspace);
    assertEquals("mem0", runtime.memoryBackend());
    config = Mem0Config.from(runtime.memoryRules());
    assertTrue(
        Set.of("127.0.0.1", "localhost").contains(config.endpoint().getHost()),
        "Restart acceptance uses the isolated local Compose service");
    key =
        new SecretResolver(System.getenv(), workspacePath.resolve("secrets.local.yaml"), redactor)
            .resolve(config.apiKeyReference());
    container = required("oryxos.real.mem0.container");
    assertTrue(container.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}"));
    image = required("oryxos.real.mem0.imageId");
    assertTrue(image.matches("sha256:[0-9a-f]{64}"));
    llm = required("oryxos.real.mem0.llm");
    embedding = required("oryxos.real.mem0.embedding");
    assertEquals(
        "true",
        System.getProperty("oryxos.real.mem0.allowRestart"),
        "Explicitly opt in to restarting ONLY this isolated test container");
    var inspected =
        json.readTree(
            docker(
                "inspect",
                "--format",
                "{\"image\":{{json .Image}},\"project\":{{json (index .Config.Labels \"com.docker.compose.project\")}},\"service\":{{json (index .Config.Labels \"com.docker.compose.service\")}},\"revision\":{{json (index .Config.Labels \"org.opencontainers.image.revision\")}}}",
                container));
    assertEquals("oryxos-personal-mem0", inspected.path("project").asText());
    assertEquals("mem0", inspected.path("service").asText());
    assertEquals("94c3fe9f238f3dbf29c9ce98643bd71eb13077cd", inspected.path("revision").asText());
    assertEquals(image, inspected.path("image").asText());
    verifyModels();
  }

  @Test
  void realSemanticIsolationCoreOfflineAndRestartPersistence() throws Exception {
    var corpus = json.readTree(getClass().getResourceAsStream("/memory/semantic-cases.json"));
    var evidence = new LinkedHashMap<String, Object>();
    evidence.put("kind", "REAL_MEM0");
    evidence.put("time", Instant.now().toString());
    evidence.put("imageId", image);
    evidence.put("llm", llm);
    evidence.put("embedding", embedding);
    evidence.put("threshold", config.searchThreshold());
    evidence.put("semanticCoverage", "NOT_PROVEN");
    evidence.put("status", "FAILED");
    try (var f = new MemoryFixture(workspacePath)) {
      var factory = new StorageResourceFactory();
      UUID source;
      try (var storage = factory.open(f.workspace, f.lock);
          var backend =
              new Mem0MemoryStore(
                  f.id, new JpaMemoryRecordStore(() -> storage), new Mem0Client(config, key))) {
        var receipt =
            backend.save(
                corpus.path("save").asText(), MemoryEntry.Scope.ARCHIVAL, f.context("real-writer"));
        assertTrue(receipt.confirmed());
        source = receipt.sourceEntryId();
        var matches = backend.recall(corpus.path("positive").asText(), f.context("other-agent"));
        assertTrue(matches.stream().anyMatch(e -> e.id().equals(source)));
        assertTrue(
            matches.stream()
                .allMatch(e -> e.rawContent().equals(corpus.path("expectedFact").asText())),
            "Returned sources must contain no invented facts");
        assertTrue(
            backend.recall(corpus.path("negative").asText(), f.context("other-agent")).isEmpty());
        // Preserve actual remote projections for required human comparison; raw-source equality is
        // not semantic proof.
        var saved =
            backend.load(MemoryEntry.Scope.ARCHIVAL, f.context("evidence-reader")).stream()
                .filter(e -> e.id().equals(source))
                .findFirst()
                .orElseThrow();
        var projections = new ArrayList<Map<String, String>>();
        try (var client = new Mem0Client(config, key)) {
          for (String remoteId : saved.remoteIds()) {
            var projection = client.get(remoteId, f.context("evidence-reader"));
            projections.add(
                Map.of(
                    "id",
                    remoteId,
                    "memory",
                    io.github.oryxlabs.oryxos.memory.backend.MemoryTextCodec.bounded(
                        redactor.redact(projection.path("memory").asText()), 4000)));
          }
        }
        evidence.put("savedFact", corpus.path("save").asText());
        evidence.put("remoteProjectionsForHumanReview", projections);
        // A fresh workspace must not inherit this source, even when the semantic query is
        // identical.
        try (var isolated = new MemoryFixture(workspacePath.resolve("isolation-probe"));
            var other =
                new Mem0MemoryStore(
                    isolated.id,
                    new JpaMemoryRecordStore(() -> storage),
                    new Mem0Client(config, key))) {
          assertTrue(
              other
                  .recall(corpus.path("positive").asText(), isolated.context("isolated-agent"))
                  .isEmpty());
        }
        String before = docker("inspect", "--format", "{{.State.StartedAt}}", container).trim();
        docker("stop", container);
        try {
          String core = "完整CORE😀".repeat(1000);
          assertTrue(
              backend.save(core, MemoryEntry.Scope.CORE, f.context("offline-writer")).confirmed());
          assertTrue(
              backend.load(MemoryEntry.Scope.CORE, f.context("offline-reader")).stream()
                  .anyMatch(e -> e.rawContent().equals(core)));
        } finally {
          docker("start", container);
        }
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        boolean ready = false;
        while (System.nanoTime() < end) {
          try {
            verifyModels();
            ready = true;
            break;
          } catch (Exception | AssertionError e) {
            Thread.sleep(1000);
          }
        }
        assertTrue(ready, "Mem0 did not become ready after restart");
        String after = docker("inspect", "--format", "{{.State.StartedAt}}", container).trim();
        assertNotEquals(before, after);
        evidence.put("startedBefore", before);
        evidence.put("startedAfter", after);
      }
      try (var reopened = factory.open(f.workspace, f.lock);
          var backend =
              new Mem0MemoryStore(
                  f.id, new JpaMemoryRecordStore(() -> reopened), new Mem0Client(config, key))) {
        assertTrue(
            backend.load(MemoryEntry.Scope.ARCHIVAL, f.context("reopened-reader")).stream()
                .anyMatch(e -> e.id().equals(source)));
        assertTrue(
            backend.recall(corpus.path("positive").asText(), f.context("reopened-reader")).stream()
                .anyMatch(e -> e.id().equals(source)));
      }
      evidence.put("sourceEntryId", source.toString());
      evidence.put("workspaceId", f.id.toString());
      evidence.put("status", "SUCCEEDED");
      evidence.put(
          "humanSemanticReview",
          "PENDING: compare remote projections with saved fact before closing T057");
    } finally {
      Path directory =
          Path.of(System.getProperty("oryxos.test.evidenceDir"))
              .resolve("us3-real")
              .resolve(System.getProperty("oryxos.test.runId"));
      Files.createDirectories(directory);
      json.writerWithDefaultPrettyPrinter()
          .writeValue(directory.resolve("mem0.json").toFile(), evidence);
    }
  }

  private String required(String name) {
    String value = System.getProperty(name);
    assertTrue(value != null && !value.isBlank(), "Missing -D" + name);
    return value;
  }

  private void verifyModels() throws Exception {
    try (var http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()) {
      var response =
          http.send(
              HttpRequest.newBuilder(config.endpoint().resolve("/configure"))
                  .timeout(Duration.ofSeconds(10))
                  .header("X-API-Key", key)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      var value = json.readTree(response.body());
      assertEquals(llm, value.path("llm").path("config").path("model").asText());
      assertEquals(embedding, value.path("embedder").path("config").path("model").asText());
    }
  }

  private String docker(String... args) throws Exception {
    var command = new ArrayList<String>();
    command.add(System.getProperty("oryxos.real.docker", "/usr/local/bin/docker"));
    command.addAll(List.of(args));
    var builder = new ProcessBuilder(command).redirectErrorStream(true);
    builder
        .environment()
        .put(
            "DOCKER_CONFIG",
            Path.of(System.getProperty("oryxos.test.evidenceDir"))
                .getParent()
                .resolve("docker")
                .toString());
    Path socket = Path.of(System.getenv("HOME"), ".docker/run/docker.sock");
    builder
        .environment()
        .put(
            "DOCKER_HOST",
            System.getenv()
                .getOrDefault(
                    "ORYXOS_DOCKER_HOST",
                    Files.exists(socket) ? "unix://" + socket : "unix:///var/run/docker.sock"));
    var process = builder.start();
    if (!process.waitFor(45, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      fail("Docker test operation timed out");
    }
    String result =
        new String(
            process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    assertEquals(
        0,
        process.exitValue(),
        "Isolated Docker operation failed; check the daemon and test container");
    return result;
  }
}
