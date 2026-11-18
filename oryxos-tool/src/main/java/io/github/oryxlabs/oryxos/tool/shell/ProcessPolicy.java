package io.github.oryxlabs.oryxos.tool.shell;

import io.github.oryxlabs.oryxos.core.config.SafeYaml;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.security.SecretResolver;
import io.github.oryxlabs.oryxos.tool.file.FileAccessPolicy;
import java.nio.file.*;
import java.util.*;

public final class ProcessPolicy {
  public record Invocation(List<String> argv, Path directory, Map<String, String> environment) {
    @Override
    public String toString() {
      return "Invocation[arguments and environment withheld]";
    }
  }

  private final Path root;
  private final Set<Path> commands;
  private final FileAccessPolicy scripts;
  private final Map<String, String> extra;
  private final int timeout;

  public ProcessPolicy(Path root, Map<String, Object> rules, SecretResolver secrets) {
    this.root = root;
    commands = new HashSet<>();
    for (Object raw : (List<?>) rules.getOrDefault("allowed_commands", List.of())) {
      Path p = Path.of((String) raw);
      if (!p.isAbsolute()) throw OryxError.configuration("shell absolute command");
      commands.add(p.normalize());
    }
    scripts =
        new FileAccessPolicy(
            root,
            ((List<?>) rules.getOrDefault("allowed_script_paths", List.of()))
                .stream().map(Object::toString).toList(),
            List.of());
    timeout = SafeYaml.positive(rules.getOrDefault("timeout_seconds", 20), "shell timeout");
    var values = new HashMap<String, String>();
    SafeYaml.map(rules.getOrDefault("env", Map.of()), "shell env")
        .forEach(
            (k, v) -> {
              if (!k.matches("[A-Za-z_][A-Za-z0-9_]*")
                  || Set.of("PATH", "HOME", "TMPDIR").contains(k))
                throw OryxError.configuration("shell env name");
              values.put(k, secrets.resolve(SafeYaml.text(v, "shell env reference")));
            });
    extra = Map.copyOf(values);
  }

  public int timeoutSeconds() {
    return timeout;
  }

  public Invocation authorize(Map<String, Object> args) {
    try {
      Path command = Path.of((String) args.get("command"));
      if (!command.isAbsolute()
          || !commands.contains(command.normalize())
          || !Files.isExecutable(command)
          || Files.isDirectory(command)) throw denied();
      var argv = new ArrayList<String>();
      argv.add(command.toString());
      for (Object arg : (List<?>) args.getOrDefault("args", List.of())) argv.add((String) arg);
      String name = command.getFileName().toString().toLowerCase(Locale.ROOT);
      if (name.matches(
          "python[0-9.]*|node(js)?|ruby[0-9.]*|perl[0-9.]*|[bz]?sh|dash|fish|php[0-9.]*")) {
        if (argv.size() < 2 || argv.get(1).startsWith("-") || !Path.of(argv.get(1)).isAbsolute())
          throw denied();
        Path script = Path.of(argv.get(1)).normalize();
        if (!script.startsWith(root)) throw denied();
        scripts.authorize(root.relativize(script).toString(), false);
        if (!Files.isRegularFile(script)) throw denied();
        argv.set(1, script.toString());
      }
      Path dir = root.resolve((String) args.getOrDefault("workingDirectory", ".")).normalize();
      if (!dir.startsWith(root) || !Files.isDirectory(dir)) throw denied();
      Path realRoot = root.toRealPath();
      if (!dir.toRealPath().startsWith(realRoot)) throw denied();
      var env = new HashMap<>(extra);
      env.put("PATH", "/usr/bin:/bin");
      env.put("HOME", root.resolve("runtime/process-home").toString());
      env.put("TMPDIR", root.resolve("runtime/process-tmp").toString());
      return new Invocation(List.copyOf(argv), dir, Map.copyOf(env));
    } catch (OryxError e) {
      throw e;
    } catch (Exception e) {
      throw denied();
    }
  }

  private static OryxError denied() {
    return new OryxError("TARGET_DENIED", "Command, script or working directory is not allowed");
  }
}
