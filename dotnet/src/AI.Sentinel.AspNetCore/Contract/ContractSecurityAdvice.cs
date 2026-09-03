using System.Text.Json.Serialization;

namespace AI.Sentinel.AspNetCore.Contract;

public sealed class ContractSecurityAdvice
{
    [JsonPropertyName("code")]
    public string Code { get; set; } = string.Empty;

    [JsonPropertyName("priority")]
    public string Priority { get; set; } = string.Empty;

    [JsonPropertyName("reason")]
    public string Reason { get; set; } = string.Empty;

    [JsonPropertyName("linkedFactorCodes")]
    public List<string> LinkedFactorCodes { get; set; } = new();

    [JsonPropertyName("humanReviewRecommended")]
    public bool HumanReviewRecommended { get; set; }
}
