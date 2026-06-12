package io.runcycles.events.evidence;

/**
 * The CyclesEvidence artifact types (cycles-evidence-v0.1). The wire name is
 * both the {@code artifact_type} value and the single key the envelope's
 * {@code payload} object must carry — the NORMATIVE pairing the builder
 * enforces by construction.
 */
public enum EvidenceArtifactType {

    DECIDE("decide"),
    RESERVE("reserve"),
    COMMIT("commit"),
    RELEASE("release"),
    ERROR("error");

    private final String wireName;

    EvidenceArtifactType(String wireName) {
        this.wireName = wireName;
    }

    /** The lowercase token used for both {@code artifact_type} and the
     *  {@code payload} key. */
    public String wireName() {
        return wireName;
    }
}
