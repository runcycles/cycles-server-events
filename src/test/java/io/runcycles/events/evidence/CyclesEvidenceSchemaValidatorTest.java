package io.runcycles.events.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CyclesEvidenceSchemaValidatorTest {

    private static final String SERVER_ID = "https://cycles.example.com/v1";

    private final ObjectMapper mapper = new ObjectMapper();
    private final CyclesEvidenceSchemaValidator validator = new CyclesEvidenceSchemaValidator();

    @ParameterizedTest
    @EnumSource(EvidenceArtifactType.class)
    void acceptsEveryConformingArtifactPayload(EvidenceArtifactType type) {
        assertThatCode(() -> validator.validatePayload(type, validPayload(type)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingNestedRequiredFieldsAndUnknownProperties() {
        ObjectNode invalid = validPayload(EvidenceArtifactType.RESERVE);
        ((ObjectNode) invalid.path("request").path("action")).remove("name");
        ((ObjectNode) invalid.path("request")).put("unattested_extension", true);

        assertThatThrownBy(() -> validator.validatePayload(EvidenceArtifactType.RESERVE, invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cycles-evidence-v0.2")
                .hasMessageContaining("/request");
    }

    @Test
    void rejectsProtocolImpossibleNonDryReserveDenial() {
        ObjectNode invalid = validPayload(EvidenceArtifactType.RESERVE);
        ((ObjectNode) invalid.path("response")).put("decision", "DENY");
        ((ObjectNode) invalid.path("response")).remove("reservation_id");

        assertThatThrownBy(() -> validator.validatePayload(EvidenceArtifactType.RESERVE, invalid))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDryRunReserveThatClaimsAReservation() {
        ObjectNode invalid = validPayload(EvidenceArtifactType.RESERVE);
        ((ObjectNode) invalid.path("request")).put("dry_run", true);

        assertThatThrownBy(() -> validator.validatePayload(EvidenceArtifactType.RESERVE, invalid))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validatesCompletedEnvelopeAndRejectsArtifactPayloadMismatch() throws Exception {
        EnvelopeSigner signer = new EnvelopeSigner();
        EvidenceSigningKey key = new LocalEvidenceSigningKey(signer, "", "", true);
        CyclesEvidenceEnvelopeBuilder builder = new CyclesEvidenceEnvelopeBuilder(
                new CyclesEvidenceCanonicalizer(), key);
        ObjectNode envelope = builder.build(
                EvidenceArtifactType.RESERVE,
                SERVER_ID,
                1810000000100L,
                "0af7651916cd43dd8448eb211c80319c",
                validPayload(EvidenceArtifactType.RESERVE)).envelope();

        assertThatCode(() -> validator.validateEnvelope(envelope)).doesNotThrowAnyException();

        envelope.put("artifact_type", "decide");
        assertThatThrownBy(() -> validator.validateEnvelope(envelope))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evidence envelope");
    }

    @Test
    void rejectsNullArtifactType() {
        assertThatThrownBy(() -> validator.validatePayload(null, mapper.createObjectNode()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("artifact type");
    }

    @Test
    void rejectsNullPayload() {
        assertThatThrownBy(() -> validator.validatePayload(EvidenceArtifactType.RESERVE, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required");
    }

    private static ObjectNode validPayload(EvidenceArtifactType type) {
        ObjectNode payload = new ObjectMapper().createObjectNode();
        switch (type) {
            case DECIDE -> {
                putDecisionRequest(payload.putObject("request"), "d1");
                payload.putObject("response").put("decision", "ALLOW");
            }
            case RESERVE -> {
                putDecisionRequest(payload.putObject("request"), "r1");
                ObjectNode response = payload.putObject("response");
                response.put("decision", "ALLOW");
                response.putArray("affected_scopes").add("tenant:acme");
                response.put("reservation_id", "res_1");
            }
            case COMMIT -> {
                payload.put("reservation_id", "res_1");
                ObjectNode request = payload.putObject("request");
                request.put("idempotency_key", "c1");
                putAmount(request.putObject("actual"), 750);
                ObjectNode response = payload.putObject("response");
                response.put("status", "COMMITTED");
                putAmount(response.putObject("charged"), 750);
            }
            case RELEASE -> {
                payload.put("reservation_id", "res_1");
                payload.putObject("request").put("idempotency_key", "x1");
                ObjectNode response = payload.putObject("response");
                response.put("status", "RELEASED");
                putAmount(response.putObject("released"), 1000);
            }
            case ERROR -> {
                payload.put("endpoint", "POST /v1/reservations");
                payload.put("http_status", 409);
                ObjectNode response = payload.putObject("response");
                response.put("error", "BUDGET_EXCEEDED");
                response.put("message", "Budget exceeded");
                response.put("request_id", "req_1");
            }
        }
        return payload;
    }

    private static void putDecisionRequest(ObjectNode request, String idempotencyKey) {
        request.put("idempotency_key", idempotencyKey);
        request.putObject("subject").put("tenant", "acme");
        ObjectNode action = request.putObject("action");
        action.put("kind", "model.call");
        action.put("name", "test-model");
        putAmount(request.putObject("estimate"), 1000);
    }

    private static void putAmount(ObjectNode amount, long value) {
        amount.put("unit", "USD_MICROCENTS");
        amount.put("amount", value);
    }
}
