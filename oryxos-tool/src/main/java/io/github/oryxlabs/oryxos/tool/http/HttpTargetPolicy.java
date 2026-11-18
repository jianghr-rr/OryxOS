package io.github.oryxlabs.oryxos.tool.http;

import io.github.oryxlabs.oryxos.core.config.SafeYaml;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import java.net.URI;
import java.util.*;

public final class HttpTargetPolicy {
    public record Target(String scheme,String host,int port) { }
    private final List<Target> targets;
    public HttpTargetPolicy(List<Target> targets){this.targets=List.copyOf(targets);}
    public static HttpTargetPolicy from(Map<String,Object> http){
        var targets=new ArrayList<Target>();
        if(!(http.getOrDefault("allowed_targets",List.of()) instanceof List<?> list))throw OryxError.configuration("tools.http.allowed_targets");
        for(var raw:list){var v=SafeYaml.map(raw,"HTTP target");targets.add(new Target(SafeYaml.text(v.get("scheme"),"HTTP scheme"),SafeYaml.text(v.get("host"),"HTTP host"),SafeYaml.positive(v.get("port"),"HTTP port")));}
        return new HttpTargetPolicy(targets);
    }
    public URI authorize(String value) {
        try {
            URI uri=URI.create(value);String scheme=Objects.toString(uri.getScheme(),"").toLowerCase(Locale.ROOT);
            String host=normalize(uri.getHost());int port=uri.getPort()==-1?(scheme.equals("https")?443:80):uri.getPort();
            if(!Set.of("http","https").contains(scheme)||host.isEmpty()||uri.getRawUserInfo()!=null||uri.getFragment()!=null||port<1||port>65535)throw denied();
            boolean allowed=targets.stream().anyMatch(t->t.scheme().equalsIgnoreCase(scheme)&&t.port()==port&&matches(host,normalize(t.host())));
            if(!allowed)throw denied();return uri;
        }catch(RuntimeException e){throw denied();}
    }
    private static boolean matches(String host,String rule){return rule.startsWith("*.")?host.endsWith(rule.substring(1))&&host.length()>rule.length()-1:host.equals(rule);}
    private static String normalize(String host){if(host==null)return "";String value=host.toLowerCase(Locale.ROOT);return value.endsWith(".")?value.substring(0,value.length()-1):value;}
    private static OryxError denied(){return new OryxError("TARGET_DENIED","HTTP target is not authorized by scheme, host and port");}
}
