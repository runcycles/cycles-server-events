package io.runcycles.events.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.Specification;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.dialect.Dialect;
import com.networknt.schema.keyword.AnnotationKeyword;
import com.networknt.schema.keyword.NonValidationKeyword;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Validates evidence payloads and completed envelopes against the authoritative
 * Cycles Evidence v0.2 OpenAPI 3.1 schema bundled with this service.
 *
 * <p>The document is schema-only but is not itself a JSON Schema root, so this
 * class compiles small JSON Schema 2020-12 wrappers that reference the original
 * components without translating or weakening them.
 */
@Component
@ConditionalOnEvidenceConfigured
public final class CyclesEvidenceSchemaValidator {

    static final String SPEC_RESOURCE = "/spec/cycles-evidence-v0.2.yaml";
    private static final String DIALECT = "https://json-schema.org/draft/2020-12/schema";
    private static final int MAX_REPORTED_ERRORS = 5;
    private static final YAMLMapper YAML_MAPPER = YAMLMapper.builder().build();
    private static final Map<EvidenceArtifactType, String> PAYLOAD_SCHEMAS = Map.of(
            EvidenceArtifactType.DECIDE, "DecidePayload",
            EvidenceArtifactType.RESERVE, "ReservePayload",
            EvidenceArtifactType.COMMIT, "CommitPayload",
            EvidenceArtifactType.RELEASE, "ReleasePayload",
            EvidenceArtifactType.ERROR, "ErrorPayload");

    private final Schema envelopeSchema;
    private final Map<EvidenceArtifactType, Schema> payloadSchemas;

    public CyclesEvidenceSchemaValidator() {
        JsonNode components = loadComponents();
        Dialect openApiSchemaDialect = Dialect.builder(
                        DIALECT,
                        Specification.getDialect(SpecificationVersion.DRAFT_2020_12))
                .keyword(new NonValidationKeyword("components"))
                .keyword(new AnnotationKeyword("example"))
                .build();
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(
                openApiSchemaDialect,
                builder -> builder.schemaRegistryConfig(SchemaRegistryConfig.builder()
                        .formatAssertionsEnabled(true)
                        .build()));
        this.envelopeSchema = compile(registry, components, "CyclesEvidence");
        EnumMap<EvidenceArtifactType, Schema> compiledPayloads = new EnumMap<>(EvidenceArtifactType.class);
        PAYLOAD_SCHEMAS.forEach((type, schemaName) ->
                compiledPayloads.put(type, compile(registry, components, schemaName)));
        this.payloadSchemas = Map.copyOf(compiledPayloads);
    }

    public void validatePayload(EvidenceArtifactType type, JsonNode payload) {
        if (type == null) {
            throw new IllegalArgumentException("evidence artifact type is required");
        }
        validate(payloadSchemas.get(type), payload, type.wireName() + " payload");
    }

    public void validateEnvelope(JsonNode envelope) {
        validate(envelopeSchema, envelope, "evidence envelope");
    }

    private static JsonNode loadComponents() {
        try (InputStream input = CyclesEvidenceSchemaValidator.class.getResourceAsStream(SPEC_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Bundled Cycles Evidence schema is missing: " + SPEC_RESOURCE);
            }
            JsonNode document = YAML_MAPPER.readTree(input);
            JsonNode components = document.path("components");
            if (!components.isObject() || !components.path("schemas").isObject()) {
                throw new IllegalStateException("Bundled Cycles Evidence schema has no components.schemas object");
            }
            return components;
        } catch (IOException invalidSpec) {
            throw new IllegalStateException("Bundled Cycles Evidence schema could not be parsed", invalidSpec);
        }
    }

    private static Schema compile(SchemaRegistry registry, JsonNode components, String componentName) {
        if (!components.path("schemas").has(componentName)) {
            throw new IllegalStateException("Bundled Cycles Evidence schema is missing component: " + componentName);
        }
        ObjectNode wrapper = YAML_MAPPER.createObjectNode();
        wrapper.put("$schema", DIALECT);
        wrapper.put("$id", "urn:runcycles:cycles-evidence:v0.2:" + componentName);
        wrapper.put("$ref", "#/components/schemas/" + componentName);
        wrapper.set("components", components);
        return registry.getSchema(wrapper);
    }

    private static void validate(Schema schema, JsonNode value, String description) {
        if (value == null) {
            throw new IllegalArgumentException(description + " is required");
        }
        List<Error> errors = schema.validate(value);
        if (errors.isEmpty()) {
            return;
        }
        String summary = errors.stream()
                .sorted(Comparator.comparing(error -> error.getInstanceLocation().toString()))
                .limit(MAX_REPORTED_ERRORS)
                .map(error -> error.getInstanceLocation() + " violated " + error.getKeyword())
                .reduce((left, right) -> left + ", " + right)
                .orElse("schema violation");
        if (errors.size() > MAX_REPORTED_ERRORS) {
            summary += ", and " + (errors.size() - MAX_REPORTED_ERRORS) + " more";
        }
        throw new IllegalArgumentException(description + " does not conform to cycles-evidence-v0.2: " + summary);
    }
}
