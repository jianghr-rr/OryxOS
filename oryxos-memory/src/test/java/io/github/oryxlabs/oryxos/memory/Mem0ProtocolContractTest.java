package io.github.oryxlabs.oryxos.memory;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import io.github.oryxlabs.oryxos.core.memory.*;
import io.github.oryxlabs.oryxos.core.execution.*;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.memory.backend.*;
import io.github.oryxlabs.oryxos.memory.mem0.*;
import java.net.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@Tag("us3-memory")
class Mem0ProtocolContractTest {
    @TempDir Path root;
    static final ObjectMapper JSON=new ObjectMapper();
    static class Ledger implements MemoryRecordStore {
        final Map<UUID,MemoryEntry> entries=new LinkedHashMap<>();
        public synchronized void insert(MemoryEntry e){if(entries.putIfAbsent(e.id(),e)!=null)throw new IllegalStateException();}
        public synchronized void finish(UUID workspace,UUID id,MemoryEntry.WriteStatus state,List<String> ids,Instant at){var e=entries.get(id);if(e==null||!e.workspaceId().equals(workspace))throw new IllegalStateException();entries.put(id,e.withState(state,ids));}
        public synchronized List<MemoryEntry> list(UUID workspace,String backend,MemoryEntry.Scope scope,boolean confirmed){return entries.values().stream().filter(e->e.workspaceId().equals(workspace)&&e.backend().equals(backend)&&e.scope()==scope&&(!confirmed||e.writeStatus()==MemoryEntry.WriteStatus.CONFIRMED)).toList();}
        public synchronized MemoryEntry find(UUID workspace,UUID id){var e=entries.get(id);return e!=null&&e.workspaceId().equals(workspace)?e:null;}
        public synchronized boolean maps(UUID workspace,UUID source,String remote){var e=find(workspace,source);return e!=null&&e.writeStatus()==MemoryEntry.WriteStatus.CONFIRMED&&e.remoteIds().contains(remote);}
    }
    static class Server implements AutoCloseable {
        final HttpServer server;final AtomicInteger adds=new AtomicInteger(),gets=new AtomicInteger(),searches=new AtomicInteger();
        final List<JsonNode> requests=new ArrayList<>();final Map<String,Object> projections=new LinkedHashMap<>();String mode="ok";Runnable afterAdd=()->{};
        Server() throws Exception {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",this::handle);server.start();
        }
        URI endpoint(){return URI.create("http://127.0.0.1:"+server.getAddress().getPort());}
        void handle(HttpExchange exchange) throws java.io.IOException {
            assertEquals("fixture-key",exchange.getRequestHeaders().getFirst("X-API-Key"));String path=exchange.getRequestURI().getPath();Object body;
            if(path.equals("/memories")){
                adds.incrementAndGet();var request=JSON.readTree(exchange.getRequestBody());requests.add(request);
                String id=UUID.randomUUID().toString();var item=new LinkedHashMap<String,Object>();item.put("id",id);item.put("user_id",mode.equals("foreign")?UUID.randomUUID().toString():request.path("user_id").asText());item.put("metadata",JSON.convertValue(request.get("metadata"),Map.class));item.put("memory","A projection, not authoritative source text");item.put("score",0.9);projections.put(id,item);
                if(mode.equals("disconnect")){exchange.close();return;}
                body=Map.of("results",mode.equals("empty")?List.of():mode.equals("missing")?List.of(Map.of("memory","no identifier")):List.of(Map.of("id",id)));
                afterAdd.run();
            }else if(path.startsWith("/memories/")){gets.incrementAndGet();if(mode.equals("read-reject")){exchange.sendResponseHeaders(401,-1);exchange.close();return;}body=projections.getOrDefault(path.substring("/memories/".length()),Map.of());}
            else if(path.equals("/search")){searches.incrementAndGet();var request=JSON.readTree(exchange.getRequestBody());requests.add(request);body=Map.of("results",request.path("query").asText().equals("饮食忌口")?List.of():new ArrayList<>(projections.values()));}
            else{exchange.sendResponseHeaders(404,-1);exchange.close();return;}
            byte[] bytes=JSON.writeValueAsBytes(body);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        }
        public void close(){server.stop(0);}
    }
    Mem0MemoryStore backend(MemoryFixture f,Ledger ledger,Server server){return new Mem0MemoryStore(f.id,ledger,new Mem0Client(new Mem0Config(server.endpoint(),"${KEY}",2,10,0.7),"fixture-key"));}
    @Test void archiveConfirmsEachRemoteIdAndSendsOnlyExplicitContent() throws Exception {
        try(var f=new MemoryFixture(root);var server=new Server();var backend=backend(f,new Ledger(),server)){
            var cases=JSON.readTree(getClass().getResourceAsStream("/memory/semantic-cases.json"));String fact=cases.path("save").asText();
            var receipt=backend.save(fact,MemoryEntry.Scope.ARCHIVAL,f.context("writer"));assertTrue(receipt.confirmed());assertEquals("NOT_PROVEN",receipt.semanticCoverage());assertEquals(1,server.adds.get());assertEquals(1,server.gets.get());
            var request=server.requests.getFirst();assertEquals(f.id.toString(),request.path("user_id").asText());assertEquals(1,request.path("messages").size());assertEquals(fact,request.path("messages").get(0).path("content").asText());assertFalse(request.has("agent_id"));assertFalse(request.has("run_id"));
            assertEquals(fact,backend.load(MemoryEntry.Scope.ARCHIVAL,f.context("other-agent")).getFirst().rawContent());
            assertEquals(fact,backend.recall(cases.path("positive").asText(),f.context("reader")).getFirst().rawContent());
            assertTrue(backend.recall(cases.path("negative").asText(),f.context("reader")).isEmpty());
            assertEquals(2,server.searches.get());var search=server.requests.getLast();assertEquals(10,search.path("top_k").asInt());assertEquals(0.7,search.path("threshold").asDouble());assertEquals(f.id.toString(),search.path("filters").path("user_id").asText());
        }
    }
    @Test void coreIsCompleteAndNeverContactsRemoteService() throws Exception {
        try(var f=new MemoryFixture(root);var server=new Server();var backend=backend(f,new Ledger(),server)){
            server.mode="disconnect";String original="CORE😀".repeat(2500);assertTrue(backend.save(original,MemoryEntry.Scope.CORE,f.context("writer")).confirmed());assertEquals(original,backend.load(MemoryEntry.Scope.CORE,f.context("reader")).getFirst().rawContent());assertEquals(0,server.adds.get()+server.gets.get()+server.searches.get());
        }
    }
    @Test void emptyMissingForeignAndDisconnectedProjectionsNeverBecomeFacts() throws Exception {
        for(String mode:List.of("empty","missing","foreign","disconnect","read-reject"))try(var f=new MemoryFixture(root.resolve(mode));var server=new Server()){
            var ledger=new Ledger();try(var backend=backend(f,ledger,server)){
                server.mode=mode;var result=backend.save("explicit fact",MemoryEntry.Scope.ARCHIVAL,f.context("writer"));assertFalse(result.confirmed());assertTrue(result.sourceStored());assertEquals(1,server.adds.get());assertTrue(backend.load(MemoryEntry.Scope.ARCHIVAL,f.context("reader")).isEmpty());assertNotNull(ledger.find(f.id,result.sourceEntryId()));
                if(mode.equals("disconnect")||mode.equals("read-reject"))assertEquals(MemoryEntry.WriteStatus.UNKNOWN,result.writeStatus());
            }
        }
    }
    @Test void deadlineClosesReadbackAdmissionAndRetainsUnknownSource() throws Exception {
        try(var f=new MemoryFixture(root);var server=new Server()){
            var ledger=new Ledger();try(var backend=backend(f,ledger,server)){
                var ctx=f.context("writer");server.afterAdd=ctx::stop;var result=backend.save("explicit fact",MemoryEntry.Scope.ARCHIVAL,ctx);
                assertEquals(MemoryEntry.WriteStatus.UNKNOWN,result.writeStatus());assertEquals(1,server.adds.get());assertEquals(0,server.gets.get());assertTrue(backend.load(MemoryEntry.Scope.ARCHIVAL,f.context("reader")).isEmpty());
            }
        }
    }
    @Test void searchDropsForeignLowScoreAndUnconfirmedSources() throws Exception {
        try(var f=new MemoryFixture(root);var server=new Server();var backend=backend(f,new Ledger(),server)){
            assertTrue(backend.save("confirmed",MemoryEntry.Scope.ARCHIVAL,f.context("writer")).confirmed());
            @SuppressWarnings("unchecked") var item=(Map<String,Object>)server.projections.values().iterator().next();
            item.put("score",0.2);assertTrue(backend.recall("query",f.context("reader")).isEmpty());item.put("score",0.9);item.put("user_id",UUID.randomUUID().toString());assertTrue(backend.recall("query",f.context("reader")).isEmpty());
            item.put("user_id",f.id.toString());item.put("metadata",Map.of("oryx_scope","ARCHIVAL","source_entry_id",UUID.randomUUID().toString()));assertTrue(backend.recall("query",f.context("reader")).isEmpty());
        }
    }
    @Test void realHttpTimeoutLeavesUnknownAndNeverRetriesOrReadsBack() throws Exception {
        try(var f=new MemoryFixture(root);var server=new Server();var backend=new Mem0MemoryStore(f.id,new Ledger(),new Mem0Client(new Mem0Config(server.endpoint(),"${KEY}",1,10,0.7),"fixture-key"))){
            server.afterAdd=()->{try{Thread.sleep(1500);}catch(InterruptedException e){Thread.currentThread().interrupt();}};
            var result=backend.save("fact",MemoryEntry.Scope.ARCHIVAL,f.context("writer"));assertEquals(MemoryEntry.WriteStatus.UNKNOWN,result.writeStatus());assertEquals(1,server.adds.get());assertEquals(0,server.gets.get());
        }
    }
}
