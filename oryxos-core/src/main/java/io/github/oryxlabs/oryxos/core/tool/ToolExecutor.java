package io.github.oryxlabs.oryxos.core.tool;

import io.github.oryxlabs.oryxos.core.audit.*;
import io.github.oryxlabs.oryxos.core.execution.ExecutionContext;
import io.github.oryxlabs.oryxos.core.execution.RecordingScope;
import io.github.oryxlabs.oryxos.core.model.ToolCall;
import io.github.oryxlabs.oryxos.core.runtime.Uuids;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

public final class ToolExecutor {
    @FunctionalInterface public interface Sleeper { void sleep(Duration duration) throws InterruptedException; }
    public record Attempt(ToolCall call,ToolResult result,ToolRegistryPort.Validation validation,ToolCallRecord record) { }
    private final ToolRegistryPort registry;
    private final AuditSink audit;
    private final Sleeper sleeper;
    public ToolExecutor(ToolRegistryPort registry,AuditSink audit,Sleeper sleeper) { this.registry=registry;this.audit=audit;this.sleeper=sleeper; }
    public Attempt execute(ExecutionContext context,ToolCall call) {
        OryxTool tool=call.name()==null ? null : registry.find(call.name());
        if(tool==null) return rejected(context,call,null,ToolResult.Outcome.ERROR,"UNKNOWN_TOOL","Unknown tool; no action was executed");
        var validation=registry.validate(tool.descriptor(),call.argumentsJson());
        if(!validation.valid()) return rejected(context,call,validation,ToolResult.Outcome.ERROR,"INVALID_ARGUMENTS",validation.safeMessage());
        try { registry.authorize(tool,validation.arguments(),context); }
        catch(OryxError e){return rejected(context,call,validation,e.code().equals("INVALID_ARGUMENTS")?ToolResult.Outcome.ERROR:ToolResult.Outcome.DENIED,e.code(),e.getMessage());}
        var failed=new ArrayList<ToolCallRecord>();
        long totalStarted=context.time().nanoTime();
        for(int attempt=1;attempt<=4;attempt++) {
            Instant started=context.time().instant();UUID id=Uuids.v4();long begin=context.time().nanoTime();
            try { context.dispatcher().check(); }
            catch(OryxError e){return rejected(context,call,validation,ToolResult.Outcome.NOT_EXECUTED,e.code(),e.getMessage());}
            ToolResult result;
            var handedOff=new java.util.concurrent.atomic.AtomicBoolean();
            try {
                // Re-admit AFTER STARTED is committed: slow recording cannot authorize an action beyond deadline.
                final int attemptNumber=attempt;
                result=io.github.oryxlabs.oryxos.core.execution.RecordedActionDispatcher.call(context.dispatcher(),
                    ()->emit(context,record(context,call,id,attemptNumber,"STARTED",null,"NOT_APPLICABLE",null,null,started,null,0)),
                    ()->{handedOff.set(true);return tool.execute(validation.arguments(),context);});
                if(result==null) throw new IllegalStateException("Tool returned no result");
            } catch(OryxError error) {
                if(error.code().equals("RECORDING_FAILURE")){context.recordingFailed();throw error;}
                if(handedOff.get()&&tool.descriptor().sideEffecting())result=new ToolResult(ToolResult.Outcome.UNKNOWN,"结果不确定，请先核查。","SIDE_EFFECT_UNCERTAIN","结果不确定，请先核查。",ToolResult.RetryClass.NONE,ToolResult.SideEffectState.UNKNOWN,null,attempt,0);
                else result=ToolResult.failure(handedOff.get()?ToolResult.Outcome.ERROR:ToolResult.Outcome.NOT_EXECUTED,error.code(),error.getMessage());
            } catch(RuntimeException error) {
                result=tool.descriptor().sideEffecting()
                        ? new ToolResult(ToolResult.Outcome.UNKNOWN,"结果不确定，请先核查。","SIDE_EFFECT_UNCERTAIN","结果不确定，请先核查。",ToolResult.RetryClass.NONE,ToolResult.SideEffectState.UNKNOWN,null,attempt,0)
                        : ToolResult.failure(ToolResult.Outcome.ERROR,"TOOL_FAILED","Tool failed without a usable result");
            }
            result=safe(context,result).measured(call.id(),attempt,Math.max(0,(context.time().nanoTime()-totalStarted)/1_000_000));
            String resolution=result.outcome()==ToolResult.Outcome.SUCCESS ? "NOT_APPLICABLE" : "UNRESOLVED";
            var record=record(context,call,id,attempt,result.outcome().name(),result,resolution,null,null,started,context.time().instant(),Math.max(0,(context.time().nanoTime()-begin)/1_000_000));
            emit(context,record);
            if(result.outcome()==ToolResult.Outcome.SUCCESS) {
                for(var previous:failed) emit(context,revised(previous,"RESOLVED",null,call.id()));
                return new Attempt(call,result,validation,record);
            }
            failed.add(record);
            boolean retry=tool.descriptor().safeToRetry() && result.retryClass()==ToolResult.RetryClass.SAFE_TRANSIENT
                    && result.sideEffectState()==ToolResult.SideEffectState.NONE && result.outcome()==ToolResult.Outcome.ERROR
                    && !Set.of("HTTP_401","HTTP_403","INVALID_ARGUMENTS","TARGET_DENIED").contains(String.valueOf(result.errorCode()));
            if(!retry||attempt==4||context.deadline().expired()) return new Attempt(call,result,validation,record);
            long delay=new long[]{250,500,1000}[attempt-1];
            if(context.deadline().remainingNanos()<=Duration.ofMillis(delay).toNanos()) return new Attempt(call,result,validation,record);
            try { RecordingScope.flushCheckpoint();context.dispatcher().check();sleeper.sleep(Duration.ofMillis(delay));context.dispatcher().check(); }
            catch(InterruptedException e){Thread.currentThread().interrupt();return new Attempt(call,result,validation,record);}
            catch(OryxError e){return new Attempt(call,result,validation,record);}
        }
        throw new IllegalStateException("Unreachable attempt limit");
    }
    public Attempt notExecuted(ExecutionContext context,ToolCall call,String code,String message) {
        return rejected(context,call,null,ToolResult.Outcome.NOT_EXECUTED,code,message);
    }
    private Attempt rejected(ExecutionContext ctx,ToolCall call,ToolRegistryPort.Validation validation,ToolResult.Outcome outcome,String code,String message) {
        var result=safe(ctx,ToolResult.failure(outcome,code,message)).measured(call.id(),0,0);
        var record=record(ctx,call,Uuids.v4(),0,outcome.name(),result,"UNRESOLVED",null,null,ctx.time().instant(),ctx.time().instant(),0);
        emit(ctx,record);return new Attempt(call,result,validation,record);
    }
    public void resolution(ExecutionContext ctx,Attempt attempt,String status,String correctionOf,String resolvedBy) {
        emit(ctx,revised(attempt.record(),status,correctionOf,resolvedBy));
    }
    private ToolCallRecord revised(ToolCallRecord r,String status,String correctionOf,String resolvedBy) {
        return new ToolCallRecord(r.id(),r.executionId(),r.toolCallId(),r.toolName(),r.attempt(),r.status(),r.argumentsRedacted(),r.resultRedacted(),r.sideEffectState(),status,correctionOf,resolvedBy,r.startedAt(),r.finishedAt(),r.durationMs(),r.errorCode());
    }
    private ToolResult safe(ExecutionContext ctx,ToolResult r) {
        return new ToolResult(r.outcome(),bounded(ctx.redactor().redact(r.content())),r.errorCode(),bounded(ctx.redactor().redact(r.safeMessage())),r.retryClass(),r.sideEffectState(),r.callId(),r.attemptCount(),r.durationMs());
    }
    private String bounded(String text){return text==null ? null : text.substring(0,Math.min(65536,text.length()));}
    private ToolCallRecord record(ExecutionContext ctx,ToolCall call,UUID id,int attempt,String status,ToolResult result,String resolution,String correctionOf,String resolvedBy,Instant start,Instant end,long millis) {
        return new ToolCallRecord(id,ctx.execution().id(),call.id(),ctx.redactor().redact(call.name()==null?call.wireName():call.name()),attempt,status,
                bounded(ctx.redactor().redact(call.argumentsJson())),result==null?null:result.content(),result==null?ToolResult.SideEffectState.NONE:result.sideEffectState(),resolution,correctionOf,resolvedBy,start,end,millis,result==null?null:result.errorCode());
    }
    private void emit(ExecutionContext context,ToolCallRecord record) {
        try{audit.toolCall(record);}catch(RuntimeException e){context.recordingFailed();throw new OryxError("RECORDING_FAILURE","Tool audit could not be saved; workspace paused");}
    }
}
