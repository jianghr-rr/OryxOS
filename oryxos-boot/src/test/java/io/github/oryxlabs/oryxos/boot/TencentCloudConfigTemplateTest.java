package io.github.oryxlabs.oryxos.boot;

import static org.junit.jupiter.api.Assertions.*;

import io.github.oryxlabs.oryxos.core.agent.AgentCatalog;
import io.github.oryxlabs.oryxos.core.agent.AgentProfile;
import io.github.oryxlabs.oryxos.core.config.RuntimeConfig;
import io.github.oryxlabs.oryxos.core.config.RuntimeConfigLoader;
import io.github.oryxlabs.oryxos.core.config.SafeYaml;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.security.Redactor;
import io.github.oryxlabs.oryxos.core.security.SecretResolver;
import io.github.oryxlabs.oryxos.core.workspace.WorkspaceHandle;
import io.github.oryxlabs.oryxos.tool.ToolRegistry;
import io.github.oryxlabs.oryxos.tool.http.ControlledHttpClient;
import io.github.oryxlabs.oryxos.tool.http.HttpGetTool;
import io.github.oryxlabs.oryxos.tool.http.HttpTargetPolicy;
import io.github.oryxlabs.oryxos.tool.notify.NotificationTargetResolver;
import io.github.oryxlabs.oryxos.tool.notify.NotifyTool;
import io.github.oryxlabs.oryxos.tool.notify.WebhookNotifyAdapter;
import io.github.oryxlabs.oryxos.tool.policy.ToolPolicy;
import io.github.oryxlabs.oryxos.tool.policy.ToolRetryPolicy;
import io.github.oryxlabs.oryxos.tool.validation.ToolArgumentValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.scheduling.support.CronExpression;

class TencentCloudConfigTemplateTest {
  private static final String STUB_DEEPSEEK_KEY = "stub-deepseek-canary-value";
  private static final String STUB_FEISHU_URL =
      "https://open.feishu.cn/open-apis/bot/v2/hook/stub-hook-canary";

  @TempDir Path workspaceRoot;

