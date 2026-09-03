namespace AI.Sentinel.AspNetCore.Contract;

/// <summary>Synthetic fail-open responses for remote transport failures (mirrors Java EvaluationFailureResponses).</summary>
public static class EvaluationFailureResponses
{
    public const string RemoteEvaluationFailureStatus = "REMOTE_EVALUATION_FAILURE";

    public static EvaluationResponse RemoteFailure(string correlationId)
    {
        return new EvaluationResponse
        {
            ContractVersion = EvaluationContractConstants.ContractVersion,
            CorrelationId = string.IsNullOrWhiteSpace(correlationId) ? "unknown" : correlationId,
            Action = EnforcementAction.ALLOW,
            EvaluationStatuses = new List<string> { RemoteEvaluationFailureStatus },
            AnomalyScore = null,
            PolicyScore = null,
            StartupGraceActive = false,
            Proceed = true,
            Endpoint = string.Empty,
            Factors = new List<ContractRiskFactor>(),
            Advice = null
        };
    }
}
