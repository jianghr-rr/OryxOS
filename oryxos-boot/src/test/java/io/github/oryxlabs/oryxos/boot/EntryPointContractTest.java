package io.github.oryxlabs.oryxos.boot;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Tag("f0-discovery")
class EntryPointContractTest {
  @TempDir Path temp;

  private String invoke(String argument) throws Exception {
    Path output = temp.resolve("output.txt");
    var builder =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-Dserver.port=0",
                "-Doryxos.root=" + temp.resolve("workspace"),
                "-cp",
                System.getProperty("java.class.path"),
                OryxOsApplication.class.getName(),
                argument)
            .redirectErrorStream(true)
            .redirectOutput(output.toFile());
    builder.environment().clear();
    var child = builder.start();
    try {
      assertTrue(
          child.waitFor(10, TimeUnit.SECONDS), "light command must exit without starting Spring");
      assertEquals(0, child.exitValue(), Files.readString(output));
      String text = Files.readString(output);
      assertFalse(text.contains("Spring Boot"), text);
      assertFalse(text.contains("Tomcat"), text);
      assertFalse(Files.exists(temp.resolve("workspace")), "light command cannot create workspace");
      return text;
    } finally {
      child.destroyForcibly();
      child.waitFor();
    }
  }

  @Test
  void versionDoesNotStartSpring() throws Exception {
    assertTrue(invoke("--version").contains("1.0.0-SNAPSHOT"));
  }

  @Test
  void helpDoesNotStartSpring() throws Exception {
    assertTrue(invoke("--help").contains("Usage:"));
  }
}
