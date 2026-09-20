package dev.aisentinel.core.scoring.lifecycle;

import dev.aisentinel.core.scoring.artifact.CandidateScorerProvenance;
import dev.aisentinel.core.scoring.shadow.AcceptedCandidateIdentity;

import java.util.Locale;
import java.util.Objects;

/**
 * Exact identity used for champion/challenger governance binding.
 * <p>
 * {@code CONFIGURATION FINGERPRINT != ARTIFACT DIGEST}<br>
 * {@code CHAMPION DESIGNATION != PRODUCTION SCORER WIRING}
 */
public final class ModelLifecycleIdentity {

    private final ModelLifecycleIdentityKind kind;
    private final String scorerId;
    private final String scorerVersion;
    private final String artifactId;
    private final String verifiedDigestHex;
    private final String configurationFingerprintSha256Hex;

    private ModelLifecycleIdentity(
        ModelLifecycleIdentityKind kind,
        String scorerId,
        String scorerVersion,
        String artifactId,
        String verifiedDigestHex,
        String configurationFingerprintSha256Hex
    ) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.scorerId = ModelLifecycleCanonical.requireLifecycleToken(scorerId, "scorerId");
        this.scorerVersion = ModelLifecycleCanonical.requireLifecycleToken(scorerVersion, "scorerVersion");
        this.artifactId = ModelLifecycleCanonical.requireLifecycleToken(artifactId, "artifactId");
        this.verifiedDigestHex = ModelLifecycleCanonical.requireSha256Hex(verifiedDigestHex, "verifiedDigestHex");
        this.configurationFingerprintSha256Hex = ModelLifecycleCanonical.requireSha256Hex(
            configurationFingerprintSha256Hex, "configurationFingerprintSha256Hex");
        if (this.verifiedDigestHex.equals(this.configurationFingerprintSha256Hex)
            && kind == ModelLifecycleIdentityKind.ARTIFACT_BACKED) {
            // allowed only when accidental equality; fingerprint and digest are distinct concepts even if equal
        }
    }

    public static ModelLifecycleIdentity fromAccepted(AcceptedCandidateIdentity accepted) {
        Objects.requireNonNull(accepted, "accepted");
        return new ModelLifecycleIdentity(
            ModelLifecycleIdentityKind.ARTIFACT_BACKED,
            accepted.scorerId(),
            accepted.scorerVersion(),
            accepted.artifactId(),
            accepted.verifiedDigestHex(),
            accepted.configurationFingerprintSha256Hex()
        );
    }

    public static ModelLifecycleIdentity fromProvenance(CandidateScorerProvenance provenance) {
        Objects.requireNonNull(provenance, "provenance");
        return new ModelLifecycleIdentity(
            ModelLifecycleIdentityKind.ARTIFACT_BACKED,
            provenance.scorerId(),
            provenance.scorerVersion(),
            provenance.artifactId(),
            provenance.verifiedDigestHex(),
            provenance.configurationFingerprintSha256Hex()
        );
    }

    /**
     * Designates a non-artifact reference champion. Digest is a deterministic
     * content hash of the designation fields — not an artifact SHA-256.
     */
    public static ModelLifecycleIdentity designatedReference(
        String scorerId,
        String scorerVersion,
        String configurationFingerprintSha256Hex
    ) {
        String normalizedScorerId = ModelLifecycleCanonical.requireLifecycleToken(scorerId, "scorerId");
        String normalizedScorerVersion = ModelLifecycleCanonical.requireLifecycleToken(scorerVersion, "scorerVersion");
        String fingerprint = ModelLifecycleCanonical.requireSha256Hex(
            configurationFingerprintSha256Hex, "configurationFingerprintSha256Hex");
        StringBuilder material = new StringBuilder();
        ModelLifecycleCanonical.appendField(material, "kind", ModelLifecycleIdentityKind.DESIGNATED_REFERENCE.name());
        ModelLifecycleCanonical.appendField(material, "scorerId", normalizedScorerId);
        ModelLifecycleCanonical.appendField(material, "scorerVersion", normalizedScorerVersion);
        ModelLifecycleCanonical.appendField(material, "configurationFingerprintSha256Hex", fingerprint);
        String digest = ModelLifecycleCanonical.sha256Hex(material.toString());
        return new ModelLifecycleIdentity(
            ModelLifecycleIdentityKind.DESIGNATED_REFERENCE,
            normalizedScorerId,
            normalizedScorerVersion,
            "designated-reference",
            digest,
            fingerprint
        );
    }

    public boolean matches(ModelLifecycleIdentity other) {
        if (other == null) {
            return false;
        }
        return kind == other.kind
            && scorerId.equals(other.scorerId)
            && scorerVersion.equals(other.scorerVersion)
            && artifactId.equals(other.artifactId)
            && verifiedDigestHex.equalsIgnoreCase(other.verifiedDigestHex)
            && configurationFingerprintSha256Hex.equalsIgnoreCase(other.configurationFingerprintSha256Hex);
    }

    public ModelLifecycleIdentityKind kind() {
        return kind;
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

    /** Deterministic stable key for history / binding. */
    public String bindingKey() {
        return kind.name() + "|" + scorerId + "|" + scorerVersion + "|" + artifactId + "|"
            + verifiedDigestHex + "|" + configurationFingerprintSha256Hex;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ModelLifecycleIdentity that)) {
            return false;
        }
        return matches(that);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, scorerId, scorerVersion, artifactId,
            verifiedDigestHex.toLowerCase(Locale.ROOT),
            configurationFingerprintSha256Hex.toLowerCase(Locale.ROOT));
    }

}