  private Path locateConfigDir() {
    Path current = Path.of("").toAbsolutePath().normalize();
    for (int i = 0; i < 4 && current != null; i++) {
      Path candidate = current.resolve("deploy/tencent-cloud/config");
      if (Files.isDirectory(candidate)) {
        return candidate;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("Cannot locate deploy/tencent-cloud/config from " + Path.of("").toAbsolutePath());
  }

  private void stageTemplates(String secretsContent) throws Exception {
    Path configDir = locateConfigDir();
    Files.createDirectories(workspaceRoot.resolve("output"));
    Files.createDirectories(workspaceRoot.resolve("memory"));
    Files.copy(
        configDir.resolve("runtime.yaml.template"),
        workspaceRoot.resolve("runtime.yaml"));

    Path agentsSource = configDir.resolve("agents");
    try (Stream<Path> dirs = Files.list(agentsSource)) {
      for (Path dir : dirs.filter(Files::isDirectory).toList()) {
        Path targetDir = workspaceRoot.resolve("agents").resolve(dir.getFileName());
        Files.createDirectories(targetDir);
        Files.copy(dir.resolve("AGENT.md"), targetDir.resolve("AGENT.md"));
      }
    }

    Path secrets = workspaceRoot.resolve("secrets.local.yaml");
    Files.writeString(secrets, secretsContent);
    Files.setPosixFilePermissions(secrets, PosixFilePermissions.fromString("rw-------"));
  }

  @Test
  void cloudTemplatesLoadCleanlyAndMatchManagedAgentContract() throws Exception {
    Path configDir = locateConfigDir();
    assertFalse(Files.exists(configDir.resolve("agents/default")), "default template must not exist");

    stageTemplates(
        "DEEPSEEK_API_KEY: \""
            + STUB_DEEPSEEK_KEY
            + "\"\nFEISHU_WEBHOOK_URL: \""
            + STUB_FEISHU_URL
            + "\"\n");

    Redactor redactor = new Redactor();
    WorkspaceHandle workspace = new WorkspaceHandle(workspaceRoot);
    RuntimeConfig config = new RuntimeConfigLoader(Map.of(), redactor).load(workspace);

    assertEquals("Asia/Shanghai", config.timezone().getId());
    assertEquals("md", config.memoryBackend());
    assertNotNull(config.providers().get("deepseek"));
    assertTrue(config.providers().get("deepseek").available(), "deepseek provider should load cleanly");
    assertEquals("${DEEPSEEK_API_KEY}", config.providers().get("deepseek").apiKeyReference());

    AgentCatalog rawCatalog = AgentCatalog.load(workspace, config, Map.of(), redactor);
    Map<String, Object> httpRules = SafeYaml.map(config.toolRules().get("http"), "tools.http");

    try (ControlledHttpClient http =
        new ControlledHttpClient(HttpTargetPolicy.from(httpRules), 5, 20, 65536)) {
      List<AgentProfile> loadedProfiles =
          rawCatalog.entries().stream()
              .filter(AgentCatalog.Entry::available)
              .map(AgentCatalog.Entry::profile)
              .toList();
      NotificationTargetResolver resolver =
          new NotificationTargetResolver(
              loadedProfiles,
              new SecretResolver(
                  Map.of(), workspace.root().resolve("secrets.local.yaml"), redactor));
      ToolRetryPolicy retry = new ToolRetryPolicy();
      ToolRegistry registry =
          new ToolRegistry(
              List.of(
                  retry.apply(new HttpGetTool(http)),
                  retry.apply(new NotifyTool(resolver, new WebhookNotifyAdapter(http)))),
              new ToolArgumentValidator(),
              new ToolPolicy());

      AgentCatalog validated =
          rawCatalog
              .validateTools(registry, server -> false)
              .validateSchedules(CronExpression::isValidExpression);

      assertEquals(2, validated.entries().size());
      assertEquals(
          List.of("feishu-notify", "weather"),
          validated.entries().stream().map(AgentCatalog.Entry::name).toList());
      assertTrue(validated.entries().stream().allMatch(AgentCatalog.Entry::available));

      AgentProfile weather = validated.require("weather");
      assertEquals("deepseek", weather.providerName());
      assertEquals("deepseek-chat", weather.model());
      assertEquals(List.of("http_get", "notify"), weather.tools());
      assertTrue(weather.schedules().isEmpty());
      assertEquals(Set.of("feishu"), weather.notifyChannelNames());
      assertTrue(weather.notifications().get("feishu").defaultTarget());
      assertEquals("json_equals", weather.notifications().get("feishu").acknowledgement().get("mode"));
      assertEquals("/code", weather.notifications().get("feishu").acknowledgement().get("pointer"));
      assertEquals(0, weather.notifications().get("feishu").acknowledgement().get("expected"));

      AgentProfile notify = validated.require("feishu-notify");
      assertEquals("deepseek", notify.providerName());
      assertEquals("deepseek-chat", notify.model());
      assertEquals(List.of("notify"), notify.tools());
      assertTrue(notify.schedules().isEmpty());
      assertEquals(Set.of("feishu"), notify.notifyChannelNames());
      assertTrue(notify.notifications().get("feishu").defaultTarget());

      NotificationTargetResolver.Target defaultTarget = resolver.resolve(weather, null);
      NotificationTargetResolver.Target explicitTarget = resolver.resolve(notify, "feishu");
      assertEquals(STUB_FEISHU_URL, defaultTarget.url());
      assertEquals(STUB_FEISHU_URL, explicitTarget.url());
      assertFalse(defaultTarget.toString().contains("stub-hook-canary"));
      assertFalse(validated.entries().toString().contains(STUB_DEEPSEEK_KEY));
      assertFalse(validated.entries().toString().contains("stub-hook-canary"));
    }
  }

  @Test
  void httpAllowlistPermitsOnlyRequiredHttpsTargets() throws Exception {
    stageTemplates(
        "DEEPSEEK_API_KEY: \""
            + STUB_DEEPSEEK_KEY
            + "\"\nFEISHU_WEBHOOK_URL: \""
            + STUB_FEISHU_URL
            + "\"\n");

    Redactor redactor = new Redactor();
    RuntimeConfig config =
        new RuntimeConfigLoader(Map.of(), redactor).load(new WorkspaceHandle(workspaceRoot));
    HttpTargetPolicy policy =
        HttpTargetPolicy.from(SafeYaml.map(config.toolRules().get("http"), "tools.http"));

    assertDoesNotThrow(() -> policy.authorize("https://open.feishu.cn/open-apis/bot/v2/hook/test"));
    assertDoesNotThrow(
        () ->
            policy.authorize(
                "https://api.open-meteo.com/v1/forecast?latitude=31.2304&longitude=121.4737"));
    assertDoesNotThrow(
        () -> policy.authorize("https://geocoding-api.open-meteo.com/v1/search?name=Shanghai"));

    assertThrows(OryxError.class, () -> policy.authorize("https://example.com/"));
    assertThrows(
        OryxError.class, () -> policy.authorize("http://open.feishu.cn/open-apis/bot/v2/hook/test"));
    assertThrows(
        OryxError.class, () -> policy.authorize("https://api.open-meteo.com:8443/v1/forecast"));
  }

  @Test
  void missingWebhookSecretDegradesAgentsWithoutLeakingSecrets() throws Exception {
    stageTemplates("DEEPSEEK_API_KEY: \"" + STUB_DEEPSEEK_KEY + "\"\n");

    Redactor redactor = new Redactor();
    WorkspaceHandle workspace = new WorkspaceHandle(workspaceRoot);
    RuntimeConfig config = new RuntimeConfigLoader(Map.of(), redactor).load(workspace);
    assertTrue(config.providers().get("deepseek").available());

    AgentCatalog catalog = AgentCatalog.load(workspace, config, Map.of(), redactor);
    assertEquals(2, catalog.entries().size());
    assertTrue(catalog.entries().stream().noneMatch(AgentCatalog.Entry::available));
    assertTrue(catalog.entries().stream().allMatch(e -> e.error() != null));
    assertFalse(catalog.entries().toString().contains(STUB_DEEPSEEK_KEY));
  }
}
