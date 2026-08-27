package dev.aisentinel.core.contract;

import dev.aisentinel.core.decision.RiskDecision;
import dev.aisentinel.core.decision.RiskExplanation;
import dev.aisentinel.core.decision.RiskFactor;
import dev.aisentinel.core.decision.SecurityAdvice;
import dev.aisentinel.core.http.HttpRequestView;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Bidirectional mapping between platform-neutral contract types and the local engine boundary.
 */
public final class EvaluationContractMapper {

    private EvaluationContractMapper() {
    }

    /**
     * Build an {@link EvaluationRequest} from an existing {@link HttpRequestView} for local adapters.
     * Does not require Authorization/Cookie; copies headers present on the view for feature parity.
     */
    public static EvaluationRequest fromHttpRequestView(HttpRequestView view,
                                                        String identityKey,
                                                        String correlationId) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(identityKey, "identityKey");
        String corr = correlationId == null || correlationId.isBlank()
            ? UUID.randomUUID().toString()
            : correlationId;

        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        Enumeration<String> names = view.getHeaderNames();
        if (names != null) {
            while (names.hasMoreElements()) {
                String name = names.nextElement();
                if (name == null) {
                    continue;
                }
                String normalized = EvaluationRequest.normalizeHeaderName(name);
                if (normalized.isBlank() || headers.containsKey(normalized)) {
                    continue;
                }
                if (headers.size() >= EvaluationContract.MAX_HEADERS) {
                    break;
                }
                String value = view.getHeader(name);
                if (value == null) {
                    continue;
                }
                if (value.length() > EvaluationContract.MAX_STRING_LENGTH) {
                    value = value.substring(0, EvaluationContract.MAX_STRING_LENGTH);
                }
                headers.put(normalized, value);
            }
        }

        LinkedHashMap<String, String> parameters = new LinkedHashMap<>();
        Map<String, String[]> raw = view.getParameterMap();
        if (raw != null) {
            for (Map.Entry<String, String[]> e : raw.entrySet()) {
                if (parameters.size() >= EvaluationContract.MAX_PARAMETERS) {
                    break;
                }
                String key = e.getKey();
                if (key == null || key.isBlank()) {
                    continue;
                }
                String[] values = e.getValue();
                String first = values != null && values.length > 0 && values[0] != null ? values[0] : "";
                if (first.length() > EvaluationContract.MAX_STRING_LENGTH) {
                    first = first.substring(0, EvaluationContract.MAX_STRING_LENGTH);
                }
                parameters.put(key, first);
            }
        }

        String path = view.getRequestURI() != null ? view.getRequestURI() : "/";
        if (!path.startsWith("/")) {
            path = "/" + path;
        }

        return EvaluationRequest.builder()
            .correlationId(corr)
            .timestampEpochMillis(System.currentTimeMillis())
            .method(view.getMethod() != null ? view.getMethod() : "GET")
            .path(path)
            .identityKey(identityKey)
            .identityType(identityKey.isBlank() ? "ANONYMOUS" : "HASH")
            .sessionId(view.getSessionId())
            .sessionPresent(view.hasSession())
            .sessionNew(view.isNewSession())
            .remoteAddress(view.getRemoteAddr())
            .headers(headers)
            .parameters(parameters)
            .build();
    }

    public static HttpRequestView toHttpRequestView(EvaluationRequest request) {
        return new ContractHttpRequestView(request);
    }

    public static EvaluationResponse toResponse(EvaluationRequest request,
                                                RiskDecision decision,
                                                boolean proceed) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(decision, "decision");
        List<String> statuses = decision.evaluationStatuses().stream()
            .map(Enum::name)
            .sorted()
            .toList();
        String endpoint = decision.features() != null ? decision.features().endpoint() : "";
        RiskExplanation explanation = decision.explanation() != null
            ? decision.explanation()
            : RiskExplanation.empty();
        return new EvaluationResponse(
            EvaluationContract.CONTRACT_VERSION,
            request.correlationId(),
            decision.action(),
            statuses,
            finiteOrNull(decision.anomalyScore()),
            finiteOrNull(decision.policyScore()),
            decision.startupGraceActive(),
            proceed,
            endpoint == null ? "" : endpoint,
            toContractFactors(explanation),
            toContractAdvice(explanation.advice())
        );
    }

    private static List<ContractRiskFactor> toContractFactors(RiskExplanation explanation) {
        List<ContractRiskFactor> out = new ArrayList<>(explanation.factors().size());
        for (RiskFactor factor : explanation.factors()) {
            out.add(new ContractRiskFactor(
                factor.code().name(),
                factor.category().name(),
                factor.severity().name(),
                factor.contribution(),
                factor.confidence(),
                sanitizeEvidenceRef(factor.evidenceRef()),
                factor.explanation(),
                factor.source()
            ));
        }
        return List.copyOf(out);
    }

    private static ContractSecurityAdvice toContractAdvice(SecurityAdvice advice) {
        if (advice == null) {
            return null;
        }
        List<String> linked = advice.linkedFactorCodes().stream().map(Enum::name).toList();
        return new ContractSecurityAdvice(
            advice.code().name(),
            advice.priority().name(),
            advice.reason(),
            linked,
            advice.humanReviewRecommended()
        );
    }

    /** Keep evidence references as closed codes/feature names; strip obvious secret-bearing prefixes. */
    static String sanitizeEvidenceRef(String evidenceRef) {
        if (evidenceRef == null || evidenceRef.isBlank()) {
            return "";
        }
        String lower = evidenceRef.toLowerCase(Locale.ROOT);
        if (lower.contains("authorization") || lower.contains("cookie") || lower.contains("token")) {
            return "redacted";
        }
        return evidenceRef.length() > EvaluationContract.MAX_STRING_LENGTH
            ? evidenceRef.substring(0, EvaluationContract.MAX_STRING_LENGTH)
            : evidenceRef;
    }

    static Double finiteOrNull(double score) {
        return Double.isFinite(score) ? score : null;
    }
}
