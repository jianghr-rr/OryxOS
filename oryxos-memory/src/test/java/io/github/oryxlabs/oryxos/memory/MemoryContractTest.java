package io.github.oryxlabs.oryxos.memory;
import io.github.oryxlabs.oryxos.core.memory.*;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.memory.backend.MarkdownMemoryStore;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
@Tag("us3-memory")
class MemoryContractTest {
    @TempDir Path root;
    @Test void markdownSharesOriginalCoreAndArchivesAcrossAgentsAndReopens() throws Exception {
        try(var f=new MemoryFixture(root)){
            var store=new MarkdownMemoryStore(f.workspace,f.id,f.lock);String core="核心😀".repeat(2000);
            assertTrue(store.save(core,MemoryEntry.Scope.CORE,f.context("one")).confirmed());
            assertTrue(store.save("我关注 AI 芯片",MemoryEntry.Scope.ARCHIVAL,f.context("one")).confirmed());
            var reopened=new MarkdownMemoryStore(f.workspace,f.id,f.lock);
            assertEquals(core,reopened.load(MemoryEntry.Scope.CORE,f.context("two")).getFirst().rawContent());
            assertEquals("one",reopened.load(MemoryEntry.Scope.ARCHIVAL,f.context("two")).getFirst().sourceAgentName());
        }
    }
    @Test void literalRecallFoldsUnicodeWithoutWildcardsOrCoreLeakage() throws Exception {
        try(var f=new MemoryFixture(root)){
            var store=new MarkdownMemoryStore(f.workspace,f.id,f.lock);var ctx=f.context("one");
            store.save("CORE_ONLY secret",MemoryEntry.Scope.CORE,ctx);store.save("Straße Café A_50%",MemoryEntry.Scope.ARCHIVAL,ctx);
            for(String query:List.of("STRASSE","CAFE\u0301","a_50%"))assertEquals(1,store.recall(query,ctx).size());
            for(String query:List.of("CORE_ONLY","diet","A_5_"))assertTrue(store.recall(query,ctx).isEmpty());
            assertThrows(OryxError.class,()->store.recall(" ",ctx));
        }
    }
    @Test void concurrentConfirmedWritesSurviveAndCorruptionIsNotEmpty() throws Exception {
        try(var f=new MemoryFixture(root)){
            var store=new MarkdownMemoryStore(f.workspace,f.id,f.lock);
            try(var workers=Executors.newVirtualThreadPerTaskExecutor()){
                var jobs=new ArrayList<Future<WriteReceipt>>();for(int i=0;i<20;i++){int n=i;jobs.add(workers.submit(()->new MarkdownMemoryStore(f.workspace,f.id,f.lock).save("fact "+n,MemoryEntry.Scope.ARCHIVAL,f.context("a"+n))));}
                for(var job:jobs)assertTrue(job.get().confirmed());
            }
            assertEquals(20,store.load(MemoryEntry.Scope.ARCHIVAL,f.context("reader")).size());
            var file=root.resolve("memory/MEMORY.md");Files.writeString(file,"damaged");
            assertThrows(OryxError.class,()->store.load(MemoryEntry.Scope.CORE,f.context("reader")));
            assertThrows(OryxError.class,()->store.save("must not overwrite",MemoryEntry.Scope.ARCHIVAL,f.context("reader")));assertEquals("damaged",Files.readString(file));
        }
    }
    @Test void facadeLoadsHistoryOnceKeepsFullCoreAndBoundsOnlyArchiveCodePoints() throws Exception {
        try(var f=new MemoryFixture(root)){
            var store=new MarkdownMemoryStore(f.workspace,f.id,f.lock);var ctx=f.context("writer");String core="核心😀".repeat(3000);
            store.save(core,MemoryEntry.Scope.CORE,ctx);store.save("😀😀😀😀😀tail",MemoryEntry.Scope.ARCHIVAL,ctx);
            var reads=new java.util.concurrent.atomic.AtomicInteger();
            var facade=new MemoryService((session,turn,max)->{reads.incrementAndGet();return new io.github.oryxlabs.oryxos.core.session.SessionHistoryPort.History(List.of(io.github.oryxlabs.oryxos.core.model.ChatMessage.user("old")),List.of(io.github.oryxlabs.oryxos.core.model.ChatMessage.user("current")));},store,3);
            var view=facade.load(ctx);assertEquals(1,reads.get());assertEquals(core,view.core());assertEquals("😀😀😀",view.archival());assertEquals(1,view.recent().size());assertEquals(1,view.current().size());
            assertEquals("😀😀😀😀😀tail",store.load(MemoryEntry.Scope.ARCHIVAL,ctx).getFirst().rawContent());
        }
    }
    @Test void explicitSaveAllowsOrdinaryUrlsButRejectsRegisteredCredentialsBeforeWrite() throws Exception {
        try(var f=new MemoryFixture(root)){
            var store=new MarkdownMemoryStore(f.workspace,f.id,f.lock);var tool=new io.github.oryxlabs.oryxos.memory.tool.SaveMemoryTool(store);var ctx=f.context("writer");
            var fact=Map.<String,Object>of("content","Reference https://example.org/project");assertDoesNotThrow(()->tool.authorize(fact,ctx));assertEquals(io.github.oryxlabs.oryxos.core.tool.ToolResult.Outcome.SUCCESS,tool.execute(fact,ctx).outcome());
            ctx.redactor().register("private-fixture-key");assertThrows(OryxError.class,()->tool.authorize(Map.of("content","private-fixture-key"),ctx));assertEquals(1,store.load(MemoryEntry.Scope.ARCHIVAL,ctx).size());
        }
    }
    @Test void corruptAndEscapingMarkdownFailWithoutChangingFiles() throws Exception {
        try(var f=new MemoryFixture(root.resolve("workspace"))){
            var store=new MarkdownMemoryStore(f.workspace,f.id,f.lock);var tool=new io.github.oryxlabs.oryxos.memory.tool.SaveMemoryTool(store);var ctx=f.context("writer");var args=Map.<String,Object>of("content","do not write");
            Files.createDirectories(f.workspace.root().resolve("memory"));var file=f.workspace.root().resolve("memory/MEMORY.md");Files.writeString(file,"corrupt original");
            var result=tool.execute(args,ctx);assertEquals(io.github.oryxlabs.oryxos.core.tool.ToolResult.Outcome.ERROR,result.outcome());assertEquals(io.github.oryxlabs.oryxos.core.tool.ToolResult.SideEffectState.NONE,result.sideEffectState());assertEquals("corrupt original",Files.readString(file));
            var outside=root.resolve("outside.md");Files.writeString(outside,"untouched");Files.delete(file);Files.createSymbolicLink(file,outside);
            assertEquals("TARGET_DENIED",assertThrows(OryxError.class,()->tool.authorize(args,ctx)).code());assertEquals("untouched",Files.readString(outside));
        }
    }

}
