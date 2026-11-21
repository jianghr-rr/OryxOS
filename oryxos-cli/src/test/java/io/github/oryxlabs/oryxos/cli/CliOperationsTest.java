package io.github.oryxlabs.oryxos.cli;

import static org.junit.jupiter.api.Assertions.*;

import io.github.oryxlabs.oryxos.core.workspace.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Tag("us5-cli")
class CliOperationsTest {
  @TempDir Path root;
  @TempDir Path outside;
  StringWriter output;

  int run(String... args) {
    output = new StringWriter();
    var line =
        new OryxCommandLine(
                (mode, workspace) -> {
                  fail("Light operation launched Spring");
                  return 3;
                })
            .commandLine();
    line.setOut(new PrintWriter(output));
    line.setErr(new PrintWriter(output));
    var all = new ArrayList<>(List.of("--root", root.toString()));
    all.addAll(List.of(args));
    return line.execute(all.toArray(String[]::new));
  }

  @BeforeEach
  void init() {
    assertEquals(0, run("init"));
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void profilesAreFileBasedNeverOverwriteAndArchiveWithoutDeletingHistory() throws Exception {
    assertEquals(0, run("profile", "create", "researcher"), output.toString());
    Path file = root.resolve("agents/researcher/AGENT.md");
    String original = Files.readString(file);
    assertTrue(original.contains("name: researcher"));
    assertEquals(4, run("profile", "create", "researcher"));
    assertEquals(original, Files.readString(file));
    assertEquals(0, run("profile", "list"));
    assertTrue(output.toString().contains("researcher"));
    Files.writeString(root.resolve("oryxos.db"), "existing history sentinel");
    assertEquals(0, run("profile", "delete", "researcher"), output.toString());
    assertFalse(Files.exists(file));
    assertEquals("existing history sentinel", Files.readString(root.resolve("oryxos.db")));
    try (var paths = Files.walk(root.resolve("agent-archive"))) {
      assertTrue(
          paths
              .filter(Files::isRegularFile)
              .anyMatch(p -> {
                try {
                  return Files.readString(p).equals(original);
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              }));
    }
    assertEquals(3, run("profile", "show", "researcher"));
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void deletionIsBlockedWhileAnyRuntimeOwnsWorkspace() throws Exception {
    try (var lock = WorkspaceProcessLock.acquire(new WorkspaceHandle(root))) {
      assertEquals(4, run("profile", "delete", "default"), output.toString());
      assertTrue(Files.exists(root.resolve("agents/default/AGENT.md")));
      assertEquals(0, run("profile", "create", "next"), output.toString());
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void profilePathsRejectTraversalAndEscapingSymlinks() throws Exception {
    assertEquals(2, run("profile", "create", "../escape"));
    Files.createSymbolicLink(root.resolve("agents/linked"), outside);
    assertEquals(2, run("profile", "show", "linked"));
    assertEquals(2, run("profile", "delete", "linked"));
    Files.createSymbolicLink(root.resolve("agent-archive"), outside);
    assertEquals(2, run("profile", "delete", "default"));
    try (var paths = Files.list(outside)) {
      assertEquals(0, paths.count());
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void lightJsonQueriesAreHonestAndMalformedInputIsSafe() throws Exception {
    assertEquals(0, run("status", "--json"), output.toString());
    assertTrue(output.toString().startsWith("{\"code\":\"OK\""));
    assertTrue(output.toString().contains("NOT_RUNNING"));
    assertTrue(output.toString().contains("NOT_CHECKED"));
    assertFalse(output.toString().contains("NOT_IMPLEMENTED"));
    assertEquals(0, run("provider", "list", "--json"), output.toString());
    assertTrue(output.toString().contains("CURRENT_FILES"));
    assertFalse(Files.exists(root.resolve("oryxos.db")));
    assertEquals(2, run("chat", "--session", "PRIVATE_CANARY", "--json"));
    assertTrue(output.toString().contains("\"code\":\"INVALID_INPUT\""));
    assertFalse(output.toString().contains("PRIVATE_CANARY"));
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void profileShowRedactsConfigurationAndBodyAndExplainsSnapshot() throws Exception {
    Path agent = root.resolve("agents/default/AGENT.md");
    Files.writeString(
        agent,
        Files.readString(agent)
            + "\nAuthorization: Bearer BODY_CANARY\nhttps://private.example/key\n");
    assertEquals(0, run("profile", "show", "default", "--json"), output.toString());
    assertTrue(output.toString().contains("currentFile"));
    assertTrue(output.toString().contains("NOT_RUNNING"));
    assertFalse(output.toString().contains("BODY_CANARY"));
    assertFalse(output.toString().contains("private.example"));
    assertFalse(Files.exists(root.resolve("oryxos.db")));
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void twelveOperationsAndModeHelpAreDiscoverableWithoutLaunching() {
    assertEquals(0, run("--help"));
    for (String s :
        List.of(
            "init", "status", "chat", "serve", "gateway", "profile", "provider", "tool", "session"))
      assertTrue(output.toString().contains(s), s);
    assertEquals(0, run("profile", "--help"));
    for (String s : List.of("list", "create", "show", "delete"))
      assertTrue(output.toString().contains(s), s);
    assertEquals(0, run("gateway", "--help"));
    assertTrue(output.toString().contains("HTTP"));
    assertTrue(output.toString().contains("IM"));
  }
}
