namespace AI.Sentinel.AspNetCore.Contract;

/// <summary>
/// Client-side validation aligned with Java EvaluationResponseValidator.
/// Failures are contract/transport errors, not risk classifications.
/// </summary>
public static class EvaluationResponseValidator
{
    private static readonly HashSet<string> KnownActions = Enum.GetNames<EnforcementAction>()
        .ToHashSet(StringComparer.Ordinal);

    public static void Validate(EvaluationResponse response, string? expectedCorrelationId)
    {
        ArgumentNullException.ThrowIfNull(response);

        if (response.ContractVersion != EvaluationContractConstants.ContractVersion)
        {
            throw new EvaluationContractException(
                "unsupported response contractVersion: " + response.ContractVersion);
        }

        if (expectedCorrelationId != null
            && !expectedCorrelationId.Equals(response.CorrelationId, StringComparison.Ordinal))
        {
            throw new EvaluationContractException("response correlationId mismatch");
        }

        if (string.IsNullOrWhiteSpace(response.CorrelationId))
        {
            throw new EvaluationContractException("response correlationId is required");
        }

        if (!KnownActions.Contains(response.Action.ToString()))
        {
            throw new EvaluationContractException("unknown response action");
        }

        if (response.AnomalyScore is { } anomaly && !IsFinite(anomaly))
        {
            throw new EvaluationContractException("response anomalyScore must be finite or null");
        }

        if (response.PolicyScore is { } policy && !IsFinite(policy))
        {
            throw new EvaluationContractException("response policyScore must be finite or null");
        }

        var proceedExpected = response.Action is EnforcementAction.ALLOW or EnforcementAction.MONITOR;
        if (response.IsRemoteEvaluationFailure)
        {
            ValidateRemoteFailureShape(response);
        }
        else if (response.Proceed != proceedExpected)
        {
            throw new EvaluationContractException("response proceed inconsistent with action");
        }

        if (response.EvaluationStatuses.Count > 64)
        {
            throw new EvaluationContractException("too many evaluationStatuses");
        }

        foreach (var status in response.EvaluationStatuses)
        {
            if (string.IsNullOrWhiteSpace(status))
            {
                throw new EvaluationContractException("evaluationStatuses entry must be non-blank");
            }

            if (status.Length > EvaluationContractConstants.MaxStringLength)
            {
                throw new EvaluationContractException("evaluationStatuses entry exceeds max length");
            }

            if (!status.Equals(status.ToUpperInvariant(), StringComparison.Ordinal))
            {
                throw new EvaluationContractException("evaluationStatuses must be uppercase codes");
            }
        }

        if (response.Factors.Count > 64)
        {
            throw new EvaluationContractException("too many factors");
        }

        if (response.Advice != null)
        {
            if (string.IsNullOrWhiteSpace(response.Advice.Code))
            {
                throw new EvaluationContractException("advice.code is required");
            }

            var present = response.Factors.Select(f => f.Code).ToHashSet(StringComparer.Ordinal);
            foreach (var linked in response.Advice.LinkedFactorCodes)
            {
                if (!present.Contains(linked))
                {
                    throw new EvaluationContractException(
                        "advice linkedFactorCodes must reference present factors");
                }
            }
        }
    }

    private static void ValidateRemoteFailureShape(EvaluationResponse response)
    {
        if (response.Action != EnforcementAction.ALLOW
            || !response.Proceed
            || response.AnomalyScore != null
            || response.PolicyScore != null
            || response.StartupGraceActive
            || response.Factors.Count > 0
            || response.Advice != null)
        {
            throw new EvaluationContractException(
                "REMOTE_EVALUATION_FAILURE response must be fail-open ALLOW with no scores, factors, or advice");
        }
    }

    private static bool IsFinite(double value) => !double.IsNaN(value) && !double.IsInfinity(value);
}
