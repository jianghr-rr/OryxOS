package io.github.oryxlabs.oryxos.memory.mem0;
import io.github.oryxlabs.oryxos.core.config.SafeYaml;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import java.net.URI;
import java.util.*;
public record Mem0Config(URI endpoint,String apiKeyReference,int timeoutSeconds,int searchTopK,double searchThreshold) {
    public Mem0Config {
        if(endpoint==null||!Set.of("http","https").contains(endpoint.getScheme())||endpoint.getHost()==null||endpoint.getRawUserInfo()!=null||endpoint.getQuery()!=null||endpoint.getFragment()!=null||!Set.of("","/").contains(endpoint.getPath())||timeoutSeconds<1||searchTopK<1||!Double.isFinite(searchThreshold)||searchThreshold<0||searchThreshold>1)throw OryxError.configuration("Mem0 endpoint or limits");
    }
    public static Mem0Config from(Map<String,Object> map){return new Mem0Config(URI.create(SafeYaml.text(map.get("endpoint"),"memory.endpoint")),SafeYaml.text(map.get("api_key"),"memory.api_key"),SafeYaml.positive(map.getOrDefault("timeout_seconds",20),"memory.timeout"),SafeYaml.positive(map.getOrDefault("search_top_k",10),"memory.search_top_k"),((Number)map.getOrDefault("search_threshold",0.7)).doubleValue());}
    @Override public String toString(){return "Mem0Config[endpoint and credentials withheld, topK="+searchTopK+"]";}
}
