package io.github.oryxlabs.oryxos.boot.config;

import io.github.oryxlabs.oryxos.core.agent.AgentService;
import io.github.oryxlabs.oryxos.core.audit.AuditSink;
import io.github.oryxlabs.oryxos.core.config.SafeYaml;
import io.github.oryxlabs.oryxos.core.context.*;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.execution.ExecutionStore;
import io.github.oryxlabs.oryxos.core.memory.MemoryPort;
import io.github.oryxlabs.oryxos.core.provider.ProviderPort;
import io.github.oryxlabs.oryxos.core.react.ReActLoop;
import io.github.oryxlabs.oryxos.core.runtime.*;
import io.github.oryxlabs.oryxos.core.security.Redactor;
import io.github.oryxlabs.oryxos.core.session.*;
import io.github.oryxlabs.oryxos.core.tool.*;
import io.github.oryxlabs.oryxos.core.workspace.WorkspaceHandle;
import io.github.oryxlabs.oryxos.tool.ToolRegistry;
import io.github.oryxlabs.oryxos.tool.http.*;
import io.github.oryxlabs.oryxos.tool.policy.ToolPolicy;
import io.github.oryxlabs.oryxos.tool.validation.ToolArgumentValidator;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods=false)
public class AgentRuntimeConfiguration {
    public record RuntimeAgents(AgentService service) implements AutoCloseable {
        public AgentService require(){if(service==null)throw new OryxError("RUNTIME_NOT_READY","Execution storage and memory services are not configured; chat is not ready");return service;}
        public void close(){if(service!=null)service.close();}
    }
    @Bean TimeSource runtimeTimeSource(){return TimeSource.system();}
    @Bean(destroyMethod="close") RuntimeAgents runtimeAgents(WorkspaceHandle workspace,RuntimeConfiguration.LoadedRuntime loaded,
            ToolConfiguration.CheckedAgents agents,StorageLifecycle storage,RuntimeGate gate,TimeSource time,ProviderPort provider,ToolRegistryPort tools,
            AuditSink audit,Redactor redactor,ObjectProvider<SessionStore> sessions,ObjectProvider<ExecutionStore> executions,ObjectProvider<MemoryPort> memory,ObjectProvider<SessionManager> managers,ObjectProvider<io.github.oryxlabs.oryxos.boot.runtime.RuntimeStartupCheck> startup) {
        SessionStore sessionStore=sessions.getIfAvailable();ExecutionStore executionStore=executions.getIfAvailable();MemoryPort memoryPort=memory.getIfAvailable();
        if(loaded.config()==null||agents.catalog()==null||sessionStore==null||executionStore==null||memoryPort==null)return new RuntimeAgents(null);
        var manager=managers.getObject();
        var executor=new ToolExecutor(tools,audit,d->Thread.sleep(d));
        var loop=new ReActLoop(provider,tools,executor,memoryPort,new PromptBuilder(new ContextLoader()));
        var service=new AgentService(workspace,agents.catalog(),manager,executionStore,loop,tools,gate,time,loaded.config().timezone(),redactor);
        var check=startup.getIfAvailable();if(check!=null)check.openInitial();else gate.readyAtFirstStartup();return new RuntimeAgents(service);
    }
    @Bean SessionManager sessionManager(ObjectProvider<SessionStore> sessions,TimeSource time) {
        var store=sessions.getIfAvailable();
        if(store==null)return null; // No in-memory fallback in the production distribution.
        return new SessionManager(store,time);
    }
}
