package io.github.oryxlabs.oryxos.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Tag("f0-foundation")
class WorkspaceCommandsTest {
  @TempDir Path root;
  @TempDir Path outside;
  private StringWriter output;

  private int execute(String action) {
    output = new StringWriter();
    var command =
        new OryxCommandLine(
                (mode, workspace) -> {
                  fail("light command started runtime");
                  return 3;
                })
            .commandLine();
    command.setOut(new PrintWriter(output));
    command.setErr(new PrintWriter(output));
    return command.execute("--root", root.toString(), action);
  }

  @Test
  void initIsIdempotentAndStatusIsHonest() throws Exception {
    assertEquals(0, execute("init"), output.toString());
    assertTrue(Files.isRegularFile(root.resolve("agents/default/AGENT.md")));
    Files.writeString(root.resolve("USER.md"), "Keep my text");
    Map<Path, byte[]> before = new HashMap<>();
    try (var paths = Files.walk(root)) {
      paths
          .filter(Files::isRegularFile)
          .forEach(p -> {
            try {
              before.put(p, Files.readAllBytes(p));
            } catch (IOException e) {
              throw new UncheckedIOException(e);
            }
          });
    }
    assertEquals(0, execute("init"));
    for (var entry : before.entrySet()) {
      assertArrayEquals(entry.getValue(), Files.readAllBytes(entry.getKey()));
    }
    assertEquals(0, execute("status"), output.toString());
    assertTrue(output.toString().contains("Asia/Shanghai"));
    assertTrue(output.toString().contains("NOT_RUNNING"));
    assertTrue(output.toString().contains("NOT_CHECKED"));
    assertFalse(output.toString().contains("production session/execution storage pending"));
    assertFalse(Files.exists(root.resolve("oryxos.db")));
  }

  @Test
  void initRefusesEscapingSymlink() throws Exception {
    Files.createSymbolicLink(root.resolve("agents"), outside);
    assertEquals(2, execute("init"));
    try (var files = Files.list(outside)) {
      assertEquals(0, files.count());
    }
  }

  @Test
  void invalidConfigurationIsSafeAndDoesNotStartRuntime() throws Exception {
    assertEquals(0, execute("init"));
    Files.writeString(root.resolve("runtime.yaml"), "providers: [CANARY-secret: [");
    assertEquals(2, execute("status"));
    assertTrue(output.toString().contains("CONFIGURATION_ERROR"));
    assertFalse(output.toString().contains("CANARY-secret"));
    assertFalse(Files.exists(root.resolve("oryxos.db")));
  }
}
