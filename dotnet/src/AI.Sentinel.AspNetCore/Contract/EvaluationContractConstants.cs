namespace AI.Sentinel.AspNetCore.Contract;

/// <summary>Shared bounds and version constants for the platform-neutral evaluation contract (v1).</summary>
public static class EvaluationContractConstants
{
    public const int ContractVersion = 1;
    public const int MaxStringLength = 2048;
    public const int MaxPathLength = 2048;
    public const int MaxHeaders = 64;
    public const int MaxParameters = 128;
    public const int MaxAttributes = 64;
    public const int MaxTrustSignals = 16;
    public const string DefaultEvaluationPath = "/ai-sentinel/v1/evaluation";
}
