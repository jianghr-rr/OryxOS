package io.github.oryxlabs.oryxos.core.react;

import io.github.oryxlabs.oryxos.core.execution.ExecutionContext;
import io.github.oryxlabs.oryxos.core.tool.*;
import java.util.*;

/** Only a single adjacent same-tool chain may change exactly the originally invalid fields. */
public final class CorrectionTracker {
    private final Set<String> unresolved=new LinkedHashSet<>();
    private final List<ToolExecutor.Attempt> chain=new ArrayList<>();
    private boolean hadErrors;
    public boolean hadErrors(){return hadErrors;}
    public boolean unresolved(){return !unresolved.isEmpty();}
    public void observe(ExecutionContext context,List<ToolExecutor.Attempt> round,ToolExecutor executor) {
        for(var a:round) {
            if(a.result().outcome()!=ToolResult.Outcome.SUCCESS) {hadErrors=true;unresolved.add(a.call().id());}
            if(a.result().attemptCount()>1) hadErrors=true;
        }
        if(round.size()!=1){chain.clear();return;}
        var next=round.getFirst();
        if(!chain.isEmpty()) {
            var first=chain.getFirst();var previous=chain.getLast();
            if(matches(first,next)) {
                executor.resolution(context,next,next.result().outcome()==ToolResult.Outcome.SUCCESS?"NOT_APPLICABLE":"UNRESOLVED",previous.call().id(),null);
                if(next.result().outcome()==ToolResult.Outcome.SUCCESS) {
                    for(int i=0;i<chain.size();i++) {
                        var old=chain.get(i);unresolved.remove(old.call().id());
                        executor.resolution(context,old,"RESOLVED",i==0?null:chain.get(i-1).call().id(),next.call().id());
                    }
                    chain.clear();return;
                }
                if(candidate(next) && next.validation().errorPointers().stream().allMatch(p->covered(p,first.validation().errorPointers()))) {chain.add(next);return;}
            }
            chain.clear();
        }
        // Existing independent failures cannot be erased by a new correction window.
        if(unresolved.size()==1 && candidate(next)) chain.add(next);
    }
    private static boolean candidate(ToolExecutor.Attempt a) {
        return "INVALID_ARGUMENTS".equals(a.result().errorCode()) && a.result().sideEffectState()==ToolResult.SideEffectState.NONE
                && a.validation()!=null && a.validation().parseable() && !a.validation().errorPointers().isEmpty() && !a.validation().errorPointers().contains("");
    }
    private static boolean matches(ToolExecutor.Attempt original,ToolExecutor.Attempt next) {
        return Objects.equals(original.call().name(),next.call().name()) && next.validation()!=null && next.validation().parseable()
                && next.result().sideEffectState()!=ToolResult.SideEffectState.UNKNOWN
                && equalOutside(original.validation().arguments(),next.validation().arguments(),"",original.validation().errorPointers());
    }
    private static boolean covered(String path,Set<String> invalid){return invalid.stream().anyMatch(p->path.equals(p)||path.startsWith(p+"/"));}
    private static boolean equalOutside(Object a,Object b,String path,Set<String> invalid) {
        if(covered(path,invalid))return true;
        if(a instanceof Map<?,?> left && b instanceof Map<?,?> right){
            var keys=new HashSet<Object>(left.keySet());keys.addAll(right.keySet());
            for(Object key:keys){String child=path+"/"+key.toString().replace("~","~0").replace("/","~1");
                if(!covered(child,invalid) && (!left.containsKey(key)||!right.containsKey(key)))return false;
                if(!equalOutside(left.get(key),right.get(key),child,invalid))return false;
            }return true;
        }
        if(a instanceof List<?> left && b instanceof List<?> right){if(left.size()!=right.size())return false;for(int i=0;i<left.size();i++)if(!equalOutside(left.get(i),right.get(i),path+"/"+i,invalid))return false;return true;}
        return Objects.equals(a,b);
    }
}
