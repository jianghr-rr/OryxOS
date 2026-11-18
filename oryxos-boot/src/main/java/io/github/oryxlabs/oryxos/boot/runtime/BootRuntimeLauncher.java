package io.github.oryxlabs.oryxos.boot.runtime;

import io.github.oryxlabs.oryxos.boot.OryxOsApplication;
import io.github.oryxlabs.oryxos.boot.config.AgentRuntimeConfiguration;
import io.github.oryxlabs.oryxos.channel.cli.CliChannelAdapter;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.runtime.*;
import io.github.oryxlabs.oryxos.core.workspace.*;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

/** Boot implements the inward-facing core port; CLI never depends on Boot. */
public final class BootRuntimeLauncher implements RuntimeLauncher {
  private final Map<String, Object> properties;
  private final Class<?>[] additionalSources;

  public BootRuntimeLauncher() {
    this(Map.of());
  }

  public BootRuntimeLauncher(Map<String, Object> properties, Class<?>... additionalSources) {
    this.properties = Map.copyOf(properties);
    this.additionalSources = additionalSources.clone();
  }

  public int chat(
      WorkspaceHandle workspace,
      ChatOptions options,
      java.io.Reader input,
      java.io.PrintWriter output) {
    try (var context =
        start(
            workspace,
            Map.of(
                "spring.main.web-application-type", "none", "oryxos.scheduling.enabled", false))) {
      var service = context.getBean(AgentRuntimeConfiguration.RuntimeAgents.class).require();
      if (context.getBean(RuntimeGate.class).state() != RuntimeGate.State.READY)
        throw new OryxError(
            "EXECUTION_PAUSED",
            "Workspace recording is paused; inspect the incident and complete manual recovery"
                + " before chatting");
      return new CliChannelAdapter(
              service,
              context.getBean(io.github.oryxlabs.oryxos.core.security.Redactor.class),
              context.getBean(TimeSource.class))
          .chat(options, input, output);
    }
  }

  public int launch(LaunchMode mode, WorkspaceHandle workspace) {
    if (mode != LaunchMode.SERVE && mode != LaunchMode.GATEWAY)
      throw new OryxError("NOT_IMPLEMENTED", "Runtime mode is not supported");
    start(workspace, Map.of("oryxos.scheduling.enabled", true));
    return 0;
  }

  public ConfigurableApplicationContext start(
      WorkspaceHandle workspace, Map<String, Object> serverDefaults) {
    var lock = WorkspaceProcessLock.acquire(workspace);
    try {
      CliRuntimeView.clear(workspace);
      var sources = new java.util.ArrayList<Class<?>>();
      sources.add(OryxOsApplication.class);
      sources.addAll(java.util.List.of(additionalSources));
      SpringApplication application = new SpringApplication(sources.toArray(Class<?>[]::new));
      var defaults = new LinkedHashMap<>(properties);
      defaults.putAll(serverDefaults);
      application.setBannerMode(org.springframework.boot.Banner.Mode.OFF);
      application.setDefaultProperties(defaults);
      application.addInitializers(
          context -> {
            context
                .getEnvironment()
                .getPropertySources()
                .addFirst(
                    new org.springframework.core.env.MapPropertySource(
                        "oryxos-launcher-options", defaults));
            var generic = (GenericApplicationContext) context;
            generic.registerBean("selectedWorkspace", WorkspaceHandle.class, () -> workspace);
            generic.registerBean(
                "selectedWorkspaceLock",
                WorkspaceProcessLock.class,
                () -> lock,
                definition -> definition.setDestroyMethodName("close"));
          });
      var context = application.run();
      try {
        CliRuntimeView.publish(workspace, context);
        return context;
      } catch (RuntimeException error) {
        context.close();
        throw error;
      }
    } catch (RuntimeException error) {
      lock.close();
      throw error;
    }
  }
}
