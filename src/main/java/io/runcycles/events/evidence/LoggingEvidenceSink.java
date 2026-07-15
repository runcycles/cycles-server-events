package io.runcycles.events.evidence;

import static io.runcycles.events.logging.LogSanitizer.safe;

import io.runcycles.events.evidence.CyclesEvidenceEnvelopeBuilder.BuiltEvidenceEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Non-primary diagnostic {@link EvidenceSink} that records an envelope build.
 * {@link StoringEvidenceSink} is the production default.
 */
@Component
public class LoggingEvidenceSink implements EvidenceSink {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingEvidenceSink.class);

    @Override
    public void accept(BuiltEvidenceEnvelope envelope) {
        LOG.info("Built CyclesEvidence envelope without persistent store: evidence_id={} envelope_bytes={} sink=logging",
                safe(envelope.evidenceId()), envelope.json().length());
    }
}
