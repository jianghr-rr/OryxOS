package io.github.oryxlabs.oryxos.boot.support;

import static io.github.oryxlabs.oryxos.boot.support.RuntimeProcess.JSON;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.*;
import io.github.oryxlabs.oryxos.core.model.ToolNameMap;
import io.github.oryxlabs.oryxos.core.workspace.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Controlled remote services only. All production engine, adapters, stores and entry points are
 * real.
 */
public final class KernelFixture implements AutoCloseable {
  public static final Path REPO =
      Path.of(System.getProperty("oryxos.test.evidenceDir")).getParent().getParent();
  public static final List<String> AGENTS =
      List.of("daily-weather", "daily-tech-digest", "github-daily");
  public static final String PREFERENCE = "关注 AI 和芯片";
  public final Path root;
  public final HttpServer server;
  private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
  public final Map<String, AtomicInteger> sources = new ConcurrentHashMap<>();
  public final List<JsonNode> requests = new CopyOnWriteArrayList<>();
  public final List<String> notifications = new CopyOnWriteArrayList<>();
  public final List<Throwable> failures = new CopyOnWriteArrayList<>();
  public final List<String> sourceQueries = new CopyOnWriteArrayList<>();
  public volatile String sourceFailure = "", notificationMode = "ok";
  public volatile boolean emptyNews, incompleteGithub, failMemorySave;

