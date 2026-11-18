package io.github.oryxlabs.oryxos.provider;

import io.github.oryxlabs.oryxos.core.agent.AgentCatalog;
import io.github.oryxlabs.oryxos.core.audit.ModelCallRecord;
import io.github.oryxlabs.oryxos.core.config.RuntimeConfigLoader;
import io.github.oryxlabs.oryxos.core.model.*;
import io.github.oryxlabs.oryxos.core.provider.ProviderRequest;
import io.github.oryxlabs.oryxos.core.security.Redactor;
import io.github.oryxlabs.oryxos.core.workspace.WorkspaceHandle;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Standalone US1 demonstration: actual adapter, two loopback services, no real-model claim. */
@Tag("us1-contract")
class ProviderSmokeTest {
    @TempDir Path root;
    @Test void twoAgentDefinitionsReachTheirConfiguredServices() throws Exception {
        try (var one=new ProviderContractTest.Receiver(); var two=new ProviderContractTest.Receiver()) {
            one.response=ProviderContractTest.ordinary("one-version","one response"); two.response=ProviderContractTest.ordinary("two-version","two response");
            var env=Map.of("ONE_KEY","fixture-one","TWO_KEY","fixture-two");
            Files.writeString(root.resolve("runtime.yaml"),"schema_version: 1\nproviders:\n"+ProviderContractTest.definition("one",one.origin(),"/chat/completions","{}")+ProviderContractTest.definition("two",two.origin(),"/v1/chat/completions","{}"));
            for(String name: List.of("one","two")) {
                Path file=root.resolve("agents/"+name+"/AGENT.md"); Files.createDirectories(file.getParent());
                Files.writeString(file,"---\nschema_version: 1\nname: "+name+"\nprovider: {name: "+name+", model: "+name+"-configured}\n---\nReturn the requested text.\n");
            }
            var redactor=new Redactor(); var workspace=new WorkspaceHandle(root); var config=new RuntimeConfigLoader(env,redactor).load(workspace);
            var catalog=AgentCatalog.load(workspace,config,env,redactor); var audit=new ArrayList<ModelCallRecord>();
            var providers=new ProviderRegistry(config,workspace,env,redactor,audit::add);
            for(String name: List.of("one","two")) {
                var profile=catalog.require(name);
                var reply=providers.call(new ProviderRequest(profile.providerName(),profile.model(),"Return the requested text.",
                        List.of(ChatMessage.user("hello "+name)),List.of(),ToolNameMap.empty(),Duration.ofSeconds(3),UUID.randomUUID(),1));
                assertEquals(name+" response",reply.assistantMessage().content()); assertEquals(name+"-version",reply.actualModel());
            }
            assertEquals(1,one.requests.size()); assertEquals(1,two.requests.size()); assertEquals(4,audit.size());
            System.out.println("US1 LOCAL demo: two AGENT.md profiles -> explicit ProviderRegistry -> two loopback HTTP receivers; two responses, four correlated audit events, zero tool executors.");
        }
    }
}
