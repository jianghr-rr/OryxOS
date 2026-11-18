package io.github.oryxlabs.oryxos.core.agent;

import io.github.oryxlabs.oryxos.core.config.*;
import io.github.oryxlabs.oryxos.core.security.Redactor;
import io.github.oryxlabs.oryxos.core.workspace.WorkspaceHandle;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@Tag("us1-contract")
class AgentCatalogContractTest {
    @TempDir Path root;
    final Redactor redactor = new Redactor();
    static final Map<String,String> ENV = Map.of("GOOD_KEY", "fixture-secret");
    @BeforeEach void runtime() throws Exception {
        Files.writeString(root.resolve("runtime.yaml"), """
                schema_version: 1
                providers:
                  good:
                    protocol: openai_chat_completions
                    endpoint: http://127.0.0.1:1
                    completions_path: /chat/completions
                    api_key: "${GOOD_KEY}"
                  missing:
                    protocol: openai_chat_completions
                    endpoint: http://127.0.0.1:1
                    completions_path: /chat/completions
                    api_key: "${MISSING_KEY}"
                """);
    }
    Path agent(String name, String extra) throws Exception {
        Path file = root.resolve("agents/" + name + "/AGENT.md"); Files.createDirectories(file.getParent());
        Files.writeString(file, "---\nschema_version: 1\nname: " + name + "\nprovider: {name: good, model: configured-model}\n" + extra + "---\ninitial instructions\n");
        return file;
    }
    AgentCatalog catalog() {
        var workspace = new WorkspaceHandle(root);
        return AgentCatalog.load(workspace, new RuntimeConfigLoader(ENV, redactor).load(workspace), ENV, redactor);
    }
    @Test void isolatesInvalidAgentsAndKeepsOnlyExplicitPermissions() throws Exception {
        agent("good", "");
        Path missing = agent("missing", ""); Files.writeString(missing, Files.readString(missing).replace("name: good,", "name: missing,"));
        agent("invalid", "tools: [shell, shell]\n");
        var catalog = catalog(); var profile = assertDoesNotThrow(() -> catalog.require("good"));
        assertTrue(profile.tools().isEmpty()); assertEquals(10, profile.maxIterations()); assertEquals(20, profile.maxHistoryTurns());
        assertEquals(3, catalog.entries().size()); assertEquals(1, catalog.entries().stream().filter(AgentCatalog.Entry::available).count());
        assertFalse(catalog.entries().toString().contains("fixture-secret"));
    }
    @Test void snapshotsConfigurationSeparatelyFromBodyAndValidatesSchedules() throws Exception {
        Path file = agent("good", "schedules:\n  - id: morning\n    cron: '0 0 8 * * *'\n    message: hello\n");
        var catalog = catalog(); var old = assertDoesNotThrow(() -> catalog.require("good"));
        Files.writeString(file, Files.readString(file).replace("initial instructions", "changed instructions"));
        var changed = catalog().require("good");
        assertEquals(old.configRevision(), changed.configRevision()); assertNotEquals(old.bodyRevision(), changed.bodyRevision());
        assertEquals(old, catalog.require("good")); assertEquals("Asia/Shanghai", old.schedules().getFirst().timezone().getId());
        for (String bad : List.of("settings: {max_iterations: 101}\n", "settings: {max_history_turns: 0}\n",
                "schedules: [{id: x, cron: '0 99 8 * * *', message: hello}]\n",
                "schedules: [{id: x, cron: '0 0 8 * * *', timezone: UTC, message: hello}]\n",
                "bootstrap: [secrets.local.yaml]\n", "tools: [shell, shell]\n")) {
            agent("bad", bad); assertFalse(catalog().entries().stream().filter(e -> e.name().equals("bad")).findFirst().orElseThrow().available());
        }
    }
    @Test void rejectsUnsafeDefinitionsAndBootstrapEscapesWithoutAffectingGoodAgent() throws Exception {
        agent("good", ""); Path bad=agent("bad", "");
        for(String content: List.of("no frontmatter", "---\nname: bad\nname: duplicate\n---\nbody", "---\n!!java.net.URL [https://example.com]\n---\nbody", "---\na: &x [1]\nb: [*x]\n---\nbody")) {
            Files.writeString(bad,content); assertDoesNotThrow(() -> catalog().require("good"));
            assertFalse(catalog().entries().stream().filter(e->e.name().equals("bad")).findFirst().orElseThrow().available());
        }
        agent("bad", "bootstrap: [alias.md]\n"); Files.writeString(root.resolve("secrets.local.yaml"),"SECRET: private-value\n");
        Files.createSymbolicLink(root.resolve("alias.md"),root.resolve("secrets.local.yaml"));
        assertFalse(catalog().entries().stream().filter(e->e.name().equals("bad")).findFirst().orElseThrow().available());
        assertFalse(catalog().entries().toString().contains("private-value"));
        Path outside=Files.createTempDirectory("oryxos-agent-outside-");
        try {
            Files.writeString(outside.resolve("AGENT.md"),"---\nschema_version: 1\nname: escaped\nprovider: {name: good, model: model}\n---\nbody");
            Files.createSymbolicLink(root.resolve("agents/escaped"),outside);
            assertFalse(catalog().entries().stream().filter(e->e.name().equals("escaped")).findFirst().orElseThrow().available());
        } finally { Files.deleteIfExists(outside.resolve("AGENT.md")); Files.delete(outside); }
    }
    @Test void exposesNotificationNamesOnlyAndKeepsRevisionStableAcrossFormatting() throws Exception {
        Files.writeString(root.resolve("secrets.local.yaml"),"HOOK: 'https://example.invalid/private-hook'\n");
        Files.setPosixFilePermissions(root.resolve("secrets.local.yaml"),java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        Path file=agent("good","notify_channels:\n  daily:\n    type: webhook\n    url: '${HOOK}'\n    payload: {text: '$CONTENT'}\n    acknowledgement: {mode: http_2xx}\n");
        var original=catalog().require("good"); assertEquals(Set.of("daily"),original.notifyChannelNames());
        assertFalse(original.toString().contains("private-hook")); assertFalse(original.notifications().toString().contains("private-hook"));
        Files.writeString(file,Files.readString(file).replace("name: good\nprovider:","name: good # comment\nprovider:").replace("{name: good, model: configured-model}","{model: configured-model, name: good}"));
        assertEquals(original.configRevision(),catalog().require("good").configRevision());
        Files.writeString(file,Files.readString(file).replace("configured-model","different-model"));
        assertNotEquals(original.configRevision(),catalog().require("good").configRevision());
    }

    @Test @Tag("us4-tools") void notificationDefaultsAndPrivateHeadersAreValidatedPerAgent() throws Exception {
        Files.writeString(root.resolve("secrets.local.yaml"), "HOOK: 'https://example.invalid/private-hook'\nHEADER: private-header-canary\n");
        Files.setPosixFilePermissions(root.resolve("secrets.local.yaml"), java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        String notification = "default_target: daily\nnotify_channels:\n  daily:\n    type: webhook\n    url: '${HOOK}'\n    headers: {X-Private-Key: '${HEADER}'}\n    payload: {text: '$CONTENT'}\n    acknowledgement: {mode: http_2xx}\n";
        agent("good", notification);
        var profile = catalog().require("good");
        assertTrue(profile.notifications().get("daily").defaultTarget());
        assertEquals("${HEADER}", profile.notifications().get("daily").headerReferences().get("X-Private-Key"));
        for (String invalid : List.of(notification.replace("default_target: daily", "default_target: unknown"), notification.replace("X-Private-Key", "Host"), notification.replace("'${HEADER}'", "inline-credential"))) {
            agent("bad", invalid);
            assertDoesNotThrow(() -> catalog().require("good"));
            assertThrows(io.github.oryxlabs.oryxos.core.error.OryxError.class, () -> catalog().require("bad"));
            assertFalse(catalog().entries().toString().contains("private-header-canary"));
        }
    }
}
