package io.runcycles.events.evidence;

/**
 * Durable, content-addressed store for signed CyclesEvidence envelopes — the
 * pluggable backend seam behind {@link StoringEvidenceSink}.
 *
 * <p>Keyed by {@code evidence_id} (the envelope's content hash), so a put is
 * idempotent: the same envelope id always carries the same bytes. The default
 * implementation ({@link RedisEvidenceStore}) targets the Redis both services
 * already share; an S3/GCS implementation can replace it via the
 * {@code cycles.evidence.store.backend} config without touching the worker.
 * cycles-server serves these by id at {@code GET /v1/evidence/{id}}.
 */
public interface EvidenceStore {

    /** Persist an envelope's JSON under its content-addressed id. */
    void put(String evidenceId, String envelopeJson);
}
