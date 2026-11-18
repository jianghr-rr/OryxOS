package io.github.oryxlabs.oryxos.core.session;

import io.github.oryxlabs.oryxos.core.agent.AgentProfile;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.execution.*;
import io.github.oryxlabs.oryxos.core.model.ChatMessage;
import io.github.oryxlabs.oryxos.core.react.LoopFixture;
import io.github.oryxlabs.oryxos.core.tool.ToolResult;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.oryxlabs.oryxos.core.react.LoopFixture.call;

@Tag("us2-loop")
class SessionRuntimeContractTest {
    @TempDir Path root;
    @Test void identityAndCreationModelAreCheckedEvenForEmptySessions() throws Exception {
        try(var f=new LoopFixture(root,10)) {
            var empty=f.sessions.acquire(null,"cli","user",f.profile,Session.Kind.CONVERSATION);
            var id=empty.session().id();empty.close();
            assertNotEquals(id,f.lease.session().id());
            for(var identity:List.of(List.of("http","user"),List.of("cli","other")))
                assertEquals("SESSION_IDENTITY_MISMATCH",assertThrows(OryxError.class,()->f.sessions.acquire(id,identity.get(0),identity.get(1),f.profile,Session.Kind.CONVERSATION)).code());
            var p=f.profile;
            var changed=new AgentProfile(p.name(),p.description(),p.providerName(),"different-model",p.tools(),p.mcpServers(),p.bootstrap(),p.notifications(),p.schedules(),p.maxIterations(),p.maxHistoryTurns(),p.definition(),p.configRevision(),p.bodyRevision());
            assertEquals("SESSION_MODEL_CHANGED",assertThrows(OryxError.class,()->f.sessions.acquire(id,"cli","user",changed,Session.Kind.CONVERSATION)).code());
            var other=new AgentProfile("other",p.description(),p.providerName(),p.model(),p.tools(),p.mcpServers(),p.bootstrap(),p.notifications(),p.schedules(),10,20,p.definition(),"r","b");
            assertEquals("SESSION_IDENTITY_MISMATCH",assertThrows(OryxError.class,()->f.sessions.acquire(id,"cli","user",other,Session.Kind.CONVERSATION)).code());
        }
    }
    @Test void busyLeaseRejectsWithoutQueueAndAllowsReuseOnlyAfterClose() throws Exception {
        try(var f=new LoopFixture(root,10)) {
            var id=f.lease.session().id();
            assertEquals("SESSION_BUSY",assertThrows(OryxError.class,()->f.sessions.acquire(id,"cli","user",f.profile,Session.Kind.CONVERSATION)).code());
            f.lease.close();try(var next=f.sessions.acquire(id,"cli","user",f.profile,Session.Kind.CONVERSATION)){assertEquals(id,next.session().id());}
        }
    }
    @Test void historySelectsCompleteTurnsOnceAndNeverDeletesOrIncludesCurrent() throws Exception {
        try(var f=new LoopFixture(root,10)) {
            for(int i=0;i<25;i++){
                var turn=UUID.randomUUID();var exec=UUID.randomUUID();var call=call("t"+i,"first","{}");
                f.lease.append(exec,turn,ChatMessage.user("turn"+i),null);
                f.lease.append(exec,turn,LoopFixture.reply(null,call).assistantMessage(),null);
                f.lease.append(exec,turn,ChatMessage.tool(call,"ok"),ToolResult.Outcome.SUCCESS);
                f.lease.append(exec,turn,LoopFixture.reply("done").assistantMessage(),null);f.lease.finish(turn);
            }
            int stored=f.store.messages.size();var current=f.context.execution().turnId();
            var history=f.sessions.history(f.lease.session().id(),current,20);
            assertEquals(80,history.recent().size());assertEquals(1,history.current().size());
            assertEquals("turn5",history.recent().getFirst().content());
            assertEquals(20,history.recent().stream().filter(m->m.role()==ChatMessage.Role.USER).count());
            assertEquals(8,f.sessions.history(f.lease.session().id(),current,2).recent().size());assertEquals(stored,f.store.messages.size());
        }
    }
    @Test void incompleteToolPairsFailClosed() throws Exception {
        try(var f=new LoopFixture(root,10)) {
            var turn=f.context.execution().turnId();
            f.lease.append(f.context.execution().id(),turn,LoopFixture.reply(null,call("missing","first","{}")).assistantMessage(),null);
            assertEquals("HISTORY_INCOMPLETE",assertThrows(OryxError.class,()->f.lease.finish(turn)).code());
            assertEquals("HISTORY_INCOMPLETE",assertThrows(OryxError.class,()->f.sessions.history(f.lease.session().id(),turn,20)).code());
        }
    }
    @Test void activeLeaseReusesPriorTurnHistoryAcrossIterationsWhileReflectingAppendedCurrentMessages() throws Exception {
        try(var f=new LoopFixture(root,10)) {
            var historyCalls=new AtomicInteger();
            var sequenceCalls=new AtomicInteger();
            var failFinish=new java.util.concurrent.atomic.AtomicBoolean();
            var countingStore=new LoopFixture.Store(){
                @Override public SessionHistoryPort.History history(UUID id,UUID current,int maxTurns) {
                    historyCalls.incrementAndGet();
                    return super.history(id,current,maxTurns);
                }
                @Override public long lastSequence(UUID id) {
                    sequenceCalls.incrementAndGet();
                    return super.lastSequence(id);
                }
                @Override public synchronized void finishTurn(UUID session,UUID turn) {
                    if(failFinish.compareAndSet(true,false)) throw new IllegalStateException("injected finish failure");
                    super.finishTurn(session,turn);
                }
            };
            var seederSessions=new SessionManager(countingStore,f.clock);
            var executions=new ExecutionStore(){
                public void save(Execution.Snapshot execution){}
                public Execution.Snapshot find(UUID executionId){return null;}
            };
            UUID sid;
            var initial=seederSessions.acquireForExecution(null,"cli","user",f.profile,Session.Kind.CONVERSATION);
            sid=initial.session().id();
            initial.close();
            for(int i=0;i<2;i++) {
                try(var lease=seederSessions.acquireForExecution(sid,"cli","user",f.profile,Session.Kind.CONVERSATION)) {
                    var execId=UUID.randomUUID();var turnId=UUID.randomUUID();
                    var snap=new Execution(execId,sid,turnId,ProcessRequest.manual("cli","user","demo",sid,"seed"+i),"rev",f.clock.instant(),f.clock.instant().plusSeconds(60),f.context.execution().snapshot(0).timezone(),f.context.execution().snapshot(0).toolNames()).snapshot(0);
                    lease.begin(snap,"seed"+i,executions);
                    lease.append(execId,turnId,LoopFixture.reply("ok"+i).assistantMessage(),null);
                    lease.finish(turnId);
                }
            }
            // Simulate cold SessionManager after restart for Turn 3, then warm cross-turn reuse on Turn 4.
            var sessions=new SessionManager(countingStore,f.clock);
            try(var lease=sessions.acquireForExecution(sid,"cli","user",f.profile,Session.Kind.CONVERSATION)) {
                var execId=UUID.randomUUID();var turnId=UUID.randomUUID();
                var snap=new Execution(execId,sid,turnId,ProcessRequest.manual("cli","user","demo",sid,"current"),"rev",f.clock.instant(),f.clock.instant().plusSeconds(60),f.context.execution().snapshot(0).timezone(),f.context.execution().snapshot(0).toolNames()).snapshot(0);
                historyCalls.set(0);sequenceCalls.set(0);
                lease.begin(snap,"current",executions);
                var iter1=sessions.history(sid,turnId,20);
                assertEquals(4,iter1.recent().size());
                assertEquals(1,iter1.current().size());
                var toolCall=call("tc-1","first","{}");
                lease.append(execId,turnId,LoopFixture.reply(null,toolCall).assistantMessage(),null);
                lease.append(execId,turnId,ChatMessage.tool(toolCall,"sunny"),ToolResult.Outcome.SUCCESS);
                var iter2=sessions.history(sid,turnId,20);
                assertEquals(1,historyCalls.get(),"Cold lease should query durable history once per turn");
                assertEquals(1,sequenceCalls.get(),"Cold lease should query lastSequence once");
                assertEquals(4,iter2.recent().size());
                assertEquals(3,iter2.current().size());
                assertEquals("current",iter2.current().get(0).content());
                assertEquals(ChatMessage.Role.TOOL,iter2.current().get(2).role());
                lease.append(execId,turnId,LoopFixture.reply("done").assistantMessage(),null);
                lease.finish(turnId);
            }
            try(var warmLease=sessions.acquireForExecution(sid,"cli","user",f.profile,Session.Kind.CONVERSATION)) {
                var execId4=UUID.randomUUID();var turnId4=UUID.randomUUID();
                var snap4=new Execution(execId4,sid,turnId4,ProcessRequest.manual("cli","user","demo",sid,"warm"),"rev",f.clock.instant(),f.clock.instant().plusSeconds(60),f.context.execution().snapshot(0).timezone(),f.context.execution().snapshot(0).toolNames()).snapshot(0);
                historyCalls.set(0);sequenceCalls.set(0);
                warmLease.begin(snap4,"warm",executions);
                var warmHist=sessions.history(sid,turnId4,20);
                assertEquals(0,historyCalls.get(),"Warm SessionManager must reuse completed-turn cache across turns");
                assertEquals(0,sequenceCalls.get(),"Warm SessionManager must reuse sequence across turns");
                assertEquals(8,warmHist.recent().size());
                assertEquals("done",warmHist.recent().getLast().content());
                assertEquals(1,warmHist.current().size());
                assertEquals("warm",warmHist.current().getFirst().content());
                warmLease.append(execId4,turnId4,LoopFixture.reply("warm-fail").assistantMessage(),null);
                failFinish.set(true);
                assertThrows(OryxError.class,()->warmLease.finish(turnId4));
            }
            // Error invalidation must force fresh durable sequence/history read on subsequent acquisition.
            try(var afterFailure=sessions.acquireForExecution(sid,"cli","user",f.profile,Session.Kind.CONVERSATION)) {
                var execId5=UUID.randomUUID();var turnId5=UUID.randomUUID();
                var snap5=new Execution(execId5,sid,turnId5,ProcessRequest.manual("cli","user","demo",sid,"recover"),"rev",f.clock.instant(),f.clock.instant().plusSeconds(60),f.context.execution().snapshot(0).timezone(),f.context.execution().snapshot(0).toolNames()).snapshot(0);
                historyCalls.set(0);sequenceCalls.set(0);
                afterFailure.begin(snap5,"recover",executions);
                afterFailure.append(execId5,turnId5,LoopFixture.reply("ok").assistantMessage(),null);
                afterFailure.finish(turnId5);
                sessions.history(sid,turnId5,20);
                assertTrue(sequenceCalls.get()>=1,"Invalidated hot state must re-read sequence");
            }
            sessions.archive(sid,"cli","user","demo");
            assertEquals("SESSION_ARCHIVED",assertThrows(OryxError.class,()->sessions.acquireForExecution(sid,"cli","user",f.profile,Session.Kind.CONVERSATION)).code());
        }
    }
}
