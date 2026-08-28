using System.Text.Json.Serialization;

namespace AI.Sentinel.AspNetCore.Contract;

/// <summary>Platform-neutral evaluation output matching the frozen Step-8 wire contract.</summary>
public sealed class EvaluationResponse
{
    [JsonPropertyName("contractVersion")]
    public int ContractVersion { get; set; }

    [JsonPropertyName("correlationId")]
    public string CorrelationId { get; set; } = string.Empty;

    [JsonPropertyName("action")]
    [JsonConverter(typeof(StrictEnforcementActionJsonConverter))]
    public EnforcementAction? Action { get; set; }

    [JsonPropertyName("evaluationStatuses")]
    public List<string> EvaluationStatuses { get; set; } = new();

    [JsonPropertyName("anomalyScore")]
    public double? AnomalyScore { get; set; }

    [JsonPropertyName("policyScore")]
    public double? PolicyScore { get; set; }

    [JsonPropertyName("startupGraceActive")]
    public bool StartupGraceActive { get; set; }

    [JsonPropertyName("proceed")]
    public bool Proceed { get; set; }

    [JsonPropertyName("endpoint")]
    public string Endpoint { get; set; } = string.Empty;

    [JsonPropertyName("factors")]
    public List<ContractRiskFactor> Factors { get; set; } = new();

    [JsonPropertyName("advice")]
    public ContractSecurityAdvice? Advice { get; set; }

    public bool IsRemoteEvaluationFailure =>
        EvaluationStatuses.Contains("REMOTE_EVALUATION_FAILURE", StringComparer.Ordinal);
}
