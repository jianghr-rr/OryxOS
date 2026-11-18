package io.github.oryxlabs.oryxos.core.execution;

import io.github.oryxlabs.oryxos.core.model.ToolNameMap;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/** One locked state machine; a late result can add facts but never reopen a terminal execution. */
public final class Execution {
    public enum Status { RUNNING, SUCCEEDED, PARTIAL, FAILED, TIMED_OUT, PARTIAL_RECORDING_FAILURE, INTERRUPTED }
    public enum RecordingStatus { COMPLETE, INCOMPLETE, UNKNOWN }
    public record Snapshot(UUID id,UUID sessionId,UUID turnId,String agentName,String channel,String userId,
                           ProcessRequest.Trigger triggerType,String scheduleId,String configRevision,
                           Instant startedAt,Instant deadlineAt,Instant finishedAt,Status status,String stopReason,
                           RecordingStatus recordingStatus,int inFlightCount,String finalContent,String errorCode,
                           boolean hasToolErrors,boolean hasUnresolvedToolErrors,ZoneId timezone,ToolNameMap toolNames) { }
    private final ReentrantLock lock = new ReentrantLock();
    private final UUID id,sessionId,turnId;
    private final ProcessRequest request;
    private final String revision;
    private final Instant startedAt,deadlineAt;
    private final ZoneId timezone;
    private final ToolNameMap names;
    private Status status=Status.RUNNING;
    private RecordingStatus recording=RecordingStatus.COMPLETE;
    private String reason,content,error;
    private Instant finished;
    private boolean toolErrors,unresolved;
    public Execution(UUID id,UUID sessionId,UUID turnId,ProcessRequest request,String revision,Instant start,Instant deadline,ZoneId zone,ToolNameMap names) {
        this.id=id;this.sessionId=sessionId;this.turnId=turnId;this.request=request;this.revision=revision;startedAt=start;deadlineAt=deadline;timezone=zone;this.names=names;
    }
    public void withLock(Runnable action) {
        lock.lock();
        try { action.run(); } finally { lock.unlock(); }
    }
    public void finish(Status next,String stop,String text,String code,Instant at) {
        lock.lock();
        try {
            if (status!=Status.RUNNING) return;
            status=next;reason=stop;content=text;error=code;finished=at;
        } finally { lock.unlock(); }
    }
    public void recordingFailed(Instant now,boolean beforeDispatch) {
        lock.lock();
        try {
            recording=RecordingStatus.INCOMPLETE;
            if (!"DEADLINE".equals(reason)) reason="RECORDING_FAILURE";
            status=beforeDispatch&&!"DEADLINE".equals(reason)?Status.FAILED:Status.PARTIAL_RECORDING_FAILURE;
            error="RECORDING_FAILURE"; content="结果记录不完整，请先核查执行记录，不要自动重放。"; finished=now;
        } finally { lock.unlock(); }
    }
    public void toolErrors(boolean any,boolean unresolved) {
        lock.lock();
        try { this.toolErrors=any;this.unresolved=unresolved; } finally { lock.unlock(); }
    }
    public void recordingPending() {
        lock.lock();
        try { if(recording!=RecordingStatus.INCOMPLETE)recording=RecordingStatus.UNKNOWN; } finally { lock.unlock(); }
    }
    public void recordingComplete() {
        lock.lock();
        try { if(recording!=RecordingStatus.INCOMPLETE)recording=RecordingStatus.COMPLETE; } finally { lock.unlock(); }
    }
    public Snapshot snapshot(int inFlight) {
        lock.lock();
        try {
            return new Snapshot(id,sessionId,turnId,request.agentName(),request.channel(),request.userId(),request.trigger(),request.scheduleId(),revision,
                    startedAt,deadlineAt,finished,status,reason,recording,inFlight,content,error,toolErrors,unresolved,timezone,names);
        } finally { lock.unlock(); }
    }
    public UUID id(){return id;} public UUID sessionId(){return sessionId;} public UUID turnId(){return turnId;}
    public Status status() {
        lock.lock();
        try { return status; } finally { lock.unlock(); }
    }
}
