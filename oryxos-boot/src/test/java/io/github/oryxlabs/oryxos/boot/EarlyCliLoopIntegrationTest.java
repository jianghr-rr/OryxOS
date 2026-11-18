package io.github.oryxlabs.oryxos.boot;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import io.github.oryxlabs.oryxos.boot.runtime.BootRuntimeLauncher;
import io.github.oryxlabs.oryxos.boot.support.EarlyRuntimeConfiguration;
import io.github.oryxlabs.oryxos.core.execution.Execution;
import io.github.oryxlabs.oryxos.core.workspace.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@Tag("us2-loop")
class EarlyCliLoopIntegrationTest {
    @TempDir Path root;
    @Test void cliRunsRealAdapterAndWeatherToolThenResumesSameSession() throws Exception {
        var json=new ObjectMapper();var weatherCalls=new AtomicInteger();var modelCalls=new CopyOnWriteArrayList<JsonNode>();
        var weather=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var model=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            weather.setExecutor(executor);model.setExecutor(executor);String weatherUrl="http://127.0.0.1:"+weather.getAddress().getPort()+"/weather";
            weather.createContext("/weather",e->{weatherCalls.incrementAndGet();byte[] body="Beijing sunny 23C".getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();});
            model.createContext("/chat/completions",e->{
                var request=json.readTree(e.getRequestBody());modelCalls.add(request);int n=modelCalls.size();String response;
                if(n==1){String args=json.writeValueAsString(Map.of("url",weatherUrl));response=json.writeValueAsString(Map.of("id","first","model","fixture-actual","choices",List.of(Map.of("index",0,"finish_reason","tool_calls","message",Map.of("role","assistant","content","checking","reasoning_content","private fixture reasoning","tool_calls",List.of(Map.of("id","weather-call","type","function","function",Map.of("name","http_get","arguments",args))))))));}
                else response=json.writeValueAsString(Map.of("id","final-"+n,"model","fixture-actual","choices",List.of(Map.of("index",0,"finish_reason","stop","message",Map.of("role","assistant","content",n==2?"Beijing sunny 23C":"same session confirmed")))));
                byte[] bytes=response.getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();
            });weather.start();model.start();
            var workspace=new WorkspaceHandle(root);new WorkspaceInitializer().initialize(workspace,Map.of("runtime.yaml","schema_version: 1\nproviders:\n  local:\n    protocol: openai_chat_completions\n    endpoint: http://127.0.0.1:"+model.getAddress().getPort()+"\n    completions_path: /chat/completions\n    api_key: '${LOCAL_KEY}'\ntools:\n  http:\n    allowed_targets: [{scheme: http, host: 127.0.0.1, port: "+weather.getAddress().getPort()+"}]\n"));
            Files.writeString(root.resolve("secrets.local.yaml"),"LOCAL_KEY: fixture-private-key\n");Files.setPosixFilePermissions(root.resolve("secrets.local.yaml"),PosixFilePermissions.fromString("rw-------"));
            Path agent=root.resolve("agents/weather/AGENT.md");Files.createDirectories(agent.getParent());Files.writeString(agent,"---\nschema_version: 1\nname: weather\nprovider: {name: local, model: configured}\ntools: [http_get]\nbootstrap: [USER.md]\n---\nUse the weather tool.\n");Files.writeString(root.resolve("USER.md"),"Be concise.");
            var output=new StringWriter();var writer=new PrintWriter(output,true);
            var launcher=new BootRuntimeLauncher(Map.of("spring.profiles.active","us2-local","spring.main.banner-mode","off"),EarlyRuntimeConfiguration.class);
            int code=OryxOsApplication.execute(new String[]{"--root",root.toString(),"chat","--profile","weather","--user","alice"},launcher,new StringReader("weather\ncontinue\n/quit\n"),writer,writer);
            assertEquals(0,code,output.toString());assertTrue(output.toString().contains("Beijing sunny 23C"));assertTrue(output.toString().contains("same session confirmed"));
            assertEquals(3,modelCalls.size());assertEquals(1,weatherCalls.get());
            var second=modelCalls.get(1).path("messages");assertEquals("weather-call",second.get(3).path("tool_call_id").asText());assertEquals("private fixture reasoning",second.get(2).path("reasoning_content").asText());
            var third=modelCalls.get(2).path("messages");assertEquals(6,third.size());assertEquals(1,third.findValuesAsText("content").stream().filter(s->s.equals("weather")).count());
            assertEquals(1,EarlyRuntimeConfiguration.lastSessions.allSessions().size());assertEquals(2,EarlyRuntimeConfiguration.lastExecutions.all().size());
            assertTrue(EarlyRuntimeConfiguration.lastExecutions.all().stream().allMatch(e->e.status()==Execution.Status.SUCCEEDED));assertEquals(6,EarlyRuntimeConfiguration.lastAudit.models.size());assertEquals(2,EarlyRuntimeConfiguration.lastAudit.tools.size());
            assertFalse(EarlyRuntimeConfiguration.lastAudit.models.toString().contains("private fixture reasoning"));assertFalse(output.toString().contains("fixture-private-key"));assertFalse(WorkspaceProcessLock.isRunning(workspace));
        }finally{model.stop(0);weather.stop(0);}
    }
    @Test void defaultDistributionDoesNotFallbackToTemporaryStores() throws Exception {
        var workspace=new WorkspaceHandle(root);new WorkspaceInitializer().initialize(workspace,Map.of("runtime.yaml","schema_version: 1\nproviders: {}\n"));
        var text=new StringWriter();var out=new PrintWriter(text,true);
        int code=OryxOsApplication.execute(new String[]{"--root",root.toString(),"chat","--profile","missing","--user","alice"},new BootRuntimeLauncher(),new StringReader("hello\n/quit\n"),out,out);
        assertEquals(3,code,text.toString());assertTrue(text.toString().contains("AGENT_UNAVAILABLE"));
        assertTrue(java.nio.file.Files.isRegularFile(workspace.database()),"The default distribution now uses the real SQLite stores");assertFalse(WorkspaceProcessLock.isRunning(workspace));
    }
}
