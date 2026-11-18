package io.github.oryxlabs.oryxos.tool.validation;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.networknt.schema.*;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.tool.ToolRegistryPort.Validation;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ToolArgumentValidator {
    private static final int LIMIT=65536;
    private final ObjectMapper json=new ObjectMapper(JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64).maxStringLength(LIMIT).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Map<String,JsonSchema> schemas=new ConcurrentHashMap<>();
    public void register(String text){schema(text);}
    public boolean matches(String schema,Object value){return schema(schema).validate(json.valueToTree(value)).isEmpty();}
    public Validation validate(String schema,String arguments) {
        JsonNode node;
        try {node=parse(arguments);if(!node.isObject())throw new IllegalArgumentException();}
        catch(Exception e){return new Validation(Map.of(),false,Set.of(""),"Arguments must be a valid JSON object within 64 KiB and depth 64");}
        Map<String,Object> values=immutable(json.convertValue(node,new TypeReference<Map<String,Object>>(){}));
        var errors=schema(schema).validate(node);var paths=new TreeSet<String>();
        for(var error:errors) {
            String path=error.getInstanceLocation().toString();
            if("required".equals(error.getType())&&error.getProperty()!=null)path+="/"+escape(error.getProperty());
            paths.add(path);
        }
        // Paths are structural evidence; never copy NetworkNT messages that can quote the rejected value.
        return new Validation(values,true,paths,paths.isEmpty()?null:"Arguments do not match the tool schema; invalid fields: "+String.join(", ",paths));
    }
    private JsonSchema schema(String text) {
        return schemas.computeIfAbsent(text,key->{
            try {
                JsonNode node=parse(key);if(!node.isObject())throw new IllegalArgumentException();checkReferences(node);
                var version=node.path("$schema").asText("").contains("draft-07")?SpecVersion.VersionFlag.V7:SpecVersion.VersionFlag.V202012;
                var factory=JsonSchemaFactory.getInstance(version,b->b.schemaLoaders(loaders->loaders.values(list->{list.clear();list.add(iri->{throw OryxError.configuration("External schema loading is forbidden");});})));
                var config=SchemaValidatorsConfig.builder().pathType(PathType.JSON_POINTER).typeLoose(false).build();
                var schema=factory.getSchema(node,config);schema.initializeValidators();return schema;
            } catch(RuntimeException e){throw OryxError.configuration("tool schema (offline, 64 KiB, depth 64)");}
            catch(java.io.IOException e){throw OryxError.configuration("tool schema JSON");}
        });
    }
    private JsonNode parse(String text) throws java.io.IOException {
        if(text==null||text.getBytes(StandardCharsets.UTF_8).length>LIMIT)throw new IllegalArgumentException();
        return json.readTree(text);
    }
    private void checkReferences(JsonNode node) {
        if(node.isObject())node.fields().forEachRemaining(field->{
            String key=field.getKey();JsonNode value=field.getValue();
            if(Set.of("$ref","$dynamicRef","$recursiveRef","$id").contains(key)&&(!value.isTextual()||!value.asText().startsWith("#")))throw OryxError.configuration("external schema reference");
            if(key.equals("$schema")&&!Set.of("https://json-schema.org/draft/2020-12/schema","http://json-schema.org/draft-07/schema#","https://json-schema.org/draft-07/schema#").contains(value.asText()))throw OryxError.configuration("unsupported schema dialect");
            checkReferences(value);
        });
        else if(node.isArray())node.forEach(this::checkReferences);
    }
    private static String escape(String text){return text.replace("~","~0").replace("/","~1");}
    private static Map<String,Object> immutable(Map<String,Object> values){var out=new LinkedHashMap<String,Object>();values.forEach((k,v)->out.put(k,freeze(v)));return Collections.unmodifiableMap(out);}
    @SuppressWarnings("unchecked") private static Object freeze(Object v){if(v instanceof Map<?,?> m)return immutable((Map<String,Object>)m);if(v instanceof List<?> l)return l.stream().map(ToolArgumentValidator::freeze).toList();return v;}
}
