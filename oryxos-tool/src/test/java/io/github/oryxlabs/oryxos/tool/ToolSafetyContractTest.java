package io.github.oryxlabs.oryxos.tool;

import com.sun.net.httpserver.HttpServer;
import io.github.oryxlabs.oryxos.core.agent.AgentProfile;
import io.github.oryxlabs.oryxos.core.audit.*;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.execution.*;
import io.github.oryxlabs.oryxos.core.model.*;
import io.github.oryxlabs.oryxos.core.runtime.*;
import io.github.oryxlabs.oryxos.core.security.Redactor;
import io.github.oryxlabs.oryxos.core.tool.*;
import io.github.oryxlabs.oryxos.core.workspace.WorkspaceHandle;
import io.github.oryxlabs.oryxos.tool.http.*;
import io.github.oryxlabs.oryxos.tool.policy.ToolPolicy;
import io.github.oryxlabs.oryxos.tool.validation.ToolArgumentValidator;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@Tag("us2-loop")
class ToolSafetyContractTest {
    @TempDir Path root;
    final List<ToolCallRecord> records=new ArrayList<>();
    final AuditSink audit=new AuditSink(){public void modelCall(ModelCallRecord r){}public void toolCall(ToolCallRecord r){records.add(r);}};
    static final class Server implements AutoCloseable {
        final HttpServer http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();final AtomicInteger count=new AtomicInteger();
        volatile int status=200;volatile String body="sunny";volatile String location;volatile boolean disconnect;volatile long delay;
        Server() throws Exception{
            http.setExecutor(executor);http.createContext("/",e->{try(e){count.incrementAndGet();e.getRequestBody().readAllBytes();if(disconnect)return;
                if(location!=null)e.getResponseHeaders().set("Location",location);
                byte[] bytes=body.getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(status,bytes.length);
                if(delay>0){e.getResponseBody().write(bytes[0]);e.getResponseBody().flush();try{Thread.sleep(delay);}catch(InterruptedException x){Thread.currentThread().interrupt();}e.getResponseBody().write(bytes,1,bytes.length-1);}
                else e.getResponseBody().write(bytes);
            }});http.start();
        }
        String url(){return "http://127.0.0.1:"+http.getAddress().getPort()+"/weather";}
        HttpTargetPolicy.Target target(){return new HttpTargetPolicy.Target("http","127.0.0.1",http.getAddress().getPort());}
        public void close(){http.stop(0);executor.shutdownNow();}
    }
    ExecutionContext context(List<String> allowed,Duration budget){
        var time=TimeSource.system();var gate=new RuntimeGate();gate.readyAtFirstStartup();var deadline=new Deadline(time,budget);var permit=gate.execution(deadline);
        var profile=new AgentProfile("demo","","p","m",allowed,List.of(),List.of(),Map.of(),List.of(),10,20,root.resolve("AGENT.md"),"rev","body");
        var execution=new Execution(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),ProcessRequest.manual("cli","u","demo",null,"weather"),"rev",time.instant(),deadline.deadlineAt(),ZoneId.of("Asia/Shanghai"),ToolNameMap.empty());
        return new ExecutionContext(new WorkspaceHandle(root),profile,execution,deadline,gate,permit,new ActionDispatcher(permit),time,new Redactor());
    }
    @Test void schemasAreOfflineBoundedAndReportFieldsWithoutValues() throws Exception {
        try(var server=new Server()){
            var validator=new ToolArgumentValidator();
            for(String schema:List.of("{\"$ref\":\""+server.url()+"\"}","{\"properties\":{\"x\":{\"$ref\":\"file:///etc/passwd\"}}}","{\"$schema\":\""+server.url()+"\"}","{\"$id\":\""+server.url()+"\",\"$ref\":\"#/a\"}","{\"description\":\""+"x".repeat(65536)+"\"}"))assertThrows(OryxError.class,()->validator.register(schema));
            assertEquals(0,server.count.get());
            String schema="{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"integer\",\"minimum\":0}},\"required\":[\"value\"]}";
            var wrong=validator.validate(schema,"{\"value\":\"CANARY-secret\"}");assertEquals(Set.of("/value"),wrong.errorPointers());assertFalse(wrong.safeMessage().contains("CANARY"));
            assertEquals(Set.of("/value"),validator.validate(schema,"{}").errorPointers());
            assertFalse(validator.validate(schema,"{\"value\":1,\"value\":2}").parseable());
            assertFalse(validator.validate(schema,"{\"value\":"+"[".repeat(65)+"0"+"]".repeat(65)+"}").parseable());
            assertFalse(validator.validate(schema,"{} {} ").parseable());assertTrue(validator.validate(schema,"{\"value\":1}").valid());
            validator.register("{\"$defs\":{\"x\":{\"type\":\"integer\"}},\"type\":\"object\",\"properties\":{\"x\":{\"$ref\":\"#/$defs/x\"}}}");
            assertEquals(0,server.count.get());
        }
    }
    @Test void permissionAndRedirectBoundariesHaveNoUnauthorizedIo() throws Exception {
        try(var allowed=new Server();var denied=new Server();var tool=new HttpGetTool(new HttpTargetPolicy(List.of(allowed.target())),5,2,65536)){
            var registry=new ToolRegistry(List.of(tool),new ToolArgumentValidator(),new ToolPolicy());var executor=new ToolExecutor(registry,audit,d->{});
            var call=new ToolCall("c","http_get","http_get","{\"url\":\""+allowed.url()+"\"}");
            assertEquals(ToolResult.Outcome.DENIED,executor.execute(context(List.of(),Duration.ofSeconds(5)),call).result().outcome());assertEquals(0,allowed.count.get());
            allowed.status=302;allowed.location=denied.url();
            var result=executor.execute(context(List.of("http_get"),Duration.ofSeconds(5)),call).result();
            assertEquals(ToolResult.Outcome.DENIED,result.outcome());assertEquals(1,allowed.count.get());assertEquals(0,denied.count.get());
            assertThrows(OryxError.class,()->new HttpTargetPolicy(List.of(allowed.target())).authorize(allowed.url().replace("http://","http://u:p@")));
            var wildcard=new HttpTargetPolicy(List.of(new HttpTargetPolicy.Target("https","*.example.com",443)));
            assertDoesNotThrow(()->wildcard.authorize("https://api.example.com/path"));
            for(String url:List.of("https://example.com/path","https://example.com.evil/path","https://api.example.com:444/path"))assertThrows(OryxError.class,()->wildcard.authorize(url));
        }
    }
    @Test void readsAreBoundedAndEachHopHasOneTransportAttempt() throws Exception {
        try(var server=new Server();var tool=new HttpGetTool(new HttpTargetPolicy(List.of(server.target())),5,2,65536)){
            var ctx=context(List.of("http_get"),Duration.ofSeconds(5));
            assertEquals(ToolResult.Outcome.SUCCESS,tool.execute(Map.of("url",server.url()),ctx).outcome());assertEquals(1,server.count.get());
            server.disconnect=true;assertEquals(ToolResult.Outcome.ERROR,tool.execute(Map.of("url",server.url()),ctx).outcome());assertEquals(2,server.count.get(),"transport must not retry a disconnected GET");server.disconnect=false;
            server.body="x".repeat(65537);assertEquals("HTTP_RESPONSE_TOO_LARGE",tool.execute(Map.of("url",server.url()),ctx).errorCode());
            server.body="slow response";server.delay=1200;long start=System.nanoTime();
            assertEquals("HTTP_TIMEOUT",tool.execute(Map.of("url",server.url()),context(List.of("http_get"),Duration.ofMillis(150))).errorCode());
            assertTrue(Duration.ofNanos(System.nanoTime()-start).toMillis()<900);server.delay=0;
            server.status=302;server.location=server.url();int count=server.count.get();
            assertEquals("HTTP_REDIRECT_LIMIT",tool.execute(Map.of("url",server.url()),context(List.of("http_get"),Duration.ofSeconds(5))).errorCode());assertEquals(count+4,server.count.get());
        }
    }
    @Test void safeRetriesAreAuditedAndAuthenticationIsNotRetried() throws Exception {
        try(var server=new Server();var tool=new HttpGetTool(new HttpTargetPolicy(List.of(server.target())),5,2,65536)){
            server.status=503;var delays=new ArrayList<Long>();
            var registry=new ToolRegistry(List.of(tool),new ToolArgumentValidator(),new ToolPolicy());var executor=new ToolExecutor(registry,audit,d->delays.add(d.toMillis()));
            var call=new ToolCall("retry","http_get","http_get","{\"url\":\""+server.url()+"\"}");
            var result=executor.execute(context(List.of("http_get"),Duration.ofSeconds(5)),call).result();assertEquals(4,result.attemptCount());assertEquals(4,server.count.get());assertEquals(List.of(250L,500L,1000L),delays);assertEquals(8,records.size());
            server.status=401;int count=server.count.get();executor.execute(context(List.of("http_get"),Duration.ofSeconds(5)),call);assertEquals(count+1,server.count.get());
        }
    }
}
