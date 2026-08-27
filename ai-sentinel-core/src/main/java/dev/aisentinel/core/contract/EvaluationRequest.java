package dev.aisentinel.core.contract;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Platform-neutral evaluation input. Free of servlet/Spring types.
 * <p>
 * Enforcement/evaluation mode is <strong>not</strong> a request field — it remains trusted
 * server configuration so callers cannot disable enforcement.
 *
 * @param contractVersion       must be {@link EvaluationContract#CONTRACT_VERSION}
 * @param correlationId         caller correlation / request id
 * @param timestampEpochMillis  request timestamp (epoch millis)
 * @param method                HTTP method
 * @param path                  request path/URI
 * @param identityKey           opaque identity key (local adapters typically supply identity hash)
 * @param identityType          optional identity kind (e.g. {@code HASH}, {@code ANONYMOUS})
 * @param tenantId              optional tenant
 * @param sessionId             optional opaque session reference
 * @param sessionPresent        whether a session exists
 * @param sessionNew            whether the session is new for this request
 * @param remoteAddress         optional client address as known to the adapter
 * @param headers               normalized lowercase header map (secrets not required)
 * @param parameters            first-value query/form parameters
 * @param attributes            bounded application attributes
 * @param trustSignals          optional known trust signal weights in {@code [0,1]}
 */
public record EvaluationRequest(
    int contractVersion,
    String correlationId,
    long timestampEpochMillis,
    String method,
    String path,
    String identityKey,
    String identityType,
    String tenantId,
    String sessionId,
    boolean sessionPresent,
    boolean sessionNew,
    String remoteAddress,
    Map<String, String> headers,
    Map<String, String> parameters,
    Map<String, String> attributes,
    Map<String, Double> trustSignals
) {
    public EvaluationRequest {
        headers = copyStringMap(headers);
        parameters = copyStringMap(parameters);
        attributes = copyStringMap(attributes);
        trustSignals = copyTrustMap(trustSignals);
        EvaluationRequestValidator.validateConstructed(
            contractVersion, correlationId, timestampEpochMillis, method, path, identityKey,
            identityType, tenantId, sessionId, remoteAddress, headers, parameters, attributes, trustSignals);
    }

    public static Builder builder() {
        return new Builder();
    }

    private static Map<String, String> copyStringMap(Map<String, String> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, String> copy = new LinkedHashMap<>(input.size());
        for (Map.Entry<String, String> e : input.entrySet()) {
            Objects.requireNonNull(e.getKey(), "map key");
            copy.put(e.getKey(), e.getValue());
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, Double> copyTrustMap(Map<String, Double> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Double> copy = new LinkedHashMap<>(input.size());
        for (Map.Entry<String, Double> e : input.entrySet()) {
            Objects.requireNonNull(e.getKey(), "trust key");
            copy.put(e.getKey(), e.getValue());
        }
        return Collections.unmodifiableMap(copy);
    }

    /** Fluent builder; calls compact constructor validation on {@link #build()}. */
    public static final class Builder {
        private int contractVersion = EvaluationContract.CONTRACT_VERSION;
        private String correlationId;
        private long timestampEpochMillis = System.currentTimeMillis();
        private String method = "GET";
        private String path = "/";
        private String identityKey = "";
        private String identityType;
        private String tenantId;
        private String sessionId;
        private boolean sessionPresent;
        private boolean sessionNew;
        private String remoteAddress;
        private Map<String, String> headers = Map.of();
        private Map<String, String> parameters = Map.of();
        private Map<String, String> attributes = Map.of();
        private Map<String, Double> trustSignals = Map.of();

        public Builder contractVersion(int contractVersion) {
            this.contractVersion = contractVersion;
            return this;
        }

        public Builder correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        public Builder timestampEpochMillis(long timestampEpochMillis) {
            this.timestampEpochMillis = timestampEpochMillis;
            return this;
        }

        public Builder method(String method) {
            this.method = method;
            return this;
        }

        public Builder path(String path) {
            this.path = path;
            return this;
        }

        public Builder identityKey(String identityKey) {
            this.identityKey = identityKey;
            return this;
        }

        public Builder identityType(String identityType) {
            this.identityType = identityType;
            return this;
        }

        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder sessionPresent(boolean sessionPresent) {
            this.sessionPresent = sessionPresent;
            return this;
        }

        public Builder sessionNew(boolean sessionNew) {
            this.sessionNew = sessionNew;
            return this;
        }

        public Builder remoteAddress(String remoteAddress) {
            this.remoteAddress = remoteAddress;
            return this;
        }

        public Builder headers(Map<String, String> headers) {
            this.headers = headers;
            return this;
        }

        public Builder parameters(Map<String, String> parameters) {
            this.parameters = parameters;
            return this;
        }

        public Builder attributes(Map<String, String> attributes) {
            this.attributes = attributes;
            return this;
        }

        public Builder trustSignals(Map<String, Double> trustSignals) {
            this.trustSignals = trustSignals;
            return this;
        }

        public EvaluationRequest build() {
            return new EvaluationRequest(
                contractVersion, correlationId, timestampEpochMillis, method, path, identityKey,
                identityType, tenantId, sessionId, sessionPresent, sessionNew, remoteAddress,
                headers, parameters, attributes, trustSignals);
        }
    }

    /** Normalize header names to lowercase English for deterministic maps. */
    public static String normalizeHeaderName(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }
}
