package io.runcycles.events.evidence;

import static io.runcycles.events.logging.LogSanitizer.safe;

import io.runcycles.events.evidence.CyclesEvidenceEnvelopeBuilder.BuiltEvidenceEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * The active {@link EvidenceSink}: persists each built, signed envelope to the
 * durable {@link EvidenceStore}. {@code @Primary} so the worker uses it over
 * the placeholder {@link LoggingEvidenceSink}.
 */
@Component
@Primary
public class StoringEvidenceSink implements EvidenceSink {

    private static final Logger LOG = LoggerFactory.getLogger(StoringEvidenceSink.class);

    private final EvidenceStore store;

    public StoringEvidenceSink(EvidenceStore store) {
        this.store = store;
    }

    @Override
    public void accept(BuiltEvidenceEnvelope envelope) {
        store.put(envelope.evidenceId(), envelope.json());
        LOG.debug("stored CyclesEvidence envelope evidence_id={}", safe(envelope.evidenceId()));
    }
}
