package io.github.oryxlabs.oryxos.boot.support;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** A separate JVM, optionally the built distribution; never embeds a test runtime or store. */
public final class RuntimeProcess implements AutoCloseable {
  public static final ObjectMapper JSON = new ObjectMapper();
  private final Path root, out, err;
  private final Process process;
  public final String base;

  public RuntimeProcess(Path root, String mode) throws Exception {
    this.root = root;
    out = root.resolve("process-" + UUID.randomUUID() + ".out");
    err = Path.of(out + ".err");
    process = builder(root, out, err, mode).start();
    process.getOutputStream().close();
    String url = null;
    try {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
      while (System.nanoTime() < deadline) {
        assertTrue(process.isAlive(), Files.readString(err));
        var m =
            java.util.regex.Pattern.compile("Tomcat started on port (\\d+)")
                .matcher(Files.readString(err));
        if (m.find()) {
          url = "http://127.0.0.1:" + m.group(1);
          break;
        }
        Thread.sleep(30);
      }
      assertNotNull(url, "HTTP process never started: " + Files.readString(err));
      base = url;
    } catch (Throwable e) {
      close();
      throw e;
    }
  }

  public long pid() {
    return process.pid();
  }

  static ProcessBuilder builder(Path root, Path out, Path err, String... args) {
    var cmd =
        new ArrayList<String>(
            List.of(
                Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-Duser.home=" + System.getProperty("user.home"),
                "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"),
                "-Duser.timezone=UTC",
                "-Dserver.port=0"));
    String jar = System.getProperty("oryxos.acceptance.jar");
    if (jar != null) {
      assertTrue(Files.isRegularFile(Path.of(jar)), "Missing acceptance JAR");
      cmd.addAll(List.of("-jar", jar));
    } else
      cmd.addAll(
          List.of(
              "-cp",
              System.getProperty("java.class.path"),
              "io.github.oryxlabs.oryxos.boot.OryxOsApplication"));
    cmd.addAll(List.of("--root", root.toString()));
    cmd.addAll(List.of(args));
    var b = new ProcessBuilder(cmd).redirectOutput(out.toFile()).redirectError(err.toFile());
    b.environment().clear();
    b.environment().put("PATH", "/usr/bin:/bin");
    b.environment().put("TZ", "UTC");
    return b;
  }

  public record Result(int exit, String stdout, String stderr) {}

  public static Result cli(Path root, String input, String... args) throws Exception {
    var out = root.resolve("command-" + UUID.randomUUID() + ".out");
    var err = Path.of(out + ".err");
    var p = builder(root, out, err, args).start();
    try {
      try (var in = p.getOutputStream()) {
        in.write(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      }
      assertTrue(p.waitFor(80, TimeUnit.SECONDS), "CLI timed out");
      return new Result(p.exitValue(), Files.readString(out), Files.readString(err));
    } finally {
      if (p.isAlive()) {
        p.destroyForcibly();
        p.waitFor();
      }
    }
  }

  public JsonNode call(String method, String path, Object body, int status) throws Exception {
    return call(method, path, body, status, "reviewer");
  }

  public JsonNode call(String method, String path, Object body, int status, String user)
      throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      var b =
          HttpRequest.newBuilder(
                  URI.create(
                      base
                          + (path.startsWith("/api/") || path.startsWith("/v3/")
                              ? path
                              : "/api/v1" + path)))
              .timeout(Duration.ofSeconds(75))
              .header("Content-Type", "application/json");
      if (user != null) b.header("X-Oryx-User-Id", user);
      var r =
          client.send(
              b.method(
                      method,
                      body == null
                          ? HttpRequest.BodyPublishers.noBody()
                          : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(status, r.statusCode(), method + " " + path + " " + r.body());
      assertFalse(r.body().contains("LOCAL_SECRET_CANARY"));
      assertFalse(r.body().contains("PRIVATE_OPAQUE_CANARY"));
      var value = JSON.readTree(r.body());
      evidence(
          "HTTP",
          Map.of(
              "pid",
              process.pid(),
              "method",
              method,
              "path",
              path,
              "status",
              r.statusCode(),
              "response",
              value));
      return value;
    }
  }

  public JsonNode data(String method, String path, Object body, int status) throws Exception {
    return call(method, path, body, status).path("data");
  }

  public static synchronized void evidence(String scenario, Map<String, Object> data)
      throws Exception {
    var folder = Path.of(System.getProperty("oryxos.test.evidenceDir"));
    Files.createDirectories(folder);
    var record = new LinkedHashMap<String, Object>();
    record.put("scenario", scenario);
    record.put("localOnly", true);
    record.put("runtime", System.getProperty("oryxos.acceptance.jar", "separate JVM classpath"));
    record.put("data", data);
    Files.writeString(
        folder.resolve("process-acceptance-" + System.getProperty("oryxos.test.runId") + ".jsonl"),
        JSON.writeValueAsString(record) + "\n",
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND);
  }

  public void close() throws Exception {
    process.destroy();
    if (!process.waitFor(15, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      process.waitFor();
    }
    assertFalse(Files.readString(out).contains("LOCAL_SECRET_CANARY"));
    assertFalse(Files.readString(err).contains("LOCAL_SECRET_CANARY"));
  }
}
