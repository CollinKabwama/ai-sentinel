package dev.aisentinel.core.scoring.shadow;

import dev.aisentinel.core.scoring.artifact.CandidateScorerProvenance;

import java.util.Objects;

/**
 * Explicit identity binding that an operator may supply only for a candidate
 * whose offline evaluation acceptance status was {@code ACCEPTED}.
 * <p>
 * Presence of this binding is the runtime acceptance gate. Constructing it does
 * not auto-enable shadow scoring; {@link ShadowScoringConfiguration} must still
 * set {@code enabled=true}.
 * <p>
 * {@code ACCEPTANCE != AUTOMATIC SHADOW ENABLEMENT}<br>
 * {@code ACCEPTED CANDIDATE != CHAMPION}
 */
public final class AcceptedCandidateIdentity {

    private final String scorerId;
    private final String scorerVersion;
    private final String artifactId;
    private final String verifiedDigestHex;
    private final String configurationFingerprintSha256Hex;

    public AcceptedCandidateIdentity(
        String scorerId,
        String scorerVersion,
        String artifactId,
        String verifiedDigestHex,
        String configurationFingerprintSha256Hex
    ) {
        this.scorerId = requireNonBlank(scorerId, "scorerId");
        this.scorerVersion = requireNonBlank(scorerVersion, "scorerVersion");
        this.artifactId = requireNonBlank(artifactId, "artifactId");
        this.verifiedDigestHex = requireNonBlank(verifiedDigestHex, "verifiedDigestHex");
        this.configurationFingerprintSha256Hex = requireNonBlank(
            configurationFingerprintSha256Hex, "configurationFingerprintSha256Hex");
    }

    /**
     * Builds an accepted-identity binding from loaded-candidate provenance.
     * Callers must only invoke this when offline acceptance was {@code ACCEPTED}
     * for this exact candidate identity.
     */
    public static AcceptedCandidateIdentity fromAcceptedProvenance(CandidateScorerProvenance provenance) {
        Objects.requireNonNull(provenance, "provenance");
        return new AcceptedCandidateIdentity(
            provenance.scorerId(),
            provenance.scorerVersion(),
            provenance.artifactId(),
            provenance.verifiedDigestHex(),
            provenance.configurationFingerprintSha256Hex()
        );
    }

    public boolean matches(CandidateScorerProvenance provenance) {
        if (provenance == null) {
            return false;
        }
        return scorerId.equals(provenance.scorerId())
            && scorerVersion.equals(provenance.scorerVersion())
            && artifactId.equals(provenance.artifactId())
            && verifiedDigestHex.equalsIgnoreCase(provenance.verifiedDigestHex())
            && configurationFingerprintSha256Hex.equalsIgnoreCase(
                provenance.configurationFingerprintSha256Hex());
    }

    public String scorerId() {
        return scorerId;
    }

    public String scorerVersion() {
        return scorerVersion;
    }

    public String artifactId() {
        return artifactId;
    }

    public String verifiedDigestHex() {
        return verifiedDigestHex;
    }

    public String configurationFingerprintSha256Hex() {
        return configurationFingerprintSha256Hex;
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
        return value;
    }
}