  public KernelFixture(Path root) throws Exception {
    this.root = root;
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(threads);
    for (String key : List.of("weather", "news", "github")) sources.put(key, new AtomicInteger());
    server.createContext(
        "/",
        e -> {
          try {
            String path = e.getRequestURI().getPath();
            if (path.equals("/chat/completions")) {
              var request = JSON.readTree(e.getRequestBody());
              requests.add(request);
              var message = model(request);
              reply(
                  e,
                  200,
                  Map.of(
                      "id",
                      "local-" + requests.size(),
                      "model",
                      "LOCAL-controlled-model",
                      "choices",
                      List.of(
                          Map.of(
                              "index",
                              0,
                              "finish_reason",
                              message.containsKey("tool_calls") ? "tool_calls" : "stop",
                              "message",
                              message)),
                      "usage",
                      Map.of("prompt_tokens", 10, "completion_tokens", 5, "total_tokens", 15)));
            } else if (path.equals("/notify")) {
              notifications.add(JSON.readTree(e.getRequestBody()).path("text").asText());
              // Accepted locally, then drop the response: only this transport ambiguity means
              // UNKNOWN.
              if (!notificationMode.equals("unknown"))
                reply(e, 200, Map.of("errcode", notificationMode.equals("reject") ? 1 : 0));
            } else {
              String kind =
                  path.startsWith("/github/")
                      ? "github"
                      : path.startsWith("/weather/") ? "weather" : "news";
              sources.get(kind).incrementAndGet();
              sourceQueries.add(e.getRequestURI().toString());
              if (sourceFailure.equals(kind))
                reply(e, 400, Map.of("error", "LOCAL source unavailable"));
              else if (kind.equals("weather"))
                reply(
                    e,
                    200,
                    Map.of(
                        "city",
                        "Shanghai",
                        "date",
                        today(),
                        "temperature_c",
                        12,
                        "condition",
                        "rain",
                        "source",
                        url("/weather/v1/daily")));
              else if (kind.equals("news"))
                reply(
                    e,
                    200,
                    Map.of(
                        "date",
                        today(),
                        "items",
                        emptyNews
                            ? List.of()
                            : List.of(
                                Map.of(
                                    "title",
                                    "LOCAL AI 芯片样本",
                                    "url",
                                    "https://news.example.invalid/ai-chip",
                                    "published_at",
                                    today()))));
              else
                reply(
                    e,
                    200,
                    Map.of(
                        "total_count",
                        1,
                        "incomplete_results",
                        incompleteGithub,
                        "items",
                        List.of(
                            Map.of(
                                "full_name",
                                "fixture/ai-chip",
                                "html_url",
                                "https://github.com/fixture/ai-chip",
                                "stargazers_count",
                                42,
                                "description",
                                "LOCAL AI chip project",
                                "created_at",
                                today() + "T00:00:00Z",
                                "pushed_at",
                                today() + "T00:00:00Z"))));
            }
          } catch (Throwable problem) {
            failures.add(problem);
            reply(e, 500, Map.of("error", "LOCAL fixture assertion failed"));
          } finally {
            e.close();
          }
        });
    // Use the shipped examples, replacing only operator configuration and local test schedule
    // cadence.
    new WorkspaceInitializer().initialize(new WorkspaceHandle(root), Map.of());
    try (var files = Files.walk(REPO.resolve("examples/agents"))) {
      for (var file : files.toList()) {
        var target =
            root.resolve("agents").resolve(REPO.resolve("examples/agents").relativize(file));
        if (Files.isDirectory(file)) Files.createDirectories(target);
        else Files.copy(file, target);
      }
    }
    String runtime =
        Files.readString(REPO.resolve("examples/runtime.example.yaml"))
            .replace("https://model.example.invalid", url(""))
            .replace(
                "{scheme: https, host: weather.example.invalid, port: 443}",
                "{scheme: http, host: 127.0.0.1, port: " + server.getAddress().getPort() + "}")
            .replace(
                "{scheme: https, host: notify.example.invalid, port: 443}",
                "{scheme: http, host: 127.0.0.1, port: " + server.getAddress().getPort() + "}");
    Files.writeString(root.resolve("runtime.yaml"), runtime);
    for (String agent : AGENTS) {
      var path = root.resolve("agents/" + agent + "/AGENT.md");
      Files.writeString(
          path,
          Files.readString(path)
              .replace("CONFIGURE_MODEL", "LOCAL-controlled-model")
              .replace("/ABSOLUTE/WORKSPACE", root.toString())
              .replace("https://weather.example.invalid", url("/weather"))
              .replace("https://api.github.com", url("/github"))
              .replaceAll("cron: '[^']+'", "cron: '*/10 * * * * *'"));
    }
    Files.copy(
        REPO.resolve("oryxos-boot/src/test/resources/kernel/news-mcp.py"),
        root.resolve("news-mcp.py"));
    Files.writeString(
        root.resolve("mcp_servers.yaml"),
        "schema_version: 1\n"
            + "servers:\n"
            + "  news:\n"
            + "    transport: stdio\n"
            + "    command: /usr/bin/python3\n"
            + "    args: ['"
            + root.resolve("news-mcp.py")
            + "', '"
            + url("/news")
            + "', '"
            + root.resolve("mcp-calls.jsonl")
            + "']\n    initialization_timeout_seconds: 5\n    request_timeout_seconds: 5\n");
    Files.writeString(
        root.resolve("secrets.local.yaml"),
        "EXAMPLE_MODEL_KEY: LOCAL_SECRET_CANARY\nTEST_WEBHOOK_URL: '" + url("/notify") + "'\n");
    Files.setPosixFilePermissions(
        root.resolve("secrets.local.yaml"),
        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    server.start();
  }

  public static String today() {
    return LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
  }

  public String url(String path) {
    return "http://127.0.0.1:" + server.getAddress().getPort() + path;
  }

  private static void reply(HttpExchange e, int status, Object value) throws IOException {
    byte[] bytes = JSON.writeValueAsBytes(value);
    e.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    e.sendResponseHeaders(status, bytes.length);
    e.getResponseBody().write(bytes);
  }

  private Map<String, Object> tool(String name, Map<String, Object> args) throws Exception {
    return Map.of(
        "role",
        "assistant",
        "content",
        "LOCAL controlled tool proposal",
        "tool_calls",
        List.of(
            Map.of(
                "id",
                "call-" + UUID.randomUUID(),
                "type",
                "function",
                "function",
                Map.of(
                    "name",
                    ToolNameMap.wireName(name),
                    "arguments",
                    JSON.writeValueAsString(args)))));
  }

  private Map<String, Object> answer(String content) {
    return Map.of("role", "assistant", "content", content);
  }

  private Map<String, Object> model(JsonNode request) throws Exception {
    var messages = request.path("messages");
    String system = messages.get(0).path("content").asText();
    assertTrue(system.contains(today()), "Workspace date must reach actual provider request");
    assertTrue(system.contains("Asia/Shanghai"));
    int lastUser = 0;
    for (int i = 0; i < messages.size(); i++)
      if (messages.get(i).path("role").asText().equals("user")) lastUser = i;
    var results = new ArrayList<String>();
    for (int i = lastUser + 1; i < messages.size(); i++)
      if (messages.get(i).path("role").asText().equals("tool"))
        results.add(messages.get(i).path("content").asText());
    if (messages.get(lastUser).path("content").asText().contains("请记住")) {
      if (results.isEmpty()) {
        if (failMemorySave) {
          Files.createDirectories(root.resolve("memory"));
          Files.setPosixFilePermissions(
              root.resolve("memory"),
              java.nio.file.attribute.PosixFilePermissions.fromString("r-x------"));
        }
        return tool("save_memory", Map.of("content", PREFERENCE, "scope", "CORE"));
      }
      return answer(
          results.getLast().startsWith("outcome=SUCCESS")
              ? "偏好已持久保存：" + PREFERENCE
              : "偏好保存失败，未声称记住");
    }
    if (!results.isEmpty() && !results.getLast().startsWith("outcome=SUCCESS"))
      return answer("资料或动作失败，请核查工具记录；未生成虚构日报，未重复发送。");
    boolean weather = system.contains("# daily-weather"),
        news = system.contains("# daily-tech-digest");
    if (!weather)
      assertTrue(
          system.contains(PREFERENCE), "Another session/agent must see the persisted preference");
    if (results.isEmpty()) {
      assertFalse(system.contains("每条包括事实标题"), "Reference is on-demand, not bootstrap");
      if (weather) {
        var matcher =
            java.util.regex.Pattern.compile(
                    "https?://[^\\s`]+/v1/daily\\?city=Shanghai&date=YYYY-MM-DD")
                .matcher(system);
        assertTrue(matcher.find(), "Source must come from the actual Agent instructions");
        return tool("http_get", Map.of("url", matcher.group().replace("YYYY-MM-DD", today())));
      }
      if (news)
        return tool(
            "read_file", Map.of("path", "agents/daily-tech-digest/skills/digest-format.md"));
      var matcher = java.util.regex.Pattern.compile("--api-base ([^\\s`]+)").matcher(system);
      assertTrue(matcher.find());
      var script =
          java.util.regex.Pattern.compile(
                  "`([^`]+/agents/github-daily/scripts/github_trending.py)`")
              .matcher(system);
      assertTrue(script.find());
      return tool(
          "shell",
          Map.of(
              "command",
              "/usr/bin/python3",
              "args",
              List.of(
                  script.group(1),
                  "--api-base",
                  matcher.group(1),
                  "--date",
                  today(),
                  "--timezone",
                  "Asia/Shanghai")));
    }
    if (news && results.size() == 1) {
      assertTrue(results.getFirst().contains("每条包括事实标题"));
      return tool("mcp.news.search", Map.of("query", PREFERENCE, "date", today()));
    }
    int sourceSteps = news ? 2 : 1;
    if (results.size() > sourceSteps) return answer("LOCAL 测试渠道已确认接收；请查看执行记录。");
    String source = results.getLast();
    String content;
    if (weather) {
      assertTrue(source.contains("\"temperature_c\":12"));
      content = "LOCAL Shanghai " + today() + "：12°C 雨，建议外套和雨具。来源 " + url("/weather/v1/daily");
    } else if (news) {
      JsonNode data = JSON.readTree(source.substring(source.indexOf('{')));
      if (data.path("items").isEmpty()) return answer("今天无可核查新闻，未发送虚构日报。");
      content =
          "LOCAL 科技日报 / "
              + PREFERENCE
              + " / "
              + data.path("items").get(0).path("title").asText()
              + " "
              + data.path("items").get(0).path("url").asText();
    } else {
      JsonNode data =
          JSON.readTree(source.substring(source.indexOf('{'), source.lastIndexOf('}') + 1));
      assertEquals(today(), data.path("date").asText());
      content = "LOCAL GitHub 日报 / " + PREFERENCE;
      for (String section : List.of("today", "month", "ai")) {
        var value = data.path("sections").path(section);
        assertFalse(value.path("source").asText().isBlank());
        var item = value.path("items").get(0);
        content +=
            "\n"
                + section
                + ": "
                + item.path("name").asText()
                + " "
                + item.path("url").asText()
                + " stars="
                + item.path("stars").asInt();
      }
      if (system.contains("本次加注：版本二")) content += " 版本二";
    }
    return tool("notify", Map.of("targetName", "test", "content", content));
  }

  public void assertHealthy() {
    assertTrue(failures.isEmpty(), failures.toString());
  }

  public void close() {
    server.stop(0);
    threads.shutdownNow();
  }
}
