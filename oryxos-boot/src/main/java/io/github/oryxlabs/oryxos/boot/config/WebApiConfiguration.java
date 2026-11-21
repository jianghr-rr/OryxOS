package io.github.oryxlabs.oryxos.boot.config;

import com.fasterxml.jackson.databind.*;
import io.github.oryxlabs.oryxos.boot.runtime.RuntimeHttpService;
import io.github.oryxlabs.oryxos.core.execution.ExecutionStore;
import io.github.oryxlabs.oryxos.core.runtime.*;
import io.github.oryxlabs.oryxos.core.schedule.ScheduleCoordinator;
import io.github.oryxlabs.oryxos.core.security.Redactor;
import io.github.oryxlabs.oryxos.core.session.SessionManager;
import io.github.oryxlabs.oryxos.core.tool.ToolRegistryPort;
import io.github.oryxlabs.oryxos.core.workspace.WorkspaceHandle;
import io.github.oryxlabs.oryxos.web.api.*;
import io.github.oryxlabs.oryxos.web.execution.HttpExecutionAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Profile("!us2-local & !us2-real")
public class WebApiConfiguration {
  @Bean
  PublicDtoMapper publicDtoMapper(Redactor redactor) {
    return new PublicDtoMapper(redactor);
  }

  @Bean
  WebRuntimePort webRuntimePort(
      WorkspaceHandle workspace,
      RuntimeConfiguration.LoadedRuntime loaded,
      ToolConfiguration.CheckedAgents catalog,
      AgentRuntimeConfiguration.RuntimeAgents agents,
      SessionManager sessions,
      ExecutionStore executions,
      MemoryConfiguration.SelectedMemory memory,
      ToolRegistryPort tools,
      ScheduleCoordinator schedules,
      RecoveryPort recovery,
      RuntimeGate gate,
      PublicDtoMapper dto) {
    return new RuntimeHttpService(
        workspace,
        loaded,
        catalog,
        agents,
        sessions,
        executions,
        memory,
        tools,
        schedules,
        recovery,
        gate,
        dto);
  }

  @Bean(destroyMethod = "close")
  HttpExecutionAdapter httpExecutionAdapter(
      TimeSource time, PublicDtoMapper mapper, ApiExceptionHandler errors, ObjectMapper json) {
    return new HttpExecutionAdapter(time, mapper, errors, json);
  }

  @Bean
  FilterRegistrationBean<org.springframework.web.filter.OncePerRequestFilter> httpIngress(
      HttpExecutionAdapter adapter) {
    var registration = new FilterRegistrationBean<>(adapter.ingress());
    registration.addUrlPatterns("/api/v1/*");
    registration.setAsyncSupported(true);
    registration.setOrder(Integer.MIN_VALUE + 10);
    return registration;
  }

  @Bean
  Jackson2ObjectMapperBuilderCustomizer strictApiJson() {
    return builder ->
        builder
            .featuresToEnable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .featuresToDisable(
                MapperFeature.ALLOW_COERCION_OF_SCALARS, DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .postConfigurer(mapper -> {
              var text =
                  mapper.coercionConfigFor(com.fasterxml.jackson.databind.type.LogicalType.Textual);
              for (var shape :
                  java.util.List.of(
                      com.fasterxml.jackson.databind.cfg.CoercionInputShape.Integer,
                      com.fasterxml.jackson.databind.cfg.CoercionInputShape.Float,
                      com.fasterxml.jackson.databind.cfg.CoercionInputShape.Boolean))
                text.setCoercion(shape, com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
            });
  }
}
