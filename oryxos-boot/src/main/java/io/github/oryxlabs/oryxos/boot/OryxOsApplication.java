package io.github.oryxlabs.oryxos.boot;

import io.github.oryxlabs.oryxos.boot.runtime.BootRuntimeLauncher;
import io.github.oryxlabs.oryxos.cli.OryxCommandLine;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;

/** Assembles the OryxOS modules into a single executable application. */
@SpringBootApplication(
    scanBasePackages = "io.github.oryxlabs.oryxos",
    exclude = {
      DataSourceAutoConfiguration.class,
      HibernateJpaAutoConfiguration.class,
      JpaRepositoriesAutoConfiguration.class
    })
public class OryxOsApplication {

  public static void main(String[] args) {
    int code =
        execute(
            args,
            new BootRuntimeLauncher(),
            new java.io.InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8),
            new java.io.PrintWriter(System.out, true),
            new java.io.PrintWriter(System.err, true));
    if (code != 0) {
      System.exit(code);
    }
  }

  /**
   * Same parser/launcher path as main, with explicit streams for embedding and CLI verification.
   */
  public static int execute(
      String[] args,
      io.github.oryxlabs.oryxos.core.runtime.RuntimeLauncher launcher,
      java.io.Reader input,
      java.io.PrintWriter output,
      java.io.PrintWriter error) {
    var line =
        new OryxCommandLine(launcher, new io.github.oryxlabs.oryxos.boot.runtime.BootCliQueries())
            .input(input)
            .commandLine();
    line.setOut(output);
    line.setErr(error);
    return line.execute(args);
  }
}
