package io.github.oryxlabs.oryxos.web.openapi;

import io.github.oryxlabs.oryxos.web.api.ContractDtos;
import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.parameters.*;
import io.swagger.v3.oas.models.responses.*;
import java.util.*;
import org.springdoc.core.customizers.*;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.HandlerMethod;

/** MVC supplies operations and request bindings; public records supply all payload properties. */
@Component
public final class RuntimeOpenApi implements OperationCustomizer, OpenApiCustomizer {
  private final PublicSchemas schemas = new PublicSchemas();

  @Override
  public synchronized Operation customize(Operation operation, HandlerMethod handler) {
    var contract = handler.getMethodAnnotation(HttpContract.class);
    if (contract == null) return operation;
    var responses = new ApiResponses();
    var data = schemas.envelope(contract.data());
    var error = schemas.envelope(ContractDtos.ErrorDetail.class);
    responses.addApiResponse(Integer.toString(contract.success()), response(data));
    for (int code : contract.errors())
      responses.addApiResponse(Integer.toString(code), response(error));
    for (int code : contract.executionErrors())
      responses.addApiResponse(
          Integer.toString(code), response(new ComposedSchema().oneOf(List.of(data, error))));
    for (int code : contract.dataErrors())
      responses.addApiResponse(Integer.toString(code), response(data));
    operation.setResponses(responses);

    var parameters = new ArrayList<Parameter>();
    for (var argument : handler.getMethod().getParameters()) {
      var body = argument.getAnnotation(org.springframework.web.bind.annotation.RequestBody.class);
      if (body != null) {
        operation.setRequestBody(
            new io.swagger.v3.oas.models.parameters.RequestBody()
                .required(body.required())
                .content(content(schemas.type(argument.getParameterizedType()))));
        continue;
      }
      var query = argument.getAnnotation(RequestParam.class);
      var path = argument.getAnnotation(PathVariable.class);
      var header = argument.getAnnotation(RequestHeader.class);
      if (query == null && path == null && header == null) continue;
      String name = query != null ? query.value() : path != null ? path.value() : header.value();
      if (name.isEmpty()) name = argument.getName();
      String in = query != null ? "query" : path != null ? "path" : "header";
      boolean required =
          query != null ? query.required() : path != null ? path.required() : header.required();
      var schema = schemas.type(argument.getParameterizedType());
      var metadata = argument.getAnnotation(io.swagger.v3.oas.annotations.Parameter.class);
      if (metadata != null) PublicSchemas.apply(schema, metadata.schema());
      parameters.add(new Parameter().name(name).in(in).required(required).schema(schema));
    }
    operation.setParameters(parameters.isEmpty() ? null : parameters);
    return operation;
  }

  @Override
  public synchronized void customise(OpenAPI openApi) {
    openApi.setOpenapi("3.0.3");
    // Stable client-generation IDs follow the registered route, not overloaded Java method names.
    openApi
        .getPaths()
        .forEach(
            (path, item) -> {
              if (path.startsWith("/api/v1/"))
                item.readOperationsMap()
                    .forEach(
                        (verb, operation) ->
                            operation.setOperationId(
                                verb.name().toLowerCase(Locale.ROOT)
                                    + "_"
                                    + path.substring("/api/v1".length())
                                        .replace("/", "_")
                                        .replace("{", "")
                                        .replace("}", "")));
            });
    if (openApi.getComponents() == null) openApi.setComponents(new Components());
    openApi.getComponents().setSchemas(schemas.components());
  }

  private static Content content(Schema<?> schema) {
    return new Content().addMediaType("application/json", new MediaType().schema(schema));
  }

  private static ApiResponse response(Schema<?> schema) {
    return new ApiResponse().description("Public runtime response").content(content(schema));
  }
}
