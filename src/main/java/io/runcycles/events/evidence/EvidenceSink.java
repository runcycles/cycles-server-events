package io.runcycles.events.evidence;

import io.runcycles.events.evidence.CyclesEvidenceEnvelopeBuilder.BuiltEvidenceEnvelope;

/**
 * Where a freshly built, signed CyclesEvidence envelope goes — the seam
 * between envelope production and persistence/serving.
 *
 * <p>The worker depends only on this interface. The current default
 * ({@link LoggingEvidenceSink}) just records that an envelope was produced;
 * the content-addressed object store (persistence slice) and the
 * {@code GET /v1/evidence/{id}} surface (serving slice) drop in behind it
 * without touching the worker.
 */
public interface EvidenceSink {

    /** Accept a built, signed envelope for persistence/serving. */
    void accept(BuiltEvidenceEnvelope envelope);
}
