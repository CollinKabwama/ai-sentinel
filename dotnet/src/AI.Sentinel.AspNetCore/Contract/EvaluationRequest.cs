using System.Text.Json.Serialization;

namespace AI.Sentinel.AspNetCore.Contract;

/// <summary>Platform-neutral evaluation input matching the frozen Step-8 wire contract.</summary>
public sealed class EvaluationRequest
{
    [JsonPropertyName("contractVersion")]
    public int ContractVersion { get; set; } = EvaluationContractConstants.ContractVersion;

    [JsonPropertyName("correlationId")]
    public string CorrelationId { get; set; } = string.Empty;

    [JsonPropertyName("timestampEpochMillis")]
    public long TimestampEpochMillis { get; set; }

    [JsonPropertyName("method")]
    public string Method { get; set; } = "GET";

    [JsonPropertyName("path")]
    public string Path { get; set; } = "/";

    [JsonPropertyName("identityKey")]
    public string IdentityKey { get; set; } = string.Empty;

    [JsonPropertyName("identityType")]
    public string? IdentityType { get; set; }

    [JsonPropertyName("tenantId")]
    public string? TenantId { get; set; }

    [JsonPropertyName("sessionId")]
    public string? SessionId { get; set; }

    [JsonPropertyName("sessionPresent")]
    public bool SessionPresent { get; set; }

    [JsonPropertyName("sessionNew")]
    public bool SessionNew { get; set; }

    [JsonPropertyName("remoteAddress")]
    public string? RemoteAddress { get; set; }

    [JsonPropertyName("headers")]
    public Dictionary<string, string> Headers { get; set; } = new();

    [JsonPropertyName("parameters")]
    public Dictionary<string, string> Parameters { get; set; } = new();

    [JsonPropertyName("attributes")]
    public Dictionary<string, string> Attributes { get; set; } = new();

    [JsonPropertyName("trustSignals")]
    public Dictionary<string, double> TrustSignals { get; set; } = new();
}
