package io.runcycles.events.evidence;

import io.runcycles.events.evidence.CyclesEvidenceEnvelopeBuilder.BuiltEvidenceEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default {@link EvidenceSink}: records that an envelope was built. A
 * placeholder until the persistence slice contributes the real sink (a
 * content-addressed object store), which will take precedence via
 * {@code @Primary} (or replace this class outright).
 */
@Component
public class LoggingEvidenceSink implements EvidenceSink {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingEvidenceSink.class);

    @Override
    public void accept(BuiltEvidenceEnvelope envelope) {
        LOG.info("built CyclesEvidence envelope evidence_id={} ({} bytes) — no store wired yet",
                envelope.evidenceId(), envelope.json().length());
    }
}
