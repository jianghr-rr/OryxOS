package io.github.oryxlabs.oryxos.web.openapi;

import io.github.oryxlabs.oryxos.web.api.ApiResponse;
import io.github.oryxlabs.oryxos.web.api.ContractDtos;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Projects the actual public records and their Swagger constraints into OpenAPI 3.0 schemas. Record
 * components are required by default, including explicitly nullable values. Maps remain open;
 * records are closed. No contract resource or parallel DTO definition is loaded.
 */
public final class PublicSchemas {
  private final Map<String, io.swagger.v3.oas.models.media.Schema> components =
      new LinkedHashMap<>();

  public Map<String, io.swagger.v3.oas.models.media.Schema> components() {
    return new LinkedHashMap<>(components);
  }

  public io.swagger.v3.oas.models.media.Schema<?> envelope(Class<?> data) {
    String name =
        (data == ContractDtos.ErrorDetail.class ? "Error" : data.getSimpleName()) + "Envelope";
    if (!components.containsKey(name))
      components.put(name, record(ApiResponse.class, Map.of("T", data)));
    return ref(name);
  }

  public io.swagger.v3.oas.models.media.Schema<?> type(Type type) {
    if (type instanceof ParameterizedType p) {
      if (p.getRawType() == List.class)
        return new io.swagger.v3.oas.models.media.ArraySchema()
            .items(type(p.getActualTypeArguments()[0]));
      if (p.getRawType() == Map.class)
        return new io.swagger.v3.oas.models.media.Schema<>()
            .type("object")
            .additionalProperties(true);
    }
    if (type instanceof Class<?> c) {
      if (c == String.class) return new io.swagger.v3.oas.models.media.Schema<>().type("string");
      if (c == UUID.class)
        return new io.swagger.v3.oas.models.media.Schema<>().type("string").format("uuid");
      if (c == Instant.class)
        return new io.swagger.v3.oas.models.media.Schema<>().type("string").format("date-time");
      if (c == Boolean.class || c == boolean.class)
        return new io.swagger.v3.oas.models.media.Schema<>().type("boolean");
      if (c == Integer.class || c == Long.class || c == int.class || c == long.class)
        return new io.swagger.v3.oas.models.media.Schema<>().type("integer");
      if (c.isRecord()) {
        var a = c.getAnnotation(Schema.class);
        if (a != null && a.schemaResolution() == Schema.SchemaResolution.INLINE)
          return record(c, Map.of());
        String name = c.getSimpleName();
        if (!components.containsKey(name)) {
          // Reserve before traversing nested records; recursive DTOs remain references.
          components.put(name, new io.swagger.v3.oas.models.media.Schema<>());
          components.put(name, record(c, Map.of()));
        }
        return ref(name);
      }
    }
    throw new IllegalArgumentException("Undocumented public type: " + type);
  }

  private io.swagger.v3.oas.models.media.Schema<?> record(Class<?> c, Map<String, Type> bindings) {
    var schema =
        new io.swagger.v3.oas.models.media.Schema<>().type("object").additionalProperties(false);
    var required = new ArrayList<String>();
    for (var field : c.getRecordComponents()) {
      Type fieldType = field.getGenericType();
      if (fieldType instanceof TypeVariable<?> variable)
        fieldType = bindings.get(variable.getName());
      var property = type(fieldType);
      var metadata = field.getAccessor().getAnnotation(Schema.class);
      apply(property, metadata);
      var array = field.getAccessor().getAnnotation(ArraySchema.class);
      if (array != null) {
        if (array.uniqueItems()) property.setUniqueItems(true);
        if (array.minItems() > 0 && array.minItems() != Integer.MAX_VALUE)
          property.setMinItems(array.minItems());
        apply(property.getItems(), array.schema());
      }
      schema.addProperty(field.getName(), property);
      if (metadata == null || metadata.requiredMode() != Schema.RequiredMode.NOT_REQUIRED)
        required.add(field.getName());
    }
    schema.setRequired(required);
    // Swagger sorts setRequired; publish the stable declaration order of the actual record.
    schema.getRequired().sort(Comparator.comparingInt(required::indexOf));
    return schema;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  public static void apply(io.swagger.v3.oas.models.media.Schema schema, Schema a) {
    if (a == null) return;
    if (!a.description().isEmpty()) schema.setDescription(a.description());
    if (!a.format().isEmpty()) schema.setFormat(a.format());
    if (a.nullable()) schema.setNullable(true);
    if (a.minLength() > 0) schema.setMinLength(a.minLength());
    if (a.maxLength() != Integer.MAX_VALUE) schema.setMaxLength(a.maxLength());
    if (!a.minimum().isEmpty()) schema.setMinimum(new BigDecimal(a.minimum()));
    if (!a.maximum().isEmpty()) schema.setMaximum(new BigDecimal(a.maximum()));
    if (!a.defaultValue().isEmpty()) schema.setDefault(value(schema, a.defaultValue()));
    if (a.allowableValues().length > 0)
      schema.setEnum(Arrays.stream(a.allowableValues()).map(v -> value(schema, v)).toList());
  }

  private static Object value(io.swagger.v3.oas.models.media.Schema<?> schema, String value) {
    return switch (schema.getType()) {
      case "boolean" -> Boolean.valueOf(value);
      case "integer" -> Long.valueOf(value);
      default -> value;
    };
  }

  public static io.swagger.v3.oas.models.media.Schema<?> ref(String name) {
    return new io.swagger.v3.oas.models.media.Schema<>().$ref("#/components/schemas/" + name);
  }
}
