package io.runcycles.events.evidence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.erdtman.jcs.JsonCanonicalizer;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Implements the {@code cycles-evidence-v0.1} normative content-hash and
 * signing-input recipe for CyclesEvidence envelopes.
 *
 * <p>Two derivations, both over RFC 8785 (JCS) canonical bytes:
 * <ul>
 *   <li><b>evidence_id</b> = sha256(JCS(envelope with {@code evidence_id} AND
 *       {@code signature} both set to {@code ""})), lowercase hex.</li>
 *   <li><b>signing input</b> = JCS(envelope with {@code evidence_id} populated
 *       and {@code signature} set to {@code ""}) — the bytes the server's
 *       Ed25519 key signs.</li>
 * </ul>
 *
 * <p>The empty-string sentinel (not field omission) is normative: both fields
 * are PRESENT in the canonical bytes with empty-string values. JCS comes from
 * the erdtman reference implementation, the same author as the JS and Python
 * JCS libraries the Cycles reference fixtures were generated with, so the bytes
 * match those impls — and therefore the APS verifier — byte-for-byte.
 */
@Component
public class CyclesEvidenceCanonicalizer {

    private final ObjectMapper mapper = new ObjectMapper();

    /** Recompute the content-addressed {@code evidence_id} (lowercase sha256 hex). */
    public String computeEvidenceId(ObjectNode envelope) {
        ObjectNode work = envelope.deepCopy();
        work.put("evidence_id", "");
        work.put("signature", "");
        return sha256Hex(canonicalize(work));
    }

    /** The JCS-canonical UTF-8 bytes the server signs: {@code evidence_id}
     *  populated, {@code signature} emptied. */
    public byte[] signingBytes(ObjectNode envelope, String evidenceId) {
        ObjectNode work = envelope.deepCopy();
        work.put("evidence_id", evidenceId);
        work.put("signature", "");
        return canonicalize(work);
    }

    /** RFC 8785 canonical UTF-8 bytes of an arbitrary object node. */
    public byte[] canonicalize(ObjectNode node) {
        requireLosslessJcsNumbers(node, "$");
        try {
            return new JsonCanonicalizer(mapper.writeValueAsString(node)).getEncodedUTF8();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("envelope serialization failed", e);
        } catch (IOException e) {
            throw new IllegalStateException("JCS canonicalization failed", e);
        }
    }

    /**
     * RFC 8785 serializes numbers with ECMAScript/IEEE-754 semantics. Reject a
     * source value when converting it to binary64 would change its mathematical
     * value; silently signing a rounded amount would corrupt audit evidence.
     */
    private static void requireLosslessJcsNumbers(JsonNode node, String path) {
        if (node.isNumber()) {
            double binary64 = node.doubleValue();
            if (!Double.isFinite(binary64)) {
                throw new IllegalArgumentException("evidence number is not finite at " + path);
            }
            BigDecimal original = node.decimalValue();
            BigDecimal jcsValue = BigDecimal.valueOf(binary64);
            if (original.compareTo(jcsValue) != 0) {
                throw new IllegalArgumentException(
                        "evidence number cannot be represented losslessly by RFC 8785 at " + path
                                + ": " + node.asText());
            }
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry ->
                    requireLosslessJcsNumbers(entry.getValue(), path + "." + entry.getKey()));
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                requireLosslessJcsNumbers(node.get(i), path + "[" + i + "]");
            }
        }
    }

    static String sha256Hex(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
