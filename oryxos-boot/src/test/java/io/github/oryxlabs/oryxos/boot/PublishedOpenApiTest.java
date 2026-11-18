package io.github.oryxlabs.oryxos.boot;

import static io.github.oryxlabs.oryxos.boot.support.RuntimeProcess.JSON;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import io.github.oryxlabs.oryxos.boot.support.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Tag("us5-published-contract")
@Tag("us5-http")
class PublishedOpenApiTest {
  @TempDir Path root;

  record NewPublicRecord(
      UUID newField,
      @io.swagger.v3.oas.annotations.media.Schema(nullable = true) String nullableField,
      @io.swagger.v3.oas.annotations.media.Schema(
              requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED)
          List<io.github.oryxlabs.oryxos.web.api.ContractDtos.Identity> optionalIdentities) {}

  @Test
  void metadataFollowsActualRecordsAndGenericEnvelope() {
    var generator = new io.github.oryxlabs.oryxos.web.openapi.PublicSchemas();
    generator.envelope(NewPublicRecord.class);
    var schemas = generator.components();
    var shape = schemas.get("NewPublicRecord");
    assertEquals(
        Set.of("newField", "nullableField", "optionalIdentities"), shape.getProperties().keySet());
    assertEquals(List.of("newField", "nullableField"), shape.getRequired());
    assertEquals(
        "uuid",
        ((io.swagger.v3.oas.models.media.Schema<?>) shape.getProperties().get("newField"))
            .getFormat());
    assertEquals(
        true,
        ((io.swagger.v3.oas.models.media.Schema<?>) shape.getProperties().get("nullableField"))
            .getNullable());
    assertEquals(
        "#/components/schemas/NewPublicRecord",
        ((io.swagger.v3.oas.models.media.Schema<?>)
                schemas.get("NewPublicRecordEnvelope").getProperties().get("data"))
            .get$ref());
    assertTrue(schemas.containsKey("Identity"));
  }

  @Test
  void generatedOpenApiDescribesAllOperationsAndPublicSchemas() throws Exception {
    var expected =
        JSON.readTree(
            Path.of("../specs/001-single-node-agent-runtime/contracts/http-api.json").toFile());
    try (var f = new PersistentFixture(root);
        var p = new RuntimeProcess(root, "serve")) {
      var actual = p.call("GET", "/v3/api-docs", null, 200);
      var diffs = new ArrayList<String>();
      assertEquals("3.0.3", actual.path("openapi").asText());
      assertEquals(19, actual.path("paths").findValues("operationId").size());
      assertEquals(44, actual.path("components").path("schemas").size());
      var evidence =
          Path.of(System.getProperty("oryxos.test.evidenceDir")).resolve("published-openapi.json");
      Files.createDirectories(evidence.getParent());
      Files.writeString(evidence, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(actual));
      expected
          .path("paths")
          .fields()
          .forEachRemaining(
              path ->
                  path.getValue()
                      .fields()
                      .forEachRemaining(
                          method -> {
                            if (!Set.of("get", "post", "put", "delete", "patch")
                                .contains(method.getKey())) return;
                            String at = method.getKey().toUpperCase() + " " + path.getKey();
                            var want = method.getValue();
                            var have =
                                actual
                                    .path("paths")
                                    .path("/api/v1" + path.getKey())
                                    .path(method.getKey());
                            if (have.isMissingNode()) {
                              diffs.add(at + " missing operation");
                              return;
                            }
                            if (!want.path("operationId").equals(have.path("operationId")))
                              diffs.add(at + " operationId differs");
                            var expectedStatuses = new TreeSet<String>();
                            want.path("responses")
                                .fieldNames()
                                .forEachRemaining(expectedStatuses::add);
                            var actualStatuses = new TreeSet<String>();
                            have.path("responses")
                                .fieldNames()
                                .forEachRemaining(actualStatuses::add);
                            if (!expectedStatuses.equals(actualStatuses))
                              diffs.add(at + " response status set differs");
                            if (want.path("requestBody").path("required").asBoolean()
                                != have.path("requestBody").path("required").asBoolean())
                              diffs.add(at + " request body required flag differs");
                            want.path("responses")
                                .fields()
                                .forEachRemaining(
                                    response -> {
                                      var desired = resolve(response.getValue(), expected);
                                      var found =
                                          resolve(
                                              have.path("responses").path(response.getKey()),
                                              actual);
                                      if (found.isMissingNode()) {
                                        diffs.add(at + " missing HTTP " + response.getKey());
                                        return;
                                      }
                                      var schema =
                                          desired
                                              .path("content")
                                              .path("application/json")
                                              .path("schema");
                                      if (!schema.isMissingNode()
                                          && !schema.equals(
                                              found
                                                  .path("content")
                                                  .path("application/json")
                                                  .path("schema")))
                                        diffs.add(
                                            at
                                                + " "
                                                + response.getKey()
                                                + " response schema differs");
                                    });
                            var request =
                                want.path("requestBody")
                                    .path("content")
                                    .path("application/json")
                                    .path("schema");
                            if (!request.isMissingNode()
                                && !request.equals(
                                    have.path("requestBody")
                                        .path("content")
                                        .path("application/json")
                                        .path("schema"))) diffs.add(at + " request schema differs");
                            for (var parameter : want.path("parameters")) {
                              var param = resolve(parameter, expected);
                              JsonNode match = null;
                              for (var candidate : have.path("parameters")) {
                                var c = resolve(candidate, actual);
                                if (c.path("in").equals(param.path("in"))
                                    && c.path("name").equals(param.path("name"))) match = c;
                              }
                              if (match == null
                                  || !param.path("schema").equals(match.path("schema"))
                                  || param.path("required").asBoolean()
                                      != match.path("required").asBoolean())
                                diffs.add(
                                    at + " parameter differs: " + param.path("name").asText());
                            }
                          }));
      expected
          .path("components")
          .path("schemas")
          .fields()
          .forEachRemaining(
              e -> {
                var got = actual.path("components").path("schemas").path(e.getKey());
                if (got.isMissingNode()) diffs.add("schema missing: " + e.getKey());
                else if (!semantic(e.getValue()).equals(semantic(got)))
                  diffs.add("schema differs: " + e.getKey());
              });
      Files.writeString(
          evidence.resolveSibling("published-openapi-diff.json"),
          JSON.writerWithDefaultPrettyPrinter().writeValueAsString(diffs));
      assertTrue(diffs.isEmpty(), String.join("\n", diffs));
      assertEquals(0, f.models.get());
    }
  }

  static JsonNode resolve(JsonNode value, JsonNode doc) {
    return value.has("$ref") ? doc.at(value.path("$ref").asText().substring(1)) : value;
  }

  static JsonNode semantic(JsonNode node) {
    if (node.isObject()) {
      var n = JSON.createObjectNode();
      node.fields()
          .forEachRemaining(
              e -> {
                if (!Set.of("description", "title", "example", "examples").contains(e.getKey()))
                  n.set(e.getKey(), semantic(e.getValue()));
              });
      return n;
    }
    if (node.isArray()) {
      var n = JSON.createArrayNode();
      node.forEach(e -> n.add(semantic(e)));
      return n;
    }
    return node;
  }
}
