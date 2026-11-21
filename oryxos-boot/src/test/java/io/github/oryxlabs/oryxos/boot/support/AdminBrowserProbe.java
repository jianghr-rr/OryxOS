package io.github.oryxlabs.oryxos.boot.support;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import io.github.oryxlabs.oryxos.boot.config.*;
import io.github.oryxlabs.oryxos.boot.runtime.BootRuntimeLauncher;
import io.github.oryxlabs.oryxos.core.execution.*;
import io.github.oryxlabs.oryxos.core.session.*;
import java.net.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.config.annotation.*;

/**
 * Local browser validation ONLY. Real backend/SQLite, explicitly simulated remote model/recipient.
 */
public final class AdminBrowserProbe {
  static final ObjectMapper JSON = new ObjectMapper();
  static Path root;
  static final List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();
  static final AtomicInteger effects = new AtomicInteger();
  static volatile boolean queryFailure;
  static volatile int queryDelay;
  static PersistentFixture fixture;
  static ServletWebServerApplicationContext context;

  static void sql(String statement) throws Exception {
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("oryxos.db"));
        var s = c.createStatement()) {
      s.execute(statement);
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  public static class BrowserConfiguration implements WebMvcConfigurer {
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
      registry
          .addResourceHandler("/__admin_test__/**")
          .addResourceLocations("classpath:/admin-browser/");
    }

    @Bean
    org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter>
        evidenceFilter() {
      var bean =
          new org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter>();
      bean.setFilter((req, res, chain) -> {
        var r = (jakarta.servlet.http.HttpServletRequest) req;
        if (r.getRequestURI().startsWith("/api/v1/")) {
          requests.add(
              Map.of(
                  "method",
                  r.getMethod(),
                  "path",
                  r.getRequestURI(),
                  "query",
                  Objects.toString(r.getQueryString(), ""),
                  "user",
                  Objects.toString(r.getHeader("X-Oryx-User-Id"), "")));
          if (r.getMethod().equals("GET") && r.getRequestURI().equals("/api/v1/profiles")) {
            try {
              Thread.sleep(queryDelay);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            if (queryFailure) {
              var h = (jakarta.servlet.http.HttpServletResponse) res;
              h.setStatus(503);
              h.setContentType("application/json");
              h.getWriter()
                  .write(
                      "{\"code\":\"LOCAL_TEST_QUERY_FAILURE\",\"message\":\"本地验证：受控查询失败\",\"data\":null}");
              return;
            }
          }
        }
        chain.doFilter(req, res);
      });
      return bean;
    }
  }

  @RestController
  @org.springframework.context.annotation.Profile("admin-browser-fixture")
  public static class FixtureControl {
    @GetMapping("/__fixture__/evidence")
    Object evidence() {
      return Map.of(
          "label",
          "LOCAL TEST ONLY — not real provider/notification acceptance",
          "models",
          fixture.models.get(),
          "effects",
          effects.get(),
          "requests",
          requests);
    }

    @PostMapping("/__fixture__/repair")
    Object repair() throws Exception {
      sql("DROP TRIGGER IF EXISTS browser_fail_result");
      sql("DROP TRIGGER IF EXISTS browser_fail_enabled");
      sql("DROP TRIGGER IF EXISTS browser_fail_recovery");
      return Map.of("repaired", true);
    }

    @PostMapping("/__fixture__/fail-write")
    Object failWrite(@RequestParam String target) throws Exception {
      String table =
          switch (target) {
            case "enabled" -> "scheduled_tasks";
            case "recovery" -> "incidents";
            default -> throw new IllegalArgumentException("Unknown local fault");
          };
      sql(
          "CREATE TRIGGER browser_fail_"
              + target
              + " BEFORE UPDATE ON "
              + table
              + " BEGIN SELECT RAISE(ABORT,'LOCAL_BROWSER_TEST'); END");
      return Map.of("localOnly", true, "target", target);
    }

    @PostMapping("/__fixture__/query")
    Object query(
        @RequestParam(defaultValue = "false") boolean fail,
        @RequestParam(defaultValue = "0") int delay) {
      queryFailure = fail;
      queryDelay = Math.min(2000, Math.max(0, delay));
      return Map.of("configured", true);
    }
  }

  static Object call(String id, String name, Object arguments) throws Exception {
    return Map.of(
        "role",
        "assistant",
        "content",
        "本地测试工具请求",
        "tool_calls",
        List.of(
            Map.of(
                "id",
                id,
                "type",
                "function",
                "function",
                Map.of("name", name, "arguments", JSON.writeValueAsString(arguments)))));
  }

  public static void main(String[] args) throws Exception {
    root = Path.of(args[0]);
    int port = Integer.parseInt(args[1]);
    if (Files.exists(root.resolve("workspace.json")))
      throw new IllegalArgumentException("Use a new isolated browser workspace");
    Files.createDirectories(root);
    fixture = new PersistentFixture(root);
    var recipient = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    recipient.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    recipient.createContext(
        "/effect",
        e -> {
          String body =
              new String(
                  e.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
          effects.incrementAndGet();
          if (body.contains("record-failure")) {
            try {
              sql(
                  "CREATE TRIGGER browser_fail_result BEFORE UPDATE ON tool_invocations WHEN"
                      + " NEW.status != 'STARTED' BEGIN SELECT RAISE(ABORT,'local browser"
                      + " fixture'); END");
            } catch (Exception x) {
              throw new RuntimeException(x);
            }
          }
          if (body.contains("unknown")) {
            e.close();
            return;
          }
          byte[] data = "local test receipt".getBytes();
          e.sendResponseHeaders(200, data.length);
          e.getResponseBody().write(data);
          e.close();
        });
    recipient.start();
    String url = "http://127.0.0.1:" + recipient.getAddress().getPort() + "/effect";
    var config = root.resolve("runtime.yaml");
    Files.writeString(
        config,
        Files.readString(config)
            .replace(
                URI.create(fixture.url()).getPort() + "}", recipient.getAddress().getPort() + "}"));
    var agent = root.resolve("agents/local/AGENT.md");
    Files.writeString(
        agent,
        Files.readString(agent)
            .replace("name: local\n", "name: local\ndescription: '本地验证服务 · 回环模型与接收器，不是真实模型或通知验收'\n")
            .replace(
                "tools: [http_post]",
                "tools: [http_post, save_memory]\n"
                    + "schedules:\n"
                    + "  - id: daily\n"
                    + "    cron: '0 0 0 1 1 *'\n"
                    + "    message: '本地调度验证'\n"
                    + "    enabled: false"));
    Files.createDirectories(root.resolve("agents/unavailable"));
    Files.writeString(
        root.resolve("agents/unavailable/AGENT.md"),
        "---\n"
            + "schema_version: 1\n"
            + "name: unavailable\n"
            + "provider: {name: missing, model: none}\n"
            + "---\n"
            + "本地无效配置测试\n");
    fixture.modelMessage =
        n -> {
          try {
            var messages = fixture.requests.getLast().path("messages");
            String user = "";
            int toolCount = 0;
            for (var m : messages) {
              if (m.path("role").asText().equals("user")) {
                user = m.path("content").asText();
                toolCount = 0;
              }
              if (m.path("role").asText().equals("tool")) toolCount++;
            }
            if (user.contains("纠正") && toolCount < 2)
              return call(
                  toolCount == 0 ? "bad-params" : "fixed-params",
                  "http_post",
                  toolCount == 0
                      ? Map.of("url", url)
                      : Map.of("url", url, "jsonBody", Map.of("mode", "corrected")));
            if (user.contains("未知") && toolCount == 0)
              return call(
                  "unknown-effect",
                  "http_post",
                  Map.of("url", url, "jsonBody", Map.of("mode", "unknown")));
            if (user.contains("记录故障") && toolCount == 0)
              return call(
                  "record-failure-effect",
                  "http_post",
                  Map.of("url", url, "jsonBody", Map.of("mode", "record-failure")));
            if (user.startsWith("seed-memory") && toolCount == 0)
              return call(
                  "memory-seed",
                  "save_memory",
                  Map.of(
                      "content",
                      "本地共享偏好：关注人工智能硬件 <img src=x onerror=alert(1)>",
                      "scope",
                      user.endsWith("core") ? "CORE" : "ARCHIVAL"));
            return Map.of(
                "role",
                "assistant",
                "content",
                "本地验证回复：" + user + "\n<img src=x onerror=alert(1)>（应按纯文本显示）");
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        };
    context =
        (ServletWebServerApplicationContext)
            new BootRuntimeLauncher(Map.of(), BrowserConfiguration.class)
                .start(
                    fixture.workspace,
                    Map.of(
                        "server.port",
                        port,
                        "oryxos.scheduling.enabled",
                        true,
                        "spring.profiles.active",
                        "admin-browser-fixture"));
    var service = context.getBean(AgentRuntimeConfiguration.RuntimeAgents.class).require();
    var sessions = context.getBean(SessionManager.class);
    var profile = context.getBean(ToolConfiguration.CheckedAgents.class).catalog().require("local");
    // Seed only disposable local data, through the actual manager/engine and persistent ports.
    UUID session;
    try (var lease =
        sessions.acquire(null, "http", "browser-review", profile, Session.Kind.CONVERSATION)) {
      session = lease.session().id();
    }
    for (int i = 1; i <= 12; i++)
      service.process(
          ProcessRequest.manual("http", "browser-review", "local", session, "历史验证 " + i));
    for (int i = 0; i < 23; i++)
      try (var lease =
          sessions.acquire(
              null,
              i % 2 == 0 ? "cli" : "http",
              "reader-" + i,
              profile,
              Session.Kind.CONVERSATION)) {}
    service.process(
        ProcessRequest.manual("http", "browser-review", "local", null, "seed-memory core"));
    service.process(
        ProcessRequest.manual("http", "browser-review", "local", null, "seed-memory archival"));
    Files.writeString(
        root.resolve("browser-server.json"),
        JSON.writeValueAsString(
            Map.of(
                "url",
                "http://127.0.0.1:" + context.getWebServer().getPort(),
                "label",
                "LOCAL TEST ONLY",
                "historySession",
                session.toString())));
    System.out.println(
        "LOCAL TEST ONLY: http://127.0.0.1:"
            + context.getWebServer().getPort()
            + "/admin/\nWorkspace: "
            + root);
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(() -> {
              context.close();
              recipient.stop(0);
              fixture.close();
            }));
    new CountDownLatch(1).await();
  }
}
