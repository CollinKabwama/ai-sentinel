package dev.aisentinel.core.contract;

import dev.aisentinel.core.identity.IdentityRiskSignalKeys;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic validation for {@link EvaluationRequest}. Failures are contract errors,
 * not security-attack classifications.
 */
public final class EvaluationRequestValidator {

    private static final Set<String> KNOWN_TRUST_KEYS = Set.of(
        IdentityRiskSignalKeys.SPARSE_HISTORY,
        IdentityRiskSignalKeys.NEW_SESSION,
        IdentityRiskSignalKeys.IP_DRIFT,
        IdentityRiskSignalKeys.USER_AGENT_DRIFT,
        IdentityRiskSignalKeys.REQUEST_BURST
    );

    private EvaluationRequestValidator() {
    }

    static void validateConstructed(
        int contractVersion,
        String correlationId,
        long timestampEpochMillis,
        String method,
        String path,
        String identityKey,
        String identityType,
        String tenantId,
        String sessionId,
        String remoteAddress,
        Map<String, String> headers,
        Map<String, String> parameters,
        Map<String, String> attributes,
        Map<String, Double> trustSignals
    ) {
        if (contractVersion != EvaluationContract.CONTRACT_VERSION) {
            throw new EvaluationContractException(
                "unsupported contractVersion: " + contractVersion);
        }
        requireNonBlank("correlationId", correlationId);
        requireBounded("correlationId", correlationId);
        rejectControlCharacters("correlationId", correlationId);
        if (timestampEpochMillis < 0L) {
            throw new EvaluationContractException("timestampEpochMillis must be >= 0");
        }
        requireNonBlank("method", method);
        requireBounded("method", method);
        rejectControlCharacters("method", method);
        if (method.chars().anyMatch(Character::isWhitespace)) {
            throw new EvaluationContractException("method must not contain whitespace");
        }
        requireNonBlank("path", path);
        if (path.length() > EvaluationContract.MAX_PATH_LENGTH) {
            throw new EvaluationContractException("path exceeds max length");
        }
        rejectControlCharacters("path", path);
        if (!path.startsWith("/")) {
            throw new EvaluationContractException("path must start with '/'");
        }
        if (identityKey == null) {
            throw new EvaluationContractException("identityKey is required");
        }
        requireBounded("identityKey", identityKey);
        rejectControlCharacters("identityKey", identityKey);
        boolean anonymous = identityType != null
            && "ANONYMOUS".equalsIgnoreCase(identityType.trim());
        if (identityKey.isBlank() && !anonymous) {
            throw new EvaluationContractException(
                "identityKey is required unless identityType is ANONYMOUS");
        }
        optionalBounded("identityType", identityType);
        optionalBounded("tenantId", tenantId);
        optionalBounded("sessionId", sessionId);
        optionalBounded("remoteAddress", remoteAddress);
        optionalRejectControlCharacters("identityType", identityType);
        optionalRejectControlCharacters("tenantId", tenantId);
        optionalRejectControlCharacters("sessionId", sessionId);
        optionalRejectControlCharacters("remoteAddress", remoteAddress);

        validateStringMap("headers", headers, EvaluationContract.MAX_HEADERS, true);
        validateStringMap("parameters", parameters, EvaluationContract.MAX_PARAMETERS, false);
        validateStringMap("attributes", attributes, EvaluationContract.MAX_ATTRIBUTES, false);
        validateTrust(trustSignals);
    }

    private static void validateStringMap(String label, Map<String, String> map, int maxEntries,
                                          boolean normalizeKeysLower) {
        if (map.size() > maxEntries) {
            throw new EvaluationContractException(label + " exceeds max entries (" + maxEntries + ")");
        }
        for (Map.Entry<String, String> e : map.entrySet()) {
            String key = e.getKey();
            if (key.isBlank()) {
                throw new EvaluationContractException(label + " key must be non-blank");
            }
            if (key.length() > EvaluationContract.MAX_STRING_LENGTH) {
                throw new EvaluationContractException(label + " key exceeds max length");
            }
            rejectControlCharacters(label + " key", key);
            if (normalizeKeysLower && !key.equals(key.toLowerCase(Locale.ROOT))) {
                throw new EvaluationContractException("header keys must be lowercase normalized");
            }
            String value = e.getValue();
            if (value == null) {
                throw new EvaluationContractException(label + " value must not be null");
            }
            if (value.length() > EvaluationContract.MAX_STRING_LENGTH) {
                throw new EvaluationContractException(label + " value exceeds max length");
            }
            rejectControlCharacters(label + " value", value);
        }
    }

    private static void validateTrust(Map<String, Double> trustSignals) {
        if (trustSignals.size() > EvaluationContract.MAX_TRUST_SIGNALS) {
            throw new EvaluationContractException("trustSignals exceeds max entries");
        }
        for (Map.Entry<String, Double> e : trustSignals.entrySet()) {
            String key = e.getKey();
            if (!KNOWN_TRUST_KEYS.contains(key)) {
                throw new EvaluationContractException("unknown trustSignals key: " + key);
            }
            Double value = e.getValue();
            if (value == null || !Double.isFinite(value) || value < 0.0 || value > 1.0) {
                throw new EvaluationContractException(
                    "trustSignals value must be finite in [0,1] for key: " + key);
            }
        }
    }

    private static void requireNonBlank(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new EvaluationContractException(field + " is required");
        }
    }

    private static void requireBounded(String field, String value) {
        if (value.length() > EvaluationContract.MAX_STRING_LENGTH) {
            throw new EvaluationContractException(field + " exceeds max length");
        }
    }

    private static void optionalBounded(String field, String value) {
        if (value != null && value.length() > EvaluationContract.MAX_STRING_LENGTH) {
            throw new EvaluationContractException(field + " exceeds max length");
        }
    }

    private static void optionalRejectControlCharacters(String field, String value) {
        if (value != null) {
            rejectControlCharacters(field, value);
        }
    }

    private static void rejectControlCharacters(String field, String value) {
        if (value.chars().anyMatch(Character::isISOControl)) {
            throw new EvaluationContractException(field + " must not contain control characters");
        }
    }
}
