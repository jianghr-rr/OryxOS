package io.github.oryxlabs.oryxos.boot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.oryxlabs.oryxos.boot.config.*;
import io.github.oryxlabs.oryxos.boot.runtime.BootRuntimeLauncher;
import io.github.oryxlabs.oryxos.boot.support.*;
import io.github.oryxlabs.oryxos.core.agent.AgentService;
import io.github.oryxlabs.oryxos.core.memory.*;
import io.github.oryxlabs.oryxos.core.execution.*;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.model.*;
import io.github.oryxlabs.oryxos.core.provider.*;
import io.github.oryxlabs.oryxos.core.session.SessionManager;
import io.github.oryxlabs.oryxos.core.workspace.*;
import io.github.oryxlabs.oryxos.memory.backend.*;
import io.github.oryxlabs.oryxos.memory.mem0.*;
import io.github.oryxlabs.oryxos.storage.lifecycle.*;
import io.github.oryxlabs.oryxos.storage.memory.JpaMemoryRecordStore;
import java.net.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("us3-memory")
class MemoryFacadeIntegrationTest {
    @TempDir Path root;
    static final List<ProviderRequest> REQUESTS=new CopyOnWriteArrayList<>();
    @TestConfiguration(proxyBeanMethods=false)
    static class ScriptedModel {
        @Bean @Primary ProviderPort memoryTestProvider(){return request->{
            REQUESTS.add(request);boolean save=request.orderedMessages().stream().anyMatch(m->m.role()==ChatMessage.Role.USER&&"remember preference".equals(m.content()));
            var calls=save&&request.iteration()==1?List.of(new ToolCall("save-1","save_memory","save_memory","{\"content\":\"我关注 AI 芯片\"}")):List.<ToolCall>of();
            return new ProviderReply(new ChatMessage(ChatMessage.Role.ASSISTANT,calls.isEmpty()?"done":null,calls,null,null,null),calls.isEmpty()?"STOP":"TOOL_CALLS",null,"local","model");
        };}
    }
    @Test void engineUsesUnifiedMemoryToolsSharesFactsAndKeepsSessionsSeparate() throws Exception {
        REQUESTS.clear();var workspace=new WorkspaceHandle(root);
        new WorkspaceInitializer().initialize(workspace,Map.of("runtime.yaml","schema_version: 1\nmemory: {backend: sqlite}\nproviders:\n  local:\n    protocol: openai_chat_completions\n    endpoint: http://127.0.0.1:1\n    completions_path: /chat/completions\n    api_key: '${LOCAL_KEY}'\n"));
        Files.writeString(root.resolve("secrets.local.yaml"),"LOCAL_KEY: fixture-secret\n");Files.setPosixFilePermissions(root.resolve("secrets.local.yaml"),java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        for(String agent:List.of("one","two")){Path file=root.resolve("agents/"+agent+"/AGENT.md");Files.createDirectories(file.getParent());Files.writeString(file,"---\nschema_version: 1\nname: "+agent+"\nprovider: {name: local, model: model}\ntools: [save_memory, recall_memory]\n---\nUse explicit memory tools only.\n");}
        var launcher=new BootRuntimeLauncher(Map.of("spring.profiles.active","us2-local","spring.main.banner-mode","off"),EarlyRuntimeConfiguration.class,ScriptedModel.class);
        try(var context=launcher.start(workspace,Map.of("spring.main.web-application-type","none"))){
            AgentService service=context.getBean(AgentRuntimeConfiguration.RuntimeAgents.class).require();
            var first=service.process(ProcessRequest.manual("cli","alice","one",null,"remember preference"));assertEquals(Execution.Status.SUCCEEDED,first.status());assertTrue(REQUESTS.get(1).systemPrompt().contains("我关注 AI 芯片"));
            var second=service.process(ProcessRequest.manual("cli","bob","two",null,"ordinary conversation"));assertEquals(Execution.Status.SUCCEEDED,second.status());assertNotEquals(first.sessionId(),second.sessionId());
            var last=REQUESTS.getLast();assertTrue(last.systemPrompt().contains("我关注 AI 芯片"));assertEquals(1,last.orderedMessages().size());
            assertEquals("SESSION_IDENTITY_MISMATCH",assertThrows(OryxError.class,()->service.process(ProcessRequest.manual("http","bob","two",first.sessionId(),"forbidden"))).code());assertEquals(3,REQUESTS.size());
            var ledger=context.getBean(MemoryRecordStore.class);assertEquals(1,ledger.list(WorkspaceInitializer.readId(workspace),"sqlite",MemoryEntry.Scope.ARCHIVAL,true).size());
            assertEquals(1,EarlyRuntimeConfiguration.lastAudit.tools.stream().filter(t->t.status().equals("SUCCESS")&&t.toolName().equals("save_memory")).count());
            context.getBean(SessionManager.class).archive(first.sessionId(),"cli","alice","one");
            assertEquals("SESSION_ARCHIVED",assertThrows(OryxError.class,()->service.process(ProcessRequest.manual("cli","alice","one",first.sessionId(),"again"))).code());
        }
    }
    @Test void realSqliteAndMarkdownRemainSeparateAndConfirmedConcurrentWritesSurviveReopen() throws Exception {
        try(var f=new MemoryFixture(root)){
            var factory=new StorageResourceFactory();String core="CORE😀".repeat(3000);
            var md=new MarkdownMemoryStore(f.workspace,f.id,f.lock);assertTrue(md.save("md fact",MemoryEntry.Scope.ARCHIVAL,f.context("md-agent")).confirmed());
            try(var resources=factory.open(f.workspace,f.lock)){
                var store=new SqliteMemoryStore(f.id,new JpaMemoryRecordStore(()->resources));assertTrue(store.save(core,MemoryEntry.Scope.CORE,f.context("first")).confirmed());
                try(var workers=Executors.newVirtualThreadPerTaskExecutor()){
                    var jobs=new ArrayList<Future<WriteReceipt>>();for(int i=0;i<20;i++){int n=i;jobs.add(workers.submit(()->store.save("Straße Café A_50% "+n,MemoryEntry.Scope.ARCHIVAL,f.context("writer"+n))));}for(var job:jobs)assertTrue(job.get().confirmed());
                }
                assertEquals(20,store.recall("strasse",f.context("other")).size());assertEquals(20,store.recall("CAFE\u0301",f.context("other")).size());assertTrue(store.recall("A_5_",f.context("other")).isEmpty());assertTrue(store.recall("CORE",f.context("other")).isEmpty());
            }
            try(var reopened=factory.open(f.workspace,f.lock)){
                var records=new JpaMemoryRecordStore(()->reopened);var store=new SqliteMemoryStore(f.id,records);
                assertEquals(core,store.load(MemoryEntry.Scope.CORE,f.context("other")).getFirst().rawContent());assertEquals(20,store.load(MemoryEntry.Scope.ARCHIVAL,f.context("other")).size());assertEquals(1,md.load(MemoryEntry.Scope.ARCHIVAL,f.context("other")).size());assertTrue(records.list(UUID.randomUUID(),"sqlite",MemoryEntry.Scope.CORE,true).isEmpty());
                reopened.close();assertThrows(OryxError.class,()->store.load(MemoryEntry.Scope.CORE,f.context("other")));
            }
        }
    }
    @Test void mem0UsesRealLocalLedgerAndOnlyConfirmedWorkspaceMappingsSurviveReopen() throws Exception {
        var json=new ObjectMapper();var projection=new AtomicReference<Map<String,Object>>();var calls=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{calls.incrementAndGet();Object response;
            if(exchange.getRequestURI().getPath().equals("/memories")){
                var request=json.readTree(exchange.getRequestBody());projection.set(Map.of("id","remote-id","user_id",request.path("user_id").asText(),"metadata",json.convertValue(request.get("metadata"),Map.class),"score",0.9));response=Map.of("results",List.of(Map.of("id","remote-id")));
            }else response=projection.get();
            byte[] body=json.writeValueAsBytes(response);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
        try(var f=new MemoryFixture(root)){
            var factory=new StorageResourceFactory();UUID source;
            try(var resources=factory.open(f.workspace,f.lock);var backend=new Mem0MemoryStore(f.id,new JpaMemoryRecordStore(()->resources),new Mem0Client(new Mem0Config(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"${KEY}",2,10,0.7),"fixture"))){
                var receipt=backend.save("explicit original",MemoryEntry.Scope.ARCHIVAL,f.context("writer"));assertTrue(receipt.confirmed());source=receipt.sourceEntryId();assertEquals(2,calls.get());
            }
            try(var reopened=factory.open(f.workspace,f.lock)){
                var records=new JpaMemoryRecordStore(()->reopened);assertTrue(records.maps(f.id,source,"remote-id"));assertFalse(records.maps(UUID.randomUUID(),source,"remote-id"));assertEquals("explicit original",records.find(f.id,source).rawContent());
                assertThrows(OryxError.class,()->records.finish(UUID.randomUUID(),source,MemoryEntry.WriteStatus.CONFIRMED,List.of("foreign"),Instant.now()));assertFalse(records.maps(f.id,source,"foreign"));
            }
        }finally{server.stop(0);}
    }
}
