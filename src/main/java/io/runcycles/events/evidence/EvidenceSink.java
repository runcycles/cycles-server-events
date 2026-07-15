package io.runcycles.events.evidence;

import io.runcycles.events.evidence.CyclesEvidenceEnvelopeBuilder.BuiltEvidenceEnvelope;

/**
 * Where a freshly built, signed CyclesEvidence envelope goes — the seam
 * between envelope production and persistence/serving.
 *
 * <p>The worker depends only on this interface. The production default is
 * {@link StoringEvidenceSink}, backed by the configured content-addressed
 * store; {@link LoggingEvidenceSink} remains a non-primary diagnostic sink.
 */
public interface EvidenceSink {

    /** Accept a built, signed envelope for persistence/serving. */
    void accept(BuiltEvidenceEnvelope envelope);
}
